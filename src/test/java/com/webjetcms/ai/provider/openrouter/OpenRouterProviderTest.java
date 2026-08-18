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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.BinaryContent;
import com.webjetcms.ai.EmbeddingOptions;
import com.webjetcms.ai.EmbeddingRequest;
import com.webjetcms.ai.EmbeddingResponse;

class OpenRouterProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

            ObjectNode body = provider.buildChatBody(request, false);
            JsonNode messages = body.path("messages");
            JsonNode content = messages.get(1).path("content");

            assertEquals("system", messages.get(0).path("role").asText());
            assertTrue(messages.get(0).path("content").asText().contains("[AI_PROMPT_SECURITY_RULES_BEGIN]"));
            assertFalse(messages.get(0).path("content").asText().contains("Create a clean icon."));
            assertTrue(content.get(0).path("text").asText().contains("[TASK_INSTRUCTIONS_BEGIN]"));
            assertTrue(content.get(0).path("text").asText().contains("Create a clean icon."));
            assertTrue(content.get(1).path("text").asText().contains("[BEGIN_UNTRUSTED_INPUT_TEXT]"));
            assertTrue(content.get(2).path("text").asText().contains("[BEGIN_UNTRUSTED_USER_PROMPT]"));
            assertEquals("image", body.path("modalities").get(0).asText());
            assertEquals("text", body.path("modalities").get(1).asText());
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
                .userPrompt("Keep the shadow.")
                .inputMedia(new BinaryContent(sourceImage, "image/png", "source.png"))
                .build();

            JsonNode content = provider.buildChatBody(request, false)
                .path("messages").get(1).path("content");

            assertTrue(content.get(0).path("text").asText().contains("[TASK_INSTRUCTIONS_BEGIN]"));
            assertTrue(content.get(1).path("text").asText().contains("[BEGIN_UNTRUSTED_USER_PROMPT]"));
            assertEquals("image_url", content.get(2).path("type").asText());
            assertEquals(
                "data:image/png;base64," + Base64.getEncoder().encodeToString(sourceImage),
                content.get(2).path("image_url").path("url").asText()
            );
        }
    }

    @Test
    void decodesGeneratedImagesAndMapsUsage() throws Exception {
        byte[] generatedImage = { 9, 8, 7, 6 };
        String responseJson = """
            {
              "choices": [{
                "message": {
                  "content": "Generated image",
                  "images": [{
                    "type": "image_url",
                    "image_url": {"url": "data:image/webp;base64,%s"}
                  }]
                },
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 10, "completion_tokens": 4, "total_tokens": 14}
            }
            """.formatted(Base64.getEncoder().encodeToString(generatedImage));

        try (OpenRouterProvider provider = new OpenRouterProvider()) {
            AiResponse response = provider.parseCompletion(responseJson, AiOperation.GENERATE_IMAGE);

            assertEquals("Generated image", response.text());
            assertEquals(1, response.media().size());
            assertEquals("image/webp", response.media().get(0).mediaType());
            assertArrayEquals(generatedImage, response.media().get(0).data());
            assertEquals(14, response.usage().totalTokens());
            assertEquals("stop", response.finishReason());
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
