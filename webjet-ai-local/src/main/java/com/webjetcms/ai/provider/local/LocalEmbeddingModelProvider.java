package com.webjetcms.ai.provider.local;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.webjetcms.ai.AiProvider;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.AiStreamListener;
import com.webjetcms.ai.EmbeddingInputType;
import com.webjetcms.ai.EmbeddingOptions;
import com.webjetcms.ai.EmbeddingRequest;
import com.webjetcms.ai.EmbeddingResponse;
import com.webjetcms.ai.EmbeddingVector;
import com.webjetcms.ai.ModelInfo;
import com.webjetcms.ai.TokenUsage;
import com.webjetcms.ai.provider.local.ApprovedEmbeddingModelCatalog.EmbeddingModelDefinition;
import com.webjetcms.ai.provider.local.NativeEmbeddingInferenceSession.PoolingResult;
import com.webjetcms.ai.provider.local.NativeEmbeddingRuntimeFactory.Resources;
import com.webjetcms.ai.provider.local.VerifiedBundleExtractor.Result;

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

    private final EmbeddingModelDefinition model;
    private final ModelInfo modelInfo;
    private final NativeEmbeddingTokenizer tokenizer;
    private final NativeEmbeddingInferenceSession inference;
    private final int maximumBatchSize;
    private final LocalProviderLifecycle lifecycle;

    private LocalEmbeddingModelProvider(Result<EmbeddingBundleManifest> bundle,
        Resources runtime, int maximumBatchSize) {
        this.model = bundle.manifest().model();
        String variant = bundle.manifest().variant().name().replace('-', ' ').toUpperCase(Locale.ROOT);
        this.modelInfo = new ModelInfo(model.canonicalId(), model.displayName() + " (" + variant + ")");
        this.tokenizer = runtime.tokenizer();
        this.inference = runtime.inference();
        this.maximumBatchSize = maximumBatchSize;
        this.lifecycle = new LocalProviderLifecycle(
            PROVIDER_ID, "Local embedding model provider is closing or closed",
            bundle.directory(), inference, tokenizer);
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
        return LocalProviderBoundary.invoke(config,
            () -> lifecycle.read(() -> List.of(modelInfo)));
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
        return LocalProviderBoundary.invoke(config, () -> {
            lifecycle.requireOpen();
            return lifecycle.read(() -> embedOpen(request));
        });
    }

    @Override
    public AiResponse execute(AiRequest request, AiProviderConfig config) throws AiProviderException {
        return LocalProviderBoundary.invoke(config, () -> {
            throw new AiProviderException(PROVIDER_ID,
                "Generation is not supported by the local embedding model provider");
        });
    }

    @Override
    public AiResponse stream(
        AiRequest request,
        AiProviderConfig config,
        AiStreamListener listener
    ) throws AiProviderException {
        return LocalProviderBoundary.invoke(config, () -> {
            throw new AiProviderException(PROVIDER_ID,
                "Streaming is not supported by the local embedding model provider");
        });
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
    public void close() throws Exception { lifecycle.close(); }

    private EmbeddingResponse embedOpen(EmbeddingRequest request) throws AiProviderException {
        validateRequest(request);
        List<EmbeddingVector> embeddings = new ArrayList<>(request.inputs().size());
        long inputTokens = 0;
        String prefix = request.options().inputType() == EmbeddingInputType.QUERY
            ? model.queryPrefix() : model.documentPrefix();

        try {
            for (int start = 0; start < request.inputs().size(); start += maximumBatchSize) {
                int end = Math.min(start + maximumBatchSize, request.inputs().size());
                List<String> preparedInputs = request.inputs().subList(start, end).stream()
                    .map(prefix::concat).toList();
                NativeEmbeddingTokenizer.Batch tokens = tokenizer.encode(preparedInputs);
                for (PoolingResult pooled : inference.run(tokens)) {
                    embeddings.add(new EmbeddingVector(pooled.vector()));
                    inputTokens = Math.addExact(inputTokens, pooled.processedTokens());
                }
            }
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException(PROVIDER_ID, "Local embedding inference failed", exception);
        }

        return new EmbeddingResponse(embeddings, new TokenUsage(inputTokens, 0, inputTokens, java.util.Map.of()));
    }

    private void validateRequest(EmbeddingRequest request) throws AiProviderException {
        if (request == null) throw invalid("Embedding request must not be null");
        if (request.inputs().isEmpty()) throw invalid("Embedding inputs must not be empty");
        if (request.inputs().stream().anyMatch(String::isBlank))
            throw invalid("Embedding inputs must not be blank");
        if (request.model() != null && request.model().isBlank() == false
            && model.aliases().contains(request.model()) == false) {
            throw invalid("Unsupported local embedding model: " + request.model());
        }
        EmbeddingOptions options = request.options();
        if (options.dimensions() != null && options.dimensions() != model.dimensions()) {
            throw invalid("Local embedding dimensions must be " + model.dimensions());
        }
    }

    boolean isOpen() { return lifecycle.isOpen(); }

    private static AiProviderException invalid(String message) {
        return new AiProviderException(PROVIDER_ID, message);
    }

    /** Builds an eagerly initialized {@link LocalEmbeddingModelProvider}. */
    public static final class Builder extends LocalProviderBuilder<Builder> {
        private int maximumBatchSize = DEFAULT_MAXIMUM_BATCH_SIZE;

        private Builder(Path bundle) { super(bundle); }

        @Override
        Builder self() { return this; }

        @Override
        public Builder intraOpThreads(int threads) { return super.intraOpThreads(threads); }

        @Override
        public Builder temporaryDirectory(Path parent) { return super.temporaryDirectory(parent); }

        /**
         * Sets the maximum number of inputs tokenized and inferred together.
         *
         * @param size positive batch size
         * @return this builder
         */
        public Builder maximumBatchSize(int size) {
            if (size <= 0) throw new IllegalArgumentException("maximumBatchSize must be positive");
            maximumBatchSize = size;
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
            Path temporaryParent = temporaryDirectory();
            Result<EmbeddingBundleManifest> prepared = null;
            Resources runtime = null;
            try {
                prepared = new EmbeddingBundleValidator(catalog)
                    .validateAndExtract(bundle(), temporaryParent);
                runtime = NativeEmbeddingRuntimeFactory.create(prepared, intraOpThreads());
                return new LocalEmbeddingModelProvider(prepared, runtime, maximumBatchSize);
            } catch (Throwable failure) {
                LocalProviderLifecycle.cleanupAfterFailure(
                    prepared == null ? null : prepared.directory(), failure,
                    runtime == null ? null : runtime.inference(),
                    runtime == null ? null : runtime.tokenizer());
                if (failure instanceof Error) throw (Error) failure;
                throw new AiProviderException(
                    PROVIDER_ID,
                    "Could not initialize the local embedding model provider: " + failure.getMessage(),
                    failure
                );
            }
        }
    }
}
