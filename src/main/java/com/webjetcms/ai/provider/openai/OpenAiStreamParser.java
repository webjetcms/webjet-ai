package com.webjetcms.ai.provider.openai;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiLifecycleListener;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.AiStreamListener;
import com.webjetcms.ai.internal.LifecycleSupport;

/**
 * Decodes OpenAI's server-sent Responses API events into completed responses.
 * Chat streams additionally validate completion text and retain the continuation ID.
 */
final class OpenAiStreamParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAiStreamParser() {
    }

    static AiResponse parse(BufferedReader reader, AiStreamListener listener)
        throws IOException, AiProviderException {

        return parse(reader, listener, false, false,
            new LifecycleSupport(OpenAiProvider.PROVIDER_ID, AiLifecycleListener.none()));
    }

    /**
     * Delivers text deltas and requires a response.completed event before returning.
     * Chat completion text must match the delivered deltas exactly.
     *
     * @param reader SSE reader, which remains open after parsing
     * @param listener non-null recipient of text fragments
     * @param chat whether to enforce chat response validation and retain the response ID
     * @param continuation whether the chat request supplies a previous response ID
     * @param lifecycle per-call tracker notified before completion validation or text delivery
     * @return accumulated text with completion usage, finish reason, and optional response ID
     * @throws IOException if reading the stream fails
     * @throws AiProviderException if the stream fails, is malformed or incomplete, or the listener throws
     */
    static AiResponse parse(
        BufferedReader reader, AiStreamListener listener, boolean chat, boolean continuation,
        LifecycleSupport lifecycle
    ) throws IOException, AiProviderException {

        StringBuilder fullText = new StringBuilder();
        StreamState state = new StreamState(chat, continuation, lifecycle);
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

        if (state.completed == null) {
            throw streamError(
                "OpenAI stream ended before the response.completed event",
                fullText.toString(),
                null
            );
        }

        return state.completed.withText(fullText.toString());
    }

    /**
     * Applies one SSE event to the accumulated text and completion state.
     *
     * @param event explicit SSE event name, or {@code null} to use the payload type
     * @param data assembled event payload
     * @param listener recipient of text deltas
     * @param fullText text accumulated across events
     * @param state completion and chat-validation state
     * @return {@code true} when an explicit end-of-stream marker is received
     * @throws AiProviderException if parsing, response validation, or listener delivery fails
     */
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

        if (state.chat && (chunk == null || chunk.isObject() == false)) {
            throw streamError("OpenAI returned a malformed chat streaming event", data, null);
        }

        String type = isBlank(event) ? textOrNull(chunk.get("type")) : event;
        if (state.chat && isBlank(event) == false && chunk.hasNonNull("type")
            && event.equals(textOrNull(chunk.get("type"))) == false) {
            throw streamError("OpenAI chat streaming event type does not match its payload", data, null);
        }
        switch (type == null ? "" : type) {
            case "response.created", "response.in_progress", "response.queued",
                "response.completed", "response.failed", "response.incomplete" -> state.observe(chunk.path("response"), data);
            default -> { }
        }
        if ("response.output_text.delta".equals(type)) {
            if (state.chat && (state.completed != null || chunk.path("delta").isTextual() == false)) {
                throw streamError("OpenAI returned an invalid chat text delta", data, null);
            }
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
            if (state.chat) {
                AiResponse completed = OpenAiProvider.parseChatResponse(response, data, state.continuation);
                if (state.completed != null || completed.text().equals(fullText.toString()) == false) {
                    throw streamError("OpenAI chat stream does not match its completed response", data, null);
                }
                state.completed = completed;
            } else {
                state.completed = new AiResponse(fullText.toString(), List.of(),
                    OpenAiProvider.parseUsage(response.path("usage")),
                    defaultIfBlank(textOrNull(response.get("status")), "completed"));
            }
        } else if ("response.incomplete".equals(type) || "response.failed".equals(type)
                || "error".equals(type)) {
            throw failure(type, chunk, data, state.continuation);
        }

        JsonNode response = chunk.path("response");
        String status = textOrNull(response.get("status"));
        if ("incomplete".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status)) {
            throw failure(status, chunk, data, state.continuation);
        }
        return false;
    }

    /**
     * Builds a non-retryable failure from an explicit error event or failed response status.
     *
     * @param type failing event type or response status
     * @param chunk parsed event payload
     * @param raw original event JSON retained for diagnostics
     * @param continuation whether structured errors may identify an invalid continuation
     * @return failure with provider details and any recognized continuation category
     */
    private static AiProviderException failure(String type, JsonNode chunk, String raw, boolean continuation) {
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
        JsonNode error = response.path("error");
        if (error.isObject() == false) error = chunk.path("error");
        if (error.isObject() == false && "error".equals(type)) error = chunk;
        return OpenAiProvider.classifyContinuationError(
            new AiProviderException(OpenAiProvider.PROVIDER_ID, 200, message, raw, false), error, continuation
        );
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

    /** Holds chat validation settings and the accepted completion for a single stream. */
    private static final class StreamState {
        private final boolean chat;
        private final boolean continuation;
        private final LifecycleSupport lifecycle;
        private AiResponse completed;

        private StreamState(boolean chat, boolean continuation, LifecycleSupport lifecycle) {
            this.chat = chat;
            this.continuation = chat && continuation;
            this.lifecycle = lifecycle;
        }

        private void observe(JsonNode response, String raw) throws AiProviderException {
            if (response.hasNonNull("id") == false) return;
            JsonNode id = response.path("id");
            if (id.isTextual() == false || id.textValue().isBlank()) {
                throw streamError("OpenAI stream returned an invalid response ID", raw, null);
            }
            try {
                lifecycle.onResponseId(id.textValue());
            } catch (AiProviderException exception) {
                throw streamError(exception.getMessage(), raw, exception);
            }
        }
    }
}
