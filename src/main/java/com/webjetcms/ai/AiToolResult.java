package com.webjetcms.ai;

import java.util.Objects;

/**
 * Output from a caller-executed function, sent with the originating response's continuation ID.
 * @param callId ID of the originating tool call
 * @param output untrusted result data as text or serialized JSON
 */
public record AiToolResult(String callId, String output) {
    /**
     * Validates the correlation ID and result.
     * @param callId ID of the originating tool call
     * @param output untrusted result data as text or serialized JSON
     */
    public AiToolResult {
        if (callId == null || callId.isBlank()) throw new IllegalArgumentException("Missing tool call ID");
        Objects.requireNonNull(output, "output");
    }

    @Override
    public String toString() {
        return "AiToolResult[redacted]";
    }
}
