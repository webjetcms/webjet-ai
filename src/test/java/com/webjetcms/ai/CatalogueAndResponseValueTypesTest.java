package com.webjetcms.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CatalogueAndResponseValueTypesTest {

    @Test
    void modelDisplayLabelFallsBackToIdentifier() {
        assertEquals("Readable name", new ModelInfo("model-id", "Readable name").displayLabel());
        assertEquals("model-id", new ModelInfo("model-id", " ").displayLabel());
        assertEquals("model-id", new ModelInfo("model-id", null).displayLabel());
    }

    @Test
    void responseTextCanBeReplacedWithoutLosingMetadata() {
        GeneratedMedia media = new GeneratedMedia(new byte[] {1}, "image/png");
        TokenUsage usage = new TokenUsage(2, 3, 5, Map.of("cached", 1L));
        AiResponse original = new AiResponse("encoded", List.of(media), usage, "stop");

        AiResponse restored = original.withText("restored");

        assertEquals("restored", restored.text());
        assertEquals(List.of(media), restored.media());
        assertSame(usage, restored.usage());
        assertEquals("stop", restored.finishReason());
    }
}
