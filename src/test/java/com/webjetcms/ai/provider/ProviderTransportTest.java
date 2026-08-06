package com.webjetcms.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webjetcms.ai.AiProvider;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.ModelInfo;
import com.webjetcms.ai.provider.gemini.GeminiProvider;
import com.webjetcms.ai.provider.openai.OpenAiProvider;
import com.webjetcms.ai.provider.openrouter.OpenRouterProvider;

class ProviderTransportTest {

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
    void customEndpointsSendProviderAuthenticationAndTrustedHeaders() throws Exception {
        Map<String, String> observedHeaders = new ConcurrentHashMap<>();
        server.createContext("/openai/models", exchange -> {
            observedHeaders.put("openai-auth", exchange.getRequestHeaders().getFirst("Authorization"));
            observedHeaders.put("openai-trusted", exchange.getRequestHeaders().getFirst("X-Test-Host"));
            respond(exchange, 200, "{\"data\":[{\"id\":\"gpt-test\",\"created\":1}]}");
        });
        server.createContext("/gemini/models", exchange -> {
            observedHeaders.put("gemini-auth", exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            observedHeaders.put("gemini-trusted", exchange.getRequestHeaders().getFirst("X-Test-Host"));
            respond(exchange, 200,
                "{\"models\":[{\"name\":\"models/gemini-test\",\"displayName\":\"Gemini Test\"}]}");
        });
        server.createContext("/openrouter/models", exchange -> {
            observedHeaders.put("openrouter-auth", exchange.getRequestHeaders().getFirst("Authorization"));
            observedHeaders.put("openrouter-trusted", exchange.getRequestHeaders().getFirst("X-Test-Host"));
            respond(exchange, 200, "{\"data\":[{\"id\":\"router-test\",\"created\":1}]}");
        });

        try (OpenAiProvider openAi = new OpenAiProvider();
             GeminiProvider gemini = new GeminiProvider();
             OpenRouterProvider openRouter = new OpenRouterProvider()) {
            assertEquals("gpt-test", openAi.listModels(config("openai-key", "/openai/")).get(0).id());
            assertEquals("gemini-test", gemini.listModels(config("gemini-key", "/gemini/")).get(0).id());
            assertEquals("router-test", openRouter.listModels(config("router-key", "/openrouter/")).get(0).id());
        }

        assertEquals("Bearer openai-key", observedHeaders.get("openai-auth"));
        assertEquals("gemini-key", observedHeaders.get("gemini-auth"));
        assertEquals("Bearer router-key", observedHeaders.get("openrouter-auth"));
        assertEquals("standalone-test", observedHeaders.get("openai-trusted"));
        assertEquals("standalone-test", observedHeaders.get("gemini-trusted"));
        assertEquals("standalone-test", observedHeaders.get("openrouter-trusted"));
    }

    @Test
    void geminiModelCatalogueFollowsEncodedPaginationTokens() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> firstQuery = new AtomicReference<>();
        AtomicReference<String> secondQuery = new AtomicReference<>();
        server.createContext("/paged-gemini/models", exchange -> {
            int requestNumber = requests.incrementAndGet();
            if (requestNumber == 1) {
                firstQuery.set(exchange.getRequestURI().getRawQuery());
                respond(exchange, 200, """
                    {
                      "models":[{"name":"models/zulu","displayName":"Zulu"}],
                      "nextPageToken":"page token+/="
                    }
                    """);
            } else if (requestNumber == 2) {
                secondQuery.set(exchange.getRequestURI().getRawQuery());
                respond(exchange, 200,
                    "{\"models\":[{\"name\":\"models/alpha\",\"displayName\":\"Alpha\"}]}");
            } else {
                respond(exchange, 500, "{\"error\":{\"message\":\"unexpected request\"}}");
            }
        });

        try (GeminiProvider provider = new GeminiProvider()) {
            List<ModelInfo> models = provider.listModels(config("gemini-key", "/paged-gemini/"));

            assertEquals(List.of("alpha", "zulu"), models.stream().map(ModelInfo::id).toList());
        }

        assertEquals(2, requests.get());
        assertEquals("pageSize=1000", firstQuery.get());
        assertEquals("pageSize=1000&pageToken=page%20token%2B%2F%3D", secondQuery.get());
    }

    @Test
    void geminiModelCatalogueStopsOnARepeatedPageToken() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/repeated-gemini/models", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, """
                {
                  "models":[{"name":"models/gemini-test"}],
                  "nextPageToken":"repeated-token"
                }
                """);
        });

        try (GeminiProvider provider = new GeminiProvider()) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> provider.listModels(config("gemini-key", "/repeated-gemini/"))
            );

            assertEquals(200, exception.statusCode());
        }

        assertEquals(2, requests.get());
    }

    @Test
    void openRouterModelCatalogueRequestsAllOutputModalities() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/modalities/models", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "{\"data\":[{\"id\":\"router-test\"}]}");
        });

        try (OpenRouterProvider provider = new OpenRouterProvider()) {
            assertEquals(
                "router-test",
                provider.listModels(config("router-key", "/modalities/")).get(0).id()
            );
        }

        assertEquals("output_modalities=all", query.get());
    }

    @Test
    void reusableProviderAllowsMoreThanTwoConcurrentRequestsPerRoute() throws Exception {
        int concurrentRequests = 3;
        CountDownLatch allRequestsArrived = new CountDownLatch(concurrentRequests);
        server.createContext("/pool/models", exchange -> {
            allRequestsArrived.countDown();
            try {
                if (allRequestsArrived.await(2, TimeUnit.SECONDS)) {
                    respond(exchange, 200, "{\"data\":[{\"id\":\"model\"}]}");
                } else {
                    respond(exchange, 503, "{\"error\":{\"message\":\"connection pool too small\"}}");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                respond(exchange, 500, "{\"error\":{\"message\":\"interrupted\"}}");
            }
        });

        AiProviderConfig config = config("router-key", "/pool/");
        ExecutorService callers = Executors.newFixedThreadPool(concurrentRequests);
        try (OpenRouterProvider provider = new OpenRouterProvider()) {
            List<CompletableFuture<List<ModelInfo>>> requests = java.util.stream.IntStream
                .range(0, concurrentRequests)
                .mapToObj(index -> CompletableFuture.supplyAsync(() -> listModels(provider, config), callers))
                .toList();

            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            for (CompletableFuture<List<ModelInfo>> request : requests) {
                assertEquals("model", request.join().get(0).id());
            }
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void malformedGeminiModelCatalogueRetainsTheResponseAndIsNotRetryable() throws Exception {
        String invalidPayload = "{\"unexpected\":true}";
        server.createContext("/invalid/models", exchange -> respond(exchange, 200, invalidPayload));

        try (GeminiProvider provider = new GeminiProvider()) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> provider.listModels(config("gemini-key", "/invalid/"))
            );

            assertEquals(200, exception.statusCode());
            assertEquals(invalidPayload, exception.rawResponse());
            assertFalse(exception.retryable());
        }
    }

    @Test
    void providerExceptionsRedactApiKeysAndTrustedHeaderValues() throws Exception {
        String trustedValue = "standalone-test";
        server.createContext("/redact-openai/models", exchange -> respond(
            exchange,
            401,
            "{\"error\":{\"message\":\"Rejected openai-key and " + trustedValue + "\"}}"
        ));
        server.createContext("/redact-gemini/models", exchange -> respond(
            exchange,
            401,
            "{\"error\":{\"message\":\"Rejected gemini-key and " + trustedValue + "\"}}"
        ));
        server.createContext("/redact-openrouter/models", exchange -> respond(
            exchange,
            401,
            "{\"error\":{\"message\":\"Rejected router-key and " + trustedValue + "\"}}"
        ));

        try (OpenAiProvider openAi = new OpenAiProvider();
             GeminiProvider gemini = new GeminiProvider();
             OpenRouterProvider openRouter = new OpenRouterProvider()) {
            assertRedacted(
                assertThrows(AiProviderException.class, () ->
                    openAi.listModels(config("openai-key", "/redact-openai/"))
                ),
                "openai-key",
                trustedValue
            );
            assertRedacted(
                assertThrows(AiProviderException.class, () ->
                    gemini.listModels(config("gemini-key", "/redact-gemini/"))
                ),
                "gemini-key",
                trustedValue
            );
            assertRedacted(
                assertThrows(AiProviderException.class, () ->
                    openRouter.listModels(config("router-key", "/redact-openrouter/"))
                ),
                "router-key",
                trustedValue
            );
        }
    }

    @Test
    void providersDoNotFollowRedirectsThatCouldLeakCredentials() throws Exception {
        HttpServer redirectTarget = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService redirectExecutor = Executors.newCachedThreadPool();
        AtomicInteger redirectedRequests = new AtomicInteger();
        redirectTarget.createContext("/capture", exchange -> {
            redirectedRequests.incrementAndGet();
            respond(exchange, 200, "{\"data\":[]}");
        });
        redirectTarget.setExecutor(redirectExecutor);
        redirectTarget.start();

        String location = "http://127.0.0.1:" + redirectTarget.getAddress().getPort() + "/capture";
        server.createContext("/redirect-openai/models", exchange -> redirect(exchange, location));
        server.createContext("/redirect-gemini/models", exchange -> redirect(exchange, location));
        server.createContext("/redirect-openrouter/models", exchange -> redirect(exchange, location));

        try (OpenAiProvider openAi = new OpenAiProvider();
             GeminiProvider gemini = new GeminiProvider();
             OpenRouterProvider openRouter = new OpenRouterProvider()) {
            assertEquals(302, assertThrows(
                AiProviderException.class,
                () -> openAi.listModels(config("openai-key", "/redirect-openai/"))
            ).statusCode());
            assertEquals(302, assertThrows(
                AiProviderException.class,
                () -> gemini.listModels(config("gemini-key", "/redirect-gemini/"))
            ).statusCode());
            assertEquals(302, assertThrows(
                AiProviderException.class,
                () -> openRouter.listModels(config("router-key", "/redirect-openrouter/"))
            ).statusCode());
            assertEquals(0, redirectedRequests.get());
        } finally {
            redirectTarget.stop(0);
            redirectExecutor.shutdownNow();
        }
    }

    @Test
    void reusableProvidersDoNotRetainCookiesBetweenRequests() throws Exception {
        AtomicInteger openAiRequests = new AtomicInteger();
        AtomicReference<String> openAiCookie = new AtomicReference<>();
        server.createContext("/cookie-openai/models", exchange -> {
            int requestNumber = openAiRequests.incrementAndGet();
            if (requestNumber == 1) {
                exchange.getResponseHeaders().add("Set-Cookie", "tenant=openai-one; Path=/");
            } else {
                openAiCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            }
            respond(exchange, 200, "{\"data\":[{\"id\":\"gpt-test\"}]}");
        });

        AtomicInteger geminiRequests = new AtomicInteger();
        AtomicReference<String> geminiCookie = new AtomicReference<>();
        server.createContext("/cookie-gemini/models", exchange -> {
            int requestNumber = geminiRequests.incrementAndGet();
            if (requestNumber == 1) {
                exchange.getResponseHeaders().add("Set-Cookie", "tenant=gemini-one; Path=/");
            } else {
                geminiCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            }
            respond(exchange, 200,
                "{\"models\":[{\"name\":\"models/gemini-test\",\"displayName\":\"Gemini Test\"}]}");
        });

        AtomicInteger openRouterRequests = new AtomicInteger();
        AtomicReference<String> openRouterCookie = new AtomicReference<>();
        server.createContext("/cookie-openrouter/models", exchange -> {
            int requestNumber = openRouterRequests.incrementAndGet();
            if (requestNumber == 1) {
                exchange.getResponseHeaders().add("Set-Cookie", "tenant=router-one; Path=/");
            } else {
                openRouterCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            }
            respond(exchange, 200, "{\"data\":[{\"id\":\"router-test\"}]}");
        });

        try (OpenAiProvider openAi = new OpenAiProvider();
             GeminiProvider gemini = new GeminiProvider();
             OpenRouterProvider openRouter = new OpenRouterProvider()) {
            openAi.listModels(config("openai-key-one", "/cookie-openai/"));
            openAi.listModels(config("openai-key-two", "/cookie-openai/"));
            gemini.listModels(config("gemini-key-one", "/cookie-gemini/"));
            gemini.listModels(config("gemini-key-two", "/cookie-gemini/"));
            openRouter.listModels(config("router-key-one", "/cookie-openrouter/"));
            openRouter.listModels(config("router-key-two", "/cookie-openrouter/"));
        }

        assertNull(openAiCookie.get());
        assertNull(geminiCookie.get());
        assertNull(openRouterCookie.get());
    }

    private AiProviderConfig config(String apiKey, String path) {
        return AiProviderConfig.builder(apiKey)
            .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
            .allowInsecureHttpForLocalTesting()
            .connectTimeout(Duration.ofSeconds(3))
            .responseTimeout(Duration.ofSeconds(3))
            .trustedHeader("X-Test-Host", "standalone-test")
            .build();
    }

    private static List<ModelInfo> listModels(AiProvider provider, AiProviderConfig config) {
        try {
            return provider.listModels(config);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertRedacted(Throwable throwable, String... secrets) {
        Throwable current = throwable;
        while (current != null) {
            String rendered = current.toString();
            for (String secret : secrets) {
                assertFalse(rendered.contains(secret));
                for (StackTraceElement element : current.getStackTrace()) {
                    assertFalse(element.toString().contains(secret));
                }
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

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }
}
