package com.webjetcms.ai;

import java.util.Map;

/**
 * Provider-neutral token accounting.
 *
 * @param inputTokens tokens consumed by request input
 * @param outputTokens tokens generated in the response
 * @param totalTokens total tokens reported by the provider
 * @param details provider-specific token counters; {@code null} is normalized to an empty map
 */
public record TokenUsage(long inputTokens, long outputTokens, long totalTokens, Map<String, Long> details) {

    /** Reusable zero-token usage value with no provider-specific details. */
    public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0, Map.of());

    /**
     * Creates usage data and makes its detail map immutable.
     *
     * @param inputTokens tokens consumed by request input
     * @param outputTokens tokens generated in the response
     * @param totalTokens total tokens reported by the provider
     * @param details provider-specific token counters, possibly {@code null}
     */
    public TokenUsage {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
