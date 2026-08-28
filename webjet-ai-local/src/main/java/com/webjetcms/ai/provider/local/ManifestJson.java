package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/** Strict JSON field validation shared by local-model manifests. */
record ManifestJson(String subject) {
    JsonNode object(JsonNode node, String name, String... fields) throws IOException {
        if (node == null || node.isObject() == false) throw new IOException(subject + " field must be an object: " + name);
        Set<String> expected = Set.of(fields);
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (actual.equals(expected) == false) {
            throw new IOException("Unexpected " + subject.toLowerCase() + " fields in " + name
                + "; missing=" + difference(expected, actual) + ", extra=" + difference(actual, expected));
        }
        return node;
    }
    JsonNode values(JsonNode parent, String name, Object... fields) throws IOException {
        String[] names = new String[fields.length / 2];
        for (int index = 0; index < fields.length; index += 2) names[index / 2] = (String) fields[index];
        JsonNode node = object(parent.get(name), name, names);
        expect(node, fields);
        return node;
    }
    String text(JsonNode node, String field) throws IOException {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isTextual() == false) throw new IOException(subject + " field must be text: " + field);
        return value.textValue();
    }
    void expect(JsonNode node, Object... fields) throws IOException {
        for (int index = 0; index < fields.length; index += 2) {
            Object expected = fields[index + 1];
            if (expected != null && matches(node.get((String) fields[index]), expected) == false)
                throw new IOException("Unexpected " + subject.toLowerCase() + " value for " + fields[index]);
        }
    }
    private static boolean matches(JsonNode value, Object expected) {
        if (value == null) return false;
        if (expected instanceof String) return value.isTextual() && expected.equals(value.textValue());
        if (expected instanceof Integer) return value.isInt() && (Integer) expected == value.intValue();
        if (expected instanceof Boolean) return value.isBoolean() && (Boolean) expected == value.booleanValue();
        if (value.isArray() == false || expected instanceof List == false) return false;
        List<?> elements = (List<?>) expected;
        if (value.size() != elements.size()) return false;
        for (int index = 0; index < elements.size(); index++)
            if (value.get(index).isTextual() == false
                || elements.get(index).equals(value.get(index).textValue()) == false) return false;
        return true;
    }
    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }
}
