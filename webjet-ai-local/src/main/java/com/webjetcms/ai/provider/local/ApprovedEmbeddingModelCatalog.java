package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Loads the production-approved embedding model identities from the core catalogue resource. */
final class ApprovedEmbeddingModelCatalog {
    static final String RESOURCE = "META-INF/webjet-ai/local-embedding-model-catalog-v1.properties";
    static final List<String> ENTRY_ORDER = List.of(
        "webjet-model.json",
        "model.onnx",
        "config.json",
        "tokenizer.json",
        "tokenizer_config.json",
        "special_tokens_map.json",
        "sentencepiece.bpe.model",
        "MODEL_CARD.md",
        "SHA256SUMS"
    );

    private static final List<String> ARTIFACT_NAMES = List.of(
        "config.json",
        "tokenizer.json",
        "tokenizer_config.json",
        "special_tokens_map.json",
        "sentencepiece.bpe.model",
        "MODEL_CARD.md"
    );

    private final EmbeddingModelDefinition model;

    private ApprovedEmbeddingModelCatalog(EmbeddingModelDefinition model) {
        this.model = model;
    }

    static ApprovedEmbeddingModelCatalog of(EmbeddingModelDefinition model) {
        return new ApprovedEmbeddingModelCatalog(model);
    }

    static ApprovedEmbeddingModelCatalog load() {
        Properties properties = new Properties();
        ClassLoader loader = ApprovedEmbeddingModelCatalog.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing approved embedding model catalogue: " + RESOURCE);
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read approved embedding model catalogue", exception);
        }

        Values values = new Values(properties);
        if (values.integer("catalog.version") != 1) {
            throw new IllegalStateException("Unsupported approved embedding model catalogue version");
        }

        Map<String, EmbeddingVariantDefinition> variants = new LinkedHashMap<>();
        for (String name : List.of("fp32", "int8-avx512-vnni")) {
            String prefix = "variant." + name + ".";
            variants.put(name, new EmbeddingVariantDefinition(
                name,
                values.required(prefix + "cpu-target"),
                values.required(prefix + "source-path"),
                values.positiveLong(prefix + "model-size"),
                values.sha256(prefix + "model-sha256")
            ));
        }

        Map<String, EmbeddingArtifactDefinition> artifacts = new LinkedHashMap<>();
        for (String name : ARTIFACT_NAMES) {
            String prefix = "artifact." + name + ".";
            artifacts.put(name, new EmbeddingArtifactDefinition(
                name,
                values.required(prefix + "source-path"),
                values.positiveLong(prefix + "size"),
                values.sha256(prefix + "sha256")
            ));
        }

        Set<String> aliases = new LinkedHashSet<>(List.of(
            values.required("model.aliases").split(",", -1)
        ));
        if (aliases.stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException("Approved embedding model aliases must not be blank");
        }

        return new ApprovedEmbeddingModelCatalog(new EmbeddingModelDefinition(
            values.required("model.canonical-id"),
            Set.copyOf(aliases),
            values.required("model.revision"),
            values.positiveInteger("model.dimensions"),
            values.positiveInteger("model.maximum-length"),
            values.required("model.format"),
            values.required("model.license"),
            values.required("model.task"),
            values.required("model.pooling"),
            values.bool("model.normalize"),
            EmbeddingInputPreparation.fromCatalog(values.required("model.input-preparation")),
            values.required("model.query-prefix"),
            values.required("model.document-prefix"),
            List.of(values.required("model.input-names").split(",", -1)),
            List.of(values.required("model.output-names").split(",", -1)),
            values.required("model.repository"),
            values.required("model.model-card-path"),
            Map.copyOf(variants),
            Map.copyOf(artifacts)
        ));
    }

    EmbeddingModelDefinition model() { return model; }

    record EmbeddingModelDefinition(
        String canonicalId,
        Set<String> aliases,
        String revision,
        int dimensions,
        int maximumLength,
        String format,
        String license,
        String task,
        String pooling,
        boolean normalize,
        EmbeddingInputPreparation inputPreparation,
        String queryPrefix,
        String documentPrefix,
        List<String> inputNames,
        List<String> outputNames,
        String repository,
        String modelCardPath,
        Map<String, EmbeddingVariantDefinition> variants,
        Map<String, EmbeddingArtifactDefinition> artifacts
    ) {
        EmbeddingVariantDefinition variant(String name) {
            EmbeddingVariantDefinition variant = variants.get(name);
            if (variant == null) {
                throw new IllegalArgumentException("Unsupported local embedding model variant: " + name);
            }
            return variant;
        }

        EmbeddingArtifactDefinition artifact(String name, EmbeddingVariantDefinition variant) {
            if ("model.onnx".equals(name)) {
                return new EmbeddingArtifactDefinition(name, variant.sourcePath(), variant.modelSize(), variant.modelSha256());
            }
            EmbeddingArtifactDefinition artifact = artifacts.get(name);
            if (artifact == null) {
                throw new IllegalArgumentException("Unsupported local embedding model artifact: " + name);
            }
            return artifact;
        }

        long maximumExtractedBytes(EmbeddingVariantDefinition variant) {
            long total = variant.modelSize();
            for (EmbeddingArtifactDefinition artifact : artifacts.values()) {
                total = Math.addExact(total, artifact.size());
            }
            return Math.addExact(total, 128L * 1024L);
        }
    }

    record EmbeddingVariantDefinition(
        String name,
        String cpuTarget,
        String sourcePath,
        long modelSize,
        String modelSha256
    ) { }

    record EmbeddingArtifactDefinition(String name, String sourcePath, long size, String sha256) { }

    private record Values(Properties properties) {
        String required(String key) {
            String value = properties.getProperty(key);
            if (value == null || value.isEmpty()) {
                throw new IllegalStateException("Missing approved embedding model catalogue value: " + key);
            }
            return value;
        }

        int integer(String key) {
            try {
                return Integer.parseInt(required(key));
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Invalid approved catalogue integer: " + key, exception);
            }
        }

        int positiveInteger(String key) {
            int value = integer(key);
            if (value < 1) throw new IllegalStateException("Approved catalogue value must be positive: " + key);
            return value;
        }

        long positiveLong(String key) {
            try {
                long value = Long.parseLong(required(key));
                if (value < 1) throw new NumberFormatException("not positive");
                return value;
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Invalid approved catalogue long: " + key, exception);
            }
        }

        boolean bool(String key) {
            String value = required(key);
            if ("true".equals(value)) return true;
            if ("false".equals(value)) return false;
            throw new IllegalStateException("Invalid approved catalogue boolean: " + key);
        }

        String sha256(String key) {
            String value = required(key);
            if (value.matches("[0-9a-f]{64}") == false) {
                throw new IllegalStateException("Invalid approved catalogue SHA-256: " + key);
            }
            return value;
        }
    }
}
