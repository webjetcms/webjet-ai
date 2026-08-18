package com.webjetcms.ai;

/**
 * Provider-neutral embedding options.
 *
 * @param dimensions required number of values in every returned embedding vector
 * @param taskType optional retrieval role for providers that support task-specific embeddings
 */
public record EmbeddingOptions(int dimensions, EmbeddingTaskType taskType) {

    /** Validates that the requested vector size is positive. */
    public EmbeddingOptions {
        if (dimensions < 1) {
            throw new IllegalArgumentException("Embedding dimensions must be greater than zero");
        }
    }

    /**
     * Creates options without a provider-specific retrieval task.
     *
     * @param dimensions required number of values in every returned embedding vector
     */
    public EmbeddingOptions(int dimensions) {
        this(dimensions, null);
    }
}
