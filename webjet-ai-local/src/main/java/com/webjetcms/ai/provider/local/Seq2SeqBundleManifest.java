package com.webjetcms.ai.provider.local;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webjetcms.ai.provider.local.ApprovedSeq2SeqModelCatalog.ModelDefinition;
import com.webjetcms.ai.provider.local.ApprovedSeq2SeqModelCatalog.VariantDefinition;

/** Parsed and approved schema-v1 encoder-decoder bundle metadata. */
record Seq2SeqBundleManifest(ModelDefinition model, VariantDefinition variant) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ManifestJson JSON = new ManifestJson("Local sequence-to-sequence manifest");

    static Seq2SeqBundleManifest parse(byte[] json, ModelDefinition approved) throws IOException {
        JsonNode root = JSON.object(MAPPER.readTree(json), "manifest",
            "schemaVersion", "model", "task", "tokenizer", "generation", "onnx", "runtime", "source"
        );
        JSON.expect(root, "schemaVersion", 1);
        JsonNode model = JSON.values(root, "model", "id", approved.canonicalId(),
            "revision", approved.revision(), "variant", null,
            "format", "onnx-seq2seq", "license", approved.license());
        VariantDefinition variant = approved.variant(JSON.text(model, "variant"));
        JSON.expect(root, "task", approved.task());

        validateM2m100Tokenizer(root, approved);

        JSON.values(root, "generation", "maximumLength", approved.maximumOutputLength(),
            "decoderStartTokenId", approved.decoderStartTokenId(), "eosTokenId", approved.eosTokenId(),
            "padTokenId", approved.padTokenId(), "vocabularySize", approved.vocabularySize(),
            "strategy", "greedy");

        JSON.values(root, "onnx", "encoderFile", approved.encoderFile(), "decoderFile", approved.decoderFile(),
            "decoderLayers", approved.decoderLayers(), "attentionHeads", approved.attentionHeads(),
            "attentionHeadSize", approved.attentionHeadSize());

        JSON.values(root, "runtime", "cpuTarget", variant.cpuTarget());
        JSON.values(root, "source", "repository", approved.repository(), "revision", approved.revision(),
            "encoderPath", variant.encoderSourcePath(), "decoderPath", variant.decoderSourcePath(),
            "modelCardPath", approved.modelCardPath());
        return new Seq2SeqBundleManifest(approved, variant);
    }

    private static void validateM2m100Tokenizer(JsonNode node, ModelDefinition approved) throws IOException {
        JSON.values(node, "tokenizer", "engine", "sentencepiece", "modelFile", approved.tokenizerModelFile(),
            "vocabularyFile", approved.vocabularyFile(), "specialTokensFile", approved.specialTokensFile(),
            "maximumLength", approved.maximumLength(), "languageTokenPattern", "__%s__");
    }
}
