package com.webjetcms.ai.provider.local;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlatformSupportTest {

    @ParameterizedTest
    @CsvSource({
        "Windows 10, amd64",
        "Windows 11, x86_64",
        "Windows Server 2022, amd64",
        "WINDOWS SERVER 2025, AMD64",
        "Linux, amd64",
        "Linux, x86_64",
        "Mac OS X, aarch64",
        "macOS, arm64",
        "Darwin, aarch64"
    })
    void portableVariantsAcceptSupportedPlatforms(String osName, String osArch) {
        for (String variant : List.of("fp32", "int8", "q4-k-m")) {
            assertDoesNotThrow(() -> PlatformSupport.requireSupported(variant, "portable", osName, osArch));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "Windows 11, aarch64",
        "Windows 11, arm64",
        "Windows 10, x86",
        "Windows 10, i386",
        "Mac OS X, x86_64",
        "Darwin, amd64",
        "Linux, aarch64",
        "Linux, x86",
        "FreeBSD, amd64",
        "'', ''"
    })
    void portableVariantsRejectUnsupportedPlatforms(String osName, String osArch) {
        IOException failure = assertThrows(IOException.class, () ->
            PlatformSupport.requireSupported("fp32", "portable", osName, osArch));

        assertEquals("Portable local models require Linux x86_64, Windows x64, or macOS ARM64",
            failure.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
        "Windows 10, amd64",
        "Windows 11, x86_64",
        "Windows Server 2022, amd64",
        "Mac OS X, aarch64",
        "Mac OS X, x86_64",
        "Linux, aarch64"
    })
    void avx512VnniRemainsLinuxX64Only(String osName, String osArch) {
        IOException failure = assertThrows(IOException.class, () ->
            PlatformSupport.requireSupported("int8-avx512-vnni", "avx512-vnni", osName, osArch));

        assertEquals("INT8 AVX-512 VNNI local models require Linux x86_64", failure.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"Windows 11, amd64", "Linux, x86_64", "Mac OS X, aarch64"})
    void unknownCpuTargetsRemainRejected(String osName, String osArch) {
        IOException failure = assertThrows(IOException.class, () ->
            PlatformSupport.requireSupported("future-variant", "unknown", osName, osArch));

        assertEquals("Unsupported CPU target for local model variant future-variant: unknown",
            failure.getMessage());
    }
}
