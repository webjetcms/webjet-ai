package com.webjetcms.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiToolArgumentValidatorTest {
    private static final AiTool LOOKUP = new AiTool("lookup", "Look up an order", """
        {"type":"object","properties":{"ids":{"type":"array","items":{"$ref":"#/$defs/id"}}},
         "$defs":{"id":{"type":"integer","minimum":1}},"required":["ids"],"additionalProperties":false}
        """);

    @Test
    void validatesTheOfferedToolAndReportsOnlyRegisteredPaths() {
        AiToolArgumentValidator.validate(LOOKUP, new AiToolCall("1", "lookup", "{\"ids\":[1.0,2]}"));
        var invalid = assertThrows(AiToolArgumentValidator.InvalidArguments.class, () ->
            AiToolArgumentValidator.validate(LOOKUP, new AiToolCall("1", "lookup", "{\"ids\":[-999]}")));
        assertEquals("arguments.ids[] must be at least 1.", invalid.getMessage());
        assertThrows(AiToolArgumentValidator.InvalidArguments.class, () ->
            AiToolArgumentValidator.validate(LOOKUP, new AiToolCall("1", "other", "{\"ids\":[1]}")));
    }

    @Test
    void handlesCompositionAndNumericEnumEquality() {
        var numericEnum = Map.of("enum", List.of(Map.of("value", 1.0)));
        var args = Map.of("value", 1);
        AiToolArgumentValidator.validate(Map.of("anyOf", List.of(false, numericEnum)), args);
        assertThrows(AiToolArgumentValidator.InvalidArguments.class, () ->
            AiToolArgumentValidator.validate(Map.of("oneOf", List.of(true, numericEnum)), args));
    }

    @Test
    void unsupportedSchemaIsAnApplicationErrorEvenInAnUnusedAlternative() {
        var schema = Map.of("anyOf", List.of(true, Map.of("maxLength", 5)));
        var error = assertThrows(IllegalArgumentException.class, () -> AiToolArgumentValidator.validate(schema, Map.of()));
        assertFalse(error instanceof AiToolArgumentValidator.InvalidArguments);
        assertTrue(error.getMessage().contains("maxLength"));
    }

    @Test
    void resolvesUriFragmentsBeforeUnescapingPointerTokens() {
        var defs = Map.of("item count", Map.of("type", "integer"), "item%20count", Map.of("type", "string"),
            "café+~/", Map.of("minimum", 3), "\uFFFD", true);
        var count = Map.of("$defs", defs, "$ref", "#/%24defs/item%20count");
        assertTrue(AiToolArgumentValidator.matches(count, 2));
        assertFalse(AiToolArgumentValidator.matches(count, "2"));
        assertTrue(AiToolArgumentValidator.matches(Map.of("$defs", defs, "$ref", "#/$defs/item%2520count"), "2"));
        assertFalse(AiToolArgumentValidator.matches(Map.of("$defs", defs, "$ref", "#/$defs/caf%C3%A9+%7E0~1"), 2));
        assertTrue(AiToolArgumentValidator.matches(Map.of("$defs", defs, "$ref", "#/$defs/%EF%BF%BD"), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"#/$defs/a%2Fb", "#/$defs/bad~2", "#/$defs/%XX", "#/$defs/%FF", "other#/$defs/count"})
    void rejectsInvalidOrNonlocalReferences(String ref) {
        var schema = Map.of("$defs", Map.of("a/b", true, "bad~2", true, "count", true, "\uFFFD", true), "$ref", ref);
        var error = assertThrows(IllegalArgumentException.class, () -> AiToolArgumentValidator.matches(schema, 0));
        assertFalse(error instanceof AiToolArgumentValidator.InvalidArguments);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void countsArgumentDepthIndependentlyOfReferencesAndAlternatives(boolean array) {
        var child = Map.of("$ref", "#/$defs/node");
        var container = array ? Map.of("type", "array", "items", child)
            : Map.of("type", "object", "properties", Map.of("child", child));
        var node = Map.of("anyOf", List.of(Map.of("type", "null"), container));
        var schema = Map.of("$defs", Map.of("node", node), "$ref", "#/$defs/node");
        Object tree = null;
        for (int depth = 0; depth < 80; depth++) {
            tree = array ? Collections.singletonList(tree) : Collections.singletonMap("child", tree);
        }
        AiToolArgumentValidator.validate(schema, tree);
        assertTrue(AiToolArgumentValidator.matches(schema, tree));

        Object tooDeep = array ? List.of(tree) : Map.of("child", tree);
        assertFalse(AiToolArgumentValidator.matches(schema, tooDeep));
        assertThrows(AiToolArgumentValidator.InvalidArguments.class, () -> AiToolArgumentValidator.validate(schema, tooDeep));
    }

    @Test
    void rejectsReferenceCyclesThatNeverDescendIntoTheArguments() {
        var defs = Map.of("first", Map.of("anyOf", List.of(true, Map.of("$ref", "#/$defs/second"))),
            "second", Map.of("$ref", "#/$defs/first"));
        var schema = Map.of("$defs", defs, "type", "object");
        var error = assertThrows(IllegalArgumentException.class, () -> AiToolArgumentValidator.matches(schema, Map.of()));
        assertFalse(error instanceof AiToolArgumentValidator.InvalidArguments);
        assertTrue(error.getMessage().contains("Cyclic"));
    }
}
