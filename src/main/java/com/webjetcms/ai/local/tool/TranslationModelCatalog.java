package com.webjetcms.ai.local.tool;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Loads the approved translation-model recipe from the shared catalogue resource. */
final class TranslationModelCatalog {
    static final String RESOURCE = "META-INF/webjet-ai/local-translation-model-catalog-v1.properties";
    static final TranslationModelCatalog INSTANCE = load();
    static final List<String> ARTIFACT_NAMES = List.of(
        "config.json",
        "generation_config.json",
        "tokenizer.json",
        "tokenizer_config.json",
        "special_tokens_map.json",
        "vocab.json",
        "sentencepiece.bpe.model",
        "MODEL_CARD.md"
    );

    private final Properties values;

    private TranslationModelCatalog(Properties values) {
        this.values = values;
        if (integer("catalog.version") != 1) {
            throw new IllegalStateException("Unsupported local translation-model catalogue version");
        }
    }

    String required(String key) {
        String value = values.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Missing local translation-model catalogue value: " + key);
        }
        return value;
    }

    int integer(String key) {
        try {
            return Integer.parseInt(required(key));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid integer in local translation-model catalogue: " + key, exception);
        }
    }

    long longValue(String key) {
        try {
            return Long.parseLong(required(key));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid long in local translation-model catalogue: " + key, exception);
        }
    }

    Set<String> aliases() {
        return Set.of(required("model.aliases").split(",", -1));
    }

    String variantValue(ModelVariant variant, String suffix) {
        return required("variant." + variant.cliName() + "." + suffix);
    }

    String artifactValue(String name, String suffix) {
        return required("artifact." + name + "." + suffix);
    }

    long artifactLong(String name, String suffix) {
        return longValue("artifact." + name + "." + suffix);
    }

    private static TranslationModelCatalog load() {
        Properties values = new Properties();
        ClassLoader loader = TranslationModelCatalog.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing local translation-model catalogue: " + RESOURCE);
            }
            values.load(input);
            return new TranslationModelCatalog(values);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read local translation-model catalogue", exception);
        }
    }
}
