package com.webjetcms.ai.local.tool;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.webjetcms.ai.local.tool.EmbeddingModelCatalog.Model;

/** Builds an approved E5 bundle directly from one catalogue model definition. */
final class MultilingualE5Recipe implements LocalModelRecipe {
    private final Model model;
    private final URI repository;

    MultilingualE5Recipe(Model model) {
        this.model = model;
        repository = URI.create("https://huggingface.co/" + model.repository() + "/resolve/");
    }

    @Override
    public String canonicalId() { return model.canonicalId(); }

    @Override
    public Set<String> acceptedIds() { return model.aliases(); }

    @Override
    public String revision() { return model.revision(); }

    @Override
    public Integer dimensions() { return model.dimensions(); }

    @Override
    public int maximumLength() { return model.maximumLength(); }

    @Override
    public Set<ModelVariant> supportedVariants() { return model.supportedVariants(); }

    @Override
    public ModelVariant defaultVariant() { return model.defaultVariant(); }

    @Override
    public String defaultOutputName(ModelVariant variant) {
        requireSupported(variant);
        return model.variantValue(variant, "default-output");
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
                "format": "%s",
                "file": "model.onnx",
                "license": "%s"
              },
              "task": "%s",
              "embedding": {
                "dimensions": %d,
                "pooling": "%s",
                "normalize": %s
              },
              "tokenizer": {
                "maximumLength": %d
              },
              "prefixes": {
                "query": "%s",
                "document": "%s"
              },
              "onnx": {
                "inputs": %s,
                "outputs": %s
              },
              "runtime": {
                "cpuTarget": "%s"
              },
              "source": {
                "repository": "%s",
                "revision": "%s",
                "modelPath": "%s",
                "modelCardPath": "%s"
              }
            }
            """.formatted(
                canonicalId(),
                revision(),
                variant.cliName(),
                model.format(),
                model.license(),
                model.task(),
                dimensions(),
                model.pooling(),
                model.normalize(),
                maximumLength(),
                model.queryPrefix(),
                model.documentPrefix(),
                jsonArray(model.inputNames()),
                jsonArray(model.outputNames()),
                model.variantValue(variant, "cpu-target"),
                model.repository(),
                revision(),
                model.variantValue(variant, "source-path"),
                model.modelCardPath()
            );
    }

    @Override
    public List<ModelArtifact> artifacts(ModelVariant variant) {
        requireSupported(variant);
        List<ModelArtifact> artifacts = new ArrayList<>();
        artifacts.add(modelArtifact(variant));
        for (String bundleName : model.artifactNames()) {
            artifacts.add(artifact(
                model.artifactValue(bundleName, "source-path"),
                bundleName,
                model.artifactLong(bundleName, "size"),
                model.artifactValue(bundleName, "sha256")
            ));
        }
        return List.copyOf(artifacts);
    }

    private ModelArtifact modelArtifact(ModelVariant variant) {
        return artifact(
            model.variantValue(variant, "source-path"),
            "model.onnx",
            model.variantLong(variant, "model-size"),
            model.variantValue(variant, "model-sha256")
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
            repository.resolve(revision() + "/" + sourcePath),
            size,
            sha256
        );
    }

    private static String jsonArray(List<String> values) {
        return values.stream()
            .map(value -> "\"" + value + "\"")
            .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }
}
