package com.webjetcms.ai.local.tool;

import java.net.URI;
import java.util.Objects;

record ModelArtifact(
    String sourcePath,
    String bundlePath,
    URI sourceUri,
    long expectedSize,
    String expectedSha256
) {
    ModelArtifact {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(bundlePath, "bundlePath");
        Objects.requireNonNull(sourceUri, "sourceUri");
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        if (sourcePath.isBlank() || bundlePath.isBlank()) {
            throw new IllegalArgumentException("Artifact paths must not be blank");
        }
        if (expectedSize < 1) {
            throw new IllegalArgumentException("Artifact size must be greater than zero");
        }
        if (expectedSha256.matches("[0-9a-f]{64}") == false) {
            throw new IllegalArgumentException("Artifact SHA-256 must contain 64 lowercase hexadecimal characters");
        }
    }
}
