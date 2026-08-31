package com.webjetcms.ai.provider.local;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Loads production-approved embedding model identities from the core catalogue resource. */
final class ApprovedEmbeddingModelCatalog {
    static final String RESOURCE = "META-INF/webjet-ai/local-embedding-model-catalog-v1.properties";

    private final Map<String, EmbeddingModelDefinition> modelsByAlias;

    private ApprovedEmbeddingModelCatalog(List<EmbeddingModelDefinition> models) {
        modelsByAlias = models.stream()
            .flatMap(model -> model.aliases().stream().map(alias -> Map.entry(alias, model)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
    static ApprovedEmbeddingModelCatalog load() {
        CatalogValues values = CatalogValues.load(ApprovedEmbeddingModelCatalog.class, RESOURCE, "embedding model");
        if (values.integer("catalog.version") != 1)
            throw new IllegalStateException("Unsupported approved embedding model catalogue version");
        return new ApprovedEmbeddingModelCatalog(values.uniqueList("catalog.models").stream()
            .map(key -> loadModel(values, key)).toList());
    }
    EmbeddingModelDefinition model(String id) {
        EmbeddingModelDefinition model = modelsByAlias.get(id);
        if (model == null) throw new IllegalArgumentException("Unsupported local embedding model: " + id);
        return model;
    }
    private static EmbeddingModelDefinition loadModel(CatalogValues values, String key) {
        String prefix = "model." + key + ".";
        List<EmbeddingVariantDefinition> variants = values.uniqueList(prefix + "variants").stream().map(name -> {
            String variantPrefix = prefix + "variant." + name + ".";
            return new EmbeddingVariantDefinition(name, values.required(variantPrefix + "cpu-target"),
                values.required(variantPrefix + "source-path"), values.positiveLong(variantPrefix + "model-size"),
                values.sha256(variantPrefix + "model-sha256"));
        }).toList();

        List<EmbeddingArtifactDefinition> artifacts = values.uniqueList(prefix + "artifacts").stream().map(name -> {
            String artifactPrefix = prefix + "artifact." + name + ".";
            return new EmbeddingArtifactDefinition(name, values.required(artifactPrefix + "source-path"),
                values.positiveLong(artifactPrefix + "size"), values.sha256(artifactPrefix + "sha256"));
        }).toList();

        String defaultVariant = values.required(prefix + "default-variant");
        if (variants.stream().noneMatch(variant -> variant.name().equals(defaultVariant))) {
            throw new IllegalStateException("Approved embedding model default variant is unsupported: " + key);
        }
        String preparation = values.required(prefix + "input-preparation");
        if ("e5-prefix".equals(preparation) == false)
            throw new IllegalStateException("Unsupported model input preparation: " + preparation);

        return new EmbeddingModelDefinition(
            values.required(prefix + "display-name"), values.required(prefix + "canonical-id"),
            values.uniqueSet(prefix + "aliases"), values.required(prefix + "revision"),
            values.positiveInteger(prefix + "dimensions"), values.positiveInteger(prefix + "maximum-length"),
            values.required(prefix + "format"), values.required(prefix + "license"),
            values.required(prefix + "task"), values.required(prefix + "pooling"),
            values.bool(prefix + "normalize"),
            values.required(prefix + "query-prefix"), values.required(prefix + "document-prefix"),
            values.list(prefix + "input-names"), values.list(prefix + "output-names"),
            values.required(prefix + "repository"), values.required(prefix + "model-card-path"),
            variants, artifacts
        );
    }
    record EmbeddingModelDefinition(
        String displayName, String canonicalId, Set<String> aliases, String revision,
        int dimensions, int maximumLength, String format, String license, String task, String pooling,
        boolean normalize, String queryPrefix, String documentPrefix,
        List<String> inputNames, List<String> outputNames, String repository, String modelCardPath,
        List<EmbeddingVariantDefinition> variants, List<EmbeddingArtifactDefinition> artifacts
    ) {
        EmbeddingVariantDefinition variant(String name) {
            return variants.stream().filter(variant -> variant.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "Unsupported local embedding model variant: " + name));
        }
        EmbeddingArtifactDefinition artifact(String name, EmbeddingVariantDefinition variant) {
            if ("model.onnx".equals(name)) {
                return new EmbeddingArtifactDefinition(name, variant.sourcePath(), variant.modelSize(), variant.modelSha256());
            }
            return artifacts.stream().filter(artifact -> artifact.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "Unsupported local embedding model artifact: " + name));
        }
        List<String> entryOrder() {
            List<String> entries = new ArrayList<>(artifacts.size() + 3);
            entries.add("webjet-model.json");
            entries.add("model.onnx");
            artifacts.forEach(artifact -> entries.add(artifact.name()));
            entries.add("SHA256SUMS");
            return List.copyOf(entries);
        }
        long maximumExtractedBytes(EmbeddingVariantDefinition variant) {
            long total = variant.modelSize();
            for (EmbeddingArtifactDefinition artifact : artifacts) total = Math.addExact(total, artifact.size());
            return Math.addExact(total, 128L * 1024L);
        }
    }

    record EmbeddingVariantDefinition(
        String name, String cpuTarget, String sourcePath, long modelSize, String modelSha256
    ) { }
    record EmbeddingArtifactDefinition(String name, String sourcePath, long size, String sha256) { }
}
