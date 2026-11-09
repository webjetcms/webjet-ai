package com.webjetcms.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webjetcms.ai.AiCancellationToken;
import com.webjetcms.ai.AiClient;
import com.webjetcms.ai.AiErrorCategory;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;

class ProviderCancellationTest {

    private static final Map<String, String> RESPONSES = Map.of(
        "openai", "{\"status\":\"completed\",\"output_text\":\"Hello\"}",
        "gemini", "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]},\"finishReason\":\"STOP\"}]}",
        "openrouter", "{\"choices\":[{\"message\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}"
    );
    private static final Map<String, String> STREAM_PREFIXES = Map.of(
        "openai", "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\n\n",
        "gemini", "data: {\"event_type\":\"interaction.created\",\"interaction\":{\"id\":\"partial\"}}\n\n"
            + "data: {\"event_type\":\"step.start\",\"index\":0,\"step\":{\"type\":\"model_output\"}}\n\n"
            + "data: {\"event_type\":\"step.delta\",\"index\":0,\"delta\":{\"type\":\"text\",\"text\":\"Hello\"}}\n\n",
        "openrouter", "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n"
    );

    private HttpServer server;
    private ExecutorService workers;
    private AiProviderConfig config;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.start();
        config = AiProviderConfig.builder("test-key").allowInsecureHttpForLocalTesting()
            .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
            .connectTimeout(Duration.ofSeconds(5)).responseTimeout(Duration.ofSeconds(20)).build();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        workers.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "gemini", "openrouter"})
    void cancellingAPartialBodyPreservesConcurrentRequestsAndClientReuse(String providerId) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (requests.incrementAndGet() == 1) {
                holdResponse(exchange, "{", firstStarted, releaseFirst);
            } else {
                holdResponse(exchange, RESPONSES.get(providerId), secondStarted, releaseSecond);
            }
        });
        AiCancellationToken cancelledToken = new AiCancellationToken();
        AiCancellationToken successfulToken = new AiCancellationToken();
        try (AiClient client = AiClient.discover()) {
            AiRequest request = AiRequest.builder().model("test-model").userPrompt("Hello").build();
            Future<AiResponse> cancelled = workers.submit(
                () -> client.execute(providerId, request, config, cancelledToken));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "The partial body must arrive first");
            Future<AiResponse> successful = workers.submit(
                () -> client.execute(providerId, request, config, successfulToken));
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS), "Both requests must be in flight");

            assertTrue(cancelledToken.cancel());
            assertCancelled(cancelled, providerId);
            assertEquals(1, releaseFirst.getCount(), "Cancellation must not wait for the remaining body");
            assertFalse(successfulToken.isCancelled());
            releaseSecond.countDown();
            assertEquals("Hello", successful.get(5, TimeUnit.SECONDS).text());
            successfulToken.cancel();
            assertEquals("Hello", client.execute(providerId, request, config).text());
            assertEquals(3, requests.get());
        } finally {
            cancelledToken.cancel();
            successfulToken.cancel();
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "gemini", "openrouter"})
    void cancelsAnUnfinishedStreamAfterItsFirstFragment(String providerId) throws Exception {
        CountDownLatch fragmentReceived = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        AtomicReference<String> fragment = new AtomicReference<>();
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            holdResponse(exchange, STREAM_PREFIXES.get(providerId), new CountDownLatch(0), releaseServer);
        });
        AiCancellationToken token = new AiCancellationToken();
        try (AiClient client = AiClient.discover()) {
            AiOperation operation = "openrouter".equals(providerId) ? AiOperation.TEXT : AiOperation.CHAT;
            AiRequest request = AiRequest.builder().model("test-model").operation(operation)
                .userPrompt("Hello").store(operation == AiOperation.CHAT).build();
            Future<AiResponse> call = workers.submit(() -> client.stream(providerId, request, config, delta -> {
                fragment.set(delta);
                fragmentReceived.countDown();
            }, token));
            assertTrue(fragmentReceived.await(5, TimeUnit.SECONDS), "The listener must receive a fragment");
            assertTrue(token.cancel());
            assertCancelled(call, providerId);
            assertEquals("Hello", fragment.get());
            assertEquals(1, releaseServer.getCount(), "Cancellation must terminate the unfinished stream");
        } finally {
            token.cancel();
            releaseServer.countDown();
        }
    }

    private static void assertCancelled(Future<AiResponse> call, String providerId) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
        AiProviderException exception = assertInstanceOf(AiProviderException.class, failure.getCause());
        assertEquals(providerId, exception.providerId());
        assertEquals(AiErrorCategory.CANCELLED, exception.category());
        assertFalse(exception.retryable());
    }

    private static void holdResponse(
        HttpExchange exchange, String prefix, CountDownLatch started, CountDownLatch release
    ) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(prefix.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            started.countDown();
            if (release.await(20, TimeUnit.SECONDS) == false) {
                throw new IOException("The test did not release the server handler");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("The test server was interrupted", exception);
        } finally {
            exchange.close();
        }
    }
}
