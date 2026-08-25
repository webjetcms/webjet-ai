package com.webjetcms.ai.provider.openai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.image.ImageOptionDefinition;
import com.webjetcms.ai.image.ImageOptions;
import com.webjetcms.ai.internal.ImageOptionCatalog;
import com.webjetcms.ai.internal.ImageOptionValidator;

/** OpenAI image-option metadata and validation. */
final class OpenAiImageOptions {

    private static final String GPT_IMAGE_2_SIZE_PATTERN =
        "(?:auto|[1-9][0-9]*x[1-9][0-9]*)";
    private static final ImageOptionDefinition GPT_IMAGE_SIZE = ImageOptionDefinition.choices(
        "auto", "1024x1024", "1024x1536", "1536x1024"
    );
    private static final ImageOptionDefinition GPT_IMAGE_2_SIZE = ImageOptionDefinition.patterned(
        GPT_IMAGE_2_SIZE_PATTERN,
        "auto", "1024x1024", "1024x1536", "1536x1024", "2048x2048",
        "2048x1152", "3840x2160", "2160x3840"
    );
    private static final ImageOptionDefinition MODERATION = ImageOptionDefinition.choices(
        "auto", "low"
    );
    private static final ImageOptionDefinition INPUT_FIDELITY = ImageOptionDefinition.choices(
        "low", "high"
    );
    private static final Map<String, ImageOptionDefinition> GPT_IMAGE_OPTIONS =
        gptImageOptions(GPT_IMAGE_SIZE, false);
    private static final Map<String, ImageOptionDefinition> GPT_IMAGE_EDIT_OPTIONS =
        gptImageOptions(GPT_IMAGE_SIZE, true);
    private static final Map<String, ImageOptionDefinition> GPT_IMAGE_2_OPTIONS =
        gptImageOptions(GPT_IMAGE_2_SIZE, false);
    private static final Map<String, ImageOptionDefinition> GPT_IMAGE_2_EDIT_OPTIONS =
        gptImageOptions(GPT_IMAGE_2_SIZE, false);
    private static final Map<String, ImageOptionDefinition> DALL_E_2_OPTIONS =
        ImageOptionCatalog.options(
            ImageOptions.COUNT, ImageOptionCatalog.COUNT_ONE_TO_TEN,
            ImageOptions.SIZE, ImageOptionDefinition.choices("256x256", "512x512", "1024x1024"),
            ImageOptions.QUALITY, ImageOptionDefinition.choices("standard")
        );
    private static final Map<String, ImageOptionDefinition> DALL_E_2_EDIT_OPTIONS =
        ImageOptionCatalog.options(
            ImageOptions.COUNT, ImageOptionCatalog.COUNT_ONE_TO_TEN,
            ImageOptions.SIZE, ImageOptionDefinition.choices("256x256", "512x512", "1024x1024")
        );
    private static final Map<String, ImageOptionDefinition> DALL_E_3_OPTIONS =
        ImageOptionCatalog.options(
            ImageOptions.COUNT, ImageOptionCatalog.COUNT_ONE,
            ImageOptions.SIZE, ImageOptionDefinition.choices("1024x1024", "1024x1792", "1792x1024"),
            ImageOptions.QUALITY, ImageOptionDefinition.choices("standard", "hd"),
            "style", ImageOptionDefinition.choices("vivid", "natural")
        );

    private OpenAiImageOptions() { }

    static Map<String, ImageOptionDefinition> definitions(
        String model,
        AiOperation operation
    ) {
        if (model == null || operation == null) return Map.of();

        if (operation == AiOperation.GENERATE_IMAGE) {
            if ("dall-e-2".equals(model)) return DALL_E_2_OPTIONS;
            if ("dall-e-3".equals(model)) return DALL_E_3_OPTIONS;
            if (isGptImage2(model)) return GPT_IMAGE_2_OPTIONS;
            if (isEarlierGptImage(model)) return GPT_IMAGE_OPTIONS;
            return Map.of();
        }
        if (operation == AiOperation.EDIT_IMAGE) {
            if ("dall-e-2".equals(model)) return DALL_E_2_EDIT_OPTIONS;
            if (isGptImage2(model)) return GPT_IMAGE_2_EDIT_OPTIONS;
            if (isEarlierGptImage(model) || "chatgpt-image-latest".equals(model)) {
                return GPT_IMAGE_EDIT_OPTIONS;
            }
        }
        return Map.of();
    }

    static Map<String, Object> validate(String providerId, AiRequest request)
        throws AiProviderException {
        Map<String, ImageOptionDefinition> definitions = definitions(
            request.model(),
            request.operation()
        );
        ImageOptions options = request.imageOptions();
        if (definitions.isEmpty()
            && (options == null || options.providerOptions().isEmpty())) {
            // Compatible endpoints may use model IDs absent from OpenAI's catalogue.
            Map<String, Object> supplied = ImageOptionValidator.suppliedValues(options);
            return supplied.isEmpty() ? Map.of() : Collections.unmodifiableMap(supplied);
        }

        Map<String, Object> validated = ImageOptionValidator.validate(
            providerId,
            request.model(),
            request.operation(),
            request.imageOptions(),
            definitions
        );
        if (isGptImage2(request.model())) {
            validateGptImage2Size(
                providerId,
                request.model(),
                validated.get(ImageOptions.SIZE)
            );
        }
        return validated;
    }

    private static Map<String, ImageOptionDefinition> gptImageOptions(
        ImageOptionDefinition size,
        boolean inputFidelity
    ) {
        Map<String, ImageOptionDefinition> options = new LinkedHashMap<>();
        options.put(ImageOptions.COUNT, ImageOptionCatalog.COUNT_ONE_TO_TEN);
        options.put(ImageOptions.SIZE, size);
        options.put(ImageOptions.QUALITY, ImageOptionCatalog.QUALITY_AUTO_LOW_MEDIUM_HIGH);
        options.put("background", ImageOptionCatalog.BACKGROUND_AUTO_TRANSPARENT_OPAQUE);
        options.put("output_format", ImageOptionCatalog.OUTPUT_FORMAT_PNG_JPEG_WEBP);
        options.put("output_compression", ImageOptionCatalog.OUTPUT_COMPRESSION_PERCENT);
        options.put("moderation", MODERATION);
        if (inputFidelity) options.put("input_fidelity", INPUT_FIDELITY);
        return Collections.unmodifiableMap(options);
    }

    private static boolean isGptImage2(String model) {
        return "gpt-image-2".equals(model) || "gpt-image-2-2026-04-21".equals(model);
    }

    private static boolean isEarlierGptImage(String model) {
        if (model == null) return false;
        return model.equals("gpt-image-1")
            || model.equals("gpt-image-1-mini")
            || model.equals("gpt-image-1.5")
            || model.equals("gpt-image-1.5-2025-12-16")
            || model.equals("chatgpt-image-latest");
    }

    private static void validateGptImage2Size(
        String providerId,
        String model,
        Object rawSize
    ) throws AiProviderException {
        if (rawSize == null || "auto".equals(rawSize)) return;

        String size = (String) rawSize;
        int separator = size.indexOf('x');
        try {
            long width = Long.parseLong(size.substring(0, separator));
            long height = Long.parseLong(size.substring(separator + 1));
            long shortEdge = Math.min(width, height);
            long longEdge = Math.max(width, height);
            long pixels = Math.multiplyExact(width, height);
            if (width % 16 != 0
                || height % 16 != 0
                || longEdge > 3840
                || longEdge > shortEdge * 3
                || pixels < 655_360
                || pixels > 8_294_400) {
                throw invalidSize(providerId, model, size);
            }
        } catch (ArithmeticException | IndexOutOfBoundsException | NumberFormatException exception) {
            throw invalidSize(providerId, model, size);
        }
    }

    private static AiProviderException invalidSize(
        String providerId,
        String model,
        String size
    ) {
        return new AiProviderException(
            providerId,
            "Image model '" + model + "' does not accept size '" + size + "'"
        );
    }
}
