package com.webjetcms.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.webjetcms.ai.image.ImageOptionDefinition;
import com.webjetcms.ai.image.ImageOptions;
import com.webjetcms.ai.internal.ImageOptionValidator;

class ImageOptionsTest {

    private static final Map<String, ImageOptionDefinition> OUTPUT_OPTIONS = Map.of(
        "background", ImageOptionDefinition.choices("auto", "transparent", "opaque"),
        "output_format", ImageOptionDefinition.choices("png", "jpeg", "webp"),
        "output_compression", ImageOptionDefinition.integerRange(0, 100)
    );
    private static final Map<String, ImageOptionDefinition> JPEG_ONLY_OUTPUT_OPTIONS = Map.of(
        "background", ImageOptionDefinition.choices("auto", "transparent", "opaque"),
        "output_format", ImageOptionDefinition.choices("jpeg"),
        "output_compression", ImageOptionDefinition.integerRange(0, 100)
    );

    @Test
    void imageOptionValuesAreTypedImmutableAndSecretSafe() {
        ImageOptions portable = new ImageOptions(2, "1024x1024", "high");
        assertEquals(List.of(2, "1024x1024", "high"), List.of(
            portable.count(), portable.size(), portable.quality()
        ));

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("background", "transparent");
        ImageOptions options = ImageOptions.builder()
            .providerOptions(source)
            .providerOption("output_compression", 80)
            .build();
        source.clear();

        assertEquals(
            Map.of("background", "transparent", "output_compression", 80),
            options.providerOptions()
        );
        assertThrows(UnsupportedOperationException.class, options.providerOptions()::clear);
        assertThrows(IllegalArgumentException.class,
            () -> ImageOptions.builder().providerOption("count", 2));
        assertThrows(IllegalArgumentException.class,
            () -> ImageOptions.builder().providerOption("colors", List.of("red")));

        ImageOptionDefinition choices = ImageOptionDefinition.choices("low", "high");
        ImageOptionDefinition range = ImageOptionDefinition.integerRange(1, 10);
        ImageOptionDefinition pattern = ImageOptionDefinition.patterned(
            "[1-9][0-9]*x[1-9][0-9]*", "auto"
        );
        assertEquals(List.of(true, false, true, false, true, true), List.of(
            choices.accepts("high"), choices.accepts("medium"),
            range.accepts(1), range.accepts(11),
            pattern.accepts("auto"), pattern.accepts("1024x1024")
        ));
        assertThrows(UnsupportedOperationException.class, choices.allowedValues()::clear);

        String secret = "provider-option-secret";
        String description = ImageOptions.builder()
            .size("sensitive-size")
            .quality("sensitive-quality")
            .providerOption("api_key", secret)
            .build()
            .toString();
        assertFalse(description.contains(secret));
        assertFalse(description.contains("api_key"));
        assertFalse(description.contains("sensitive-size"));
        assertFalse(description.contains("sensitive-quality"));
        assertTrue(description.contains("providerOptionCount=1"));
    }

    @Test
    void validatorRejectsUnsupportedInvalidAndUnsafeCombinations() {
        Map<String, ImageOptionDefinition> countOnly = Map.of(
            ImageOptions.COUNT, ImageOptionDefinition.integerRange(1, 1)
        );
        record Failure(
            ImageOptions options,
            Map<String, ImageOptionDefinition> definitions,
            String message
        ) { }
        List<Failure> failures = List.of(
            new Failure(new ImageOptions(2, null, null), null, "does not have catalogued"),
            new Failure(options("style", "vivid"), countOnly, "does not support image option 'style'"),
            new Failure(new ImageOptions(2, null, null), countOnly, "does not accept value '2'"),
            new Failure(
                options("background", "transparent", "output_format", "jpeg"),
                OUTPUT_OPTIONS,
                "requires png or webp"
            ),
            new Failure(
                options("background", "transparent"),
                JPEG_ONLY_OUTPUT_OPTIONS,
                "requires png or webp"
            ),
            new Failure(
                options("output_compression", 80),
                OUTPUT_OPTIONS,
                "requires jpeg or webp"
            ),
            new Failure(
                options("output_format", "png", "output_compression", 80),
                OUTPUT_OPTIONS,
                "requires jpeg or webp"
            )
        );

        for (Failure failure : failures) {
            AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> validate(failure.options(), failure.definitions())
            );
            assertTrue(exception.getMessage().contains(failure.message()), failure.message());
        }
    }

    @Test
    void validatorAcceptsSafeExplicitOptions() throws Exception {
        assertEquals(Map.of(), validate(null, null));
        assertEquals(
            Map.of("background", "transparent"),
            validate(options("background", "transparent"), OUTPUT_OPTIONS)
        );
        ImageOptions webp = options(
            "background", "transparent",
            "output_format", "webp",
            "output_compression", 80
        );
        assertEquals(webp.providerOptions(), validate(webp, OUTPUT_OPTIONS));
        assertEquals(
            Map.of("output_compression", 80),
            validate(options("output_compression", 80), JPEG_ONLY_OUTPUT_OPTIONS)
        );
        assertEquals(
            Map.of("output_compression", 80),
            validate(
                options("output_compression", 80),
                Map.of("output_compression", ImageOptionDefinition.integerRange(0, 100))
            )
        );
    }

    @Test
    void clientExposesDefensiveCapabilitiesAndCompatibleProviderDefault() throws Exception {
        Map<String, ImageOptionDefinition> mutable = new LinkedHashMap<>();
        mutable.put("quality", ImageOptionDefinition.choices("low", "high"));

        try (AiClient client = AiClient.of(new MetadataProvider(mutable))) {
            Map<String, ImageOptionDefinition> options = client.imageOptions(
                "image-model", AiOperation.GENERATE_IMAGE
            );
            mutable.clear();
            assertEquals(List.of("quality"), List.copyOf(options.keySet()));
            assertThrows(UnsupportedOperationException.class, options::clear);
        }
        try (ProviderWithoutMetadata provider = new ProviderWithoutMetadata()) {
            assertEquals(Map.of(), provider.imageOptions("image-model", AiOperation.GENERATE_IMAGE));
            assertThrows(IllegalArgumentException.class,
                () -> provider.imageOptions("image-model", AiOperation.TEXT));
        }
    }

    private static ImageOptions options(Object... entries) {
        ImageOptions.Builder builder = ImageOptions.builder();
        for (int index = 0; index < entries.length; index += 2) {
            builder.providerOption((String) entries[index], entries[index + 1]);
        }
        return builder.build();
    }

    private static Map<String, Object> validate(
        ImageOptions options,
        Map<String, ImageOptionDefinition> definitions
    ) throws AiProviderException {
        return ImageOptionValidator.validate(
            "provider", "image-model", AiOperation.GENERATE_IMAGE, options, definitions
        );
    }

    private static class ProviderWithoutMetadata implements AiProvider {
        @Override public String id() { return "without-metadata"; }
        @Override public List<ModelInfo> listModels(AiProviderConfig config) { return List.of(); }
        @Override public AiResponse execute(AiRequest request, AiProviderConfig config) {
            return AiResponse.text("");
        }
        @Override public AiResponse stream(
            AiRequest request,
            AiProviderConfig config,
            AiStreamListener listener
        ) {
            return AiResponse.text("");
        }
    }

    private static final class MetadataProvider extends ProviderWithoutMetadata {
        private final Map<String, ImageOptionDefinition> options;

        private MetadataProvider(Map<String, ImageOptionDefinition> options) {
            this.options = options;
        }

        @Override public String id() { return "metadata"; }
        @Override public Map<String, ImageOptionDefinition> imageOptions(
            String model,
            AiOperation operation
        ) {
            return options;
        }
    }
}
