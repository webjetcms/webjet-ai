package com.webjetcms.ai.provider.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.EmbeddingRequest;
import com.webjetcms.ai.EmbeddingResponse;
import com.webjetcms.ai.EmbeddingVector;

@Tag("local-model-smoke")
class LocalEmbeddingModelSmokeTest {

    @Test
    void suppliedProductionBundleRunsEmbeddingInference() throws Exception {
        String configuredBundle = System.getProperty("localModelBundle");
        assertNotNull(configuredBundle, "localModelBundle system property must be supplied");
        Path bundle = Path.of(configuredBundle);
        assertTrue(Files.isRegularFile(bundle), "localModelBundle must identify a regular file");

        try (LocalEmbeddingModelProvider provider = LocalEmbeddingModelProvider.open(bundle)) {
            String model = provider.listModels(AiProviderConfig.empty()).get(0).id();
            EmbeddingResponse response = provider.embed(new EmbeddingRequest(
                model, List.of("What is the capital of Slovakia?")
            ));
            assertEquals(1, response.embeddings().size());
            EmbeddingVector embedding = response.embeddings().get(0);
            assertTrue(embedding.dimensions() > 0);
            for (float value : embedding.values()) assertTrue(Float.isFinite(value));
            assertTrue(response.usage().inputTokens() > 0);
        }
    }
}
