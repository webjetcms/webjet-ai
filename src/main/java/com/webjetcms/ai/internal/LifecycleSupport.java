package com.webjetcms.ai.internal;

import java.util.Objects;

import com.webjetcms.ai.AiCancellationToken;
import com.webjetcms.ai.AiErrorCategory;
import com.webjetcms.ai.AiLifecycleListener;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequestLifecycle;
import com.webjetcms.ai.AiRequestLifecycle.State;
import com.webjetcms.ai.AiResponse;

/** Per-call lifecycle tracking; instances must never be stored in shared providers. */
public final class LifecycleSupport implements AiLifecycleListener {
    private final String providerId;
    private final AiLifecycleListener listener;
    private AiRequestLifecycle snapshot = new AiRequestLifecycle(State.NOT_SUBMITTED, null);

    /**
     * Creates isolated tracking for one call.
     * @param providerId provider identifier for failures
     * @param listener non-null application observer
     */
    public LifecycleSupport(String providerId, AiLifecycleListener listener) {
        this.providerId = providerId;
        this.listener = Objects.requireNonNull(listener, "lifecycleListener");
    }

    /**
     * Marks possible submission immediately before transport or delegation to a legacy provider.
     * @param token token checked before leaving the not-submitted state
     * @throws AiProviderException if already cancelled
     */
    public void submitting(AiCancellationToken token) throws AiProviderException {
        token.throwIfCancelled(providerId);
        snapshot = AiRequestLifecycle.unknown();
    }

    /** Records a protocol-confirmed rejection without overwriting an observed response. */
    public void rejected() {
        if (snapshot.responseId() == null) snapshot = new AiRequestLifecycle(State.REJECTED, null);
    }

    @Override
    public void onResponseId(String responseId) throws AiProviderException {
        if (responseId == null || responseId.isBlank()) return;
        if (snapshot.responseId() != null) {
            if (snapshot.responseId().equals(responseId)) return;
            throw new AiProviderException(providerId, "Provider returned inconsistent response IDs");
        }
        snapshot = new AiRequestLifecycle(State.RESPONSE_OBSERVED, responseId);
        try {
            listener.onResponseId(responseId);
        } catch (Exception exception) {
            throw new AiProviderException(providerId, "The lifecycle listener rejected a response ID", exception);
        }
    }

    /**
     * Preserves lifecycle facts through completion checks, cancellation, and credential redaction.
     * @param config configuration used for redaction
     * @param token non-null per-call cancellation token
     * @param operation generation operation
     * @return successful response after the final cancellation check
     * @throws AiProviderException if the operation or observer fails, with the best known lifecycle
     */
    public AiResponse call(
        AiProviderConfig config, AiCancellationToken token, CancellationSupport.Call<AiResponse> operation
    ) throws AiProviderException {
        try {
            return CancellationSupport.call(providerId, token, () -> {
                AiResponse response = operation.invoke();
                if (response != null) onResponseId(response.responseId());
                return response;
            });
        } catch (AiProviderException exception) {
            exception = observeFailure(exception);
            AiRequestLifecycle known = snapshot.responseId() != null || exception.lifecycle().state() == State.UNKNOWN
                ? snapshot : exception.lifecycle();
            if (token.isCancelled()) exception = exception.withCategory(AiErrorCategory.CANCELLED);
            throw exception.withLifecycle(known).redactSecrets(config);
        } catch (RuntimeException exception) {
            throw new AiProviderException(providerId, "Unexpected AI provider failure", exception)
                .withLifecycle(snapshot).redactSecrets(config);
        }
    }

    /** Forwards IDs retained by delegated failures while preserving their original diagnostics. */
    private AiProviderException observeFailure(AiProviderException failure) {
        if (snapshot.responseId() != null || failure.lifecycle().responseId() == null) return failure;
        try {
            onResponseId(failure.lifecycle().responseId());
            return failure;
        } catch (AiProviderException observerFailure) {
            AiProviderException copy = new AiProviderException(failure.providerId(), failure.statusCode(),
                failure.getMessage(), failure.rawResponse(), failure.retryable(), failure.getCause(), failure.category())
                .withLifecycle(failure.lifecycle());
            copy.setStackTrace(failure.getStackTrace());
            for (Throwable suppressed : failure.getSuppressed()) copy.addSuppressed(suppressed);
            copy.addSuppressed(observerFailure);
            return copy;
        }
    }
}
