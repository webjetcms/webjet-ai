package com.webjetcms.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.webjetcms.ai.image.ImageOptionDefinition;

/**
 * Owns a fixed set of AI provider instances and selects them by exact identifier.
 *
 * <p>{@link #discover()} includes every bundled provider, while {@link #of(AiProvider...)}
 * includes only explicitly supplied instances. Registration is local to the client; no
 * global mutable registration or class-path scanning is used. A successfully created client
 * owns all its providers and must be closed.</p>
 */
public final class AiClient implements AutoCloseable {

    private final Map<String, AiProvider> providers;

    private AiClient(Map<String, AiProvider> providers) {
        this.providers = Map.copyOf(providers);
    }

    /**
     * Creates a client containing only the supplied provider instances.
     *
     * <p>If this method returns successfully, the client owns and closes the providers.
     * If validation fails, ownership remains with the caller. Identifiers are compared
     * exactly and case-sensitively; they are not normalized.</p>
     *
     * @param providers providers to register; every provider must have a unique, non-blank identifier
     * @return a client that owns and delegates to the registered providers
     * @throws NullPointerException if the array, a provider, or a provider identifier is {@code null}
     * @throws IllegalArgumentException if an identifier is blank or registered more than once
     */
    public static AiClient of(AiProvider... providers) {
        return new AiClient(registeredProviders(providers));
    }

    private static Map<String, AiProvider> registeredProviders(
        AiProvider[] providers
    ) {
        Objects.requireNonNull(providers, "providers");
        Map<String, AiProvider> registered = new LinkedHashMap<>();
        for (AiProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            String providerId = validateProviderId(provider.id());
            register(registered, providerId, provider);
        }
        return registered;
    }

    private static String validateProviderId(String providerId) {
        Objects.requireNonNull(providerId, "provider.id");
        if (providerId.isBlank()) {
            throw new IllegalArgumentException("AI provider id must not be blank");
        }
        return providerId;
    }

    /**
     * Creates a client containing every provider bundled with WebJET AI.
     *
     * <p>Each invocation creates fresh provider instances. If construction or registration
     * fails, every instance already created by the library is closed; close failures are
     * suppressed on the primary failure.</p>
     *
     * @return a client that owns and delegates to all built-in providers
     * @throws IllegalStateException if a built-in factory violates its registered identifier
     */
    public static AiClient discover() {
        return discover(new AiProvider[0]);
    }

    /**
     * Creates a client containing every bundled provider and the supplied custom
     * provider instances.
     *
     * <p>Custom identifiers are compared exactly and case-sensitively with each other and
     * with bundled identifiers. Custom identifier validation completes before any bundled
     * provider is created.</p>
     *
     * <p>On success, the client owns and closes all providers. If creation fails, every
     * bundled instance already created by the library is closed, while ownership of all
     * supplied custom instances remains with the caller.</p>
     *
     * @param customProviders custom provider instances to add to the bundled providers
     * @return a client that owns and delegates to all bundled and custom providers
     * @throws NullPointerException if the array, a provider, or an identifier is {@code null}
     * @throws IllegalArgumentException if an identifier is blank, duplicated, or collides
     *     with a bundled provider identifier
     * @throws IllegalStateException if a bundled factory returns {@code null} or a provider whose
     *     identifier does not exactly match its registered identifier
     */
    public static AiClient discover(AiProvider... customProviders) {
        return createDiscoveredClient(AiProviders.entries(), customProviders);
    }

    static AiClient createDiscoveredClient(
        List<AiProviders.Entry> builtInEntries,
        AiProvider... customProviders
    ) {
        List<AiProviders.Entry> entries = List.copyOf(
            Objects.requireNonNull(builtInEntries, "builtInEntries")
        );
        Set<String> builtInIds = new LinkedHashSet<>();
        for (AiProviders.Entry entry : entries) {
            if (builtInIds.add(entry.id()) == false) {
                throw new IllegalArgumentException(
                    "Duplicate AI provider id: " + entry.id()
                );
            }
        }

        Map<String, AiProvider> custom = registeredProviders(customProviders);
        for (String builtIn : builtInIds) {
            if (custom.containsKey(builtIn)) {
                throw new IllegalArgumentException(
                    "Duplicate AI provider id: " + builtIn
                );
            }
        }

        Map<String, AiProvider> registered = new LinkedHashMap<>();
        List<AiProvider> builtInProviders = new ArrayList<>();
        try {
            for (AiProviders.Entry entry : entries) {
                String providerId = entry.id();
                AiProvider provider = entry.factory().get();
                if (provider == null) {
                    throw new IllegalStateException(
                        "AI provider factory returned null: " + providerId
                    );
                }
                builtInProviders.add(provider);

                String actualId = provider.id();
                if (providerId.equals(actualId) == false) {
                    throw new IllegalStateException(
                        "AI provider factory for " + providerId
                            + " returned provider with id: " + actualId
                    );
                }
                register(registered, providerId, provider);
            }

            for (Map.Entry<String, AiProvider> customProvider : custom.entrySet()) {
                register(registered, customProvider.getKey(), customProvider.getValue());
            }
            return new AiClient(registered);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(builtInProviders, failure);
            throw failure;
        }
    }

    private static void register(
        Map<String, AiProvider> registered,
        String providerId,
        AiProvider provider
    ) {
        AiProvider previous = registered.putIfAbsent(providerId, provider);
        if (previous != null) {
            throw new IllegalArgumentException("Duplicate AI provider id: " + providerId);
        }
    }

    private static void closeAfterFailure(
        List<? extends AiProvider> providers,
        Throwable failure
    ) {
        for (AiProvider provider : providers) {
            try {
                provider.close();
            } catch (Throwable closeFailure) {
                if (closeFailure != failure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
    }

    /**
     * Returns all bundled and custom provider identifiers registered in this client.
     * Reading this catalogue does not invoke a provider or load its models.
     *
     * @return an immutable list sorted by the identifiers' natural, case-sensitive order
     */
    public List<String> providers() {
        return providers.keySet().stream().sorted().toList();
    }

    /**
     * Checks whether an exact, case-sensitive provider identifier is registered.
     *
     * @param providerId exact identifier to look up; it is not normalized
     * @return {@code true} when a provider with that identifier is registered
     */
    public boolean hasProvider(String providerId) {
        return providers.containsKey(providerId);
    }

    /**
     * Loads the model catalogue from the only registered provider.
     * The client delegates every call and does not maintain a hardcoded model list.
     *
     * @param config provider credentials and connection settings
     * @return models reported by the registered provider
     * @throws IllegalStateException if the client does not contain exactly one provider
     * @throws AiProviderException when the provider cannot load or parse its model catalogue
     */
    public List<ModelInfo> listModels(AiProviderConfig config) throws AiProviderException {
        return listModels(soleProviderId(), config);
    }

    /**
     * Loads the model catalogue from a registered provider.
     * The client delegates every call and does not maintain a hardcoded model list.
     *
     * @param providerId registered provider identifier
     * @param config provider credentials and connection settings
     * @return models reported by the selected provider
     * @throws IllegalArgumentException if no provider is registered under {@code providerId}
     * @throws AiProviderException when the provider cannot load or parse its model catalogue
     */
    public List<ModelInfo> listModels(String providerId, AiProviderConfig config) throws AiProviderException {
        AiProvider selectedProvider = provider(providerId);
        try {
            return selectedProvider.listModels(config);
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (RuntimeException exception) {
            throw unexpectedProviderFailure(providerId, exception, config);
        }
    }

    /**
     * Returns static image option metadata from the only registered provider.
     * This call does not require credentials or perform network I/O.
     *
     * @param model provider-specific model identifier
     * @param operation image generation or image editing operation
     * @return immutable, deterministically ordered option definitions
     * @throws IllegalStateException if the client does not contain exactly one provider
     * @throws IllegalArgumentException if the model or operation is invalid
     */
    public Map<String, ImageOptionDefinition> imageOptions(
        String model,
        AiOperation operation
    ) {
        return imageOptions(soleProviderId(), model, operation);
    }

    /**
     * Returns static image option metadata from a registered provider.
     * This call does not require credentials or perform network I/O.
     *
     * @param providerId registered provider identifier
     * @param model provider-specific model identifier
     * @param operation image generation or image editing operation
     * @return immutable, deterministically ordered option definitions
     * @throws IllegalArgumentException if the provider, model, or operation is invalid
     */
    public Map<String, ImageOptionDefinition> imageOptions(
        String providerId,
        String model,
        AiOperation operation
    ) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Image model must not be blank");
        }
        if (operation != AiOperation.GENERATE_IMAGE && operation != AiOperation.EDIT_IMAGE) {
            throw new IllegalArgumentException("Image options require an image operation");
        }

        Map<String, ImageOptionDefinition> definitions = provider(providerId)
            .imageOptions(model, operation);
        if (definitions == null || definitions.isEmpty()) {
            return Map.of();
        }
        Map<String, ImageOptionDefinition> copy = new LinkedHashMap<>();
        definitions.forEach((key, definition) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("AI provider returned a blank image option key");
            }
            copy.put(key, Objects.requireNonNull(
                definition,
                "AI provider image option definition"
            ));
        });
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Executes a non-streaming request using the only registered provider.
     * The provider receives an immutable copy with prompt defenses applied.
     *
     * @param request operation and inputs to execute
     * @param config provider credentials and connection settings
     * @return the completed provider response
     * @throws IllegalStateException if the client does not contain exactly one provider
     * @throws AiProviderException when request validation, transport, or response parsing fails
     */
    public AiResponse execute(AiRequest request, AiProviderConfig config) throws AiProviderException {
        return execute(soleProviderId(), request, config);
    }

    /**
     * Executes a non-streaming request using a registered provider.
     * The provider receives an immutable copy with prompt defenses applied.
     *
     * @param providerId registered provider identifier
     * @param request operation and inputs to execute
     * @param config provider credentials and connection settings
     * @return the completed provider response
     * @throws IllegalArgumentException if no provider is registered under {@code providerId}
     * @throws AiProviderException when request validation, transport, or response parsing fails
     */
    public AiResponse execute(String providerId, AiRequest request, AiProviderConfig config)
        throws AiProviderException {
        AiProvider selectedProvider = provider(providerId);
        try {
            return selectedProvider.execute(prepareRequest(request), config);
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (RuntimeException exception) {
            throw unexpectedProviderFailure(providerId, exception, config);
        }
    }

    /**
     * Creates embeddings using the only registered provider.
     * Embedding inputs are forwarded unchanged because prompt-defense markers would
     * alter the resulting vectors.
     *
     * @param request model, inputs, and provider-neutral embedding options
     * @param config provider credentials and connection settings
     * @return generated embedding vectors and provider-reported usage
     * @throws IllegalStateException if the client does not contain exactly one provider
     * @throws AiProviderException when request validation, transport, or response parsing fails
     */
    public EmbeddingResponse embed(EmbeddingRequest request, AiProviderConfig config)
        throws AiProviderException {
        return embed(soleProviderId(), request, config);
    }

    /**
     * Creates embeddings using a registered provider.
     * Embedding inputs are forwarded unchanged because prompt-defense markers would
     * alter the resulting vectors.
     *
     * @param providerId registered provider identifier
     * @param request model, inputs, and provider-neutral embedding options
     * @param config provider credentials and connection settings
     * @return generated embedding vectors and provider-reported usage
     * @throws IllegalArgumentException if no provider is registered under {@code providerId}
     * @throws AiProviderException when request validation, transport, or response parsing fails
     */
    public EmbeddingResponse embed(
        String providerId,
        EmbeddingRequest request,
        AiProviderConfig config
    ) throws AiProviderException {
        AiProvider selectedProvider = provider(providerId);
        try {
            return selectedProvider.embed(request, config);
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (RuntimeException exception) {
            throw unexpectedProviderFailure(providerId, exception, config);
        }
    }

    /**
     * Executes a streaming request using the only registered provider.
     * The provider receives an immutable copy with prompt defenses applied.
     *
     * @param request operation and inputs to execute
     * @param config provider credentials and connection settings
     * @param listener callback that receives each decoded text fragment
     * @return the completed response after the stream terminates normally
     * @throws IllegalStateException if the client does not contain exactly one provider
     * @throws AiProviderException when validation, transport, parsing, or the listener fails
     */
    public AiResponse stream(
        AiRequest request,
        AiProviderConfig config,
        AiStreamListener listener
    ) throws AiProviderException {
        return stream(soleProviderId(), request, config, listener);
    }

    /**
     * Executes a streaming request using a registered provider.
     * The provider receives an immutable copy with prompt defenses applied.
     *
     * @param providerId registered provider identifier
     * @param request operation and inputs to execute
     * @param config provider credentials and connection settings
     * @param listener callback that receives each decoded text fragment
     * @return the completed response after the stream terminates normally
     * @throws IllegalArgumentException if no provider is registered under {@code providerId}
     * @throws AiProviderException when validation, transport, parsing, or the listener fails
     */
    public AiResponse stream(
        String providerId,
        AiRequest request,
        AiProviderConfig config,
        AiStreamListener listener
    ) throws AiProviderException {
        AiProvider selectedProvider = provider(providerId);
        try {
            return selectedProvider.stream(prepareRequest(request), config, listener);
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (RuntimeException exception) {
            throw unexpectedProviderFailure(providerId, exception, config);
        }
    }

    private static AiProviderException unexpectedProviderFailure(
        String providerId,
        RuntimeException cause,
        AiProviderConfig config
    ) {
        return new AiProviderException(providerId, "Unexpected AI provider failure", cause)
            .redactSecrets(config);
    }

    private static AiRequest prepareRequest(AiRequest request) {
        return request == null ? null : AiRequestPreparer.prepare(request);
    }

    private String soleProviderId() {
        if (providers.size() != 1) {
            throw new IllegalStateException(
                "Provider identifier can be omitted only when exactly one AI provider is registered; "
                    + "registered count: " + providers.size()
            );
        }
        return providers.keySet().iterator().next();
    }

    private AiProvider provider(String providerId) {
        AiProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown AI provider: " + providerId);
        }
        return provider;
    }

    /**
     * Closes every registered provider, retaining later failures as suppressed exceptions.
     *
     * @throws Exception when at least one provider cannot be closed
     */
    @Override
    public void close() throws Exception {
        Throwable firstFailure = null;
        for (AiProvider provider : providers.values()) {
            try {
                provider.close();
            } catch (Exception | Error failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else if (failure != firstFailure) {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure instanceof Exception exception) {
            throw exception;
        }
        if (firstFailure instanceof Error error) {
            throw error;
        }
    }
}
