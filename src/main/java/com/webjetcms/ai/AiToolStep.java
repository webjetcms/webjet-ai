package com.webjetcms.ai;

import java.util.Objects;

/**
 * Completed model step. A caller executes an authorized call and continues using its result.
 * @param response generated text, usage and the provider response ID
 * @param toolCall requested function, or null when the model has answered
 */
public record AiToolStep(AiResponse response, AiToolCall toolCall) {
    /**
     * Requires completed response metadata.
     * @param response generated text, usage and the provider response ID
     * @param toolCall requested function, or null when the model has answered
     */
    public AiToolStep {
        Objects.requireNonNull(response, "response");
    }
}
