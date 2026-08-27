package com.webjetcms.ai.local.tool;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

record DownloadRequest(URI uri, Path destination, long expectedSize, String expectedSha256) {
    DownloadRequest {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        if (expectedSize < 1) {
            throw new IllegalArgumentException("Expected size must be greater than zero");
        }
        if (expectedSha256.matches("[0-9a-f]{64}") == false) {
            throw new IllegalArgumentException("Expected SHA-256 must contain 64 lowercase hexadecimal characters");
        }
    }
}
