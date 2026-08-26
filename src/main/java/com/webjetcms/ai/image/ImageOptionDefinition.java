package com.webjetcms.ai.image;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Immutable UI- and validation-friendly definition of one supported image option.
 *
 * @param valueType accepted value shape
 * @param allowedValues ordered string values advertised by the provider
 * @param minimum inclusive integer minimum, when applicable
 * @param maximum inclusive integer maximum, when applicable
 * @param pattern regular expression for patterned string values, when applicable
 */
public record ImageOptionDefinition(
    ImageOptionValueType valueType,
    List<String> allowedValues,
    Long minimum,
    Long maximum,
    String pattern
) {

    /**
     * Validates the definition and defensively copies its allowed values.
     *
     * @param valueType accepted value shape
     * @param allowedValues ordered string values advertised by the provider
     * @param minimum inclusive integer minimum, when applicable
     * @param maximum inclusive integer maximum, when applicable
     * @param pattern regular expression for patterned string values, when applicable
     */
    public ImageOptionDefinition {
        Objects.requireNonNull(valueType, "valueType");
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        if (allowedValues.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Image option allowed values must not be null or blank");
        }
        if (allowedValues.stream().distinct().count() != allowedValues.size()) {
            throw new IllegalArgumentException("Image option allowed values must be unique");
        }
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IllegalArgumentException("Image option minimum must not exceed maximum");
        }

        switch (valueType) {
            case CHOICE -> {
                if (allowedValues.isEmpty()) {
                    throw new IllegalArgumentException("Choice image options require allowed values");
                }
                requireNull(minimum, maximum, pattern);
            }
            case INTEGER -> {
                if (allowedValues.isEmpty() == false || pattern != null) {
                    throw new IllegalArgumentException("Integer image options cannot have string choices or a pattern");
                }
            }
            case BOOLEAN -> {
                if (allowedValues.isEmpty() == false || minimum != null || maximum != null || pattern != null) {
                    throw new IllegalArgumentException("Boolean image options cannot have choices, bounds, or a pattern");
                }
            }
            case PATTERN -> {
                if (minimum != null || maximum != null || pattern == null || pattern.isBlank()) {
                    throw new IllegalArgumentException("Pattern image options require a pattern and no numeric bounds");
                }
                try {
                    Pattern.compile(pattern);
                } catch (PatternSyntaxException exception) {
                    throw new IllegalArgumentException("Invalid image option pattern", exception);
                }
            }
        }
    }

    /**
     * Creates an ordered string-choice definition.
     *
     * @param values allowed string values in display order
     * @return immutable choice definition
     */
    public static ImageOptionDefinition choices(String... values) {
        return new ImageOptionDefinition(
            ImageOptionValueType.CHOICE,
            List.of(values),
            null,
            null,
            null
        );
    }

    /**
     * Creates an inclusive integer-range definition.
     *
     * @param minimum inclusive lower bound
     * @param maximum inclusive upper bound
     * @return immutable integer definition
     */
    public static ImageOptionDefinition integerRange(long minimum, long maximum) {
        return new ImageOptionDefinition(
            ImageOptionValueType.INTEGER,
            List.of(),
            minimum,
            maximum,
            null
        );
    }

    /**
     * Creates an unconstrained integral definition.
     *
     * @return immutable integer definition without bounds
     */
    public static ImageOptionDefinition integer() {
        return new ImageOptionDefinition(
            ImageOptionValueType.INTEGER,
            List.of(),
            null,
            null,
            null
        );
    }

    /**
     * Creates a boolean definition.
     *
     * @return immutable boolean definition
     */
    public static ImageOptionDefinition bool() {
        return new ImageOptionDefinition(
            ImageOptionValueType.BOOLEAN,
            List.of(),
            null,
            null,
            null
        );
    }

    /**
     * Creates a patterned string definition with optional explicit choices.
     *
     * @param pattern regular expression accepted for non-choice values
     * @param values ordered explicit choices accepted in addition to the pattern
     * @return immutable patterned-string definition
     */
    public static ImageOptionDefinition patterned(String pattern, String... values) {
        return new ImageOptionDefinition(
            ImageOptionValueType.PATTERN,
            List.of(values),
            null,
            null,
            pattern
        );
    }

    /**
     * Returns whether a scalar value conforms to this definition.
     *
     * @param value candidate scalar value
     * @return {@code true} when the value has the required type and constraints
     */
    public boolean accepts(Object value) {
        return switch (valueType) {
            case CHOICE -> value instanceof String text && allowedValues.contains(text);
            case INTEGER -> isAcceptedInteger(value);
            case BOOLEAN -> value instanceof Boolean;
            case PATTERN -> value instanceof String text
                && (allowedValues.contains(text) || Pattern.matches(pattern, text));
        };
    }

    private boolean isAcceptedInteger(Object value) {
        if (value instanceof Integer == false && value instanceof Long == false) {
            return false;
        }
        long number = ((Number) value).longValue();
        return (minimum == null || number >= minimum) && (maximum == null || number <= maximum);
    }

    private static void requireNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                throw new IllegalArgumentException("Choice image options cannot have bounds or a pattern");
            }
        }
    }
}
