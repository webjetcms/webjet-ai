package com.webjetcms.ai.provider.local;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Validates and extracts a strictly specified local model ZIP in one bounded pass. */
final class VerifiedBundleExtractor {
    private static final long GENERATED_ENTRY_LIMIT = 64L * 1024L;
    private static final int BUFFER_SIZE = 64 * 1024;

    private VerifiedBundleExtractor() { }
    static <M> Result<M> validateAndExtract(Path bundle, Path temporaryParent, String temporaryPrefix,
        Specification<M> specification) throws IOException {
        requireBundle(bundle);
        requireTemporaryParent(temporaryParent);
        Path extraction = Files.createTempDirectory(temporaryParent, temporaryPrefix);
        try {
            return readBundle(bundle, extraction, specification);
        } catch (IOException | RuntimeException | Error failure) {
            LocalProviderLifecycle.cleanupAfterFailure(extraction, failure);
            throw failure;
        }
    }
    private static <M> Result<M> readBundle(Path bundle, Path extraction, Specification<M> specification)
        throws IOException {
        M manifest;
        Map<String, String> actualHashes = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(bundle), StandardCharsets.UTF_8)) {
            EntryResult manifestResult = extractEntry(
                zip, extraction, "webjet-model.json", GENERATED_ENTRY_LIMIT, true);
            manifest = specification.manifest().parse(manifestResult.content());
            long totalBytes = manifestResult.size();
            long totalLimit = specification.maximumSize().bytes(manifest);
            actualHashes.put("webjet-model.json", manifestResult.sha256());

            List<String> expectedOrder = specification.entryOrder().entries(manifest);
            validateSpecification(expectedOrder);

            for (int index = 1; index < expectedOrder.size(); index++) {
                String expectedName = expectedOrder.get(index);
                boolean checksum = index == expectedOrder.size() - 1;
                Specification.Artifact artifact = checksum ? null
                    : specification.artifacts().artifact(manifest, expectedName);
                EntryResult result = extractEntry(zip, extraction, expectedName,
                    checksum ? GENERATED_ENTRY_LIMIT : artifact.size(), checksum);
                totalBytes = Math.addExact(totalBytes, result.size());
                if (artifact != null) validateArtifact(expectedName, result, artifact);
                if (checksum) validateChecksums(result.content(), actualHashes);
                else actualHashes.put(expectedName, result.sha256());
                require(totalBytes <= totalLimit, "Local model bundle exceeds the approved extracted size");
            }
            ZipEntry extra = zip.getNextEntry();
            if (extra != null) throw new IOException("Unexpected local model bundle entry: " + extra.getName());
        } catch (ArithmeticException exception) {
            throw new IOException("Local model bundle size overflow", exception);
        }
        return new Result<>(extraction, manifest);
    }
    private static void validateSpecification(List<String> entries) throws IOException {
        require(entries.size() >= 3 && "webjet-model.json".equals(entries.get(0))
            && "SHA256SUMS".equals(entries.get(entries.size() - 1)),
            "Invalid local model bundle specification");
        var unique = new HashSet<String>();
        for (String name : entries) require(name != null && name.isEmpty() == false
            && name.startsWith("/") == false && name.startsWith("\\") == false
            && name.contains("/") == false && name.contains("\\") == false
            && name.equals(".") == false && name.equals("..") == false && unique.add(name),
            "Invalid local model bundle specification");
    }
    private static void validateArtifact(String name, EntryResult result, Specification.Artifact artifact) throws IOException {
        require(result.size() == artifact.size(), "Unexpected size for " + name + ": " + result.size());
        require(artifact.sha256().equals(result.sha256()), "Approved SHA-256 mismatch for " + name);
    }
    private static void requireBundle(Path bundle) throws IOException {
        if (bundle == null) throw new NullPointerException("bundle");
        require(Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS),
            "Local model bundle must be an existing regular file: " + bundle);
        require(Files.isReadable(bundle), "Local model bundle is not readable: " + bundle);
    }
    private static void requireTemporaryParent(Path parent) throws IOException {
        if (parent == null) throw new NullPointerException("temporaryParent");
        require(Files.isDirectory(parent), "Temporary directory parent must already exist: " + parent);
        require(Files.isWritable(parent), "Temporary directory parent is not writable: " + parent);
    }
    private static EntryResult extractEntry(ZipInputStream zip, Path extraction, String name,
        long maximumSize, boolean capture) throws IOException {
        ZipEntry entry = zip.getNextEntry();
        require(entry != null, "Local model bundle is missing entry: " + name);
        require(name.equals(entry.getName()),
            "Unexpected local model bundle entry order; expected " + name + " but found " + entry.getName());
        require(entry.isDirectory() == false, "Local model bundle contains an invalid entry");
        Path destination = extraction.resolve(name);
        MessageDigest digest = sha256();
        ByteArrayOutputStream content = capture ? new ByteArrayOutputStream() : null;
        byte[] buffer = new byte[BUFFER_SIZE];
        long size = 0;
        try (OutputStream output = Files.newOutputStream(destination)) {
            int count;
            while ((count = zip.read(buffer)) != -1) {
                size = Math.addExact(size, count);
                require(size <= maximumSize,
                    "Local model bundle entry exceeds approved size: " + destination.getFileName());
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                if (content != null) content.write(buffer, 0, count);
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Local model bundle entry size overflow", exception);
        }
        zip.closeEntry();
        return new EntryResult(size, HexFormat.of().formatHex(digest.digest()), content == null ? null : content.toByteArray());
    }
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
    private static void validateChecksums(byte[] content, Map<String, String> actualHashes) throws IOException {
        String text = new String(content, StandardCharsets.US_ASCII);
        require(text.indexOf('\r') < 0 && text.endsWith("\n"),
            "SHA256SUMS must use canonical LF-terminated lines");
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        require(lines.length == actualHashes.size(),
            "SHA256SUMS does not contain the expected number of entries");
        int index = 0;
        for (Map.Entry<String, String> expected : actualHashes.entrySet()) {
            require((expected.getValue() + "  " + expected.getKey()).equals(lines[index++]),
                "Invalid SHA256SUMS entry for " + expected.getKey());
        }
    }
    private static void require(boolean valid, String message) throws IOException {
        if (valid == false) throw new IOException(message);
    }
    record Result<M>(Path directory, M manifest) { }
    record Specification<M>(EntryOrder<M> entryOrder, ManifestParser<M> manifest,
        ArtifactLookup<M> artifacts, MaximumSize<M> maximumSize) {
        interface EntryOrder<M> { List<String> entries(M manifest); }
        interface ManifestParser<M> { M parse(byte[] content) throws IOException; }
        interface ArtifactLookup<M> { Artifact artifact(M manifest, String name) throws IOException; }
        interface MaximumSize<M> { long bytes(M manifest); }
        record Artifact(long size, String sha256) { }
    }
    private record EntryResult(long size, String sha256, byte[] content) { }
}
