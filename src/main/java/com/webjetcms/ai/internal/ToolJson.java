package com.webjetcms.ai.internal;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webjetcms.ai.AiLifecycleListener;

/** Reads unambiguous JSON objects for tool definitions, calls and responses. */
public final class ToolJson {
    private static final ObjectMapper JSON = new ObjectMapper().enable(
        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
        DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private ToolJson() { }

    /**
     * Parses one complete JSON object and rejects duplicate fields.
     * @param value serialized JSON object
     * @return the parsed object
     * @throws IllegalArgumentException if the value is malformed, ambiguous or not an object
     */
    public static JsonNode object(String value) {
        if (value != null) {
            try {
                JsonNode node = JSON.readTree(value);
                if (node != null && node.isObject()) return node;
            } catch (JsonProcessingException invalid) {
                // Parser diagnostics may contain untrusted arguments.
            }
        }
        throw new IllegalArgumentException("Expected a single JSON object for tool parameters");
    }

    /**
     * Reads a tool response and reports its unique top-level ID, including on ambiguous nested fields.
     * @param payload serialized provider response
     * @param lifecycle observer for stored response IDs
     * @return the strictly validated response object
     * @throws IOException if the response is invalid or the observer fails
     */
    public static JsonNode readResponse(String payload, AiLifecycleListener lifecycle) throws IOException {
        JsonNode response = null;
        String id;
        try {
            response = object(payload);
            id = response.path("id").textValue();
        } catch (IllegalArgumentException invalid) {
            id = recoverResponseId(payload);
        }
        if (id != null && !id.isBlank()) {
            try {
                lifecycle.onResponseId(id);
            } catch (Exception exception) {
                throw new IOException("The lifecycle listener rejected a tool response ID", exception);
            }
        }
        if (response == null) throw new IOException("Expected a single unambiguous tool response object");
        return response;
    }

    /** Recovers only a unique ID from a single, syntactically complete response object. */
    private static String recoverResponseId(String payload) {
        if (payload == null) return null;
        try (JsonParser parser = JSON.createParser(payload)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return null;
            String id = null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("id".equals(field)) {
                    if (id != null || value != JsonToken.VALUE_STRING) return null;
                    id = parser.getText();
                }
                parser.skipChildren();
            }
            if (parser.currentToken() == JsonToken.END_OBJECT && parser.nextToken() == null) return id;
        } catch (IOException invalid) {
            // An incomplete document cannot establish a unique response ID.
        }
        return null;
    }
}
