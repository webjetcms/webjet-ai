package com.webjetcms.ai;

/** Receives decoded text fragments as they arrive from an AI provider. */
@FunctionalInterface
public interface AiStreamListener {
    /**
     * Handles the next decoded text fragment in provider order.
     *
     * @param delta text fragment received from the provider; it may contain a partial token
     * @throws Exception when the consuming application cannot process the fragment
     */
    void onTextDelta(String delta) throws Exception;
}
