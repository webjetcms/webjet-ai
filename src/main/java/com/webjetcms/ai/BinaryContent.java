package com.webjetcms.ai;

import java.util.Arrays;
import java.util.Objects;

/**
 * Binary input passed to a provider without exposing host-specific file types.
 *
 * @param data content bytes; the record stores and returns defensive copies
 * @param mediaType MIME type, defaulting to {@code application/octet-stream} when blank
 * @param fileName source file name, possibly {@code null}
 */
public record BinaryContent(byte[] data, String mediaType, String fileName) {

    /**
     * Creates binary input while normalizing its data and media type.
     *
     * @param data content bytes, or {@code null} for an empty payload
     * @param mediaType MIME type, or a null/blank value for the binary default
     * @param fileName source file name, possibly {@code null}
     */
    public BinaryContent {
        data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
        mediaType = mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType;
    }

    /**
     * Returns a defensive copy of the content bytes.
     *
     * @return a newly allocated byte array
     */
    @Override
    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }

    /** Uses byte content, rather than array identity, for value equality. */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof BinaryContent that
            && Arrays.equals(data, that.data)
            && Objects.equals(mediaType, that.mediaType)
            && Objects.equals(fileName, that.fileName);
    }

    /** Uses byte content, rather than array identity, for the value hash. */
    @Override
    public int hashCode() {
        int result = Arrays.hashCode(data);
        result = 31 * result + Objects.hashCode(mediaType);
        result = 31 * result + Objects.hashCode(fileName);
        return result;
    }
}
