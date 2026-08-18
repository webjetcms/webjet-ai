package com.webjetcms.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
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
    void embeddingValueTypesValidateDefaultsAndRemainImmutable() {
        assertNull(new EmbeddingOptions().dimensions());
        assertEquals(Integer.valueOf(768), new EmbeddingOptions(768).dimensions());
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingOptions(0));

        String sensitiveInput = "secret-input-must-not-be-logged";
        List<String> sourceInputs = new ArrayList<>(List.of(sensitiveInput, "second"));
        EmbeddingOptions requestOptions = new EmbeddingOptions(768);
        EmbeddingRequest request = EmbeddingRequest.builder()
            .model("embedding-model")
            .inputs(sourceInputs)
            .options(requestOptions)
            .build();
        sourceInputs.set(0, "changed");

        assertEquals("embedding-model", request.model());
        assertEquals(List.of(sensitiveInput, "second"), request.inputs());
        assertSame(requestOptions, request.options());
        assertThrows(UnsupportedOperationException.class, () -> request.inputs().add("third"));
        assertThrows(
            NullPointerException.class,
            () -> new EmbeddingRequest("model", Arrays.asList("input", null))
        );
        assertEquals(List.of(), new EmbeddingRequest(null, null).inputs());
        assertFalse(request.toString().contains(sensitiveInput));

        float[] sourceValues = {1.0f, 2.0f};
        EmbeddingVector vector = new EmbeddingVector(sourceValues);
        sourceValues[0] = 9.0f;
        float[] returnedValues = vector.values();
        returnedValues[1] = 9.0f;

        assertArrayEquals(new float[] {1.0f, 2.0f}, vector.values());
        assertEquals(2, vector.dimensions());
        assertEquals(new EmbeddingVector(new float[] {1.0f, 2.0f}), vector);

        List<EmbeddingVector> sourceEmbeddings = new ArrayList<>(List.of(vector));
        EmbeddingResponse response = new EmbeddingResponse(sourceEmbeddings, null);
        sourceEmbeddings.clear();

        assertEquals(List.of(vector), response.embeddings());
        assertSame(TokenUsage.EMPTY, response.usage());
        assertThrows(UnsupportedOperationException.class, () -> response.embeddings().clear());
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
        assertEquals(
            List.of("text", "media", "usage", "finishReason"),
            Arrays.stream(AiResponse.class.getRecordComponents()).map(component -> component.getName()).toList()
        );
    }
}
