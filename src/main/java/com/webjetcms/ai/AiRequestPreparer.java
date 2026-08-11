package com.webjetcms.ai;

import java.util.Objects;

import com.webjetcms.ai.security.PromptInjectionDefense;

/** Internal immutable request preparation used by {@link AiClient}. */
final class AiRequestPreparer {

    private AiRequestPreparer() {
    }

    static AiRequest prepare(AiRequest request) {
        Objects.requireNonNull(request, "request");
        return AiRequest.preparedCopy(
            request,
            PromptInjectionDefense.hardenSystemInstructions(request.instructions())
        );
    }
}
