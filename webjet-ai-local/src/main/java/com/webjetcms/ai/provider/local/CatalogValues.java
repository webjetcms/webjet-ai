package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Typed access to a versioned local-model catalogue. */
record CatalogValues(Properties properties, String description) {
    static CatalogValues load(Class<?> owner, String resource, String description) {
        Properties properties = new Properties();
        try (InputStream input = owner.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing approved " + description + " catalogue: " + resource);
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read approved " + description + " catalogue", exception);
        }
        return new CatalogValues(properties, description);
    }
    String required(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isEmpty()) throw error("Missing", "value", key, null);
        return value;
    }
    String optional(String key) {
        String value = properties.getProperty(key);
        return value == null || value.isEmpty() ? null : value;
    }
    List<String> list(String key) {
        List<String> values = List.of(required(key).split(",", -1));
        if (values.stream().anyMatch(String::isBlank)) throw error("Blank", "value", key, null);
        return values;
    }
    List<String> uniqueList(String key) {
        List<String> values = list(key);
        if (new LinkedHashSet<>(values).size() != values.size()) throw error("Duplicate", "value", key, null);
        return values;
    }
    Set<String> uniqueSet(String key) { return Set.copyOf(uniqueList(key)); }
    int integer(String key) { return (int) number(key, "integer"); }
    int positiveInteger(String key) { return minimum(integer(key), 1, key); }
    int nonNegativeInteger(String key) { return minimum(integer(key), 0, key); }
    long positiveLong(String key) {
        long value = number(key, "long");
        if (value < 1) throw error("Invalid", "long", key, null);
        return value;
    }
    private long number(String key, String type) {
        try {
            return "integer".equals(type) ? Integer.parseInt(required(key)) : Long.parseLong(required(key));
        } catch (NumberFormatException exception) {
            throw error("Invalid", type, key, exception);
        }
    }
    boolean bool(String key) {
        String value = required(key);
        if ("true".equals(value) || "false".equals(value)) return Boolean.parseBoolean(value);
        throw error("Invalid", "boolean", key, null);
    }
    String sha256(String key) {
        String value = required(key);
        if (value.matches("[0-9a-f]{64}") == false) throw error("Invalid", "SHA-256", key, null);
        return value;
    }
    private static int minimum(int value, int minimum, String key) {
        if (value < minimum) throw new IllegalStateException(
            minimum == 0 ? "Approved catalogue value must not be negative: " + key
                : "Approved catalogue value must be positive: " + key);
        return value;
    }
    private IllegalStateException error(String problem, String type, String key, Throwable cause) {
        String message = problem + " approved " + description + " catalogue " + type + ": " + key;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }
}
