package com.webjetcms.ai;

/**
 * Optional provider-neutral settings for translation requests.
 *
 * @param sourceLanguage source language code, possibly {@code null} when provider defaults apply
 * @param targetLanguage target language code, possibly {@code null} when provider defaults apply
 * @param maximumOutputTokens output-token limit, possibly {@code null} when provider defaults apply
 */
public record TranslationOptions(
    String sourceLanguage,
    String targetLanguage,
    Integer maximumOutputTokens
) {
    /** Validates optional language codes and the output-token limit. */
    public TranslationOptions {
        if (sourceLanguage != null && sourceLanguage.isBlank()) {
            throw new IllegalArgumentException("Source language must not be blank");
        }
        if (targetLanguage != null && targetLanguage.isBlank()) {
            throw new IllegalArgumentException("Target language must not be blank");
        }
        if (maximumOutputTokens != null && maximumOutputTokens < 1) {
            throw new IllegalArgumentException("Maximum output tokens must be positive");
        }
    }

    /** Creates translation options that rely entirely on provider defaults. */
    public TranslationOptions() {
        this(null, null, null);
    }
}
