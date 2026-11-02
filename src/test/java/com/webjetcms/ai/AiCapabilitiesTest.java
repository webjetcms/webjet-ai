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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.webjetcms.ai.AiCapabilities.Operation;
import com.webjetcms.ai.AiCapabilities.Support;

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
                assertEquals("openrouter".equals(provider) ? new Operation(UNSUPPORTED, UNKNOWN, UNSUPPORTED, NONE)
                    : new Operation(SUPPORTED, UNKNOWN, SUPPORTED, PROVIDER_STORAGE_REQUIRED), chat, provider);
                assertEquals("openrouter".equals(provider) ? UNSUPPORTED : UNKNOWN, chat.support(), provider);
                for (AiOperation operation : List.of(GENERATE_IMAGE, EDIT_IMAGE)) {
                    assertEquals(new Operation(SUPPORTED, UNKNOWN,
                        "gemini".equals(provider) ? SUPPORTED : UNSUPPORTED, NONE), capabilities.operation(operation), provider);
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "openai, gpt-image-2, SUPPORTED, SUPPORTED",
        "openai, dall-e-3, SUPPORTED, UNSUPPORTED",
        "gemini, models/gemini-3.1-flash-image, SUPPORTED, SUPPORTED",
        "openrouter, openai/gpt-image-2, SUPPORTED, UNKNOWN",
        "openrouter, recraft/recraft-v4-vector, UNSUPPORTED, UNSUPPORTED"
    })
    void knownImageMetadataPreservesOperationRestrictionsAndGeminiNormalization(
        String provider, String model, Support generation, Support editing
    ) throws Exception {
        try (AiClient client = AiClient.discover()) {
            AiCapabilities capabilities = client.capabilities(provider, model);
            assertEquals(generation, capabilities.operation(GENERATE_IMAGE).support());
            assertEquals(editing, capabilities.operation(EDIT_IMAGE).support());
            assertEquals(editing, capabilities.operation(EDIT_IMAGE).modelSupport());
            if ("gemini".equals(provider)) {
                assertEquals(client.capabilities(provider, "gemini-3.1-flash-image"), capabilities);
            }
        }
    }
}
