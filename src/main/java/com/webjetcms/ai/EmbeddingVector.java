package com.webjetcms.ai;

import java.util.Arrays;

/**
 * Immutable embedding vector.
 *
 * @param values vector values; the record stores and returns defensive copies
 */
public record EmbeddingVector(float[] values) {

    /** Stores a defensive copy of the supplied vector. */
    public EmbeddingVector {
        values = values == null ? new float[0] : Arrays.copyOf(values, values.length);
    }

    /**
     * Returns a defensive copy of the vector values.
     *
     * @return newly allocated vector values
     */
    @Override
    public float[] values() {
        return Arrays.copyOf(values, values.length);
    }

    /**
     * Returns the number of values in the vector.
     *
     * @return vector dimension count
     */
    public int dimensions() {
        return values.length;
    }

    /** Uses vector content rather than array identity for equality. */
    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof EmbeddingVector that
            && Arrays.equals(values, that.values);
    }

    /** Uses vector content rather than array identity for the value hash. */
    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}
