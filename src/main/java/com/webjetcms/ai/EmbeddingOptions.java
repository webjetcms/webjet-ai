package com.webjetcms.ai;

/**
 * Provider-neutral embedding options.
 *
 * @param dimensions requested number of values in every returned vector, or {@code null}
 *     to use the provider default
 * @param inputType input role used for model-specific preparation; {@code null} selects
 *     {@link EmbeddingInputType#DOCUMENT}
 */
public record EmbeddingOptions(Integer dimensions, EmbeddingInputType inputType) {

    /** Validates an explicitly requested vector size. */
    public EmbeddingOptions {
        if (dimensions != null && dimensions < 1) {
            throw new IllegalArgumentException("Embedding dimensions must be greater than zero");
        }
        inputType = java.util.Objects.requireNonNullElse(inputType, EmbeddingInputType.DOCUMENT);
    }

    /**
     * Creates options with a fixed vector size and document input preparation.
     *
     * @param dimensions requested vector size, or {@code null} to use the provider default
     */
    public EmbeddingOptions(Integer dimensions) {
        this(dimensions, EmbeddingInputType.DOCUMENT);
    }

    /** Creates options that use the provider's default vector size. */
    public EmbeddingOptions() {
        this(null, EmbeddingInputType.DOCUMENT);
    }
}
