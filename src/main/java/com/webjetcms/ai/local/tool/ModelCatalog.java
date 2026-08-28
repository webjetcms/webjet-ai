package com.webjetcms.ai.local.tool;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Small typed view over a local-model properties catalogue. */
record ModelCatalog(Properties values, String description, String prefix) {
    static ModelCatalog load(String resource, String description) {
        Properties values = new Properties();
        try (var input = ModelCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing local " + description + " catalogue: " + resource);
            values.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read local " + description + " catalogue", exception);
        }
        ModelCatalog result = new ModelCatalog(values, description, "");
        if (result.integer("catalog.version") != 1) {
            throw new IllegalStateException("Unsupported local " + description + " catalogue version");
        }
        return result;
    }
    ModelCatalog at(String nestedPrefix) { return new ModelCatalog(values, description, prefix + nestedPrefix); }
    boolean has(String key) { return values.containsKey(prefix + key); }
    String value(String key) {
        String result = values.getProperty(prefix + key);
        if (result == null || result.isEmpty()) throw invalid("Missing", key, null);
        return result;
    }
    int integer(String key) {
        try { return Integer.parseInt(value(key)); }
        catch (NumberFormatException exception) { throw invalid("Invalid integer in", key, exception); }
    }
    int positiveInteger(String key) { return (int) minimum(integer(key), 1, key); }
    int nonNegativeInteger(String key) { return (int) minimum(integer(key), 0, key); }
    long number(String key) {
        try { return Long.parseLong(value(key)); }
        catch (NumberFormatException exception) { throw invalid("Invalid number in", key, exception); }
    }
    long positiveNumber(String key) { return minimum(number(key), 1, key); }
    String sha256(String key) {
        String result = value(key);
        if (result.matches("[0-9a-f]{64}") == false) throw invalid("Invalid SHA-256 in", key, null);
        return result;
    }
    boolean bool(String key) {
        String result = value(key);
        if ("true".equals(result) == false && "false".equals(result) == false) throw invalid("Invalid boolean in", key, null);
        return Boolean.parseBoolean(result);
    }
    List<String> list(String key) {
        List<String> result = List.of(value(key).split(",", -1));
        if (result.stream().anyMatch(String::isBlank)) throw invalid("Blank list value in", key, null);
        return result;
    }
    Set<String> unique(String key) {
        List<String> list = list(key);
        Set<String> result = new LinkedHashSet<>(list);
        if (result.size() != list.size()) throw invalid("Duplicate list value in", key, null);
        return Set.copyOf(result);
    }
    Set<ModelVariant> variants() {
        return Set.copyOf(unique("variants").stream().map(ModelVariant::parse).toList());
    }
    ModelVariant defaultVariant() {
        ModelVariant result = ModelVariant.parse(value("default-variant"));
        if (variants().contains(result) == false) {
            throw new IllegalStateException("Default local model variant is unsupported: " + result.cliName());
        }
        return result;
    }
    String variant(ModelVariant variant, String key) { return value("variant." + variant.cliName() + "." + key); }
    long variantNumber(ModelVariant variant, String key) { return positiveNumber("variant." + variant.cliName() + "." + key); }
    String variantSha256(ModelVariant variant, String key) { return sha256("variant." + variant.cliName() + "." + key); }
    String artifact(String name, String key) { return value("artifact." + name + "." + key); }
    long artifactNumber(String name, String key) { return positiveNumber("artifact." + name + "." + key); }
    String artifactSha256(String name) { return sha256("artifact." + name + ".sha256"); }
    private long minimum(long value, int minimum, String key) {
        if (value < minimum) throw invalid(minimum == 0 ? "Negative" : "Non-positive", key, null);
        return value;
    }
    private IllegalStateException invalid(String reason, String key, Throwable cause) {
        return new IllegalStateException(reason + " local " + description + " catalogue value: " + prefix + key, cause);
    }
}
