package com.webjetcms.ai.local.tool;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Loads approved local embedding-model recipes from the shared core catalogue. */
final class EmbeddingModelCatalog {
    static final String RESOURCE = "META-INF/webjet-ai/local-embedding-model-catalog-v1.properties";
    static final EmbeddingModelCatalog INSTANCE = load();

    private final List<Model> models;

    private EmbeddingModelCatalog(Properties values) {
        if (integer(values, "catalog.version") != 1) {
            throw new IllegalStateException("Unsupported local embedding-model catalogue version");
        }
        List<Model> loaded = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(list(values, "catalog.models"));
        if (keys.size() != list(values, "catalog.models").size()) {
            throw new IllegalStateException("Duplicate local embedding-model catalogue key");
        }
        for (String key : keys) {
            Model model = new Model(values, key);
            model.validate();
            loaded.add(model);
        }
        models = List.copyOf(loaded);
    }

    List<Model> models() { return models; }

    record Model(Properties values, String key) {
        String displayName() { return required("display-name"); }

        String canonicalId() { return required("canonical-id"); }

        Set<String> aliases() {
            List<String> aliases = list("aliases");
            Set<String> unique = new LinkedHashSet<>(aliases);
            if (unique.size() != aliases.size()) {
                throw new IllegalStateException("Duplicate aliases for local embedding model: " + canonicalId());
            }
            return Set.copyOf(unique);
        }

        String revision() { return required("revision"); }

        int dimensions() { return integer("dimensions"); }

        int maximumLength() { return integer("maximum-length"); }

        String format() { return required("format"); }

        String license() { return required("license"); }

        String task() { return required("task"); }

        String pooling() { return required("pooling"); }

        boolean normalize() { return bool("normalize"); }

        String inputPreparation() { return required("input-preparation"); }

        String queryPrefix() { return required("query-prefix"); }

        String documentPrefix() { return required("document-prefix"); }

        List<String> inputNames() { return list("input-names"); }

        List<String> outputNames() { return list("output-names"); }

        String repository() { return required("repository"); }

        String modelCardPath() { return required("model-card-path"); }

        Set<ModelVariant> supportedVariants() {
            Set<ModelVariant> variants = new LinkedHashSet<>();
            for (String name : list("variants")) variants.add(ModelVariant.parse(name));
            return Set.copyOf(variants);
        }

        ModelVariant defaultVariant() {
            ModelVariant variant = ModelVariant.parse(required("default-variant"));
            if (supportedVariants().contains(variant) == false) {
                throw new IllegalStateException("Default variant is not supported by " + canonicalId());
            }
            return variant;
        }

        List<String> artifactNames() { return list("artifacts"); }

        String variantValue(ModelVariant variant, String suffix) {
            return required("variant." + variant.cliName() + "." + suffix);
        }

        long variantLong(ModelVariant variant, String suffix) {
            return longValue("variant." + variant.cliName() + "." + suffix);
        }

        String artifactValue(String bundleName, String suffix) {
            return required("artifact." + bundleName + "." + suffix);
        }

        long artifactLong(String bundleName, String suffix) {
            return longValue("artifact." + bundleName + "." + suffix);
        }

        private void validate() {
            displayName();
            canonicalId();
            aliases();
            revision();
            positive(dimensions(), "dimensions");
            positive(maximumLength(), "maximum-length");
            format();
            license();
            task();
            pooling();
            normalize();
            inputPreparation();
            queryPrefix();
            documentPrefix();
            inputNames();
            outputNames();
            repository();
            modelCardPath();
            Set<ModelVariant> variants = supportedVariants();
            if (variants.isEmpty()) throw new IllegalStateException("Embedding model has no variants: " + canonicalId());
            defaultVariant();
            for (ModelVariant variant : variants) {
                variantValue(variant, "cpu-target");
                variantValue(variant, "source-path");
                variantValue(variant, "default-output");
                positive(variantLong(variant, "model-size"), "model-size");
                sha256(variantValue(variant, "model-sha256"));
            }
            List<String> artifacts = artifactNames();
            if (artifacts.isEmpty()) throw new IllegalStateException("Embedding model has no artifacts: " + canonicalId());
            for (String artifact : artifacts) {
                artifactValue(artifact, "source-path");
                positive(artifactLong(artifact, "size"), "artifact size");
                sha256(artifactValue(artifact, "sha256"));
            }
        }

        private String required(String suffix) {
            return EmbeddingModelCatalog.required(values, prefix() + suffix);
        }

        private int integer(String suffix) {
            return EmbeddingModelCatalog.integer(values, prefix() + suffix);
        }

        private long longValue(String suffix) {
            return EmbeddingModelCatalog.longValue(values, prefix() + suffix);
        }

        private boolean bool(String suffix) {
            String value = required(suffix);
            if ("true".equals(value)) return true;
            if ("false".equals(value)) return false;
            throw new IllegalStateException("Invalid boolean in local embedding-model catalogue: " + prefix() + suffix);
        }

        private List<String> list(String suffix) {
            return EmbeddingModelCatalog.list(values, prefix() + suffix);
        }

        private String prefix() { return "model." + key + "."; }
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Missing local embedding-model catalogue value: " + key);
        }
        return value;
    }

    private static int integer(Properties values, String key) {
        try {
            return Integer.parseInt(required(values, key));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid integer in local embedding-model catalogue: " + key, exception);
        }
    }

    private static long longValue(Properties values, String key) {
        try {
            return Long.parseLong(required(values, key));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid long in local embedding-model catalogue: " + key, exception);
        }
    }

    private static List<String> list(Properties values, String key) {
        List<String> result = List.of(required(values, key).split(",", -1));
        if (result.stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException("Blank list value in local embedding-model catalogue: " + key);
        }
        return result;
    }

    private static void positive(long value, String name) {
        if (value < 1) throw new IllegalStateException("Local embedding-model " + name + " must be positive");
    }

    private static void sha256(String value) {
        if (value.matches("[0-9a-f]{64}") == false) {
            throw new IllegalStateException("Invalid SHA-256 in local embedding-model catalogue");
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
