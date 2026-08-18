package com.webjetcms.ai.provider.gemini;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

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
import com.webjetcms.ai.EmbeddingRequest;
import com.webjetcms.ai.EmbeddingResponse;
import com.webjetcms.ai.EmbeddingVector;
import com.webjetcms.ai.ModelInfo;
import com.webjetcms.ai.TokenUsage;
import com.webjetcms.ai.security.PromptInjectionDefense;
import com.webjetcms.ai.security.PromptInjectionDefense.UntrustedSource;

/** Framework-neutral Google Gemini REST provider. */
public final class GeminiProvider implements AiProvider {

    /** Stable provider identifier used by {@code AiClient}. */
    public static final String PROVIDER_ID = "gemini";

    /** Default base URI for the Google Gemini REST API. */
    public static final URI DEFAULT_BASE_URI = URI.create(
        "https://generativelanguage.googleapis.com/v1beta/"
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ContentType JSON_UTF_8 = ContentType.create("application/json", StandardCharsets.UTF_8);
    private static final int MAX_CONNECTIONS = 100;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 20;

    private final CloseableHttpClient httpClient;

    /** Creates a Gemini provider backed by a reusable, pooled Apache HTTP client. */
    public GeminiProvider() {
        this(HttpClients.custom()
            .useSystemProperties()
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableCookieManagement()
            .setMaxConnTotal(MAX_CONNECTIONS)
            .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
            .build());
    }

    GeminiProvider(CloseableHttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
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
        requireConfigured(config);
        List<ModelInfo> result = new ArrayList<>();
        Set<String> seenPageTokens = new HashSet<>();
        String pageToken = null;

        while (true) {
            HttpGet request = new HttpGet(modelsCollectionUri(config, pageToken));
            configure(request, config, false);

            String payload;
            int statusCode;
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                payload = entityAsString(response.getEntity());
                statusCode = response.getStatusLine().getStatusCode();
                if (isSuccessful(statusCode) == false) {
                    throw responseFailure(statusCode, payload, "Unable to list Gemini models.");
                }
            } catch (AiProviderException exception) {
                throw exception.redactSecrets(config);
            } catch (IOException exception) {
                throw transportFailure("Unable to list Gemini models.", exception).redactSecrets(config);
            }

            try {
                if (payload.isBlank()) {
                    throw new IOException("Gemini returned an empty model catalogue.");
                }
                JsonNode root = MAPPER.readTree(payload);
                JsonNode models = root.path("models");
                if (models.isArray() == false) {
                    throw new IOException("Gemini model response does not contain a models array.");
                }
                for (JsonNode model : models) {
                    String id = normalizeModelId(model.path("name").asText(""));
                    if (id.isBlank()) {
                        continue;
                    }
                    result.add(new ModelInfo(id, model.path("displayName").asText(id)));
                }

                String nextPageToken = root.path("nextPageToken").asText(null);
                if (isNotBlank(nextPageToken) == false) {
                    break;
                }
                if (seenPageTokens.add(nextPageToken) == false) {
                    throw new IOException("Gemini returned a repeated model catalogue page token.");
                }
                pageToken = nextPageToken;
            } catch (IOException | RuntimeException exception) {
                throw invalidResponse(statusCode, "Unable to parse the Gemini model catalogue.", payload, exception)
                    .redactSecrets(config);
            }
        }

        result.sort(Comparator.comparing(ModelInfo::id));
        return List.copyOf(result);
    }

    @Override
    public AiResponse execute(AiRequest request, AiProviderConfig config) throws AiProviderException {
        return invokeAtBoundary(config, () -> executeInternal(request, config));
    }

    private AiResponse executeInternal(AiRequest request, AiProviderConfig config) throws AiProviderException {
        requireConfigured(config);
        validateRequest(request);

        HttpPost post = new HttpPost(operationUri(config, request.model(), "generateContent", false));
        configure(post, config, true);
        post.setEntity(new StringEntity(buildRequestBody(request).toString(), JSON_UTF_8));

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            String payload = entityAsString(response.getEntity());
            int statusCode = response.getStatusLine().getStatusCode();
            if (isSuccessful(statusCode) == false) {
                throw responseFailure(statusCode, payload, "Gemini generation failed.");
            }
            try {
                return GeminiResponseParser.parse(payload);
            } catch (IOException exception) {
                throw new AiProviderException(
                    PROVIDER_ID,
                    statusCode,
                    exception.getMessage(),
                    payload,
                    false,
                    exception
                );
            }
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (IOException exception) {
            throw transportFailure("Gemini generation failed.", exception).redactSecrets(config);
        }
    }

    @Override
    public AiResponse stream(
        AiRequest request,
        AiProviderConfig config,
        AiStreamListener listener
    ) throws AiProviderException {
        return invokeAtBoundary(config, () -> streamInternal(request, config, listener));
    }

    private AiResponse streamInternal(
        AiRequest request,
        AiProviderConfig config,
        AiStreamListener listener
    ) throws AiProviderException {
        requireConfigured(config);
        validateRequest(request);
        if (listener == null) {
            throw new AiProviderException(PROVIDER_ID, "Gemini stream listener is required.");
        }

        HttpPost post = new HttpPost(operationUri(config, request.model(), "streamGenerateContent", true));
        configure(post, config, true);
        post.setHeader("Accept", "text/event-stream");
        post.setHeader("Accept-Encoding", "identity");
        post.setEntity(new StringEntity(buildRequestBody(request).toString(), JSON_UTF_8));

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (isSuccessful(statusCode) == false) {
                String payload = entityAsString(response.getEntity());
                throw responseFailure(statusCode, payload, "Gemini streaming generation failed.");
            }

            HttpEntity entity = response.getEntity();
            if (entity == null) {
                throw new AiProviderException(PROVIDER_ID, "Gemini returned an empty stream.");
            }
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(entity.getContent(), StandardCharsets.UTF_8)
            )) {
                return GeminiResponseParser.parseStream(reader, listener);
            } catch (IOException exception) {
                throw new AiProviderException(
                    PROVIDER_ID,
                    statusCode,
                    exception.getMessage(),
                    null,
                    false,
                    exception
                );
            }
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (IOException exception) {
            throw transportFailure("Gemini streaming generation failed.", exception).redactSecrets(config);
        }
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request, AiProviderConfig config)
        throws AiProviderException {
        return invokeAtBoundary(config, () -> embedInternal(request, config));
    }

    private EmbeddingResponse embedInternal(EmbeddingRequest request, AiProviderConfig config)
        throws AiProviderException {
        requireConfigured(config);
        EmbeddingOptions options = requireEmbeddingRequest(request);
        Integer dimensions = options.dimensions();
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode requests = body.putArray("requests");
        String modelResource = "models/" + normalizeModelId(request.model());
        for (String input : request.inputs()) {
            ObjectNode item = requests.addObject();
            item.put("model", modelResource);
            item.putObject("content").putArray("parts").addObject().put("text", input);
            if (dimensions != null) {
                item.putObject("embedContentConfig")
                    .put("outputDimensionality", dimensions);
            }
        }

        HttpPost post = new HttpPost(operationUri(
            config,
            request.model(),
            "batchEmbedContents",
            false
        ));
        configure(post, config, true);
        post.setEntity(new StringEntity(body.toString(), JSON_UTF_8));

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            String payload = entityAsString(response.getEntity());
            int statusCode = response.getStatusLine().getStatusCode();
            if (isSuccessful(statusCode) == false) {
                throw responseFailure(statusCode, payload, "Gemini embedding request failed.");
            }
            try {
                return parseEmbeddingResponse(
                    request.model(),
                    request.inputs().size(),
                    payload,
                    dimensions,
                    statusCode
                );
            } catch (IOException exception) {
                throw invalidResponse(
                    statusCode,
                    "Unable to parse the Gemini embedding response.",
                    payload,
                    exception
                );
            }
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (IOException exception) {
            throw transportFailure("Gemini embedding request failed.", exception).redactSecrets(config);
        }
    }

    private static EmbeddingResponse parseEmbeddingResponse(
        String model,
        int expectedCount,
        String payload,
        Integer requestedDimensions,
        int statusCode
    ) throws IOException, AiProviderException {
        JsonNode root = MAPPER.readTree(payload);
        JsonNode embeddings = root == null ? null : root.get("embeddings");
        if (embeddings == null || embeddings.isArray() == false || embeddings.size() != expectedCount) {
            throw invalidResponse(
                statusCode,
                "Gemini returned an unexpected number of embeddings.",
                payload,
                null
            );
        }

        List<EmbeddingVector> vectors = new ArrayList<>(expectedCount);
        int dimensions = requestedDimensions == null ? -1 : requestedDimensions;
        for (JsonNode embedding : embeddings) {
            JsonNode values = embedding.get("values");
            int actual = values != null && values.isArray() ? values.size() : 0;
            if (actual < 1) {
                throw invalidResponse(
                    statusCode,
                    "Gemini returned an empty embedding vector.",
                    payload,
                    null
                );
            }
            if (dimensions < 0) {
                dimensions = actual;
            } else if (actual != dimensions) {
                throw invalidResponse(
                    statusCode,
                    "Gemini returned " + actual + " embedding dimensions, expected " + dimensions + ".",
                    payload,
                    null
                );
            }
            float[] vector = new float[dimensions];
            for (int index = 0; index < dimensions; index++) {
                JsonNode value = values.get(index);
                if (value.isNumber() == false) {
                    throw invalidResponse(
                        statusCode,
                        "Gemini embedding response contains a non-numeric value.",
                        payload,
                        null
                    );
                }
                float parsedValue = value.floatValue();
                if (Float.isFinite(parsedValue) == false) {
                    throw invalidResponse(
                        statusCode,
                        "Gemini embedding response contains a non-finite value.",
                        payload,
                        null
                    );
                }
                vector[index] = parsedValue;
            }
            if (isReducedGeminiEmbedding001(model, dimensions)) {
                normalize(vector, statusCode, payload);
            }
            vectors.add(new EmbeddingVector(vector));
        }

        long inputTokens = root.path("usageMetadata").path("promptTokenCount").asLong(0);
        TokenUsage usage = new TokenUsage(inputTokens, 0, inputTokens, null);
        return new EmbeddingResponse(vectors, usage);
    }

    private static boolean isReducedGeminiEmbedding001(String model, int dimensions) {
        return dimensions < 3072 && "gemini-embedding-001".equals(normalizeModelId(model));
    }

    private static void normalize(float[] vector, int statusCode, String payload)
        throws AiProviderException {
        double sumOfSquares = 0;
        for (float value : vector) {
            double doubleValue = value;
            sumOfSquares += doubleValue * doubleValue;
        }
        if (sumOfSquares <= 0 || Double.isFinite(sumOfSquares) == false) {
            throw invalidResponse(
                statusCode,
                "Gemini embedding response contains a vector that cannot be normalized.",
                payload,
                null
            );
        }

        double norm = Math.sqrt(sumOfSquares);
        if (norm <= 0 || Double.isFinite(norm) == false) {
            throw invalidResponse(
                statusCode,
                "Gemini embedding response contains a vector that cannot be normalized.",
                payload,
                null
            );
        }
        for (int index = 0; index < vector.length; index++) {
            vector[index] = (float) (vector[index] / norm);
        }
    }

    static ObjectNode buildRequestBody(AiRequest request) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode userContent = contents.addObject();
        userContent.put("role", "user");
        ArrayNode parts = userContent.putArray("parts");

        if (request.operation() == AiOperation.TEXT) {
            addProtectedTextPart(parts, request.inputText(), UntrustedSource.INPUT_TEXT);
        } else {
            addTextPart(parts, PromptInjectionDefense.getTaskInstructions(request.instructions()));
            if (request.operation() == AiOperation.GENERATE_IMAGE) {
                addProtectedTextPart(parts, request.inputText(), UntrustedSource.INPUT_TEXT);
            }
        }
        addProtectedTextPart(parts, request.userPrompt(), UntrustedSource.USER_PROMPT);
        addMediaPart(parts, request.inputMedia());
        if (parts.isEmpty()) {
            addTextPart(parts, "Apply the task instructions to the provided data.");
        }

        String systemInstructions = request.operation() == AiOperation.TEXT
            ? PromptInjectionDefense.hardenSystemInstructions(request.instructions())
            : PromptInjectionDefense.getSecurityInstructions(request.instructions());
        if (isNotBlank(systemInstructions)) {
            ObjectNode systemInstruction = root.putObject("systemInstruction");
            systemInstruction.putArray("parts").addObject().put("text", systemInstructions);
        }

        String modality = request.operation() == AiOperation.TEXT ? "TEXT" : "IMAGE";
        root.putObject("generationConfig").putArray("responseModalities").add(modality);
        return root;
    }

    private static void addTextPart(ArrayNode parts, String value) {
        if (isNotBlank(value)) {
            parts.addObject().put("text", value);
        }
    }

    private static void addProtectedTextPart(
        ArrayNode parts,
        String value,
        UntrustedSource source
    ) {
        addTextPart(
            parts,
            PromptInjectionDefense.protectUntrustedText(value, source).protectedText()
        );
    }

    private static void addMediaPart(ArrayNode parts, BinaryContent media) {
        if (media == null || media.data().length == 0) {
            return;
        }
        ObjectNode inlineData = parts.addObject().putObject("inlineData");
        inlineData.put("mimeType", media.mediaType());
        inlineData.put("data", Base64.getEncoder().encodeToString(media.data()));
    }

    private void configure(HttpRequestBase request, AiProviderConfig config, boolean hasJsonBody) {
        request.setConfig(requestConfig(config));
        for (Entry<String, String> header : config.trustedHeaders().entrySet()) {
            request.setHeader(header.getKey(), header.getValue());
        }
        request.setHeader("x-goog-api-key", config.apiKey());
        request.setHeader("Accept", "application/json");
        if (hasJsonBody) {
            request.setHeader("Content-Type", JSON_UTF_8.toString());
        }
    }

    private static RequestConfig requestConfig(AiProviderConfig config) {
        return RequestConfig.custom()
            .setConnectTimeout(config.connectTimeoutMillis())
            .setConnectionRequestTimeout(config.connectTimeoutMillis())
            .setSocketTimeout(config.responseTimeoutMillis())
            .build();
    }

    private static URI modelsBaseUri(AiProviderConfig config) {
        URI baseUri = config.baseUri() == null ? DEFAULT_BASE_URI : config.baseUri();
        String value = baseUri.toString();
        if (value.endsWith("/") == false) {
            value += "/";
        }
        if (URI.create(value).getPath().endsWith("/models/")) {
            return URI.create(value);
        }
        return URI.create(value).resolve("models/");
    }

    private static URI modelsCollectionUri(AiProviderConfig config, String pageToken) {
        String modelsUri = modelsBaseUri(config).toString();
        StringBuilder value = new StringBuilder(modelsUri.substring(0, modelsUri.length() - 1))
            .append("?pageSize=1000");
        if (isNotBlank(pageToken)) {
            value.append("&pageToken=").append(encodeUriValue(pageToken));
        }
        return URI.create(value.toString());
    }

    private static URI operationUri(
        AiProviderConfig config,
        String model,
        String operation,
        boolean sse
    ) {
        String normalizedModel = normalizeModelId(model);
        StringBuilder value = new StringBuilder(modelsBaseUri(config).toString());
        String[] segments = normalizedModel.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                value.append('/');
            }
            value.append(encodeUriValue(segments[i]));
        }
        value.append(':').append(operation);
        if (sse) {
            value.append("?alt=sse");
        }
        return URI.create(value.toString());
    }

    private static String encodeUriValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String normalizeModelId(String model) {
        String normalized = model == null ? "" : model.trim();
        if (normalized.startsWith("models/")) {
            normalized = normalized.substring("models/".length());
        }
        return normalized;
    }

    private static void validateRequest(AiRequest request) throws AiProviderException {
        if (request == null) {
            throw new AiProviderException(PROVIDER_ID, "Gemini request is required.");
        }
        if (isNotBlank(normalizeModelId(request.model())) == false) {
            throw new AiProviderException(PROVIDER_ID, "Gemini model is required.");
        }
        if (request.operation() == AiOperation.EDIT_IMAGE
            && (request.inputMedia() == null || request.inputMedia().data().length == 0)) {
            throw new AiProviderException(PROVIDER_ID, "Gemini image editing requires input media.");
        }
    }

    private static EmbeddingOptions requireEmbeddingRequest(EmbeddingRequest request)
        throws AiProviderException {
        if (request == null) {
            throw new AiProviderException(PROVIDER_ID, "Gemini embedding request is required.");
        }
        if (isNotBlank(normalizeModelId(request.model())) == false) {
            throw new AiProviderException(PROVIDER_ID, "Gemini embedding model is required.");
        }
        if (request.inputs().isEmpty()) {
            throw new AiProviderException(PROVIDER_ID, "At least one Gemini embedding input is required.");
        }
        for (String input : request.inputs()) {
            if (isNotBlank(input) == false) {
                throw new AiProviderException(PROVIDER_ID, "Gemini embedding inputs must not be blank.");
            }
        }
        return request.options();
    }

    private static void requireConfigured(AiProviderConfig config) throws AiProviderException {
        if (config == null || config.isConfigured() == false) {
            throw new AiProviderException(PROVIDER_ID, "Gemini API key is not configured.");
        }
    }

    private static String entityAsString(HttpEntity entity) throws IOException {
        return entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
    }

    private static boolean isSuccessful(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static AiProviderException responseFailure(int statusCode, String payload, String fallbackMessage) {
        String message = extractErrorMessage(payload);
        if (message == null || message.isBlank()) {
            message = fallbackMessage;
        }
        return new AiProviderException(
            PROVIDER_ID,
            statusCode,
            "(" + statusCode + ") " + message,
            payload,
            isRetryable(statusCode)
        );
    }

    private static String extractErrorMessage(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(payload);
            if (root.isArray() && root.isEmpty() == false) {
                root = root.get(0);
            }
            String message = root.path("error").path("message").asText(null);
            return isNotBlank(message) ? message : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private static AiProviderException transportFailure(String message, IOException exception) {
        return new AiProviderException(PROVIDER_ID, -1, message, null, true, exception);
    }

    private static <T> T invokeAtBoundary(AiProviderConfig config, ProviderCall<T> call)
        throws AiProviderException {
        try {
            return call.invoke();
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (RuntimeException exception) {
            throw new AiProviderException(PROVIDER_ID, "Unexpected Gemini provider failure", exception)
                .redactSecrets(config);
        }
    }

    @FunctionalInterface
    private interface ProviderCall<T> {
        T invoke() throws AiProviderException;
    }

    private static AiProviderException invalidResponse(
        int statusCode,
        String message,
        String payload,
        Throwable cause
    ) {
        return new AiProviderException(PROVIDER_ID, statusCode, message, payload, false, cause);
    }

    private static boolean isNotBlank(String value) {
        return value != null && value.isBlank() == false;
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }
}
