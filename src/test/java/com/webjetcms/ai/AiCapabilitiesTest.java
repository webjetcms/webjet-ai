package com.webjetcms.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static com.webjetcms.ai.AiCapabilities.Continuation.NONE;
import static com.webjetcms.ai.AiCapabilities.Continuation.PROVIDER_STORAGE_REQUIRED;
import static com.webjetcms.ai.AiCapabilities.Support.SUPPORTED;
import static com.webjetcms.ai.AiCapabilities.Support.UNKNOWN;
import static com.webjetcms.ai.AiCapabilities.Support.UNSUPPORTED;
import static com.webjetcms.ai.AiOperation.CHAT;
import static com.webjetcms.ai.AiOperation.EDIT_IMAGE;
import static com.webjetcms.ai.AiOperation.GENERATE_IMAGE;
import static com.webjetcms.ai.AiOperation.TEXT;

import java.util.EnumMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.webjetcms.ai.AiCapabilities.Operation;

class AiCapabilitiesTest {

    @Test
    void defaultsRemainUnknownAndCapabilityValuesAreImmutable() {
        AiCapabilities unknown = AiCapabilities.unknown();
        assertEquals(Operation.unknown(), unknown.operation(CHAT));
        assertEquals(UNKNOWN, unknown.embeddingSupport());
        assertEquals(UNKNOWN, unknown.embeddingModelSupport());
        assertEquals(UNKNOWN, Operation.unsupported().modelSupport());

        EnumMap<AiOperation, Operation> source = new EnumMap<>(AiOperation.class);
        Operation text = new Operation(SUPPORTED, UNKNOWN, SUPPORTED, NONE);
        source.put(TEXT, text);
        AiCapabilities capabilities = new AiCapabilities(source, SUPPORTED, UNKNOWN);
        source.put(TEXT, Operation.unsupported());
        assertEquals(text, capabilities.operation(TEXT));
        assertEquals(Operation.unknown(), capabilities.operation(CHAT));
        assertEquals(List.of(AiOperation.values()), List.copyOf(capabilities.operations().keySet()));
        assertThrows(UnsupportedOperationException.class,
            () -> capabilities.operations().put(CHAT, Operation.unsupported()));

        assertEquals(UNKNOWN, text.support());
        assertEquals(SUPPORTED, new Operation(SUPPORTED, SUPPORTED, SUPPORTED, NONE).support());
        assertEquals(UNSUPPORTED, new Operation(SUPPORTED, UNSUPPORTED, SUPPORTED, NONE).support());
        assertEquals(UNSUPPORTED, new Operation(UNSUPPORTED, UNKNOWN, UNSUPPORTED, NONE).support());
    }

    @Test
    void remoteAdaptersDescribeOperationsWithoutClaimingUnknownModelsAreSupported() throws Exception {
        try (AiClient client = AiClient.discover()) {
            for (String provider : List.of("openai", "gemini", "openrouter")) {
                AiCapabilities capabilities = client.capabilities(provider, "unlisted-model");
                Operation text = capabilities.operation(TEXT);
                assertEquals(new Operation(SUPPORTED, UNKNOWN, SUPPORTED, NONE), text, provider);
                assertEquals(UNKNOWN, text.support(), provider);
                assertEquals(SUPPORTED, capabilities.embeddingSupport(), provider);
                assertEquals(UNKNOWN, capabilities.embeddingModelSupport(), provider);

                Operation chat = capabilities.operation(CHAT);
                if ("openrouter".equals(provider)) {
                    assertEquals(UNSUPPORTED, chat.adapterSupport());
                    assertEquals(UNSUPPORTED, chat.support());
                    assertEquals(UNSUPPORTED, chat.adapterStreaming());
                    assertEquals(NONE, chat.continuation());
                } else {
                    assertEquals(new Operation(SUPPORTED, UNKNOWN, SUPPORTED, PROVIDER_STORAGE_REQUIRED),
                        chat, provider);
                }
                for (AiOperation operation : List.of(GENERATE_IMAGE, EDIT_IMAGE)) {
                    Operation image = capabilities.operation(operation);
                    assertEquals(SUPPORTED, image.adapterSupport(), provider);
                    assertEquals(UNKNOWN, image.modelSupport(), provider);
                    assertEquals("gemini".equals(provider) ? SUPPORTED : UNSUPPORTED,
                        image.adapterStreaming(), provider);
                    assertEquals(NONE, image.continuation(), provider);
                }
            }
        }
    }

    @Test
    void knownImageMetadataPreservesOperationRestrictionsAndGeminiNormalization() throws Exception {
        try (AiClient client = AiClient.discover()) {
            AiCapabilities openAi = client.capabilities("openai", "gpt-image-2");
            assertEquals(SUPPORTED, openAi.operation(GENERATE_IMAGE).support());
            assertEquals(SUPPORTED, openAi.operation(EDIT_IMAGE).support());
            AiCapabilities dallE = client.capabilities("openai", "dall-e-3");
            assertEquals(SUPPORTED, dallE.operation(GENERATE_IMAGE).support());
            assertEquals(UNSUPPORTED, dallE.operation(EDIT_IMAGE).support());

            AiCapabilities gemini = client.capabilities("gemini", "models/gemini-3.1-flash-image");
            assertEquals(client.capabilities("gemini", "gemini-3.1-flash-image"), gemini);
            assertEquals(SUPPORTED, gemini.operation(GENERATE_IMAGE).support());
            assertEquals(SUPPORTED, gemini.operation(EDIT_IMAGE).support());

            AiCapabilities router = client.capabilities("openrouter", "openai/gpt-image-2");
            assertEquals(SUPPORTED, router.operation(GENERATE_IMAGE).support());
            assertEquals(UNKNOWN, router.operation(EDIT_IMAGE).modelSupport());
            assertEquals(UNKNOWN, router.operation(EDIT_IMAGE).support());
            AiCapabilities vector = client.capabilities("openrouter", "recraft/recraft-v4-vector");
            assertEquals(UNSUPPORTED, vector.operation(GENERATE_IMAGE).support());
            assertEquals(UNSUPPORTED, vector.operation(EDIT_IMAGE).support());
        }
    }
}
