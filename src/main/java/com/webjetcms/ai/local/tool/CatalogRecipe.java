package com.webjetcms.ai.local.tool;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Prepares approved local models entirely from catalogue metadata. */
final class CatalogRecipe implements LocalModelRecipe {
    private static final String EMBEDDINGS = "META-INF/webjet-ai/local-embedding-model-catalog-v1.properties";
    private final ModelCatalog model;
    private final ModelCatalog files;
    private final URI repository;
    private final boolean embedding;

    private CatalogRecipe(ModelCatalog model, ModelCatalog files, boolean embedding) {
        this.model = model;
        this.files = files;
        this.embedding = embedding;
        repository = URI.create("https://huggingface.co/" + value("repository") + "/resolve/");
        validate();
    }

    static List<CatalogRecipe> embeddings() {
        ModelCatalog catalog = ModelCatalog.load(EMBEDDINGS, "embedding-model");
        return catalog.list("catalog.models").stream().map(key -> catalog.at("model." + key + "."))
            .map(model -> new CatalogRecipe(model, model, true)).toList();
    }

    static CatalogRecipe seq2seq(String resource, String description) {
        ModelCatalog catalog = ModelCatalog.load(resource, description);
        return new CatalogRecipe(catalog.at("model."), catalog, false);
    }

    @Override public String canonicalId() { return value("canonical-id"); }
    @Override public Set<String> acceptedIds() { return model.unique("aliases"); }
    @Override public String revision() { return value("revision"); }
    @Override public Integer dimensions() { return embedding ? model.positiveInteger("dimensions") : null; }
    @Override public int maximumLength() { return model.positiveInteger("maximum-length"); }
    @Override public Set<ModelVariant> supportedVariants() { return model.variants(); }
    @Override public ModelVariant defaultVariant() { return model.defaultVariant(); }

    @Override public String defaultOutputName(ModelVariant variant) {
        requireSupported(variant);
        return files.variant(variant, "default-output");
    }

    @Override public String manifest(ModelVariant variant) {
        requireSupported(variant);
        return embedding ? embeddingManifest(variant) : seq2seqManifest(variant);
    }

    @Override public List<ModelArtifact> artifacts(ModelVariant variant) {
        requireSupported(variant);
        List<ModelArtifact> result = new ArrayList<>();
        if (embedding) result.add(variantArtifact(variant, "model", "model.onnx"));
        else {
            result.add(variantArtifact(variant, "encoder", value("encoder-file")));
            result.add(variantArtifact(variant, "decoder", value("decoder-file")));
        }
        for (String name : model.list("artifacts")) result.add(artifact(files.artifact(name, "source-path"), name,
            files.artifactNumber(name, "size"), files.artifactSha256(name)));
        return List.copyOf(result);
    }

    private String embeddingManifest(ModelVariant variant) {
        return """
            {"schemaVersion":1,"model":{"id":"%s","revision":"%s","variant":"%s","format":"%s","file":"model.onnx","license":"%s"},"task":"%s","embedding":{"dimensions":%d,"pooling":"%s","normalize":%s},"tokenizer":{"maximumLength":%d},"prefixes":{"query":"%s","document":"%s"},"onnx":{"inputs":%s,"outputs":%s},"runtime":{"cpuTarget":"%s"},"source":{"repository":"%s","revision":"%s","modelPath":"%s","modelCardPath":"%s"}}
            """.formatted(canonicalId(), revision(), variant.cliName(), value("format"), value("license"), value("task"),
                dimensions(), value("pooling"), model.bool("normalize"), maximumLength(), value("query-prefix"),
                value("document-prefix"), jsonArray(model.list("input-names")), jsonArray(model.list("output-names")),
                files.variant(variant, "cpu-target"), value("repository"), revision(),
                files.variant(variant, "source-path"), value("model-card-path"));
    }

    private String seq2seqManifest(ModelVariant variant) {
        return """
            {"schemaVersion":1,"model":{"id":"%s","revision":"%s","variant":"%s","format":"onnx-seq2seq","license":"%s"},"task":"%s","tokenizer":%s,"generation":{"maximumLength":%d,"decoderStartTokenId":%d,"eosTokenId":%d,"padTokenId":%d,"vocabularySize":%d,"strategy":"greedy"},"onnx":{"encoderFile":"%s","decoderFile":"%s","decoderLayers":%d,"attentionHeads":%d,"attentionHeadSize":%d},"runtime":{"cpuTarget":"%s"},"source":{"repository":"%s","revision":"%s","encoderPath":"%s","decoderPath":"%s","modelCardPath":"%s"}}
            """.formatted(canonicalId(), revision(), variant.cliName(), value("license"), value("task"), tokenizer(),
                model.positiveInteger("maximum-output-length"), model.nonNegativeInteger("decoder-start-token-id"),
                model.nonNegativeInteger("eos-token-id"), model.nonNegativeInteger("pad-token-id"),
                model.positiveInteger("vocabulary-size"), value("encoder-file"), value("decoder-file"),
                model.positiveInteger("decoder-layers"), model.positiveInteger("attention-heads"),
                model.positiveInteger("attention-head-size"),
                files.variant(variant, "cpu-target"), value("repository"), revision(),
                files.variant(variant, "encoder-source-path"), files.variant(variant, "decoder-source-path"),
                value("model-card-path"));
    }

    private String tokenizer() {
        return switch (value("tokenizer-kind")) {
            case "m2m100" -> ("{\"engine\":\"sentencepiece\",\"modelFile\":\"%s\",\"vocabularyFile\":\"%s\","
                + "\"specialTokensFile\":\"%s\",\"maximumLength\":%d,\"languageTokenPattern\":\"__%%s__\"}")
                .formatted(value("tokenizer-model-file"), value("vocabulary-file"), value("special-tokens-file"), maximumLength());
            case "huggingface" -> ("{\"engine\":\"huggingface\",\"tokenizerFile\":\"%s\",\"configFile\":\"%s\","
                + "\"modelFile\":\"%s\",\"specialTokensFile\":\"%s\",\"maximumLength\":%d}")
                .formatted(value("tokenizer-file"), value("tokenizer-config-file"), value("tokenizer-model-file"),
                    value("special-tokens-file"), maximumLength());
            default -> throw new IllegalStateException("Unsupported local model tokenizer kind");
        };
    }

    private String value(String key) { return model.value(key); }
    private void validate() {
        value("display-name");
        if (acceptedIds().contains(canonicalId()) == false) {
            throw new IllegalStateException("Local model aliases must contain the canonical ID: " + canonicalId());
        }
        if (embedding) value("input-preparation");
        else {
            model.positiveInteger("hidden-size");
            for (String key : List.of("encoder-file", "decoder-file", "tokenizer-file", "tokenizer-config-file",
                "tokenizer-model-file", "special-tokens-file", "model-card-path")) value(key);
            tokenizer();
        }
        model.unique("artifacts");
        defaultVariant();
        for (ModelVariant variant : supportedVariants()) {
            defaultOutputName(variant);
            manifest(variant);
            artifacts(variant);
        }
    }
    private void requireSupported(ModelVariant variant) {
        if (supportedVariants().contains(variant) == false)
            throw new IllegalArgumentException("Unsupported variant for " + canonicalId() + ": " + variant.cliName());
    }
    private ModelArtifact variantArtifact(ModelVariant variant, String role, String path) {
        String prefix = embedding ? "model" : role;
        String source = embedding ? "source-path" : role + "-source-path";
        return artifact(files.variant(variant, source), path,
            files.variantNumber(variant, prefix + "-size"), files.variantSha256(variant, prefix + "-sha256"));
    }
    private ModelArtifact artifact(String sourcePath, String bundlePath, long size, String sha256) {
        return new ModelArtifact(sourcePath, bundlePath, repository.resolve(revision() + "/" + sourcePath), size, sha256);
    }
    private static String jsonArray(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
