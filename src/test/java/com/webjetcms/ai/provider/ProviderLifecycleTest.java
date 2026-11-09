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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;
import com.webjetcms.ai.AiCancellationToken;
import com.webjetcms.ai.AiClient;
import com.webjetcms.ai.AiErrorCategory;
import com.webjetcms.ai.AiLifecycleListener;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiRequestLifecycle.State;
import com.webjetcms.ai.provider.gemini.GeminiProvider;
import com.webjetcms.ai.provider.openai.OpenAiProvider;

class ProviderLifecycleTest {
    private HttpServer server;
    private ExecutorService workers;
    private AiProviderConfig config;
    private final AtomicReference<String> payload = new AtomicReference<>();
    private volatile int status = 200;
    private volatile CountDownLatch release = new CountDownLatch(0);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(status, 0);
                exchange.getResponseBody().write(payload.get().getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        config = AiProviderConfig.builder("private-key").trustedHeader("X-Private", "header-secret")
            .allowInsecureHttpForLocalTesting()
            .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
            .responseTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
        workers.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "gemini"})
    void retainsAnEarlyIdWhenTheStreamEndsWithoutCompletion(String provider) throws Exception {
        payload.set(created(provider));
        try (AiClient client = client(provider)) {
            List<String> ids = new ArrayList<>();
            AiRequest continuation = request();
            AiProviderException failure = assertThrows(AiProviderException.class, () -> client.stream(
                continuation, config, text -> { }, AiCancellationToken.none(), ids::add));
            assertEquals(List.of("turn-new"), ids);
            assertObserved(failure);
            assertEquals("turn-previous", continuation.previousResponseId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "gemini"})
    void cancellationAfterCreationPreservesTheIdBeforeAnyTextOrCompletion(String provider) throws Exception {
        payload.set(created(provider));
        release = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<String> id = new AtomicReference<>();
        AiCancellationToken token = new AiCancellationToken();
        try (AiClient client = client(provider)) {
            var call = workers.submit(() -> client.stream(request(), config,
                text -> { throw new AssertionError("Text must not arrive before cancellation"); }, token,
                value -> { id.set(value); observed.countDown(); }));
            assertTrue(observed.await(5, TimeUnit.SECONDS), "The ID must arrive while the stream is still open");
            assertEquals("turn-new", id.get());
            token.cancel();
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
            AiProviderException failure = assertInstanceOf(AiProviderException.class, thrown.getCause());
            assertObserved(failure);
            assertEquals(AiErrorCategory.CANCELLED, failure.category());
            assertFalse(failure.retryable());
            assertEquals(1, release.getCount());
        } finally {
            token.cancel();
            release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "gemini"})
    void distinguishesExplicitRejectionsFromAmbiguousFailures(String provider) throws Exception {
        record Failure(int status, String body, State state, boolean retryable) { }
        String invalid = error(provider, "invalid_request_error", "invalid_request");
        String rateLimited = error(provider, "rate_limit_error", "rate_limit_exceeded");
        List<Failure> cases = List.of(
            new Failure(400, invalid, State.REJECTED, false),
            new Failure(429, rateLimited, State.REJECTED, true),
            new Failure(400, "{broken", State.UNKNOWN, false),
            new Failure(500, invalid, State.UNKNOWN, true)
        );
        try (AiClient client = client(provider)) {
            for (Failure expected : cases) {
                status = expected.status();
                payload.set(expected.body());
                AiProviderException failure = assertThrows(AiProviderException.class,
                    () -> client.execute(request(), config));
                assertEquals(expected.state(), failure.lifecycle().state(), expected.toString());
                assertNull(failure.lifecycle().responseId());
                assertEquals(expected.retryable(), failure.retryable());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "gemini"})
    void retainsIdsWhenLifecycleObserversThrowWithoutRetryingTheCallback(String provider) throws Exception {
        try (AiClient client = client(provider)) {
            for (boolean streaming : new boolean[] {false, true}) {
                payload.set(streaming ? created(provider) : "{\"id\":\"turn-new\",\"status\":\"incomplete\"}");
                List<String> ids = new ArrayList<>();
                AiLifecycleListener observer = value -> {
                    ids.add(value);
                    throw new Exception("Observer failed with private-key and header-secret");
                };
                AiProviderException failure = assertThrows(AiProviderException.class, () -> {
                    if (streaming) client.stream(request(), config, text -> { }, AiCancellationToken.none(), observer);
                    else client.execute(request(), config, AiCancellationToken.none(), observer);
                });
                assertEquals(List.of("turn-new"), ids);
                assertObserved(failure);
                StringWriter trace = new StringWriter();
                failure.printStackTrace(new PrintWriter(trace));
                assertTrue(trace.toString().contains("Observer failed"));
                assertFalse(trace.toString().contains("private-key"));
                assertFalse(trace.toString().contains("header-secret"));
            }
        }
    }

    private static String error(String provider, String openAiType, String geminiCode) {
        return "openai".equals(provider)
            ? "{\"error\":{\"type\":\"" + openAiType + "\"}}"
            : "{\"error\":{\"code\":\"" + geminiCode + "\"}}";
    }

    private static void assertObserved(AiProviderException failure) {
        assertEquals(State.RESPONSE_OBSERVED, failure.lifecycle().state());
        assertEquals("turn-new", failure.lifecycle().responseId());
    }

    private static AiClient client(String provider) {
        return AiClient.of("openai".equals(provider) ? new OpenAiProvider() : new GeminiProvider());
    }

    private static AiRequest request() {
        return AiRequest.builder().operation(AiOperation.CHAT).model("test-model").userPrompt("Hello")
            .store(true).previousResponseId("turn-previous").build();
    }

    private static String created(String provider) {
        return "openai".equals(provider)
            ? event("response.created", "{\"response\":{\"id\":\"turn-new\"}}")
            : event("interaction.created", "{\"interaction\":{\"id\":\"turn-new\"}}");
    }

    private static String event(String type, String body) { return "event: " + type + "\ndata: " + body + "\n\n"; }
}
