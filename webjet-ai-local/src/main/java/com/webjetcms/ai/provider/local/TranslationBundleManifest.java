package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webjetcms.ai.provider.local.ApprovedTranslationModelCatalog.TranslationModelDefinition;
import com.webjetcms.ai.provider.local.ApprovedTranslationModelCatalog.TranslationVariantDefinition;

/** Parsed and approved schema-v1 sequence-to-sequence bundle metadata. */
record TranslationBundleManifest(TranslationModelDefinition model, TranslationVariantDefinition variant) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TranslationBundleManifest parse(byte[] json, TranslationModelDefinition approved) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        requireObject(root, "manifest", Set.of(
            "schemaVersion", "model", "task", "tokenizer", "generation", "onnx", "runtime", "source"
        ));
        requireInteger(root, "schemaVersion", 1);
        JsonNode model = requireObject(root.get("model"), "model", Set.of(
            "id", "revision", "variant", "format", "license"
        ));
        requireText(model, "id", approved.canonicalId());
        requireText(model, "revision", approved.revision());
        String variantName = requiredText(model, "variant");
        TranslationVariantDefinition variant;
        try {
            variant = approved.variant(variantName);
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
        requireText(model, "format", "onnx-seq2seq");
        requireText(model, "license", approved.license());
        requireText(root, "task", approved.task());

        JsonNode tokenizer = requireObject(root.get("tokenizer"), "tokenizer", Set.of(
            "engine", "modelFile", "vocabularyFile", "specialTokensFile",
            "maximumLength", "languageTokenPattern"
        ));
        requireText(tokenizer, "engine", "sentencepiece");
        requireText(tokenizer, "modelFile", approved.tokenizerModelFile());
        requireText(tokenizer, "vocabularyFile", approved.vocabularyFile());
        requireText(tokenizer, "specialTokensFile", approved.specialTokensFile());
        requireInteger(tokenizer, "maximumLength", approved.maximumLength());
        requireText(tokenizer, "languageTokenPattern", "__%s__");

        JsonNode generation = requireObject(root.get("generation"), "generation", Set.of(
            "maximumLength", "decoderStartTokenId", "eosTokenId", "padTokenId", "vocabularySize", "strategy"
        ));
        requireInteger(generation, "maximumLength", approved.maximumOutputLength());
        requireInteger(generation, "decoderStartTokenId", approved.decoderStartTokenId());
        requireInteger(generation, "eosTokenId", approved.eosTokenId());
        requireInteger(generation, "padTokenId", approved.padTokenId());
        requireInteger(generation, "vocabularySize", approved.vocabularySize());
        requireText(generation, "strategy", "greedy");

        JsonNode onnx = requireObject(root.get("onnx"), "onnx", Set.of(
            "encoderFile", "decoderFile", "decoderLayers", "attentionHeads", "attentionHeadSize"
        ));
        requireText(onnx, "encoderFile", approved.encoderFile());
        requireText(onnx, "decoderFile", approved.decoderFile());
        requireInteger(onnx, "decoderLayers", approved.decoderLayers());
        requireInteger(onnx, "attentionHeads", approved.attentionHeads());
        requireInteger(onnx, "attentionHeadSize", approved.attentionHeadSize());

        JsonNode runtime = requireObject(root.get("runtime"), "runtime", Set.of("cpuTarget"));
        requireText(runtime, "cpuTarget", variant.cpuTarget());
        JsonNode source = requireObject(root.get("source"), "source", Set.of(
            "repository", "revision", "encoderPath", "decoderPath", "modelCardPath"
        ));
        requireText(source, "repository", approved.repository());
        requireText(source, "revision", approved.revision());
        requireText(source, "encoderPath", variant.encoderSourcePath());
        requireText(source, "decoderPath", variant.decoderSourcePath());
        requireText(source, "modelCardPath", approved.modelCardPath());
        return new TranslationBundleManifest(approved, variant);
    }

    private static JsonNode requireObject(JsonNode node, String name, Set<String> expected) throws IOException {
        if (node == null || node.isObject() == false) {
            throw new IOException("Local translation model manifest field must be an object: " + name);
        }
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (actual.equals(expected) == false) {
            throw new IOException("Unexpected local translation model manifest fields in " + name);
        }
        return node;
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isTextual() == false) {
            throw new IOException("Local translation model manifest field must be text: " + field);
        }
        return value.textValue();
    }

    private static void requireText(JsonNode node, String field, String expected) throws IOException {
        String actual = requiredText(node, field);
        if (expected.equals(actual) == false) {
            throw new IOException("Unexpected local translation model manifest value for " + field + ": " + actual);
        }
    }

    private static void requireInteger(JsonNode node, String field, int expected) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isInt() == false || value.intValue() != expected) {
            throw new IOException("Unexpected local translation model manifest integer for " + field);
        }
    }
}
