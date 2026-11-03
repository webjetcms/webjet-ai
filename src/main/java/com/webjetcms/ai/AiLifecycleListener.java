package com.webjetcms.ai;

/** Optional per-call observer of provider response IDs, separate from streamed text. */
@FunctionalInterface
public interface AiLifecycleListener {
    /**
     * Receives an ID as soon as the adapter observes it, before completion validation.
     * The callback runs synchronously on the executing thread and may occur after
     * cancellation was requested. Throwing aborts the call; its failure retains the ID.
     * This notification must not advance the application's successful continuation.
     *
     * @param responseId non-blank provider response ID; repeated IDs are delivered once per call
     * @throws Exception if the application cannot record the observation
     */
    void onResponseId(String responseId) throws Exception;

    /**
     * Returns an observer that ignores notifications.
     * @return no-op observer
     */
    static AiLifecycleListener none() { return responseId -> { }; }
}
