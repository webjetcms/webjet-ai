package com.webjetcms.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Optional image rendering parameters understood by capable providers and models.
 *
 * <p>{@code count}, {@code size}, and {@code quality} are the portable options used by
 * more than one provider. Other rendering controls retain the provider's documented
 * wire name in {@link #providerOptions()}.</p>
 *
 * @param count requested number of images, or {@code null} for the provider default
 * @param size provider-supported dimensions or resolution, or {@code null}
 * @param quality provider-supported quality level, or {@code null}
 * @param providerOptions immutable provider-specific scalar rendering options
 */
public record ImageOptions(
    Integer count,
    String size,
    String quality,
    Map<String, Object> providerOptions
) {

    /** Portable option key for {@link #count()}. */
    public static final String COUNT = "count";
    /** Portable option key for {@link #size()}. */
    public static final String SIZE = "size";
    /** Portable option key for {@link #quality()}. */
    public static final String QUALITY = "quality";

    /**
     * Creates options while defensively copying and validating provider-specific values.
     */
    public ImageOptions {
        providerOptions = immutableProviderOptions(providerOptions);
    }

    /**
     * Preserves the original source-compatible constructor for portable image options.
     *
     * @param count requested number of images, or {@code null}
     * @param size provider-supported dimensions or resolution, or {@code null}
     * @param quality provider-supported quality level, or {@code null}
     */
    public ImageOptions(Integer count, String size, String quality) {
        this(count, size, quality, Map.of());
    }

    /**
     * Creates an empty image-options builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether at least one non-blank image option was explicitly supplied.
     *
     * @return {@code true} when validation and serialization are required
     */
    public boolean hasExplicitOptions() {
        return count != null || isNotBlank(size) || isNotBlank(quality) || providerOptions.isEmpty() == false;
    }

    /**
     * Returns option metadata without exposing provider-specific names or values.
     *
     * @return a credential-safe option summary
     */
    @Override
    public String toString() {
        return "ImageOptions[count=" + count
            + ", sizeConfigured=" + isNotBlank(size)
            + ", qualityConfigured=" + isNotBlank(quality)
            + ", providerOptionCount=" + providerOptions.size() + "]";
    }

    private static Map<String, Object> immutableProviderOptions(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        options.forEach((key, value) -> putProviderOption(copy, key, value));
        return Collections.unmodifiableMap(copy);
    }

    private static void putProviderOption(Map<String, Object> target, String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Image provider option key must not be blank");
        }
        if (COUNT.equals(key) || SIZE.equals(key) || QUALITY.equals(key)) {
            throw new IllegalArgumentException(
                "Portable image option must use its typed field: " + key
            );
        }
        if (value instanceof String == false
            && value instanceof Integer == false
            && value instanceof Long == false
            && value instanceof Boolean == false) {
            throw new IllegalArgumentException(
                "Image provider option values must be String, Integer, Long, or Boolean: " + key
            );
        }
        target.put(key, value);
    }

    private static boolean isNotBlank(String value) {
        return value != null && value.isBlank() == false;
    }

    /** Mutable builder that produces immutable {@link ImageOptions} values. */
    public static final class Builder {
        private Integer count;
        private String size;
        private String quality;
        private final Map<String, Object> providerOptions = new LinkedHashMap<>();

        private Builder() { }

        /**
         * Sets the portable requested image count.
         *
         * @param count desired number of images, or {@code null}
         * @return this builder
         */
        public Builder count(Integer count) {
            this.count = count;
            return this;
        }

        /**
         * Sets the portable provider-supported size or resolution.
         *
         * @param size dimensions or resolution, or {@code null}
         * @return this builder
         */
        public Builder size(String size) {
            this.size = size;
            return this;
        }

        /**
         * Sets the portable provider-supported quality.
         *
         * @param quality quality name, or {@code null}
         * @return this builder
         */
        public Builder quality(String quality) {
            this.quality = quality;
            return this;
        }

        /**
         * Adds or replaces one provider-specific scalar rendering option.
         *
         * @param key provider wire name
         * @param value immutable scalar value
         * @return this builder
         */
        public Builder providerOption(String key, Object value) {
            putProviderOption(providerOptions, key, value);
            return this;
        }

        /**
         * Replaces all provider-specific options with values from the supplied map.
         *
         * @param options provider wire names and immutable scalar values
         * @return this builder
         */
        public Builder providerOptions(Map<String, ?> options) {
            Objects.requireNonNull(options, "options");
            providerOptions.clear();
            options.forEach((key, value) -> putProviderOption(providerOptions, key, value));
            return this;
        }

        /**
         * Builds an immutable options value.
         *
         * @return defensively copied image options
         */
        public ImageOptions build() {
            return new ImageOptions(count, size, quality, providerOptions);
        }
    }
}
