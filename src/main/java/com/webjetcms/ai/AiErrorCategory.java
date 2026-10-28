package com.webjetcms.ai;

/** Stable classifications for failures that callers can handle without parsing messages. */
public enum AiErrorCategory {
    /** No more specific classification is available. */
    UNKNOWN,

    /** The caller explicitly cancelled this operation; it must not be automatically retried. */
    CANCELLED
}
