package com.webjetcms.ai.provider.openrouter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webjetcms.ai.AiClient;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.BinaryContent;
import com.webjetcms.ai.EmbeddingOptions;
import com.webjetcms.ai.EmbeddingRequest;
import com.webjetcms.ai.EmbeddingResponse;
import com.webjetcms.ai.image.ImageOptionDefinition;
import com.webjetcms.ai.image.ImageOptions;

class OpenRouterProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void exposesImmutableSnapshotOptionsForKnownImageModels() throws Exception {
        try (OpenRouterProvider provider = new OpenRouterProvider()) {
            Map<String, ImageOptionDefinition> openAi = provider.imageOptions(
                "openai/gpt-image-2",
                AiOperation.GENERATE_IMAGE
            );
            assertEquals(
                List.of("count", "aspect_ratio", "quality", "background", "output_compression"),
                List.copyOf(openAi.keySet())
            );
            assertTrue(provider.imageOptions(
                "recraft/recraft-v4-vector",
                AiOperation.GENERATE_IMAGE
            ).isEmpty());
            assertEquals(
                List.of("count", "aspect_ratio"),
                List.copyOf(provider.imageOptions(
                    "recraft/recraft-v4",
                    AiOperation.GENERATE_IMAGE
                ).keySet())
            );
            assertTrue(provider.imageOptions(
                "future/image-model",
                AiOperation.GENERATE_IMAGE
            ).isEmpty());
            assertEquals("2026-08-25", OpenRouterProvider.IMAGE_MODELS_CATALOGUE_VERSION);
            assertThrows(UnsupportedOperationException.class, openAi::clear);
        }
    }

    @Test
    void usesDedicatedImagesEndpointAndParsesMimeAwareResponse() throws Exception {
        byte[] generatedImage = { 9, 8, 7, 6 };
        byte[] sourceImage = { 5, 4, 3, 2 };
        RecordingHttpClient transport = new RecordingHttpClient(200, """
            {
              "data": [{"b64_json": "%s", "media_type": "image/webp"}],
              "usage": {"prompt_tokens": 10, "completion_tokens": 4, "total_tokens": 14}
            }
            """.formatted(Base64.getEncoder().encodeToString(generatedImage)));
        AiRequest request = AiRequest.builder()
            .operation(AiOperation.GENERATE_IMAGE)
            .model("openai/gpt-image-2")
            .inputText("A paper-cut forest")
            .inputMedia(new BinaryContent(sourceImage, "image/jpeg", "reference.jpg"))
            .imageOptions(ImageOptions.builder()
                .count(2)
                .quality("high")
                .build())
            .build();

        AiResponse response;
        try (OpenRouterProvider provider = new OpenRouterProvider(transport, MAPPER)) {
            response = provider.execute(request, embeddingConfig("key", "trusted"));
        }

        assertEquals("POST", transport.method);
        assertEquals("https://example.test/router/v1/images", transport.uri);
        JsonNode body = MAPPER.readTree(transport.requestBody);
        assertEquals("openai/gpt-image-2", body.path("model").asText());
        assertEquals(2, body.path("n").asInt());
        assertEquals("high", body.path("quality").asText());
        assertEquals(
            "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(sourceImage),
            body.path("input_references").get(0).path("image_url").path("url").asText()
        );
        assertArrayEquals(generatedImage, response.media().get(0).data());
        assertEquals("image/webp", response.media().get(0).mediaType());
        assertEquals(14, response.usage().totalTokens());
    }

    @Test
    void rejectsControlOnlyImagePromptsDirectlyAndAfterClientPreparation() throws Exception {
        RecordingHttpClient direct = new RecordingHttpClient(200, "{}");
        try (OpenRouterProvider provider = new OpenRouterProvider(direct, MAPPER)) {
            assertImagePromptsRejected(request -> provider.execute(request, testConfig()));
        }

        RecordingHttpClient prepared = new RecordingHttpClient(200, "{}");
        try (AiClient client = AiClient.of(new OpenRouterProvider(prepared, MAPPER))) {
            assertImagePromptsRejected(request -> client.execute(request, testConfig()));
        }

        assertEquals(0, direct.calls + prepared.calls);
    }

    @Test
    void rejectsEmptyEditMediaBeforeTransport() throws Exception {
        RecordingHttpClient transport = new RecordingHttpClient(200, "{}");
        AiRequest request = AiRequest.builder()
            .operation(AiOperation.EDIT_IMAGE)
            .model("openai/gpt-image-2")
            .instructions("Replace the background")
            .inputMedia(new BinaryContent(new byte[0], "image/png", "empty.png"))
            .build();

        try (OpenRouterProvider provider = new OpenRouterProvider(transport, MAPPER)) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> provider.execute(request, embeddingConfig("key", "trusted"))
            );

            assertTrue(exception.getMessage().contains("must not be empty"));
        }
        assertEquals(0, transport.calls);
    }

    @ParameterizedTest(name = "malformed image response [{index}]")
    @MethodSource("invalidImageResponses")
    void rejectsMalformedImageResponsesAndPreservesStatus(String payload) throws Exception {
        RecordingHttpClient transport = new RecordingHttpClient(206, payload);

        try (OpenRouterProvider provider = new OpenRouterProvider(transport, MAPPER)) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> provider.execute(imageRequest("openai/gpt-image-2"), embeddingConfig("key", "trusted"))
            );

            assertEquals(206, exception.statusCode());
            assertEquals(payload, exception.rawResponse());
        }
    }

    @Test
    void infersMimeTypeFromSingleFormatModelWhenResponseOmitsIt() throws Exception {
        byte[] generatedImage = { 9, 7, 5, 3 };
        RecordingHttpClient transport = new RecordingHttpClient(200, """
            {"data":[{"b64_json":"%s"}]}
            """.formatted(Base64.getEncoder().encodeToString(generatedImage)));

        try (OpenRouterProvider provider = new OpenRouterProvider(transport, MAPPER)) {
            AiResponse response = provider.execute(
                imageRequest("sourceful/riverflow-v2.5-fast"),
                embeddingConfig("key", "trusted")
            );

            assertArrayEquals(generatedImage, response.media().get(0).data());
            assertEquals("image/jpeg", response.media().get(0).mediaType());
        }
    }

    @ParameterizedTest(name = "accepts {0}")
    @MethodSource("supportedRasterMediaTypes")
    void acceptsSupportedRasterMediaTypes(String mediaType) throws Exception {
        String payload = """
            {"data":[{"b64_json":"AQI=","media_type":"%s"}]}
            """.formatted(mediaType);

        try (OpenRouterProvider provider = new OpenRouterProvider()) {
            AiResponse response = provider.parseImageResponse(
                payload,
                null,
                "openai/gpt-image-2",
                200
            );

            assertEquals(mediaType, response.media().get(0).mediaType());
        }
    }

    @Test
    void rejectsSvgFromImageAndLegacyCompletionResponses() throws Exception {
        try (OpenRouterProvider provider = new OpenRouterProvider()) {
            AiProviderException imageResponse = assertThrows(
                AiProviderException.class,
                () -> provider.parseImageResponse(
                    "{\"data\":[{\"b64_json\":\"AQI=\"}]}",
                    "svg",
                    "recraft/recraft-v4-vector",
                    200
                )
            );
            AiProviderException legacyResponse = assertThrows(
                AiProviderException.class,
                () -> provider.parseCompletion(
                    "{\"choices\":[{\"message\":{\"images\":[{\"image_url\":{\"url\":"
                        + "\"data:image/svg+xml;base64,AQI=\"}}]},\"finish_reason\":\"stop\"}]}",
                    AiOperation.GENERATE_IMAGE
                )
            );

            assertEquals(200, imageResponse.statusCode());
            for (AiProviderException exception : List.of(imageResponse, legacyResponse)) {
                assertTrue(exception.getMessage().contains("unsupported image media type"));
            }
        }
    }

    @Test
    void redactsSecretsFromImageEndpointFailures() throws Exception {
        AiProviderConfig config = embeddingConfig("router-secret", "trusted-secret");
        String payload = """
            {"error":{"message":"Rejected router-secret and trusted-secret"}}
            """;
        RecordingHttpClient transport = new RecordingHttpClient(503, payload);

        try (OpenRouterProvider provider = new OpenRouterProvider(transport, MAPPER)) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> provider.execute(imageRequest("openai/gpt-image-2"), config)
            );

            assertEquals(503, exception.statusCode());
            assertTrue(exception.retryable());
            assertRedacted(exception, "router-secret", "trusted-secret");
        }
    }

    @Test
    void embedsOrderedBatchAndBuildsAuthenticatedRequest() throws Exception {
        RecordingHttpClient transport = new RecordingHttpClient(200, """
            {
              "data": [
                {"index": 1, "embedding": [0.3, 0.4]},
                {"index": 0, "embedding": [0.1, 0.2]}
              ],
              "usage": {"prompt_tokens": 7, "total_tokens": 7}
            }
            """);
        AiProviderConfig config = embeddingConfig("router-secret", "trusted-secret");

        try (OpenRouterProvider provider = new OpenRouterProvider(transport, MAPPER)) {
            EmbeddingResponse response = provider.embed(
                new EmbeddingRequest(
                    "openai/text-embedding-3-small",
                    List.of("first", "second"),
                    new EmbeddingOptions(2)
                ),
                config
            );

            assertArrayEquals(new float[] {0.1F, 0.2F}, response.embeddings().get(0).values());
            assertArrayEquals(new float[] {0.3F, 0.4F}, response.embeddings().get(1).values());
            assertEquals(7, response.usage().inputTokens());
            assertEquals(7, response.usage().totalTokens());
        }

        assertEquals("POST", transport.method);
        assertEquals("https://example.test/router/v1/embeddings", transport.uri);
        assertEquals("Bearer router-secret", transport.header(HttpHeaders.AUTHORIZATION));
        assertEquals("trusted-secret", transport.header("X-Test-Secret"));
        assertEquals("application/json", transport.header(HttpHeaders.ACCEPT));
        JsonNode requestBody = MAPPER.readTree(transport.requestBody);
        assertEquals("openai/text-embedding-3-small", requestBody.path("model").asText());
        assertEquals(2, requestBody.path("dimensions").asInt());
        assertEquals("float", requestBody.path("encoding_format").asText());
        assertEquals(List.of("first", "second"), MAPPER.convertValue(requestBody.path("input"), List.class));
        assertFalse(requestBody.has("input_type"));

        RecordingHttpClient defaultsTransport = new RecordingHttpClient(200, """
            {
              "data": [
                {"index": 0, "embedding": [0.1, 0.2, 0.3]},
                {"index": 1, "embedding": [0.4, 0.5, 0.6]}
              ]
            }
            """);

        try (OpenRouterProvider provider = new OpenRouterProvider(defaultsTransport, MAPPER)) {
            EmbeddingResponse response = provider.embed(
                new EmbeddingRequest("thenlper/gte-base", List.of("first", "second")),
                embeddingConfig("key", "trusted")
            );

            assertEquals(3, response.embeddings().get(0).dimensions());
            assertEquals(3, response.embeddings().get(1).dimensions());
        }

        assertFalse(MAPPER.readTree(defaultsTransport.requestBody).has("dimensions"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEmbeddingResponses")
    void rejectsMalformedEmbeddingResponses(
        String description,
        int status,
        String payload,
        Integer dimensions
    ) throws Exception {
        RecordingHttpClient transport = new RecordingHttpClient(status, payload);

        try (OpenRouterProvider provider = new OpenRouterProvider(transport, MAPPER)) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> provider.embed(
                    new EmbeddingRequest("embedding-model", List.of("input"), new EmbeddingOptions(dimensions)),
                    embeddingConfig("key", "trusted")
                )
            );

            assertEquals(status, exception.statusCode(), description);
            assertEquals(payload, exception.rawResponse(), description);
            assertFalse(exception.retryable(), description);
        }
    }

    @Test
    void classifiesAndRedactsEmbeddingFailures() throws Exception {
        AiProviderConfig config = embeddingConfig("router-secret", "trusted-secret");
        String payloadError = """
            {"error":{"code":429,"message":"Rejected router-secret and trusted-secret"}}
            """;
        RecordingHttpClient rateLimited = new RecordingHttpClient(200, payloadError);

        try (OpenRouterProvider provider = new OpenRouterProvider(rateLimited, MAPPER)) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> provider.embed(new EmbeddingRequest("model", List.of("input")), config)
            );

            assertEquals(429, exception.statusCode());
            assertTrue(exception.retryable());
            assertRedacted(exception, "router-secret", "trusted-secret");
        }

        String httpError = """
            {"error":{"message":"Unavailable router-secret and trusted-secret"}}
            """;
        RecordingHttpClient unavailable = new RecordingHttpClient(503, httpError);
        try (OpenRouterProvider provider = new OpenRouterProvider(unavailable, MAPPER)) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> provider.embed(new EmbeddingRequest("model", List.of("input")), config)
            );

            assertEquals(503, exception.statusCode());
            assertTrue(exception.retryable());
            assertRedacted(exception, "router-secret", "trusted-secret");
        }

        RecordingHttpClient timedOut = new RecordingHttpClient(
            new SocketTimeoutException("router-secret trusted-secret timed out")
        );

        try (OpenRouterProvider provider = new OpenRouterProvider(timedOut, MAPPER)) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> provider.embed(new EmbeddingRequest("model", List.of("input")), config)
            );

            assertEquals(-1, exception.statusCode());
            assertTrue(exception.retryable());
            assertRedacted(exception, "router-secret", "trusted-secret");
        }
    }

    @Test
    void validatesEmbeddingRequestsBeforeTransport() throws Exception {
        RecordingHttpClient transport = new RecordingHttpClient(200, "{}");
        AiProviderConfig config = embeddingConfig("key", "trusted");

        try (OpenRouterProvider provider = new OpenRouterProvider(transport, MAPPER)) {
            assertThrows(AiProviderException.class, () -> provider.embed(null, config));
            assertThrows(
                AiProviderException.class,
                () -> provider.embed(new EmbeddingRequest(" ", List.of("input")), config)
            );
        }

        assertEquals(0, transport.calls);
    }

    @Test
    void imageGenerationSeparatesSecurityRulesFromTaskAndUntrustedInput() throws Exception {
        try (OpenRouterProvider provider = new OpenRouterProvider()) {
            AiRequest request = AiRequest.builder()
                .operation(AiOperation.GENERATE_IMAGE)
                .model("image-model")
                .instructions("Create a clean icon.")
                .inputText("Draw a blue rocket.")
                .userPrompt("Use a transparent background.")
                .build();

            String prompt = provider.buildImageBody(request).path("prompt").asText();

            assertTrue(prompt.contains("[AI_PROMPT_SECURITY_RULES_BEGIN]"));
            assertTrue(prompt.contains("[TASK_INSTRUCTIONS_BEGIN]"));
            assertTrue(prompt.contains("Create a clean icon."));
            assertTrue(prompt.contains("[BEGIN_UNTRUSTED_INPUT_TEXT]"));
            assertTrue(prompt.contains("[BEGIN_UNTRUSTED_USER_PROMPT]"));
        }
    }

    @Test
    void imageEditingPlacesTheImageAfterTaskAndUserPrompt() throws Exception {
        byte[] sourceImage = { 1, 2, 3, 4 };
        try (OpenRouterProvider provider = new OpenRouterProvider()) {
            AiRequest request = AiRequest.builder()
                .operation(AiOperation.EDIT_IMAGE)
                .model("image-model")
                .instructions("Remove the background.")
                .inputText("/host-specific/path/source.png")
                .userPrompt("Keep the shadow.")
                .inputMedia(new BinaryContent(sourceImage, "image/png", "source.png"))
                .build();

            JsonNode body = provider.buildImageBody(request);
            String prompt = body.path("prompt").asText();

            assertTrue(prompt.contains("[TASK_INSTRUCTIONS_BEGIN]"));
            assertTrue(prompt.contains("[BEGIN_UNTRUSTED_USER_PROMPT]"));
            assertFalse(prompt.contains("[BEGIN_UNTRUSTED_INPUT_TEXT]"));
            assertFalse(prompt.contains("/host-specific/path/source.png"));
            assertEquals("image_url", body.path("input_references").get(0).path("type").asText());
            assertEquals(
                "data:image/png;base64," + Base64.getEncoder().encodeToString(sourceImage),
                body.path("input_references").get(0).path("image_url").path("url").asText()
            );
        }
    }

    @Test
    void rejectsPartialOrFilteredCompletionFinishReasons() throws Exception {
        String responseJson = """
            {
              "choices": [{
                "message": {"content": "Partial response"},
                "finish_reason": "length"
              }]
            }
            """;

        try (OpenRouterProvider provider = new OpenRouterProvider()) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> provider.parseCompletion(responseJson, AiOperation.TEXT)
            );

            assertTrue(exception.getMessage().contains("length"));
            assertFalse(exception.retryable());
        }
    }

    private static Stream<String> invalidImageResponses() {
        return Stream.of(
            "", "{not-json", "{}", "{\"error\":{\"message\":\"failed\"}}",
            "{\"data\":[]}", "{\"data\":[\"image\"]}", "{\"data\":[{}]}",
            "{\"data\":[{\"b64_json\":1234,\"media_type\":\"image/png\"}]}",
            "{\"data\":[{\"b64_json\":\"%%%\",\"media_type\":\"image/png\"}]}",
            "{\"data\":[{\"b64_json\":\"AQI=\",\"media_type\":12}]}",
            "{\"data\":[{\"b64_json\":\"AQI=\",\"media_type\":\"text/plain\"}]}",
            "{\"data\":[{\"b64_json\":\"AQI=\",\"media_type\":\"image/svg+xml\"}]}",
            "{\"data\":[{\"b64_json\":\"AQI=\",\"media_type\":\"image/gif\"}]}"
        );
    }

    private static Stream<String> supportedRasterMediaTypes() {
        return Stream.of("image/png", "image/jpeg", "image/jpg", "image/webp", "IMAGE/PNG; charset=binary");
    }

    private static void assertImagePromptsRejected(ImageRequestExecutor executor) {
        for (AiRequest request : invalidImagePromptRequests()) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> executor.execute(request)
            );
            assertTrue(exception.getMessage().contains("image prompt is required"));
        }
    }

    private static List<AiRequest> invalidImagePromptRequests() {
        String controlOnly = "\u0000\u200B\u2060";
        return List.of(
            imageRequest("openai/gpt-image-2", null),
            imageRequest("openai/gpt-image-2", controlOnly),
            AiRequest.builder()
                .operation(AiOperation.EDIT_IMAGE)
                .model("openai/gpt-image-2")
                .inputText("/ignored/source.png")
                .userPrompt(controlOnly)
                .inputMedia(new BinaryContent(new byte[] { 1 }, "image/png", "source.png"))
                .build()
        );
    }

    private static AiRequest imageRequest(String model) {
        return imageRequest(model, "A lighthouse at dusk");
    }

    private static AiRequest imageRequest(String model, String inputText) {
        return AiRequest.builder()
            .operation(AiOperation.GENERATE_IMAGE)
            .model(model)
            .inputText(inputText)
            .build();
    }

    private static AiProviderConfig testConfig() {
        return embeddingConfig("key", "trusted");
    }

    private static Stream<Arguments> invalidEmbeddingResponses() {
        return Stream.of(
            Arguments.of("empty response", 200, "", null),
            Arguments.of("malformed JSON", 200, "{not-json", null),
            Arguments.of("malformed shape preserves status", 206, "{}", null),
            Arguments.of(
                "dimension mismatch",
                200,
                "{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}",
                2
            )
        );
    }

    private static AiProviderConfig embeddingConfig(String apiKey, String trustedValue) {
        return AiProviderConfig.builder(apiKey)
            .baseUri(URI.create("https://example.test/router/v1/"))
            .trustedHeader("X-Test-Secret", trustedValue)
            .build();
    }

    private static void assertRedacted(AiProviderException exception, String... secrets) {
        String exposed = exception + "\n" + exception.rawResponse() + "\n" + exception.getCause();
        for (String secret : secrets) {
            assertFalse(exposed.contains(secret));
        }
    }

    @FunctionalInterface
    private interface ImageRequestExecutor {
        AiResponse execute(AiRequest request) throws AiProviderException;
    }

    private static final class RecordingHttpClient extends CloseableHttpClient {
        private final int responseStatus;
        private final String responseBody;
        private final IOException failure;
        private final List<org.apache.http.Header> headers = new ArrayList<>();
        private int calls;
        private String method;
        private String uri;
        private String requestBody;

        private RecordingHttpClient(int responseStatus, String responseBody) {
            this.responseStatus = responseStatus;
            this.responseBody = responseBody;
            failure = null;
        }

        private RecordingHttpClient(IOException failure) {
            responseStatus = -1;
            responseBody = null;
            this.failure = failure;
        }

        @Override
        protected CloseableHttpResponse doExecute(
            HttpHost target,
            HttpRequest request,
            HttpContext context
        ) throws IOException {
            calls++;
            method = request.getRequestLine().getMethod();
            uri = request.getRequestLine().getUri();
            headers.clear();
            headers.addAll(List.of(request.getAllHeaders()));
            if (request instanceof HttpEntityEnclosingRequest enclosingRequest) {
                requestBody = EntityUtils.toString(enclosingRequest.getEntity(), StandardCharsets.UTF_8);
            }
            if (failure != null) {
                throw failure;
            }
            return new StubResponse(responseStatus, responseBody);
        }

        private String header(String name) {
            return headers.stream()
                .filter(header -> header.getName().equalsIgnoreCase(name))
                .map(org.apache.http.Header::getValue)
                .findFirst()
                .orElse(null);
        }

        @Override
        public void close() {
            // Nothing to close in the deterministic test transport.
        }

        @Override
        @SuppressWarnings("deprecation")
        public HttpParams getParams() {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public ClientConnectionManager getConnectionManager() {
            return null;
        }
    }

    private static final class StubResponse extends BasicHttpResponse implements CloseableHttpResponse {
        private StubResponse(int statusCode, String body) {
            super(HttpVersion.HTTP_1_1, statusCode, "");
            setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        }

        @Override
        public void close() {
            // Nothing to close in the in-memory response.
        }
    }
}
