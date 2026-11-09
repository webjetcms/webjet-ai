package com.webjetcms.ai.provider.openai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webjetcms.ai.AiCancellationToken;
import com.webjetcms.ai.AiLifecycleListener;
import com.webjetcms.ai.AiErrorCategory;
import com.webjetcms.ai.AiCapabilities;
import com.webjetcms.ai.AiCapabilities.Continuation;
import com.webjetcms.ai.AiCapabilities.Operation;
import com.webjetcms.ai.AiCapabilities.Support;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProvider;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.AiResponseDeletion;
import com.webjetcms.ai.AiStreamListener;
import com.webjetcms.ai.BinaryContent;
import com.webjetcms.ai.EmbeddingOptions;
import com.webjetcms.ai.EmbeddingRequest;
import com.webjetcms.ai.EmbeddingResponse;
import com.webjetcms.ai.EmbeddingVector;
import com.webjetcms.ai.GeneratedMedia;
import com.webjetcms.ai.image.ImageOptionDefinition;
import com.webjetcms.ai.image.ImageOptions;
import com.webjetcms.ai.internal.CancellationSupport;
import com.webjetcms.ai.internal.LifecycleSupport;
import com.webjetcms.ai.internal.ResponseDeletionSupport;
import com.webjetcms.ai.internal.ChatRequestValidator;
import com.webjetcms.ai.ModelInfo;
import com.webjetcms.ai.TokenUsage;
import com.webjetcms.ai.security.PromptInjectionDefense;
import com.webjetcms.ai.security.PromptInjectionDefense.UntrustedSource;

/**
 * Framework-neutral REST client for the OpenAI Responses, Images, and Embeddings APIs.
 * One provider instance owns one thread-safe HTTP client and should be reused.
 */
public final class OpenAiProvider implements AiProvider {

    /** Stable provider identifier used by {@code AiClient}. */
    public static final String PROVIDER_ID = "openai";
    static final URI DEFAULT_BASE_URI = URI.create("https://api.openai.com/v1/");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODELS_PATH = "models";
    private static final String RESPONSES_PATH = "responses";
    private static final String IMAGE_GENERATIONS_PATH = "images/generations";
    private static final String IMAGE_EDITS_PATH = "images/edits";
    private static final String EMBEDDINGS_PATH = "embeddings";
    private static final ContentType TEXT_UTF_8 = ContentType.create(
        "text/plain",
        StandardCharsets.UTF_8
    );
    private static final int MAX_CONNECTIONS = 100;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 20;

    private final CloseableHttpClient httpClient;

    /** Creates a provider backed by a reusable Apache HTTP client. */
    public OpenAiProvider() {
        this(HttpClients.custom()
            .useSystemProperties()
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableCookieManagement()
            .setMaxConnTotal(MAX_CONNECTIONS)
            .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
            .build());
    }

    OpenAiProvider(CloseableHttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
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
    public AiResponseDeletion deleteResponse(String responseId, AiProviderConfig config) throws AiProviderException {
        return deleteResponse(responseId, config, AiCancellationToken.none());
    }

    @Override
    public AiResponseDeletion deleteResponse(
        String responseId, AiProviderConfig config, AiCancellationToken token
    ) throws AiProviderException {
        return invokeAtBoundary(config, token, () -> deleteResponseInternal(responseId, config, token));
    }

    /** Deletes one Responses resource, accepting only an explicit confirmation or resource-specific absence. */
    private AiResponseDeletion deleteResponseInternal(
        String responseId, AiProviderConfig config, AiCancellationToken token
    ) throws AiProviderException {
        requireConfigured(config);
        HttpDelete request = new HttpDelete(endpoint(config,
            RESPONSES_PATH + "/" + ResponseDeletionSupport.encodedId(PROVIDER_ID, responseId)));
        prepare(request, config, "application/json");
        try (AiCancellationToken.Registration cancellation = token.onCancel(request::abort);
             CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            String payload;
            try {
                payload = ResponseDeletionSupport.readBody(response.getEntity());
            } catch (ResponseDeletionSupport.ResponseTooLargeException exception) {
                throw new AiProviderException(PROVIDER_ID, status, exception.getMessage(), null,
                    isRetryable(status), exception);
            }
            JsonNode body;
            try {
                body = ResponseDeletionSupport.readObject(payload);
            } catch (IOException exception) {
                if (isSuccessful(status) == false) throw httpError(status, payload, false);
                throw new AiProviderException(PROVIDER_ID, status,
                    "OpenAI did not confirm response deletion", payload, false, exception);
            }
            if (status == 200 && body.has("error") == false
                && body.path("deleted").isBoolean() && body.path("deleted").booleanValue()
                && "response.deleted".equals(body.path("object").asText())
                && body.path("id").isTextual()
                && responseId.equals(body.path("id").asText())) {
                return AiResponseDeletion.DELETED;
            }
            if (status == 404 && isDeletedResponseAbsent(body.path("error"), responseId)) {
                return AiResponseDeletion.ALREADY_ABSENT;
            }
            if (isSuccessful(status) == false) throw httpError(status, payload, false);
            throw new AiProviderException(PROVIDER_ID, status,
                "OpenAI did not confirm response deletion", payload, false);
        } catch (IOException exception) {
            throw transportFailure("OpenAI response deletion was not confirmed", exception);
        }
    }

    /** Recognizes only a response-specific code or an exact missing-response diagnostic for this ID. */
    private static boolean isDeletedResponseAbsent(JsonNode error, String responseId) {
        return "response_not_found".equals(error.path("code").asText())
            || ("invalid_request_error".equals(error.path("type").asText())
                && error.hasNonNull("code") == false
                && ("Response with id '" + responseId + "' not found.").equals(error.path("message").asText()));
    }

    @Override
    public Map<String, ImageOptionDefinition> imageOptions(String model, AiOperation operation) {
        AiProvider.super.imageOptions(model, operation);
        return OpenAiImageOptions.definitions(model, operation);
    }

    @Override
    public AiCapabilities capabilities(String model) {
        AiProvider.super.capabilities(model);
        Support generation = OpenAiImageOptions.definitions(model, AiOperation.GENERATE_IMAGE).isEmpty()
            ? Support.UNKNOWN : Support.SUPPORTED;
        Support editing = "dall-e-3".equals(model) ? Support.UNSUPPORTED
            : OpenAiImageOptions.definitions(model, AiOperation.EDIT_IMAGE).isEmpty()
                ? Support.UNKNOWN : Support.SUPPORTED;
        return new AiCapabilities(Map.of(
            AiOperation.TEXT, new Operation(
                Support.SUPPORTED, Support.UNKNOWN, Support.SUPPORTED, Continuation.NONE),
            AiOperation.CHAT, new Operation(
                Support.SUPPORTED, Support.UNKNOWN, Support.SUPPORTED, Continuation.PROVIDER_STORAGE_REQUIRED),
            AiOperation.GENERATE_IMAGE, new Operation(
                Support.SUPPORTED, generation, Support.UNSUPPORTED, Continuation.NONE),
            AiOperation.EDIT_IMAGE, new Operation(
                Support.SUPPORTED, editing, Support.UNSUPPORTED, Continuation.NONE)
        ), Support.SUPPORTED, Support.UNKNOWN, Support.SUPPORTED);
    }

    /**
     * Fetches and parses the model catalogue using the configured transport.
     *
     * @param config credentials, endpoint, and transport settings
     * @return immutable model list ordered by creation time, newest first
     * @throws AiProviderException if configuration, transport, or catalogue parsing fails
     */
    private List<ModelInfo> listModelsInternal(AiProviderConfig config) throws AiProviderException {
        requireConfigured(config);

        HttpGet request = new HttpGet(endpoint(config, MODELS_PATH));
        String response = executeForResult(request, config, AiCancellationToken.none(), false).body();

        try {
            return parseModels(response);
        } catch (IOException | RuntimeException exception) {
            throw invalidResponse("Could not parse the OpenAI model catalogue", response, exception);
        }
    }

    @Override
    public AiResponse execute(AiRequest request, AiProviderConfig config) throws AiProviderException {
        return execute(request, config, AiCancellationToken.none());
    }

    @Override
    public AiResponse execute(AiRequest request, AiProviderConfig config, AiCancellationToken token)
        throws AiProviderException {
        return execute(request, config, token, AiLifecycleListener.none());
    }

    @Override
    public AiResponse execute(
        AiRequest request, AiProviderConfig config, AiCancellationToken token, AiLifecycleListener lifecycleListener
    ) throws AiProviderException {
        LifecycleSupport lifecycle = new LifecycleSupport(PROVIDER_ID, lifecycleListener);
        return lifecycle.call(config, token, () -> executeInternal(request, config, token, lifecycle));
    }

    /**
     * Validates and dispatches a synchronous text, chat, or image request.
     *
     * @param request operation and inputs to validate and send
     * @param config credentials, endpoint, and transport settings
     * @param token non-null cancellation token for the active HTTP request
     * @param lifecycle per-call lifecycle tracker
     * @return generated content and operation-specific metadata
     * @throws AiProviderException if validation, request creation, transport, or response parsing fails
     */
    private AiResponse executeInternal(
        AiRequest request,
        AiProviderConfig config,
        AiCancellationToken token,
        LifecycleSupport lifecycle
    ) throws AiProviderException {
        requireRequest(request);
        ChatRequestValidator.validate(PROVIDER_ID, request);
        requireConfigured(config);
        requireModel(request);

        try {
            return switch (request.operation()) {
                case TEXT, CHAT -> executeText(request, config, token, lifecycle);
                case GENERATE_IMAGE -> generateImage(request, config, token, lifecycle);
                case EDIT_IMAGE -> editImage(request, config, token, lifecycle);
            };
        } catch (RuntimeException exception) {
            throw new AiProviderException(PROVIDER_ID, "Could not create the OpenAI request", exception);
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
        requireConfigured(config);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", request.model());
        if (options.dimensions() != null) {
            body.put("dimensions", options.dimensions());
        }
        body.put("encoding_format", "float");
        ArrayNode input = body.putArray("input");
        request.inputs().forEach(input::add);

        HttpResult result = executeForResult(
            jsonPost(endpoint(config, EMBEDDINGS_PATH), body), config, AiCancellationToken.none(), false
        );
        try {
            return parseEmbeddingResponse(
                result.body(),
                request.inputs().size(),
                options.dimensions(),
                result.statusCode()
            );
        } catch (IOException | RuntimeException exception) {
            throw invalidResponse(
                result.statusCode(),
                "Could not parse the OpenAI embedding response",
                result.body(),
                exception
            );
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
        return stream(request, config, listener, token, AiLifecycleListener.none());
    }

    @Override
    public AiResponse stream(
        AiRequest request, AiProviderConfig config, AiStreamListener listener,
        AiCancellationToken token, AiLifecycleListener lifecycleListener
    ) throws AiProviderException {
        LifecycleSupport lifecycle = new LifecycleSupport(PROVIDER_ID, lifecycleListener);
        return lifecycle.call(config, token, () -> streamInternal(
            request, config, CancellationSupport.listener(PROVIDER_ID, token, listener), token, lifecycle
        ));
    }

    /**
     * Streams a text or chat response while registering cancellation for the active HTTP request.
     *
     * @param request text or chat request to validate and send
     * @param config credentials, endpoint, and transport settings
     * @param listener non-null recipient of generated text fragments
     * @param token non-null cancellation token for this call
     * @param lifecycle per-call lifecycle tracker
     * @return accumulated text and completion metadata, including a response ID for chat
     * @throws AiProviderException if validation, transport, parsing, or listener delivery fails
     */
    private AiResponse streamInternal(
        AiRequest request,
        AiProviderConfig config,
        AiStreamListener listener,
        AiCancellationToken token,
        LifecycleSupport lifecycle
    ) throws AiProviderException {

        requireRequest(request);
        ChatRequestValidator.validate(PROVIDER_ID, request);
        if (listener == null) {
            throw new AiProviderException(PROVIDER_ID, "OpenAI stream listener is required");
        }
        requireConfigured(config);
        requireModel(request);
        if (request.operation() != AiOperation.TEXT && request.operation() != AiOperation.CHAT) {
            throw new AiProviderException(PROVIDER_ID, "OpenAI streaming is supported only for text requests")
                .withCategory(AiErrorCategory.UNSUPPORTED_OPERATION);
        }

        ObjectNode body = buildTextBody(request);
        body.put("stream", true);

        HttpPost post = jsonPost(endpoint(config, RESPONSES_PATH), body);
        prepare(post, config, "text/event-stream");

        lifecycle.submitting(token);
        try (AiCancellationToken.Registration cancellation = token.onCancel(post::abort);
             CloseableHttpResponse response = httpClient.execute(post)) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (isSuccessful(statusCode) == false) {
                String bodyText = readEntity(response.getEntity());
                trackRejection(lifecycle, statusCode, bodyText);
                throw httpError(statusCode, bodyText, isContinuation(request));
            }

            HttpEntity entity = response.getEntity();
            if (entity == null) {
                throw invalidResponse("OpenAI returned an empty streaming response", "", null);
            }
            Charset charset = responseCharset(entity);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(entity.getContent(), charset))) {
                return OpenAiStreamParser.parse(
                    reader,
                    listener,
                    request.operation() == AiOperation.CHAT,
                    isContinuation(request),
                    lifecycle
                );
            }
        } catch (IOException exception) {
            throw transportFailure("OpenAI streaming request failed", exception);
        }
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }

    /**
     * Sends a Responses API request and applies text or chat completion validation.
     *
     * @param request validated text or chat request
     * @param config credentials, endpoint, and transport settings
     * @param token cancellation token for the active HTTP request
     * @param lifecycle per-call lifecycle tracker
     * @return generated text, usage, and completion metadata
     * @throws AiProviderException if transport or response validation fails
     */
    private AiResponse executeText(
        AiRequest request, AiProviderConfig config, AiCancellationToken token, LifecycleSupport lifecycle
    ) throws AiProviderException {
        HttpPost post = jsonPost(endpoint(config, RESPONSES_PATH), buildTextBody(request));
        String response = executeForResult(post, config, token, isContinuation(request), lifecycle).body();

        try {
            JsonNode root = MAPPER.readTree(response);
            if (root != null && root.path("id").isTextual()) lifecycle.onResponseId(root.path("id").textValue());
            return request.operation() == AiOperation.CHAT
                ? parseChatResponse(root, response, isContinuation(request))
                : parseTextResponse(root, response);
        } catch (IOException | RuntimeException exception) {
            throw invalidResponse("Could not parse the OpenAI text response", response, exception);
        }
    }

    /**
     * Validates image-generation options and requests images encoded as Base64.
     *
     * @param request image-generation request with a meaningful prompt
     * @param config credentials, endpoint, and transport settings
     * @param token cancellation token for the active HTTP request
     * @param lifecycle per-call lifecycle tracker
     * @return decoded generated images and available usage metadata
     * @throws AiProviderException if validation, transport, or response parsing fails
     */
    private AiResponse generateImage(
        AiRequest request, AiProviderConfig config, AiCancellationToken token, LifecycleSupport lifecycle
    ) throws AiProviderException {
        if (hasMeaningfulImagePrompt(request) == false) {
            throw new AiProviderException(PROVIDER_ID, "An image prompt is required");
        }
        String prompt = imagePrompt(request);

        Map<String, Object> imageOptions = OpenAiImageOptions.validate(PROVIDER_ID, request);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", request.model());
        body.put("prompt", prompt);
        addJsonImageOptions(body, imageOptions);
        if ("dall-e-2".equals(request.model()) || "dall-e-3".equals(request.model())) {
            body.put("response_format", "b64_json");
        }

        HttpPost post = jsonPost(endpoint(config, IMAGE_GENERATIONS_PATH), body);
        return executeImage(post, config, (String) imageOptions.get("output_format"), token, lifecycle);
    }

    /**
     * Validates image-editing support and sends the input image as a multipart request.
     *
     * @param request edit request containing input media and a meaningful prompt
     * @param config credentials, endpoint, and transport settings
     * @param token cancellation token for the active HTTP request
     * @param lifecycle per-call lifecycle tracker
     * @return decoded edited images and available usage metadata
     * @throws AiProviderException if editing is unsupported or validation, transport, or parsing fails
     */
    private AiResponse editImage(
        AiRequest request, AiProviderConfig config, AiCancellationToken token, LifecycleSupport lifecycle
    ) throws AiProviderException {
        if ("dall-e-3".equals(request.model())) {
            throw new AiProviderException(PROVIDER_ID,
                "Image editing with " + request.model() + " is not supported by this provider adapter")
                .withCategory(AiErrorCategory.UNSUPPORTED_OPERATION);
        }

        BinaryContent input = request.inputMedia();
        if (input == null || input.data().length == 0) {
            throw new AiProviderException(PROVIDER_ID, "An input image is required for image editing");
        }

        if (hasMeaningfulImagePrompt(request) == false) {
            throw new AiProviderException(PROVIDER_ID, "An image edit prompt is required");
        }
        String prompt = imagePrompt(request);

        Map<String, Object> imageOptions = OpenAiImageOptions.validate(PROVIDER_ID, request);
        HttpPost post = new HttpPost(endpoint(config, IMAGE_EDITS_PATH));
        post.setEntity(buildImageEditEntity(request, input, prompt, imageOptions));
        return executeImage(post, config, (String) imageOptions.get("output_format"), token, lifecycle);
    }

    /**
     * Validates image options and builds a multipart image-edit request body.
     *
     * @param request edit request containing the model and image options
     * @param input image bytes and media metadata
     * @param prompt prepared edit prompt
     * @return multipart entity with a sanitized image filename
     * @throws AiProviderException if the supplied image options are invalid
     */
    static HttpEntity buildImageEditEntity(
        AiRequest request,
        BinaryContent input,
        String prompt
    ) throws AiProviderException {
        return buildImageEditEntity(
            request,
            input,
            prompt,
            OpenAiImageOptions.validate(PROVIDER_ID, request)
        );
    }

    /**
     * Encodes validated image options and media into a multipart request body.
     *
     * @param request edit request containing the model
     * @param input image bytes and media metadata
     * @param prompt prepared edit prompt
     * @param imageOptions validated options, with count mapped to the wire name {@code n}
     * @return multipart entity using a safe filename and media type
     */
    private static HttpEntity buildImageEditEntity(
        AiRequest request,
        BinaryContent input,
        String prompt,
        Map<String, Object> imageOptions
    ) {
        MultipartEntityBuilder multipart = MultipartEntityBuilder.create()
            .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
            .addTextBody("model", request.model(), TEXT_UTF_8)
            .addTextBody("prompt", prompt, TEXT_UTF_8);
        if ("dall-e-2".equals(request.model())) {
            multipart.addTextBody("response_format", "b64_json", TEXT_UTF_8);
        }
        for (Map.Entry<String, Object> option : imageOptions.entrySet()) {
            String wireName = ImageOptions.COUNT.equals(option.getKey()) ? "n" : option.getKey();
            multipart.addTextBody(wireName, String.valueOf(option.getValue()), TEXT_UTF_8);
        }
        multipart.addBinaryBody(
            "image",
            input.data(),
            safeContentType(input.mediaType()),
            safeMultipartFileName(input.fileName())
        );
        return multipart.build();
    }

    /**
     * Removes directory components and replaces unsafe multipart filename characters with underscores.
     *
     * @param fileName supplied filename, possibly {@code null} or blank
     * @return ASCII leaf filename, or {@code image} when no usable name remains
     */
    static String safeMultipartFileName(String fileName) {
        if (isBlank(fileName)) return "image";

        int pathSeparator = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String leafName = fileName.substring(pathSeparator + 1);
        StringBuilder sanitized = new StringBuilder(leafName.length());
        for (int index = 0; index < leafName.length(); index++) {
            char character = leafName.charAt(index);
            if (character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '.'
                    || character == '-'
                    || character == '_') {
                sanitized.append(character);
            } else {
                sanitized.append('_');
            }
        }

        String result = sanitized.toString();
        return result.isBlank() || ".".equals(result) || "..".equals(result) ? "image" : result;
    }

    /**
     * Sends a prepared image request and decodes its Base64 response.
     *
     * @param post image-generation or image-edit request
     * @param config credentials and transport settings
     * @param requestedFormat output format used when response metadata omits it; may be {@code null}
     * @param token cancellation token for the active HTTP request
     * @param lifecycle per-call lifecycle tracker
     * @return decoded image media and available usage metadata
     * @throws AiProviderException if transport or response parsing fails
     */
    private AiResponse executeImage(
        HttpPost post,
        AiProviderConfig config,
        String requestedFormat,
        AiCancellationToken token,
        LifecycleSupport lifecycle
    ) throws AiProviderException {
        String response = executeForResult(post, config, token, false, lifecycle).body();
        try {
            return parseImageResponse(response, requestedFormat);
        } catch (IOException | RuntimeException exception) {
            throw invalidResponse("Could not parse the OpenAI image response", response, exception);
        }
    }

    /**
     * Builds a Responses payload with protected user inputs and explicit storage settings.
     * Chat requests use top-level instructions and may include a previous response ID.
     *
     * @param request validated text or chat request
     * @return request body containing instructions, text, and any input image
     */
    static ObjectNode buildTextBody(AiRequest request) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", request.model());
        body.put("store", request.store());

        ArrayNode inputs = body.putArray("input");
        if (request.operation() == AiOperation.CHAT) {
            body.put("instructions", PromptInjectionDefense.hardenChatInstructions(request.instructions()));
            if (request.previousResponseId() != null) {
                body.put("previous_response_id", request.previousResponseId());
            }
        } else {
            addTextInput(
                inputs,
                "system",
                PromptInjectionDefense.hardenSystemInstructions(request.instructions())
            );
        }
        addProtectedInput(inputs, request.inputText(), UntrustedSource.INPUT_TEXT);
        addProtectedInput(inputs, request.userPrompt(), UntrustedSource.USER_PROMPT);

        BinaryContent media = request.inputMedia();
        if (media != null && media.data().length > 0) {
            ObjectNode imageMessage = inputs.addObject();
            imageMessage.put("role", "user");
            ObjectNode content = imageMessage.putArray("content").addObject();
            content.put("type", "input_image");
            content.put("image_url", "data:" + media.mediaType() + ";base64,"
                + Base64.getEncoder().encodeToString(media.data()));
        }
        return body;
    }

    /**
     * Parses catalogue entries, omitting blank identifiers and sorting newest models first.
     *
     * @param response JSON model catalogue
     * @return immutable model list with undated entries last
     * @throws IOException if JSON decoding fails or the data array is absent
     */
    static List<ModelInfo> parseModels(String response) throws IOException {
        JsonNode root = MAPPER.readTree(response);
        JsonNode data = root.path("data");
        if (data.isArray() == false) {
            throw new IOException("OpenAI model response does not contain a data array");
        }

        List<ModelInfo> models = new ArrayList<>();
        for (JsonNode model : data) {
            String id = textOrNull(model.get("id"));
            if (isBlank(id)) {
                continue;
            }
            Long created = model.has("created") && model.get("created").canConvertToLong()
                ? model.get("created").longValue()
                : null;
            models.add(new ModelInfo(id, id, created));
        }
        models.sort(Comparator.comparing(
            ModelInfo::createdAt,
            Comparator.nullsLast(Comparator.reverseOrder())
        ));
        return List.copyOf(models);
    }

    /**
     * Parses generated text and usage while rejecting explicit unsuccessful response statuses.
     *
     * @param root parsed response object
     * @param response JSON response body
     * @return output text, which may be empty, with usage and optional finish status
     * @throws AiProviderException if the response is invalid, unsuccessful, or lacks output text
     */
    static AiResponse parseTextResponse(JsonNode root, String response) throws AiProviderException {
        ensureSuccessfulResponse(root, response, false);

        String text = outputText(root, false);
        if (text == null) {
            throw invalidResponse("OpenAI response does not contain output text", response, null);
        }

        return new AiResponse(text, List.of(), parseUsage(root.path("usage")), responseStatus(root));
    }

    /**
     * Requires a completed chat response with usable text, a response ID, and no unsupported output actions.
     *
     * @param root parsed response object, including one nested inside a completion event
     * @param response original response or event JSON retained for diagnostics
     * @param continuation whether structured errors may identify an invalid continuation
     * @return chat text, usage, completed status, and provider continuation ID
     * @throws AiProviderException if completion, output, or continuation validation fails
     */
    static AiResponse parseChatResponse(JsonNode root, String response, boolean continuation) throws AiProviderException {
        ensureSuccessfulResponse(root, response, continuation);
        if ("completed".equals(textOrNull(root.get("status"))) == false || root.hasNonNull("error")) {
            throw classifyContinuationError(
                invalidResponse("OpenAI chat response is not completed", response, null), root.path("error"), continuation
            );
        }
        JsonNode id = root.get("id");
        if (id == null || id.isTextual() == false || id.textValue().isBlank()) {
            throw invalidResponse("OpenAI chat response does not contain a response ID", response, null);
        }

        JsonNode outputs = root.path("output");
        if (root.hasNonNull("output") && outputs.isArray() == false) {
            throw invalidResponse("OpenAI chat response contains invalid output", response, null);
        }
        for (JsonNode output : outputs) {
            String type = textOrNull(output.get("type"));
            if ("message".equals(type) == false && "reasoning".equals(type) == false) {
                throw invalidResponse("OpenAI chat response contains an unsupported output action", response, null);
            }
            if (output.hasNonNull("status")
                && "completed".equals(textOrNull(output.get("status"))) == false) {
                throw invalidResponse("OpenAI chat output is not completed", response, null);
            }
        }

        String text = outputText(root, true);
        if (isBlank(text)) {
            throw invalidResponse("OpenAI chat response does not contain usable output text", response, null);
        }
        return new AiResponse(text, List.of(), parseUsage(root.path("usage")), "completed", id.textValue());
    }

    /**
     * Prefers top-level output text, otherwise concatenating content outside reasoning outputs.
     * Chat mode accepts only textual values and content parts typed as {@code output_text}.
     *
     * @param root response object whose structure has already been validated
     * @param chat whether to apply the stricter chat text rules
     * @return extracted text, possibly empty, or {@code null} when no text field is found
     */
    private static String outputText(JsonNode root, boolean chat) {
        String text = chat ? root.path("output_text").textValue() : textOrNull(root.get("output_text"));
        if (text != null) return text;
        StringBuilder combined = new StringBuilder();
        boolean found = false;
        JsonNode outputs = root.path("output");
        if (outputs.isArray()) {
            for (JsonNode output : outputs) {
                if ("reasoning".equals(textOrNull(output.get("type")))) continue;
                JsonNode content = output.path("content");
                if (content.isArray() == false) continue;
                for (JsonNode part : content) {
                    if (chat && "output_text".equals(textOrNull(part.get("type"))) == false) continue;
                    String partText = chat ? part.path("text").textValue() : textOrNull(part.get("text"));
                    if (partText != null) {
                        combined.append(partText);
                        found = true;
                    }
                }
            }
        }
        return found ? combined.toString() : null;
    }

    static AiResponse parseImageResponse(String response) throws IOException, AiProviderException {
        return parseImageResponse(response, null);
    }

    /**
     * Decodes generated Base64 images, using response format metadata before the requested format.
     *
     * @param response JSON image response
     * @param requestedFormat format fallback, or {@code null} to default to PNG
     * @return generated media and available usage and finish metadata
     * @throws IOException if JSON decoding fails
     * @throws AiProviderException if the response failed, image data is missing, or Base64 is invalid
     */
    static AiResponse parseImageResponse(String response, String requestedFormat)
        throws IOException, AiProviderException {
        JsonNode root = MAPPER.readTree(response);
        ensureSuccessfulResponse(root, response, false);
        JsonNode data = root.path("data");
        if (data.isArray() == false) {
            throw invalidResponse("OpenAI image response does not contain a data array", response, null);
        }

        String defaultFormat = defaultIfBlank(
            textOrNull(root.get("output_format")),
            defaultIfBlank(requestedFormat, "png")
        );
        List<GeneratedMedia> media = new ArrayList<>();
        for (JsonNode image : data) {
            String encoded = textOrNull(image.get("b64_json"));
            if (isBlank(encoded)) {
                continue;
            }
            String format = defaultIfBlank(textOrNull(image.get("output_format")), defaultFormat);
            try {
                media.add(new GeneratedMedia(Base64.getDecoder().decode(encoded), mediaType(format)));
            } catch (IllegalArgumentException exception) {
                throw invalidResponse("OpenAI returned invalid Base64 image data", response, exception);
            }
        }
        if (media.isEmpty()) {
            throw invalidResponse("OpenAI image response does not contain image data", response, null);
        }

        return new AiResponse(null, media, parseUsage(root.path("usage")), responseStatus(root));
    }

    /**
     * Validates embedding indices and vectors, restoring the original input order.
     *
     * @param response JSON embedding response
     * @param expectedCount number of input texts
     * @param dimensions required vector size, or {@code null} to infer it from the first vector
     * @param statusCode HTTP status retained in validation failures
     * @return ordered embeddings and available token usage
     * @throws IOException if JSON decoding fails
     * @throws AiProviderException if counts, indices, dimensions, or vector values are invalid
     */
    private static EmbeddingResponse parseEmbeddingResponse(
        String response,
        int expectedCount,
        Integer dimensions,
        int statusCode
    )
        throws IOException, AiProviderException {
        JsonNode root = MAPPER.readTree(response);
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || data.isArray() == false || data.size() != expectedCount) {
            throw invalidResponse(
                statusCode,
                "OpenAI returned an unexpected number of embeddings",
                response,
                null
            );
        }

        EmbeddingVector[] ordered = new EmbeddingVector[expectedCount];
        Integer actualDimensions = dimensions;
        for (JsonNode item : data) {
            JsonNode indexNode = item.get("index");
            if (indexNode == null || indexNode.isIntegralNumber() == false || indexNode.canConvertToInt() == false) {
                throw invalidResponse(statusCode, "OpenAI embedding response has no valid index", response, null);
            }
            int index = indexNode.intValue();
            if (index < 0 || index >= expectedCount || ordered[index] != null) {
                throw invalidResponse(
                    statusCode,
                    "OpenAI embedding response contains an invalid index",
                    response,
                    null
                );
            }
            JsonNode values = item.get("embedding");
            actualDimensions = requireEmbeddingDimensions(values, actualDimensions, response, statusCode);
            ordered[index] = parseEmbeddingVector(values, actualDimensions, response, statusCode);
        }

        for (EmbeddingVector vector : ordered) {
            if (vector == null) {
                throw invalidResponse(
                    statusCode,
                    "OpenAI embedding response is missing an input index",
                    response,
                    null
                );
            }
        }
        return new EmbeddingResponse(List.of(ordered), parseUsage(root.path("usage")));
    }

    /**
     * Requires a nonempty vector and checks its size against any expected dimension count.
     *
     * @param values embedding array to inspect
     * @param expected required size, or {@code null} to accept the observed size
     * @param response original response retained for diagnostics
     * @param statusCode HTTP status retained in validation failures
     * @return validated vector size
     * @throws AiProviderException if the vector is missing, empty, or has the wrong size
     */
    private static int requireEmbeddingDimensions(
        JsonNode values,
        Integer expected,
        String response,
        int statusCode
    )
        throws AiProviderException {
        int actual = values != null && values.isArray() ? values.size() : 0;
        if (actual < 1) {
            throw invalidResponse(
                statusCode,
                "OpenAI embedding response contains an empty vector",
                response,
                null
            );
        }
        if (expected != null && actual != expected) {
            throw invalidResponse(
                statusCode,
                "OpenAI returned " + actual + " embedding dimensions, expected " + expected,
                response,
                null
            );
        }
        return actual;
    }

    /**
     * Converts a JSON embedding array into an immutable vector of finite floats.
     *
     * @param values embedding array to decode
     * @param dimensions required vector size
     * @param response original response retained for diagnostics
     * @param statusCode HTTP status retained in validation failures
     * @return validated embedding vector
     * @throws AiProviderException if the size or any numeric value is invalid
     */
    private static EmbeddingVector parseEmbeddingVector(
        JsonNode values,
        int dimensions,
        String response,
        int statusCode
    )
        throws AiProviderException {
        if (values == null || values.isArray() == false || values.size() != dimensions) {
            int actual = values != null && values.isArray() ? values.size() : 0;
            throw invalidResponse(
                statusCode,
                "OpenAI returned " + actual + " embedding dimensions, expected " + dimensions,
                response,
                null
            );
        }
        float[] vector = new float[dimensions];
        for (int index = 0; index < dimensions; index++) {
            JsonNode value = values.get(index);
            if (value.isNumber() == false) {
                throw invalidResponse(
                    statusCode,
                    "OpenAI embedding response contains a non-numeric value",
                    response,
                    null
                );
            }
            float parsedValue = value.floatValue();
            if (Float.isFinite(parsedValue) == false) {
                throw invalidResponse(
                    statusCode,
                    "OpenAI embedding response contains a non-finite value",
                    response,
                    null
                );
            }
            vector[index] = parsedValue;
        }
        return new EmbeddingVector(vector);
    }

    /**
     * Reads token totals and nested integral details, accepting Responses and Chat Completions field names.
     *
     * @param usage optional provider usage object
     * @return normalized counts and details, or empty usage when no object is supplied
     */
    static TokenUsage parseUsage(JsonNode usage) {
        if (usage == null || usage.isObject() == false) {
            return TokenUsage.EMPTY;
        }

        long input = usage.path("input_tokens").asLong(usage.path("prompt_tokens").asLong(0));
        long output = usage.path("output_tokens").asLong(usage.path("completion_tokens").asLong(0));
        long total = usage.path("total_tokens").asLong(input + output);
        Map<String, Long> details = new LinkedHashMap<>();
        collectNumericDetails(usage, "", details);
        details.remove("input_tokens");
        details.remove("output_tokens");
        details.remove("total_tokens");
        details.remove("prompt_tokens");
        details.remove("completion_tokens");
        return new TokenUsage(input, output, total, details);
    }

    /**
     * Flattens integral usage fields from nested objects into dotted paths.
     *
     * @param node usage object to traverse
     * @param prefix parent path, empty at the root
     * @param target destination for collected path-value pairs
     */
    private static void collectNumericDetails(JsonNode node, String prefix, Map<String, Long> target) {
        node.properties().forEach(entry -> {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isIntegralNumber()) {
                target.put(key, value.longValue());
            } else if (value.isObject()) {
                collectNumericDetails(value, key, target);
            }
        });
    }

    /**
     * Sends a configured request, reads its body, and classifies HTTP or transport failures.
     * The cancellation registration is released with the response; secrets are redacted by the caller's boundary.
     *
     * @param request prepared request whose headers and timeouts will be configured
     * @param config credentials and transport settings
     * @param token non-null cancellation token for the active request
     * @param continuation whether structured errors may identify an invalid continuation
     * @return successful HTTP status and response body
     * @throws AiProviderException if transport fails or the HTTP status is unsuccessful
     */
    private HttpResult executeForResult(
        HttpRequestBase request,
        AiProviderConfig config,
        AiCancellationToken token,
        boolean continuation
    ) throws AiProviderException {
        return executeForResult(request, config, token, continuation, null);
    }

    /** Sends generation requests with submission tracking; other operations may omit the tracker. */
    private HttpResult executeForResult(
        HttpRequestBase request, AiProviderConfig config, AiCancellationToken token,
        boolean continuation, LifecycleSupport lifecycle
    ) throws AiProviderException {
        prepare(request, config, "application/json");
        if (lifecycle != null) lifecycle.submitting(token);
        try (AiCancellationToken.Registration cancellation = token.onCancel(request::abort);
             CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            String body = readEntity(response.getEntity());
            if (isSuccessful(statusCode) == false) {
                trackRejection(lifecycle, statusCode, body);
                throw httpError(statusCode, body, continuation);
            }
            return new HttpResult(statusCode, body);
        } catch (IOException exception) {
            throw transportFailure("OpenAI request failed", exception);
        }
    }

    /** Recognizes explicit client-request rejections; transport, timeout, and server failures stay unknown. */
    private static void trackRejection(LifecycleSupport lifecycle, int status, String payload) {
        if (lifecycle == null || (status != 400 && status != 401 && status != 403
            && status != 404 && status != 422 && status != 429)) return;
        try {
            JsonNode root = MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(payload);
            if (root == null || root.hasNonNull("id") || root.hasNonNull("response")) return;
            String type = root.path("error").path("type").asText();
            if ("invalid_request_error".equals(type) || "authentication_error".equals(type)
                || "permission_error".equals(type) || "rate_limit_error".equals(type)
                || "insufficient_quota".equals(type)) {
                lifecycle.rejected();
            }
        } catch (IOException ignored) {
            // An unrecognized error body cannot establish rejection before response creation.
        }
    }

    /**
     * Successful HTTP response retained for operation-specific parsing.
     *
     * @param statusCode successful HTTP status
     * @param body decoded response body, empty when no entity is supplied
     */
    private record HttpResult(int statusCode, String body) { }

    private static HttpPost jsonPost(URI endpoint, JsonNode body) {
        HttpPost post = new HttpPost(endpoint);
        post.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));
        return post;
    }

    private static void prepare(HttpRequestBase request, AiProviderConfig config, String accept) {
        request.setConfig(requestConfig(config));
        config.trustedHeaders().forEach(request::setHeader);
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey());
        request.setHeader(HttpHeaders.ACCEPT, accept);
    }

    private static RequestConfig requestConfig(AiProviderConfig config) {
        return RequestConfig.custom()
            .setConnectTimeout(config.connectTimeoutMillis())
            .setConnectionRequestTimeout(config.connectTimeoutMillis())
            .setSocketTimeout(config.responseTimeoutMillis())
            .build();
    }

    private static AiProviderException transportFailure(String message, IOException exception) {
        return new AiProviderException(PROVIDER_ID, -1, message, null, true, exception);
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
            throw new AiProviderException(PROVIDER_ID, "Unexpected OpenAI provider failure", exception)
                .redactSecrets(config);
        }
    }

    private static URI endpoint(AiProviderConfig config, String path) {
        URI base = config.baseUri() == null ? DEFAULT_BASE_URI : config.baseUri();
        String value = base.toString();
        if (value.endsWith("/") == false) {
            value += "/";
        }
        return URI.create(value).resolve(path);
    }

    private static void requireConfigured(AiProviderConfig config) throws AiProviderException {
        if (config == null || config.isConfigured() == false) {
            throw new AiProviderException(PROVIDER_ID, "OpenAI API key is not configured");
        }
    }

    private static void requireRequest(AiRequest request) throws AiProviderException {
        if (request == null) {
            throw new AiProviderException(PROVIDER_ID, "OpenAI request is required");
        }
    }

    private static void requireModel(AiRequest request) throws AiProviderException {
        if (isBlank(request.model())) {
            throw new AiProviderException(PROVIDER_ID, "An OpenAI model is required");
        }
    }

    /**
     * Validates the embedding model and every input before returning the request options.
     *
     * @param request embedding request to validate
     * @return configured embedding options
     * @throws AiProviderException if the request, model, or input texts are missing or blank
     */
    private static EmbeddingOptions requireEmbeddingRequest(EmbeddingRequest request)
        throws AiProviderException {
        if (request == null) {
            throw new AiProviderException(PROVIDER_ID, "OpenAI embedding request is required");
        }
        if (isBlank(request.model())) {
            throw new AiProviderException(PROVIDER_ID, "An OpenAI embedding model is required");
        }
        if (request.inputs().isEmpty()) {
            throw new AiProviderException(PROVIDER_ID, "At least one OpenAI embedding input is required");
        }
        for (String input : request.inputs()) {
            if (isBlank(input)) {
                throw new AiProviderException(PROVIDER_ID, "OpenAI embedding inputs must not be blank");
            }
        }
        return request.options();
    }

    private static void addTextInput(ArrayNode inputs, String role, String value) {
        if (isBlank(value)) {
            return;
        }
        ObjectNode input = inputs.addObject();
        input.put("role", role);
        input.put("content", value);
    }

    /**
     * Copies scalar image options to JSON, mapping the public count option to {@code n}.
     *
     * @param body destination image request body
     * @param options validated image options
     */
    private static void addJsonImageOptions(ObjectNode body, Map<String, Object> options) {
        for (Map.Entry<String, Object> option : options.entrySet()) {
            String wireName = ImageOptions.COUNT.equals(option.getKey()) ? "n" : option.getKey();
            Object value = option.getValue();
            if (value instanceof Integer integer) body.put(wireName, integer);
            else if (value instanceof Long number) body.put(wireName, number);
            else if (value instanceof Boolean bool) body.put(wireName, bool);
            else body.put(wireName, String.valueOf(value));
        }
    }

    /**
     * Combines security and task instructions with protected image prompt data.
     * Input text is included only for image generation, not editing.
     *
     * @param request image request whose prompt fields are combined
     * @return prepared prompt with blank components omitted
     */
    static String imagePrompt(AiRequest request) {
        StringBuilder prompt = new StringBuilder();
        appendPrompt(prompt, PromptInjectionDefense.getSecurityInstructions(request.instructions()));
        appendPrompt(prompt, PromptInjectionDefense.getTaskInstructions(request.instructions()));
        if (request.operation() == AiOperation.GENERATE_IMAGE) {
            appendProtectedPrompt(prompt, request.inputText(), UntrustedSource.INPUT_TEXT);
        }
        appendProtectedPrompt(prompt, request.userPrompt(), UntrustedSource.USER_PROMPT);
        return prompt.toString();
    }

    /**
     * Checks for task instructions or meaningful prompt data, excluding {@code inputText} for image editing.
     *
     * @param request image request to inspect
     * @return whether content beyond the security instructions defines an image task
     */
    private static boolean hasMeaningfulImagePrompt(AiRequest request) {
        return PromptInjectionDefense.hasTaskInstructions(request.instructions())
            || PromptInjectionDefense.hasUntrustedText(
                request.userPrompt(),
                UntrustedSource.USER_PROMPT
            )
            || (request.operation() == AiOperation.GENERATE_IMAGE
                && PromptInjectionDefense.hasUntrustedText(
                    request.inputText(),
                    UntrustedSource.INPUT_TEXT
                ));
    }

    private static void addProtectedInput(ArrayNode inputs, String value, UntrustedSource source) {
        addTextInput(
            inputs,
            "user",
            PromptInjectionDefense.protectUntrustedText(value, source).protectedText()
        );
    }

    private static void appendProtectedPrompt(
        StringBuilder target,
        String value,
        UntrustedSource source
    ) {
        appendPrompt(
            target,
            PromptInjectionDefense.protectUntrustedText(value, source).protectedText()
        );
    }

    private static void appendPrompt(StringBuilder target, String value) {
        if (isBlank(value)) {
            return;
        }
        if (target.length() > 0) {
            target.append("\n\n");
        }
        target.append(value);
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private static boolean isSuccessful(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static Charset responseCharset(HttpEntity entity) {
        ContentType contentType = ContentType.get(entity);
        return contentType == null || contentType.getCharset() == null
            ? StandardCharsets.UTF_8
            : contentType.getCharset();
    }

    private static String readEntity(HttpEntity entity) throws IOException {
        return entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
    }

    private static ContentType safeContentType(String mediaType) {
        try {
            return ContentType.create(defaultIfBlank(mediaType, "application/octet-stream"));
        } catch (RuntimeException exception) {
            return ContentType.DEFAULT_BINARY;
        }
    }

    private static String mediaType(String format) {
        String normalized = format == null ? "png" : format.toLowerCase(Locale.ROOT).replace(".", "");
        return switch (normalized) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "image/png";
        };
    }

    /**
     * Builds an HTTP failure with retryability and conservative continuation classification.
     *
     * @param statusCode unsuccessful HTTP status
     * @param response original response body retained for diagnostics
     * @param continuation whether to inspect structured continuation error fields
     * @return failure awaiting credential redaction at the public boundary
     */
    private AiProviderException httpError(int statusCode, String response, boolean continuation) {
        String detail = errorMessage(response);
        String message = "OpenAI request failed (" + statusCode + ")";
        if (isBlank(detail) == false) {
            message += ": " + detail;
        }
        AiProviderException exception = new AiProviderException(
            PROVIDER_ID, statusCode, message, response, isRetryable(statusCode)
        );
        if (continuation == false) return exception;
        try {
            return classifyContinuationError(exception, MAPPER.readTree(response).path("error"), true);
        } catch (IOException | RuntimeException ignored) {
            return exception;
        }
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500;
    }

    private static boolean isContinuation(AiRequest request) {
        return request.operation() == AiOperation.CHAT && isBlank(request.previousResponseId()) == false;
    }

    /**
     * Classifies a continuation failure from a protocol error object, never model output.
     *
     * @param exception failure whose diagnostics must be retained
     * @param error structured error from an HTTP response or explicit response/SSE failure
     * @param continuation whether the request is a chat with a supplied continuation ID
     * @return categorized failure when an exact code or parameter identifies the continuation, otherwise the original
     */
    static AiProviderException classifyContinuationError(
        AiProviderException exception,
        JsonNode error,
        boolean continuation
    ) {
        if (continuation && error.isObject()
            && ("previous_response_not_found".equals(error.path("code").textValue())
                || "previous_response_id".equals(error.path("param").textValue()))) {
            return exception.withCategory(AiErrorCategory.INVALID_CONTINUATION);
        }
        return exception;
    }

    /**
     * Extracts upstream error metadata before falling back to the provider's message.
     *
     * @param response JSON error response
     * @return error detail, or {@code null} when absent or parsing fails
     */
    private static String errorMessage(String response) {
        try {
            JsonNode error = MAPPER.readTree(response).path("error");
            String raw = textOrNull(error.path("metadata").get("raw"));
            if (isBlank(raw) == false) {
                return raw;
            }
            return textOrNull(error.get("message"));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Rejects non-object responses and explicit incomplete, failed, or cancelled statuses.
     *
     * @param root parsed response to inspect
     * @param rawResponse original response retained for diagnostics
     * @param continuation whether structured errors may identify an invalid continuation
     * @throws AiProviderException if the response shape or status indicates failure
     */
    private static void ensureSuccessfulResponse(JsonNode root, String rawResponse, boolean continuation)
        throws AiProviderException {
        if (root == null || root.isObject() == false) {
            throw invalidResponse("OpenAI returned an empty or invalid response", rawResponse, null);
        }
        String status = responseStatus(root);
        if ("incomplete".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status)) {
            String reason = textOrNull(root.path("incomplete_details").get("reason"));
            if (isBlank(reason)) {
                reason = textOrNull(root.path("error").get("message"));
            }
            throw classifyContinuationError(new AiProviderException(
                PROVIDER_ID,
                200,
                "OpenAI response " + status + (isBlank(reason) ? "" : ": " + reason),
                rawResponse,
                false
            ), root.path("error"), continuation);
        }
    }

    /**
     * Reads the top-level status, falling back to the first output entry when it is blank.
     *
     * @param root response object to inspect
     * @return response or first-output status, or {@code null} when unavailable
     */
    private static String responseStatus(JsonNode root) {
        String status = textOrNull(root.get("status"));
        if (isBlank(status) == false) {
            return status;
        }
        JsonNode output = root.path("output");
        return output.isArray() && output.isEmpty() == false
            ? textOrNull(output.get(0).get("status"))
            : null;
    }

    private static AiProviderException invalidResponse(String message, String response, Throwable cause) {
        return invalidResponse(200, message, response, cause);
    }

    private static AiProviderException invalidResponse(
        int statusCode,
        String message,
        String response,
        Throwable cause
    ) {
        return new AiProviderException(PROVIDER_ID, statusCode, message, response, false, cause);
    }
}
