package com.webjetcms.ai.provider.local;

import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;

/** Applies credential redaction at public local-provider boundaries. */
final class LocalProviderBoundary {

    private LocalProviderBoundary() { }

    static <T> T invoke(AiProviderConfig config, ProviderCall<T> call)
        throws AiProviderException {
        try {
            return call.invoke();
        } catch (AiProviderException exception) {
            throw exception.redactSecrets(config);
        }
    }

    @FunctionalInterface
    interface ProviderCall<T> {
        T invoke() throws AiProviderException;
    }
}
