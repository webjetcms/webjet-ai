package com.webjetcms.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AiClientChatOutputTest {
    private static final String INSTRUCTIONS = "Answer clearly in the user’s language.";
    private static final String ANSWER = "Hlavným mestom Slovenska je Bratislava.";
    private static final String RAW = "[BEGIN_UNTRUSTED_INPUT_TEXT]\nAké je hlavné mesto Slovenska?\n"
        + "[END_UNTRUSTED_INPUT_TEXT]\n\n" + ANSWER;

    @Test
    void cleansCompletedAndStreamedChatAndPreservesMetadata() throws Exception {
        EchoingProvider provider = new EchoingProvider("\n" + RAW);
        AiRequest request = request(AiOperation.CHAT);
        try (AiClient client = AiClient.of(provider)) {
            var deltas = new ArrayList<String>();
            for (AiResponse result : List.of(client.execute(request, AiProviderConfig.empty()),
                client.stream(request, AiProviderConfig.empty(), deltas::add),
                client.stream(request, AiProviderConfig.empty(), null))) {
                assertEquals(new AiResponse(ANSWER, provider.response.media(), provider.response.usage(),
                    "completed", "response-id"), result);
            }
            assertEquals(ANSWER, String.join("", deltas));
            assertEquals(INSTRUCTIONS, request.instructions());
            assertTrue(provider.prepared.instructions().contains(INSTRUCTIONS));
            assertTrue(provider.prepared.instructions().contains("Do not echo the user's question"));
            assertTrue(provider.prepared.userPrompt().contains("[BEGIN_UNTRUSTED_USER_PROMPT]"));
        }
    }

    @Test
    void keepsOtherOperationsAndFlushesOrdinaryTrailingBrackets() throws Exception {
        EchoingProvider provider = new EchoingProvider(RAW);
        try (AiClient client = AiClient.of(provider)) {
            var deltas = new ArrayList<String>();
            assertSame(provider.response, client.execute(request(AiOperation.TEXT), AiProviderConfig.empty()));
            assertSame(provider.response, client.stream(request(AiOperation.TEXT), AiProviderConfig.empty(), deltas::add));
            assertEquals(RAW, String.join("", deltas));
        }
        try (AiClient client = AiClient.of(new EchoingProvider(RAW + " ["))) {
            var deltas = new ArrayList<String>();
            assertEquals(ANSWER + " [", client.stream(request(AiOperation.CHAT), AiProviderConfig.empty(), deltas::add).text());
            assertEquals(ANSWER + " [", String.join("", deltas));
        }
    }

    @Test
    void honoursCancellationWhileEchoedTextIsHidden() throws Exception {
        EchoingProvider provider = new EchoingProvider(RAW);
        AiCancellationToken token = new AiCancellationToken();
        provider.beforeDelta = token::cancel;
        try (AiClient client = AiClient.of(provider)) {
            var deltas = new ArrayList<String>();
            AiProviderException failure = assertThrows(AiProviderException.class,
                () -> client.stream(request(AiOperation.CHAT), AiProviderConfig.empty(), deltas::add, token));
            assertEquals(AiErrorCategory.CANCELLED, failure.category());
            assertTrue(deltas.isEmpty());
        }
    }

    @Test
    void translatesListenerFailuresDuringTailFlushWithoutExposingCredentials() throws Exception {
        try (AiClient client = AiClient.of(new EchoingProvider("["))) {
            AiProviderException failure = assertThrows(AiProviderException.class,
                () -> client.stream(request(AiOperation.CHAT), AiProviderConfig.builder("private-key").build(),
                    delta -> { throw new Exception("private-key"); }));
            assertFalse(failure.getMessage().contains("private-key"));
            assertFalse(failure.getCause().getMessage().contains("private-key"));
        }
    }

    private static AiRequest request(AiOperation operation) {
        return AiRequest.builder().operation(operation).store(true).instructions(INSTRUCTIONS)
            .userPrompt("Aké je hlavné mesto Slovenska?").build();
    }

    private static final class EchoingProvider implements AiProvider {
        private final AiResponse response;
        private AiRequest prepared;
        private Runnable beforeDelta = () -> { };

        private EchoingProvider(String text) {
            response = new AiResponse(text, List.of(), new TokenUsage(1, 2, 3, Map.of()), "completed", "response-id");
        }

        @Override public String id() { return "echoing"; }
        @Override public List<ModelInfo> listModels(AiProviderConfig config) { return List.of(); }
        @Override public AiResponse execute(AiRequest request, AiProviderConfig config) {
            prepared = request;
            return response;
        }
        @Override public AiResponse stream(AiRequest request, AiProviderConfig config, AiStreamListener listener)
            throws AiProviderException {
            prepared = request;
            if (listener != null) {
                try {
                    for (int i = 0; i < response.text().length(); i++) {
                        beforeDelta.run();
                        listener.onTextDelta(response.text().substring(i, i + 1));
                    }
                } catch (AiProviderException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new AiProviderException(id(), "Listener failed", exception);
                }
            }
            return response;
        }
    }
}
