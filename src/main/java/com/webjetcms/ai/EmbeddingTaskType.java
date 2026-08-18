package com.webjetcms.ai;

/** Provider-neutral retrieval task for an embedding request. */
public enum EmbeddingTaskType {
    /** Embeds a document that will be stored and searched. */
    RETRIEVAL_DOCUMENT,

    /** Embeds a query used to search stored documents. */
    RETRIEVAL_QUERY
}
