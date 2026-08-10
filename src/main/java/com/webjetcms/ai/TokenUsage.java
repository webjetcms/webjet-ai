package com.webjetcms.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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

    /**
     * Adds two immutable usage values, including matching provider-specific detail counters.
     *
     * @param other usage to add
     * @return combined usage
     * @throws NullPointerException when {@code other} is {@code null}
     * @throws ArithmeticException when a counter overflows a {@code long}
     */
    public TokenUsage plus(TokenUsage other) {
        Objects.requireNonNull(other, "other");
        if (EMPTY.equals(this)) return other;
        if (EMPTY.equals(other)) return this;

        Map<String, Long> combinedDetails = new LinkedHashMap<>(details);
        other.details.forEach((name, value) -> combinedDetails.merge(
            name,
            value,
            (left, right) -> Math.addExact(left, right)
        ));
        return new TokenUsage(
            Math.addExact(inputTokens, other.inputTokens),
            Math.addExact(outputTokens, other.outputTokens),
            Math.addExact(totalTokens, other.totalTokens),
            combinedDetails
        );
    }
}
