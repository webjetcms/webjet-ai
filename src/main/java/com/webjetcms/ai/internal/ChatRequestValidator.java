package com.webjetcms.ai.internal;

import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.security.PromptInjectionDefense;
import com.webjetcms.ai.security.PromptInjectionDefense.UntrustedSource;

/** Validates chat inputs and continuation fields before provider execution. */
public final class ChatRequestValidator {

    private ChatRequestValidator() { }

    /**
     * Checks shared chat constraints and rejects continuation IDs on other operations.
     *
     * <p>Chat requests require storage opt-in and meaningful text, without binary input,
     * image options, or translation options. A supplied continuation ID must be non-blank.</p>
     *
     * @param providerId provider identifier attached to validation failures
     * @param request request to validate; {@code null} is left for the provider to handle
     * @throws AiProviderException when chat inputs or continuation fields are invalid
     */
    public static void validate(String providerId, AiRequest request) throws AiProviderException {
        if (request == null) return;
        String previousResponseId = request.previousResponseId();
        if (previousResponseId != null) {
            if (request.operation() != AiOperation.CHAT) {
                throw new AiProviderException(providerId, "previousResponseId is supported only for CHAT requests");
            }
            if (previousResponseId.isBlank()) {
                throw new AiProviderException(providerId, "previousResponseId must not be blank");
            }
        }
        if (request.operation() != AiOperation.CHAT) return;
        if (request.store() == false) {
            throw new AiProviderException(providerId, "CHAT requires explicit store(true) for provider-managed context");
        }
        if (request.inputMedia() != null || request.imageOptions() != null || request.translationOptions() != null) {
            throw new AiProviderException(providerId, "CHAT accepts text only, without binary input, image or translation options");
        }
        if (PromptInjectionDefense.hasUntrustedText(request.inputText(), UntrustedSource.INPUT_TEXT) == false
            && PromptInjectionDefense.hasUntrustedText(request.userPrompt(), UntrustedSource.USER_PROMPT) == false) {
            throw new AiProviderException(providerId, "CHAT requires non-blank inputText or userPrompt");
        }
    }

    /**
     * Rejects chat requests for providers without chat support and validates other continuation fields.
     *
     * @param providerId provider identifier attached to validation failures
     * @param request request to validate; {@code null} is left for the provider to handle
     * @throws AiProviderException when the request uses chat or supplies a continuation ID
     */
    public static void rejectUnsupported(String providerId, AiRequest request) throws AiProviderException {
        if (request != null && request.operation() == AiOperation.CHAT) {
            throw new AiProviderException(providerId, "CHAT is not supported by this provider");
        }
        validate(providerId, request);
    }
}
