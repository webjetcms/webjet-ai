package com.webjetcms.ai.local.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class LocalModelBundleWriter {
    private static final LocalDateTime ZIP_TIMESTAMP = LocalDateTime.of(1980, 1, 1, 0, 0);

    Path write(
        Path destination,
        boolean overwrite,
        LocalModelRecipe recipe,
        ModelVariant variant,
        List<ModelArtifact> artifacts,
        List<DownloadResult> downloads
    ) throws IOException {
        Path absoluteDestination = destination.toAbsolutePath().normalize();
        Path parent = absoluteDestination.getParent();
        if (parent == null) {
            throw new IOException("Output path has no parent directory: " + destination);
        }
        Files.createDirectories(parent);
        if (Files.exists(absoluteDestination) && overwrite == false) {
            throw new IOException("Output already exists; use --overwrite to replace it: " + absoluteDestination);
        }
        if (artifacts.size() != downloads.size()) {
            throw new IllegalArgumentException("Artifact and download counts do not match");
        }

        Map<String, DownloadResult> downloadsByBundlePath = new HashMap<>();
        for (int index = 0; index < artifacts.size(); index++) {
            downloadsByBundlePath.put(artifacts.get(index).bundlePath(), downloads.get(index));
        }

        byte[] manifest = recipe.manifest(variant).getBytes(StandardCharsets.UTF_8);
        List<EntrySource> entries = new ArrayList<>();
        entries.add(EntrySource.generated("webjet-model.json", manifest));
        for (ModelArtifact artifact : artifacts) {
            DownloadResult result = downloadsByBundlePath.get(artifact.bundlePath());
            entries.add(EntrySource.downloaded(artifact.bundlePath(), result));
        }
        byte[] checksums = checksums(entries).getBytes(StandardCharsets.UTF_8);
        entries.add(EntrySource.generated("SHA256SUMS", checksums));

        Path temporaryZip = Files.createTempFile(parent, "." + absoluteDestination.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            writeZip(temporaryZip, entries);
            moveIntoPlace(temporaryZip, absoluteDestination, overwrite);
            moved = true;
            return absoluteDestination;
        } finally {
            if (moved == false) {
                Files.deleteIfExists(temporaryZip);
            }
        }
    }

    private static String checksums(List<EntrySource> entries) {
        StringBuilder checksums = new StringBuilder();
        for (EntrySource entry : entries) {
            checksums.append(entry.sha256()).append("  ").append(entry.name()).append('\n');
        }
        return checksums.toString();
    }

    private static void writeZip(Path target, List<EntrySource> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            for (EntrySource source : entries) {
                ZipEntry entry = new ZipEntry(source.name());
                entry.setTimeLocal(ZIP_TIMESTAMP);
                if (source.name().endsWith(".onnx")) {
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(source.size());
                    entry.setCompressedSize(source.size());
                    entry.setCrc(source.crc32());
                } else {
                    entry.setMethod(ZipEntry.DEFLATED);
                }
                zip.putNextEntry(entry);
                source.writeTo(zip);
                zip.closeEntry();
            }
        }
    }

    private static void moveIntoPlace(Path source, Path destination, boolean overwrite) throws IOException {
        StandardCopyOption[] atomicOptions = overwrite
            ? new StandardCopyOption[] {StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING}
            : new StandardCopyOption[] {StandardCopyOption.ATOMIC_MOVE};
        StandardCopyOption[] fallbackOptions = overwrite
            ? new StandardCopyOption[] {StandardCopyOption.REPLACE_EXISTING}
            : new StandardCopyOption[0];
        try {
            Files.move(source, destination, atomicOptions);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, fallbackOptions);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record EntrySource(
        String name,
        Path path,
        byte[] content,
        long size,
        String sha256,
        long crc32
    ) {
        static EntrySource generated(String name, byte[] content) {
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(content);
            return new EntrySource(
                name,
                null,
                content,
                content.length,
                LocalModelBundleWriter.sha256(content),
                crc.getValue()
            );
        }

        static EntrySource downloaded(String name, DownloadResult result) {
            return new EntrySource(name, result.path(), null, result.size(), result.sha256(), result.crc32());
        }

        void writeTo(ZipOutputStream zip) throws IOException {
            if (content != null) {
                zip.write(content);
            } else {
                Files.copy(path, zip);
            }
        }
    }
}
