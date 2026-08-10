package com.webjetcms.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

class TokenUsageTest {

    @Test
    void combinesCoreAndProviderSpecificCounters() {
        TokenUsage first = new TokenUsage(10, 4, 14, Map.of("cached", 3L, "reasoning", 1L));
        TokenUsage second = new TokenUsage(5, 2, 7, Map.of("cached", 2L, "images", 1L));

        TokenUsage combined = first.plus(second);

        assertEquals(15, combined.inputTokens());
        assertEquals(6, combined.outputTokens());
        assertEquals(21, combined.totalTokens());
        assertEquals(Map.of("cached", 5L, "reasoning", 1L, "images", 1L), combined.details());
        assertEquals(3L, first.details().get("cached"));
    }

    @Test
    void treatsEmptyAsTheIdentityAndRejectsInvalidAddition() {
        TokenUsage usage = new TokenUsage(1, 2, 3, Map.of());

        assertSame(usage, TokenUsage.EMPTY.plus(usage));
        assertSame(usage, usage.plus(TokenUsage.EMPTY));
        assertThrows(NullPointerException.class, () -> usage.plus(null));
        assertThrows(ArithmeticException.class, () ->
            new TokenUsage(Long.MAX_VALUE, 0, 0, Map.of()).plus(new TokenUsage(1, 0, 0, Map.of()))
        );
        assertThrows(ArithmeticException.class, () ->
            new TokenUsage(0, 0, 0, Map.of("cached", Long.MAX_VALUE))
                .plus(new TokenUsage(0, 0, 0, Map.of("cached", 1L)))
        );
    }
}
