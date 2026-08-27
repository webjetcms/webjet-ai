package com.webjetcms.ai.provider.local;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.webjetcms.ai.AiInputHandling;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProvider;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.AiStreamListener;
import com.webjetcms.ai.ModelInfo;
import com.webjetcms.ai.TranslationOptions;
import com.webjetcms.ai.provider.local.ApprovedTranslationModelCatalog.TranslationModelDefinition;
import com.webjetcms.ai.provider.local.NativeTranslationRuntimeFactory.Resources;
import com.webjetcms.ai.provider.local.TranslationBundleValidator.PreparedBundle;

/**
 * Processes literal text locally with an approved sequence-to-sequence model bundle.
 *
 * <p>The initial implementation supports M2M100 translation with dynamic language tokens.
 * It remains a normal {@link AiOperation#TEXT} request: source text is supplied through
 * {@link AiRequest#inputText()}, language selection through {@link TranslationOptions}, and the
 * transformed string is available from {@link AiResponse#text()}.</p>
 *
 * <p>Initialization validates and extracts the bundle, then creates one reusable tokenizer
 * and paired ONNX sessions. Instances are thread-safe, should be long-lived, and must be
 * closed to release native resources and remove extracted model files.</p>
 */
public final class LocalTranslationModelProvider implements AiProvider {
    /** Exact identifier used to register this provider with {@code AiClient}. */
    public static final String PROVIDER_ID = "local-translation";

    private final PreparedBundle bundle;
    private final TranslationModelDefinition model;
    private final NativeTranslationTokenizer tokenizer;
    private final NativeSeq2SeqGenerator generator;
    private final String defaultSourceLanguage;
    private final String defaultTargetLanguage;
    private final Integer defaultMaximumOutputTokens;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);

    private LocalTranslationModelProvider(
        PreparedBundle bundle,
        Resources runtime,
        String defaultSourceLanguage,
        String defaultTargetLanguage,
        Integer defaultMaximumOutputTokens
    ) {
        this.bundle = bundle;
        this.model = bundle.manifest().model();
        this.tokenizer = runtime.tokenizer();
        this.generator = runtime.generator();
        this.defaultSourceLanguage = defaultSourceLanguage;
        this.defaultTargetLanguage = defaultTargetLanguage;
        this.defaultMaximumOutputTokens = defaultMaximumOutputTokens;
    }

    /**
     * Opens an approved local translation model bundle with default runtime settings.
     *
     * @param bundle local schema-v1 sequence-to-sequence model ZIP
     * @return an initialized provider that owns its extracted and native resources
     * @throws AiProviderException when validation, extraction, or native initialization fails
     */
    public static LocalTranslationModelProvider open(Path bundle) throws AiProviderException {
        return builder(bundle).build();
    }

    /**
     * Starts configuring a provider for an approved local translation model bundle.
     *
     * @param bundle local schema-v1 sequence-to-sequence model ZIP
     * @return a local translation provider builder
     */
    public static Builder builder(Path bundle) {
        return new Builder(bundle);
    }

    /**
     * Returns the provider identifier used by {@code AiClient}.
     *
     * @return {@value #PROVIDER_ID}
     */
    @Override
    public String id() { return PROVIDER_ID; }

    /**
     * Keeps text input literal because deterministic transformation models do not consume prompts.
     *
     * @param operation requested operation
     * @return literal handling for text, otherwise the protected-prompt default
     */
    @Override
    public AiInputHandling inputHandling(AiOperation operation) {
        return operation == AiOperation.TEXT
            ? AiInputHandling.LITERAL
            : AiInputHandling.PROTECTED_PROMPT;
    }

    /**
     * Returns the translation model loaded from the validated bundle.
     *
     * @param config ignored because local execution requires no connection settings
     * @return a one-element immutable local model catalogue
     * @throws AiProviderException when the provider is closing or closed
     */
    @Override
    public List<ModelInfo> listModels(AiProviderConfig config) throws AiProviderException {
        lifecycleLock.readLock().lock();
        try {
            requireOpen();
            String variant = bundle.manifest().variant().name().replace('-', ' ').toUpperCase(Locale.ROOT);
            return List.of(new ModelInfo(model.canonicalId(), "M2M100 418M (" + variant + ")"));
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * Returns language codes discovered from the validated tokenizer metadata.
     *
     * @return immutable set of supported language codes
     * @throws AiProviderException when the provider is closing or closed
     */
    public Set<String> supportedLanguages() throws AiProviderException {
        lifecycleLock.readLock().lock();
        try {
            requireOpen();
            return tokenizer.supportedLanguages();
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * Transforms literal text using builder-configured source and target languages.
     *
     * @param text literal source text
     * @return transformed text only
     * @throws AiProviderException when defaults are missing, input is invalid, or inference fails
     */
    public String translate(String text) throws AiProviderException {
        AiRequest request = AiRequest.builder().inputText(text).build();
        return execute(request, AiProviderConfig.empty()).text();
    }

    /**
     * Executes a literal non-streaming text transformation.
     *
     * @param request normal text request with optional per-request {@link TranslationOptions}
     * @param config ignored because local execution requires no credentials or endpoint
     * @return a text-only response containing the transformed string
     * @throws AiProviderException when validation or local inference fails
     */
    @Override
    public AiResponse execute(AiRequest request, AiProviderConfig config) throws AiProviderException {
        if (state.get() != State.OPEN) throw closedFailure();
        lifecycleLock.readLock().lock();
        try {
            requireOpen();
            ResolvedRequest resolved = validateAndResolve(request);
            try {
                NativeTranslationTokenizer.Batch source = tokenizer.encode(
                    request.inputText(),
                    resolved.sourceLanguage()
                );
                long targetLanguageTokenId = tokenizer.languageTokenId(resolved.targetLanguage());
                long[] generated = generator.generate(
                    source,
                    targetLanguageTokenId,
                    resolved.maximumOutputTokens()
                );
                return AiResponse.text(tokenizer.decode(generated));
            } catch (Exception exception) {
                throw new AiProviderException(PROVIDER_ID, "Local translation inference failed", exception);
            }
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * Rejects streaming because the local sequence-to-sequence runtime returns completed text.
     *
     * @param request text request
     * @param config ignored local configuration
     * @param listener unused stream listener
     * @return never returns normally
     * @throws AiProviderException always, because streaming is unsupported
     */
    @Override
    public AiResponse stream(
        AiRequest request,
        AiProviderConfig config,
        AiStreamListener listener
    ) throws AiProviderException {
        throw new AiProviderException(PROVIDER_ID, "Streaming is not supported by the local translation model provider");
    }

    /**
     * Waits for active transformations, releases native resources, and removes extracted files.
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
            failure = closeResource(generator, failure);
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

    private ResolvedRequest validateAndResolve(AiRequest request) throws AiProviderException {
        if (request == null) throw invalid("Text request must not be null");
        if (request.operation() != AiOperation.TEXT) throw invalid("Local translation models require a TEXT request");
        if (request.inputText() == null || request.inputText().isBlank()) {
            throw invalid("Local translation model input must not be blank");
        }
        if (hasText(request.instructions()) || hasText(request.userPrompt())) {
            throw invalid("Local translation uses literal inputText and does not accept instructions or userPrompt");
        }
        if (request.inputMedia() != null || request.imageOptions() != null) {
            throw invalid("Local translation models do not accept image or binary input");
        }
        if (request.store()) throw invalid("Local translation model responses cannot be stored by the provider");
        if (hasText(request.model()) && model.aliases().contains(request.model()) == false) {
            throw invalid("Unsupported local translation model: " + request.model());
        }
        TranslationOptions options = request.translationOptions() == null ? new TranslationOptions() : request.translationOptions();
        String source = first(options.sourceLanguage(), defaultSourceLanguage);
        String target = first(options.targetLanguage(), defaultTargetLanguage);
        if (source == null || target == null) {
            throw invalid("Source and target languages must be supplied in TranslationOptions or provider defaults");
        }
        int maximumOutputTokens = options.maximumOutputTokens() != null
            ? options.maximumOutputTokens()
            : defaultMaximumOutputTokens != null
                ? defaultMaximumOutputTokens
                : model.maximumOutputLength();
        if (maximumOutputTokens > model.maximumOutputLength()) {
            throw invalid("Maximum output tokens must not exceed " + model.maximumOutputLength());
        }
        return new ResolvedRequest(source, target, maximumOutputTokens);
    }

    private void requireOpen() throws AiProviderException {
        if (state.get() != State.OPEN) throw closedFailure();
    }

    private static String first(String first, String second) {
        return hasText(first) ? first : hasText(second) ? second : null;
    }

    private static boolean hasText(String value) {
        return value != null && value.isBlank() == false;
    }

    private static AiProviderException closedFailure() {
        return new AiProviderException(PROVIDER_ID, "Local translation model provider is closing or closed");
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

    private record ResolvedRequest(String sourceLanguage, String targetLanguage, int maximumOutputTokens) { }

    /** Builds an eagerly initialized {@link LocalTranslationModelProvider}. */
    public static final class Builder {
        private final Path bundle;
        private Integer intraOpThreads;
        private Path temporaryDirectory;
        private String sourceLanguage;
        private String targetLanguage;
        private Integer maximumOutputTokens;

        private Builder(Path bundle) {
            this.bundle = Objects.requireNonNull(bundle, "bundle");
        }

        /**
         * Sets a default source language that per-request translation options may override.
         *
         * @param language non-blank model language code
         * @return this builder
         */
        public Builder sourceLanguage(String language) {
            this.sourceLanguage = requireText(language, "sourceLanguage");
            return this;
        }

        /**
         * Sets a default target language that per-request translation options may override.
         *
         * @param language non-blank model language code
         * @return this builder
         */
        public Builder targetLanguage(String language) {
            this.targetLanguage = requireText(language, "targetLanguage");
            return this;
        }

        /**
         * Sets a default generated-token limit that per-request translation options may override.
         *
         * @param tokens positive output-token limit
         * @return this builder
         */
        public Builder maximumOutputTokens(int tokens) {
            if (tokens < 1) throw new IllegalArgumentException("maximumOutputTokens must be positive");
            this.maximumOutputTokens = tokens;
            return this;
        }

        /**
         * Sets the ONNX Runtime intra-operation worker count for both model sessions.
         *
         * @param threads positive worker count
         * @return this builder
         */
        public Builder intraOpThreads(int threads) {
            if (threads < 1) throw new IllegalArgumentException("intraOpThreads must be positive");
            this.intraOpThreads = threads;
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
         * Validates, extracts, and initializes the local translation model provider.
         *
         * @return an open provider that owns its extracted and native resources
         * @throws AiProviderException when bundle validation or runtime initialization fails
         */
        public LocalTranslationModelProvider build() throws AiProviderException {
            ApprovedTranslationModelCatalog catalog = ApprovedTranslationModelCatalog.load();
            Path parent = temporaryDirectory == null ? defaultTemporaryDirectory() : temporaryDirectory;
            PreparedBundle prepared = null;
            Resources runtime = null;
            try {
                prepared = new TranslationBundleValidator(catalog)
                    .validateAndExtract(bundle, parent);
                if (maximumOutputTokens != null
                    && maximumOutputTokens > prepared.manifest().model().maximumOutputLength()) {
                    throw new IllegalArgumentException(
                        "maximumOutputTokens must not exceed "
                            + prepared.manifest().model().maximumOutputLength()
                    );
                }
                runtime = NativeTranslationRuntimeFactory.create(prepared, intraOpThreads);
                LocalTranslationModelProvider provider = new LocalTranslationModelProvider(
                    prepared,
                    runtime,
                    sourceLanguage,
                    targetLanguage,
                    maximumOutputTokens
                );
                if (sourceLanguage != null) provider.tokenizer.languageTokenId(sourceLanguage);
                if (targetLanguage != null) provider.tokenizer.languageTokenId(targetLanguage);
                return provider;
            } catch (Throwable failure) {
                cleanupFailedInitialization(prepared, runtime, failure);
                if (failure instanceof Error error) throw error;
                throw new AiProviderException(
                    PROVIDER_ID,
                    "Could not initialize the local translation model provider: " + failure.getMessage(),
                    failure
                );
            }
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
            return value;
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
                closeAfterFailure(runtime.generator(), failure);
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
