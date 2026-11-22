package com.webjetcms.ai;

import com.webjetcms.ai.internal.ToolJson;

/**
 * Caller-selected function offered to a model.
 * @param name function identifier
 * @param description instructions for selecting the function
 * @param inputSchema JSON object describing the arguments
 */
public record AiTool(String name, String description, String inputSchema) {
    /**
     * Validates the name and schema.
     * @param name function identifier
     * @param description instructions for selecting the function
     * @param inputSchema JSON object describing the arguments
     */
    public AiTool {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid tool name");
        ToolJson.object(inputSchema);
    }
}
