package com.webjetcms.ai;

import java.util.List;
import java.util.Map;

import com.webjetcms.ai.image.ImageOptionDefinition;
import com.webjetcms.ai.internal.CancellationSupport;

/**
 * Provider contract implemented by each supported AI backend.
 *
 * <p>Provider implementations may own reusable transport resources. A provider instance
 * should therefore be reused for calls made by its owning {@link AiClient}. Applications
 * normally invoke providers through the client, which applies prompt defenses to
 * {@link AiRequest} generation operations before delegation.</p>
 *
 * <p>Application-owned implementations can use constructor injection and be added to the
 * bundled providers through {@link AiClient#discover(AiProvider...)}, or used alone through
 * {@link AiClient#of(AiProvider...)}. Ownership transfers to a successfully created client,
 * which closes its providers. If client creation fails, supplied instances remain owned by
 * the caller.</p>
 */
public interface AiProvider extends AutoCloseable {

    /**
     * Returns the stable identifier used to register and select this provider.
     * The value is matched exactly and case-sensitively and is not normalized.
     *
     * @return a non-blank provider identifier
     */
    String id();

    /**
     * Selects client-side preparation for the requested operation.
     *
     * <p>The default keeps prompt defenses enabled for existing and generative providers.
     * Providers that perform a literal transformation may return {@link AiInputHandling#LITERAL}
     * for the applicable operation.</p>
     *
     * @param operation requested provider operation
     * @return input preparation mode; never {@code null}
     */
    default AiInputHandling inputHandling(AiOperation operation) {
        return AiInputHandling.PROTECTED_PROMPT;
    }

    /**
     * Loads the models currently exposed by the provider.
     *
     * @param config credentials, endpoint, and transport settings for the call
     * @return an immutable or caller-safe model catalogue
     * @throws AiProviderException when validation, transport, or response parsing fails
     */
    List<ModelInfo> listModels(AiProviderConfig config) throws AiProviderException;

    /**
     * Describes locally known adapter and model capabilities without credentials or I/O.
     *
     * <p>The default preserves custom-provider compatibility by returning unknown
     * metadata. Implementations must not load models, invoke inference, or make network
     * requests. Unknown model support does not prohibit execution.</p>
     *
     * @param model non-blank provider-specific model identifier
     * @return immutable advisory capability metadata; never {@code null}
     * @throws IllegalArgumentException if the model is null or blank
     */
    default AiCapabilities capabilities(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Capability model must not be blank");
        }
        return AiCapabilities.unknown();
    }

    /**
     * Returns the static image rendering options supported by a model and operation.
     *
     * <p>The default preserves compatibility for custom providers that do not publish
     * image capability metadata. Implementations should return an immutable or caller-safe,
     * deterministically ordered map. Reading this metadata must not require credentials or
     * make a network request.</p>
     *
     * @param model provider-specific model identifier
     * @param operation image generation or image editing operation
     * @return supported option definitions keyed by portable or provider wire name
     */
    default Map<String, ImageOptionDefinition> imageOptions(
        String model,
        AiOperation operation
    ) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Image model must not be blank");
        }
        if (operation != AiOperation.GENERATE_IMAGE && operation != AiOperation.EDIT_IMAGE) {
            throw new IllegalArgumentException("Image options require an image operation");
        }
        return Map.of();
    }

    /**
     * Executes a non-streaming request.
     *
     * @param request provider-neutral operation and input data
     * @param config credentials, endpoint, and transport settings for the call
     * @return the completed provider response
     * @throws AiProviderException when validation, transport, or response parsing fails
     */
    AiResponse execute(AiRequest request, AiProviderConfig config) throws AiProviderException;

    /**
     * Executes a request with a per-call cancellation token.
     *
     * <p>The compatibility default checks before and after the existing blocking call.
     * Override this method to abort transport or cooperatively stop computation.
     * Cancellation observed at the final check takes precedence over a result or failure.</p>
     *
     * @param request provider-neutral operation and input data
     * @param config credentials, endpoint, and transport settings
     * @param token non-null token created for this call
     * @return the completed response
     * @throws AiProviderException if cancelled or the request fails
     */
    default AiResponse execute(AiRequest request, AiProviderConfig config, AiCancellationToken token)
        throws AiProviderException {
        try {
            return CancellationSupport.call(id(), token, () -> execute(request, config));
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        }
    }

    /**
     * Creates embedding vectors for one or more text inputs.
     *
     * <p>The default implementation preserves compatibility for providers that do not
     * support embeddings.</p>
     *
     * <p>Prompt-defense markers are not added. A provider may still apply model-required
     * preprocessing selected through {@link EmbeddingOptions}, such as a retrieval role
     * prefix, without modifying the request value.</p>
     *
     * @param request provider-neutral embedding request
     * @param config credentials, endpoint, and transport settings for the call
     * @return generated embedding vectors and provider-reported usage
     * @throws AiProviderException when embeddings are unsupported or the request fails
     */
    default EmbeddingResponse embed(EmbeddingRequest request, AiProviderConfig config)
        throws AiProviderException {
        throw new AiProviderException(id(), "Embedding is not supported by this provider")
            .withCategory(AiErrorCategory.UNSUPPORTED_OPERATION);
    }

    /**
     * Executes a streaming request and reports decoded text fragments to a listener.
     *
     * @param request provider-neutral operation and input data
     * @param config credentials, endpoint, and transport settings for the call
     * @param listener callback that receives each decoded text fragment
     * @return the completed response, including the accumulated text and token usage
     * @throws AiProviderException when validation, transport, parsing, or the listener fails
     */
    AiResponse stream(AiRequest request, AiProviderConfig config, AiStreamListener listener)
        throws AiProviderException;

    /**
     * Streams a request with a per-call cancellation token.
     *
     * <p>The compatibility default checks around the existing call and each listener
     * delivery. It cannot interrupt a blocked legacy provider. An already-dispatched
     * callback may finish; providers must never close shared resources to cancel one call.</p>
     *
     * @param request provider-neutral operation and input data
     * @param config credentials, endpoint, and transport settings
     * @param listener callback receiving text fragments
     * @param token non-null token created for this call
     * @return the completed response
     * @throws AiProviderException if cancelled or the stream fails
     */
    default AiResponse stream(
        AiRequest request, AiProviderConfig config, AiStreamListener listener, AiCancellationToken token
    ) throws AiProviderException {
        try {
            return CancellationSupport.call(id(), token,
                () -> stream(request, config, CancellationSupport.listener(id(), token, listener)));
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        }
    }

    /**
     * Releases resources owned by this provider when its client is closed.
     * Implementations must not close injected application-owned resources unless ownership
     * of those resources was explicitly transferred to the provider.
     *
     * @throws Exception when an owned resource cannot be closed
     */
    @Override
    default void close() throws Exception { }
}
