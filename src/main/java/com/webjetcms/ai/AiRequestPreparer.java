package com.webjetcms.ai;

import java.util.Objects;

import com.webjetcms.ai.security.PromptInjectionDefense;
import com.webjetcms.ai.security.PromptInjectionDefense.ProtectionResult;
import com.webjetcms.ai.security.PromptInjectionDefense.UntrustedSource;

/** Internal immutable request preparation used by {@link AiClient}. */
final class AiRequestPreparer {

    private AiRequestPreparer() {
    }

    static AiRequest prepare(AiRequest request) {
        Objects.requireNonNull(request, "request");

        ProtectionResult inputText = PromptInjectionDefense.protectUntrustedText(
            request.inputText(),
            UntrustedSource.INPUT_TEXT
        );
        ProtectionResult userPrompt = PromptInjectionDefense.protectUntrustedText(
            request.userPrompt(),
            UntrustedSource.USER_PROMPT
        );

        return AiRequest.builder()
            .operation(request.operation())
            .model(request.model())
            .instructions(PromptInjectionDefense.hardenSystemInstructions(request.instructions()))
            .inputText(inputText.protectedText())
            .userPrompt(userPrompt.protectedText())
            .inputMedia(request.inputMedia())
            .store(request.store())
            .imageOptions(request.imageOptions())
            .build();
    }
}
