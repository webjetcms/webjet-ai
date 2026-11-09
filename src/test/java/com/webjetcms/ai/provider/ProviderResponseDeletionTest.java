package com.webjetcms.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webjetcms.ai.AiCancellationToken;
import com.webjetcms.ai.AiClient;
import com.webjetcms.ai.AiErrorCategory;
import com.webjetcms.ai.AiProvider;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiResponseDeletion;
import com.webjetcms.ai.provider.gemini.GeminiProvider;
import com.webjetcms.ai.provider.openai.OpenAiProvider;

class ProviderResponseDeletionTest {
    private static final String ID = "saved/123?x#y%2e+ =é";
    private static final String CONFIRMED = "{\"id\":\"" + ID + "\",\"object\":\"response.deleted\",\"deleted\":true}";

    private HttpServer server;
    private ExecutorService workers;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> trustedHeader = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        workers.shutdownNow();
    }

    @ParameterizedTest
    @MethodSource("confirmations")
    void distinguishesDeletionFromResourceSpecificAbsence(
        String providerId, int status, String body, AiResponseDeletion expected
    ) throws Exception {
        respondWith(providerId, status, body);
        try (AiClient client = AiClient.of(provider(providerId))) {
            assertEquals(expected, client.deleteResponse(providerId, ID,
                config("gemini".equals(providerId) ? "/custom/models/" : "/custom")));
        }
        assertEquals(1, requests.get());
        assertEquals("DELETE", method.get());
        assertEquals("/custom/" + resource(providerId) + "/saved%2F123%3Fx%23y%252e%2B%20%3D%C3%A9", path.get());
        assertEquals("openai".equals(providerId) ? "Bearer test-key" : "test-key", authorization.get());
        assertEquals("private-header", trustedHeader.get());
        assertNull(query.get());
    }

    static Stream<Arguments> confirmations() {
        return Stream.of(
            Arguments.of("openai", 200, CONFIRMED, AiResponseDeletion.DELETED),
            Arguments.of("openai", 404, "{\"error\":{\"code\":\"response_not_found\"}}", AiResponseDeletion.ALREADY_ABSENT),
            Arguments.of("gemini", 204, "", AiResponseDeletion.DELETED),
            Arguments.of("gemini", 200, "{}", AiResponseDeletion.DELETED),
            Arguments.of("gemini", 404,
                "{\"error\":{\"code\":\"not_found\",\"message\":\"Failed to get completed interaction: Result not found.\"}}",
                AiResponseDeletion.ALREADY_ABSENT)
        );
    }

    @ParameterizedTest
    @MethodSource("unconfirmedResponses")
    void rejectsAmbiguousMissingResourcesAndUnconfirmedSuccess(String providerId, int status, String body)
        throws Exception {
        respondWith(providerId, status, body);
        try (AiProvider provider = provider(providerId)) {
            AiProviderException failure = assertThrows(AiProviderException.class,
                () -> provider.deleteResponse(ID, config("/")));
            assertEquals(status, failure.statusCode());
            assertEquals(AiErrorCategory.UNKNOWN, failure.category());
            assertFalse(failure.retryable());
            assertEquals(body, failure.rawResponse());
        }
        assertEquals(1, requests.get());
    }

    static Stream<Arguments> unconfirmedResponses() {
        return Stream.of(
            Arguments.of("openai", 404, "<html>Not found</html>"),
            Arguments.of("gemini", 404, "{\"error\":{\"code\":\"not_found\",\"message\":\"Resource not found.\"}}"),
            Arguments.of("openai", 200, "{broken"),
            Arguments.of("gemini", 200, "{broken"),
            Arguments.of("openai", 200, CONFIRMED.replace(ID, "other")),
            Arguments.of("openai", 200, CONFIRMED.replace("true", "false")),
            Arguments.of("openai", 200, CONFIRMED.replace("\"deleted\":true", "\"deleted\":false,\"deleted\":true")),
            Arguments.of("gemini", 200, "{} {}"),
            Arguments.of("gemini", 202, "{}")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "gemini"})
    void validatesInputsAndCancellationBeforeSending(String providerId) throws Exception {
        respondWith(providerId, 200, "{}");
        try (AiProvider provider = provider(providerId)) {
            for (String invalidId : new String[] {null, "", " ", ".", "..", "\uD800"}) {
                assertThrows(AiProviderException.class, () -> provider.deleteResponse(invalidId, config("/")));
            }
            assertThrows(AiProviderException.class, () -> provider.deleteResponse(ID, null));
            assertThrows(AiProviderException.class, () -> provider.deleteResponse(ID, AiProviderConfig.empty()));
            AiCancellationToken token = new AiCancellationToken();
            token.cancel();
            assertEquals(AiErrorCategory.CANCELLED, assertThrows(AiProviderException.class,
                () -> provider.deleteResponse(ID, config("/"), token)).category());
        }
        assertEquals(0, requests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "gemini"})
    void redactsFailureDiagnosticsAndPreservesRetryability(String providerId) throws Exception {
        respondWith(providerId, 429, "{\"error\":{\"message\":\"test-key private-header\"}}");
        try (AiProvider provider = provider(providerId)) {
            AiProviderException failure = assertThrows(AiProviderException.class,
                () -> provider.deleteResponse(ID, config("/")));
            assertEquals(429, failure.statusCode());
            assertTrue(failure.retryable());
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            assertFalse((trace + failure.rawResponse()).contains("test-key"));
            assertFalse((trace + failure.rawResponse()).contains("private-header"));
        }
        assertEquals(1, requests.get(), "The adapter must not retry deletion automatically");
    }

    @ParameterizedTest
    @CsvSource({
        "openai, 200, false", "gemini, 200, false",
        "openai, 401, false", "gemini, 401, false",
        "openai, 429, true", "gemini, 429, true"
    })
    void oversizedBodiesRetainHttpStatusAndRetryability(String providerId, int status, boolean retryable)
        throws Exception {
        respondWith(providerId, status, " ".repeat(65537));
        try (AiProvider provider = provider(providerId)) {
            AiProviderException failure = assertThrows(AiProviderException.class,
                () -> provider.deleteResponse(ID, config("/")));
            assertEquals(status, failure.statusCode());
            assertEquals(retryable, failure.retryable());
            assertNull(failure.rawResponse(), "An oversized response must not expose a partial body");
        }
        assertEquals(1, requests.get(), "The adapter must not retry deletion automatically");
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "gemini"})
    void cancellationAbortsOnlyTheActiveDeletionAndAllowsClientReuse(String providerId) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            if (requests.incrementAndGet() > 1) {
                respond(exchange, 200, "openai".equals(providerId) ? CONFIRMED : "{}");
                return;
            }
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                started.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        AiCancellationToken token = new AiCancellationToken();
        try (AiClient client = AiClient.of(provider(providerId))) {
            Future<AiResponseDeletion> call = workers.submit(() -> client.deleteResponse(ID, config("/"), token));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            token.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
            AiProviderException cancelled = assertInstanceOf(AiProviderException.class, failure.getCause());
            assertEquals(AiErrorCategory.CANCELLED, cancelled.category());
            assertFalse(cancelled.retryable());
            assertEquals(1, release.getCount(), "Cancellation must finish before the server completes its body");
            assertEquals(AiResponseDeletion.DELETED, client.deleteResponse(ID, config("/")));
        } finally {
            token.cancel();
            release.countDown();
        }
    }

    private void respondWith(String providerId, int status, String body) {
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getRawPath());
            query.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("openai".equals(providerId) ? "Authorization" : "x-goog-api-key"));
            trustedHeader.set(exchange.getRequestHeaders().getFirst("X-Test-Host"));
            respond(exchange, status, body);
        });
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }

    private AiProviderConfig config(String basePath) {
        return AiProviderConfig.builder("test-key").allowInsecureHttpForLocalTesting()
            .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + basePath))
            .trustedHeader("X-Test-Host", "private-header")
            .connectTimeout(Duration.ofSeconds(5)).responseTimeout(Duration.ofSeconds(10)).build();
    }

    private static AiProvider provider(String providerId) {
        return "openai".equals(providerId) ? new OpenAiProvider() : new GeminiProvider();
    }

    private static String resource(String providerId) {
        return "openai".equals(providerId) ? "responses" : "interactions";
    }
}
