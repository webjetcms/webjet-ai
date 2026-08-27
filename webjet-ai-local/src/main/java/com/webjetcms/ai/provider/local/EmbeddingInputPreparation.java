package com.webjetcms.ai.provider.local;

import com.webjetcms.ai.EmbeddingInputType;

/** Applies the input transformation selected by the approved model catalogue. */
enum EmbeddingInputPreparation {
    E5_PREFIX("e5-prefix") {
        @Override
        String prepare(EmbeddingBundleManifest manifest, String input, EmbeddingInputType inputType) {
            String prefix = inputType == EmbeddingInputType.QUERY
                ? manifest.queryPrefix()
                : manifest.documentPrefix();
            return prefix + input;
        }
    };

    private final String catalogValue;

    EmbeddingInputPreparation(String catalogValue) {
        this.catalogValue = catalogValue;
    }

    abstract String prepare(
        EmbeddingBundleManifest manifest,
        String input,
        EmbeddingInputType inputType
    );

    static EmbeddingInputPreparation fromCatalog(String value) {
        for (EmbeddingInputPreparation preparation : values()) {
            if (preparation.catalogValue.equals(value)) return preparation;
        }
        throw new IllegalStateException("Unsupported model input preparation: " + value);
    }
}
