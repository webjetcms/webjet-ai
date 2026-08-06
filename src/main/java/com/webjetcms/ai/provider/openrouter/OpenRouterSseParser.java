package com.webjetcms.ai.provider.openrouter;

import java.io.BufferedReader;
import java.io.IOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.AiStreamListener;
import com.webjetcms.ai.TokenUsage;

/** Parses OpenRouter's Server-Sent Events stream without any servlet dependency. */
final class OpenRouterSseParser {

    private final ObjectMapper mapper;
    private final StringBuilder text = new StringBuilder();
    private TokenUsage usage = TokenUsage.EMPTY;
    private String finishReason;

    OpenRouterSseParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    AiResponse parse(BufferedReader reader, AiStreamListener listener) throws IOException, AiProviderException {
        StringBuilder eventData = new StringBuilder();
        String line;
        boolean done = false;

        while (done == false && (line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                done = dispatch(eventData, listener);
                eventData.setLength(0);
                continue;
            }
            if (line.startsWith(":")) continue;
            if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (value.startsWith(" ")) value = value.substring(1);
                if (eventData.length() > 0) eventData.append('\n');
                eventData.append(value);
            }
        }
        if (done == false && eventData.length() > 0) {
            done = dispatch(eventData, listener);
        }
        if (done == false) {
            throw incompleteStream("OpenRouter stream ended before the [DONE] event.");
        }
        if (finishReason == null || finishReason.isBlank()) {
            throw incompleteStream("OpenRouter stream ended without a finish reason.");
        }

        return new AiResponse(text.toString(), java.util.List.of(), usage, finishReason);
    }

    private boolean dispatch(StringBuilder eventData, AiStreamListener listener) throws AiProviderException {
        if (eventData.length() == 0) return false;
        String data = eventData.toString().trim();
        if (data.isEmpty()) return false;
        if ("[DONE]".equals(data)) return true;

        JsonNode root;
        try {
            root = mapper.readTree(data);
        } catch (JsonProcessingException exception) {
            throw new AiProviderException(
                OpenRouterProvider.PROVIDER_ID,
                -1,
                "OpenRouter returned invalid JSON in its event stream.",
                data,
                false,
                exception
            );
        }

        if (root.hasNonNull("error")) {
            String message = root.path("error").path("message").asText("OpenRouter stream failed.");
            throw new AiProviderException(
                OpenRouterProvider.PROVIDER_ID,
                -1,
                message,
                data,
                false
            );
        }

        JsonNode usageNode = root.get("usage");
        if (usageNode != null && usageNode.isNull() == false) {
            usage = OpenRouterProvider.parseUsage(usageNode);
        }

        JsonNode choices = root.path("choices");
        if (choices.isArray() == false || choices.isEmpty()) return false;

        JsonNode choice = choices.get(0);
        String delta = OpenRouterProvider.extractText(choice.path("delta").get("content"));
        if (delta.isEmpty() == false) {
            try {
                listener.onTextDelta(delta);
            } catch (Exception exception) {
                throw new AiProviderException(
                    OpenRouterProvider.PROVIDER_ID,
                    "OpenRouter stream listener failed.",
                    exception
                );
            }
            text.append(delta);
        }

        JsonNode finishReasonNode = choice.get("finish_reason");
        if (finishReasonNode != null && finishReasonNode.isNull() == false) {
            finishReason = finishReasonNode.asText();
            OpenRouterProvider.ensureSuccessfulFinishReason(finishReason, data);
        }
        return false;
    }

    private AiProviderException incompleteStream(String message) {
        return new AiProviderException(
            OpenRouterProvider.PROVIDER_ID,
            200,
            message,
            text.toString(),
            false
        );
    }
}
