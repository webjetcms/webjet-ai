package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Loads production-approved embedding model identities from the core catalogue resource. */
final class ApprovedEmbeddingModelCatalog {
    static final String RESOURCE = "META-INF/webjet-ai/local-embedding-model-catalog-v1.properties";

    private final Map<String, EmbeddingModelDefinition> modelsByAlias;

    private ApprovedEmbeddingModelCatalog(List<EmbeddingModelDefinition> models) {
        Map<String, EmbeddingModelDefinition> aliases = new LinkedHashMap<>();
        for (EmbeddingModelDefinition model : models) {
            for (String alias : model.aliases()) {
                EmbeddingModelDefinition previous = aliases.putIfAbsent(alias, model);
                if (previous != null) {
                    throw new IllegalStateException("Embedding model alias is not unique: " + alias);
                }
            }
        }
        modelsByAlias = Map.copyOf(aliases);
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

        List<EmbeddingModelDefinition> models = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(values.list("catalog.models"));
        if (keys.size() != values.list("catalog.models").size()) {
            throw new IllegalStateException("Approved embedding model keys must be unique");
        }
        for (String key : keys) models.add(loadModel(values, key));
        return new ApprovedEmbeddingModelCatalog(List.copyOf(models));
    }

    EmbeddingModelDefinition model(String id) {
        EmbeddingModelDefinition model = modelsByAlias.get(id);
        if (model == null) throw new IllegalArgumentException("Unsupported local embedding model: " + id);
        return model;
    }

    private static EmbeddingModelDefinition loadModel(Values values, String key) {
        String prefix = "model." + key + ".";

        Map<String, EmbeddingVariantDefinition> variants = new LinkedHashMap<>();
        for (String name : values.list(prefix + "variants")) {
            String variantPrefix = prefix + "variant." + name + ".";
            variants.put(name, new EmbeddingVariantDefinition(
                name,
                values.required(variantPrefix + "cpu-target"),
                values.required(variantPrefix + "source-path"),
                values.positiveLong(variantPrefix + "model-size"),
                values.sha256(variantPrefix + "model-sha256")
            ));
        }

        List<String> artifactNames = values.list(prefix + "artifacts");
        Map<String, EmbeddingArtifactDefinition> artifacts = new LinkedHashMap<>();
        for (String name : artifactNames) {
            String artifactPrefix = prefix + "artifact." + name + ".";
            artifacts.put(name, new EmbeddingArtifactDefinition(
                name,
                values.required(artifactPrefix + "source-path"),
                values.positiveLong(artifactPrefix + "size"),
                values.sha256(artifactPrefix + "sha256")
            ));
        }

        Set<String> aliases = new LinkedHashSet<>(values.list(prefix + "aliases"));
        if (aliases.size() != values.list(prefix + "aliases").size()) {
            throw new IllegalStateException("Approved embedding model aliases must be unique");
        }

        String defaultVariant = values.required(prefix + "default-variant");
        if (variants.containsKey(defaultVariant) == false) {
            throw new IllegalStateException("Approved embedding model default variant is unsupported: " + key);
        }

        return new EmbeddingModelDefinition(
            values.required(prefix + "display-name"),
            values.required(prefix + "canonical-id"),
            Set.copyOf(aliases),
            values.required(prefix + "revision"),
            values.positiveInteger(prefix + "dimensions"),
            values.positiveInteger(prefix + "maximum-length"),
            values.required(prefix + "format"),
            values.required(prefix + "license"),
            values.required(prefix + "task"),
            values.required(prefix + "pooling"),
            values.bool(prefix + "normalize"),
            EmbeddingInputPreparation.fromCatalog(values.required(prefix + "input-preparation")),
            values.required(prefix + "query-prefix"),
            values.required(prefix + "document-prefix"),
            values.list(prefix + "input-names"),
            values.list(prefix + "output-names"),
            values.required(prefix + "repository"),
            values.required(prefix + "model-card-path"),
            defaultVariant,
            Map.copyOf(variants),
            artifactNames,
            Map.copyOf(artifacts)
        );
    }

    record EmbeddingModelDefinition(
        String displayName,
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
        String defaultVariant,
        Map<String, EmbeddingVariantDefinition> variants,
        List<String> artifactNames,
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

        List<String> entryOrder() {
            List<String> entries = new ArrayList<>(artifactNames.size() + 3);
            entries.add("webjet-model.json");
            entries.add("model.onnx");
            entries.addAll(artifactNames);
            entries.add("SHA256SUMS");
            return List.copyOf(entries);
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

        List<String> list(String key) {
            List<String> values = List.of(required(key).split(",", -1));
            if (values.stream().anyMatch(String::isBlank)) {
                throw new IllegalStateException("Blank approved embedding model catalogue value: " + key);
            }
            return values;
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
