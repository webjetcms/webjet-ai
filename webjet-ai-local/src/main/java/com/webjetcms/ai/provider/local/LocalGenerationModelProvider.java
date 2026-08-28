package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.webjetcms.ai.provider.local.VerifiedBundleExtractor.Result;
import com.webjetcms.ai.provider.local.VerifiedBundleExtractor.Specification;
import com.webjetcms.ai.security.PromptInjectionDefense;
import com.webjetcms.ai.security.PromptInjectionDefense.UntrustedSource;

import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import de.kherud.llama.ModelParameters;
import de.kherud.llama.args.LogFormat;

/**
 * Generates text locally with an approved multilingual causal model bundle.
 *
 * <p>The initial model is EuroLLM-1.7B-Instruct. It accepts the normal {@link AiOperation#TEXT}
 * request fields and returns generated text through {@link AiResponse#text()}. The provider
 * rejects text detected as prompt injection, validates and extracts its bundle eagerly, and
 * performs greedy CPU inference without network access.</p>
 *
 * <p>Instances are thread-safe, should be reused, and must be closed to release native
 * resources and remove extracted model files.</p>
 */
public final class LocalGenerationModelProvider implements AiProvider {
    /** Exact identifier used to register this provider with {@code AiClient}. */
    public static final String PROVIDER_ID = "local-generation";
    private static final String CATALOG_RESOURCE =
        "META-INF/webjet-ai/local-generation-model-catalog-v1.properties";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ManifestJson MANIFEST = new ManifestJson("Local generation manifest");
    private static final String CHAT_START = "<|im_start|>";
    private static final String CHAT_END = "<|im_end|>";

    private final CatalogValues model;
    private final LlamaModel generator;
    private final LocalProviderLifecycle lifecycle;
    private final int maximumOutputTokens;

    private LocalGenerationModelProvider(Result<CatalogValues> bundle,
        LlamaModel generator, int maximumOutputTokens) {
        model = bundle.manifest();
        this.generator = generator;
        this.maximumOutputTokens = maximumOutputTokens;
        lifecycle = new LocalProviderLifecycle(PROVIDER_ID,
            "Local generation model provider is closing or closed", bundle.directory(), generator);
    }

    /** Opens an approved local text-generation bundle with default runtime settings.
     * @param bundle local schema-v1 causal model ZIP
     * @return initialized provider owning its extracted and native resources
     * @throws AiProviderException when validation, extraction, or initialization fails
     */
    public static LocalGenerationModelProvider open(Path bundle) throws AiProviderException {
        return builder(bundle).build();
    }

    /** Starts configuring an approved local text-generation bundle.
     * @param bundle local schema-v1 causal model ZIP
     * @return local generation provider builder
     */
    public static Builder builder(Path bundle) { return new Builder(bundle); }

    /** Returns the provider identifier used by {@code AiClient}.
     * @return {@value #PROVIDER_ID}
     */
    @Override
    public String id() { return PROVIDER_ID; }

    @Override
    public AiInputHandling inputHandling(AiOperation operation) {
        return operation == AiOperation.TEXT ? AiInputHandling.LITERAL : AiInputHandling.PROTECTED_PROMPT;
    }

    @Override
    public List<ModelInfo> listModels(AiProviderConfig config) throws AiProviderException {
        return read(() -> List.of(new ModelInfo(model.required("model.canonical-id"),
            model.required("model.display-name") + " ("
                + variant(model).replace('-', ' ').toUpperCase(Locale.ROOT) + ")")));
    }

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
            String prompt = chatPrompt(request);
            try {
                synchronized (generator) {
                    if (generator.encode(prompt).length >= model.positiveInteger("model.maximum-length") - maximumOutputTokens)
                        throw invalid("Local generation prompt exceeds the model context length");
                    InferenceParameters parameters = new InferenceParameters(prompt)
                        .setNPredict(maximumOutputTokens)
                        .setTemperature(0)
                        .setStopStrings(CHAT_END);
                    StringBuilder generated = new StringBuilder();
                    for (LlamaOutput output : generator.generate(parameters)) generated.append(output.text);
                    return AiResponse.text(generated.toString().trim());
                }
            } catch (AiProviderException exception) {
                throw exception;
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
        if (hasText(request.model()) && model.uniqueSet("model.aliases").contains(request.model()) == false)
            throw invalid("Unsupported local generation model: " + request.model());
    }

    private static String chatPrompt(AiRequest request) throws AiProviderException {
        String instructions = chatContent(request.instructions());
        StringBuilder user = new StringBuilder();
        append(user, chatContent(request.inputText()));
        append(user, chatContent(request.userPrompt()));

        StringBuilder prompt = new StringBuilder();
        appendMessage(prompt, "system", instructions);
        appendMessage(prompt, "user", user.toString());
        prompt.append(CHAT_START).append("assistant\n");
        return prompt.toString();
    }

    private static String chatContent(String value) throws AiProviderException {
        String content = PromptInjectionDefense.stripUnsafeCharacters(value);
        if (content != null && (content.contains(CHAT_START) || content.contains(CHAT_END)))
            throw invalid("Local generation input contains reserved chat control tokens");
        return content;
    }

    private static void appendMessage(StringBuilder target, String role, String content) {
        if (hasText(content) == false) return;
        target.append(CHAT_START).append(role).append('\n').append(content).append(CHAT_END).append('\n');
    }

    private static void append(StringBuilder target, String value) {
        if (hasText(value) == false) return;
        if (target.length() > 0) target.append("\n\n");
        target.append(value);
    }

    private static boolean hasText(String value) { return value != null && value.isBlank() == false; }

    private static AiProviderException invalid(String message) { return new AiProviderException(PROVIDER_ID, message); }

    private <T> T read(LocalProviderLifecycle.Operation<T> operation) throws AiProviderException {
        return lifecycle.read(operation);
    }

    @Override
    public void close() throws Exception { lifecycle.close(); }

    /** Builds an eagerly initialized {@link LocalGenerationModelProvider}. */
    public static final class Builder extends LocalProviderBuilder<Builder> {
        private Integer maximumOutputTokens;

        private Builder(Path bundle) { super(bundle); }

        @Override Builder self() { return this; }
        @Override public Builder intraOpThreads(int threads) { return super.intraOpThreads(threads); }
        @Override public Builder temporaryDirectory(Path parent) { return super.temporaryDirectory(parent); }

        /** Sets the maximum number of tokens generated for each request.
         * @param tokens positive output-token limit
         * @return this builder
         */
        public Builder maximumOutputTokens(int tokens) {
            if (tokens < 1) throw new IllegalArgumentException("maximumOutputTokens must be positive");
            maximumOutputTokens = tokens;
            return this;
        }

        /** Validates, extracts, and initializes the local generation provider.
         * @return open provider owning its extracted and native resources
         * @throws AiProviderException when bundle validation or initialization fails
         */
        public LocalGenerationModelProvider build() throws AiProviderException {
            CatalogValues model = approvedModel();
            Result<CatalogValues> prepared = null;
            LlamaModel generator = null;
            try {
                prepared = validateAndExtract(model, bundle(), temporaryDirectory());
                int approvedMaximum = model.positiveInteger("model.maximum-output-length");
                int maximum = maximumOutputTokens == null ? approvedMaximum : maximumOutputTokens;
                if (maximum > approvedMaximum)
                    throw new IllegalArgumentException("maximumOutputTokens must not exceed " + approvedMaximum);
                ModelParameters parameters = new ModelParameters()
                    .setModelFilePath(prepared.directory().resolve(model.required("model.model-file")).toString())
                    .setNCtx(model.positiveInteger("model.maximum-length"));
                if (intraOpThreads() != null)
                    parameters.setNThreads(intraOpThreads()).setNThreadsBatch(intraOpThreads());
                synchronized (LlamaModel.class) {
                    LlamaModel.setLogger(LogFormat.TEXT, (level, message) -> { });
                    generator = new LlamaModel(parameters);
                }
                return new LocalGenerationModelProvider(prepared, generator, maximum);
            } catch (Throwable failure) {
                LocalProviderLifecycle.cleanupAfterFailure(
                    prepared == null ? null : prepared.directory(), failure, generator);
                if (failure instanceof Error) throw (Error) failure;
                throw new AiProviderException(PROVIDER_ID,
                    "Could not initialize the local generation provider: " + failure.getMessage(), failure);
            }
        }
    }

    private static CatalogValues approvedModel() {
        CatalogValues values = CatalogValues.load(LocalGenerationModelProvider.class,
            CATALOG_RESOURCE, "generation-model");
        if (values.integer("catalog.version") != 1)
            throw new IllegalStateException("Unsupported approved generation-model catalogue version");
        String canonicalId = values.required("model.canonical-id");
        Set<String> aliases = values.uniqueSet("model.aliases");
        if (aliases.contains(canonicalId) == false)
            throw new IllegalStateException("Approved model aliases must contain its canonical ID: " + canonicalId);
        String variant = values.required("model.default-variant");
        if (values.uniqueSet("model.variants").equals(Set.of(variant)) == false)
            throw new IllegalStateException("Approved generation model must have one default variant");
        List<String> artifacts = values.uniqueList("model.artifacts");
        if (artifacts.size() != 1) throw new IllegalStateException("Approved generation model must have one model card");
        String variantPrefix = "variant." + variant + ".";
        String card = artifacts.get(0), cardPrefix = "artifact." + card + ".";
        for (String key : List.of("model.display-name", "model.repository", "model.revision", "model.license",
            "model.task", "model.model-file", "model.model-card-path", variantPrefix + "cpu-target",
            variantPrefix + "source-path", cardPrefix + "source-path")) values.required(key);
        values.positiveLong(variantPrefix + "model-size"); values.sha256(variantPrefix + "model-sha256");
        values.positiveLong(cardPrefix + "size"); values.sha256(cardPrefix + "sha256");
        if (values.positiveInteger("model.maximum-output-length") > values.positiveInteger("model.maximum-length"))
            throw new IllegalStateException("Approved generation output length exceeds its context length");
        return values;
    }

    private static Result<CatalogValues> validateAndExtract(CatalogValues model,
        Path bundle, Path temporaryParent) throws IOException {
        return VerifiedBundleExtractor.validateAndExtract(bundle, temporaryParent,
            "webjet-ai-local-generation-", new Specification<>(
                LocalGenerationModelProvider::entryOrder,
                content -> parseManifest(content, model),
                LocalGenerationModelProvider::artifact,
                LocalGenerationModelProvider::maximumExtractedBytes));
    }

    private static CatalogValues parseManifest(byte[] content, CatalogValues approved) throws IOException {
        JsonNode root = MANIFEST.object(MAPPER.readTree(content), "manifest",
            "schemaVersion", "model", "task", "generation", "runtime", "source");
        MANIFEST.expect(root, "schemaVersion", 1);
        String variant = variant(approved), prefix = "variant." + variant + ".";
        MANIFEST.values(root, "model", "id", approved.required("model.canonical-id"),
            "revision", approved.required("model.revision"), "variant", variant, "format", "gguf",
            "file", approved.required("model.model-file"), "license", approved.required("model.license"));
        MANIFEST.expect(root, "task", approved.required("model.task"));
        MANIFEST.values(root, "generation", "maximumLength", approved.positiveInteger("model.maximum-output-length"),
            "contextLength", approved.positiveInteger("model.maximum-length"), "strategy", "greedy");
        MANIFEST.values(root, "runtime", "cpuTarget", approved.required(prefix + "cpu-target"));
        MANIFEST.values(root, "source", "repository", approved.required("model.repository"),
            "revision", approved.required("model.revision"), "modelPath", approved.required(prefix + "source-path"),
            "modelCardPath", approved.required("model.model-card-path"));
        PlatformSupport.requireSupported(variant, approved.required(prefix + "cpu-target"));
        return approved;
    }

    private static List<String> entryOrder(CatalogValues model) {
        return List.of("webjet-model.json", model.required("model.model-file"), modelCard(model), "SHA256SUMS");
    }
    private static Specification.Artifact artifact(CatalogValues model, String name) throws IOException {
        String variantPrefix = "variant." + variant(model) + ".";
        if (model.required("model.model-file").equals(name)) return new Specification.Artifact(
            model.positiveLong(variantPrefix + "model-size"), model.sha256(variantPrefix + "model-sha256"));
        String card = modelCard(model), prefix = "artifact." + card + ".";
        if (card.equals(name)) return new Specification.Artifact(
            model.positiveLong(prefix + "size"), model.sha256(prefix + "sha256"));
        throw new IOException("Unsupported local generation artifact: " + name);
    }
    private static long maximumExtractedBytes(CatalogValues model) {
        String variantPrefix = "variant." + variant(model) + ".", cardPrefix = "artifact." + modelCard(model) + ".";
        return Math.addExact(Math.addExact(model.positiveLong(variantPrefix + "model-size"),
            model.positiveLong(cardPrefix + "size")), 128L * 1024L);
    }
    private static String variant(CatalogValues model) { return model.required("model.default-variant"); }
    private static String modelCard(CatalogValues model) { return model.uniqueList("model.artifacts").get(0); }
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
