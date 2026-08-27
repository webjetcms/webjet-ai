package com.webjetcms.ai.local.tool;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Loads the approved local embedding-model recipe shared through the core resource. */
final class EmbeddingModelCatalog {
    static final String RESOURCE = "META-INF/webjet-ai/local-embedding-model-catalog-v1.properties";
    static final EmbeddingModelCatalog INSTANCE = load();

    private static final List<String> ARTIFACT_NAMES = List.of(
        "config.json",
        "tokenizer.json",
        "tokenizer_config.json",
        "special_tokens_map.json",
        "sentencepiece.bpe.model",
        "MODEL_CARD.md"
    );

    private final Properties values;

    private EmbeddingModelCatalog(Properties values) {
        this.values = values;
        if (integer("catalog.version") != 1) {
            throw new IllegalStateException("Unsupported local embedding-model catalogue version");
        }
    }

    String canonicalId() { return required("model.canonical-id"); }

    Set<String> aliases() { return Set.of(required("model.aliases").split(",", -1)); }

    String revision() { return required("model.revision"); }

    int dimensions() { return integer("model.dimensions"); }

    int maximumLength() { return integer("model.maximum-length"); }

    String variantValue(ModelVariant variant, String suffix) {
        return required("variant." + variant.cliName() + "." + suffix);
    }

    long variantLong(ModelVariant variant, String suffix) {
        return longValue("variant." + variant.cliName() + "." + suffix);
    }

    List<String> artifactNames() { return ARTIFACT_NAMES; }

    String artifactValue(String bundleName, String suffix) {
        return required("artifact." + bundleName + "." + suffix);
    }

    long artifactLong(String bundleName, String suffix) {
        return longValue("artifact." + bundleName + "." + suffix);
    }

    private String required(String key) {
        String value = values.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Missing local embedding-model catalogue value: " + key);
        }
        return value;
    }

    private int integer(String key) {
        try {
            return Integer.parseInt(required(key));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid integer in local embedding-model catalogue: " + key, exception);
        }
    }

    private long longValue(String key) {
        try {
            return Long.parseLong(required(key));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid long in local embedding-model catalogue: " + key, exception);
        }
    }

    private static EmbeddingModelCatalog load() {
        Properties values = new Properties();
        ClassLoader loader = EmbeddingModelCatalog.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing local embedding-model catalogue resource: " + RESOURCE);
            }
            values.load(input);
            return new EmbeddingModelCatalog(values);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read local embedding-model catalogue", exception);
        }
    }
}
