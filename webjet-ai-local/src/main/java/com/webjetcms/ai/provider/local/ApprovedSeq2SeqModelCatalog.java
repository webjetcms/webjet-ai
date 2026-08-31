package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Loads one production-approved encoder-decoder model from a core catalogue resource. */
final class ApprovedSeq2SeqModelCatalog {
    static final String TRANSLATION_RESOURCE =
        "META-INF/webjet-ai/local-translation-model-catalog-v1.properties";
    private ApprovedSeq2SeqModelCatalog() { }

    static ModelDefinition load(String resource, String description) {
        CatalogValues values = CatalogValues.load(ApprovedSeq2SeqModelCatalog.class, resource, description);
        if (values.integer("catalog.version") != 1)
            throw new IllegalStateException("Unsupported approved " + description + " catalogue version");

        List<VariantDefinition> variants = new ArrayList<>();
        for (String name : values.uniqueList("model.variants")) {
            String prefix = "variant." + name + ".";
            variants.add(new VariantDefinition(
                name, values.required(prefix + "cpu-target"),
                values.required(prefix + "encoder-source-path"), values.positiveLong(prefix + "encoder-size"),
                values.sha256(prefix + "encoder-sha256"), values.required(prefix + "decoder-source-path"),
                values.positiveLong(prefix + "decoder-size"),
                values.sha256(prefix + "decoder-sha256")
            ));
        }

        List<ArtifactDefinition> artifacts = new ArrayList<>();
        for (String name : values.uniqueList("model.artifacts")) {
            String prefix = "artifact." + name + ".";
            artifacts.add(new ArtifactDefinition(
                name, values.required(prefix + "source-path"), values.positiveLong(prefix + "size"),
                values.sha256(prefix + "sha256")
            ));
        }

        Set<String> aliases = values.uniqueSet("model.aliases");
        String canonicalId = values.required("model.canonical-id");
        if (aliases.contains(canonicalId) == false)
            throw new IllegalStateException("Approved model aliases must contain its canonical ID: " + canonicalId);
        String defaultVariant = values.required("model.default-variant");
        if (variants.stream().noneMatch(variant -> variant.name().equals(defaultVariant)))
            throw new IllegalStateException("Approved model default variant is unsupported: " + defaultVariant);

        requireM2m100Tokenizer(values.required("model.tokenizer-kind"));
        return new ModelDefinition(
            values.required("model.display-name"), canonicalId, aliases,
            values.required("model.repository"), values.required("model.revision"),
            values.required("model.license"), values.required("model.task"),
            values.positiveInteger("model.maximum-length"),
            values.positiveInteger("model.maximum-output-length"),
            values.nonNegativeInteger("model.decoder-start-token-id"), values.nonNegativeInteger("model.eos-token-id"),
            values.nonNegativeInteger("model.pad-token-id"), values.positiveInteger("model.vocabulary-size"),
            values.positiveInteger("model.decoder-layers"), values.positiveInteger("model.hidden-size"),
            values.positiveInteger("model.attention-heads"), values.positiveInteger("model.attention-head-size"),
            values.required("model.encoder-file"), values.required("model.decoder-file"),
            values.required("model.tokenizer-model-file"), values.optional("model.vocabulary-file"),
            values.required("model.special-tokens-file"), values.required("model.model-card-path"),
            defaultVariant, List.copyOf(variants), List.copyOf(artifacts)
        );
    }

    private static void requireM2m100Tokenizer(String value) {
        if ("m2m100".equals(value) == false)
            throw new IllegalStateException("Unsupported approved tokenizer kind: " + value);
    }

    record ModelDefinition(
        String displayName, String canonicalId, Set<String> aliases,
        String repository, String revision, String license, String task,
        int maximumLength, int maximumOutputLength,
        int decoderStartTokenId, int eosTokenId, int padTokenId, int vocabularySize,
        int decoderLayers, int hiddenSize, int attentionHeads, int attentionHeadSize,
        String encoderFile, String decoderFile, String tokenizerModelFile, String vocabularyFile,
        String specialTokensFile, String modelCardPath,
        String defaultVariant, List<VariantDefinition> variants, List<ArtifactDefinition> artifacts
    ) {
        VariantDefinition variant(String name) throws IOException {
            for (VariantDefinition variant : variants) if (variant.name().equals(name)) return variant;
            throw new IOException("Unsupported local model variant: " + name);
        }

        ArtifactDefinition artifact(String name, VariantDefinition variant) throws IOException {
            if (encoderFile.equals(name)) return new ArtifactDefinition(
                name, variant.encoderSourcePath(), variant.encoderSize(), variant.encoderSha256());
            if (decoderFile.equals(name)) return new ArtifactDefinition(
                name, variant.decoderSourcePath(), variant.decoderSize(), variant.decoderSha256());
            for (ArtifactDefinition artifact : artifacts) if (artifact.name().equals(name)) return artifact;
            throw new IOException("Unsupported local model artifact: " + name);
        }

        List<String> entryOrder() {
            List<String> entries = new ArrayList<>(artifacts.size() + 4);
            entries.addAll(List.of("webjet-model.json", encoderFile, decoderFile));
            for (ArtifactDefinition artifact : artifacts) entries.add(artifact.name());
            entries.add("SHA256SUMS");
            return List.copyOf(entries);
        }

        long maximumExtractedBytes(VariantDefinition variant) {
            long total = Math.addExact(variant.encoderSize(), variant.decoderSize());
            for (ArtifactDefinition artifact : artifacts) total = Math.addExact(total, artifact.size());
            return Math.addExact(total, 128L * 1024L);
        }
    }

    record VariantDefinition(
        String name, String cpuTarget,
        String encoderSourcePath, long encoderSize, String encoderSha256,
        String decoderSourcePath, long decoderSize, String decoderSha256
    ) { }

    record ArtifactDefinition(String name, String sourcePath, long size, String sha256) { }
}
