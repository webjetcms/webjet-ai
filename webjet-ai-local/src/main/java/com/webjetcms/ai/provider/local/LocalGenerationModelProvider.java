package com.webjetcms.ai.provider.local;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.webjetcms.ai.AiInputHandling;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProvider;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.AiStreamListener;
import com.webjetcms.ai.ModelInfo;
import com.webjetcms.ai.provider.local.ApprovedSeq2SeqModelCatalog.ModelDefinition;
import com.webjetcms.ai.provider.local.NativeSeq2SeqRuntimeFactory.Resources;
import com.webjetcms.ai.provider.local.Seq2SeqBundleValidator.PreparedBundle;
import com.webjetcms.ai.security.PromptInjectionDefense;
import com.webjetcms.ai.security.PromptInjectionDefense.UntrustedSource;

/**
 * Generates text locally with an approved instruction-tuned encoder-decoder model bundle.
 *
 * <p>The initial model is FLAN-T5 Small. It accepts the normal {@link AiOperation#TEXT}
 * request fields and returns generated text through {@link AiResponse#text()}. The provider
 * rejects text detected as prompt injection, validates and extracts its bundle eagerly, and
 * performs greedy CPU inference without network access.</p>
 *
 * <p>Instances are thread-safe, should be reused, and must be closed to release native
 * resources and remove extracted model files.</p>
 */
public final class LocalGenerationModelProvider extends Seq2SeqProvider<NativeGenerationTokenizer> {
    /** Exact identifier used to register this provider with {@code AiClient}. */
    public static final String PROVIDER_ID = "local-generation";

    private final int maximumOutputTokens;

    private LocalGenerationModelProvider(PreparedBundle bundle,
        Resources<NativeGenerationTokenizer> runtime, int maximumOutputTokens) {
        super(PROVIDER_ID, "Local generation model provider is closing or closed", bundle, runtime);
        this.maximumOutputTokens = maximumOutputTokens;
    }

    /** Opens an approved local text-generation bundle with default runtime settings.
     * @param bundle local schema-v1 encoder-decoder model ZIP
     * @return initialized provider owning its extracted and native resources
     * @throws AiProviderException when validation, extraction, or initialization fails
     */
    public static LocalGenerationModelProvider open(Path bundle) throws AiProviderException {
        return builder(bundle).build();
    }

    /** Starts configuring an approved local text-generation bundle.
     * @param bundle local schema-v1 encoder-decoder model ZIP
     * @return local generation provider builder
     */
    public static Builder builder(Path bundle) { return new Builder(bundle); }

    /** Returns the provider identifier used by {@code AiClient}.
     * @return {@value #PROVIDER_ID}
     */
    @Override
    public String id() { return PROVIDER_ID; }

    /** Generates text from a normal string prompt without provider connection settings.
     * @param prompt non-blank end-user prompt
     * @return generated string
     * @throws AiProviderException when validation or local inference fails
     */
    public String generate(String prompt) throws AiProviderException {
        return execute(AiRequest.builder().userPrompt(prompt).build(), AiProviderConfig.empty()).text();
    }

    /** Executes protected, non-streaming local text generation.
     * @param request normal text request containing instructions, input text, or a user prompt
     * @param config ignored because local execution requires no credentials or endpoint
     * @return text-only response containing the generated string
     * @throws AiProviderException when validation or local inference fails
     */
    @Override
    public AiResponse execute(AiRequest request, AiProviderConfig config) throws AiProviderException {
        return read(() -> {
            validate(request);
            try {
                NativeTokenizerBatch source = tokenizer.encode(prompt(request));
                long[] generated = generator.generate(
                    source.inputIds(), source.attentionMask(),
                    new long[]{model.decoderStartTokenId()}, maximumOutputTokens);
                return AiResponse.text(tokenizer.decode(generated));
            } catch (Exception exception) {
                throw new AiProviderException(PROVIDER_ID, "Local text generation failed", exception);
            }
        });
    }

    /** Rejects streaming because the local runtime returns completed text.
     * @param request text request
     * @param config ignored local configuration
     * @param listener unused stream listener
     * @return never returns normally
     * @throws AiProviderException always, because streaming is unsupported
     */
    @Override
    public AiResponse stream(AiRequest request, AiProviderConfig config, AiStreamListener listener)
        throws AiProviderException {
        throw new AiProviderException(PROVIDER_ID, "Streaming is not supported by the local generation provider");
    }

    private void validate(AiRequest request) throws AiProviderException {
        if (request == null) throw invalid("Text request must not be null");
        if (request.operation() != AiOperation.TEXT) throw invalid("Local generation models require a TEXT request");
        if (PromptInjectionDefense.hasTaskInstructions(request.instructions()) == false
            && PromptInjectionDefense.hasUntrustedText(request.inputText(), UntrustedSource.INPUT_TEXT) == false
            && PromptInjectionDefense.hasUntrustedText(request.userPrompt(), UntrustedSource.USER_PROMPT) == false) {
            throw invalid("Local generation requires non-blank instructions, inputText, or userPrompt");
        }
        if (request.suspiciousSources().isEmpty() == false)
            throw invalid("Local generation rejects text detected as prompt injection");
        if (request.inputMedia() != null || request.imageOptions() != null)
            throw invalid("Local generation models do not accept image or binary input");
        if (request.translationOptions() != null)
            throw invalid("Local generation models do not accept translation options");
        if (request.store()) throw invalid("Local generation responses cannot be stored by the provider");
        if (hasText(request.model()) && model.aliases().contains(request.model()) == false)
            throw invalid("Unsupported local generation model: " + request.model());
    }

    private static String prompt(AiRequest request) {
        StringBuilder prompt = new StringBuilder();
        append(prompt, PromptInjectionDefense.stripUnsafeCharacters(request.instructions()));
        append(prompt, PromptInjectionDefense.stripUnsafeCharacters(request.inputText()));
        append(prompt, PromptInjectionDefense.stripUnsafeCharacters(request.userPrompt()));
        return prompt.toString();
    }

    private static void append(StringBuilder target, String value) {
        if (hasText(value) == false) return;
        if (target.length() > 0) target.append("\n\n");
        target.append(value);
    }

    private static boolean hasText(String value) { return value != null && value.isBlank() == false; }

    private static AiProviderException invalid(String message) { return new AiProviderException(PROVIDER_ID, message); }

    /** Builds an eagerly initialized {@link LocalGenerationModelProvider}. */
    public static final class Builder extends Seq2SeqProviderBuilder<Builder> {
        private Builder(Path bundle) { super(bundle); }

        @Override Builder self() { return this; }
        @Override public Builder intraOpThreads(int threads) { return super.intraOpThreads(threads); }
        @Override public Builder temporaryDirectory(Path parent) { return super.temporaryDirectory(parent); }
        @Override public Builder maximumOutputTokens(int tokens) { return super.maximumOutputTokens(tokens); }

        /** Validates, extracts, and initializes the local generation provider.
         * @return open provider owning its extracted and native resources
         * @throws AiProviderException when bundle validation or initialization fails
         */
        public LocalGenerationModelProvider build() throws AiProviderException {
            return initialize(
                ApprovedSeq2SeqModelCatalog.GENERATION_RESOURCE, "generation-model",
                "webjet-ai-local-generation-", PROVIDER_ID,
                "Could not initialize the local generation provider: ",
                prepared -> outputLimit(prepared, true),
                NativeSeq2SeqRuntimeFactory::createGeneration,
                LocalGenerationModelProvider::new
            );
        }
    }
}

/** Shared ownership and model discovery for local encoder-decoder providers. */
abstract class Seq2SeqProvider<T extends AutoCloseable> implements AiProvider {
    final PreparedBundle bundle;
    final ModelDefinition model;
    final T tokenizer;
    final NativeSeq2SeqGenerator generator;
    private final LocalProviderLifecycle lifecycle;

    Seq2SeqProvider(String providerId, String closedMessage,
        PreparedBundle bundle, Resources<T> resources) {
        this.bundle = bundle;
        model = bundle.manifest().model();
        tokenizer = resources.tokenizer();
        generator = resources.generator();
        lifecycle = new LocalProviderLifecycle(
            providerId, closedMessage, bundle.directory(), generator, tokenizer);
    }

    final <R> R read(LocalProviderLifecycle.Operation<R> operation) throws AiProviderException {
        lifecycle.requireOpen();
        return lifecycle.read(operation);
    }

    @Override
    public final List<ModelInfo> listModels(AiProviderConfig config) throws AiProviderException {
        return read(() -> {
            String variant = bundle.manifest().variant().name().replace('-', ' ').toUpperCase(Locale.ROOT);
            return List.of(new ModelInfo(model.canonicalId(), model.displayName() + " (" + variant + ")"));
        });
    }

    @Override
    public final AiInputHandling inputHandling(AiOperation operation) {
        return operation == AiOperation.TEXT ? AiInputHandling.LITERAL : AiInputHandling.PROTECTED_PROMPT;
    }

    @Override
    public final void close() throws Exception { lifecycle.close(); }
}

/** Shared validation, extraction, native initialization, and failed-build cleanup. */
abstract class Seq2SeqProviderBuilder<B extends Seq2SeqProviderBuilder<B>>
    extends LocalProviderBuilder<B> {
    Integer maximumOutputTokens;

    Seq2SeqProviderBuilder(Path bundle) { super(bundle); }

    /** Sets the maximum number of tokens generated for each request.
     * @param tokens positive output-token limit
     * @return this builder
     */
    public B maximumOutputTokens(int tokens) {
        if (tokens < 1) throw new IllegalArgumentException("maximumOutputTokens must be positive");
        maximumOutputTokens = tokens;
        return self();
    }

    final Integer outputLimit(PreparedBundle prepared, boolean useModelDefault) {
        int maximum = prepared.manifest().model().maximumOutputLength();
        if (maximumOutputTokens != null && maximumOutputTokens > maximum) {
            throw new IllegalArgumentException("maximumOutputTokens must not exceed " + maximum);
        }
        return maximumOutputTokens == null && useModelDefault ? maximum : maximumOutputTokens;
    }

    final <T extends AutoCloseable, C, P> P initialize(String resource, String description,
        String temporaryPrefix, String providerId, String failureMessage,
        Configuration<C> configuration, RuntimeLoader<T> loader,
        ProviderFactory<T, C, P> factory) throws AiProviderException {
        ModelDefinition model = ApprovedSeq2SeqModelCatalog.load(resource, description);
        Path temporaryParent = temporaryDirectory();
        PreparedBundle prepared = null;
        Resources<T> runtime = null;
        try {
            prepared = Seq2SeqBundleValidator.validateAndExtract(
                model, temporaryPrefix, bundle(), temporaryParent);
            C configured = configuration.resolve(prepared);
            runtime = loader.create(prepared, intraOpThreads());
            return factory.create(prepared, runtime, configured);
        } catch (Throwable failure) {
            LocalProviderLifecycle.cleanupAfterFailure(
                prepared == null ? null : prepared.directory(), failure,
                runtime == null ? null : runtime.generator(), runtime == null ? null : runtime.tokenizer());
            if (failure instanceof Error) throw (Error) failure;
            throw new AiProviderException(providerId, failureMessage + failure.getMessage(), failure);
        }
    }

    interface Configuration<C> { C resolve(PreparedBundle prepared) throws Exception; }

    interface RuntimeLoader<T extends AutoCloseable> {
        Resources<T> create(PreparedBundle prepared, Integer intraOpThreads) throws Exception;
    }

    interface ProviderFactory<T extends AutoCloseable, C, P> {
        P create(PreparedBundle prepared, Resources<T> runtime, C configured) throws Exception;
    }
}
