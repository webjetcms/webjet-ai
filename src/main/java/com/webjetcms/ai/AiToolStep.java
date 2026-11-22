package com.webjetcms.ai;

import java.util.Objects;

/**
 * Completed model step. A caller executes an authorized call and continues using its result.
 * @param response generated text, usage and the provider response ID
 * @param toolCall executable function, or null for an answer or rejection
 * @param rejectedCall malformed call reported to an opted-in host, or null
 */
public record AiToolStep(AiResponse response, AiToolCall toolCall, RejectedCall rejectedCall) {
    /**
     * Creates an answer or executable call.
     * @param response generated text, usage and the provider response ID
     * @param toolCall requested function, or null when the model has answered
     */
    public AiToolStep(AiResponse response, AiToolCall toolCall) {
        this(response, toolCall, null);
    }

    /** Requires completed response metadata and mutually exclusive call outcomes. */
    public AiToolStep {
        if (toolCall != null && rejectedCall != null) throw new IllegalArgumentException("A rejected call cannot execute");
        Objects.requireNonNull(response, "response");
    }

    /**
     * An offered function whose call ID was missing or whose arguments failed JSON validation.
     * @param callId provider ID for returning corrective feedback, or null when the call had no usable ID
     */
    public record RejectedCall(String callId) {
        /** Normalizes a blank correlation ID to a missing ID. */
        public RejectedCall {
            if (callId != null && callId.isBlank()) callId = null;
        }

        @Override
        public String toString() { return "RejectedCall[redacted]"; }

        /** {@return a correction that does not include model-generated arguments} */
        public String reason() {
            return callId == null ? "The function call had no call ID. Generate a new function call."
                : "Function arguments must be a single JSON object. Escape quotes and newlines inside string values.";
        }
    }
}
