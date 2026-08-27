package com.webjetcms.ai.provider.local;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Validates and extracts a strictly specified local model ZIP in one bounded pass. */
final class VerifiedBundleExtractor {
    private static final long GENERATED_ENTRY_LIMIT = 64L * 1024L;
    private static final int BUFFER_SIZE = 64 * 1024;

    private VerifiedBundleExtractor() { }

    static <M> Result<M> validateAndExtract(
        Path bundle,
        Path temporaryParent,
        String temporaryPrefix,
        Specification<M> specification
    ) throws IOException {
        requireBundle(bundle);
        requireTemporaryParent(temporaryParent);
        Path extraction = Files.createTempDirectory(temporaryParent, temporaryPrefix);
        boolean prepared = false;
        Throwable failure = null;
        try {
            Result<M> result = readBundle(bundle, extraction, specification);
            prepared = true;
            return result;
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            if (prepared == false) {
                try {
                    DirectoryCleaner.delete(extraction);
                } catch (IOException cleanupFailure) {
                    if (failure != null) failure.addSuppressed(cleanupFailure);
                    else throw cleanupFailure;
                }
            }
        }
    }

    private static <M> Result<M> readBundle(
        Path bundle,
        Path extraction,
        Specification<M> specification
    ) throws IOException {
        M manifest = null;
        Map<String, String> actualHashes = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        long totalBytes = 0;
        long totalLimit = GENERATED_ENTRY_LIMIT;
        List<String> expectedOrder = specification.entryOrder();
        if (expectedOrder.size() < 3
            || "webjet-model.json".equals(expectedOrder.get(0)) == false
            || "SHA256SUMS".equals(expectedOrder.get(expectedOrder.size() - 1)) == false) {
            throw new IOException("Invalid local model bundle specification");
        }

        try (InputStream input = Files.newInputStream(bundle);
             ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            for (int index = 0; index < expectedOrder.size(); index++) {
                String expectedName = expectedOrder.get(index);
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) {
                    throw new IOException("Local model bundle is missing entry: " + expectedName);
                }
                validateEntry(entry, expectedName, seen);
                Specification.Artifact artifact = null;
                long entryLimit = GENERATED_ENTRY_LIMIT;
                if (index > 0 && index < expectedOrder.size() - 1) {
                    if (manifest == null) {
                        throw new IOException("Local model manifest must be validated before artifacts");
                    }
                    artifact = specification.artifact(manifest, expectedName);
                    entryLimit = artifact.size();
                }
                boolean capture = "webjet-model.json".equals(expectedName)
                    || "SHA256SUMS".equals(expectedName);
                EntryResult result = extractEntry(
                    zip,
                    extraction.resolve(expectedName),
                    entryLimit,
                    capture
                );
                zip.closeEntry();
                totalBytes = Math.addExact(totalBytes, result.size());
                if (artifact != null) {
                    if (result.size() != artifact.size()) {
                        throw new IOException("Unexpected size for " + expectedName + ": " + result.size());
                    }
                    if (artifact.sha256().equals(result.sha256()) == false) {
                        throw new IOException("Approved SHA-256 mismatch for " + expectedName);
                    }
                }
                if ("webjet-model.json".equals(expectedName)) {
                    manifest = specification.parseManifest(result.content());
                    totalLimit = specification.maximumExtractedBytes(manifest);
                } else if ("SHA256SUMS".equals(expectedName)) {
                    validateChecksums(result.content(), actualHashes);
                }
                if (totalBytes > totalLimit) {
                    throw new IOException("Local model bundle exceeds the approved extracted size");
                }
                if ("SHA256SUMS".equals(expectedName) == false) {
                    actualHashes.put(expectedName, result.sha256());
                }
            }
            ZipEntry extra = zip.getNextEntry();
            if (extra != null) {
                throw new IOException("Unexpected local model bundle entry: " + extra.getName());
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Local model bundle size overflow", exception);
        }
        if (manifest == null) throw new IOException("Local model bundle manifest is missing");
        return new Result<>(extraction, manifest);
    }

    private static void requireBundle(Path bundle) throws IOException {
        if (bundle == null) throw new NullPointerException("bundle");
        if (Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS) == false) {
            throw new IOException("Local model bundle must be an existing regular file: " + bundle);
        }
        if (Files.isReadable(bundle) == false) {
            throw new IOException("Local model bundle is not readable: " + bundle);
        }
    }

    private static void requireTemporaryParent(Path parent) throws IOException {
        if (parent == null) throw new NullPointerException("temporaryParent");
        if (Files.isDirectory(parent) == false) {
            throw new IOException("Temporary directory parent must already exist: " + parent);
        }
        if (Files.isWritable(parent) == false) {
            throw new IOException("Temporary directory parent is not writable: " + parent);
        }
    }

    private static void validateEntry(ZipEntry entry, String expectedName, Set<String> seen)
        throws IOException {
        String name = entry.getName();
        if (name == null || name.isEmpty() || entry.isDirectory()) {
            throw new IOException("Local model bundle contains an invalid entry");
        }
        if (name.startsWith("/") || name.startsWith("\\") || name.contains("/")
            || name.contains("\\") || name.equals(".") || name.equals("..")) {
            throw new IOException("Unsafe local model bundle entry: " + name);
        }
        if (seen.add(name) == false) {
            throw new IOException("Duplicate local model bundle entry: " + name);
        }
        if (expectedName.equals(name) == false) {
            throw new IOException(
                "Unexpected local model bundle entry order; expected " + expectedName + " but found " + name
            );
        }
    }

    private static EntryResult extractEntry(
        ZipInputStream zip,
        Path destination,
        long maximumSize,
        boolean capture
    ) throws IOException {
        MessageDigest digest = sha256();
        ByteArrayOutputStream content = capture ? new ByteArrayOutputStream() : null;
        byte[] buffer = new byte[BUFFER_SIZE];
        long size = 0;
        try (OutputStream output = Files.newOutputStream(destination)) {
            int count;
            while ((count = zip.read(buffer)) != -1) {
                size = Math.addExact(size, count);
                if (size > maximumSize) {
                    throw new IOException("Local model bundle entry exceeds approved size: " + destination.getFileName());
                }
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                if (content != null) content.write(buffer, 0, count);
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Local model bundle entry size overflow", exception);
        }
        return new EntryResult(
            size,
            HexFormat.of().formatHex(digest.digest()),
            content == null ? null : content.toByteArray()
        );
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void validateChecksums(byte[] content, Map<String, String> actualHashes)
        throws IOException {
        String text = new String(content, StandardCharsets.US_ASCII);
        if (text.indexOf('\r') >= 0 || text.endsWith("\n") == false) {
            throw new IOException("SHA256SUMS must use canonical LF-terminated lines");
        }
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        if (lines.length != actualHashes.size()) {
            throw new IOException("SHA256SUMS does not contain the expected number of entries");
        }
        int index = 0;
        for (Map.Entry<String, String> expected : actualHashes.entrySet()) {
            String canonical = expected.getValue() + "  " + expected.getKey();
            if (canonical.equals(lines[index++]) == false) {
                throw new IOException("Invalid SHA256SUMS entry for " + expected.getKey());
            }
        }
    }

    record Result<M>(Path directory, M manifest) { }

    interface Specification<M> {
        List<String> entryOrder();

        M parseManifest(byte[] content) throws IOException;

        Artifact artifact(M manifest, String name) throws IOException;

        long maximumExtractedBytes(M manifest);

        record Artifact(long size, String sha256) { }
    }

    private record EntryResult(long size, String sha256, byte[] content) { }
}
