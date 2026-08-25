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
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webjetcms.ai.AiClient;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.BinaryContent;
import com.webjetcms.ai.EmbeddingOptions;
import com.webjetcms.ai.EmbeddingRequest;
import com.webjetcms.ai.EmbeddingResponse;
import com.webjetcms.ai.ImageOptionDefinition;
import com.webjetcms.ai.ImageOptionValueType;
import com.webjetcms.ai.ImageOptions;
import com.webjetcms.ai.ModelInfo;
import com.webjetcms.ai.security.PromptInjectionDefense;
import com.webjetcms.ai.security.PromptInjectionDefense.UntrustedSource;

class OpenAiProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void exposesModelAndOperationSpecificImageOptions() throws Exception {
        try (OpenAiProvider provider = new OpenAiProvider()) {
            Map<String, ImageOptionDefinition> edit = provider.imageOptions(
                "gpt-image-2",
                AiOperation.EDIT_IMAGE
            );
            assertEquals(ImageOptionValueType.PATTERN, edit.get("size").valueType());
            assertFalse(edit.containsKey("input_fidelity"));
            assertEquals(
                List.of("count", "size"),
                List.copyOf(provider.imageOptions("dall-e-2", AiOperation.EDIT_IMAGE).keySet())
            );
            assertEquals(Map.of(), provider.imageOptions("dall-e-3", AiOperation.EDIT_IMAGE));
            assertThrows(UnsupportedOperationException.class, edit::clear);
        }
    }

    @Test
    void sendsValidatedGenerationOptions() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        RecordingHttpClient transport = new RecordingHttpClient(
            200,
            "{\"data\":[{\"b64_json\":\"" + encoded + "\"}]}"
        );
        AiRequest request = AiRequest.builder()
            .operation(AiOperation.GENERATE_IMAGE)
            .model("gpt-image-1.5")
            .inputText("A mountain at sunrise")
            .imageOptions(ImageOptions.builder()
                .count(2)
                .providerOption("output_format", "webp")
                .providerOption("output_compression", 80)
                .build())
            .build();

        try (OpenAiProvider provider = new OpenAiProvider(transport)) {
            AiResponse response = provider.execute(request, embeddingConfig("key", "trusted"));
            assertEquals("image/webp", response.media().get(0).mediaType());
        }

        JsonNode body = MAPPER.readTree(transport.requestBody);
        assertEquals("https://example.test/custom/v1/images/generations", transport.uri);
        assertEquals(2, body.path("n").asInt());
        assertEquals("webp", body.path("output_format").asText());
        assertEquals(80, body.path("output_compression").asInt());
    }

    @Test
    void forwardsPortableGenerationOptionsForUncataloguedCompatibleModel() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        RecordingHttpClient transport = new RecordingHttpClient(
            200,
            "{\"data\":[{\"b64_json\":\"" + encoded + "\"}]}"
        );
        AiRequest request = AiRequest.builder()
            .operation(AiOperation.GENERATE_IMAGE)
            .model("compatible-image-model")
            .inputText("A mountain at sunrise")
            .imageOptions(new ImageOptions(3, "640x480", "vendor-quality"))
            .build();

        try (OpenAiProvider provider = new OpenAiProvider(transport)) {
            provider.execute(request, embeddingConfig("key", "trusted"));
        }

        JsonNode body = MAPPER.readTree(transport.requestBody);
        assertEquals("compatible-image-model", body.path("model").asText());
        assertEquals(3, body.path("n").asInt());
        assertEquals("640x480", body.path("size").asText());
        assertEquals("vendor-quality", body.path("quality").asText());
    }

    @Test
    void rejectsProviderSpecificOptionsForUncataloguedModels() throws Exception {
        RecordingHttpClient transport = new RecordingHttpClient(200, "{}");
        AiRequest request = AiRequest.builder()
            .operation(AiOperation.GENERATE_IMAGE)
            .model("compatible-image-model")
            .inputText("A mountain at sunrise")
            .imageOptions(ImageOptions.builder()
                .providerOption("model", "overridden-model")
                .build())
            .build();

        try (OpenAiProvider provider = new OpenAiProvider(transport)) {
            assertThrows(
                AiProviderException.class,
                () -> provider.execute(request, embeddingConfig("key", "trusted"))
            );
        }

        assertEquals(0, transport.calls);
    }

    @Test
    void rejectsInvalidImageDimensionsBeforeTransport() throws Exception {
        RecordingHttpClient transport = new RecordingHttpClient(200, "{}");
        try (OpenAiProvider provider = new OpenAiProvider(transport)) {
            assertThrows(AiProviderException.class, () -> provider.execute(
                AiRequest.builder()
                    .operation(AiOperation.GENERATE_IMAGE)
                    .model("gpt-image-2")
                    .inputText("Generate an image")
                    .imageOptions(new ImageOptions(1, "1025x1024", "high"))
                    .build(),
                embeddingConfig("key", "trusted")
            ));
        }
        assertEquals(0, transport.calls);
    }

    @Test
    void embedsOrderedBatchAndBuildsAuthenticatedRequest() throws Exception {
        RecordingHttpClient transport = new RecordingHttpClient(200, """
            {
              "data": [
                {"index": 1, "embedding": [0.3, 0.4]},
                {"index": 0, "embedding": [0.1, 0.2]}
              ],
              "usage": {"prompt_tokens": 7, "total_tokens": 7}
            }
            """);
        AiProviderConfig config = embeddingConfig("openai-secret", "trusted-secret");

        try (OpenAiProvider provider = new OpenAiProvider(transport)) {
            EmbeddingResponse response = provider.embed(
                new EmbeddingRequest(
                    "text-embedding-3-small",
                    List.of("first", "second"),
                    new EmbeddingOptions(2)
                ),
                config
            );

            assertArrayEquals(new float[] {0.1F, 0.2F}, response.embeddings().get(0).values());
            assertArrayEquals(new float[] {0.3F, 0.4F}, response.embeddings().get(1).values());
            assertEquals(7, response.usage().inputTokens());
            assertEquals(7, response.usage().totalTokens());
        }

        assertEquals("POST", transport.method);
        assertEquals("https://example.test/custom/v1/embeddings", transport.uri);
        assertEquals("Bearer openai-secret", transport.header(HttpHeaders.AUTHORIZATION));
        assertEquals("trusted-secret", transport.header("X-Test-Secret"));
        assertEquals("application/json", transport.header(HttpHeaders.ACCEPT));
        JsonNode requestBody = MAPPER.readTree(transport.requestBody);
        assertEquals("text-embedding-3-small", requestBody.path("model").asText());
        assertEquals(2, requestBody.path("dimensions").asInt());
        assertEquals("float", requestBody.path("encoding_format").asText());
        assertEquals(List.of("first", "second"), MAPPER.convertValue(requestBody.path("input"), List.class));

        RecordingHttpClient defaultsTransport = new RecordingHttpClient(200, """
            {
              "data": [
                {"index": 0, "embedding": [0.1, 0.2, 0.3]},
                {"index": 1, "embedding": [0.4, 0.5, 0.6]}
              ]
            }
            """);

        try (OpenAiProvider provider = new OpenAiProvider(defaultsTransport)) {
            EmbeddingResponse response = provider.embed(
                new EmbeddingRequest("text-embedding-ada-002", List.of("first", "second")),
                embeddingConfig("key", "trusted")
            );

            assertEquals(3, response.embeddings().get(0).dimensions());
            assertEquals(3, response.embeddings().get(1).dimensions());
        }

        assertFalse(MAPPER.readTree(defaultsTransport.requestBody).has("dimensions"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEmbeddingResponses")
    void rejectsMalformedEmbeddingResponses(
        String description,
        int status,
        String payload,
        Integer dimensions
    ) throws Exception {
        RecordingHttpClient transport = new RecordingHttpClient(status, payload);

        try (OpenAiProvider provider = new OpenAiProvider(transport)) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> provider.embed(
                    new EmbeddingRequest("embedding-model", List.of("input"), new EmbeddingOptions(dimensions)),
                    embeddingConfig("key", "trusted")
                )
            );

            assertEquals(status, exception.statusCode(), description);
            assertEquals(payload, exception.rawResponse(), description);
            assertFalse(exception.retryable(), description);
        }
    }

    @Test
    void redactsEmbeddingHttpErrorsAndTimeoutCauses() throws Exception {
        AiProviderConfig config = embeddingConfig("openai-secret", "trusted-secret");
        String errorPayload = """
            {"error":{"message":"Rejected openai-secret and trusted-secret"}}
            """;
        RecordingHttpClient rejected = new RecordingHttpClient(401, errorPayload);

        try (OpenAiProvider provider = new OpenAiProvider(rejected)) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> provider.embed(new EmbeddingRequest("model", List.of("input")), config)
            );

            assertEquals(401, exception.statusCode());
            assertFalse(exception.retryable());
            assertRedacted(exception, "openai-secret", "trusted-secret");
        }

        RecordingHttpClient timedOut = new RecordingHttpClient(
            new SocketTimeoutException("openai-secret trusted-secret timed out")
        );
        try (OpenAiProvider provider = new OpenAiProvider(timedOut)) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> provider.embed(new EmbeddingRequest("model", List.of("input")), config)
            );

            assertEquals(-1, exception.statusCode());
            assertTrue(exception.retryable());
            assertRedacted(exception, "openai-secret", "trusted-secret");
        }
    }

    @Test
    void validatesEmbeddingRequestsBeforeTransport() throws Exception {
        RecordingHttpClient transport = new RecordingHttpClient(200, "{}");
        AiProviderConfig config = embeddingConfig("key", "trusted");

        try (OpenAiProvider provider = new OpenAiProvider(transport)) {
            assertThrows(AiProviderException.class, () -> provider.embed(null, config));
            assertThrows(
                AiProviderException.class,
                () -> provider.embed(new EmbeddingRequest(" ", List.of("input")), config)
            );
        }

        assertEquals(0, transport.calls);
    }

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
    void validatesMeaningfulImagePromptsDirectlyAndAfterClientPreparation() throws Exception {
        RecordingHttpClient transport = new RecordingHttpClient(
            200, "{\"data\":[{\"b64_json\":\"AQID\"}]}"
        );
        AiProviderConfig config = embeddingConfig("key", "trusted");
        String controlCharactersOnly = "\u0000\u200B\u2060";
        AiRequest[] invalidRequests = {
            AiRequest.builder()
                .operation(AiOperation.GENERATE_IMAGE)
                .model("gpt-image-1")
                .instructions(PromptInjectionDefense.getSecurityInstructions(null))
                .inputText(controlCharactersOnly)
                .build(),
            AiRequest.builder()
                .operation(AiOperation.EDIT_IMAGE)
                .model("gpt-image-1")
                .inputText("/ignored/source.png")
                .userPrompt(controlCharactersOnly)
                .inputMedia(new BinaryContent(new byte[] {1, 2, 3}, "image/png", "source.png"))
                .build()
        };

        try (OpenAiProvider provider = new OpenAiProvider(transport)) {
            assertImagePromptsRejected(invalidRequests, request -> provider.execute(request, config));
        }
        assertEquals(0, transport.calls);
        try (AiClient client = AiClient.of(new OpenAiProvider(transport))) {
            assertImagePromptsRejected(invalidRequests, request -> client.execute(request, config));
            client.execute(
                AiRequest.builder()
                    .operation(AiOperation.GENERATE_IMAGE)
                    .model("gpt-image-1")
                    .instructions("Create a line drawing of a lighthouse")
                    .build(),
                config
            );
        }

        assertEquals(1, transport.calls);
        assertTrue(MAPPER.readTree(transport.requestBody).path("prompt").asText()
            .contains("Create a line drawing of a lighthouse"));
    }

    private static void assertImagePromptsRejected(
        AiRequest[] requests,
        RequestExecutor executor
    ) {
        for (AiRequest request : requests) {
            String expectedMessage = request.operation() == AiOperation.GENERATE_IMAGE
                ? "An image prompt is required"
                : "An image edit prompt is required";
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> executor.execute(request)
            );
            assertEquals(expectedMessage, exception.getMessage());
        }
    }

    @FunctionalInterface
    private interface RequestExecutor {
        void execute(AiRequest request) throws AiProviderException;
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
    void serializesDallE2EditOptionsAndRequestsBase64() throws Exception {
        BinaryContent input = new BinaryContent(new byte[] {1, 2, 3}, "image/png", "source.png");
        AiRequest request = AiRequest.builder()
            .operation(AiOperation.EDIT_IMAGE)
            .model("dall-e-2")
            .inputMedia(input)
            .imageOptions(new ImageOptions(2, "512x512", null))
            .build();

        HttpEntity entity = OpenAiProvider.buildImageEditEntity(request, input, "Edit the image");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        entity.writeTo(bytes);
        String multipartBody = bytes.toString(StandardCharsets.UTF_8);

        assertTrue(multipartBody.contains("name=\"n\""));
        assertTrue(multipartBody.contains("\r\n\r\n2\r\n"));
        assertTrue(multipartBody.contains("name=\"size\""));
        assertTrue(multipartBody.contains("512x512"));
        assertTrue(multipartBody.contains("name=\"response_format\""));
        assertTrue(multipartBody.contains("b64_json"));
    }

    @Test
    void forwardsPortableEditOptionsForUncataloguedCompatibleModel() throws Exception {
        BinaryContent input = new BinaryContent(new byte[] {1, 2, 3}, "image/png", "source.png");
        AiRequest request = AiRequest.builder()
            .operation(AiOperation.EDIT_IMAGE)
            .model("compatible-image-edit-model")
            .inputMedia(input)
            .imageOptions(new ImageOptions(4, "800x600", "vendor-quality"))
            .build();

        HttpEntity entity = OpenAiProvider.buildImageEditEntity(request, input, "Edit the image");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        entity.writeTo(bytes);
        String multipartBody = bytes.toString(StandardCharsets.UTF_8);

        assertTrue(multipartBody.contains("name=\"n\""));
        assertTrue(multipartBody.contains("\r\n\r\n4\r\n"));
        assertTrue(multipartBody.contains("name=\"size\""));
        assertTrue(multipartBody.contains("800x600"));
        assertTrue(multipartBody.contains("name=\"quality\""));
        assertTrue(multipartBody.contains("vendor-quality"));
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

    private static Stream<Arguments> invalidEmbeddingResponses() {
        return Stream.of(
            Arguments.of("empty response", 200, "", null),
            Arguments.of("malformed JSON", 200, "{not-json", null),
            Arguments.of("malformed shape preserves status", 206, "{}", null),
            Arguments.of(
                "dimension mismatch",
                200,
                "{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}",
                2
            )
        );
    }

    private static AiProviderConfig embeddingConfig(String apiKey, String trustedValue) {
        return AiProviderConfig.builder(apiKey)
            .baseUri(URI.create("https://example.test/custom/v1/"))
            .trustedHeader("X-Test-Secret", trustedValue)
            .build();
    }

    private static void assertRedacted(AiProviderException exception, String... secrets) {
        String exposed = exception + "\n" + exception.rawResponse() + "\n" + exception.getCause();
        for (String secret : secrets) {
            assertFalse(exposed.contains(secret));
        }
    }

    private static final class RecordingHttpClient extends CloseableHttpClient {
        private final int responseStatus;
        private final String responseBody;
        private final IOException failure;
        private final List<org.apache.http.Header> headers = new ArrayList<>();
        private int calls;
        private String method;
        private String uri;
        private String requestBody;

        private RecordingHttpClient(int responseStatus, String responseBody) {
            this.responseStatus = responseStatus;
            this.responseBody = responseBody;
            failure = null;
        }

        private RecordingHttpClient(IOException failure) {
            responseStatus = -1;
            responseBody = null;
            this.failure = failure;
        }

        @Override
        protected CloseableHttpResponse doExecute(
            HttpHost target,
            HttpRequest request,
            HttpContext context
        ) throws IOException {
            calls++;
            method = request.getRequestLine().getMethod();
            uri = request.getRequestLine().getUri();
            headers.clear();
            headers.addAll(List.of(request.getAllHeaders()));
            if (request instanceof HttpEntityEnclosingRequest enclosingRequest) {
                requestBody = EntityUtils.toString(enclosingRequest.getEntity(), StandardCharsets.UTF_8);
            }
            if (failure != null) {
                throw failure;
            }
            return new StubResponse(responseStatus, responseBody);
        }

        private String header(String name) {
            return headers.stream()
                .filter(header -> header.getName().equalsIgnoreCase(name))
                .map(org.apache.http.Header::getValue)
                .findFirst()
                .orElse(null);
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
        private StubResponse(int statusCode, String body) {
            super(HttpVersion.HTTP_1_1, statusCode, "");
            setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        }

        @Override
        public void close() {
            // Nothing to close in the in-memory response.
        }
    }
}
