package com.webjetcms.ai;

/**
 * Optional image generation parameters understood by capable providers.
 * Provider adapters may apply their own defaults or reject unsupported values.
 *
 * @param count requested number of images, or {@code null} for the provider default
 * @param size provider-supported dimensions such as {@code 1024x1024}, or {@code null}
 * @param quality provider-supported quality level, or {@code null}
 */
public record ImageOptions(Integer count, String size, String quality) {
}
