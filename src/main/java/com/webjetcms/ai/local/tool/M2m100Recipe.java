package com.webjetcms.ai.local.tool;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Prepares approved M2M100 encoder-decoder bundles for offline text translation. */
final class M2m100Recipe implements LocalModelRecipe {
    private static final TranslationModelCatalog CATALOG = TranslationModelCatalog.INSTANCE;
    private static final Set<ModelVariant> VARIANTS = Set.of(ModelVariant.FP32, ModelVariant.INT8);
    private static final URI REPOSITORY = URI.create(
        "https://huggingface.co/" + CATALOG.required("model.repository") + "/resolve/"
    );

    @Override
    public String canonicalId() { return CATALOG.required("model.canonical-id"); }

    @Override
    public Set<String> acceptedIds() { return CATALOG.aliases(); }

    @Override
    public String revision() { return CATALOG.required("model.revision"); }

    @Override
    public Integer dimensions() { return null; }

    @Override
    public int maximumLength() { return CATALOG.integer("model.maximum-length"); }

    @Override
    public Set<ModelVariant> supportedVariants() { return VARIANTS; }

    @Override
    public ModelVariant defaultVariant() { return ModelVariant.INT8; }

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
                "format": "onnx-seq2seq",
                "license": "%s"
              },
              "task": "text-translation",
              "tokenizer": {
                "engine": "sentencepiece",
                "modelFile": "%s",
                "vocabularyFile": "%s",
                "specialTokensFile": "%s",
                "maximumLength": %d,
                "languageTokenPattern": "__%%s__"
              },
              "generation": {
                "maximumLength": %d,
                "decoderStartTokenId": %d,
                "eosTokenId": %d,
                "padTokenId": %d,
                "vocabularySize": %d,
                "strategy": "greedy"
              },
              "onnx": {
                "encoderFile": "%s",
                "decoderFile": "%s",
                "decoderLayers": %d,
                "attentionHeads": %d,
                "attentionHeadSize": %d
              },
              "runtime": {
                "cpuTarget": "%s"
              },
              "source": {
                "repository": "%s",
                "revision": "%s",
                "encoderPath": "%s",
                "decoderPath": "%s",
                "modelCardPath": "%s"
              }
            }
            """.formatted(
                canonicalId(),
                revision(),
                variant.cliName(),
                CATALOG.required("model.license"),
                CATALOG.required("model.tokenizer-model-file"),
                CATALOG.required("model.vocabulary-file"),
                CATALOG.required("model.special-tokens-file"),
                maximumLength(),
                CATALOG.integer("model.maximum-output-length"),
                CATALOG.integer("model.decoder-start-token-id"),
                CATALOG.integer("model.eos-token-id"),
                CATALOG.integer("model.pad-token-id"),
                CATALOG.integer("model.vocabulary-size"),
                CATALOG.required("model.encoder-file"),
                CATALOG.required("model.decoder-file"),
                CATALOG.integer("model.decoder-layers"),
                CATALOG.integer("model.attention-heads"),
                CATALOG.integer("model.attention-head-size"),
                CATALOG.variantValue(variant, "cpu-target"),
                CATALOG.required("model.repository"),
                revision(),
                CATALOG.variantValue(variant, "encoder-source-path"),
                CATALOG.variantValue(variant, "decoder-source-path"),
                CATALOG.required("model.model-card-path")
            );
    }

    @Override
    public List<ModelArtifact> artifacts(ModelVariant variant) {
        requireSupported(variant);
        List<ModelArtifact> artifacts = new ArrayList<>();
        artifacts.add(variantArtifact(variant, "encoder", CATALOG.required("model.encoder-file")));
        artifacts.add(variantArtifact(variant, "decoder", CATALOG.required("model.decoder-file")));
        for (String name : TranslationModelCatalog.ARTIFACT_NAMES) {
            artifacts.add(artifact(
                CATALOG.artifactValue(name, "source-path"),
                name,
                CATALOG.artifactLong(name, "size"),
                CATALOG.artifactValue(name, "sha256")
            ));
        }
        return List.copyOf(artifacts);
    }

    private ModelArtifact variantArtifact(ModelVariant variant, String role, String bundlePath) {
        return artifact(
            CATALOG.variantValue(variant, role + "-source-path"),
            bundlePath,
            CATALOG.longValue("variant." + variant.cliName() + "." + role + "-size"),
            CATALOG.variantValue(variant, role + "-sha256")
        );
    }

    private ModelArtifact artifact(String sourcePath, String bundlePath, long size, String sha256) {
        return new ModelArtifact(
            sourcePath,
            bundlePath,
            REPOSITORY.resolve(revision() + "/" + sourcePath),
            size,
            sha256
        );
    }

    private void requireSupported(ModelVariant variant) {
        if (VARIANTS.contains(variant) == false) {
            throw new IllegalArgumentException(
                "Unsupported variant for " + canonicalId() + ": " + variant.cliName()
            );
        }
    }
}
