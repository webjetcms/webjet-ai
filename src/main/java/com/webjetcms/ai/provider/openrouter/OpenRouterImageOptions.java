package com.webjetcms.ai.provider.openrouter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.webjetcms.ai.image.ImageOptionDefinition;
import com.webjetcms.ai.image.ImageOptions;
import com.webjetcms.ai.internal.ImageOptionCatalog;

/** OpenRouter image-model option metadata. */
final class OpenRouterImageOptions {

    static final String CATALOGUE_VERSION = "2026-08-25";

    private static final ImageOptionDefinition COUNT_FOUR =
        ImageOptionDefinition.integerRange(1, 4);
    private static final ImageOptionDefinition COUNT_SIX =
        ImageOptionDefinition.integerRange(1, 6);
    private static final ImageOptionDefinition SEED = ImageOptionDefinition.integer();
    private static final ImageOptionDefinition RESOLUTION_1K_2K =
        ImageOptionDefinition.choices("1K", "2K");
    private static final ImageOptionDefinition RESOLUTION_2K_4K =
        ImageOptionDefinition.choices("2K", "4K");
    private static final ImageOptionDefinition RECRAFT_ASPECT = ImageOptionDefinition.choices(
        "1:1", "4:3", "3:4", "16:9", "9:16", "auto"
    );
    private static final ImageOptionDefinition ROUTER_WIDE_ASPECT = ImageOptionDefinition.choices(
        "1:1", "1:2", "2:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4",
        "9:16", "16:9", "9:19.5", "19.5:9", "9:20", "20:9", "9:21", "21:9", "auto"
    );
    private static final ImageOptionDefinition XAI_ASPECT = ImageOptionDefinition.choices(
        "1:1", "3:4", "4:3", "9:16", "16:9", "2:3", "3:2",
        "9:19.5", "19.5:9", "9:20", "20:9", "1:2", "2:1", "auto"
    );
    private static final ImageOptionDefinition RIVER_ASPECT = ImageOptionDefinition.choices(
        "1:1", "4:3", "3:4", "3:2", "2:3", "16:9", "9:16", "21:9", "auto"
    );
    private static final ImageOptionDefinition BACKGROUND_AUTO_OPAQUE =
        ImageOptionDefinition.choices("auto", "opaque");
    private static final Map<String, ImageOptionDefinition> OPENAI_GPT_IMAGE_1 =
        ImageOptionCatalog.options(
            ImageOptions.COUNT, ImageOptionCatalog.COUNT_ONE_TO_TEN,
            "aspect_ratio", ImageOptionDefinition.choices("1:1", "3:2", "2:3", "auto"),
            ImageOptions.QUALITY, ImageOptionCatalog.QUALITY_AUTO_LOW_MEDIUM_HIGH,
            "background", ImageOptionCatalog.BACKGROUND_AUTO_TRANSPARENT_OPAQUE,
            "output_compression", ImageOptionCatalog.OUTPUT_COMPRESSION_PERCENT
        );
    private static final Map<String, ImageOptionDefinition> OPENAI_GPT_IMAGE_2 =
        ImageOptionCatalog.options(
            ImageOptions.COUNT, ImageOptionCatalog.COUNT_ONE_TO_TEN,
            "aspect_ratio", ImageOptionDefinition.choices(
                "1:1", "3:2", "2:3", "4:3", "3:4", "16:9", "9:16", "21:9", "auto"
            ),
            ImageOptions.QUALITY, ImageOptionCatalog.QUALITY_AUTO_LOW_MEDIUM_HIGH,
            "background", ImageOptionDefinition.choices("auto", "opaque"),
            "output_compression", ImageOptionCatalog.OUTPUT_COMPRESSION_PERCENT
        );
    private static final Map<String, ImageOptionDefinition> GEMINI_31_FLASH = routerOptions(
        ImageOptionCatalog.COUNT_ONE,
        ImageOptionCatalog.RESOLUTION_512_1K_2K_4K,
        ImageOptionCatalog.GEMINI_EXTENDED_ASPECT_RATIOS
    );
    private static final Map<String, ImageOptionDefinition> GEMINI_31_LITE = routerOptions(
        ImageOptionCatalog.COUNT_ONE,
        ImageOptionCatalog.RESOLUTION_1K,
        ImageOptionCatalog.GEMINI_EXTENDED_ASPECT_RATIOS
    );
    private static final Map<String, ImageOptionDefinition> GEMINI_3_PRO = routerOptions(
        ImageOptionCatalog.COUNT_ONE,
        ImageOptionCatalog.RESOLUTION_1K_2K_4K,
        ImageOptionCatalog.GEMINI_STANDARD_ASPECT_RATIOS
    );
    private static final Map<String, ImageOptionDefinition> RECRAFT_RASTER =
        ImageOptionCatalog.options(
            ImageOptions.COUNT, COUNT_SIX,
            "aspect_ratio", RECRAFT_ASPECT
        );
    private static final Map<String, ImageOptionDefinition> KREA =
        ImageOptionCatalog.options(
            "resolution", ImageOptionCatalog.RESOLUTION_1K,
            "aspect_ratio", ImageOptionDefinition.choices(
                "1:1", "4:3", "3:2", "16:9", "4:5", "2:3", "9:16"
            ),
            "seed", SEED
        );
    private static final Map<String, ImageOptionDefinition> RIVER_V2 = routerOptions(
        ImageOptionCatalog.COUNT_ONE,
        ImageOptionCatalog.RESOLUTION_1K_2K_4K,
        RIVER_ASPECT
    );
    private static final Map<String, ImageOptionDefinition> RIVER_V25_PRO =
        imageOptionsWithFormat(
            ImageOptionCatalog.RESOLUTION_1K_2K_4K,
            ImageOptionCatalog.OUTPUT_FORMAT_PNG_JPEG_WEBP
        );
    private static final Map<String, ImageOptionDefinition> RIVER_V25_FAST =
        imageOptionsWithFormat(
            RESOLUTION_1K_2K,
            ImageOptionDefinition.choices("jpeg")
        );
    private static final Map<String, ImageOptionDefinition> FLUX_2 =
        ImageOptionCatalog.options(
            ImageOptions.COUNT, ImageOptionCatalog.COUNT_ONE,
            "aspect_ratio", RIVER_ASPECT,
            "output_format", ImageOptionDefinition.choices("png", "jpeg"),
            "seed", SEED
        );

    private OpenRouterImageOptions() { }

    /**
     * Returns the image options supported by an OpenRouter model.
     *
     * @param model OpenRouter model identifier, possibly {@code null}
     * @return immutable supported-option definitions, or an empty map when unsupported
     */
    static Map<String, ImageOptionDefinition> definitions(String model) {
        if (model == null || model.isBlank()) return Map.of();

        if (model.equals("openai/gpt-image-2") || model.equals("openai/gpt-5.4-image-2")) {
            return OPENAI_GPT_IMAGE_2;
        }
        if (model.equals("openai/gpt-image-1")
            || model.equals("openai/gpt-image-1-mini")
            || model.equals("openai/gpt-5-image")
            || model.equals("openai/gpt-5-image-mini")) {
            return OPENAI_GPT_IMAGE_1;
        }
        if (model.equals("google/gemini-3.1-flash-lite-image")) return GEMINI_31_LITE;
        if (model.equals("google/gemini-3.1-flash-image")
            || model.equals("google/gemini-3.1-flash-image-preview")) {
            return GEMINI_31_FLASH;
        }
        if (model.equals("google/gemini-3-pro-image")
            || model.equals("google/gemini-3-pro-image-preview")) {
            return GEMINI_3_PRO;
        }
        if (model.equals("google/gemini-2.5-flash-image")) {
            return ImageOptionCatalog.options(
                ImageOptions.COUNT, ImageOptionCatalog.COUNT_ONE,
                "aspect_ratio", ImageOptionCatalog.GEMINI_STANDARD_ASPECT_RATIOS
            );
        }
        if (isRecraftVectorModel(model)) return Map.of();
        if (isRecraftRasterModel(model)) return RECRAFT_RASTER;
        if (model.equals("bytedance-seed/seedream-5-0-lite")) {
            return seededRouterOptions(COUNT_FOUR, RESOLUTION_2K_4K, ROUTER_WIDE_ASPECT);
        }
        if (model.equals("bytedance-seed/seedream-5-0-pro")) {
            return seededRouterOptions(
                ImageOptionCatalog.COUNT_ONE,
                RESOLUTION_1K_2K,
                ROUTER_WIDE_ASPECT
            );
        }
        if (model.equals("bytedance-seed/seedream-4.5")) {
            return seededRouterOptions(
                ImageOptionCatalog.COUNT_ONE_TO_TEN,
                ImageOptionCatalog.RESOLUTION_1K_2K_4K,
                ROUTER_WIDE_ASPECT
            );
        }
        if (model.equals("x-ai/grok-imagine-image-2.0")) {
            return qualityRouterOptions(
                RESOLUTION_1K_2K,
                XAI_ASPECT,
                ImageOptionDefinition.choices("low", "medium")
            );
        }
        if (model.equals("x-ai/grok-imagine-image-quality")) {
            return routerOptions(ImageOptionCatalog.COUNT_ONE, RESOLUTION_1K_2K, XAI_ASPECT);
        }
        if (model.equals("qwen/qwen-image-3") || model.equals("qwen/qwen-image-3-pro")) {
            return seededRouterOptions(
                COUNT_SIX,
                RESOLUTION_1K_2K,
                ImageOptionDefinition.choices(
                    "1:1", "1:2", "1:4", "2:1", "2:3", "3:2", "3:4",
                    "4:1", "4:3", "4:5", "5:4", "9:16", "16:9"
                )
            );
        }
        if (model.equals("krea/krea-2-large")
            || model.equals("krea/krea-2-medium")
            || model.equals("krea/krea-2-medium-turbo")) {
            return KREA;
        }
        if (model.equals("microsoft/mai-image-2.5")
            || model.equals("microsoft/mai-image-2.5-pro")) {
            return ImageOptionCatalog.options(
                ImageOptions.COUNT, ImageOptionCatalog.COUNT_ONE,
                "aspect_ratio", ImageOptionDefinition.choices(
                    "1:1", "4:3", "3:4", "16:9", "9:16", "3:2", "2:3", "auto"
                )
            );
        }
        if (model.equals("sourceful/riverflow-v2.5-pro")) return RIVER_V25_PRO;
        if (model.equals("sourceful/riverflow-v2.5-fast")) return RIVER_V25_FAST;
        if (model.equals("sourceful/riverflow-v2-pro")
            || model.equals("sourceful/riverflow-v2-fast")) {
            return RIVER_V2;
        }
        return isFlux2Model(model) ? FLUX_2 : Map.of();
    }

    /**
     * Indicates whether an OpenRouter model produces vector output through Recraft.
     *
     * @param model non-null OpenRouter model identifier
     * @return {@code true} for a recognized Recraft vector model
     */
    static boolean isRecraftVectorModel(String model) {
        return switch (model) {
            case "recraft/recraft-v4.1-pro-vector",
                 "recraft/recraft-v4.1-vector",
                 "recraft/recraft-v4-pro-vector",
                 "recraft/recraft-v4-vector" -> true;
            default -> false;
        };
    }

    /**
     * Returns the only advertised output format when a model fixes that option.
     *
     * @param model OpenRouter model identifier, possibly {@code null}
     * @return the single output format, or {@code null} when none is fixed
     */
    static String singleOutputFormat(String model) {
        ImageOptionDefinition definition = definitions(model).get("output_format");
        return definition != null && definition.allowedValues().size() == 1
            ? definition.allowedValues().get(0)
            : null;
    }

    private static Map<String, ImageOptionDefinition> routerOptions(
        ImageOptionDefinition count,
        ImageOptionDefinition resolution,
        ImageOptionDefinition aspectRatio
    ) {
        return ImageOptionCatalog.options(
            ImageOptions.COUNT, count,
            "resolution", resolution,
            "aspect_ratio", aspectRatio
        );
    }

    private static Map<String, ImageOptionDefinition> seededRouterOptions(
        ImageOptionDefinition count,
        ImageOptionDefinition resolution,
        ImageOptionDefinition aspectRatio
    ) {
        Map<String, ImageOptionDefinition> options = new LinkedHashMap<>(
            routerOptions(count, resolution, aspectRatio)
        );
        options.put("seed", SEED);
        return Collections.unmodifiableMap(options);
    }

    private static Map<String, ImageOptionDefinition> qualityRouterOptions(
        ImageOptionDefinition resolution,
        ImageOptionDefinition aspectRatio,
        ImageOptionDefinition quality
    ) {
        Map<String, ImageOptionDefinition> options = new LinkedHashMap<>(
            routerOptions(ImageOptionCatalog.COUNT_ONE, resolution, aspectRatio)
        );
        options.put(ImageOptions.QUALITY, quality);
        return Collections.unmodifiableMap(options);
    }

    private static Map<String, ImageOptionDefinition> imageOptionsWithFormat(
        ImageOptionDefinition resolution,
        ImageOptionDefinition outputFormat
    ) {
        Map<String, ImageOptionDefinition> options = new LinkedHashMap<>(
            routerOptions(ImageOptionCatalog.COUNT_ONE, resolution, RIVER_ASPECT)
        );
        options.put("output_format", outputFormat);
        options.put(
            "background",
            supportsTransparentBackground(outputFormat)
                ? ImageOptionCatalog.BACKGROUND_AUTO_TRANSPARENT_OPAQUE
                : BACKGROUND_AUTO_OPAQUE
        );
        return Collections.unmodifiableMap(options);
    }

    private static boolean supportsTransparentBackground(
        ImageOptionDefinition outputFormat
    ) {
        return outputFormat.allowedValues().stream()
            .anyMatch(format -> "png".equalsIgnoreCase(format) || "webp".equalsIgnoreCase(format));
    }

    private static boolean isRecraftRasterModel(String model) {
        return switch (model) {
            case "recraft/recraft-v4.1-utility-pro",
                 "recraft/recraft-v4.1-utility",
                 "recraft/recraft-v4.1-pro",
                 "recraft/recraft-v4.1",
                 "recraft/recraft-v4-pro",
                 "recraft/recraft-v4",
                 "recraft/recraft-v3" -> true;
            default -> false;
        };
    }

    private static boolean isFlux2Model(String model) {
        return switch (model) {
            case "black-forest-labs/flux.2-klein-4b",
                 "black-forest-labs/flux.2-max",
                 "black-forest-labs/flux.2-flex",
                 "black-forest-labs/flux.2-pro" -> true;
            default -> false;
        };
    }
}
