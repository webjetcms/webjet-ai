package com.webjetcms.ai;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import com.webjetcms.ai.security.PromptInjectionDefense;
import com.webjetcms.ai.security.PromptInjectionDefense.ProtectionResult;
import com.webjetcms.ai.security.PromptInjectionDefense.UntrustedSource;

/** Immutable request passed from a host application to an AI provider. */
public final class AiRequest {

    private final AiOperation operation;
    private final String model;
    private final String instructions;
    private final String inputText;
    private final String userPrompt;
    private final BinaryContent inputMedia;
    private final boolean store;
    private final ImageOptions imageOptions;
    private final ProtectionResult inputTextProtection;
    private final ProtectionResult userPromptProtection;
    private final Set<UntrustedSource> suspiciousSources;

    private AiRequest(Builder builder) {
        this(
            builder,
            PromptInjectionDefense.protectUntrustedText(builder.inputText, UntrustedSource.INPUT_TEXT),
            PromptInjectionDefense.protectUntrustedText(builder.userPrompt, UntrustedSource.USER_PROMPT)
        );
    }

    private AiRequest(
        Builder builder,
        ProtectionResult inputTextProtection,
        ProtectionResult userPromptProtection
    ) {
        operation = Objects.requireNonNullElse(builder.operation, AiOperation.TEXT);
        model = builder.model;
        instructions = builder.instructions;
        inputText = builder.inputText;
        userPrompt = builder.userPrompt;
        inputMedia = builder.inputMedia;
        store = builder.store;
        imageOptions = builder.imageOptions;
        this.inputTextProtection = Objects.requireNonNull(inputTextProtection, "inputTextProtection");
        this.userPromptProtection = Objects.requireNonNull(userPromptProtection, "userPromptProtection");
        suspiciousSources = collectSuspiciousSources(inputTextProtection, userPromptProtection);
    }

    /** Creates a provider-facing copy that reuses the source's immutable protection results. */
    static AiRequest preparedCopy(AiRequest source, String preparedInstructions) {
        Objects.requireNonNull(source, "source");
        Builder builder = new Builder()
            .operation(source.operation)
            .model(source.model)
            .instructions(preparedInstructions)
            .inputText(source.inputTextProtection.protectedText())
            .userPrompt(source.userPromptProtection.protectedText())
            .inputMedia(source.inputMedia)
            .store(source.store)
            .imageOptions(source.imageOptions);
        return new AiRequest(builder, source.inputTextProtection, source.userPromptProtection);
    }

    /**
     * Starts a request with text as its default operation.
     *
     * @return a new request builder
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Returns the requested provider operation.
     *
     * @return the operation, defaulting to {@link AiOperation#TEXT}
     */
    public AiOperation operation() { return operation; }

    /**
     * Returns the provider-specific model identifier.
     *
     * @return the model identifier, possibly {@code null}
     */
    public String model() { return model; }

    /**
     * Returns host-controlled task or system instructions.
     *
     * @return trusted instructions, possibly {@code null}
     */
    public String instructions() { return instructions; }

    /**
     * Returns untrusted source text to process.
     *
     * @return input text, possibly {@code null}
     */
    public String inputText() { return inputText; }

    /**
     * Returns the untrusted end-user prompt.
     *
     * @return user prompt, possibly {@code null}
     */
    public String userPrompt() { return userPrompt; }

    /**
     * Returns binary input supplied to a multimodal or image-edit operation.
     *
     * @return binary input, or {@code null} when the request has none
     */
    public BinaryContent inputMedia() { return inputMedia; }

    /**
     * Indicates whether a provider that supports persistence may store the response.
     *
     * @return the provider storage preference, defaulting to {@code false}
     */
    public boolean store() { return store; }

    /**
     * Returns provider-neutral image generation options.
     *
     * @return image options, or {@code null} to use provider defaults
     */
    public ImageOptions imageOptions() { return imageOptions; }

    /**
     * Returns untrusted fields whose content matched prompt-injection patterns or reserved markers.
     *
     * <p>The request retains the original field values. {@link AiClient} applies prompt defenses
     * automatically to an immutable copy before delegating to a provider.</p>
     *
     * @return immutable set of suspicious untrusted sources
     */
    public Set<UntrustedSource> suspiciousSources() { return suspiciousSources; }

    private static Set<UntrustedSource> collectSuspiciousSources(
        ProtectionResult inputTextProtection,
        ProtectionResult userPromptProtection
    ) {
        EnumSet<UntrustedSource> sources = EnumSet.noneOf(UntrustedSource.class);
        if (inputTextProtection.suspiciousContentDetected()) {
            sources.add(UntrustedSource.INPUT_TEXT);
        }
        if (userPromptProtection.suspiciousContentDetected()) {
            sources.add(UntrustedSource.USER_PROMPT);
        }
        return Collections.unmodifiableSet(sources);
    }

    /** Builds an immutable {@link AiRequest}. */
    public static final class Builder {
        private AiOperation operation = AiOperation.TEXT;
        private String model;
        private String instructions;
        private String inputText;
        private String userPrompt;
        private BinaryContent inputMedia;
        private boolean store;
        private ImageOptions imageOptions;

        private Builder() { }

        /**
         * Selects the operation to execute.
         *
         * @param operation operation type; {@code null} selects {@link AiOperation#TEXT}
         * @return this builder
         */
        public Builder operation(AiOperation operation) { this.operation = operation; return this; }

        /**
         * Selects a provider-specific model.
         *
         * @param model provider model identifier
         * @return this builder
         */
        public Builder model(String model) { this.model = model; return this; }

        /**
         * Supplies host-controlled task or system instructions.
         *
         * @param instructions trusted instructions for the provider
         * @return this builder
         */
        public Builder instructions(String instructions) { this.instructions = instructions; return this; }

        /**
         * Supplies untrusted source text to process.
         *
         * @param inputText input text that providers protect as untrusted data
         * @return this builder
         */
        public Builder inputText(String inputText) { this.inputText = inputText; return this; }

        /**
         * Supplies an untrusted end-user prompt.
         *
         * @param userPrompt user prompt that providers protect as untrusted data
         * @return this builder
         */
        public Builder userPrompt(String userPrompt) { this.userPrompt = userPrompt; return this; }

        /**
         * Supplies binary input for a multimodal or image-edit operation.
         *
         * @param inputMedia binary input and its media metadata
         * @return this builder
         */
        public Builder inputMedia(BinaryContent inputMedia) { this.inputMedia = inputMedia; return this; }

        /**
         * Sets the provider storage preference.
         *
         * @param store {@code true} to opt in to persistence when the provider supports it
         * @return this builder
         */
        public Builder store(boolean store) { this.store = store; return this; }

        /**
         * Supplies optional image generation settings.
         *
         * @param imageOptions image count, dimensions, and quality preferences
         * @return this builder
         */
        public Builder imageOptions(ImageOptions imageOptions) { this.imageOptions = imageOptions; return this; }

        /**
         * Creates an immutable request from the accumulated values.
         *
         * @return a provider-neutral AI request
         */
        public AiRequest build() { return new AiRequest(this); }
    }
}
