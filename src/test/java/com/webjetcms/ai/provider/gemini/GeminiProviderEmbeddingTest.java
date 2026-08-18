package com.webjetcms.ai.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.EmbeddingOptions;
import com.webjetcms.ai.EmbeddingRequest;
import com.webjetcms.ai.EmbeddingResponse;

class GeminiProviderEmbeddingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private ExecutorService serverExecutor;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void sendsAuthenticatedBatchRequestAndParsesVectorsAndUsage() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> rawPath = new AtomicReference<>();
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> trustedHeader = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext(
            "/gemini/models/gemini-embedding-test:batchEmbedContents",
            exchange -> {
                method.set(exchange.getRequestMethod());
                rawPath.set(exchange.getRequestURI().getRawPath());
                rawQuery.set(exchange.getRequestURI().getRawQuery());
                apiKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
                trustedHeader.set(exchange.getRequestHeaders().getFirst("X-Test-Host"));
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                requestBody.set(readRequestBody(exchange));
                respond(exchange, 200, """
                    {
                      "embeddings": [
                        {"values": [0.1, 0.2, 0.3]},
                        {"values": [0.4, 0.5, 0.6]}
                      ],
                      "usageMetadata": {"promptTokenCount": 7}
                    }
                    """);
            }
        );

        EmbeddingRequest request = new EmbeddingRequest(
            "models/gemini-embedding-test",
            List.of("first", "second"),
            new EmbeddingOptions(3)
        );
        EmbeddingResponse response;
        try (GeminiProvider provider = new GeminiProvider()) {
            response = provider.embed(request, config("gemini-key", "/gemini/"));
        }

        assertEquals("POST", method.get());
        assertEquals(
            "/gemini/models/gemini-embedding-test:batchEmbedContents",
            rawPath.get()
        );
        assertNull(rawQuery.get());
        assertEquals("gemini-key", apiKey.get());
        assertEquals("standalone-test", trustedHeader.get());
        assertTrue(contentType.get().startsWith("application/json"));

        JsonNode body = MAPPER.readTree(requestBody.get());
        JsonNode requests = body.path("requests");
        assertEquals(2, requests.size());
        assertEquals("models/gemini-embedding-test", requests.get(0).path("model").asText());
        assertEquals("first", requests.get(0).path("content").path("parts").get(0).path("text").asText());
        assertEquals(3, requests.get(0).path("embedContentConfig").path("outputDimensionality").asInt());
        assertEquals("second", requests.get(1).path("content").path("parts").get(0).path("text").asText());
        assertEquals(3, requests.get(1).path("embedContentConfig").path("outputDimensionality").asInt());
        assertFalse(body.toString().contains("taskType"));

        assertEquals(2, response.embeddings().size());
        assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f}, response.embeddings().get(0).values());
        assertArrayEquals(new float[] {0.4f, 0.5f, 0.6f}, response.embeddings().get(1).values());
        assertEquals(7, response.usage().inputTokens());
        assertEquals(0, response.usage().outputTokens());
        assertEquals(7, response.usage().totalTokens());

        AtomicReference<String> defaultsRequestBody = new AtomicReference<>();
        server.createContext(
            "/defaults/models/gemini-embedding-2:batchEmbedContents",
            exchange -> {
                defaultsRequestBody.set(readRequestBody(exchange));
                respond(exchange, 200, """
                    {
                      "embeddings": [
                        {"values": [1, 2, 3, 4]},
                        {"values": [5, 6, 7, 8]}
                      ]
                    }
                    """);
            }
        );

        EmbeddingRequest defaultsRequest = new EmbeddingRequest(
            "gemini-embedding-2",
            List.of("first", "second")
        );
        EmbeddingResponse defaultsResponse;
        try (GeminiProvider provider = new GeminiProvider()) {
            defaultsResponse = provider.embed(defaultsRequest, config("gemini-key", "/defaults/"));
        }

        JsonNode defaultRequests = MAPPER.readTree(defaultsRequestBody.get()).path("requests");
        assertFalse(defaultRequests.get(0).has("embedContentConfig"));
        assertFalse(defaultRequests.get(1).has("embedContentConfig"));
        assertEquals(4, defaultsResponse.embeddings().get(0).dimensions());
        assertEquals(4, defaultsResponse.embeddings().get(1).dimensions());
    }

    @Test
    void normalizesReducedEmbedding001WithoutChangingNewerModelVectors() throws Exception {
        server.createContext("/normalize/models/", exchange -> respond(exchange, 200, """
            {"embeddings":[{"values":[3e20,4e20]}]}
            """));
        AiProviderConfig config = config("gemini-key", "/normalize/");

        EmbeddingResponse older;
        EmbeddingResponse newer;
        try (GeminiProvider provider = new GeminiProvider()) {
            older = provider.embed(
                new EmbeddingRequest(
                    "models/gemini-embedding-001",
                    List.of("older"),
                    new EmbeddingOptions(2)
                ),
                config
            );
            newer = provider.embed(
                new EmbeddingRequest(
                    "gemini-embedding-2",
                    List.of("newer"),
                    new EmbeddingOptions(2)
                ),
                config
            );
        }

        assertArrayEquals(new float[] {0.6f, 0.8f}, older.embeddings().get(0).values(), 0.000001f);
        assertArrayEquals(new float[] {3e20f, 4e20f}, newer.embeddings().get(0).values());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEmbeddingResponses")
    void rejectsMalformedEmbeddingResponses(
        String description,
        String model,
        List<String> inputs,
        EmbeddingOptions options,
        String payload,
        String expectedMessage
    ) throws Exception {
        server.createContext(
            "/invalid/models/" + model + ":batchEmbedContents",
            exchange -> respond(exchange, 200, payload)
        );

        AiProviderException exception;
        try (GeminiProvider provider = new GeminiProvider()) {
            exception = assertThrows(
                AiProviderException.class,
                () -> provider.embed(
                    new EmbeddingRequest(model, inputs, options),
                    config("gemini-key", "/invalid/")
                )
            );
        }

        assertEquals(200, exception.statusCode());
        assertEquals(payload, exception.rawResponse());
        assertTrue(exception.getMessage().contains(expectedMessage));
        assertFalse(exception.retryable());
    }

    static Stream<Arguments> invalidEmbeddingResponses() {
        return Stream.of(
            Arguments.of(
                "empty response",
                "gemini-test",
                List.of("one"),
                new EmbeddingOptions(2),
                "",
                "unexpected number"
            ),
            Arguments.of(
                "malformed JSON",
                "gemini-test",
                List.of("one"),
                new EmbeddingOptions(2),
                "{not-json}",
                "Unable to parse"
            ),
            Arguments.of(
                "fewer explicit dimensions",
                "gemini-test",
                List.of("one"),
                new EmbeddingOptions(2),
                "{\"embeddings\":[{\"values\":[1]}]}",
                "returned 1 embedding dimensions, expected 2"
            ),
            Arguments.of(
                "zero vector cannot be normalized",
                "gemini-embedding-001",
                List.of("one"),
                new EmbeddingOptions(2),
                "{\"embeddings\":[{\"values\":[0,0]}]}",
                "cannot be normalized"
            )
        );
    }

    @Test
    void rejectsInvalidEmbeddingRequestsBeforeTransport() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/validation/", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 500, "{}");
        });
        AiProviderConfig config = config("gemini-key", "/validation/");

        try (GeminiProvider provider = new GeminiProvider()) {
            AiProviderException missingRequest = assertThrows(
                AiProviderException.class,
                () -> provider.embed(null, config)
            );
            AiProviderException blankInput = assertThrows(
                AiProviderException.class,
                () -> provider.embed(new EmbeddingRequest("gemini-test", List.of("  ")), config)
            );

            assertTrue(missingRequest.getMessage().contains("request is required"));
            assertTrue(blankInput.getMessage().contains("must not be blank"));
        }

        assertEquals(0, requests.get());
    }

    @Test
    void mapsEmbeddingFailuresAndRedactsConfiguredSecrets() throws Exception {
        String apiKey = "gemini-sensitive-key";
        String trustedValue = "sensitive-tenant";
        String payload = "{\"error\":{\"message\":\"Rejected " + apiKey + " for " + trustedValue + "\"}}";
        server.createContext(
            "/error/models/gemini-test:batchEmbedContents",
            exchange -> respond(exchange, 429, payload)
        );

        AiProviderException exception;
        try (GeminiProvider provider = new GeminiProvider()) {
            exception = assertThrows(
                AiProviderException.class,
                () -> provider.embed(
                    new EmbeddingRequest("gemini-test", List.of("text")),
                    config(apiKey, trustedValue, "/error/")
                )
            );
        }

        assertEquals(429, exception.statusCode());
        assertTrue(exception.retryable());
        assertRedacted(exception, apiKey, trustedValue);

        CloseableHttpClient failingClient = new CloseableHttpClient() {
            @Override
            protected CloseableHttpResponse doExecute(
                HttpHost target,
                HttpRequest request,
                HttpContext context
            ) throws IOException {
                throw new IOException("Connection failed for " + apiKey + " and " + trustedValue);
            }

            @Override
            public void close() {
                // No resources in this deterministic failure transport.
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
        };

        AiProviderException transportException;
        try (GeminiProvider provider = new GeminiProvider(failingClient)) {
            transportException = assertThrows(
                AiProviderException.class,
                () -> provider.embed(
                    new EmbeddingRequest("gemini-test", List.of("text")),
                    AiProviderConfig.builder(apiKey)
                        .trustedHeader("X-Test-Host", trustedValue)
                        .build()
                )
            );
        }

        assertEquals(-1, transportException.statusCode());
        assertTrue(transportException.retryable());
        assertRedacted(transportException, apiKey, trustedValue);
    }

    private AiProviderConfig config(String apiKey, String path) {
        return config(apiKey, "standalone-test", path);
    }

    private AiProviderConfig config(String apiKey, String trustedValue, String path) {
        return AiProviderConfig.builder(apiKey)
            .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
            .allowInsecureHttpForLocalTesting()
            .connectTimeout(Duration.ofSeconds(3))
            .responseTimeout(Duration.ofSeconds(3))
            .trustedHeader("X-Test-Host", trustedValue)
            .build();
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private static void assertRedacted(Throwable throwable, String... secrets) {
        Throwable current = throwable;
        while (current != null) {
            String rendered = current.toString();
            for (String secret : secrets) {
                assertFalse(rendered.contains(secret));
            }
            for (Throwable suppressed : current.getSuppressed()) {
                assertRedacted(suppressed, secrets);
            }
            current = current.getCause();
        }

        if (throwable instanceof AiProviderException providerException) {
            for (String secret : secrets) {
                assertFalse(String.valueOf(providerException.rawResponse()).contains(secret));
                assertFalse(providerException.providerId().contains(secret));
            }
        }
    }
}
