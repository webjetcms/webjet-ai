package com.webjetcms.ai.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.image.ImageOptionDefinition;
import com.webjetcms.ai.image.ImageOptions;

/** Shared normalization and local validation for built-in image providers. */
public final class ImageOptionValidator {

    private ImageOptionValidator() { }

    /**
     * Returns explicitly supplied options after validating them against model metadata.
     *
     * @param providerId stable provider identifier for validation failures
     * @param model selected provider model
     * @param operation image generation or editing operation
     * @param options explicitly supplied image options, or {@code null}
     * @param definitions supported options for the model and operation
     * @return immutable explicitly supplied values in wire order
     * @throws AiProviderException when a supplied option is unsupported or invalid
     */
    public static Map<String, Object> validate(
        String providerId,
        String model,
        AiOperation operation,
        ImageOptions options,
        Map<String, ImageOptionDefinition> definitions
    ) throws AiProviderException {
        if (options == null || options.hasExplicitOptions() == false) {
            return Map.of();
        }
        if (definitions == null || definitions.isEmpty()) {
            throw failure(
                providerId,
                model,
                "does not have catalogued image options for " + operation
            );
        }

        Map<String, Object> supplied = suppliedValues(options);
        for (Map.Entry<String, Object> option : supplied.entrySet()) {
            ImageOptionDefinition definition = definitions.get(option.getKey());
            if (definition == null) {
                throw failure(
                    providerId,
                    model,
                    "does not support image option '" + option.getKey() + "' for " + operation
                );
            }
            if (definition.accepts(option.getValue()) == false) {
                throw failure(
                    providerId,
                    model,
                    "does not accept value '" + option.getValue() + "' for image option '"
                        + option.getKey() + "'"
                );
            }
        }
        validateCombinations(providerId, model, supplied, definitions);
        return Collections.unmodifiableMap(supplied);
    }

    /**
     * Returns the non-blank portable and provider-specific values in wire order.
     *
     * @param options image options, or {@code null}
     * @return caller-owned ordered values, or an immutable empty map
     */
    public static Map<String, Object> suppliedValues(ImageOptions options) {
        if (options == null) {
            return Map.of();
        }
        Map<String, Object> supplied = new LinkedHashMap<>();
        if (options.count() != null) {
            supplied.put(ImageOptions.COUNT, options.count());
        }
        if (isNotBlank(options.size())) {
            supplied.put(ImageOptions.SIZE, options.size());
        }
        if (isNotBlank(options.quality())) {
            supplied.put(ImageOptions.QUALITY, options.quality());
        }
        supplied.putAll(options.providerOptions());
        return supplied;
    }

    private static void validateCombinations(
        String providerId,
        String model,
        Map<String, Object> supplied,
        Map<String, ImageOptionDefinition> definitions
    ) throws AiProviderException {
        String format = normalizedString(supplied.get("output_format"));
        String background = normalizedString(supplied.get("background"));
        if ("transparent".equals(background)
            && format != null
            && "png".equals(format) == false
            && "webp".equals(format) == false) {
            throw failure(
                providerId,
                model,
                "requires png or webp output_format for a transparent background"
            );
        }
        if (supplied.containsKey("output_compression")
            && definitions.containsKey("output_format")
            && "jpeg".equals(format) == false
            && "webp".equals(format) == false) {
            throw failure(
                providerId,
                model,
                "requires jpeg or webp output_format when output_compression is set"
            );
        }
    }

    private static AiProviderException failure(String providerId, String model, String message) {
        return new AiProviderException(
            providerId,
            "Image model '" + model + "' " + message
        );
    }

    private static String normalizedString(Object value) {
        return value instanceof String text ? text.toLowerCase(Locale.ROOT) : null;
    }

    private static boolean isNotBlank(String value) {
        return value != null && value.isBlank() == false;
    }
}
