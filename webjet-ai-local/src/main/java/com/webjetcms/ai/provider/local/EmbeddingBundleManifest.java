package com.webjetcms.ai.provider.local;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webjetcms.ai.provider.local.ApprovedEmbeddingModelCatalog.EmbeddingModelDefinition;
import com.webjetcms.ai.provider.local.ApprovedEmbeddingModelCatalog.EmbeddingVariantDefinition;

/** Parsed and approved schema-v1 model bundle metadata. */
record EmbeddingBundleManifest(EmbeddingModelDefinition model, EmbeddingVariantDefinition variant) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ManifestJson JSON = new ManifestJson("Local model manifest");

    static EmbeddingBundleManifest parse(byte[] json, ApprovedEmbeddingModelCatalog catalog) throws IOException {
        JsonNode root = JSON.object(MAPPER.readTree(json), "manifest",
            "schemaVersion", "model", "task", "embedding", "tokenizer", "prefixes", "onnx", "runtime", "source");
        JSON.expect(root, "schemaVersion", 1);
        EmbeddingModelDefinition approved = catalog.model(JSON.text(root.get("model"), "id"));
        JsonNode model = JSON.values(root, "model",
            "id", approved.canonicalId(), "revision", approved.revision(), "variant", null,
            "format", approved.format(), "file", "model.onnx", "license", approved.license());
        EmbeddingVariantDefinition variant = approved.variant(JSON.text(model, "variant"));
        JSON.expect(root, "task", approved.task());

        JSON.values(root, "embedding", "dimensions", approved.dimensions(),
            "pooling", approved.pooling(), "normalize", approved.normalize());
        JSON.values(root, "tokenizer", "maximumLength", approved.maximumLength());
        JSON.values(root, "prefixes", "query", approved.queryPrefix(),
            "document", approved.documentPrefix());
        JSON.values(root, "onnx", "inputs", approved.inputNames(), "outputs", approved.outputNames());
        JSON.values(root, "runtime", "cpuTarget", variant.cpuTarget());
        JSON.values(root, "source", "repository", approved.repository(), "revision", approved.revision(),
            "modelPath", variant.sourcePath(), "modelCardPath", approved.modelCardPath());
        return new EmbeddingBundleManifest(approved, variant);
    }
}
