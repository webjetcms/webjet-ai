package com.webjetcms.ai;

/** Identifies how an embedding model should prepare text for asymmetric retrieval. */
public enum EmbeddingInputType {
    /** Prepares a search query that will be compared with indexed documents. */
    QUERY,

    /** Prepares a document or passage that can be indexed and retrieved. */
    DOCUMENT
}
