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
import java.util.Map;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.fasterxml.jackson.databind.JsonNode;
import com.webjetcms.ai.AiClient;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.BinaryContent;
import com.webjetcms.ai.ImageOptionDefinition;
import com.webjetcms.ai.ImageOptions;
import com.webjetcms.ai.security.PromptInjectionDefense;

class GeminiResponseParserTest {

    private static final AiProviderConfig CONFIG = AiProviderConfig.builder("key").build();
    private static final BinaryContent SOURCE_IMAGE = new BinaryContent(
        new byte[] { 1, 2, 3 },
        "image/png",
        "source.png"
    );
    private static final String CONTROL_ONLY = "\u0000\u200B\u2060";
    private static final String PROMPT_REQUIRED = "Gemini image prompt is required.";
    private static final String IMAGE_RESPONSE = """
        {
          "candidates": [{
            "content": {"parts": [{
              "inlineData": {"mimeType": "image/png", "data": "AQID"}
            }]},
            "finishReason": "STOP"
          }]
        }
        """;

    @Test
    void exposesExactImageOptionsByGeminiModelFamily() throws Exception {
        try (GeminiProvider provider = new GeminiProvider()) {
            Map<String, ImageOptionDefinition> flash = provider.imageOptions(
                "models/gemini-3.1-flash-image-preview",
                AiOperation.GENERATE_IMAGE
            );
            assertEquals(List.of("size", "aspectRatio"), List.copyOf(flash.keySet()));
            assertEquals(List.of("512", "1K", "2K", "4K"), flash.get("size").allowedValues());
            Map<String, ImageOptionDefinition> flashLite = provider.imageOptions(
                "gemini-3.1-flash-lite-image",
                AiOperation.EDIT_IMAGE
            );
            assertEquals(List.of("1K"), flashLite.get("size").allowedValues());
            assertEquals(
                List.of(
                    "1:1", "1:4", "1:8", "2:3", "3:2", "3:4", "4:1",
                    "4:3", "4:5", "5:4", "8:1", "9:16", "16:9", "21:9"
                ),
                flashLite.get("aspectRatio").allowedValues()
            );
            assertTrue(flashLite.get("aspectRatio").accepts("1:8"));
            assertTrue(flashLite.get("aspectRatio").accepts("8:1"));
            assertEquals(
                List.of("aspectRatio"),
                List.copyOf(provider.imageOptions(
                    "gemini-2.5-flash-image",
                    AiOperation.GENERATE_IMAGE
                ).keySet())
            );
        }
    }

    @Test
    void mapsPortableSizeAndAspectRatioIntoImageConfig() {
        AiRequest request = AiRequest.builder()
            .operation(AiOperation.GENERATE_IMAGE)
            .model("gemini-3.1-flash-lite-image")
            .inputText("An isometric garden")
            .imageOptions(ImageOptions.builder()
                .size("1K")
                .providerOption("aspectRatio", "1:8")
                .build())
            .build();

        JsonNode imageConfig = GeminiProvider.buildRequestBody(request)
            .path("generationConfig").path("imageConfig");

        assertEquals("1K", imageConfig.path("imageSize").asText());
        assertEquals("1:8", imageConfig.path("aspectRatio").asText());
    }

    @Test
    void rejectsDirectImageRequestsWithoutMeaningfulPromptsBeforeTransport() throws Exception {
        RecordingHttpClient transport = new RecordingHttpClient();

        try (GeminiProvider provider = new GeminiProvider(transport)) {
            for (AiRequest request : requestsWithoutMeaningfulPrompts()) {
                assertPromptRequired(() -> provider.execute(request, CONFIG));
            }
        }

        assertEquals(0, transport.calls);
    }

    @Test
    void validatesMeaningfulImagePromptsAfterAiClientPreparation() throws Exception {
        RecordingHttpClient transport = new RecordingHttpClient();

        try (AiClient client = AiClient.of(new GeminiProvider(transport))) {
            for (AiRequest request : requestsWithoutMeaningfulPrompts()) {
                assertPromptRequired(() -> client.execute(request, CONFIG));
            }
            for (AiRequest request : requestsWithMeaningfulPrompts()) {
                client.execute(request, CONFIG);
            }
        }

        assertEquals(3, transport.calls);
    }

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

    private static List<AiRequest> requestsWithoutMeaningfulPrompts() {
        return List.of(
            imageRequest(AiOperation.GENERATE_IMAGE)
                .instructions(PromptInjectionDefense.getSecurityInstructions(null))
                .inputText(CONTROL_ONLY)
                .build(),
            editRequest()
                .inputText("/ignored/source.png")
                .userPrompt(CONTROL_ONLY)
                .build()
        );
    }

    private static List<AiRequest> requestsWithMeaningfulPrompts() {
        return List.of(
            imageRequest(AiOperation.GENERATE_IMAGE)
                .instructions("Create a line drawing of a lighthouse")
                .build(),
            imageRequest(AiOperation.GENERATE_IMAGE)
                .inputText("Create a watercolor garden")
                .build(),
            editRequest().userPrompt("Remove the background").build()
        );
    }

    private static AiRequest.Builder imageRequest(AiOperation operation) {
        return AiRequest.builder().operation(operation).model("gemini-test");
    }

    private static AiRequest.Builder editRequest() {
        return imageRequest(AiOperation.EDIT_IMAGE).inputMedia(SOURCE_IMAGE);
    }

    private static void assertPromptRequired(Executable call) {
        assertEquals(
            PROMPT_REQUIRED,
            assertThrows(AiProviderException.class, call).getMessage()
        );
    }

    private static final class RecordingHttpClient extends CloseableHttpClient {
        private int calls;

        @Override
        protected CloseableHttpResponse doExecute(
            HttpHost target,
            HttpRequest request,
            HttpContext context
        ) {
            calls++;
            return new StubResponse();
        }

        @Override
        public void close() {
            // Nothing to close in the deterministic test transport.
        }

        @Override
        @SuppressWarnings("deprecation")
        public HttpParams getParams() {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public ClientConnectionManager getConnectionManager() {
            return null;
        }
    }

    private static final class StubResponse extends BasicHttpResponse implements CloseableHttpResponse {
        private StubResponse() {
            super(HttpVersion.HTTP_1_1, 200, "");
            setEntity(new StringEntity(IMAGE_RESPONSE, ContentType.APPLICATION_JSON));
        }

        @Override
        public void close() {
            // Nothing to close in the in-memory response.
        }
    }
}
