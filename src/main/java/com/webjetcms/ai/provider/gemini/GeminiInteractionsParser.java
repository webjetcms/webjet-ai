package com.webjetcms.ai.provider.gemini;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.AiStreamListener;
import com.webjetcms.ai.TokenUsage;

/**
 * Decodes Gemini Interactions responses for text chat with provider-managed continuation.
 *
 * <p>Reads interaction steps, IDs and lifecycle events; generateContent candidate payloads
 * are handled by {@link GeminiResponseParser}. Only model output text is returned.</p>
 */
final class GeminiInteractionsParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GeminiInteractionsParser() {
    }

    /**
     * Collects text from a completed interaction, excluding thoughts and non-text content.
     *
     * @param payload interaction response as a JSON object
     * @return text, token usage and the interaction ID for continuation
     * @throws IOException if the payload is malformed, incomplete, requires a function call or has no usable text
     */
    static AiResponse parse(String payload) throws IOException {
        JsonNode root = parseObject(payload);
        rejectError(root);
        String id = requiredText(root, "id");
        requireCompleted(root);
        JsonNode steps = root.path("steps");
        if (steps.isArray() == false) {
            throw new IOException("Gemini interaction response does not contain a steps array.");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode step : steps) {
            if (step.isObject() == false) {
                throw new IOException("Gemini returned an invalid interaction step.");
            }
            String stepType = requiredText(step, "type");
            rejectFunctionCall(stepType);
            if ("model_output".equals(stepType)) {
                JsonNode content = step.path("content");
                if (content.isArray() == false) {
                    throw new IOException("Gemini model output does not contain a content array.");
                }
                for (JsonNode part : content) {
                    if (part.isObject() == false) {
                        throw new IOException("Gemini returned invalid interaction content.");
                    }
                    if ("text".equals(part.path("type").asText())) {
                        text.append(textValue(part, "text"));
                    }
                }
            }
        }
        return response(text, root, id);
    }

    /**
     * Consumes interaction SSE events and emits model output text as it arrives.
     *
     * @param reader event stream; remains open after parsing
     * @param listener recipient of text fragments, or {@code null} to collect them without callbacks
     * @return accumulated text, usage and continuation ID after an interaction completion event
     * @throws IOException if reading fails, events are invalid, completion is missing or the listener fails
     */
    static AiResponse parseStream(BufferedReader reader, AiStreamListener listener) throws IOException {
        Accumulator accumulator = new Accumulator(listener);
        StringBuilder data = new StringBuilder();
        String event = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                accumulator.accept(event, data.toString());
                event = null;
                data.setLength(0);
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(fieldValue(line, 5));
            } else if (line.startsWith("event:")) {
                event = fieldValue(line, 6);
            }
        }
        accumulator.accept(event, data.toString());
        return accumulator.result();
    }

    private static String fieldValue(String line, int start) {
        String value = line.substring(start);
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    /**
     * Decodes a non-blank response or event payload and requires a JSON object.
     *
     * @param payload JSON text to decode
     * @return parsed object
     * @throws IOException if the payload is empty, malformed or not an object
     */
    private static JsonNode parseObject(String payload) throws IOException {
        if (payload == null || payload.isBlank()) {
            throw new IOException("Gemini returned an empty interaction response.");
        }
        JsonNode root = MAPPER.readTree(payload);
        if (root == null || root.isObject() == false) {
            throw new IOException("Gemini returned an invalid interaction response.");
        }
        return root;
    }

    private static String textValue(JsonNode root, String field) throws IOException {
        JsonNode value = root.path(field);
        if (value.isTextual() == false) {
            throw new IOException("Gemini interaction contains an invalid " + field + ".");
        }
        return value.textValue();
    }

    private static String requiredText(JsonNode root, String field) throws IOException {
        String value = textValue(root, field);
        if (value.isBlank()) {
            throw new IOException("Gemini interaction contains an empty " + field + ".");
        }
        return value;
    }

    private static void rejectError(JsonNode root) throws IOException {
        JsonNode error = root.path("error");
        if (error.isMissingNode() == false && error.isNull() == false) {
            String message = error.path("message").asText("Gemini interaction failed.");
            throw new IOException(message);
        }
    }

    private static void requireCompleted(JsonNode interaction) throws IOException {
        rejectError(interaction);
        String status = requiredText(interaction, "status");
        if ("completed".equals(status) == false) {
            throw new IOException("Gemini interaction ended with status " + status + ".");
        }
    }

    private static void rejectFunctionCall(String stepType) throws IOException {
        if ("function_call".equals(stepType)) {
            throw new IOException("Gemini CHAT does not support responses requiring a function call.");
        }
    }

    private static AiResponse response(StringBuilder text, JsonNode interaction, String id) throws IOException {
        if (text.toString().isBlank()) {
            throw new IOException("Gemini interaction returned no usable text.");
        }
        return new AiResponse(text.toString(), List.of(), parseUsage(interaction.path("usage")), "completed", id);
    }

    /**
     * Reads interaction token totals and preserves additional integral usage fields.
     *
     * @param usage usage object, or a missing or JSON null node
     * @return reported usage, with absent totals defaulting to zero
     * @throws IOException if usage is not an object or a total is invalid
     */
    private static TokenUsage parseUsage(JsonNode usage) throws IOException {
        if (usage.isMissingNode() || usage.isNull()) {
            return TokenUsage.EMPTY;
        }
        if (usage.isObject() == false) {
            throw new IOException("Gemini interaction contains invalid token usage.");
        }
        Map<String, Long> details = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : usage.properties()) {
            if (entry.getValue().isIntegralNumber() && entry.getValue().canConvertToLong()) {
                details.put(entry.getKey(), entry.getValue().longValue());
            }
        }
        return new TokenUsage(
            tokenCount(usage, "total_input_tokens"),
            tokenCount(usage, "total_output_tokens"),
            tokenCount(usage, "total_tokens"),
            details
        );
    }

    /**
     * Reads an optional non-negative token total without coercing strings or fractions.
     *
     * @param usage object containing token totals
     * @param field total to read
     * @return the total, or zero for an absent or JSON null field
     * @throws IOException if the value is not a non-negative integer representable as a {@code long}
     */
    private static long tokenCount(JsonNode usage, String field) throws IOException {
        JsonNode value = usage.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return 0;
        }
        if (value.isIntegralNumber() == false || value.canConvertToLong() == false || value.longValue() < 0) {
            throw new IOException("Gemini interaction contains an invalid " + field + ".");
        }
        return value.longValue();
    }

    /** Tracks active steps and completion while collecting text for a single interaction stream. */
    private static final class Accumulator {
        private final AiStreamListener listener;
        private final StringBuilder text = new StringBuilder();
        private final Map<Integer, String> activeSteps = new HashMap<>();
        private String id;
        private JsonNode completed;
        private boolean done;

        private Accumulator(AiStreamListener listener) {
            this.listener = listener;
        }

        /**
         * Applies one SSE event, checking interaction identity, step state and completion order.
         *
         * @param event optional SSE event name, used when the JSON event type is absent
         * @param data joined SSE data lines, possibly empty or the {@code [DONE]} marker
         * @throws IOException if the event is invalid, reports failure or triggers a listener failure
         */
        private void accept(String event, String data) throws IOException {
            String payload = data.trim();
            if (payload.isEmpty()) {
                return;
            }
            if ("[DONE]".equals(payload)) {
                done = true;
                return;
            }
            if (done) {
                throw new IOException("Gemini interaction stream contains data after its end marker.");
            }
            JsonNode root = parseObject(payload);
            rejectError(root);
            String type = root.has("event_type") ? requiredText(root, "event_type") : event;
            if (type == null || type.isBlank()) {
                throw new IOException("Gemini interaction stream is missing an event type.");
            }
            if (event != null && event.isBlank() == false && event.equals(type) == false) {
                throw new IOException("Gemini interaction stream contains conflicting event types.");
            }
            if (completed != null) {
                if (type.startsWith("step.") || type.startsWith("interaction.")) {
                    throw new IOException("Gemini interaction stream contains data after completion.");
                }
                return;
            }
            switch (type) {
                case "interaction.created" -> readId(root.path("interaction"));
                case "interaction.status_update" -> {
                    if (root.has("interaction_id")) {
                        acceptId(requiredText(root, "interaction_id"));
                    }
                    String status = requiredText(root, "status");
                    if ("failed".equals(status) || "cancelled".equals(status)
                        || "incomplete".equals(status) || "requires_action".equals(status)) {
                        throw new IOException("Gemini interaction ended with status " + status + ".");
                    }
                }
                case "step.start" -> {
                    int index = stepIndex(root);
                    String stepType = requiredText(root.path("step"), "type");
                    rejectFunctionCall(stepType);
                    if (activeSteps.putIfAbsent(index, stepType) != null) {
                        throw new IOException("Gemini interaction stream repeated an active step.");
                    }
                }
                case "step.delta" -> acceptDelta(root);
                case "step.stop" -> {
                    if (activeSteps.remove(stepIndex(root)) == null) {
                        throw new IOException("Gemini interaction stream stopped an unknown step.");
                    }
                }
                case "interaction.completed" -> {
                    JsonNode interaction = root.path("interaction");
                    requireCompleted(interaction);
                    readId(interaction);
                    if (activeSteps.isEmpty() == false) {
                        throw new IOException("Gemini interaction stream completed with unfinished steps.");
                    }
                    completed = interaction;
                }
                case "error" -> throw new IOException("Gemini interaction stream reported an error.");
                default -> { }
            }
        }

        private void readId(JsonNode interaction) throws IOException {
            acceptId(requiredText(interaction, "id"));
        }

        private void acceptId(String receivedId) throws IOException {
            if (id != null && id.equals(receivedId) == false) {
                throw new IOException("Gemini interaction stream returned inconsistent IDs.");
            }
            id = receivedId;
        }

        private static int stepIndex(JsonNode root) throws IOException {
            JsonNode index = root.path("index");
            if (index.isIntegralNumber() == false || index.canConvertToInt() == false || index.intValue() < 0) {
                throw new IOException("Gemini interaction stream contains an invalid step index.");
            }
            return index.intValue();
        }

        /**
         * Collects and optionally emits text deltas belonging to an active model output step.
         *
         * @param root step delta event containing its index and typed delta
         * @throws IOException if the step is unknown, required fields are invalid or the listener fails
         */
        private void acceptDelta(JsonNode root) throws IOException {
            String stepType = activeSteps.get(stepIndex(root));
            if (stepType == null) {
                throw new IOException("Gemini interaction stream contains a delta for an unknown step.");
            }
            JsonNode delta = root.path("delta");
            String type = requiredText(delta, "type");
            if ("model_output".equals(stepType) && "text".equals(type)) {
                String fragment = textValue(delta, "text");
                if (fragment.isEmpty() == false) {
                    text.append(fragment);
                    if (listener != null) {
                        try {
                            listener.onTextDelta(fragment);
                        } catch (Exception exception) {
                            throw new IOException("The stream listener rejected a Gemini response fragment.", exception);
                        }
                    }
                }
            }
        }

        private AiResponse result() throws IOException {
            if (completed == null) {
                throw new IOException("Gemini interaction stream ended before completion.");
            }
            return response(text, completed, id);
        }
    }
}
