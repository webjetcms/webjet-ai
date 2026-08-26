package com.webjetcms.ai;

import java.util.List;
import java.util.Map;

import com.webjetcms.ai.image.ImageOptionDefinition;

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
     * Loads the models currently exposed by the provider.
     *
     * @param config credentials, endpoint, and transport settings for the call
     * @return an immutable or caller-safe model catalogue
     * @throws AiProviderException when validation, transport, or response parsing fails
     */
    List<ModelInfo> listModels(AiProviderConfig config) throws AiProviderException;

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
     * Creates embedding vectors for one or more text inputs.
     *
     * <p>The default implementation preserves compatibility for providers that do not
     * support embeddings.</p>
     *
     * <p>Embedding inputs are forwarded unchanged; prompt-defense markers are not added.</p>
     *
     * @param request provider-neutral embedding request
     * @param config credentials, endpoint, and transport settings for the call
     * @return generated embedding vectors and provider-reported usage
     * @throws AiProviderException when embeddings are unsupported or the request fails
     */
    default EmbeddingResponse embed(EmbeddingRequest request, AiProviderConfig config)
        throws AiProviderException {
        throw new AiProviderException(id(), "Embedding is not supported by this provider");
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
     * Releases resources owned by this provider when its client is closed.
     * Implementations must not close injected application-owned resources unless ownership
     * of those resources was explicitly transferred to the provider.
     *
     * @throws Exception when an owned resource cannot be closed
     */
    @Override
    default void close() throws Exception { }
}
