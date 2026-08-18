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
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProvider;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.AiStreamListener;
import com.webjetcms.ai.BinaryContent;
import com.webjetcms.ai.EmbeddingOptions;
import com.webjetcms.ai.EmbeddingTaskType;
import com.webjetcms.ai.EmbeddingVector;
import com.webjetcms.ai.GeneratedMedia;
import com.webjetcms.ai.ModelInfo;
import com.webjetcms.ai.TokenUsage;
import com.webjetcms.ai.security.PromptInjectionDefense;
import com.webjetcms.ai.security.PromptInjectionDefense.UntrustedSource;

/** Framework-neutral client for the OpenRouter chat-completions API. */
public final class OpenRouterProvider implements AiProvider {

    /** Stable provider identifier used by {@code AiClient}. */
    public static final String PROVIDER_ID = "openrouter";

    private static final URI DEFAULT_BASE_URI = URI.create("https://openrouter.ai/api/v1/");
    private static final String MODELS_PATH = "models?output_modalities=all";
    private static final String CHAT_COMPLETIONS_PATH = "chat/completions";
    private static final String EMBEDDINGS_PATH = "embeddings";
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
        return invokeAtBoundary(config, () -> listModelsInternal(config));
    }

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
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (IOException exception) {
            throw transportFailure("Unable to load the OpenRouter model catalogue.", exception)
                .redactSecrets(config);
        }
    }

    @Override
    public AiResponse execute(AiRequest request, AiProviderConfig config) throws AiProviderException {
        return invokeAtBoundary(config, () -> executeInternal(request, config));
    }

    private AiResponse executeInternal(AiRequest request, AiProviderConfig config) throws AiProviderException {
        validateRequest(request);
        validateConfig(config);

        if (request.operation() == AiOperation.EMBEDDING) {
            return executeEmbedding(request, config);
        }

        HttpPost httpRequest = createChatRequest(request, config, false);
        try (CloseableHttpResponse response = httpClient.execute(httpRequest)) {
            int statusCode = response.getStatusLine().getStatusCode();
            String rawResponse = readEntity(response.getEntity());
            ensureSuccessful(statusCode, rawResponse);
            return parseCompletion(rawResponse, request.operation());
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (IOException exception) {
            throw transportFailure("OpenRouter request failed.", exception).redactSecrets(config);
        }
    }

    @Override
    public AiResponse stream(AiRequest request, AiProviderConfig config, AiStreamListener listener)
        throws AiProviderException {
        return invokeAtBoundary(config, () -> streamInternal(request, config, listener));
    }

    private AiResponse streamInternal(
        AiRequest request,
        AiProviderConfig config,
        AiStreamListener listener
    ) throws AiProviderException {
        validateRequest(request);
        validateConfig(config);
        if (request.operation() != AiOperation.TEXT) {
            throw new AiProviderException(
                PROVIDER_ID,
                "OpenRouter streaming is supported only for text requests."
            );
        }
        if (listener == null) {
            throw new AiProviderException(PROVIDER_ID, "A stream listener is required.");
        }

        HttpPost httpRequest = createChatRequest(request, config, true);
        try (CloseableHttpResponse response = httpClient.execute(httpRequest)) {
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
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (IOException exception) {
            throw transportFailure("OpenRouter streaming request failed.", exception).redactSecrets(config);
        }
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }

    private HttpPost createChatRequest(AiRequest aiRequest, AiProviderConfig config, boolean stream)
        throws AiProviderException {
        ObjectNode body = buildChatBody(aiRequest, stream);
        HttpPost request = new HttpPost(endpoint(config, CHAT_COMPLETIONS_PATH));
        configureRequest(request, config, stream);
        request.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));
        return request;
    }

    private AiResponse executeEmbedding(AiRequest request, AiProviderConfig config)
        throws AiProviderException {
        EmbeddingOptions options = requireEmbeddingRequest(request);
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        body.put("dimensions", options.dimensions());
        body.put("encoding_format", "float");
        ArrayNode input = body.putArray("input");
        request.embeddingInputs().forEach(input::add);
        if (options.taskType() != null) {
            body.put("input_type", inputType(options.taskType()));
        }

        HttpPost httpRequest = new HttpPost(endpoint(config, EMBEDDINGS_PATH));
        configureRequest(httpRequest, config, false);
        httpRequest.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse response = httpClient.execute(httpRequest)) {
            int statusCode = response.getStatusLine().getStatusCode();
            String rawResponse = readEntity(response.getEntity());
            ensureSuccessful(statusCode, rawResponse);
            return parseEmbeddingResponse(
                rawResponse,
                request.embeddingInputs().size(),
                options.dimensions()
            );
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (IOException exception) {
            throw transportFailure("OpenRouter embedding request failed.", exception).redactSecrets(config);
        }
    }

    private AiResponse parseEmbeddingResponse(String rawResponse, int expectedCount, int dimensions)
        throws AiProviderException {
        JsonNode root = parseJson(rawResponse, "embedding");
        throwIfPayloadError(root, rawResponse);
        JsonNode data = root.get("data");
        if (data == null || data.isArray() == false || data.size() != expectedCount) {
            throw malformedResponse("OpenRouter returned an unexpected number of embeddings.", rawResponse);
        }

        EmbeddingVector[] ordered = new EmbeddingVector[expectedCount];
        for (JsonNode item : data) {
            JsonNode indexNode = item.get("index");
            if (indexNode == null || indexNode.isIntegralNumber() == false || indexNode.canConvertToInt() == false) {
                throw malformedResponse("OpenRouter embedding response has no valid index.", rawResponse);
            }
            int index = indexNode.intValue();
            if (index < 0 || index >= expectedCount || ordered[index] != null) {
                throw malformedResponse("OpenRouter embedding response contains an invalid index.", rawResponse);
            }
            ordered[index] = parseEmbeddingVector(item.get("embedding"), dimensions, rawResponse);
        }

        for (EmbeddingVector vector : ordered) {
            if (vector == null) {
                throw malformedResponse("OpenRouter embedding response is missing an input index.", rawResponse);
            }
        }
        return new AiResponse(null, List.of(), parseUsage(root.get("usage")), null, List.of(ordered));
    }

    private EmbeddingVector parseEmbeddingVector(JsonNode values, int dimensions, String rawResponse)
        throws AiProviderException {
        if (values == null || values.isArray() == false || values.size() != dimensions) {
            int actual = values != null && values.isArray() ? values.size() : 0;
            throw malformedResponse(
                "OpenRouter returned " + actual + " embedding dimensions, expected " + dimensions + ".",
                rawResponse
            );
        }
        float[] vector = new float[dimensions];
        for (int index = 0; index < dimensions; index++) {
            JsonNode value = values.get(index);
            if (value.isNumber() == false) {
                throw malformedResponse("OpenRouter embedding response contains a non-numeric value.", rawResponse);
            }
            float parsedValue = value.floatValue();
            if (Float.isFinite(parsedValue) == false) {
                throw malformedResponse("OpenRouter embedding response contains a non-finite value.", rawResponse);
            }
            vector[index] = parsedValue;
        }
        return new EmbeddingVector(vector);
    }

    private static String inputType(EmbeddingTaskType taskType) {
        return switch (taskType) {
            case RETRIEVAL_DOCUMENT -> "search_document";
            case RETRIEVAL_QUERY -> "search_query";
        };
    }

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
        if (mediaType.startsWith("image/") == false) {
            throw malformedResponse("OpenRouter generated media is not an image.", rawResponse);
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

    private void validateRequest(AiRequest request) throws AiProviderException {
        if (request == null) {
            throw new AiProviderException(PROVIDER_ID, "OpenRouter request is required.");
        }
        if (isBlank(request.model())) {
            throw new AiProviderException(PROVIDER_ID, "OpenRouter model is required.");
        }
        if (request.operation() == AiOperation.EDIT_IMAGE && request.inputMedia() == null) {
            throw new AiProviderException(PROVIDER_ID, "OpenRouter image editing requires input media.");
        }
    }

    private EmbeddingOptions requireEmbeddingRequest(AiRequest request) throws AiProviderException {
        EmbeddingOptions options = request.embeddingOptions();
        if (options == null) {
            throw new AiProviderException(PROVIDER_ID, "OpenRouter embedding options are required.");
        }
        if (request.embeddingInputs().isEmpty()) {
            throw new AiProviderException(PROVIDER_ID, "At least one OpenRouter embedding input is required.");
        }
        for (String input : request.embeddingInputs()) {
            if (isBlank(input)) {
                throw new AiProviderException(PROVIDER_ID, "OpenRouter embedding inputs must not be blank.");
            }
        }
        return options;
    }

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
        if (isBlank(rawResponse)) {
            throw malformedResponse("OpenRouter returned an empty " + responseName + " response.", rawResponse);
        }
        try {
            return mapper.readTree(rawResponse);
        } catch (JsonProcessingException exception) {
            throw new AiProviderException(
                PROVIDER_ID,
                -1,
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
        if (root.hasNonNull("error")) throw errorResponse(-1, rawResponse);
    }

    private AiProviderException malformedResponse(String message, String rawResponse) {
        return new AiProviderException(PROVIDER_ID, -1, message, rawResponse, false);
    }

    private AiProviderException transportFailure(String message, IOException cause) {
        return new AiProviderException(PROVIDER_ID, -1, message, null, true, cause);
    }

    private static <T> T invokeAtBoundary(AiProviderConfig config, ProviderCall<T> call)
        throws AiProviderException {
        try {
            return call.invoke();
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (RuntimeException exception) {
            throw new AiProviderException(PROVIDER_ID, "Unexpected OpenRouter provider failure", exception)
                .redactSecrets(config);
        }
    }

    @FunctionalInterface
    private interface ProviderCall<T> {
        T invoke() throws AiProviderException;
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 408 || statusCode == 409 || statusCode == 425 || statusCode == 429
            || statusCode >= 500;
    }

    private static String readEntity(HttpEntity entity) throws IOException {
        return entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
    }

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

    private static void collectNumericDetails(JsonNode node, String prefix, Map<String, Long> details) {
        node.fields().forEachRemaining(entry -> {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isIntegralNumber()) {
                details.put(key, value.asLong());
            } else if (value.isObject()) {
                collectNumericDetails(value, key, details);
            }
        });
    }

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
