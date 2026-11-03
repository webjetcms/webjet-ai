package com.webjetcms.ai.provider.openrouter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webjetcms.ai.AiCancellationToken;
import com.webjetcms.ai.AiCapabilities;
import com.webjetcms.ai.AiErrorCategory;
import com.webjetcms.ai.AiCapabilities.Continuation;
import com.webjetcms.ai.AiCapabilities.Operation;
import com.webjetcms.ai.AiCapabilities.Support;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProvider;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.AiStreamListener;
import com.webjetcms.ai.BinaryContent;
import com.webjetcms.ai.EmbeddingOptions;
import com.webjetcms.ai.EmbeddingRequest;
import com.webjetcms.ai.EmbeddingResponse;
import com.webjetcms.ai.EmbeddingVector;
import com.webjetcms.ai.GeneratedMedia;
import com.webjetcms.ai.image.ImageOptionDefinition;
import com.webjetcms.ai.image.ImageOptions;
import com.webjetcms.ai.ModelInfo;
import com.webjetcms.ai.TokenUsage;
import com.webjetcms.ai.internal.CancellationSupport;
import com.webjetcms.ai.internal.ChatRequestValidator;
import com.webjetcms.ai.internal.ImageOptionValidator;
import com.webjetcms.ai.security.PromptInjectionDefense;
import com.webjetcms.ai.security.PromptInjectionDefense.UntrustedSource;

/**
 * Framework-neutral client for OpenRouter text, embedding, and image APIs.
 * Reuse the provider's pooled HTTP client and close the provider when it is no longer needed.
 */
public final class OpenRouterProvider implements AiProvider {

    /** Stable provider identifier used by {@code AiClient}. */
    public static final String PROVIDER_ID = "openrouter";

    private static final URI DEFAULT_BASE_URI = URI.create("https://openrouter.ai/api/v1/");
    private static final String MODELS_PATH = "models?output_modalities=all";
    private static final String CHAT_COMPLETIONS_PATH = "chat/completions";
    private static final String IMAGES_PATH = "images";
    private static final String EMBEDDINGS_PATH = "embeddings";
    static final String IMAGE_MODELS_CATALOGUE_VERSION = OpenRouterImageOptions.CATALOGUE_VERSION;
    private static final int MAX_CONNECTIONS = 100;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 20;

    private final CloseableHttpClient httpClient;
    private final ObjectMapper mapper;

    /** Creates an OpenRouter provider backed by a reusable, pooled Apache HTTP client. */
    public OpenRouterProvider() {
        this(HttpClients.custom()
            .useSystemProperties()
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableCookieManagement()
            .setMaxConnTotal(MAX_CONNECTIONS)
            .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
            .build(), new ObjectMapper());
    }

    OpenRouterProvider(CloseableHttpClient httpClient, ObjectMapper mapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public List<ModelInfo> listModels(AiProviderConfig config) throws AiProviderException {
        return invokeAtBoundary(config, AiCancellationToken.none(), () -> listModelsInternal(config));
    }

    @Override
    public Map<String, ImageOptionDefinition> imageOptions(String model, AiOperation operation) {
        AiProvider.super.imageOptions(model, operation);
        return OpenRouterImageOptions.definitions(model);
    }

    @Override
    public AiCapabilities capabilities(String model) {
        AiProvider.super.capabilities(model);
        boolean vectorModel = OpenRouterImageOptions.isRecraftVectorModel(model);
        Support generation = vectorModel ? Support.UNSUPPORTED
            : OpenRouterImageOptions.definitions(model).isEmpty() ? Support.UNKNOWN : Support.SUPPORTED;
        Support editing = vectorModel ? Support.UNSUPPORTED : Support.UNKNOWN;
        return new AiCapabilities(Map.of(
            AiOperation.TEXT, new Operation(
                Support.SUPPORTED, Support.UNKNOWN, Support.SUPPORTED, Continuation.NONE),
            AiOperation.CHAT, Operation.unsupported(),
            AiOperation.GENERATE_IMAGE, new Operation(
                Support.SUPPORTED, generation, Support.UNSUPPORTED, Continuation.NONE),
            AiOperation.EDIT_IMAGE, new Operation(
                Support.SUPPORTED, editing, Support.UNSUPPORTED, Continuation.NONE)
        ), Support.SUPPORTED, Support.UNKNOWN);
    }

    /**
     * Loads the catalogue across output modalities and orders models by creation time.
     *
     * @param config credentials, endpoint, and transport settings
     * @return immutable model list, newest first with undated entries last
     * @throws AiProviderException if configuration, transport, or catalogue parsing fails
     */
    private List<ModelInfo> listModelsInternal(AiProviderConfig config) throws AiProviderException {
        validateConfig(config);

        HttpGet request = new HttpGet(endpoint(config, MODELS_PATH));
        configureRequest(request, config, false);

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            String rawResponse = readEntity(response.getEntity());
            ensureSuccessful(statusCode, rawResponse);

            JsonNode root = parseJson(rawResponse, "model catalogue");
            JsonNode data = root.path("data");
            if (data.isArray() == false) {
                throw malformedResponse("OpenRouter model response does not contain a data array.", rawResponse);
            }

            List<ModelInfo> models = new ArrayList<>();
            for (JsonNode model : data) {
                String modelId = textOrNull(model.get("id"));
                if (isBlank(modelId)) continue;
                models.add(new ModelInfo(modelId, modelId, longOrNull(model.get("created"))));
            }
            models.sort(Comparator.comparing(
                ModelInfo::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())
            ));
            return List.copyOf(models);
        } catch (IOException exception) {
            throw transportFailure("Unable to load the OpenRouter model catalogue.", exception);
        }
    }

    @Override
    public AiResponse execute(AiRequest request, AiProviderConfig config) throws AiProviderException {
        return execute(request, config, AiCancellationToken.none());
    }

    @Override
    public AiResponse execute(AiRequest request, AiProviderConfig config, AiCancellationToken token)
        throws AiProviderException {
        return invokeAtBoundary(config, token, () -> executeInternal(request, config, token));
    }

    /**
     * Validates and dispatches text or image generation through the appropriate endpoint.
     * Cancellation aborts this request without closing the shared HTTP client.
     *
     * @param request text or image request to validate and send
     * @param config credentials, endpoint, and transport settings
     * @param token non-null cancellation token for this call
     * @return generated text or images with available usage and finish metadata
     * @throws AiProviderException if validation, transport, or response parsing fails
     */
    private AiResponse executeInternal(
        AiRequest request,
        AiProviderConfig config,
        AiCancellationToken token
    ) throws AiProviderException {
        validateRequest(request);
        validateConfig(config);

        boolean image = request.operation() != AiOperation.TEXT;
        ObjectNode body = image ? buildImageBody(request) : buildChatBody(request, false);
        HttpPost httpRequest = createRequest(config, image ? IMAGES_PATH : CHAT_COMPLETIONS_PATH, body, false);
        try (AiCancellationToken.Registration cancellation = token.onCancel(httpRequest::abort);
            CloseableHttpResponse response = httpClient.execute(httpRequest)) {
            int statusCode = response.getStatusLine().getStatusCode();
            String rawResponse = readEntity(response.getEntity());
            ensureSuccessful(statusCode, rawResponse);
            return image
                ? parseImageResponse(rawResponse, textOrNull(body.get("output_format")), request.model(), statusCode)
                : parseCompletion(rawResponse, request.operation());
        } catch (IOException exception) {
            throw transportFailure(image ? "OpenRouter image request failed." : "OpenRouter request failed.", exception);
        }
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request, AiProviderConfig config)
        throws AiProviderException {
        return invokeAtBoundary(config, AiCancellationToken.none(), () -> embedInternal(request, config));
    }

    /**
     * Requests floating-point embeddings and validates their correspondence to the inputs.
     *
     * @param request input texts, model, and optional output dimensions
     * @param config credentials, endpoint, and transport settings
     * @return embeddings in input order with available token usage
     * @throws AiProviderException if validation, transport, or response parsing fails
     */
    private EmbeddingResponse embedInternal(EmbeddingRequest request, AiProviderConfig config)
        throws AiProviderException {
        EmbeddingOptions options = requireEmbeddingRequest(request);
        validateConfig(config);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        if (options.dimensions() != null) {
            body.put("dimensions", options.dimensions());
        }
        body.put("encoding_format", "float");
        ArrayNode input = body.putArray("input");
        request.inputs().forEach(input::add);

        HttpPost httpRequest = new HttpPost(endpoint(config, EMBEDDINGS_PATH));
        configureRequest(httpRequest, config, false);
        httpRequest.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse response = httpClient.execute(httpRequest)) {
            int statusCode = response.getStatusLine().getStatusCode();
            String rawResponse = readEntity(response.getEntity());
            ensureSuccessful(statusCode, rawResponse);
            return parseEmbeddingResponse(
                rawResponse,
                request.inputs().size(),
                options.dimensions(),
                statusCode
            );
        } catch (IOException exception) {
            throw transportFailure("OpenRouter embedding request failed.", exception);
        }
    }

    @Override
    public AiResponse stream(AiRequest request, AiProviderConfig config, AiStreamListener listener)
        throws AiProviderException {
        return stream(request, config, listener, AiCancellationToken.none());
    }

    @Override
    public AiResponse stream(
        AiRequest request,
        AiProviderConfig config,
        AiStreamListener listener,
        AiCancellationToken token
    ) throws AiProviderException {
        return invokeAtBoundary(config, token, () -> streamInternal(
            request, config, CancellationSupport.listener(PROVIDER_ID, token, listener), token
        ));
    }

    /**
     * Streams a text completion while registering cancellation for the active HTTP request.
     *
     * @param request text request to validate and send
     * @param config credentials, endpoint, and transport settings
     * @param listener non-null recipient of generated text fragments
     * @param token non-null cancellation token for this call
     * @return accumulated text and completion metadata
     * @throws AiProviderException if validation, transport, parsing, or listener delivery fails
     */
    private AiResponse streamInternal(
        AiRequest request,
        AiProviderConfig config,
        AiStreamListener listener,
        AiCancellationToken token
    ) throws AiProviderException {
        validateRequest(request);
        validateConfig(config);
        if (request.operation() != AiOperation.TEXT) {
            throw new AiProviderException(
                PROVIDER_ID,
                "OpenRouter streaming is supported only for text requests."
            ).withCategory(AiErrorCategory.UNSUPPORTED_OPERATION);
        }
        if (listener == null) {
            throw new AiProviderException(PROVIDER_ID, "A stream listener is required.");
        }

        HttpPost httpRequest = createRequest(config, CHAT_COMPLETIONS_PATH, buildChatBody(request, true), true);
        try (AiCancellationToken.Registration cancellation = token.onCancel(httpRequest::abort);
            CloseableHttpResponse response = httpClient.execute(httpRequest)) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw errorResponse(statusCode, readEntity(response.getEntity()));
            }

            HttpEntity entity = response.getEntity();
            if (entity == null) {
                throw malformedResponse("OpenRouter returned an empty streaming response.", "");
            }

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(entity.getContent(), StandardCharsets.UTF_8)
            )) {
                return new OpenRouterSseParser(mapper).parse(reader, listener);
            }
        } catch (IOException exception) {
            throw transportFailure("OpenRouter streaming request failed.", exception);
        }
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }

    private HttpPost createRequest(AiProviderConfig config, String path, ObjectNode body, boolean stream)
        throws AiProviderException {
        HttpPost request = new HttpPost(endpoint(config, path));
        configureRequest(request, config, stream);
        request.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));
        return request;
    }

    /**
     * Builds an image request with a protected prompt, validated options, and any input reference.
     *
     * @param request image-generation or image-edit request
     * @return JSON body for the images endpoint
     * @throws AiProviderException if the prompt, image options, or supplied media are invalid
     */
    ObjectNode buildImageBody(AiRequest request) throws AiProviderException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        if (hasMeaningfulImagePrompt(request) == false) {
            throw new AiProviderException(PROVIDER_ID, "OpenRouter image prompt is required.");
        }
        body.put("prompt", imagePrompt(request));

        Map<String, Object> options = ImageOptionValidator.validate(
            PROVIDER_ID,
            request.model(),
            request.operation(),
            request.imageOptions(),
            OpenRouterImageOptions.definitions(request.model())
        );
        for (Map.Entry<String, Object> option : options.entrySet()) {
            String wireName = ImageOptions.COUNT.equals(option.getKey()) ? "n" : option.getKey();
            putScalar(body, wireName, option.getValue());
        }

        if (request.inputMedia() != null) {
            addInputReference(body, request.inputMedia());
        }
        return body;
    }

    /**
     * Validates embedding indices and vectors, restoring the original input order.
     *
     * @param rawResponse JSON embedding response
     * @param expectedCount number of input texts
     * @param dimensions required vector size, or {@code null} to infer it from the first vector
     * @param statusCode HTTP status retained in parsing and validation failures
     * @return ordered embeddings and available token usage
     * @throws AiProviderException if decoding fails, the payload reports an error, or vectors are invalid
     */
    private EmbeddingResponse parseEmbeddingResponse(
        String rawResponse,
        int expectedCount,
        Integer dimensions,
        int statusCode
    )
        throws AiProviderException {
        JsonNode root = parseJson(rawResponse, "embedding", statusCode);
        throwIfPayloadError(root, rawResponse);
        JsonNode data = root.get("data");
        if (data == null || data.isArray() == false || data.size() != expectedCount) {
            throw malformedResponse(
                statusCode,
                "OpenRouter returned an unexpected number of embeddings.",
                rawResponse
            );
        }

        EmbeddingVector[] ordered = new EmbeddingVector[expectedCount];
        Integer actualDimensions = dimensions;
        for (JsonNode item : data) {
            JsonNode indexNode = item.get("index");
            if (indexNode == null || indexNode.isIntegralNumber() == false || indexNode.canConvertToInt() == false) {
                throw malformedResponse(statusCode, "OpenRouter embedding response has no valid index.", rawResponse);
            }
            int index = indexNode.intValue();
            if (index < 0 || index >= expectedCount || ordered[index] != null) {
                throw malformedResponse(
                    statusCode,
                    "OpenRouter embedding response contains an invalid index.",
                    rawResponse
                );
            }
            JsonNode values = item.get("embedding");
            actualDimensions = requireEmbeddingDimensions(values, actualDimensions, rawResponse, statusCode);
            ordered[index] = parseEmbeddingVector(values, actualDimensions, rawResponse, statusCode);
        }

        for (EmbeddingVector vector : ordered) {
            if (vector == null) {
                throw malformedResponse(
                    statusCode,
                    "OpenRouter embedding response is missing an input index.",
                    rawResponse
                );
            }
        }
        return new EmbeddingResponse(List.of(ordered), parseUsage(root.get("usage")));
    }

    /**
     * Decodes Base64 image entries and accepts only supported raster media types.
     *
     * @param rawResponse JSON image response
     * @param requestedFormat format fallback when an entry omits its media type; may be {@code null}
     * @param model model used to infer a format when the request omits it
     * @param statusCode HTTP status retained in parsing and validation failures
     * @return decoded images and available token usage
     * @throws AiProviderException if the response reports an error or its image data or media types are invalid
     */
    AiResponse parseImageResponse(
        String rawResponse,
        String requestedFormat,
        String model,
        int statusCode
    )
        throws AiProviderException {
        JsonNode root = parseJson(rawResponse, "image", statusCode);
        throwIfPayloadError(root, rawResponse, statusCode);
        JsonNode data = root.get("data");
        if (data == null || data.isArray() == false) {
            throw malformedResponse(
                statusCode,
                "OpenRouter image response does not contain a data array.",
                rawResponse
            );
        }

        List<GeneratedMedia> media = new ArrayList<>();
        for (JsonNode image : data) {
            if (image.isObject() == false) {
                throw malformedResponse(
                    statusCode,
                    "OpenRouter image response contains a non-object data entry.",
                    rawResponse
                );
            }
            JsonNode encodedNode = image.get("b64_json");
            if (encodedNode == null || encodedNode.isNull()) {
                throw malformedResponse(
                    statusCode,
                    "OpenRouter image response entry does not contain Base64 data.",
                    rawResponse
                );
            }
            if (encodedNode.isTextual() == false) {
                throw malformedResponse(
                    statusCode,
                    "OpenRouter image response contains non-text Base64 data.",
                    rawResponse
                );
            }
            String encoded = textOrNull(encodedNode);
            if (isBlank(encoded)) {
                throw malformedResponse(
                    statusCode,
                    "OpenRouter image response contains blank Base64 data.",
                    rawResponse
                );
            }
            JsonNode mediaTypeNode = image.get("media_type");
            if (mediaTypeNode != null && mediaTypeNode.isNull() == false && mediaTypeNode.isTextual() == false) {
                throw malformedResponse(
                    statusCode,
                    "OpenRouter image response contains a non-text media type.",
                    rawResponse
                );
            }
            String mediaType = textOrNull(mediaTypeNode);
            if (isBlank(mediaType)) {
                mediaType = mediaTypeForFormat(requestedFormat, model);
            }
            if (isSupportedGeneratedMediaType(mediaType) == false) {
                throw malformedResponse(
                    statusCode,
                    "OpenRouter generated media has an unsupported image media type.",
                    rawResponse
                );
            }
            try {
                media.add(new GeneratedMedia(Base64.getDecoder().decode(encoded), mediaType));
            } catch (IllegalArgumentException exception) {
                throw new AiProviderException(
                    PROVIDER_ID,
                    statusCode,
                    "OpenRouter generated image contains invalid Base64 data.",
                    rawResponse,
                    false,
                    exception
                );
            }
        }
        if (media.isEmpty()) {
            throw malformedResponse(
                statusCode,
                "OpenRouter image response does not contain image data.",
                rawResponse
            );
        }
        return new AiResponse(null, media, parseUsage(root.get("usage")), null);
    }

    /**
     * Requires a nonempty vector and checks its size against any expected dimension count.
     *
     * @param values embedding array to inspect
     * @param expected required size, or {@code null} to accept the observed size
     * @param rawResponse original response retained for diagnostics
     * @param statusCode HTTP status retained in validation failures
     * @return validated vector size
     * @throws AiProviderException if the vector is missing, empty, or has the wrong size
     */
    private int requireEmbeddingDimensions(
        JsonNode values,
        Integer expected,
        String rawResponse,
        int statusCode
    ) throws AiProviderException {
        int actual = values != null && values.isArray() ? values.size() : 0;
        if (actual < 1) {
            throw malformedResponse(
                statusCode,
                "OpenRouter embedding response contains an empty vector.",
                rawResponse
            );
        }
        if (expected != null && actual != expected) {
            throw malformedResponse(
                statusCode,
                "OpenRouter returned " + actual + " embedding dimensions, expected " + expected + ".",
                rawResponse
            );
        }
        return actual;
    }

    /**
     * Converts a JSON embedding array into an immutable vector of finite floats.
     *
     * @param values embedding array to decode
     * @param dimensions required vector size
     * @param rawResponse original response retained for diagnostics
     * @param statusCode HTTP status retained in validation failures
     * @return validated embedding vector
     * @throws AiProviderException if the size or any numeric value is invalid
     */
    private EmbeddingVector parseEmbeddingVector(
        JsonNode values,
        int dimensions,
        String rawResponse,
        int statusCode
    )
        throws AiProviderException {
        if (values == null || values.isArray() == false || values.size() != dimensions) {
            int actual = values != null && values.isArray() ? values.size() : 0;
            throw malformedResponse(
                statusCode,
                "OpenRouter returned " + actual + " embedding dimensions, expected " + dimensions + ".",
                rawResponse
            );
        }
        float[] vector = new float[dimensions];
        for (int index = 0; index < dimensions; index++) {
            JsonNode value = values.get(index);
            if (value.isNumber() == false) {
                throw malformedResponse(
                    statusCode,
                    "OpenRouter embedding response contains a non-numeric value.",
                    rawResponse
                );
            }
            float parsedValue = value.floatValue();
            if (Float.isFinite(parsedValue) == false) {
                throw malformedResponse(
                    statusCode,
                    "OpenRouter embedding response contains a non-finite value.",
                    rawResponse
                );
            }
            vector[index] = parsedValue;
        }
        return new EmbeddingVector(vector);
    }

    /**
     * Builds a Chat Completions payload with protected messages and optional streaming.
     * Stateless text requests use this endpoint; {@link AiOperation#CHAT} is unsupported.
     *
     * @param request request whose instructions, text, and media form the messages
     * @param stream whether to request streaming output
     * @return JSON body for the Chat Completions endpoint
     * @throws AiProviderException if supplied input media is empty
     */
    ObjectNode buildChatBody(AiRequest request, boolean stream) throws AiProviderException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        body.set("messages", buildMessages(request));
        if (request.operation() != AiOperation.TEXT) {
            body.putArray("modalities").add("image").add("text");
        }
        if (stream) body.put("stream", true);
        return body;
    }

    /**
     * Combines security and task instructions with protected image prompt data.
     * Input text is included only for image generation, not editing.
     *
     * @param request image request whose prompt fields are combined
     * @return prepared prompt with blank components omitted
     */
    private static String imagePrompt(AiRequest request) {
        StringBuilder prompt = new StringBuilder();
        appendPrompt(prompt, PromptInjectionDefense.getSecurityInstructions(request.instructions()));
        appendPrompt(prompt, PromptInjectionDefense.getTaskInstructions(request.instructions()));
        if (request.operation() == AiOperation.GENERATE_IMAGE) {
            appendPrompt(
                prompt,
                PromptInjectionDefense.protectUntrustedText(
                    request.inputText(),
                    UntrustedSource.INPUT_TEXT
                ).protectedText()
            );
        }
        appendPrompt(
            prompt,
            PromptInjectionDefense.protectUntrustedText(
                request.userPrompt(),
                UntrustedSource.USER_PROMPT
            ).protectedText()
        );
        return prompt.toString();
    }

    /**
     * Checks for task instructions or meaningful prompt data, excluding {@code inputText} for image editing.
     *
     * @param request image request to inspect
     * @return whether content beyond the security instructions defines an image task
     */
    private static boolean hasMeaningfulImagePrompt(AiRequest request) {
        if (PromptInjectionDefense.hasTaskInstructions(request.instructions())) return true;
        if (request.operation() == AiOperation.GENERATE_IMAGE
            && PromptInjectionDefense.hasUntrustedText(
                request.inputText(),
                UntrustedSource.INPUT_TEXT
            )) {
            return true;
        }
        return PromptInjectionDefense.hasUntrustedText(
            request.userPrompt(),
            UntrustedSource.USER_PROMPT
        );
    }

    private static void appendPrompt(StringBuilder prompt, String value) {
        if (isBlank(value)) return;
        if (prompt.length() > 0) prompt.append("\n\n");
        prompt.append(value);
    }

    /**
     * Adds the supplied image as a Base64 data URL in the image request's input references.
     *
     * @param body destination image request body
     * @param media non-null input image and its media type
     * @throws AiProviderException if the image bytes are empty
     */
    private void addInputReference(ObjectNode body, BinaryContent media) throws AiProviderException {
        byte[] data = media.data();
        if (data.length == 0) {
            throw new AiProviderException(PROVIDER_ID, "OpenRouter input media must not be empty.");
        }

        ArrayNode references = body.putArray("input_references");
        ObjectNode reference = references.addObject();
        reference.put("type", "image_url");
        reference.putObject("image_url").put(
            "url",
            "data:" + media.mediaType() + ";base64," + Base64.getEncoder().encodeToString(data)
        );
    }

    /**
     * Separates trusted system instructions from protected user content and optional input media.
     *
     * @param request request whose operation determines instruction placement
     * @return message array containing user content and any system instructions
     * @throws AiProviderException if supplied input media is empty
     */
    private ArrayNode buildMessages(AiRequest request) throws AiProviderException {
        ArrayNode messages = mapper.createArrayNode();

        String systemInstructions = request.operation() == AiOperation.TEXT
            ? PromptInjectionDefense.hardenSystemInstructions(request.instructions())
            : PromptInjectionDefense.getSecurityInstructions(request.instructions());
        if (isBlank(systemInstructions) == false) {
            ObjectNode systemMessage = mapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemInstructions);
            messages.add(systemMessage);
        }

        ArrayNode userContent = mapper.createArrayNode();
        if (request.operation() == AiOperation.TEXT) {
            addProtectedText(userContent, request.inputText(), UntrustedSource.INPUT_TEXT);
        } else {
            addTextContent(userContent, PromptInjectionDefense.getTaskInstructions(request.instructions()));
            if (request.operation() == AiOperation.GENERATE_IMAGE) {
                addProtectedText(userContent, request.inputText(), UntrustedSource.INPUT_TEXT);
            }
        }
        addTextContent(
            userContent,
            PromptInjectionDefense.protectUntrustedText(request.userPrompt(), UntrustedSource.USER_PROMPT).protectedText()
        );
        if (request.inputMedia() != null) addImageContent(userContent, request.inputMedia());
        if (userContent.isEmpty()) addTextContent(userContent, "Apply the task instructions to the provided data.");

        ObjectNode userMessage = mapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.set("content", userContent);
        messages.add(userMessage);
        return messages;
    }

    private void addProtectedText(ArrayNode content, String value, UntrustedSource source) {
        addTextContent(content, PromptInjectionDefense.protectUntrustedText(value, source).protectedText());
    }

    private void addTextContent(ArrayNode content, String value) {
        if (isBlank(value)) return;

        ObjectNode item = mapper.createObjectNode();
        item.put("type", "text");
        item.put("text", value);
        content.add(item);
    }

    /**
     * Appends an input image as a Base64 data URL within multimodal message content.
     *
     * @param content destination user-content array
     * @param media non-null input image and its media type
     * @throws AiProviderException if the image bytes are empty
     */
    private void addImageContent(ArrayNode content, BinaryContent media) throws AiProviderException {
        byte[] data = media.data();
        if (data.length == 0) {
            throw new AiProviderException(PROVIDER_ID, "OpenRouter input media must not be empty.");
        }

        ObjectNode imageUrl = mapper.createObjectNode();
        imageUrl.put(
            "url",
            "data:" + media.mediaType() + ";base64," + Base64.getEncoder().encodeToString(data)
        );

        ObjectNode item = mapper.createObjectNode();
        item.put("type", "image_url");
        item.set("image_url", imageUrl);
        content.add(item);
    }

    /**
     * Parses the first completion choice and rejects unsuccessful finish reasons.
     *
     * @param rawResponse JSON completion response
     * @param operation operation determining whether generated media is required
     * @return message text, optional generated media, usage, and finish reason
     * @throws AiProviderException if decoding, payload validation, or media parsing fails
     */
    AiResponse parseCompletion(String rawResponse, AiOperation operation) throws AiProviderException {
        JsonNode root = parseJson(rawResponse, "completion");
        throwIfPayloadError(root, rawResponse);

        JsonNode choice = firstChoice(root);
        JsonNode message = choice.path("message");
        if (message.isMissingNode() || message.isObject() == false) {
            throw malformedResponse("OpenRouter completion does not contain a message.", rawResponse);
        }

        String text = extractText(message.get("content"));
        String finishReason = textOrNull(choice.get("finish_reason"));
        ensureSuccessfulFinishReason(finishReason, rawResponse);
        List<GeneratedMedia> media = operation == AiOperation.TEXT
            ? List.of()
            : parseGeneratedMedia(message.get("images"), rawResponse);
        return new AiResponse(text, media, parseUsage(root.get("usage")), finishReason);
    }

    /**
     * Accepts an absent finish reason or {@code stop} and rejects all other explicit reasons.
     *
     * @param finishReason provider completion reason, possibly {@code null} or blank
     * @param rawResponse original response retained for diagnostics
     * @throws AiProviderException if the finish reason indicates unsuccessful generation
     */
    static void ensureSuccessfulFinishReason(String finishReason, String rawResponse)
        throws AiProviderException {
        if (isBlank(finishReason) || "stop".equalsIgnoreCase(finishReason)) {
            return;
        }
        throw new AiProviderException(
            PROVIDER_ID,
            200,
            "OpenRouter generation stopped with finish_reason: " + finishReason,
            rawResponse,
            false
        );
    }

    /**
     * Collects generated images from completion data URLs, ignoring text entries.
     *
     * @param images completion image array
     * @param rawResponse original response retained for diagnostics
     * @return nonempty immutable list of decoded image media
     * @throws AiProviderException if images are absent or any selected data URL is invalid
     */
    private List<GeneratedMedia> parseGeneratedMedia(JsonNode images, String rawResponse)
        throws AiProviderException {
        if (images == null || images.isArray() == false) {
            throw malformedResponse("OpenRouter image response does not contain an images array.", rawResponse);
        }

        List<GeneratedMedia> media = new ArrayList<>();
        for (JsonNode image : images) {
            if (image.has("text")) continue;

            String dataUrl = textOrNull(image.path("image_url").get("url"));
            if (isBlank(dataUrl)) continue;
            media.add(decodeDataUrl(dataUrl, rawResponse));
        }
        if (media.isEmpty()) {
            throw malformedResponse("OpenRouter image response does not contain a valid generated image.", rawResponse);
        }
        return List.copyOf(media);
    }

    /**
     * Decodes a Base64 data URL after validating its encoding and raster media type.
     *
     * @param dataUrl generated image encoded as a data URL
     * @param rawResponse original response retained for diagnostics
     * @return decoded image bytes and media type
     * @throws AiProviderException if the URL, media type, or Base64 data is invalid
     */
    private GeneratedMedia decodeDataUrl(String dataUrl, String rawResponse) throws AiProviderException {
        if (dataUrl.startsWith("data:") == false) {
            throw malformedResponse("OpenRouter generated image is not encoded as a data URL.", rawResponse);
        }

        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            throw malformedResponse("OpenRouter generated image data URL is malformed.", rawResponse);
        }

        String metadata = dataUrl.substring(5, comma);
        String[] metadataParts = metadata.split(";");
        if (metadataParts.length < 2 || "base64".equalsIgnoreCase(metadataParts[metadataParts.length - 1]) == false) {
            throw malformedResponse("OpenRouter generated image data URL is not Base64 encoded.", rawResponse);
        }

        String mediaType = metadataParts[0];
        if (isSupportedGeneratedMediaType(mediaType) == false) {
            throw malformedResponse(
                "OpenRouter generated media has an unsupported image media type.",
                rawResponse
            );
        }

        try {
            return new GeneratedMedia(Base64.getDecoder().decode(dataUrl.substring(comma + 1)), mediaType);
        } catch (IllegalArgumentException exception) {
            throw new AiProviderException(
                PROVIDER_ID,
                -1,
                "OpenRouter generated image contains invalid Base64 data.",
                rawResponse,
                false,
                exception
            );
        }
    }

    private static void putScalar(ObjectNode target, String key, Object value) {
        if (value instanceof Integer integer) target.put(key, integer);
        else if (value instanceof Long number) target.put(key, number);
        else if (value instanceof Boolean bool) target.put(key, bool);
        else target.put(key, String.valueOf(value));
    }

    /**
     * Resolves an image media type from the requested format or the model's known default.
     *
     * @param requestedFormat optional output format
     * @param model model used for format inference
     * @return raster media type, PNG when unspecified, or a binary fallback for unsupported formats
     */
    private static String mediaTypeForFormat(String requestedFormat, String model) {
        String format = requestedFormat;
        if (isBlank(format)) {
            if (OpenRouterImageOptions.isRecraftVectorModel(model)) {
                return "application/octet-stream";
            }
            format = OpenRouterImageOptions.singleOutputFormat(model);
        }
        if (isBlank(format)) return "image/png";
        return switch (format.toLowerCase(java.util.Locale.ROOT)) {
            case "png" -> "image/png";
            case "jpeg", "jpg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    /**
     * Checks the raster media-type allowlist after removing parameters and normalizing case.
     *
     * @param mediaType candidate media type, possibly {@code null}
     * @return whether the type identifies PNG, JPEG, or WebP content
     */
    private static boolean isSupportedGeneratedMediaType(String mediaType) {
        if (isBlank(mediaType)) return false;
        String normalized = mediaType.trim().toLowerCase(java.util.Locale.ROOT);
        int parameterSeparator = normalized.indexOf(';');
        if (parameterSeparator >= 0) {
            normalized = normalized.substring(0, parameterSeparator).trim();
        }
        return switch (normalized) {
            case "image/png", "image/jpeg", "image/jpg", "image/webp" -> true;
            default -> false;
        };
    }

    /**
     * Applies timeouts and trusted headers before setting required authentication and content headers.
     *
     * @param request HTTP request to configure
     * @param config credentials, timeouts, and trusted headers
     * @param stream whether to accept SSE instead of JSON
     */
    private void configureRequest(HttpRequestBase request, AiProviderConfig config, boolean stream) {
        request.setConfig(RequestConfig.custom()
            .setConnectTimeout(config.connectTimeoutMillis())
            .setConnectionRequestTimeout(config.connectTimeoutMillis())
            .setSocketTimeout(config.responseTimeoutMillis())
            .build());

        config.trustedHeaders().forEach(request::setHeader);
        request.setHeader("Authorization", "Bearer " + config.apiKey());
        request.setHeader("Accept", stream ? "text/event-stream" : "application/json");
        if (request instanceof HttpPost) {
            request.setHeader("Content-Type", "application/json; charset=utf-8");
        }
    }

    private void validateConfig(AiProviderConfig config) throws AiProviderException {
        if (config == null || config.isConfigured() == false) {
            throw new AiProviderException(PROVIDER_ID, "OpenRouter API key is not set.");
        }
    }

    /**
     * Validates model and image requirements while rejecting chat and unsupported vector-image operations.
     *
     * @param request request to validate
     * @throws AiProviderException if the operation is unsupported or required inputs are missing or empty
     */
    private void validateRequest(AiRequest request) throws AiProviderException {
        if (request == null) {
            throw new AiProviderException(PROVIDER_ID, "OpenRouter request is required.");
        }
        ChatRequestValidator.rejectUnsupported(PROVIDER_ID, request);
        if (isBlank(request.model())) {
            throw new AiProviderException(PROVIDER_ID, "OpenRouter model is required.");
        }
        if (request.operation() != AiOperation.TEXT
            && OpenRouterImageOptions.isRecraftVectorModel(request.model())) {
            throw new AiProviderException(
                PROVIDER_ID,
                "OpenRouter Recraft vector models return SVG, which is not supported."
            ).withCategory(AiErrorCategory.UNSUPPORTED_OPERATION);
        }
        if (request.operation() == AiOperation.EDIT_IMAGE && request.inputMedia() == null) {
            throw new AiProviderException(PROVIDER_ID, "OpenRouter image editing requires input media.");
        }
        if (request.operation() != AiOperation.TEXT
            && request.inputMedia() != null
            && request.inputMedia().data().length == 0) {
            throw new AiProviderException(PROVIDER_ID, "OpenRouter input media must not be empty.");
        }
        if (request.operation() != AiOperation.TEXT && hasMeaningfulImagePrompt(request) == false) {
            throw new AiProviderException(PROVIDER_ID, "OpenRouter image prompt is required.");
        }
    }

    /**
     * Validates the embedding model and every input before returning the request options.
     *
     * @param request embedding request to validate
     * @return configured embedding options
     * @throws AiProviderException if the request, model, or input texts are missing or blank
     */
    private EmbeddingOptions requireEmbeddingRequest(EmbeddingRequest request) throws AiProviderException {
        if (request == null) {
            throw new AiProviderException(PROVIDER_ID, "OpenRouter embedding request is required.");
        }
        if (isBlank(request.model())) {
            throw new AiProviderException(PROVIDER_ID, "OpenRouter embedding model is required.");
        }
        if (request.inputs().isEmpty()) {
            throw new AiProviderException(PROVIDER_ID, "At least one OpenRouter embedding input is required.");
        }
        for (String input : request.inputs()) {
            if (isBlank(input)) {
                throw new AiProviderException(PROVIDER_ID, "OpenRouter embedding inputs must not be blank.");
            }
        }
        return request.options();
    }

    /**
     * Appends a provider resource path to the configured or default API base URI.
     *
     * @param config configuration containing an optional custom endpoint
     * @param path relative resource path and any query string
     * @return absolute resource URI
     * @throws AiProviderException if the combined URI is invalid
     */
    private URI endpoint(AiProviderConfig config, String path) throws AiProviderException {
        URI baseUri = config.baseUri() == null ? DEFAULT_BASE_URI : config.baseUri();
        String base = baseUri.toString();
        if (base.endsWith("/") == false) base += "/";
        try {
            return URI.create(base + path);
        } catch (IllegalArgumentException exception) {
            throw new AiProviderException(PROVIDER_ID, "Invalid OpenRouter base URI.", exception);
        }
    }

    private JsonNode parseJson(String rawResponse, String responseName) throws AiProviderException {
        return parseJson(rawResponse, responseName, -1);
    }

    /**
     * Decodes a nonblank response body while preserving HTTP diagnostics on parsing failures.
     *
     * @param rawResponse JSON body to decode
     * @param responseName operation name used in failure messages
     * @param statusCode HTTP status, or {@code -1} when unavailable
     * @return decoded JSON tree without response-shape validation
     * @throws AiProviderException if the body is blank or contains invalid JSON
     */
    private JsonNode parseJson(String rawResponse, String responseName, int statusCode)
        throws AiProviderException {
        if (isBlank(rawResponse)) {
            throw malformedResponse(
                statusCode,
                "OpenRouter returned an empty " + responseName + " response.",
                rawResponse
            );
        }
        try {
            return mapper.readTree(rawResponse);
        } catch (JsonProcessingException exception) {
            throw new AiProviderException(
                PROVIDER_ID,
                statusCode,
                "OpenRouter returned invalid JSON for the " + responseName + " response.",
                rawResponse,
                false,
                exception
            );
        }
    }

    private void ensureSuccessful(int statusCode, String rawResponse) throws AiProviderException {
        if (statusCode < 200 || statusCode >= 300) throw errorResponse(statusCode, rawResponse);
    }

    /**
     * Builds a provider failure using upstream error details and status-based retry classification.
     *
     * @param statusCode HTTP or embedded error status, or {@code -1} when unavailable
     * @param rawResponse original response retained for diagnostics
     * @return failure awaiting credential redaction at the public boundary
     */
    private AiProviderException errorResponse(int statusCode, String rawResponse) {
        String errorMessage = null;
        try {
            JsonNode root = mapper.readTree(rawResponse);
            JsonNode error = root.path("error");
            errorMessage = textOrNull(error.path("metadata").get("raw"));
            if (isBlank(errorMessage)) errorMessage = textOrNull(error.get("message"));
            if (isBlank(errorMessage)) errorMessage = textOrNull(root.get("message"));
        } catch (Exception ignored) {
            // The raw body is retained in AiProviderException for host-owned diagnostics.
        }
        if (isBlank(errorMessage)) errorMessage = "OpenRouter request failed.";

        return new AiProviderException(
            PROVIDER_ID,
            statusCode,
            "(" + statusCode + ") " + errorMessage,
            rawResponse,
            isRetryable(statusCode)
        );
    }

    private void throwIfPayloadError(JsonNode root, String rawResponse) throws AiProviderException {
        throwIfPayloadError(root, rawResponse, -1);
    }

    private void throwIfPayloadError(JsonNode root, String rawResponse, int fallbackStatus)
        throws AiProviderException {
        if (root.hasNonNull("error")) {
            int statusCode = errorStatus(root.path("error").get("code"));
            throw errorResponse(statusCode < 0 ? fallbackStatus : statusCode, rawResponse);
        }
    }

    /**
     * Converts an integral or decimal-string error code into a status value.
     *
     * @param code provider error-code node, possibly {@code null}
     * @return parsed integer, or {@code -1} when the code cannot be represented as an integer
     */
    private static int errorStatus(JsonNode code) {
        if (code != null && code.isIntegralNumber() && code.canConvertToInt()) {
            return code.intValue();
        }
        if (code != null && code.isTextual()) {
            try {
                return Integer.parseInt(code.textValue());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private AiProviderException malformedResponse(String message, String rawResponse) {
        return malformedResponse(-1, message, rawResponse);
    }

    private AiProviderException malformedResponse(int statusCode, String message, String rawResponse) {
        return new AiProviderException(PROVIDER_ID, statusCode, message, rawResponse, false);
    }

    private AiProviderException transportFailure(String message, IOException cause) {
        return new AiProviderException(PROVIDER_ID, -1, message, null, true, cause);
    }

    /**
     * Applies cancellation checks and redacts credentials before a failure leaves the provider.
     * Unexpected runtime failures are wrapped as provider exceptions.
     *
     * @param config configuration whose API key and trusted-header values must be redacted
     * @param token non-null cancellation token checked before and after the call
     * @param call operation to run within the provider boundary
     * @param <T> operation result type
     * @return operation result when cancellation has not been observed
     * @throws AiProviderException if cancelled or the operation fails, with configured secrets redacted
     */
    private static <T> T invokeAtBoundary(
        AiProviderConfig config, AiCancellationToken token, CancellationSupport.Call<T> call
    ) throws AiProviderException {
        try {
            return CancellationSupport.call(PROVIDER_ID, token, call);
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (RuntimeException exception) {
            throw new AiProviderException(PROVIDER_ID, "Unexpected OpenRouter provider failure", exception)
                .redactSecrets(config);
        }
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 408 || statusCode == 409 || statusCode == 425 || statusCode == 429
            || statusCode >= 500;
    }

    private static String readEntity(HttpEntity entity) throws IOException {
        return entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
    }

    /**
     * Selects the first choice from a nonempty completion or stream choice array.
     *
     * @param root response object containing choices
     * @return first choice node
     * @throws AiProviderException if the choices array is missing or empty
     */
    static JsonNode firstChoice(JsonNode root) throws AiProviderException {
        JsonNode choices = root.path("choices");
        if (choices.isArray() == false || choices.isEmpty()) {
            throw new AiProviderException(
                PROVIDER_ID,
                -1,
                "OpenRouter response does not contain a choice.",
                root.toString(),
                false
            );
        }
        return choices.get(0);
    }

    /**
     * Concatenates plain content strings and textual parts from a content array.
     *
     * @param content message content, possibly {@code null} or a missing node
     * @return collected text, or an empty string when no supported text is present
     */
    static String extractText(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode item : content) {
                if (item.isTextual()) {
                    text.append(item.asText());
                } else if (item.isObject() && item.path("text").isTextual()) {
                    text.append(item.path("text").asText());
                }
            }
            return text.toString();
        }
        return "";
    }

    /**
     * Reads token totals and retains nested integral usage details under dotted paths.
     *
     * @param usage optional provider usage object
     * @return normalized counts and details, or empty usage when no object is supplied
     */
    static TokenUsage parseUsage(JsonNode usage) {
        if (usage == null || usage.isNull() || usage.isObject() == false) return TokenUsage.EMPTY;

        long inputTokens = usage.path("prompt_tokens").asLong(0);
        long outputTokens = usage.path("completion_tokens").asLong(0);
        long totalTokens = usage.path("total_tokens").asLong(inputTokens + outputTokens);
        Map<String, Long> details = new LinkedHashMap<>();
        collectNumericDetails(usage, "", details);
        details.remove("prompt_tokens");
        details.remove("completion_tokens");
        details.remove("total_tokens");
        return new TokenUsage(inputTokens, outputTokens, totalTokens, details);
    }

    /**
     * Flattens integral usage fields from nested objects into dotted paths.
     *
     * @param node usage object to traverse
     * @param prefix parent path, empty at the root
     * @param details destination for collected path-value pairs
     */
    private static void collectNumericDetails(JsonNode node, String prefix, Map<String, Long> details) {
        node.properties().forEach(entry -> {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isIntegralNumber()) {
                details.put(key, value.asLong());
            } else if (value.isObject()) {
                collectNumericDetails(value, key, details);
            }
        });
    }

    /**
     * Reads an integral or decimal-string catalogue timestamp without failing on unparseable text.
     *
     * @param node timestamp node, possibly {@code null}
     * @return numeric value, or {@code null} when absent or unsupported
     */
    private static Long longOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isTextual()) {
            try {
                return Long.valueOf(node.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
