package com.webjetcms.ai.internal;

import java.util.Objects;

import com.webjetcms.ai.AiCancellationToken;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiStreamListener;

/** Shared cancellation checks around provider calls and streamed callbacks. */
public final class CancellationSupport {

    private CancellationSupport() { }

    /**
     * Runs a call with cancellation checks before execution and its final result.
     * Cancellation observed at these checks takes precedence over a result or failure.
     *
     * @param providerId provider associated with the call
     * @param token non-null cancellation token
     * @param operation blocking operation to run on the current thread
     * @param <T> result type
     * @return the result if cancellation was not observed at the final check
     * @throws AiProviderException if cancelled or the operation fails
     */
    public static <T> T call(String providerId, AiCancellationToken token, Call<T> operation)
        throws AiProviderException {
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled(providerId);
        try {
            T result = operation.invoke();
            token.throwIfCancelled(providerId);
            return result;
        } catch (AiProviderException | RuntimeException exception) {
            token.throwIfCancelled(providerId);
            throw exception;
        }
    }

    /**
     * Guards each callback against cancellation while retaining provider null validation.
     *
     * @param providerId provider associated with the stream
     * @param token cancellation token
     * @param listener application listener, or {@code null}
     * @return guarded listener, or {@code null} when the supplied listener is null
     */
    public static AiStreamListener listener(
        String providerId, AiCancellationToken token, AiStreamListener listener
    ) {
        Objects.requireNonNull(token, "token");
        if (listener == null) return null;
        return delta -> {
            token.throwIfCancelled(providerId);
            listener.onTextDelta(delta);
            token.throwIfCancelled(providerId);
        };
    }

    /** Blocking operation that may fail with a structured provider exception. */
    @FunctionalInterface
    public interface Call<T> {
        /**
         * Executes the operation.
         *
         * @return operation result
         * @throws AiProviderException if execution fails
         */
        T invoke() throws AiProviderException;
    }
}
