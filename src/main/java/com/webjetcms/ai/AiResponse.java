package com.webjetcms.ai;

import java.util.List;

/**
 * Completed provider response, including optional generated media and usage.
 *
 * @param text generated or accumulated text, possibly {@code null}
 * @param media generated binary media; {@code null} is normalized to an empty immutable list
 * @param usage provider-reported token usage; {@code null} is normalized to {@link TokenUsage#EMPTY}
 * @param finishReason provider-specific completion reason, possibly {@code null}
 */
public record AiResponse(
    String text,
    List<GeneratedMedia> media,
    TokenUsage usage,
    String finishReason
) {

    /**
     * Creates a response and normalizes nullable collection and usage values.
     *
     * @param text generated or accumulated text, possibly {@code null}
     * @param media generated binary media, possibly {@code null}
     * @param usage provider-reported token usage, possibly {@code null}
     * @param finishReason provider-specific completion reason, possibly {@code null}
     */
    public AiResponse {
        media = media == null ? List.of() : List.copyOf(media);
        usage = usage == null ? TokenUsage.EMPTY : usage;
    }

    /**
     * Creates a text-only response without usage or finish metadata.
     *
     * @param text generated text, possibly {@code null}
     * @return a response containing only the supplied text
     */
    public static AiResponse text(String text) {
        return new AiResponse(text, List.of(), TokenUsage.EMPTY, null);
    }

    /**
     * Returns a copy with replaced text and unchanged media, usage, and completion metadata.
     *
     * @param replacementText replacement response text, possibly {@code null}
     * @return response copy containing the replacement text
     */
    public AiResponse withText(String replacementText) {
        return new AiResponse(replacementText, media, usage, finishReason);
    }
}
