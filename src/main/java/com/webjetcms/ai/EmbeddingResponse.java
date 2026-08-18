package com.webjetcms.ai;

import java.util.List;

/**
 * Completed provider embedding response.
 *
 * @param embeddings generated vectors in request input order; {@code null} becomes an empty list
 * @param usage provider-reported token usage; {@code null} becomes {@link TokenUsage#EMPTY}
 */
public record EmbeddingResponse(List<EmbeddingVector> embeddings, TokenUsage usage) {

    /** Normalizes nullable values and stores an immutable copy of the vectors. */
    public EmbeddingResponse {
        embeddings = embeddings == null ? List.of() : List.copyOf(embeddings);
        usage = usage == null ? TokenUsage.EMPTY : usage;
    }

    /**
     * Creates an embedding response without provider-reported token usage.
     *
     * @param embeddings generated vectors in request input order
     */
    public EmbeddingResponse(List<EmbeddingVector> embeddings) {
        this(embeddings, TokenUsage.EMPTY);
    }
}
