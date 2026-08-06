package com.webjetcms.ai;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Entry point for selecting and invoking registered AI providers. */
public final class AiClient implements AutoCloseable {

    private final Map<String, AiProvider> providers;

    private AiClient(Map<String, AiProvider> providers) {
        this.providers = Map.copyOf(providers);
    }

    /**
     * Creates a client backed by the supplied provider instances.
     *
     * @param providers providers to register; every provider must have a unique, non-blank identifier
     * @return a client that owns and delegates to the registered providers
     * @throws NullPointerException if the array, a provider, or a provider identifier is {@code null}
     * @throws IllegalArgumentException if an identifier is blank or registered more than once
     */
    public static AiClient of(AiProvider... providers) {
        Objects.requireNonNull(providers, "providers");
        Map<String, AiProvider> byId = new LinkedHashMap<>();
        Arrays.stream(providers).forEach(provider -> {
            Objects.requireNonNull(provider, "provider");
            String providerId = Objects.requireNonNull(provider.id(), "provider.id");
            if (providerId.isBlank()) {
                throw new IllegalArgumentException("AI provider id must not be blank");
            }
            AiProvider previous = byId.putIfAbsent(providerId, provider);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate AI provider id: " + providerId);
            }
        });
        return new AiClient(byId);
    }

    /**
     * Checks whether a provider identifier is registered.
     *
     * @param providerId identifier to look up
     * @return {@code true} when a provider with that identifier is registered
     */
    public boolean hasProvider(String providerId) {
        return providers.containsKey(providerId);
    }

    /**
     * Loads the model catalogue from a registered provider.
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
     * Executes a non-streaming request using a registered provider.
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
            return selectedProvider.execute(request, config);
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        } catch (RuntimeException exception) {
            throw unexpectedProviderFailure(providerId, exception, config);
        }
    }

    /**
     * Executes a streaming request using a registered provider.
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
            return selectedProvider.stream(request, config, listener);
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
        Exception firstFailure = null;
        for (AiProvider provider : providers.values()) {
            try {
                provider.close();
            } catch (Exception exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                } else {
                    firstFailure.addSuppressed(exception);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
