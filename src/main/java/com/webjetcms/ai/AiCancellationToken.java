package com.webjetcms.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe, one-way cancellation signal for a single call.
 *
 * <p>Create a fresh token before submitting work and call {@link #cancel()} from the
 * thread handling Stop or cleanup. Tokens do not own threads or provider resources.
 * A cancelled token cannot be reset. Reusing one token intentionally cancels every
 * call associated with it.</p>
 *
 * <p>Built-in HTTP providers abort the individual request. Existing custom and local
 * providers may only observe cancellation before and after their blocking work.
 * Cancellation cannot undo provider-side work or retract an already returned response.
 * A callback already dispatched to application code may finish.</p>
 */
public final class AiCancellationToken {

    private static final AiCancellationToken NONE = new AiCancellationToken(false);

    private final boolean cancellable;
    private final Set<Action> actions = new LinkedHashSet<>();
    private volatile boolean cancelled;

    /** Creates a fresh, independently cancellable token. */
    public AiCancellationToken() {
        this(true);
    }

    private AiCancellationToken(boolean cancellable) {
        this.cancellable = cancellable;
    }

    /**
     * Returns a shared token that cannot be cancelled, for compatibility overloads.
     *
     * @return the immutable-in-behavior no-cancellation token
     */
    public static AiCancellationToken none() {
        return NONE;
    }

    /**
     * Signals cancellation and invokes registered actions on the calling thread.
     * Runtime exceptions from actions are ignored so remaining actions still run.
     *
     * @return {@code true} only for the first cancellation of a cancellable token
     */
    public boolean cancel() {
        if (cancellable == false) return false;
        ArrayList<Action> pending;
        synchronized (actions) {
            if (cancelled) return false;
            cancelled = true;
            pending = new ArrayList<>(actions);
            actions.clear();
        }
        pending.forEach(Action::invoke);
        return true;
    }

    /**
     * Returns whether cancellation has been requested.
     *
     * @return {@code true} after this token has been cancelled
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Fails with a non-retryable cancellation when this token has been cancelled.
     *
     * @param providerId provider associated with the cancelled call
     * @throws AiProviderException with category {@link AiErrorCategory#CANCELLED} on cancellation
     */
    public void throwIfCancelled(String providerId) throws AiProviderException {
        if (cancelled) {
            throw new AiProviderException(
                providerId, -1, "AI request cancelled", null, false, null, AiErrorCategory.CANCELLED
            );
        }
    }

    /**
     * Registers a short, non-blocking action such as aborting one HTTP request.
     * If cancellation has already happened, the action runs immediately. Close the
     * registration in {@code finally} to release references when the call completes.
     * Actions run at most once; runtime exceptions from actions are ignored.
     *
     * @param action cancellation action; must not close shared provider resources
     * @return registration that can remove the action before cancellation claims it
     */
    public Registration onCancel(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (cancellable == false) return () -> { };
        Action registration = new Action(action);
        synchronized (actions) {
            if (cancelled == false) {
                actions.add(registration);
                return registration;
            }
        }
        registration.invoke();
        return registration;
    }

    /** Registration whose lifetime must be limited to the call's active resources. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        /** Removes an action unless it has already been claimed for cancellation. */
        @Override
        void close();
    }

    private final class Action implements Registration {
        private final AtomicReference<Runnable> action;

        private Action(Runnable action) {
            this.action = new AtomicReference<>(action);
        }

        private void invoke() {
            Runnable pending = action.getAndSet(null);
            if (pending != null) {
                try {
                    pending.run();
                } catch (RuntimeException ignored) {
                    // One failing hook must not prevent the remaining abort actions.
                }
            }
        }

        @Override
        public void close() {
            action.set(null);
            synchronized (actions) {
                actions.remove(this);
            }
        }
    }
}
