package com.webjetcms.ai;

/** Stable classifications for failures that callers can handle without parsing messages. */
public enum AiErrorCategory {
    /** No more specific classification is available. */
    UNKNOWN,

    /** The caller explicitly cancelled this operation; it must not be automatically retried. */
    CANCELLED,

    /** A supplied chat continuation ID is invalid or cannot be continued. */
    INVALID_CONTINUATION,

    /** The provider adapter or selected model does not support the requested operation. */
    UNSUPPORTED_OPERATION
}
