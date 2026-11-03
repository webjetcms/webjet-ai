package com.webjetcms.ai;

import java.io.Serializable;
import java.util.Objects;

/**
 * Facts known about one generation attempt, independent of retryability and completion.
 * An observed ID is useful for cleanup, but does not establish successful continuation,
 * provider storage, or future retrievability. Only a successfully returned {@link AiResponse}
 * supplies a continuation result.
 *
 * @param state known submission outcome
 * @param responseId observed provider response ID, present only for {@link State#RESPONSE_OBSERVED};
 *     null for other states or when unavailable after credential redaction
 */
public record AiRequestLifecycle(State state, String responseId) implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final AiRequestLifecycle UNKNOWN = new AiRequestLifecycle(State.UNKNOWN, null);

    /**
     * Creates a lifecycle snapshot, rejecting contradictory state and ID values.
     *
     * @param state non-null outcome
     * @param responseId non-blank ID for an observed response, or null if absent or fully redacted
     */
    public AiRequestLifecycle {
        Objects.requireNonNull(state, "state");
        if (responseId != null && (state != State.RESPONSE_OBSERVED || responseId.isBlank())) {
            throw new IllegalArgumentException("A response ID must be non-blank and requires RESPONSE_OBSERVED");
        }
    }

    /**
     * Returns an unspecified outcome, including for providers without lifecycle support.
     * @return shared immutable unknown snapshot
     */
    public static AiRequestLifecycle unknown() { return UNKNOWN; }

    /** Submission facts; none of these states means that generation completed successfully. */
    public enum State {
        /** Execution stopped before submission to the provider. */
        NOT_SUBMITTED,
        /** The provider explicitly rejected the request before creating a response. */
        REJECTED,
        /** The adapter cannot establish whether a response was created. */
        UNKNOWN,
        /** A response ID was observed, even if generation failed, was cancelled, or the ID was redacted. */
        RESPONSE_OBSERVED
    }
}
