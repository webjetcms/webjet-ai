package com.webjetcms.ai;

import com.webjetcms.ai.internal.ToolJson;

/**
 * A model request for a function. The caller validates arguments and authorizes execution.
 * @param callId provider correlation ID to return with the result
 * @param name function identifier
 * @param arguments JSON object containing the model's proposed arguments
 */
public record AiToolCall(String callId, String name, String arguments) {
    /**
     * Validates the call identity and JSON arguments.
     * @param callId provider correlation ID to return with the result
     * @param name function identifier
     * @param arguments JSON object containing the model's proposed arguments
     */
    public AiToolCall {
        if (callId == null || callId.isBlank() || name == null || name.isBlank()) throw new IllegalArgumentException("Missing tool call ID or name");
        ToolJson.object(arguments);
    }

    @Override
    public String toString() {
        return "AiToolCall[redacted]";
    }
}
