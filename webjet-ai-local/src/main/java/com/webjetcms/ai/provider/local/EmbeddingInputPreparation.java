package com.webjetcms.ai.provider.local;

/** Applies the input transformation selected by the approved model catalogue. */
enum EmbeddingInputPreparation {
    E5_DOCUMENT_PREFIX("e5-document-prefix") {
        @Override
        String prepare(EmbeddingBundleManifest manifest, String input) {
            return manifest.documentPrefix() + input;
        }
    };

    private final String catalogValue;

    EmbeddingInputPreparation(String catalogValue) {
        this.catalogValue = catalogValue;
    }

    abstract String prepare(EmbeddingBundleManifest manifest, String input);

    static EmbeddingInputPreparation fromCatalog(String value) {
        for (EmbeddingInputPreparation preparation : values()) {
            if (preparation.catalogValue.equals(value)) return preparation;
        }
        throw new IllegalStateException("Unsupported model input preparation: " + value);
    }
}
