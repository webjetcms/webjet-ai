package com.webjetcms.ai.provider.openai;

import java.io.BufferedReader;
import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiStreamListener;
import com.webjetcms.ai.TokenUsage;

/** Decodes OpenAI's server-sent Responses API events. */
final class OpenAiStreamParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAiStreamParser() {
    }

    static StreamResult parse(BufferedReader reader, AiStreamListener listener)
        throws IOException, AiProviderException {

        StringBuilder fullText = new StringBuilder();
        StreamState state = new StreamState();
        String event = null;
        StringBuilder data = new StringBuilder();

        String line;
        boolean done = false;
        while (done == false && (line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                done = dispatch(event, data.toString(), listener, fullText, state);
                event = null;
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            if (line.startsWith("event:")) {
                event = fieldValue(line, "event:".length());
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(fieldValue(line, "data:".length()));
            }
        }

        if (done == false && (event != null || data.length() > 0)) {
            dispatch(event, data.toString(), listener, fullText, state);
        }

        if (state.completed == false) {
            throw streamError(
                "OpenAI stream ended before the response.completed event",
                fullText.toString(),
                null
            );
        }

        return new StreamResult(fullText.toString(), state.usage, state.finishReason);
    }

    private static boolean dispatch(
        String event,
        String data,
        AiStreamListener listener,
        StringBuilder fullText,
        StreamState state
    ) throws AiProviderException {

        if ("done".equals(event) || "[DONE]".equals(data)) {
            return true;
        }
        if (data == null || data.isBlank()) {
            return false;
        }

        JsonNode chunk;
        try {
            chunk = MAPPER.readTree(data);
        } catch (IOException exception) {
            throw streamError("OpenAI returned a malformed streaming event", data, exception);
        }

        String type = isBlank(event) ? textOrNull(chunk.get("type")) : event;
        if ("response.output_text.delta".equals(type)) {
            String delta = textOrNull(chunk.get("delta"));
            if (delta != null) {
                fullText.append(delta);
                try {
                    listener.onTextDelta(delta);
                } catch (Exception exception) {
                    throw streamError("The OpenAI stream listener failed", data, exception);
                }
            }
        } else if ("response.completed".equals(type)) {
            JsonNode response = chunk.path("response");
            state.usage = OpenAiProvider.parseUsage(response.path("usage"));
            state.finishReason = defaultIfBlank(textOrNull(response.get("status")), "completed");
            state.completed = true;
        } else if ("response.incomplete".equals(type) || "response.failed".equals(type)
                || "error".equals(type)) {
            throw failure(type, chunk, data);
        }

        JsonNode response = chunk.path("response");
        String status = textOrNull(response.get("status"));
        if ("incomplete".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status)) {
            throw failure(status, chunk, data);
        }
        return false;
    }

    private static AiProviderException failure(String type, JsonNode chunk, String raw) {
        JsonNode response = chunk.path("response");
        String reason = textOrNull(response.path("incomplete_details").get("reason"));
        if (isBlank(reason)) {
            reason = textOrNull(response.path("error").get("message"));
        }
        if (isBlank(reason)) {
            reason = textOrNull(chunk.path("error").get("message"));
        }
        if (isBlank(reason)) {
            reason = textOrNull(chunk.get("message"));
        }
        String message = "OpenAI stream " + type;
        if (isBlank(reason) == false) {
            message += ": " + reason;
        }
        return new AiProviderException(OpenAiProvider.PROVIDER_ID, 200, message, raw, false);
    }

    private static AiProviderException streamError(String message, String raw, Throwable cause) {
        return new AiProviderException(OpenAiProvider.PROVIDER_ID, 200, message, raw, false, cause);
    }

    private static String fieldValue(String line, int prefixLength) {
        String value = line.substring(prefixLength);
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private static final class StreamState {
        private TokenUsage usage = TokenUsage.EMPTY;
        private String finishReason;
        private boolean completed;
    }

    record StreamResult(String text, TokenUsage usage, String finishReason) {
    }
}
