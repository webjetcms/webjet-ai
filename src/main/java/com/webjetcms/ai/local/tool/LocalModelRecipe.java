package com.webjetcms.ai.local.tool;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;

interface LocalModelRecipe {
    String canonicalId();
    Set<String> acceptedIds();
    String revision();
    Integer dimensions();
    int maximumLength();
    Set<ModelVariant> supportedVariants();
    ModelVariant defaultVariant();
    String defaultOutputName(ModelVariant variant);
    String manifest(ModelVariant variant);
    List<ModelArtifact> artifacts(ModelVariant variant);
}

record ModelArtifact(String sourcePath, String bundlePath, URI sourceUri, long expectedSize, String expectedSha256) {
    ModelArtifact {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(bundlePath, "bundlePath");
        Objects.requireNonNull(sourceUri, "sourceUri");
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        if (sourcePath.isBlank() || bundlePath.isBlank()) throw new IllegalArgumentException("Artifact paths must not be blank");
        if (expectedSize < 1) throw new IllegalArgumentException("Artifact size must be greater than zero");
        if (expectedSha256.matches("[0-9a-f]{64}") == false) {
            throw new IllegalArgumentException("Artifact SHA-256 must contain 64 lowercase hexadecimal characters");
        }
    }
}

enum ModelVariant {
    FP32("fp32"), INT8("int8"), INT8_AVX512_VNNI("int8-avx512-vnni"), Q4_K_M("q4-k-m");
    private final String cliName;
    ModelVariant(String cliName) { this.cliName = cliName; }
    String cliName() { return cliName; }
    static ModelVariant parse(String value) {
        for (ModelVariant variant : values()) if (variant.cliName.equals(value)) return variant;
        throw new IllegalArgumentException("Unsupported variant: " + value);
    }
}
