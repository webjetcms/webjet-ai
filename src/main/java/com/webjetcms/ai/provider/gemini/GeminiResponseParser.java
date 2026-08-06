package com.webjetcms.ai.provider.gemini;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.AiStreamListener;
import com.webjetcms.ai.GeneratedMedia;
import com.webjetcms.ai.TokenUsage;

/** Decodes Gemini generateContent and streamGenerateContent payloads. */
final class GeminiResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GeminiResponseParser() {
    }

    static AiResponse parse(String payload) throws IOException {
        if (payload == null || payload.isBlank()) {
            throw new IOException("Gemini returned an empty response.");
        }

        Accumulator accumulator = new Accumulator(null);
        accumulator.accept(MAPPER.readTree(payload));
        return accumulator.result(false);
    }

    static AiResponse parseStream(BufferedReader reader, AiStreamListener listener) throws IOException {
        Accumulator accumulator = new Accumulator(listener);
        StringBuilder eventData = new StringBuilder();
        StringBuilder nonSsePayload = new StringBuilder();
        boolean sawSseData = false;

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (eventData.length() > 0) {
                    acceptEvent(eventData, accumulator);
                    eventData.setLength(0);
                }
                continue;
            }

            if (line.startsWith(":")) {
                continue;
            }
            if (line.startsWith("data:")) {
                sawSseData = true;
                if (eventData.length() > 0) {
                    eventData.append('\n');
                }
                String value = line.substring(5);
                eventData.append(value.startsWith(" ") ? value.substring(1) : value);
                continue;
            }

            // Some compatible endpoints return the streamed JSON array instead of SSE.
            if (sawSseData == false) {
                nonSsePayload.append(line).append('\n');
            }
        }

        if (eventData.length() > 0) {
            acceptEvent(eventData, accumulator);
        }
        if (sawSseData == false && nonSsePayload.toString().isBlank() == false) {
            accumulator.accept(MAPPER.readTree(nonSsePayload.toString()));
        }

        return accumulator.result(true);
    }

    private static void acceptEvent(StringBuilder eventData, Accumulator accumulator) throws IOException {
        String payload = eventData.toString().trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }
        accumulator.accept(MAPPER.readTree(payload));
    }

    private static final class Accumulator {

        private final AiStreamListener listener;
        private final StringBuilder text = new StringBuilder();
        private final List<GeneratedMedia> media = new ArrayList<>();
        private final Map<String, Long> usageDetails = new LinkedHashMap<>();
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
        private String finishReason;
        private String finishMessage;
        private boolean sawPayload;
        private boolean sawCandidate;
        private boolean sawUsableContent;

        private Accumulator(AiStreamListener listener) {
            this.listener = listener;
        }

        private void accept(JsonNode root) throws IOException {
            if (root == null || root.isNull() || root.isMissingNode()) {
                return;
            }
            if (root.isArray()) {
                for (JsonNode chunk : root) {
                    accept(chunk);
                }
                return;
            }

            sawPayload = true;
            rejectEmbeddedError(root);
            rejectPromptFeedback(root);
            readCandidate(root.path("candidates"));
            readUsage(root.has("usageMetadata") ? root.path("usageMetadata") : root.path("usage"));
        }

        private void rejectEmbeddedError(JsonNode root) throws IOException {
            JsonNode error = root.path("error");
            if (error.isMissingNode() || error.isNull()) {
                return;
            }
            String message = error.path("message").asText(null);
            if (message == null || message.isBlank()) {
                message = error.toString();
            }
            throw new IOException("Gemini error: " + message);
        }

        private void rejectPromptFeedback(JsonNode root) throws IOException {
            JsonNode promptFeedback = root.path("promptFeedback");
            String blockReason = promptFeedback.path("blockReason").asText(null);
            if (blockReason == null || blockReason.isBlank()) {
                return;
            }
            String blockMessage = promptFeedback.path("blockReasonMessage").asText(null);
            String suffix = blockMessage == null || blockMessage.isBlank() ? "" : ": " + blockMessage;
            throw new IOException("Gemini blocked the prompt with " + blockReason + suffix);
        }

        private void readCandidate(JsonNode candidates) throws IOException {
            if (candidates.isArray() == false || candidates.isEmpty()) {
                return;
            }

            // Gemini may return alternative candidates. The client consumes the first one.
            JsonNode candidate = candidates.get(0);
            sawCandidate = true;
            JsonNode parts = candidate.path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    String delta = part.path("text").asText(null);
                    if (delta != null && delta.isEmpty() == false) {
                        text.append(delta);
                        sawUsableContent = true;
                        notifyListener(delta);
                    }
                    readInlineData(part);
                }
            }

            String candidateFinishReason = candidate.path("finishReason").asText(null);
            if (candidateFinishReason != null && candidateFinishReason.isBlank() == false) {
                finishReason = candidateFinishReason;
            }
            String candidateFinishMessage = candidate.path("finishMessage").asText(null);
            if (candidateFinishMessage != null && candidateFinishMessage.isBlank() == false) {
                finishMessage = candidateFinishMessage;
            }
        }

        private void readInlineData(JsonNode part) throws IOException {
            JsonNode inlineData = part.has("inlineData") ? part.path("inlineData") : part.path("inline_data");
            if (inlineData.isMissingNode() || inlineData.isNull()) {
                return;
            }

            String data = inlineData.path("data").asText(null);
            if (data == null || data.isBlank()) {
                return;
            }
            String mediaType = inlineData.has("mimeType")
                ? inlineData.path("mimeType").asText("application/octet-stream")
                : inlineData.path("mime_type").asText("application/octet-stream");
            try {
                media.add(new GeneratedMedia(Base64.getDecoder().decode(data), mediaType));
                sawUsableContent = true;
            } catch (IllegalArgumentException exception) {
                throw new IOException("Gemini returned invalid Base64 media data.", exception);
            }
        }

        private void notifyListener(String delta) throws IOException {
            if (listener == null) {
                return;
            }
            try {
                listener.onTextDelta(delta);
            } catch (Exception exception) {
                throw new IOException("The stream listener rejected a Gemini response fragment.", exception);
            }
        }

        private void readUsage(JsonNode usage) {
            if (usage.isObject() == false) {
                return;
            }

            for (Entry<String, JsonNode> field : usage.properties()) {
                if (field.getValue().isIntegralNumber()) {
                    usageDetails.put(field.getKey(), field.getValue().asLong());
                }
            }

            inputTokens = usage.path("promptTokenCount").asLong(inputTokens);
            outputTokens = usage.path("candidatesTokenCount").asLong(outputTokens);
            long fallbackTotal = inputTokens + outputTokens + usage.path("thoughtsTokenCount").asLong(0);
            totalTokens = usage.path("totalTokenCount").asLong(fallbackTotal);
        }

        private AiResponse result(boolean requireTerminalFinishReason) throws IOException {
            if (sawPayload == false) {
                throw new IOException("Gemini returned an empty response.");
            }
            if (sawCandidate == false) {
                throw new IOException("Gemini returned no response candidate.");
            }
            if (finishReason != null && "STOP".equalsIgnoreCase(finishReason) == false) {
                String suffix = finishMessage == null ? "" : ": " + finishMessage;
                throw new IOException("Gemini generation stopped with " + finishReason + suffix);
            }
            if (requireTerminalFinishReason && (finishReason == null || finishReason.isBlank())) {
                throw new IOException("Gemini stream ended before a terminal STOP response.");
            }
            if (sawUsableContent == false) {
                throw new IOException("Gemini returned no usable text or media content.");
            }

            TokenUsage usage = new TokenUsage(inputTokens, outputTokens, totalTokens, usageDetails);
            return new AiResponse(text.toString(), media, usage, finishReason);
        }
    }
}
