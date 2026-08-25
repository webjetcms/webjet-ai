package com.webjetcms.ai.image;

/** Describes the value shape accepted by one image rendering option. */
public enum ImageOptionValueType {
    /** One string selected from an ordered allowlist. */
    CHOICE,
    /** An integral number, optionally constrained by inclusive bounds. */
    INTEGER,
    /** A boolean value. */
    BOOLEAN,
    /** A string accepted by an advertised value or regular-expression pattern. */
    PATTERN
}
