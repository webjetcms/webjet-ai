package com.webjetcms.ai.provider.openai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.BinaryContent;
import com.webjetcms.ai.ModelInfo;
import com.webjetcms.ai.security.PromptInjectionDefense;
import com.webjetcms.ai.security.PromptInjectionDefense.UntrustedSource;

class OpenAiProviderTest {

    @Test
    void reportsNullRequestsAndListenersAsProviderValidationErrors() throws Exception {
        AiProviderConfig config = AiProviderConfig.builder("key").build();
        try (OpenAiProvider provider = new OpenAiProvider()) {
            AiProviderException missingRequest = assertThrows(
                AiProviderException.class,
                () -> provider.execute(null, config)
            );
            AiProviderException missingStreamRequest = assertThrows(
                AiProviderException.class,
                () -> provider.stream(null, config, ignored -> { })
            );
            AiProviderException missingListener = assertThrows(
                AiProviderException.class,
                () -> provider.stream(AiRequest.builder().model("gpt-test").build(), config, null)
            );

            assertEquals(OpenAiProvider.PROVIDER_ID, missingRequest.providerId());
            assertEquals(OpenAiProvider.PROVIDER_ID, missingStreamRequest.providerId());
            assertEquals(OpenAiProvider.PROVIDER_ID, missingListener.providerId());
        }
    }

    @Test
    void parsesAndSortsModelsByCreationTime() throws Exception {
        List<ModelInfo> models = OpenAiProvider.parseModels("""
            {
              "data": [
                {"id": "older", "created": 10},
                {"id": "newer", "created": 20},
                {"id": "without-date"}
              ]
            }
            """);

        assertEquals(List.of("newer", "older", "without-date"),
            models.stream().map(ModelInfo::id).toList());
        assertEquals("newer", models.get(0).displayName());
        assertEquals(20L, models.get(0).createdAt());
    }

    @Test
    void createsResponsesApiInputWithoutHostTypes() {
        AiRequest request = AiRequest.builder()
            .model("gpt-4.1")
            .instructions("System rules")
            .inputText("Source text")
            .userPrompt("Rewrite it")
            .inputMedia(new BinaryContent(new byte[] {1, 2, 3}, "image/png", "source.png"))
            .store(false)
            .build();

        ObjectNode body = OpenAiProvider.buildTextBody(request);

        assertEquals("gpt-4.1", body.path("model").asText());
        assertFalse(body.path("store").asBoolean());
        assertEquals("system", body.path("input").get(0).path("role").asText());
        assertTrue(body.path("input").get(0).path("content").asText()
            .contains("[AI_PROMPT_SECURITY_RULES_BEGIN]"));
        assertTrue(body.path("input").get(0).path("content").asText()
            .contains("[TASK_INSTRUCTIONS_BEGIN]"));
        assertTrue(body.path("input").get(1).path("content").asText()
            .contains("[BEGIN_UNTRUSTED_INPUT_TEXT]"));
        assertTrue(body.path("input").get(1).path("content").asText().contains("Source text"));
        assertTrue(body.path("input").get(2).path("content").asText()
            .contains("[BEGIN_UNTRUSTED_USER_PROMPT]"));
        assertTrue(body.path("input").get(2).path("content").asText().contains("Rewrite it"));
        assertEquals("input_image",
            body.path("input").get(3).path("content").get(0).path("type").asText());
        assertEquals("data:image/png;base64,AQID",
            body.path("input").get(3).path("content").get(0).path("image_url").asText());
    }

    @Test
    void securesImagePromptsAndExcludesImagePathFromEditPrompt() {
        AiRequest generation = AiRequest.builder()
            .operation(AiOperation.GENERATE_IMAGE)
            .model("gpt-image-1")
            .instructions("Create an illustration")
            .inputText("A lighthouse at dusk")
            .userPrompt("Use a watercolor style")
            .build();

        String generationPrompt = OpenAiProvider.imagePrompt(generation);
        assertTrue(generationPrompt.contains("[AI_PROMPT_SECURITY_RULES_BEGIN]"));
        assertTrue(generationPrompt.contains("[TASK_INSTRUCTIONS_BEGIN]"));
        assertTrue(generationPrompt.contains("[BEGIN_UNTRUSTED_INPUT_TEXT]"));
        assertTrue(generationPrompt.contains("[BEGIN_UNTRUSTED_USER_PROMPT]"));

        AiRequest edit = AiRequest.builder()
            .operation(AiOperation.EDIT_IMAGE)
            .model("gpt-image-1")
            .instructions("Edit the image")
            .inputText("/host-specific/path/source.png")
            .userPrompt("Make the sky brighter")
            .build();

        String editPrompt = OpenAiProvider.imagePrompt(edit);
        assertTrue(editPrompt.contains("[AI_PROMPT_SECURITY_RULES_BEGIN]"));
        assertTrue(editPrompt.contains("[TASK_INSTRUCTIONS_BEGIN]"));
        assertTrue(editPrompt.contains("[BEGIN_UNTRUSTED_USER_PROMPT]"));
        assertFalse(editPrompt.contains("[BEGIN_UNTRUSTED_INPUT_TEXT]"));
        assertFalse(editPrompt.contains("/host-specific/path/source.png"));
    }

    @Test
    void encodesMultipartImageEditTextAsUtf8() throws Exception {
        BinaryContent input = new BinaryContent(new byte[] {1, 2, 3}, "image/png", "source.png");
        AiRequest request = AiRequest.builder()
            .operation(AiOperation.EDIT_IMAGE)
            .model("gpt-image-1")
            .inputMedia(input)
            .build();
        String prompt = "Žltý kôň pri rieke";

        HttpEntity entity = OpenAiProvider.buildImageEditEntity(request, input, prompt);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        entity.writeTo(bytes);
        String multipartBody = bytes.toString(StandardCharsets.UTF_8);

        assertTrue(multipartBody.contains(prompt));
    }

    @Test
    void stripsMultipartFilenamePathsAndHeaderControlCharacters() throws Exception {
        String hostileName = "C:\\private/secret\r\nX-Injected: true.png";
        BinaryContent input = new BinaryContent(new byte[] {1, 2, 3}, "image/png", hostileName);
        AiRequest request = AiRequest.builder()
            .operation(AiOperation.EDIT_IMAGE)
            .model("gpt-image-1")
            .inputMedia(input)
            .build();

        assertEquals("secret__X-Injected__true.png", OpenAiProvider.safeMultipartFileName(hostileName));
        assertEquals("image", OpenAiProvider.safeMultipartFileName("../"));

        HttpEntity entity = OpenAiProvider.buildImageEditEntity(request, input, "Edit the image");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        entity.writeTo(bytes);
        String multipartBody = bytes.toString(StandardCharsets.UTF_8);

        assertTrue(multipartBody.contains("filename=\"secret__X-Injected__true.png\""));
        assertFalse(multipartBody.contains("C:\\private"));
        assertFalse(multipartBody.contains("X-Injected: true"));
    }

    @Test
    void leavesPreprotectedTextRequestValuesUnchanged() {
        String hardened = PromptInjectionDefense.hardenSystemInstructions("Summarize the source");
        String protectedInput = PromptInjectionDefense
            .protectUntrustedText("Source text", UntrustedSource.INPUT_TEXT)
            .protectedText();
        String protectedPrompt = PromptInjectionDefense
            .protectUntrustedText("Keep it short", UntrustedSource.USER_PROMPT)
            .protectedText();
        AiRequest request = AiRequest.builder()
            .model("gpt-4.1")
            .instructions(hardened)
            .inputText(protectedInput)
            .userPrompt(protectedPrompt)
            .build();

        ObjectNode body = OpenAiProvider.buildTextBody(request);

        assertEquals(hardened, body.path("input").get(0).path("content").asText());
        assertEquals(protectedInput, body.path("input").get(1).path("content").asText());
        assertEquals(protectedPrompt, body.path("input").get(2).path("content").asText());
    }

    @Test
    void parsesTextAndUsageWhileIgnoringReasoningOutput() throws Exception {
        AiResponse response = OpenAiProvider.parseTextResponse("""
            {
              "status": "completed",
              "output": [
                {"type": "reasoning", "content": [{"text": "hidden"}]},
                {"type": "message", "content": [
                  {"type": "output_text", "text": "Hello "},
                  {"type": "output_text", "text": "world"}
                ]}
              ],
              "usage": {
                "input_tokens": 11,
                "output_tokens": 4,
                "total_tokens": 15,
                "input_tokens_details": {"cached_tokens": 3}
              }
            }
            """);

        assertEquals("Hello world", response.text());
        assertEquals("completed", response.finishReason());
        assertEquals(11, response.usage().inputTokens());
        assertEquals(4, response.usage().outputTokens());
        assertEquals(15, response.usage().totalTokens());
        assertEquals(3, response.usage().details().get("input_tokens_details.cached_tokens"));
    }

    @Test
    void decodesGeneratedImages() throws Exception {
        String encoded = java.util.Base64.getEncoder()
            .encodeToString("image-data".getBytes(StandardCharsets.UTF_8));

        AiResponse response = OpenAiProvider.parseImageResponse("""
            {
              "output_format": "webp",
              "data": [{"b64_json": "%s"}],
              "usage": {"input_tokens": 2, "output_tokens": 7, "total_tokens": 9}
            }
            """.formatted(encoded));

        assertEquals(1, response.media().size());
        assertEquals("image/webp", response.media().get(0).mediaType());
        assertArrayEquals("image-data".getBytes(StandardCharsets.UTF_8), response.media().get(0).data());
        assertEquals(9, response.usage().totalTokens());
    }

    @Test
    void parsesStreamingDeltasAndCompletionUsage() throws Exception {
        String stream = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"Hel"}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"lo"}

            event: response.completed
            data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":5,"output_tokens":2,"total_tokens":7}}}

            event: done
            data: [DONE]

            """;
        List<String> deltas = new ArrayList<>();

        OpenAiStreamParser.StreamResult result = OpenAiStreamParser.parse(
            new BufferedReader(new StringReader(stream)),
            deltas::add
        );

        assertEquals(List.of("Hel", "lo"), deltas);
        assertEquals("Hello", result.text());
        assertEquals("completed", result.finishReason());
        assertEquals(7, result.usage().totalTokens());
    }

    @Test
    void surfacesIncompleteStreamingResponses() {
        String stream = """
            event: response.incomplete
            data: {"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}

            """;

        AiProviderException exception = assertThrows(AiProviderException.class, () ->
            OpenAiStreamParser.parse(new BufferedReader(new StringReader(stream)), ignored -> { })
        );

        assertTrue(exception.getMessage().contains("max_output_tokens"));
        assertEquals(OpenAiProvider.PROVIDER_ID, exception.providerId());
        assertFalse(exception.retryable());
    }

    @Test
    void rejectsStreamThatEndsBeforeCompletedEvent() {
        String stream = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"partial"}

            """;

        AiProviderException exception = assertThrows(AiProviderException.class, () ->
            OpenAiStreamParser.parse(new BufferedReader(new StringReader(stream)), ignored -> { })
        );

        assertTrue(exception.getMessage().contains("response.completed"));
    }

    @Test
    void marksTransportIoFailuresAsRetryable() throws Exception {
        CloseableHttpClient failingClient = new CloseableHttpClient() {
            @Override
            protected CloseableHttpResponse doExecute(
                HttpHost target,
                HttpRequest request,
                HttpContext context
            ) throws IOException {
                throw new IOException("connection refused");
            }

            @Override
            public void close() {
                // No resources in this deterministic failure transport.
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
        };

        try (OpenAiProvider provider = new OpenAiProvider(failingClient)) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> provider.listModels(com.webjetcms.ai.AiProviderConfig.builder("key").build())
            );

            assertEquals(-1, exception.statusCode());
            assertTrue(exception.retryable());
        }
    }
}
