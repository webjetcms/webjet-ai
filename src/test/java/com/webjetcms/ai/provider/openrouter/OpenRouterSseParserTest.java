package com.webjetcms.ai.provider.openrouter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiResponse;

class OpenRouterSseParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void combinesTextAndReadsUsageFromFinalChunk() throws Exception {
        String stream = """
            : OPENROUTER PROCESSING

            data: {"choices":[{"delta":{"content":"Hello"},"finish_reason":null}]}

            data:{"choices":[{"delta":{"content":" world"},"finish_reason":"stop"}]}

            data: {"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7,"completion_tokens_details":{"reasoning_tokens":1}}}

            data: [DONE]

            """;
        List<String> deltas = new ArrayList<>();

        AiResponse response = parser().parse(reader(stream), deltas::add);

        assertEquals("Hello world", response.text());
        assertEquals(List.of("Hello", " world"), deltas);
        assertEquals("stop", response.finishReason());
        assertEquals(5, response.usage().inputTokens());
        assertEquals(2, response.usage().outputTokens());
        assertEquals(7, response.usage().totalTokens());
        assertEquals(1, response.usage().details().get("completion_tokens_details.reasoning_tokens"));
    }

    @Test
    void acceptsArrayContentAndCompletesAtDoneEvent() throws Exception {
        String stream = """
            data: {"choices":[{"delta":{"content":[{"type":"text","text":"one"},{"type":"text","text":" two"}]},"finish_reason":"stop"}]}

            data: [DONE]

            """;

        AiResponse response = parser().parse(reader(stream), ignored -> { });

        assertEquals("one two", response.text());
        assertEquals(0, response.usage().totalTokens());
    }

    @Test
    void rejectsNonSuccessfulStreamingFinishReason() {
        String stream = """
            data: {"choices":[{"delta":{"content":"partial"},"finish_reason":"content_filter"}]}

            data: [DONE]

            """;

        AiProviderException exception = assertThrows(
            AiProviderException.class,
            () -> parser().parse(reader(stream), ignored -> { })
        );

        assertTrue(exception.getMessage().contains("content_filter"));
    }

    @Test
    void rejectsStreamThatEndsBeforeDone() {
        String stream = """
            data: {"choices":[{"delta":{"content":"partial"},"finish_reason":"stop"}]}

            """;

        AiProviderException exception = assertThrows(
            AiProviderException.class,
            () -> parser().parse(reader(stream), ignored -> { })
        );

        assertTrue(exception.getMessage().contains("[DONE]"));
    }

    @Test
    void reportsMalformedEventsWithoutLeakingThemIntoText() {
        String stream = "data: {not-json}\n\n";

        AiProviderException exception = assertThrows(
            AiProviderException.class,
            () -> parser().parse(reader(stream), ignored -> { })
        );

        assertEquals("openrouter", exception.providerId());
        assertFalse(exception.retryable());
        assertTrue(exception.getMessage().contains("invalid JSON"));
        assertEquals("{not-json}", exception.rawResponse());
    }

    @Test
    void wrapsListenerFailuresAsProviderErrors() {
        String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n";

        AiProviderException exception = assertThrows(
            AiProviderException.class,
            () -> parser().parse(reader(stream), ignored -> {
                throw new IllegalStateException("writer closed");
            })
        );

        assertEquals("openrouter", exception.providerId());
        assertTrue(exception.getMessage().contains("listener"));
    }

    private OpenRouterSseParser parser() {
        return new OpenRouterSseParser(mapper);
    }

    private BufferedReader reader(String value) {
        return new BufferedReader(new StringReader(value));
    }
}
