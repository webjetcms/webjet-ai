package com.webjetcms.ai.local.tool;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class MultilingualE5BaseRecipe implements LocalModelRecipe {

    private static final EmbeddingModelCatalog CATALOG = EmbeddingModelCatalog.INSTANCE;

    static final String MODEL_ID = CATALOG.canonicalId();
    static final String REVISION = CATALOG.revision();
    static final int DIMENSIONS = CATALOG.dimensions();
    static final int MAXIMUM_LENGTH = CATALOG.maximumLength();

    private static final URI REPOSITORY = URI.create("https://huggingface.co/" + MODEL_ID + "/resolve/");

    @Override
    public String canonicalId() { return MODEL_ID; }

    @Override
    public Set<String> acceptedIds() { return CATALOG.aliases(); }

    @Override
    public String revision() { return REVISION; }

    @Override
    public Integer dimensions() { return DIMENSIONS; }

    @Override
    public int maximumLength() { return MAXIMUM_LENGTH; }

    @Override
    public Set<ModelVariant> supportedVariants() {
        return Set.of(ModelVariant.FP32, ModelVariant.INT8_AVX512_VNNI);
    }

    @Override
    public ModelVariant defaultVariant() { return ModelVariant.FP32; }

    @Override
    public String defaultOutputName(ModelVariant variant) {
        requireSupported(variant);
        return CATALOG.variantValue(variant, "default-output");
    }

    @Override
    public String manifest(ModelVariant variant) {
        requireSupported(variant);
        return """
            {
              "schemaVersion": 1,
              "model": {
                "id": "%s",
                "revision": "%s",
                "variant": "%s",
                "format": "onnx",
                "file": "model.onnx",
                "license": "MIT"
              },
              "task": "text-embedding",
              "embedding": {
                "dimensions": %d,
                "pooling": "mean",
                "normalize": true
              },
              "tokenizer": {
                "maximumLength": %d
              },
              "prefixes": {
                "query": "query: ",
                "document": "passage: "
              },
              "onnx": {
                "inputs": ["input_ids", "attention_mask"],
                "outputs": ["last_hidden_state"]
              },
              "runtime": {
                "cpuTarget": "%s"
              },
              "source": {
                "repository": "%s",
                "revision": "%s",
                "modelPath": "%s",
                "modelCardPath": "README.md"
              }
            }
            """.formatted(
                canonicalId(),
                revision(),
                variant.cliName(),
                dimensions(),
                maximumLength(),
                CATALOG.variantValue(variant, "cpu-target"),
                canonicalId(),
                revision(),
                CATALOG.variantValue(variant, "source-path")
            );
    }

    @Override
    public List<ModelArtifact> artifacts(ModelVariant variant) {
        requireSupported(variant);
        List<ModelArtifact> artifacts = new ArrayList<>();
        artifacts.add(modelArtifact(variant));
        for (String bundleName : CATALOG.artifactNames()) {
            artifacts.add(artifact(
                CATALOG.artifactValue(bundleName, "source-path"),
                bundleName,
                CATALOG.artifactLong(bundleName, "size"),
                CATALOG.artifactValue(bundleName, "sha256")
            ));
        }
        return List.copyOf(artifacts);
    }

    private ModelArtifact modelArtifact(ModelVariant variant) {
        return artifact(
            CATALOG.variantValue(variant, "source-path"),
            "model.onnx",
            CATALOG.variantLong(variant, "model-size"),
            CATALOG.variantValue(variant, "model-sha256")
        );
    }

    private void requireSupported(ModelVariant variant) {
        if (supportedVariants().contains(variant) == false) {
            throw new IllegalArgumentException(
                "Unsupported variant for " + canonicalId() + ": " + variant.cliName()
            );
        }
    }

    private ModelArtifact artifact(String sourcePath, String bundlePath, long size, String sha256) {
        return new ModelArtifact(
            sourcePath,
            bundlePath,
            REPOSITORY.resolve(REVISION + "/" + sourcePath),
            size,
            sha256
        );
    }
}
