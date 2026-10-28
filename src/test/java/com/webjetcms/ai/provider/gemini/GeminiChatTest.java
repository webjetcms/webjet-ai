package com.webjetcms.ai.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.webjetcms.ai.AiClient;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;

class GeminiChatTest {

    private static final String RESPONSE = """
        {"id":"turn-1","status":"completed","steps":[
          {"type":"thought","summary":[{"type":"text","text":"private thought"}]},
          {"type":"model_output","content":[{"type":"text","text":"Hello"},
            {"type":"image","data":"AQID","mime_type":"image/png"}]}],
          "usage":{"total_input_tokens":11,"total_output_tokens":5,"total_tokens":20}}
        """;

    @Test
    void executesFirstTurnAndStreamsContinuationWithoutReplayingHistory() throws Exception {
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/proxy/v1beta/interactions", exchange -> {
            JsonNode body = new ObjectMapper().readTree(exchange.getRequestBody());
            bodies.add(body);
            byte[] payload = (body.path("stream").asBoolean() ? stream() : RESPONSE).getBytes(StandardCharsets.UTF_8);
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

    private static AiRequest.Builder chat() {
        return AiRequest.builder().operation(AiOperation.CHAT).model("models/gemini-test")
            .instructions("Answer clearly").userPrompt("Hello").store(true);
    }

    private static String event(String type, String fields) {
        return "event: " + type + "\ndata: {\"event_type\":\"" + type + "\"," + fields + "}\n\n";
    }

    private static String stream() {
        return ": keepalive\n\n"
            + event("interaction.created", "\"interaction\":{\"id\":\"turn-2\"}")
            + event("step.start", "\"index\":0,\"step\":{\"type\":\"thought\"}")
            + event("step.delta", "\"index\":0,\"delta\":{\"type\":\"text\",\"text\":\"private thought\"}")
            + event("step.stop", "\"index\":0")
            + event("step.start", "\"index\":1,\"step\":{\"type\":\"model_output\"}")
            + event("step.delta", "\"index\":1,\"delta\":{\"type\":\"text\",\"text\":\"Hello\"}")
            + event("step.delta", "\"index\":1,\"delta\":{\"type\":\"text\",\"text\":\" again\"}")
            + event("step.stop", "\"index\":1")
            + event("interaction.completed", "\"interaction\":{\"id\":\"turn-2\",\"status\":\"completed\",\"usage\":{\"total_tokens\":20}}")
            + "data: [DONE]\n\n";
    }
}
