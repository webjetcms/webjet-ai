package com.webjetcms.ai.provider.gemini;

import java.util.Map;

import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.image.ImageOptionDefinition;
import com.webjetcms.ai.image.ImageOptions;
import com.webjetcms.ai.internal.ImageOptionCatalog;

/** Gemini image-option metadata. */
final class GeminiImageOptions {

    private static final Map<String, ImageOptionDefinition> GEMINI_25 =
        ImageOptionCatalog.options(
            "aspectRatio", ImageOptionCatalog.GEMINI_STANDARD_ASPECT_RATIOS
        );
    private static final Map<String, ImageOptionDefinition> GEMINI_3_PRO =
        ImageOptionCatalog.options(
            ImageOptions.SIZE, ImageOptionCatalog.RESOLUTION_1K_2K_4K,
            "aspectRatio", ImageOptionCatalog.GEMINI_STANDARD_ASPECT_RATIOS
        );
    private static final Map<String, ImageOptionDefinition> GEMINI_31_FLASH =
        ImageOptionCatalog.options(
            ImageOptions.SIZE, ImageOptionCatalog.RESOLUTION_512_1K_2K_4K,
            "aspectRatio", ImageOptionCatalog.GEMINI_EXTENDED_ASPECT_RATIOS
        );
    private static final Map<String, ImageOptionDefinition> GEMINI_31_FLASH_LITE =
        ImageOptionCatalog.options(
            ImageOptions.SIZE, ImageOptionCatalog.RESOLUTION_1K,
            "aspectRatio", ImageOptionCatalog.GEMINI_EXTENDED_ASPECT_RATIOS
        );

    private GeminiImageOptions() { }

    static Map<String, ImageOptionDefinition> definitions(
        String normalizedModel,
        AiOperation operation
    ) {
        if (operation != AiOperation.GENERATE_IMAGE && operation != AiOperation.EDIT_IMAGE) {
            return Map.of();
        }
        if (normalizedModel.equals("gemini-3.1-flash-lite-image")) {
            return GEMINI_31_FLASH_LITE;
        }
        if (normalizedModel.equals("gemini-3.1-flash-image")
            || normalizedModel.equals("gemini-3.1-flash-image-preview")) {
            return GEMINI_31_FLASH;
        }
        if (normalizedModel.equals("gemini-3-pro-image")
            || normalizedModel.equals("gemini-3-pro-image-preview")) {
            return GEMINI_3_PRO;
        }
        if (normalizedModel.equals("gemini-2.5-flash-image")) {
            return GEMINI_25;
        }
        return Map.of();
    }
}
