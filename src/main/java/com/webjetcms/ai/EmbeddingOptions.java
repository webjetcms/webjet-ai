package com.webjetcms.ai;

/**
 * Provider-neutral embedding options.
 *
 * @param dimensions requested number of values in every returned vector, or {@code null}
 *     to use the provider default
 */
public record EmbeddingOptions(Integer dimensions) {

    /** Validates an explicitly requested vector size. */
    public EmbeddingOptions {
        if (dimensions != null && dimensions < 1) {
            throw new IllegalArgumentException("Embedding dimensions must be greater than zero");
        }
    }

    /** Creates options that use the provider's default vector size. */
    public EmbeddingOptions() {
        this(null);
    }
}
