package com.webjetcms.ai;

import java.util.List;

/**
 * Service-provider interface implemented by each supported AI backend.
 *
 * <p>Provider implementations may own reusable transport resources. Applications
 * should therefore reuse provider instances and close them during shutdown.</p>
 */
public interface AiProvider extends AutoCloseable {

    /**
     * Returns the stable identifier used to register and select this provider.
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
     * Executes a non-streaming request.
     *
     * @param request provider-neutral operation and input data
     * @param config credentials, endpoint, and transport settings for the call
     * @return the completed provider response
     * @throws AiProviderException when validation, transport, or response parsing fails
     */
    AiResponse execute(AiRequest request, AiProviderConfig config) throws AiProviderException;

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
     * Releases transport resources owned by this provider.
     *
     * @throws Exception when an owned resource cannot be closed
     */
    @Override
    default void close() throws Exception { }
}
