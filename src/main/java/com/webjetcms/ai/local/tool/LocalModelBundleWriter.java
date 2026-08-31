package com.webjetcms.ai.local.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class LocalModelBundleWriter {
    private static final LocalDateTime ZIP_TIMESTAMP = LocalDateTime.of(1980, 1, 1, 0, 0);

    Path write(Path destination, boolean overwrite, LocalModelRecipe recipe, ModelVariant variant,
        List<ModelArtifact> artifacts, List<DownloadResult> downloads) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Output path has no parent directory: " + destination);
        Files.createDirectories(parent);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && overwrite == false) {
            throw new IOException("Output already exists; use --overwrite to replace it: " + target);
        }
        if (artifacts.size() != downloads.size()) throw new IllegalArgumentException("Artifact and download counts do not match");

        List<Entry> entries = new ArrayList<>(artifacts.size() + 2);
        entries.add(Entry.generated("webjet-model.json", recipe.manifest(variant).getBytes(StandardCharsets.UTF_8)));
        for (int index = 0; index < artifacts.size(); index++) {
            DownloadResult result = downloads.get(index);
            entries.add(new Entry(artifacts.get(index).bundlePath(), result.path(), null,
                result.size(), result.sha256(), result.crc32()));
        }
        StringBuilder sums = new StringBuilder();
        entries.forEach(entry -> sums.append(entry.sha256).append("  ").append(entry.name).append('\n'));
        entries.add(Entry.generated("SHA256SUMS", sums.toString().getBytes(StandardCharsets.UTF_8)));

        Path temporary = Files.createTempFile(parent, "." + target.getFileName() + ".", ".tmp");
        try {
            writeZip(temporary, entries);
            move(temporary, target, overwrite);
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeZip(Path target, List<Entry> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            for (Entry source : entries) {
                ZipEntry entry = new ZipEntry(source.name);
                entry.setTimeLocal(ZIP_TIMESTAMP);
                if (source.name.endsWith(".onnx") || source.name.endsWith(".gguf")) {
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(source.size);
                    entry.setCompressedSize(source.size);
                    entry.setCrc(source.crc32);
                }
                zip.putNextEntry(entry);
                if (source.content == null) Files.copy(source.path, zip); else zip.write(source.content);
                zip.closeEntry();
            }
        }
    }

    static void move(Path source, Path target, boolean overwrite) throws IOException {
        if (overwrite == false) {
            Files.move(source, target);
            return;
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Entry(String name, Path path, byte[] content, long size, String sha256, long crc32) {
        private static Entry generated(String name, byte[] content) {
            CRC32 crc = new CRC32();
            crc.update(content);
            return new Entry(name, null, content, content.length, Hashes.sha256(content), crc.getValue());
        }
    }
}

final class Hashes {
    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
    static String sha256(byte[] content) { return HexFormat.of().formatHex(sha256().digest(content)); }
}
