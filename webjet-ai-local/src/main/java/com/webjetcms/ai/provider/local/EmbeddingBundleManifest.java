package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webjetcms.ai.provider.local.ApprovedEmbeddingModelCatalog.EmbeddingModelDefinition;
import com.webjetcms.ai.provider.local.ApprovedEmbeddingModelCatalog.EmbeddingVariantDefinition;

/** Parsed and approved schema-v1 model bundle metadata. */
record EmbeddingBundleManifest(
    String modelId,
    String revision,
    EmbeddingVariantDefinition variant,
    int dimensions,
    int maximumLength,
    String queryPrefix,
    String documentPrefix,
    List<String> inputNames,
    List<String> outputNames
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static EmbeddingBundleManifest parse(byte[] json, EmbeddingModelDefinition approved) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        requireObject(root, "manifest", Set.of(
            "schemaVersion", "model", "task", "embedding", "tokenizer",
            "prefixes", "onnx", "runtime", "source"
        ));
        requireInteger(root, "schemaVersion", 1);

        JsonNode model = requireObject(root.get("model"), "model", Set.of(
            "id", "revision", "variant", "format", "file", "license"
        ));
        requireText(model, "id", approved.canonicalId());
        requireText(model, "revision", approved.revision());
        String variantName = requiredText(model, "variant");
        EmbeddingVariantDefinition variant;
        try {
            variant = approved.variant(variantName);
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
        requireText(model, "format", approved.format());
        requireText(model, "file", "model.onnx");
        requireText(model, "license", approved.license());
        requireText(root, "task", approved.task());

        JsonNode embedding = requireObject(root.get("embedding"), "embedding", Set.of(
            "dimensions", "pooling", "normalize"
        ));
        requireInteger(embedding, "dimensions", approved.dimensions());
        requireText(embedding, "pooling", approved.pooling());
        requireBoolean(embedding, "normalize", approved.normalize());

        JsonNode tokenizer = requireObject(root.get("tokenizer"), "tokenizer", Set.of("maximumLength"));
        requireInteger(tokenizer, "maximumLength", approved.maximumLength());

        JsonNode prefixes = requireObject(root.get("prefixes"), "prefixes", Set.of("query", "document"));
        requireText(prefixes, "query", approved.queryPrefix());
        requireText(prefixes, "document", approved.documentPrefix());

        JsonNode onnx = requireObject(root.get("onnx"), "onnx", Set.of("inputs", "outputs"));
        requireTextArray(onnx, "inputs", approved.inputNames());
        requireTextArray(onnx, "outputs", approved.outputNames());

        JsonNode runtime = requireObject(root.get("runtime"), "runtime", Set.of("cpuTarget"));
        requireText(runtime, "cpuTarget", variant.cpuTarget());

        JsonNode source = requireObject(root.get("source"), "source", Set.of(
            "repository", "revision", "modelPath", "modelCardPath"
        ));
        requireText(source, "repository", approved.repository());
        requireText(source, "revision", approved.revision());
        requireText(source, "modelPath", variant.sourcePath());
        requireText(source, "modelCardPath", approved.modelCardPath());

        return new EmbeddingBundleManifest(
            approved.canonicalId(),
            approved.revision(),
            variant,
            approved.dimensions(),
            approved.maximumLength(),
            approved.queryPrefix(),
            approved.documentPrefix(),
            approved.inputNames(),
            approved.outputNames()
        );
    }

    private static JsonNode requireObject(JsonNode node, String name, Set<String> expected) throws IOException {
        if (node == null || node.isObject() == false) {
            throw new IOException("Local model manifest field must be an object: " + name);
        }
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (actual.equals(expected) == false) {
            throw new IOException(
                "Unexpected local model manifest fields in " + name
                    + "; missing=" + difference(expected, actual)
                    + ", extra=" + difference(actual, expected)
            );
        }
        return node;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> difference = new LinkedHashSet<>(left);
        difference.removeAll(right);
        return difference;
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isTextual() == false) {
            throw new IOException("Local model manifest field must be text: " + field);
        }
        return value.textValue();
    }

    private static void requireText(JsonNode node, String field, String expected) throws IOException {
        String actual = requiredText(node, field);
        if (expected.equals(actual) == false) {
            throw new IOException("Unexpected local model manifest value for " + field + ": " + actual);
        }
    }

    private static void requireInteger(JsonNode node, String field, int expected) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isInt() == false || value.intValue() != expected) {
            throw new IOException("Unexpected local model manifest integer for " + field);
        }
    }

    private static void requireBoolean(JsonNode node, String field, boolean expected) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isBoolean() == false || value.booleanValue() != expected) {
            throw new IOException("Unexpected local model manifest boolean for " + field);
        }
    }

    private static void requireTextArray(JsonNode node, String field, List<String> expected) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isArray() == false || value.size() != expected.size()) {
            throw new IOException("Unexpected local model manifest array for " + field);
        }
        for (int index = 0; index < expected.size(); index++) {
            JsonNode element = value.get(index);
            if (element.isTextual() == false || expected.get(index).equals(element.textValue()) == false) {
                throw new IOException("Unexpected local model manifest value in " + field + " at index " + index);
            }
        }
    }
}
