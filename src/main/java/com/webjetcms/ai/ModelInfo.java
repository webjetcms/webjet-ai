package com.webjetcms.ai;

/**
 * Provider-neutral model catalogue entry.
 *
 * @param id provider-specific model identifier used in requests
 * @param displayName human-readable model name
 * @param createdAt provider-reported Unix timestamp in seconds, or {@code null} when unavailable
 */
public record ModelInfo(String id, String displayName, Long createdAt) {

    /**
     * Creates a catalogue entry without creation metadata.
     *
     * @param id provider-specific model identifier used in requests
     * @param displayName human-readable model name
     */
    public ModelInfo(String id, String displayName) {
        this(id, displayName, null);
    }

    /**
     * Returns the human-readable name, falling back to the provider identifier.
     *
     * @return non-blank display label when either source value is non-blank
     */
    public String displayLabel() {
        return displayName == null || displayName.isBlank() ? id : displayName;
    }
}
