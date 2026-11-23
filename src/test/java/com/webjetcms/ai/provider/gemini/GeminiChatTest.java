package com.webjetcms.ai.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.webjetcms.ai.AiClient;
import com.webjetcms.ai.AiErrorCategory;
import com.webjetcms.ai.AiCancellationToken;
import com.webjetcms.ai.AiLifecycleListener;
import com.webjetcms.ai.AiTool;
import com.webjetcms.ai.AiToolCall;
import com.webjetcms.ai.AiToolResult;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;

class GeminiChatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESPONSE = """
        {"id":"turn-1","status":"completed","steps":[
          {"type":"thought","summary":[{"type":"text","text":"private thought"}]},
          {"type":"model_output","content":[{"type":"text","text":"Hello"},
            {"type":"image","data":"AQID","mime_type":"image/png"}]}],
          "usage":{"total_input_tokens":11,"total_output_tokens":5,"total_tokens":20}}
        """;

    private static final AiTool LOOKUP = new AiTool("lookup", "Find a record", "{\"type\":\"object\"}");
    private static final String CALL = """
        {"type":"function_call","id":"call-7","name":"lookup","arguments":{"key":7}}
        """.strip();
    private static String pending(String steps) {
        return """
            {"id":"turn-7","status":"requires_action","usage":{"total_tokens":12},"steps":[%s]}
            """.formatted(steps);
    }

    @Test
    void completesNativeCallUsingStoredInteractionAndCallId() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/interactions", exchange -> {
            requests.add(MAPPER.readTree(exchange.getRequestBody()));
            byte[] bytes = (requests.size() == 1 ? pending(CALL) : RESPONSE).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try (var client = AiClient.of(new GeminiProvider())) {
            var config = AiProviderConfig.builder("key").allowInsecureHttpForLocalTesting()
                .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
            assertTrue(client.supportsToolCalling("gemini", "gemini-test"));
            var request = chat().tools(List.of(LOOKUP)).build();
            var ids = new ArrayList<String>();
            assertThrows(AiProviderException.class, () -> client.execute(request, config));
            assertThrows(AiProviderException.class, () -> client.stream(request, config, text -> { }));
            var step = client.executeToolStep("gemini", request, config, AiCancellationToken.none(), ids::add);
            assertEquals(List.of("turn-7"), ids);
            assertEquals(new AiToolCall("call-7", "lookup", "{\"key\":7}"), step.toolCall());
            assertEquals("requires_action", step.response().finishReason());
            assertEquals(12, step.response().usage().totalTokens());
            var next = chat().previousResponseId(step.response().responseId()).userPrompt(null)
                .toolResult(new AiToolResult(step.toolCall().callId(), "found")).build();
            var answer = client.executeToolStep("gemini", next, config);
            assertEquals("Hello", answer.response().text());
            assertNull(answer.toolCall());
            assertEquals(2, requests.size());
            assertEquals(MAPPER.readTree("""
                [{"type":"function","name":"lookup","description":"Find a record","parameters":{"type":"object"}}]
                """), requests.get(0).required("tools"));
            assertEquals("turn-7", requests.get(1).required("previous_interaction_id").textValue());
            assertEquals(MAPPER.readTree("""
                [{"type":"function_result","call_id":"call-7","result":"found"}]
                """), requests.get(1).required("input"));
        } finally { server.stop(0); }
    }

    @Test
    void rejectsAmbiguousOrIncompleteToolSteps() {
        for (String payload : List.of(pending(""), pending(CALL + "," + CALL), pending(CALL.replace("lookup", "other")),
                pending(CALL.replace("call-7", "")), pending(CALL.replace("{\"key\":7}", "null")),
                pending(CALL.replace("\"key\":7", "\"key\":7,\"key\":9")), pending(CALL).replace("requires_action", "failed"))) {
            assertThrows(IOException.class, () -> GeminiInteractionsParser.parseStep(payload, AiLifecycleListener.none(), List.of(LOOKUP), false));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsAnUnambiguousRejectedToolCall(boolean missingId) throws IOException {
        String malformed = missingId ? CALL.replace("\"id\":\"call-7\",", "") : CALL.replace("{\"key\":7}", "[]");
        var ids = new ArrayList<String>();
        var rejected = GeminiInteractionsParser.parseStep(pending(malformed), ids::add, List.of(LOOKUP), true);
        assertEquals(List.of("turn-7"), ids);
        assertEquals("turn-7", rejected.response().responseId());
        assertEquals(12, rejected.response().usage().totalTokens());
        assertNull(rejected.response().text());
        assertNull(rejected.toolCall());
        assertEquals(missingId ? null : "call-7", rejected.rejectedCall().callId());
        for (String payload : List.of(pending(malformed + "," + CALL), pending(CALL + "," + malformed),
                pending(malformed.replace("lookup", "other")), pending(malformed).replace("requires_action", "completed"),
                pending(CALL.replace("\"key\":7", "\"key\":7,\"key\":9")))) {
            assertThrows(IOException.class, () -> GeminiInteractionsParser.parseStep(payload, ids::add, List.of(LOOKUP), true));
        }
    }

    @Test
    void rejectsFunctionCallInCompletedInteractionEvenWithAnswerText() {
        String payload = """
            {"id":"turn-1","status":"completed","steps":[%s,
             {"type":"model_output","content":[{"type":"text","text":"Done"}]}]}
            """.formatted(CALL);
        var observed = new ArrayList<String>();
        assertThrows(IOException.class, () -> GeminiInteractionsParser.parseStep(payload, observed::add, List.of(LOOKUP), true));
        assertEquals(List.of("turn-1"), observed);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void executesFirstTurnAndStreamsContinuationWithoutReplayingHistory(boolean echoInput) throws Exception {
        String echo = echoInput ? "[BEGIN_UNTRUSTED_INPUT_TEXT]\nQuestion\n[END_UNTRUSTED_INPUT_TEXT]\n\n" : "";
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/proxy/v1beta/interactions", exchange -> {
            JsonNode body = MAPPER.readTree(exchange.getRequestBody());
            bodies.add(body);
            byte[] payload = (body.path("stream").asBoolean() ? stream(echo)
                : RESPONSE.replace("\"Hello\"", MAPPER.writeValueAsString(echo + "Hello"))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (var output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        try (AiClient client = AiClient.of(new GeminiProvider())) {
            AiProviderConfig config = AiProviderConfig.builder("key").allowInsecureHttpForLocalTesting()
                .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/proxy/v1beta/models/")).build();
            AiResponse first = client.execute(chat().inputText("Source facts").build(), config);
            List<String> deltas = new ArrayList<>();
            AiResponse next = client.stream(chat().previousResponseId(first.responseId())
                .userPrompt("What changed?").build(), config, deltas::add);
            assertEquals("turn-1", first.responseId());
            assertEquals("Hello", first.text());
            assertTrue(first.media().isEmpty());
            assertEquals(11, first.usage().inputTokens());
            assertEquals(5, first.usage().outputTokens());
            assertEquals(List.of("Hello", " again"), deltas);
            assertEquals("Hello again", next.text());
            assertEquals("turn-2", next.responseId());
            assertEquals("completed", next.finishReason());
            assertEquals(20, next.usage().totalTokens());
            assertEquals(2, bodies.size());
            assertFalse(bodies.get(0).has("previous_interaction_id"));
            assertEquals("gemini-test", bodies.get(0).path("model").asText());
            assertTrue(bodies.get(0).path("store").asBoolean());
            assertEquals("text", bodies.get(0).path("response_format").get(0).path("type").asText());
            assertEquals(2, bodies.get(0).path("input").size());
            assertEquals(1, bodies.get(1).path("input").size());
            assertFalse(bodies.get(1).toString().contains("Source facts"));
            assertFalse(bodies.get(1).toString().contains("Hello"));
            assertEquals("turn-1", bodies.get(1).path("previous_interaction_id").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidChatBeforeDirectTransport() throws Exception {
        try (GeminiProvider provider = new GeminiProvider()) {
            AiProviderConfig config = AiProviderConfig.builder("key").allowInsecureHttpForLocalTesting()
                .baseUri(URI.create("http://127.0.0.1:1/")).build();
            var invalid = Map.of(chat().store(false).build(), "CHAT requires explicit store(true)",
                chat().previousResponseId(" ").build(), "previousResponseId must not be blank");
            for (var entry : invalid.entrySet()) {
                assertTrue(assertThrows(AiProviderException.class,
                    () -> provider.execute(entry.getKey(), config)).getMessage().contains(entry.getValue()));
                assertTrue(assertThrows(AiProviderException.class,
                    () -> provider.stream(entry.getKey(), config, delta -> { })).getMessage().contains(entry.getValue()));
            }
        }
    }

    @Test
    void rejectsMalformedIncompleteAndUnsupportedResponses() {
        for (String payload : List.of("{broken", RESPONSE.replace("turn-1", " "),
            RESPONSE.replace("completed", "failed"), RESPONSE.replace("Hello", ""),
            RESPONSE.replace("model_output", "function_call"), RESPONSE.replace("\"total_tokens\":20", "\"total_tokens\":\"20\""))) {
            assertThrows(IOException.class, () -> GeminiInteractionsParser.parse(payload), payload);
        }
        for (String payload : List.of("", "data: [DONE]\n\n", stream().replace("completed", "failed"),
            stream().replace("\"id\":\"turn-2\",\"status\"", "\"id\":\"other\",\"status\""),
            stream().replace("model_output", "function_call"),
            stream().replace("event: interaction.completed", "event: interaction.created"))) {
            assertThrows(IOException.class, () -> GeminiInteractionsParser.parseStream(
                new BufferedReader(new StringReader(payload)), delta -> { }), payload);
        }
    }

    @Test
    void genericHttpAndStreamErrorsDoNotImplyInvalidContinuation() throws Exception {
        List<Integer> statuses = List.of(404, 400, 200);
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/interactions", exchange -> {
            int status = statuses.get(bodies.size());
            bodies.add(MAPPER.readTree(exchange.getRequestBody()));
            String fields = "\"error\":{\"code\":\"" + (status == 400 ? "invalid_request" : "not_found")
                + "\",\"message\":\"previous_interaction_id missing: test-gemini-key and trusted-secret\"}";
            byte[] payload = (status == 200 ? event("error", fields) : "{" + fields + "}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (var output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        try (AiClient client = AiClient.of(new GeminiProvider())) {
            AiProviderConfig config = AiProviderConfig.builder("test-gemini-key")
                .trustedHeader("X-Test-Secret", "trusted-secret").allowInsecureHttpForLocalTesting()
                .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/")).build();
            AiRequest request = chat().previousResponseId("missing-turn").build();
            for (int status : statuses) {
                AiProviderException failure = assertThrows(AiProviderException.class, () -> {
                    if (status == 200) client.stream(request, config, ignored -> { });
                    else client.execute(request, config);
                });
                assertEquals(AiErrorCategory.UNKNOWN, failure.category());
                assertEquals(status, failure.statusCode());
                assertFalse(failure.retryable());
                assertFalse(failure.getMessage().contains("test-gemini-key"));
                assertFalse(failure.getMessage().contains("trusted-secret"));
            }
            assertEquals(statuses.size(), bodies.size());
            for (JsonNode body : bodies) {
                assertEquals("missing-turn", body.path("previous_interaction_id").asText());
                assertTrue(body.path("store").asBoolean());
            }
            assertEquals("missing-turn", request.previousResponseId());
        } finally {
            server.stop(0);
        }
    }

    private static AiRequest.Builder chat() {
        return AiRequest.builder().operation(AiOperation.CHAT).model("models/gemini-test")
            .instructions("Answer clearly").userPrompt("Hello").store(true);
    }

    private static String event(String type, String fields) {
        return "event: " + type + "\ndata: {\"event_type\":\"" + type + "\"," + fields + "}\n\n";
    }

    private static String stream() { return stream(""); }

    private static String stream(String echo) {
        StringBuilder echoedEvents = new StringBuilder();
        for (int i = 0; i < echo.length(); i++) {
            echoedEvents.append(event("step.delta", "\"index\":1,\"delta\":" + MAPPER.createObjectNode()
                .put("type", "text").put("text", echo.substring(i, i + 1))));
        }
        return ": keepalive\n\n"
            + event("interaction.created", "\"interaction\":{\"id\":\"turn-2\"}")
            + event("step.start", "\"index\":0,\"step\":{\"type\":\"thought\"}")
            + event("step.delta", "\"index\":0,\"delta\":{\"type\":\"text\",\"text\":\"private thought\"}")
            + event("step.stop", "\"index\":0")
            + event("step.start", "\"index\":1,\"step\":{\"type\":\"model_output\"}")
            + echoedEvents
            + event("step.delta", "\"index\":1,\"delta\":{\"type\":\"text\",\"text\":\"Hello\"}")
            + event("step.delta", "\"index\":1,\"delta\":{\"type\":\"text\",\"text\":\" again\"}")
            + event("step.stop", "\"index\":1")
            + event("interaction.completed", "\"interaction\":{\"id\":\"turn-2\",\"status\":\"completed\",\"usage\":{\"total_tokens\":20}}")
            + "data: [DONE]\n\n";
    }
}
