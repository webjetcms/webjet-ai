package com.webjetcms.ai.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.BinaryContent;

class GeminiResponseParserTest {

    @Test
    void parsesTextMediaFinishReasonAndUsage() throws Exception {
        byte[] image = new byte[] { 1, 2, 3, 4 };
        String payload = """
            {
              "candidates": [{
                "content": {"parts": [
                  {"text": "Hello "},
                  {"inlineData": {"mimeType": "image/png", "data": "%s"}},
                  {"text": "world"}
                ]},
                "finishReason": "STOP"
              }],
              "usageMetadata": {
                "promptTokenCount": 4,
                "candidatesTokenCount": 3,
                "thoughtsTokenCount": 2,
                "totalTokenCount": 9
              }
            }
            """.formatted(Base64.getEncoder().encodeToString(image));

        AiResponse response = GeminiResponseParser.parse(payload);

        assertEquals("Hello world", response.text());
        assertEquals("STOP", response.finishReason());
        assertEquals(1, response.media().size());
        assertEquals("image/png", response.media().get(0).mediaType());
        assertArrayEquals(image, response.media().get(0).data());
        assertEquals(4, response.usage().inputTokens());
        assertEquals(3, response.usage().outputTokens());
        assertEquals(9, response.usage().totalTokens());
        assertEquals(2, response.usage().details().get("thoughtsTokenCount"));
    }

    @Test
    void parsesSseDeltasAndFinalUsage() throws Exception {
        String payload = """
            : keep-alive

            data: {"candidates":[{"content":{"parts":[{"text":"First "}]}}]}

            data: {"candidates":[{"content":{"parts":[{"text":"second"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2,"totalTokenCount":7}}

            """;
        List<String> deltas = new ArrayList<>();

        AiResponse response = GeminiResponseParser.parseStream(
            new BufferedReader(new StringReader(payload)),
            deltas::add
        );

        assertEquals(List.of("First ", "second"), deltas);
        assertEquals("First second", response.text());
        assertEquals(7, response.usage().totalTokens());
    }

    @Test
    void parsesJsonArrayFallbackUsedByCompatibleStreamingEndpoints() throws Exception {
        String payload = """
            [
              {"candidates":[{"content":{"parts":[{"text":"A"}]}}]},
              {"candidates":[{"content":{"parts":[{"text":"B"}]},"finishReason":"STOP"}]}
            ]
            """;
        List<String> deltas = new ArrayList<>();

        AiResponse response = GeminiResponseParser.parseStream(
            new BufferedReader(new StringReader(payload)),
            deltas::add
        );

        assertEquals("AB", response.text());
        assertEquals(List.of("A", "B"), deltas);
    }

    @Test
    void rejectsStreamWithoutTerminalStopResponse() {
        String payload = """
            data: {"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}

            """;

        IOException exception = assertThrows(IOException.class, () -> GeminiResponseParser.parseStream(
            new BufferedReader(new StringReader(payload)),
            ignored -> { }
        ));

        assertTrue(exception.getMessage().contains("terminal STOP"));
    }

    @Test
    void rejectsNonSuccessfulFinishReason() {
        String payload = """
            {"candidates":[{"content":{"parts":[]},"finishReason":"SAFETY","finishMessage":"Blocked"}]}
            """;

        IOException exception = assertThrows(IOException.class, () -> GeminiResponseParser.parse(payload));

        assertTrue(exception.getMessage().contains("SAFETY"));
        assertTrue(exception.getMessage().contains("Blocked"));
    }

    @Test
    void rejectsStoppedBufferedResponseWithoutUsableContent() {
        String payload = """
            {"candidates":[{"content":{"parts":[{"thought":true} ]},"finishReason":"STOP"}]}
            """;

        IOException exception = assertThrows(IOException.class, () -> GeminiResponseParser.parse(payload));

        assertTrue(exception.getMessage().contains("no usable text or media"));
    }

    @Test
    void rejectsStoppedStreamWithoutUsableContent() {
        String payload = """
            data: {"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}

            """;

        IOException exception = assertThrows(IOException.class, () -> GeminiResponseParser.parseStream(
            new BufferedReader(new StringReader(payload)),
            ignored -> { }
        ));

        assertTrue(exception.getMessage().contains("no usable text or media"));
    }

    @Test
    void reportsPromptSafetyBlockInsteadOfReturningAnEmptySuccess() {
        String payload = """
            {"promptFeedback":{"blockReason":"PROHIBITED_CONTENT","blockReasonMessage":"Unsafe prompt"}}
            """;

        IOException exception = assertThrows(IOException.class, () -> GeminiResponseParser.parse(payload));

        assertTrue(exception.getMessage().contains("PROHIBITED_CONTENT"));
        assertTrue(exception.getMessage().contains("Unsafe prompt"));
    }

    @Test
    void buildsProtectedGeminiMultimodalEditRequestWithoutImagePathText() {
        byte[] image = "image".getBytes(StandardCharsets.UTF_8);
        AiRequest request = AiRequest.builder()
            .operation(AiOperation.EDIT_IMAGE)
            .model("gemini-test")
            .instructions("Remove the background")
            .inputText("/host-specific/path/source.png")
            .userPrompt("User detail")
            .inputMedia(new BinaryContent(image, "image/png", "source.png"))
            .build();

        JsonNode root = GeminiProvider.buildRequestBody(request);
        JsonNode parts = root.path("contents").get(0).path("parts");

        String system = root.path("systemInstruction").path("parts").get(0).path("text").asText();
        assertTrue(system.contains("[AI_PROMPT_SECURITY_RULES_BEGIN]"));
        assertTrue(system.contains("Remove the background") == false);
        assertTrue(parts.get(0).path("text").asText().contains("[TASK_INSTRUCTIONS_BEGIN]"));
        assertTrue(parts.get(0).path("text").asText().contains("Remove the background"));
        assertTrue(parts.get(1).path("text").asText().contains("[BEGIN_UNTRUSTED_USER_PROMPT]"));
        assertTrue(parts.toString().contains("/host-specific/path/source.png") == false);
        assertEquals("image/png", parts.get(2).path("inlineData").path("mimeType").asText());
        assertEquals(Base64.getEncoder().encodeToString(image), parts.get(2).path("inlineData").path("data").asText());
        assertEquals("IMAGE", root.path("generationConfig").path("responseModalities").get(0).asText());
    }

    @Test
    void hardensTextInstructionsAndProtectsBothUntrustedFields() {
        AiRequest request = AiRequest.builder()
            .operation(AiOperation.TEXT)
            .model("gemini-test")
            .instructions("Summarize the source")
            .inputText("Source text")
            .userPrompt("Keep it short")
            .build();

        JsonNode root = GeminiProvider.buildRequestBody(request);
        JsonNode parts = root.path("contents").get(0).path("parts");
        String system = root.path("systemInstruction").path("parts").get(0).path("text").asText();

        assertTrue(system.contains("[AI_PROMPT_SECURITY_RULES_BEGIN]"));
        assertTrue(system.contains("[TASK_INSTRUCTIONS_BEGIN]"));
        assertTrue(parts.get(0).path("text").asText().contains("[BEGIN_UNTRUSTED_INPUT_TEXT]"));
        assertTrue(parts.get(1).path("text").asText().contains("[BEGIN_UNTRUSTED_USER_PROMPT]"));
    }
}
