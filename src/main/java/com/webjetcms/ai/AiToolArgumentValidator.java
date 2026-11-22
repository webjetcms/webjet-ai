package com.webjetcms.ai;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.webjetcms.ai.internal.ToolJson;

/**
 * Optional validation of tool calls against a caller-supplied JSON Schema subset.
 * Schemas are trusted application configuration. Argument corrections contain only
 * paths and constraints from that configuration. Provider execution does not invoke this validator.
 */
public final class AiToolArgumentValidator {
    private static final Set<String> TYPES = Set.of("object", "array", "string", "boolean", "null", "number", "integer");
    private static final int MAX_SCHEMA_DEPTH = 80;
    private static final int MAX_ARGUMENT_DEPTH = 80;
    private static final Pattern PERCENT_OCTETS = Pattern.compile("(?:%[0-9a-fA-F]{2})+");
    private static final Pattern INVALID_POINTER_ESCAPE = Pattern.compile("~(?![01])");

    private final Map<String, ?> root;
    private final Map<String, Object> references = new HashMap<>();
    private final Map<Object, List<Object>> evaluationOrders = new IdentityHashMap<>();

    private AiToolArgumentValidator(Map<String, ?> schema) {
        root = schema;
        checkSchema(schema, 0);
        evaluationOrders.replaceAll((rule, unused) -> {
            List<Object> order = new ArrayList<>();
            orderSameValueRules(rule, new IdentityHashMap<>(), order, 0);
            return order;
        });
    }

    /** Contains feedback derived only from the registered schema, suitable for a model correction. */
    public static final class InvalidArguments extends IllegalArgumentException {
        private InvalidArguments(String message) { super(message); }
    }

    /**
     * Validates a native call against the selected tool.
     * @param tool offered function definition
     * @param call function requested by the provider
     * @throws InvalidArguments if the name or arguments do not match
     * @throws IllegalArgumentException if the registered schema is unsupported
     */
    public static void validate(AiTool tool, AiToolCall call) {
        if (!tool.name().equals(call.name())) throw new InvalidArguments("Choose the offered tool by its exact name.");
        validate(ToolJson.fields(ToolJson.object(tool.inputSchema())), ToolJson.fields(ToolJson.object(call.arguments())));
    }

    /**
     * Validates already decoded JSON arguments, including those from a text-based tool decision.
     * @param schema registered input schema expressed as JSON-compatible maps and lists
     * @param arguments decoded JSON value to check
     * @throws InvalidArguments if the arguments do not satisfy the schema
     * @throws IllegalArgumentException if the registered schema is unsupported
     */
    public static void validate(Map<String, ?> schema, Object arguments) {
        String error = argumentError(schema, arguments);
        if (error != null) throw new InvalidArguments(error);
    }

    /**
     * Tests decoded arguments when selecting between registered schema versions.
     * @param schema trusted input schema
     * @param arguments decoded JSON value to check
     * @return whether the value satisfies the supported constraints
     * @throws IllegalArgumentException if the registered schema is unsupported
     */
    public static boolean matches(Map<String, ?> schema, Object arguments) {
        return argumentError(schema, arguments) == null;
    }

    private static String argumentError(Map<String, ?> schema, Object arguments) {
        var validator = new AiToolArgumentValidator(schema);
        if (!withinArgumentDepth(arguments, 0)) return "Tool arguments exceed the maximum nesting depth of " + MAX_ARGUMENT_DEPTH + ".";
        return validator.firstError(arguments, schema, "arguments");
    }

    private static boolean withinArgumentDepth(Object value, int depth) {
        if (depth > MAX_ARGUMENT_DEPTH) return false;
        Iterable<?> children;
        if (value instanceof Map<?, ?> map) children = map.values();
        else if (value instanceof List<?> list) children = list;
        else return true;
        for (Object child : children) {
            if (!withinArgumentDepth(child, depth + 1)) return false;
        }
        return true;
    }

    /** Checks declared constraints even when a different alternative matches the arguments. */
    private void checkSchema(Object rule, int depth) {
        if (rule instanceof Boolean) return;
        if (!(rule instanceof Map<?, ?> schema) || depth > MAX_SCHEMA_DEPTH) throw new IllegalArgumentException("Invalid tool schema.");
        for (var entry : schema.entrySet()) {
            String key = (String) entry.getKey();
            Object value = entry.getValue();
            boolean valid = switch (key) {
                case "type" -> value instanceof String ? TYPES.contains(value)
                    : value instanceof List<?> types && !types.isEmpty() && types.stream().allMatch(t -> t instanceof String && TYPES.contains(t));
                case "required" -> value instanceof List<?> fields && fields.stream().allMatch(String.class::isInstance);
                case "enum" -> value instanceof List<?> options && !options.isEmpty();
                case "minimum" -> value instanceof Number;
                case "description" -> value instanceof String;
                case "$ref" -> value instanceof String ref && references.computeIfAbsent(ref, this::definition) != null;
                case "oneOf", "anyOf" -> {
                    if (!(value instanceof List<?> choices) || choices.isEmpty()) yield false;
                    for (Object choice : choices) checkSchema(choice, depth + 1);
                    yield true;
                }
                case "$defs", "properties", "patternProperties" -> {
                    if (!(value instanceof Map<?, ?> fields)) yield false;
                    for (var field : fields.entrySet()) {
                        if (!(field.getKey() instanceof String name)) yield false;
                        if ("patternProperties".equals(key)) Pattern.compile(name);
                        checkSchema(field.getValue(), depth + 1);
                    }
                    yield true;
                }
                case "items", "additionalProperties" -> {
                    checkSchema(value, depth + 1);
                    yield true;
                }
                case "default" -> true;
                default -> throw new IllegalArgumentException("Unsupported tool schema keyword: " + key);
            };
            if (!valid) throw new IllegalArgumentException("Invalid tool schema keyword: " + key);
        }
        if (schema.containsKey("$ref") || schema.containsKey("oneOf") || schema.containsKey("anyOf")) {
            evaluationOrders.put(schema, List.of());
        }
    }

    private Object definition(String ref) {
        if (!ref.startsWith("#") || !(root.get("$defs") instanceof Map<?, ?> defs)) return null;
        String fragment = URI.create(ref).getRawFragment();
        String pointer = PERCENT_OCTETS.matcher(fragment).replaceAll(match -> {
            String run = match.group();
            byte[] bytes = HexFormat.ofDelimiter("%").parseHex(run, 1, run.length());
            try {
                return Matcher.quoteReplacement(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            } catch (CharacterCodingException invalid) {
                throw new IllegalArgumentException("Invalid UTF-8 in tool schema reference.");
            }
        });
        if (!pointer.startsWith("/$defs/")) return null;
        String key = pointer.substring("/$defs/".length());
        if (key.contains("/") || INVALID_POINTER_ESCAPE.matcher(key).find()) return null;
        return defs.get(key.replace("~1", "/").replace("~0", "~"));
    }

    /** Orders references and alternatives before their parent. Child properties and items start a new evaluation. */
    private void orderSameValueRules(Object rule, IdentityHashMap<Object, Boolean> finished, List<Object> order, int depth) {
        Boolean seen = finished.putIfAbsent(rule, false);
        if (Boolean.TRUE.equals(seen)) return;
        if (Boolean.FALSE.equals(seen)) throw new IllegalArgumentException("Cyclic tool schema reference at the same argument.");
        if (depth > MAX_SCHEMA_DEPTH) throw new IllegalArgumentException("Tool schema nesting exceeds the supported depth.");
        if (rule instanceof Map<?, ?> schema) {
            if (schema.get("$ref") instanceof String ref) orderSameValueRules(references.get(ref), finished, order, depth + 1);
            for (String keyword : List.of("oneOf", "anyOf")) {
                if (schema.get(keyword) instanceof List<?> choices) {
                    for (Object choice : choices) orderSameValueRules(choice, finished, order, depth + 1);
                }
            }
        }
        finished.put(rule, true);
        order.add(rule);
    }

    /** Returns correction feedback using only paths and constraints declared by the registered schema. */
    private String firstError(Object value, Object rule, String path) {
        List<Object> order = evaluationOrders.get(rule);
        if (order == null) return ruleError(value, rule, path, Map.of());
        Map<Object, String> errors = new IdentityHashMap<>();
        for (Object constraint : order) errors.put(constraint, ruleError(value, constraint, path, errors));
        return errors.get(rule);
    }

    private String ruleError(Object value, Object rule, String path, Map<Object, String> errors) {
        if (rule instanceof Boolean permitted) return permitted ? null : path + " is not allowed.";
        Map<?, ?> schema = (Map<?, ?>) rule;
        if (schema.get("$ref") instanceof String ref) {
            String error = errors.get(references.get(ref));
            if (error != null) return error;
        }
        for (String keyword : List.of("oneOf", "anyOf")) {
            if (schema.get(keyword) instanceof List<?> choices) {
                long count = choices.stream().filter(choice -> errors.get(choice) == null).count();
                if (count == 0 || ("oneOf".equals(keyword) && count != 1)) return path + " must match its " + keyword + " schema.";
            }
        }
        Object type = schema.get("type");
        if (type instanceof List<?> types && types.stream().noneMatch(t -> isType(value, t))) return path + " must have one of the types " + types + ".";
        if (type instanceof String && !isType(value, type)) return path + " must have type " + type + ".";
        if (schema.get("enum") instanceof List<?> values && values.stream().noneMatch(option -> ToolJson.equalValues(value, option))) {
            return path + " must use a value from its enum.";
        }
        if (value instanceof Number number && schema.get("minimum") instanceof Number min
                && new BigDecimal(number.toString()).compareTo(new BigDecimal(min.toString())) < 0) return path + " must be at least " + min + ".";
        if (value instanceof List<?> list && schema.containsKey("items")) {
            for (Object item : list) {
                String error = firstError(item, schema.get("items"), path + "[]");
                if (error != null) return error;
            }
        }
        return value instanceof Map<?, ?> map ? objectError(map, schema, path) : null;
    }

    private String objectError(Map<?, ?> map, Map<?, ?> schema, String path) {
        if (schema.get("required") instanceof List<?> required) {
            for (Object field : required) {
                if (!map.containsKey(field)) return path + "." + field + " is required.";
            }
        }
        Map<?, ?> properties = schema.get("properties") instanceof Map<?, ?> p ? p : Map.of();
        Map<?, ?> patterns = schema.get("patternProperties") instanceof Map<?, ?> p ? p : Map.of();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) return path + " requires string property names.";
            boolean declared = properties.containsKey(key);
            String fieldPath = declared ? path + "." + key : path + ".*";
            if (declared) {
                String error = firstError(entry.getValue(), properties.get(key), fieldPath);
                if (error != null) return error;
            }
            for (var pattern : patterns.entrySet()) {
                if (Pattern.compile(pattern.getKey().toString()).matcher(key).find()) {
                    declared = true;
                    String error = firstError(entry.getValue(), pattern.getValue(), fieldPath);
                    if (error != null) return error;
                }
            }
            if (!declared && schema.containsKey("additionalProperties")) {
                Object additional = schema.get("additionalProperties");
                if (Boolean.FALSE.equals(additional)) {
                    if (patterns.isEmpty() && !properties.isEmpty() && properties.size() <= 8) {
                        return path + " contains an unknown field. Allowed fields are " + properties.keySet() + ".";
                    }
                    return path + " contains an unknown field. Use the properties or patterns declared for it.";
                }
                String error = firstError(entry.getValue(), additional, fieldPath);
                if (error != null) return error;
            }
        }
        return null;
    }

    private static boolean isType(Object value, Object type) {
        return switch (String.valueOf(type)) {
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number n && new BigDecimal(n.toString()).stripTrailingZeros().scale() <= 0;
            default -> false;
        };
    }
}
