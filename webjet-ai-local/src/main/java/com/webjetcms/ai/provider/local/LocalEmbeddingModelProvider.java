package com.webjetcms.ai.provider.local;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.webjetcms.ai.AiProvider;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.AiStreamListener;
import com.webjetcms.ai.EmbeddingOptions;
import com.webjetcms.ai.EmbeddingRequest;
import com.webjetcms.ai.EmbeddingResponse;
import com.webjetcms.ai.EmbeddingVector;
import com.webjetcms.ai.ModelInfo;
import com.webjetcms.ai.TokenUsage;
import com.webjetcms.ai.provider.local.ApprovedEmbeddingModelCatalog.EmbeddingModelDefinition;
import com.webjetcms.ai.provider.local.EmbeddingBundleValidator.PreparedBundle;
import com.webjetcms.ai.provider.local.NativeEmbeddingRuntimeFactory.Resources;

/**
 * Generates embeddings locally from an approved WebJET AI schema-v1 model bundle.
 *
 * <p>Initialization eagerly validates and extracts the supplied ZIP, then creates one
 * reusable tokenizer and ONNX session. Instances are thread-safe, should be long-lived,
 * and must be closed to release native resources and remove extracted model files.</p>
 */
public final class LocalEmbeddingModelProvider implements AiProvider {
    /** Exact identifier used to register the local provider with {@code AiClient}. */
    public static final String PROVIDER_ID = "local-embedding";

    private static final int DEFAULT_MAXIMUM_BATCH_SIZE = 8;

    private final PreparedBundle bundle;
    private final EmbeddingModelDefinition model;
    private final NativeEmbeddingTokenizer tokenizer;
    private final NativeEmbeddingInferenceSession inference;
    private final int maximumBatchSize;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);

    private LocalEmbeddingModelProvider(
        PreparedBundle bundle,
        Resources runtime,
        int maximumBatchSize
    ) {
        this.bundle = bundle;
        this.model = bundle.manifest().model();
        this.tokenizer = runtime.tokenizer();
        this.inference = runtime.inference();
        this.maximumBatchSize = maximumBatchSize;
    }

    /**
     * Opens an approved local embedding model bundle using default runtime settings.
     *
     * @param bundle local schema-v1 model ZIP
     * @return an initialized provider that owns its extracted and native resources
     * @throws AiProviderException when validation, extraction, or native initialization fails
     */
    public static LocalEmbeddingModelProvider open(Path bundle) throws AiProviderException {
        return builder(bundle).build();
    }

    /**
     * Starts configuring a provider for an approved local embedding model bundle.
     *
     * @param bundle local schema-v1 model ZIP
     * @return a local provider builder
     */
    public static Builder builder(Path bundle) {
        return new Builder(bundle);
    }

    @Override
    public String id() { return PROVIDER_ID; }

    /**
     * Returns the single model loaded from the validated bundle.
     *
     * @param config ignored because local embedding model discovery requires no connection settings
     * @return a one-element immutable local embedding model catalogue
     * @throws AiProviderException when the provider is closing or closed
     */
    @Override
    public List<ModelInfo> listModels(AiProviderConfig config) throws AiProviderException {
        lifecycleLock.readLock().lock();
        try {
            requireOpen();
            String variant = bundle.manifest().variant().name()
                .replace('-', ' ')
                .toUpperCase(Locale.ROOT);
            return List.of(new ModelInfo(model.canonicalId(), model.displayName() + " (" + variant + ")"));
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * Generates embeddings without requiring an {@link AiProviderConfig} instance.
     *
     * @param request text inputs and optional dimensions
     * @return normalized vectors in input order with processed-token usage
     * @throws AiProviderException when validation or local inference fails
     */
    public EmbeddingResponse embed(EmbeddingRequest request) throws AiProviderException {
        return embed(request, AiProviderConfig.empty());
    }

    /**
     * Generates local embeddings; provider connection settings are ignored.
     *
     * @param request text inputs and optional dimensions
     * @param config ignored because local inference uses no credentials or endpoint
     * @return normalized vectors in input order with processed-token usage
     * @throws AiProviderException when validation or local inference fails
     */
    @Override
    public EmbeddingResponse embed(EmbeddingRequest request, AiProviderConfig config)
        throws AiProviderException {
        if (state.get() != State.OPEN) throw closedFailure();
        lifecycleLock.readLock().lock();
        try {
            requireOpen();
            return embedOpen(request);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public AiResponse execute(AiRequest request, AiProviderConfig config) throws AiProviderException {
        throw new AiProviderException(PROVIDER_ID, "Generation is not supported by the local embedding model provider");
    }

    @Override
    public AiResponse stream(
        AiRequest request,
        AiProviderConfig config,
        AiStreamListener listener
    ) throws AiProviderException {
        throw new AiProviderException(PROVIDER_ID, "Streaming is not supported by the local embedding model provider");
    }

    /**
     * Waits for active embeddings, closes native resources, and removes extracted files.
     *
     * <p>Closing is idempotent. Every cleanup action is attempted, with secondary failures
     * attached to the first failure as suppressed exceptions.</p>
     *
     * @throws Exception when a provider-owned resource or extracted file cannot be released
     */
    @Override
    public void close() throws Exception {
        if (state.compareAndSet(State.OPEN, State.CLOSING) == false) {
            if (state.get() == State.CLOSED) return;
            lifecycleLock.writeLock().lock();
            lifecycleLock.writeLock().unlock();
            return;
        }

        lifecycleLock.writeLock().lock();
        Throwable failure = null;
        try {
            failure = closeResource(inference, failure);
            failure = closeResource(tokenizer, failure);
            try {
                DirectoryCleaner.delete(bundle.directory());
            } catch (Throwable exception) {
                failure = appendFailure(failure, exception);
            }
        } finally {
            state.set(State.CLOSED);
            lifecycleLock.writeLock().unlock();
        }
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    private EmbeddingResponse embedOpen(EmbeddingRequest request) throws AiProviderException {
        validateRequest(request);
        List<EmbeddingVector> embeddings = new ArrayList<>(request.inputs().size());
        long inputTokens = 0;

        try {
            for (int start = 0; start < request.inputs().size(); start += maximumBatchSize) {
                int end = Math.min(start + maximumBatchSize, request.inputs().size());
                List<String> preparedInputs = request.inputs().subList(start, end).stream()
                    .map(input -> model.inputPreparation().prepare(
                        bundle.manifest(),
                        input,
                        request.options().inputType()
                    ))
                    .toList();
                NativeEmbeddingTokenizer.Batch tokens = tokenizer.encode(preparedInputs);
                float[][][] hiddenState = inference.run(tokens);
                if (hiddenState.length != tokens.batchSize()) {
                    throw new IllegalStateException("ONNX output batch size does not match tokenizer output");
                }
                for (int row = 0; row < tokens.batchSize(); row++) {
                    PoolingResult pooled = poolAndNormalize(hiddenState[row], tokens.attentionMask()[row]);
                    embeddings.add(new EmbeddingVector(pooled.vector()));
                    inputTokens = Math.addExact(inputTokens, pooled.processedTokens());
                }
            }
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException(PROVIDER_ID, "Local embedding inference failed", exception);
        }

        return new EmbeddingResponse(
            embeddings,
            new TokenUsage(inputTokens, 0, inputTokens, java.util.Map.of())
        );
    }

    private void validateRequest(EmbeddingRequest request) throws AiProviderException {
        if (request == null) throw invalid("Embedding request must not be null");
        if (request.inputs().isEmpty()) throw invalid("Embedding inputs must not be empty");
        for (String input : request.inputs()) {
            if (input == null || input.isBlank()) throw invalid("Embedding inputs must not be blank");
        }
        if (request.model() != null && request.model().isBlank() == false
            && model.aliases().contains(request.model()) == false) {
            throw invalid("Unsupported local embedding model: " + request.model());
        }
        EmbeddingOptions options = request.options();
        if (options.dimensions() != null && options.dimensions() != model.dimensions()) {
            throw invalid("Local embedding dimensions must be " + model.dimensions());
        }
    }

    private PoolingResult poolAndNormalize(float[][] hiddenState, long[] attentionMask)
        throws AiProviderException {
        if (hiddenState.length != attentionMask.length || hiddenState.length == 0) {
            throw invalid("ONNX output sequence does not match the attention mask");
        }
        double[] pooled = new double[model.dimensions()];
        long processedTokens = 0;
        for (int token = 0; token < hiddenState.length; token++) {
            float[] tokenVector = hiddenState[token];
            if (tokenVector.length != model.dimensions()) {
                throw invalid("ONNX output embedding dimension is invalid");
            }
            for (float value : tokenVector) {
                if (Float.isFinite(value) == false) throw invalid("ONNX output contains a non-finite value");
            }
            long mask = attentionMask[token];
            if (mask != 0 && mask != 1) throw invalid("Attention mask must contain only zero or one");
            if (mask == 0) continue;
            processedTokens++;
            for (int dimension = 0; dimension < pooled.length; dimension++) {
                pooled[dimension] += tokenVector[dimension];
            }
        }
        if (processedTokens == 0) throw invalid("Attention mask does not contain a processed token");

        double squaredNorm = 0;
        for (int dimension = 0; dimension < pooled.length; dimension++) {
            pooled[dimension] /= processedTokens;
            squaredNorm += pooled[dimension] * pooled[dimension];
        }
        double norm = Math.sqrt(squaredNorm);
        if (Double.isFinite(norm) == false || norm <= 0) {
            throw invalid("Local embedding has an invalid L2 norm");
        }

        float[] normalized = new float[pooled.length];
        for (int dimension = 0; dimension < pooled.length; dimension++) {
            normalized[dimension] = (float) (pooled[dimension] / norm);
            if (Float.isFinite(normalized[dimension]) == false) {
                throw invalid("Local embedding contains a non-finite normalized value");
            }
        }
        return new PoolingResult(normalized, processedTokens);
    }

    private void requireOpen() throws AiProviderException {
        if (state.get() != State.OPEN) throw closedFailure();
    }

    boolean isOpen() { return state.get() == State.OPEN; }

    private static AiProviderException closedFailure() {
        return new AiProviderException(PROVIDER_ID, "Local embedding model provider is closing or closed");
    }

    private static AiProviderException invalid(String message) {
        return new AiProviderException(PROVIDER_ID, message);
    }

    private static Throwable closeResource(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
            return failure;
        } catch (Throwable exception) {
            return appendFailure(failure, exception);
        }
    }

    private static Throwable appendFailure(Throwable failure, Throwable addition) {
        if (failure == null) return addition;
        if (failure != addition) failure.addSuppressed(addition);
        return failure;
    }

    private enum State { OPEN, CLOSING, CLOSED }

    private record PoolingResult(float[] vector, long processedTokens) { }

    /** Builds an eagerly initialized {@link LocalEmbeddingModelProvider}. */
    public static final class Builder {
        private final Path bundle;
        private Integer intraOpThreads;
        private int maximumBatchSize = DEFAULT_MAXIMUM_BATCH_SIZE;
        private Path temporaryDirectory;

        private Builder(Path bundle) {
            this.bundle = Objects.requireNonNull(bundle, "bundle");
        }

        /**
         * Sets the ONNX Runtime intra-operation worker count.
         *
         * @param threads positive worker count
         * @return this builder
         */
        public Builder intraOpThreads(int threads) {
            if (threads <= 0) throw new IllegalArgumentException("intraOpThreads must be positive");
            this.intraOpThreads = threads;
            return this;
        }

        /**
         * Sets the maximum number of inputs tokenized and inferred together.
         *
         * @param size positive batch size
         * @return this builder
         */
        public Builder maximumBatchSize(int size) {
            if (size <= 0) throw new IllegalArgumentException("maximumBatchSize must be positive");
            this.maximumBatchSize = size;
            return this;
        }

        /**
         * Selects an existing writable parent for provider-owned extracted files.
         *
         * @param parent existing temporary directory parent
         * @return this builder
         */
        public Builder temporaryDirectory(Path parent) {
            this.temporaryDirectory = Objects.requireNonNull(parent, "parent");
            return this;
        }

        /**
         * Validates, extracts, and initializes the local embedding model provider.
         *
         * @return an open provider that owns its extracted and native resources
         * @throws AiProviderException when bundle validation or runtime initialization fails
         */
        public LocalEmbeddingModelProvider build() throws AiProviderException {
            ApprovedEmbeddingModelCatalog catalog = ApprovedEmbeddingModelCatalog.load();
            Path parent = temporaryDirectory == null ? defaultTemporaryDirectory() : temporaryDirectory;
            PreparedBundle prepared = null;
            Resources runtime = null;
            try {
                prepared = new EmbeddingBundleValidator(catalog)
                    .validateAndExtract(bundle, parent);
                runtime = NativeEmbeddingRuntimeFactory.create(prepared, intraOpThreads);
                return new LocalEmbeddingModelProvider(prepared, runtime, maximumBatchSize);
            } catch (Throwable failure) {
                cleanupFailedInitialization(prepared, runtime, failure);
                if (failure instanceof Error error) throw error;
                throw new AiProviderException(
                    PROVIDER_ID,
                    "Could not initialize the local embedding model provider: " + failure.getMessage(),
                    failure
                );
            }
        }

        private static Path defaultTemporaryDirectory() {
            String value = System.getProperty("java.io.tmpdir");
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("java.io.tmpdir is not configured");
            }
            return Path.of(value);
        }

        private static void cleanupFailedInitialization(
            PreparedBundle prepared,
            Resources runtime,
            Throwable failure
        ) {
            if (runtime != null) {
                closeAfterFailure(runtime.inference(), failure);
                closeAfterFailure(runtime.tokenizer(), failure);
            }
            if (prepared != null) {
                try {
                    DirectoryCleaner.delete(prepared.directory());
                } catch (Throwable cleanupFailure) {
                    if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
                }
            }
        }

        private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
            try {
                resource.close();
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
        }
    }
}
