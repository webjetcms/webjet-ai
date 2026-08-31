package com.webjetcms.ai.provider.local;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.webjetcms.ai.AiProviderException;

/** Coordinates concurrent provider calls and ordered resource cleanup. */
final class LocalProviderLifecycle implements AutoCloseable {
    private final String providerId;
    private final String closedMessage;
    private final Path directory;
    private final AutoCloseable[] closeOrder;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final CloseCoordinator closeCoordinator = new CloseCoordinator();
    LocalProviderLifecycle(String providerId, String closedMessage, Path directory, AutoCloseable... closeOrder) {
        this.providerId = providerId;
        this.closedMessage = closedMessage;
        this.directory = directory;
        this.closeOrder = closeOrder.clone();
    }
    <T> T read(Operation<T> operation) throws AiProviderException {
        lock.readLock().lock();
        try {
            requireOpen();
            return operation.run();
        } finally {
            lock.readLock().unlock();
        }
    }
    void requireOpen() throws AiProviderException {
        if (closeCoordinator.isOpen() == false) throw new AiProviderException(providerId, closedMessage);
    }
    boolean isOpen() { return closeCoordinator.isOpen(); }
    @Override
    public void close() throws Exception {
        if (closeCoordinator.claimOrAwaitCompletion() == false) return;
        lock.writeLock().lock();
        try {
            rethrow(cleanup(directory, null, closeOrder));
        } finally {
            closeCoordinator.complete();
            lock.writeLock().unlock();
        }
    }
    static Path temporaryDirectory(Path configured) {
        if (configured != null) return configured;
        String value = System.getProperty("java.io.tmpdir");
        if (value == null || value.isBlank()) throw new IllegalStateException("java.io.tmpdir is not configured");
        return Path.of(value);
    }
    static void cleanupAfterFailure(Path directory, Throwable failure, AutoCloseable... closeOrder) {
        cleanup(directory, failure, closeOrder);
    }
    static void closeResources(AutoCloseable... closeOrder) throws Exception {
        rethrow(cleanup(null, null, closeOrder));
    }
    static <S extends AutoCloseable, T> T transfer(S resource, ResourceInitializer<S, T> initializer)
        throws Exception {
        try {
            return initializer.initialize(resource);
        } catch (Exception | Error failure) {
            cleanupAfterFailure(null, failure, resource);
            throw failure;
        }
    }
    private static Throwable cleanup(Path directory, Throwable failure, AutoCloseable... closeOrder) {
        for (AutoCloseable resource : closeOrder) {
            if (resource != null) failure = close(resource, failure);
        }
        return directory == null ? failure : close(() -> DirectoryCleaner.delete(directory), failure);
    }
    private static Throwable close(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
            return failure;
        } catch (Throwable exception) {
            if (failure == null) return exception;
            if (failure != exception) failure.addSuppressed(exception);
            return failure;
        }
    }
    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
    }
    @FunctionalInterface
    interface Operation<T> { T run() throws AiProviderException; }
    @FunctionalInterface
    interface ResourceInitializer<S, T> { T initialize(S resource) throws Exception; }
    static final class CloseCoordinator {
        private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
        private final CountDownLatch completion = new CountDownLatch(1);
        private volatile Thread owner;

        boolean claimOrAwaitCompletion() {
            if (state.compareAndSet(State.OPEN, State.CLOSING)) {
                owner = Thread.currentThread();
                return true;
            }
            if (owner == Thread.currentThread()) return false;
            boolean interrupted = false;
            while (true) {
                try {
                    completion.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return false;
        }

        boolean isOpen() { return state.get() == State.OPEN; }

        void complete() {
            state.set(State.CLOSED);
            owner = null;
            completion.countDown();
        }

        private enum State { OPEN, CLOSING, CLOSED }
    }
}
/** Common bundle and native-runtime settings for local provider builders. */
abstract class LocalProviderBuilder<B extends LocalProviderBuilder<B>> {
    private final Path bundle;
    private Integer intraOpThreads;
    private Path temporaryDirectory;
    LocalProviderBuilder(Path bundle) { this.bundle = Objects.requireNonNull(bundle, "bundle"); }
    abstract B self();
    /**
     * Sets the ONNX Runtime intra-operation worker count.
     *
     * @param threads positive worker count
     * @return this builder
     */
    public B intraOpThreads(int threads) {
        if (threads <= 0) throw new IllegalArgumentException("intraOpThreads must be positive");
        intraOpThreads = threads;
        return self();
    }
    /**
     * Selects an existing writable parent for provider-owned extracted files.
     *
     * @param parent existing temporary directory parent
     * @return this builder
     */
    public B temporaryDirectory(Path parent) {
        temporaryDirectory = Objects.requireNonNull(parent, "parent");
        return self();
    }
    final Path bundle() { return bundle; }
    final Integer intraOpThreads() { return intraOpThreads; }
    final Path temporaryDirectory() { return LocalProviderLifecycle.temporaryDirectory(temporaryDirectory); }
}
