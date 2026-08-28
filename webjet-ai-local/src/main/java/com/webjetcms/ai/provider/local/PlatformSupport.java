package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.webjetcms.ai.provider.local.ApprovedEmbeddingModelCatalog.EmbeddingVariantDefinition;

/** Enforces the tested host-platform matrix for approved model variants. */
final class PlatformSupport {
    private PlatformSupport() { }

    static void requireSupported(EmbeddingVariantDefinition variant) throws IOException {
        requireSupported(variant.name(), variant.cpuTarget());
    }
    static void requireSupported(String variantName, String cpuTarget) throws IOException {
        String os = operatingSystem();
        String architecture = architecture();
        if ("portable".equals(cpuTarget)) {
            if (("linux".equals(os) && "x86_64".equals(architecture)
                || "macos".equals(os) && "aarch64".equals(architecture)) == false)
                throw new IOException("Portable local models require Linux x86_64 or macOS ARM64");
            return;
        }
        if ("avx512-vnni".equals(cpuTarget) == false)
            throw new IOException("Unsupported CPU target for local model variant " + variantName + ": " + cpuTarget);
        if ("linux".equals(os) == false || "x86_64".equals(architecture) == false)
            throw new IOException("INT8 AVX-512 VNNI local models require Linux x86_64");
        if (hasAvx512Vnni() == false)
            throw new IOException("INT8 local model requires the avx512_vnni CPU feature");
    }
    private static String operatingSystem() {
        String value = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (value.contains("linux")) return "linux";
        if (value.contains("mac") || value.contains("darwin")) return "macos";
        return value;
    }
    private static String architecture() {
        String value = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if ("amd64".equals(value) || "x86_64".equals(value)) return "x86_64";
        if ("arm64".equals(value) || "aarch64".equals(value)) return "aarch64";
        return value;
    }
    private static boolean hasAvx512Vnni() throws IOException {
        String cpuInfo = Files.readString(Path.of("/proc/cpuinfo"), StandardCharsets.US_ASCII);
        return cpuInfo.toLowerCase(Locale.ROOT).matches("(?s).*\\bavx512_vnni\\b.*");
    }
}
