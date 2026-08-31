package com.webjetcms.ai.provider.local;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.AiStreamListener;
import com.webjetcms.ai.TranslationOptions;
import com.webjetcms.ai.provider.local.NativeSeq2SeqRuntimeFactory.Resources;
import com.webjetcms.ai.provider.local.Seq2SeqBundleValidator.PreparedBundle;

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
public final class LocalTranslationModelProvider extends Seq2SeqProvider<NativeTranslationTokenizer> {
    /** Exact identifier used to register this provider with {@code AiClient}. */
    public static final String PROVIDER_ID = "local-translation";

    private final String defaultSourceLanguage;
    private final String defaultTargetLanguage;
    private final Integer defaultMaximumOutputTokens;

    private LocalTranslationModelProvider(PreparedBundle bundle,
        Resources<NativeTranslationTokenizer> runtime, String defaultSourceLanguage,
        String defaultTargetLanguage, Integer defaultMaximumOutputTokens) {
        super(PROVIDER_ID, "Local translation model provider is closing or closed", bundle, runtime);
        this.defaultSourceLanguage = defaultSourceLanguage;
        this.defaultTargetLanguage = defaultTargetLanguage;
        this.defaultMaximumOutputTokens = defaultMaximumOutputTokens;
    }

    /** Opens an approved local translation model bundle with default runtime settings.
     * @param bundle local schema-v1 sequence-to-sequence model ZIP
     * @return an initialized provider that owns its extracted and native resources
     * @throws AiProviderException when validation, extraction, or native initialization fails
     */
    public static LocalTranslationModelProvider open(Path bundle) throws AiProviderException {
        return builder(bundle).build();
    }

    /** Starts configuring a provider for an approved local translation model bundle.
     * @param bundle local schema-v1 sequence-to-sequence model ZIP
     * @return a local translation provider builder
     */
    public static Builder builder(Path bundle) { return new Builder(bundle); }

    /** Returns the provider identifier used by {@code AiClient}.
     * @return {@value #PROVIDER_ID}
     */
    @Override
    public String id() { return PROVIDER_ID; }

    /** Returns language codes discovered from the validated tokenizer metadata.
     * @return immutable set of supported language codes
     * @throws AiProviderException when the provider is closing or closed
     */
    public Set<String> supportedLanguages() throws AiProviderException {
        return read(() -> tokenizer.supportedLanguages());
    }

    /** Transforms literal text using builder-configured source and target languages.
     * @param text literal source text
     * @return transformed text only
     * @throws AiProviderException when defaults are missing, input is invalid, or inference fails
     */
    public String translate(String text) throws AiProviderException {
        AiRequest request = AiRequest.builder().inputText(text).build();
        return execute(request, AiProviderConfig.empty()).text();
    }

    /** Executes a literal non-streaming text transformation.
     * @param request normal text request with optional per-request {@link TranslationOptions}
     * @param config ignored because local execution requires no credentials or endpoint
     * @return a text-only response containing the transformed string
     * @throws AiProviderException when validation or local inference fails
     */
    @Override
    public AiResponse execute(AiRequest request, AiProviderConfig config) throws AiProviderException {
        return LocalProviderBoundary.invoke(config, () ->
            read(() -> {
                ResolvedRequest resolved = validateAndResolve(request);
                try {
                    NativeTokenizerBatch source = tokenizer.encode(
                        request.inputText(), resolved.sourceLanguage());
                    long targetLanguageTokenId = tokenizer.languageTokenId(resolved.targetLanguage());
                    long[] generated = generator.generate(
                        source.inputIds(), source.attentionMask(),
                        new long[]{model.decoderStartTokenId(), targetLanguageTokenId},
                        resolved.maximumOutputTokens());
                    return AiResponse.text(tokenizer.decode(generated));
                } catch (Exception exception) {
                    throw new AiProviderException(PROVIDER_ID, "Local translation inference failed", exception);
                }
            })
        );
    }

    /** Rejects streaming because the local sequence-to-sequence runtime returns completed text.
     * @param request text request
     * @param config ignored local configuration
     * @param listener unused stream listener
     * @return never returns normally
     * @throws AiProviderException always, because streaming is unsupported
     */
    @Override
    public AiResponse stream(AiRequest request, AiProviderConfig config, AiStreamListener listener)
        throws AiProviderException {
        return LocalProviderBoundary.invoke(config, () -> {
            throw new AiProviderException(PROVIDER_ID,
                "Streaming is not supported by the local translation model provider");
        });
    }

    private ResolvedRequest validateAndResolve(AiRequest request) throws AiProviderException {
        if (request == null) throw invalid("Text request must not be null");
        if (request.operation() != AiOperation.TEXT) throw invalid("Local translation models require a TEXT request");
        if (request.inputText() == null || request.inputText().isBlank())
            throw invalid("Local translation model input must not be blank");
        if (hasText(request.instructions()) || hasText(request.userPrompt()))
            throw invalid("Local translation uses literal inputText and does not accept instructions or userPrompt");
        if (request.inputMedia() != null || request.imageOptions() != null)
            throw invalid("Local translation models do not accept image or binary input");
        if (request.store()) throw invalid("Local translation model responses cannot be stored by the provider");
        if (hasText(request.model()) && model.aliases().contains(request.model()) == false)
            throw invalid("Unsupported local translation model: " + request.model());
        TranslationOptions options = request.translationOptions() == null ? new TranslationOptions() : request.translationOptions();
        String source = first(options.sourceLanguage(), defaultSourceLanguage);
        String target = first(options.targetLanguage(), defaultTargetLanguage);
        if (source == null || target == null)
            throw invalid("Source and target languages must be supplied in TranslationOptions or provider defaults");
        Integer configuredMaximum = options.maximumOutputTokens() != null
            ? options.maximumOutputTokens() : defaultMaximumOutputTokens;
        int maximumOutputTokens = configuredMaximum == null
            ? model.maximumOutputLength() : configuredMaximum;
        if (maximumOutputTokens > model.maximumOutputLength())
            throw invalid("Maximum output tokens must not exceed " + model.maximumOutputLength());
        return new ResolvedRequest(source, target, maximumOutputTokens);
    }

    private static String first(String first, String second) {
        return hasText(first) ? first : hasText(second) ? second : null;
    }

    private static boolean hasText(String value) { return value != null && value.isBlank() == false; }

    private static AiProviderException invalid(String message) { return new AiProviderException(PROVIDER_ID, message); }

    private record ResolvedRequest(String sourceLanguage, String targetLanguage, int maximumOutputTokens) { }

    /** Builds an eagerly initialized {@link LocalTranslationModelProvider}. */
    public static final class Builder extends Seq2SeqProviderBuilder<Builder> {
        private String sourceLanguage;
        private String targetLanguage;

        private Builder(Path bundle) { super(bundle); }

        @Override Builder self() { return this; }
        @Override public Builder intraOpThreads(int threads) { return super.intraOpThreads(threads); }
        @Override public Builder temporaryDirectory(Path parent) { return super.temporaryDirectory(parent); }
        @Override public Builder maximumOutputTokens(int tokens) { return super.maximumOutputTokens(tokens); }

        /** Sets a default source language that per-request translation options may override.
         * @param language non-blank model language code
         * @return this builder
         */
        public Builder sourceLanguage(String language) {
            sourceLanguage = requireText(language, "sourceLanguage");
            return this;
        }

        /** Sets a default target language that per-request translation options may override.
         * @param language non-blank model language code
         * @return this builder
         */
        public Builder targetLanguage(String language) {
            targetLanguage = requireText(language, "targetLanguage");
            return this;
        }

        /** Validates, extracts, and initializes the local translation model provider.
         * @return an open provider that owns its extracted and native resources
         * @throws AiProviderException when bundle validation or runtime initialization fails
         */
        public LocalTranslationModelProvider build() throws AiProviderException {
            return initialize(
                ApprovedSeq2SeqModelCatalog.TRANSLATION_RESOURCE, "translation-model",
                "webjet-ai-local-translation-", PROVIDER_ID,
                "Could not initialize the local translation model provider: ",
                prepared -> outputLimit(prepared, false),
                NativeSeq2SeqRuntimeFactory::createTranslation,
                (prepared, resources, maximum) -> {
                    LocalTranslationModelProvider provider = new LocalTranslationModelProvider(
                        prepared, resources, sourceLanguage, targetLanguage, maximum);
                    if (sourceLanguage != null) provider.tokenizer.languageTokenId(sourceLanguage);
                    if (targetLanguage != null) provider.tokenizer.languageTokenId(targetLanguage);
                    return provider;
                }
            );
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
            return value;
        }
    }
}
