package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Loads approved local sequence-to-sequence model metadata from the core catalogue. */
final class ApprovedTranslationModelCatalog {
    static final String RESOURCE = "META-INF/webjet-ai/local-translation-model-catalog-v1.properties";
    static final List<String> ARTIFACT_NAMES = List.of(
        "config.json",
        "generation_config.json",
        "tokenizer.json",
        "tokenizer_config.json",
        "special_tokens_map.json",
        "vocab.json",
        "sentencepiece.bpe.model",
        "MODEL_CARD.md"
    );
    static final List<String> ENTRY_ORDER = List.of(
        "webjet-model.json",
        "encoder_model.onnx",
        "decoder_model_merged.onnx",
        "config.json",
        "generation_config.json",
        "tokenizer.json",
        "tokenizer_config.json",
        "special_tokens_map.json",
        "vocab.json",
        "sentencepiece.bpe.model",
        "MODEL_CARD.md",
        "SHA256SUMS"
    );

    private final TranslationModelDefinition model;

    private ApprovedTranslationModelCatalog(TranslationModelDefinition model) {
        this.model = model;
    }

    static ApprovedTranslationModelCatalog load() {
        Properties properties = new Properties();
        ClassLoader loader = ApprovedTranslationModelCatalog.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing approved translation model catalogue: " + RESOURCE);
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read approved translation model catalogue", exception);
        }
        Values values = new Values(properties);
        if (values.integer("catalog.version") != 1) {
            throw new IllegalStateException("Unsupported approved translation model catalogue version");
        }

        Map<String, TranslationVariantDefinition> variants = new LinkedHashMap<>();
        for (String name : List.of("fp32", "int8")) {
            String prefix = "variant." + name + ".";
            variants.put(name, new TranslationVariantDefinition(
                name,
                values.required(prefix + "cpu-target"),
                values.required(prefix + "encoder-source-path"),
                values.positiveLong(prefix + "encoder-size"),
                values.sha256(prefix + "encoder-sha256"),
                values.required(prefix + "decoder-source-path"),
                values.positiveLong(prefix + "decoder-size"),
                values.sha256(prefix + "decoder-sha256")
            ));
        }

        Map<String, TranslationArtifactDefinition> artifacts = new LinkedHashMap<>();
        for (String name : ARTIFACT_NAMES) {
            String prefix = "artifact." + name + ".";
            artifacts.put(name, new TranslationArtifactDefinition(
                name,
                values.required(prefix + "source-path"),
                values.positiveLong(prefix + "size"),
                values.sha256(prefix + "sha256")
            ));
        }
        Set<String> aliases = new LinkedHashSet<>(List.of(values.required("model.aliases").split(",", -1)));
        if (aliases.stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException("Approved translation model aliases must not be blank");
        }
        return new ApprovedTranslationModelCatalog(new TranslationModelDefinition(
            values.required("model.canonical-id"),
            Set.copyOf(aliases),
            values.required("model.repository"),
            values.required("model.revision"),
            values.required("model.license"),
            values.required("model.task"),
            values.positiveInteger("model.maximum-length"),
            values.positiveInteger("model.maximum-output-length"),
            values.nonNegativeInteger("model.decoder-start-token-id"),
            values.nonNegativeInteger("model.eos-token-id"),
            values.nonNegativeInteger("model.pad-token-id"),
            values.positiveInteger("model.vocabulary-size"),
            values.positiveInteger("model.decoder-layers"),
            values.positiveInteger("model.attention-heads"),
            values.positiveInteger("model.attention-head-size"),
            values.required("model.encoder-file"),
            values.required("model.decoder-file"),
            values.required("model.tokenizer-model-file"),
            values.required("model.vocabulary-file"),
            values.required("model.special-tokens-file"),
            values.required("model.model-card-path"),
            Map.copyOf(variants),
            Map.copyOf(artifacts)
        ));
    }

    TranslationModelDefinition model() { return model; }

    record TranslationModelDefinition(
        String canonicalId,
        Set<String> aliases,
        String repository,
        String revision,
        String license,
        String task,
        int maximumLength,
        int maximumOutputLength,
        int decoderStartTokenId,
        int eosTokenId,
        int padTokenId,
        int vocabularySize,
        int decoderLayers,
        int attentionHeads,
        int attentionHeadSize,
        String encoderFile,
        String decoderFile,
        String tokenizerModelFile,
        String vocabularyFile,
        String specialTokensFile,
        String modelCardPath,
        Map<String, TranslationVariantDefinition> variants,
        Map<String, TranslationArtifactDefinition> artifacts
    ) {
        TranslationVariantDefinition variant(String name) {
            TranslationVariantDefinition variant = variants.get(name);
            if (variant == null) throw new IllegalArgumentException("Unsupported local translation model variant: " + name);
            return variant;
        }

        TranslationArtifactDefinition artifact(String name, TranslationVariantDefinition variant) {
            if (encoderFile.equals(name)) {
                return new TranslationArtifactDefinition(name, variant.encoderSourcePath(), variant.encoderSize(), variant.encoderSha256());
            }
            if (decoderFile.equals(name)) {
                return new TranslationArtifactDefinition(name, variant.decoderSourcePath(), variant.decoderSize(), variant.decoderSha256());
            }
            TranslationArtifactDefinition artifact = artifacts.get(name);
            if (artifact == null) throw new IllegalArgumentException("Unsupported local translation model artifact: " + name);
            return artifact;
        }

        long maximumExtractedBytes(TranslationVariantDefinition variant) {
            long total = Math.addExact(variant.encoderSize(), variant.decoderSize());
            for (TranslationArtifactDefinition artifact : artifacts.values()) {
                total = Math.addExact(total, artifact.size());
            }
            return Math.addExact(total, 128L * 1024L);
        }
    }

    record TranslationVariantDefinition(
        String name,
        String cpuTarget,
        String encoderSourcePath,
        long encoderSize,
        String encoderSha256,
        String decoderSourcePath,
        long decoderSize,
        String decoderSha256
    ) { }

    record TranslationArtifactDefinition(String name, String sourcePath, long size, String sha256) { }

    private record Values(Properties properties) {
        String required(String key) {
            String value = properties.getProperty(key);
            if (value == null || value.isEmpty()) {
                throw new IllegalStateException("Missing approved translation catalogue value: " + key);
            }
            return value;
        }

        int integer(String key) {
            try {
                return Integer.parseInt(required(key));
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Invalid approved translation catalogue integer: " + key, exception);
            }
        }

        int positiveInteger(String key) {
            int value = integer(key);
            if (value < 1) throw new IllegalStateException("Approved translation catalogue value must be positive: " + key);
            return value;
        }

        int nonNegativeInteger(String key) {
            int value = integer(key);
            if (value < 0) throw new IllegalStateException("Approved translation catalogue value must not be negative: " + key);
            return value;
        }

        long positiveLong(String key) {
            try {
                long value = Long.parseLong(required(key));
                if (value < 1) throw new NumberFormatException("not positive");
                return value;
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Invalid approved translation catalogue long: " + key, exception);
            }
        }

        String sha256(String key) {
            String value = required(key);
            if (value.matches("[0-9a-f]{64}") == false) {
                throw new IllegalStateException("Invalid approved translation catalogue SHA-256: " + key);
            }
            return value;
        }
    }
}
