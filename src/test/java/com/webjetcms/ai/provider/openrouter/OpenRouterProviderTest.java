package com.webjetcms.ai.provider.openrouter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.BinaryContent;

class OpenRouterProviderTest {

    @Test
    void imageGenerationSeparatesSecurityRulesFromTaskAndUntrustedInput() throws Exception {
        try (OpenRouterProvider provider = new OpenRouterProvider()) {
            AiRequest request = AiRequest.builder()
                .operation(AiOperation.GENERATE_IMAGE)
                .model("image-model")
                .instructions("Create a clean icon.")
                .inputText("Draw a blue rocket.")
                .userPrompt("Use a transparent background.")
                .build();

            ObjectNode body = provider.buildChatBody(request, false);
            JsonNode messages = body.path("messages");
            JsonNode content = messages.get(1).path("content");

            assertEquals("system", messages.get(0).path("role").asText());
            assertTrue(messages.get(0).path("content").asText().contains("[AI_PROMPT_SECURITY_RULES_BEGIN]"));
            assertFalse(messages.get(0).path("content").asText().contains("Create a clean icon."));
            assertTrue(content.get(0).path("text").asText().contains("[TASK_INSTRUCTIONS_BEGIN]"));
            assertTrue(content.get(0).path("text").asText().contains("Create a clean icon."));
            assertTrue(content.get(1).path("text").asText().contains("[BEGIN_UNTRUSTED_INPUT_TEXT]"));
            assertTrue(content.get(2).path("text").asText().contains("[BEGIN_UNTRUSTED_USER_PROMPT]"));
            assertEquals("image", body.path("modalities").get(0).asText());
            assertEquals("text", body.path("modalities").get(1).asText());
        }
    }

    @Test
    void imageEditingPlacesTheImageAfterTaskAndUserPrompt() throws Exception {
        byte[] sourceImage = { 1, 2, 3, 4 };
        try (OpenRouterProvider provider = new OpenRouterProvider()) {
            AiRequest request = AiRequest.builder()
                .operation(AiOperation.EDIT_IMAGE)
                .model("image-model")
                .instructions("Remove the background.")
                .userPrompt("Keep the shadow.")
                .inputMedia(new BinaryContent(sourceImage, "image/png", "source.png"))
                .build();

            JsonNode content = provider.buildChatBody(request, false)
                .path("messages").get(1).path("content");

            assertTrue(content.get(0).path("text").asText().contains("[TASK_INSTRUCTIONS_BEGIN]"));
            assertTrue(content.get(1).path("text").asText().contains("[BEGIN_UNTRUSTED_USER_PROMPT]"));
            assertEquals("image_url", content.get(2).path("type").asText());
            assertEquals(
                "data:image/png;base64," + Base64.getEncoder().encodeToString(sourceImage),
                content.get(2).path("image_url").path("url").asText()
            );
        }
    }

    @Test
    void decodesGeneratedImagesAndMapsUsage() throws Exception {
        byte[] generatedImage = { 9, 8, 7, 6 };
        String responseJson = """
            {
              "choices": [{
                "message": {
                  "content": "Generated image",
                  "images": [{
                    "type": "image_url",
                    "image_url": {"url": "data:image/webp;base64,%s"}
                  }]
                },
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 10, "completion_tokens": 4, "total_tokens": 14}
            }
            """.formatted(Base64.getEncoder().encodeToString(generatedImage));

        try (OpenRouterProvider provider = new OpenRouterProvider()) {
            AiResponse response = provider.parseCompletion(responseJson, AiOperation.GENERATE_IMAGE);

            assertEquals("Generated image", response.text());
            assertEquals(1, response.media().size());
            assertEquals("image/webp", response.media().get(0).mediaType());
            assertArrayEquals(generatedImage, response.media().get(0).data());
            assertEquals(14, response.usage().totalTokens());
            assertEquals("stop", response.finishReason());
        }
    }

    @Test
    void rejectsPartialOrFilteredCompletionFinishReasons() throws Exception {
        String responseJson = """
            {
              "choices": [{
                "message": {"content": "Partial response"},
                "finish_reason": "length"
              }]
            }
            """;

        try (OpenRouterProvider provider = new OpenRouterProvider()) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> provider.parseCompletion(responseJson, AiOperation.TEXT)
            );

            assertTrue(exception.getMessage().contains("length"));
            assertFalse(exception.retryable());
        }
    }
}
