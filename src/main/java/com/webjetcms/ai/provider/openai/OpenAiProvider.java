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
import com.webjetcms.ai.GeneratedMedia;
import com.webjetcms.ai.ImageOptions;
import com.webjetcms.ai.ModelInfo;
import com.webjetcms.ai.TokenUsage;
import com.webjetcms.ai.security.PromptInjectionDefense;
import com.webjetcms.ai.security.PromptInjectionDefense.UntrustedSource;

/**
 * Framework-neutral REST client for the OpenAI Responses and Images APIs.
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
        return invokeAtBoundary(config, () -> listModelsInternal(config));
    }

    private List<ModelInfo> listModelsInternal(AiProviderConfig config) throws AiProviderException {
        requireConfigured(config);

        HttpGet request = new HttpGet(endpoint(config, MODELS_PATH));
        prepare(request, config, "application/json");
        String response = executeForString(request, config);

        try {
            return parseModels(response);
        } catch (IOException | RuntimeException exception) {
            throw invalidResponse("Could not parse the OpenAI model catalogue", response, exception)
                .redactSecrets(config);
        }
    }

    @Override
    public AiResponse execute(AiRequest request, AiProviderConfig config) throws AiProviderException {
        return invokeAtBoundary(config, () -> executeInternal(request, config));
    }

    private AiResponse executeInternal(AiRequest request, AiProviderConfig config) throws AiProviderException {
        requireRequest(request);
        requireConfigured(config);
        requireModel(request);

        try {
            return switch (request.operation()) {
                case TEXT -> executeText(request, config);
                case GENERATE_IMAGE -> generateImage(request, config);
                case EDIT_IMAGE -> editImage(request, config);
            };
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (RuntimeException exception) {
            throw new AiProviderException(PROVIDER_ID, "Could not create the OpenAI request", exception)
                .redactSecrets(config);
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

        requireRequest(request);
        if (listener == null) {
            throw new AiProviderException(PROVIDER_ID, "OpenAI stream listener is required");
        }
        requireConfigured(config);
        requireModel(request);
        if (request.operation() != AiOperation.TEXT) {
            throw new AiProviderException(PROVIDER_ID, "OpenAI streaming is supported only for text requests");
        }

        ObjectNode body = buildTextBody(request);
        body.put("stream", true);

        HttpPost post = jsonPost(endpoint(config, RESPONSES_PATH), body);
        prepare(post, config, "text/event-stream");

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (isSuccessful(statusCode) == false) {
                String bodyText = readEntity(response.getEntity());
                throw httpError(statusCode, bodyText);
            }

            HttpEntity entity = response.getEntity();
            if (entity == null) {
                throw invalidResponse("OpenAI returned an empty streaming response", "", null);
            }
            Charset charset = responseCharset(entity);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(entity.getContent(), charset))) {
                OpenAiStreamParser.StreamResult result = OpenAiStreamParser.parse(reader, listener);
                return new AiResponse(result.text(), List.of(), result.usage(), result.finishReason());
            }
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (IOException exception) {
            throw transportFailure("OpenAI streaming request failed", exception).redactSecrets(config);
        }
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }

    private AiResponse executeText(AiRequest request, AiProviderConfig config) throws AiProviderException {
        HttpPost post = jsonPost(endpoint(config, RESPONSES_PATH), buildTextBody(request));
        prepare(post, config, "application/json");
        String response = executeForString(post, config);

        try {
            return parseTextResponse(response);
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (IOException | RuntimeException exception) {
            throw invalidResponse("Could not parse the OpenAI text response", response, exception)
                .redactSecrets(config);
        }
    }

    private AiResponse generateImage(AiRequest request, AiProviderConfig config) throws AiProviderException {
        String prompt = imagePrompt(request);
        if (prompt.isBlank()) {
            throw new AiProviderException(PROVIDER_ID, "An image prompt is required");
        }

        ImageOptions options = options(request);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", request.model());
        body.put("prompt", prompt);
        body.put("n", imageCount(options));
        putIfNotBlank(body, "quality", options.quality());
        body.put("size", defaultIfBlank(options.size(), "1024x1024"));
        if ("dall-e-2".equals(request.model()) || "dall-e-3".equals(request.model())) {
            body.put("response_format", "b64_json");
        }

        HttpPost post = jsonPost(endpoint(config, IMAGE_GENERATIONS_PATH), body);
        prepare(post, config, "application/json");
        return executeImage(post, config);
    }

    private AiResponse editImage(AiRequest request, AiProviderConfig config) throws AiProviderException {
        if ("dall-e-2".equals(request.model())) {
            throw new AiProviderException(PROVIDER_ID,
                "Image editing with dall-e-2 is not supported by this provider adapter");
        }

        BinaryContent input = request.inputMedia();
        if (input == null || input.data().length == 0) {
            throw new AiProviderException(PROVIDER_ID, "An input image is required for image editing");
        }

        String prompt = imagePrompt(request);
        if (prompt.isBlank()) {
            throw new AiProviderException(PROVIDER_ID, "An image edit prompt is required");
        }

        HttpPost post = new HttpPost(endpoint(config, IMAGE_EDITS_PATH));
        post.setEntity(buildImageEditEntity(request, input, prompt));
        prepare(post, config, "application/json");
        return executeImage(post, config);
    }

    static HttpEntity buildImageEditEntity(AiRequest request, BinaryContent input, String prompt) {
        ImageOptions options = options(request);
        MultipartEntityBuilder multipart = MultipartEntityBuilder.create()
            .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
            .addTextBody("model", request.model(), TEXT_UTF_8)
            .addTextBody("prompt", prompt, TEXT_UTF_8)
            .addTextBody("n", Integer.toString(imageCount(options)), TEXT_UTF_8)
            .addTextBody("size", defaultIfBlank(options.size(), "1024x1024"), TEXT_UTF_8);
        if (isBlank(options.quality()) == false) {
            multipart.addTextBody("quality", options.quality(), TEXT_UTF_8);
        }
        multipart.addBinaryBody(
            "image",
            input.data(),
            safeContentType(input.mediaType()),
            safeMultipartFileName(input.fileName())
        );
        return multipart.build();
    }

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

    private AiResponse executeImage(HttpPost post, AiProviderConfig config) throws AiProviderException {
        String response = executeForString(post, config);
        try {
            return parseImageResponse(response);
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (IOException | RuntimeException exception) {
            throw invalidResponse("Could not parse the OpenAI image response", response, exception)
                .redactSecrets(config);
        }
    }

    static ObjectNode buildTextBody(AiRequest request) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", request.model());
        body.put("store", request.store());

        ArrayNode inputs = body.putArray("input");
        addTextInput(
            inputs,
            "system",
            PromptInjectionDefense.hardenSystemInstructions(request.instructions())
        );
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

    static AiResponse parseTextResponse(String response) throws IOException, AiProviderException {
        JsonNode root = MAPPER.readTree(response);
        ensureSuccessfulResponse(root, response);

        String text = textOrNull(root.get("output_text"));
        boolean textFound = text != null;
        if (textFound == false) {
            StringBuilder combined = new StringBuilder();
            JsonNode outputs = root.path("output");
            if (outputs.isArray()) {
                for (JsonNode output : outputs) {
                    if ("reasoning".equals(textOrNull(output.get("type")))) {
                        continue;
                    }
                    JsonNode content = output.path("content");
                    if (content.isArray() == false) {
                        continue;
                    }
                    for (JsonNode part : content) {
                        String partText = textOrNull(part.get("text"));
                        if (partText != null) {
                            combined.append(partText);
                            textFound = true;
                        }
                    }
                }
            }
            text = combined.toString();
        }

        if (textFound == false) {
            throw invalidResponse("OpenAI response does not contain output text", response, null);
        }

        return new AiResponse(text, List.of(), parseUsage(root.path("usage")), responseStatus(root));
    }

    static AiResponse parseImageResponse(String response) throws IOException, AiProviderException {
        JsonNode root = MAPPER.readTree(response);
        ensureSuccessfulResponse(root, response);
        JsonNode data = root.path("data");
        if (data.isArray() == false) {
            throw invalidResponse("OpenAI image response does not contain a data array", response, null);
        }

        String defaultFormat = defaultIfBlank(textOrNull(root.get("output_format")), "png");
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

    private static void collectNumericDetails(JsonNode node, String prefix, Map<String, Long> target) {
        node.fields().forEachRemaining(entry -> {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isIntegralNumber()) {
                target.put(key, value.longValue());
            } else if (value.isObject()) {
                collectNumericDetails(value, key, target);
            }
        });
    }

    private String executeForString(HttpRequestBase request, AiProviderConfig config) throws AiProviderException {
        prepare(request, config, "application/json");
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            String body = readEntity(response.getEntity());
            if (isSuccessful(statusCode) == false) {
                throw httpError(statusCode, body);
            }
            return body;
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (IOException exception) {
            throw transportFailure("OpenAI request failed", exception).redactSecrets(config);
        }
    }

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

    private static <T> T invokeAtBoundary(AiProviderConfig config, ProviderCall<T> call)
        throws AiProviderException {
        try {
            return call.invoke();
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (RuntimeException exception) {
            throw new AiProviderException(PROVIDER_ID, "Unexpected OpenAI provider failure", exception)
                .redactSecrets(config);
        }
    }

    @FunctionalInterface
    private interface ProviderCall<T> {
        T invoke() throws AiProviderException;
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

    private static void addTextInput(ArrayNode inputs, String role, String value) {
        if (isBlank(value)) {
            return;
        }
        ObjectNode input = inputs.addObject();
        input.put("role", role);
        input.put("content", value);
    }

    private static ImageOptions options(AiRequest request) {
        return request.imageOptions() == null ? new ImageOptions(null, null, null) : request.imageOptions();
    }

    private static int imageCount(ImageOptions options) {
        return options.count() == null || options.count() < 1 ? 1 : options.count();
    }

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

    private static void putIfNotBlank(ObjectNode target, String name, String value) {
        if (isBlank(value) == false) {
            target.put(name, value);
        }
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

    private AiProviderException httpError(int statusCode, String response) {
        String detail = errorMessage(response);
        String message = "OpenAI request failed (" + statusCode + ")";
        if (isBlank(detail) == false) {
            message += ": " + detail;
        }
        boolean retryable = statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500;
        return new AiProviderException(PROVIDER_ID, statusCode, message, response, retryable);
    }

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

    private static void ensureSuccessfulResponse(JsonNode root, String rawResponse) throws AiProviderException {
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
            throw new AiProviderException(
                PROVIDER_ID,
                200,
                "OpenAI response " + status + (isBlank(reason) ? "" : ": " + reason),
                rawResponse,
                false
            );
        }
    }

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
        return new AiProviderException(PROVIDER_ID, 200, message, response, false, cause);
    }
}
