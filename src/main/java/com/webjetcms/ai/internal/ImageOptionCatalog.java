package com.webjetcms.ai.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.webjetcms.ai.ImageOptionDefinition;

/** Shared definitions and helpers for deterministic immutable image option catalogues. */
public final class ImageOptionCatalog {

    /** Exactly one generated image. */
    public static final ImageOptionDefinition COUNT_ONE =
        ImageOptionDefinition.integerRange(1, 1);
    /** Between one and ten generated images. */
    public static final ImageOptionDefinition COUNT_ONE_TO_TEN =
        ImageOptionDefinition.integerRange(1, 10);
    /** Automatic, low, medium, or high image quality. */
    public static final ImageOptionDefinition QUALITY_AUTO_LOW_MEDIUM_HIGH =
        ImageOptionDefinition.choices("auto", "low", "medium", "high");
    /** Automatic, transparent, or opaque image background. */
    public static final ImageOptionDefinition BACKGROUND_AUTO_TRANSPARENT_OPAQUE =
        ImageOptionDefinition.choices("auto", "transparent", "opaque");
    /** Output compression percentage from zero through one hundred. */
    public static final ImageOptionDefinition OUTPUT_COMPRESSION_PERCENT =
        ImageOptionDefinition.integerRange(0, 100);
    /** PNG, JPEG, or WebP output format. */
    public static final ImageOptionDefinition OUTPUT_FORMAT_PNG_JPEG_WEBP =
        ImageOptionDefinition.choices("png", "jpeg", "webp");
    /** One-kilopixel image resolution. */
    public static final ImageOptionDefinition RESOLUTION_1K =
        ImageOptionDefinition.choices("1K");
    /** One-, two-, or four-kilopixel image resolution. */
    public static final ImageOptionDefinition RESOLUTION_1K_2K_4K =
        ImageOptionDefinition.choices("1K", "2K", "4K");
    /** 512-pixel, one-, two-, or four-kilopixel image resolution. */
    public static final ImageOptionDefinition RESOLUTION_512_1K_2K_4K =
        ImageOptionDefinition.choices("512", "1K", "2K", "4K");
    /** Standard aspect ratios supported by Gemini image models. */
    public static final ImageOptionDefinition GEMINI_STANDARD_ASPECT_RATIOS =
        ImageOptionDefinition.choices(
            "1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9"
        );
    /** Extended aspect ratios supported by Gemini image models. */
    public static final ImageOptionDefinition GEMINI_EXTENDED_ASPECT_RATIOS =
        ImageOptionDefinition.choices(
            "1:1", "1:4", "1:8", "2:3", "3:2", "3:4", "4:1", "4:3",
            "4:5", "5:4", "8:1", "9:16", "16:9", "21:9"
        );

    private ImageOptionCatalog() { }

    /**
     * Creates an ordered immutable map from alternating key and definition arguments.
     *
     * @param entries alternating string keys and option definitions
     * @return immutable insertion-ordered map
     */
    public static Map<String, ImageOptionDefinition> options(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("Image option catalogue entries must be key/value pairs");
        }
        Map<String, ImageOptionDefinition> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            String key = (String) entries[index];
            ImageOptionDefinition definition = (ImageOptionDefinition) entries[index + 1];
            result.put(key, definition);
        }
        return Collections.unmodifiableMap(result);
    }
}
