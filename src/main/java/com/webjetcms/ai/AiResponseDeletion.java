package com.webjetcms.ai;

/**
 * Confirmed outcome of deleting one provider-managed response in the supplied account.
 * Neither outcome establishes that an entire conversation or local history was deleted.
 * Unsupported operations and unconfirmed outcomes are reported as {@link AiProviderException}.
 */
public enum AiResponseDeletion {
    /** The provider confirmed deletion of the requested response. */
    DELETED,

    /** The provider explicitly identified the requested response as absent. */
    ALREADY_ABSENT
}
