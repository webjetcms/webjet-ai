package com.webjetcms.ai.local.tool;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.zip.CRC32;

final class HttpDownloader {
    private static final long MAX_DELAY = 60_000;
    private final HttpClient client;
    private final int attempts;
    private final Sleeper sleeper;

    HttpDownloader(HttpClient client, int maximumAttempts, Sleeper sleeper) {
        if (maximumAttempts < 1) throw new IllegalArgumentException("Maximum attempts must be greater than zero");
        this.client = client;
        attempts = maximumAttempts;
        this.sleeper = sleeper;
    }

    static HttpDownloader production() {
        return new HttpDownloader(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL).build(), 3, Thread::sleep);
    }

    DownloadResult download(DownloadRequest request, Consumer<String> progress) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return downloadOnce(request);
            } catch (IOException exception) {
                try {
                    if (Files.isDirectory(request.destination(), LinkOption.NOFOLLOW_LINKS) == false)
                        Files.deleteIfExists(request.destination());
                } catch (IOException cleanup) { exception.addSuppressed(cleanup); }
                if (!(exception instanceof Retryable retryable) || attempt == attempts) throw exception;
                long delay = retryable.delay >= 0 ? retryable.delay : Math.min(1_000L << attempt - 1, MAX_DELAY);
                progress.accept("Retrying " + request.uri() + " after " + retryable.getMessage()
                    + " (attempt " + (attempt + 1) + "/" + attempts + ")");
                try { sleeper.sleep(delay); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to retry a download", interrupted);
                }
            }
        }
    }

    private DownloadResult downloadOnce(DownloadRequest request) throws IOException {
        HttpRequest httpRequest = HttpRequest.newBuilder(request.uri()).timeout(Duration.ofHours(2))
            .header("User-Agent", "webjet-ai-local-model-tool").header("Accept-Encoding", "identity").GET().build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + request.uri(), exception);
        } catch (IOException exception) {
            throw retry("I/O failure: " + message(exception), -1, exception);
        }
        try (InputStream input = response.body()) {
            int status = response.statusCode();
            if (status == 429 || status >= 500 && status < 600)
                throw retry("HTTP " + status, retryDelay(response.headers().firstValue("Retry-After").orElse(null)), null);
            if (status != 200) throw new IOException("Download failed with HTTP " + status + ": " + request.uri());
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (length > request.expectedSize()) throw new IOException(sizeMessage(request, length, "Response is larger than expected"));
            if (length >= 0 && length < request.expectedSize()) throw retry(sizeMessage(request, length, "truncated response"), -1, null);
            return copy(input, request);
        }
    }

    private DownloadResult copy(InputStream input, DownloadRequest request) throws IOException {
        var digest = Hashes.sha256();
        CRC32 crc = new CRC32();
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (var output = Files.newOutputStream(request.destination())) {
            while (true) {
                int read;
                try { read = input.read(buffer); }
                catch (IOException exception) { throw retry("I/O failure: " + message(exception), -1, exception); }
                if (read < 0) break;
                if (read == 0) continue;
                size += read;
                if (size > request.expectedSize()) throw new IOException(sizeMessage(request, size, "Response is larger than expected"));
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                crc.update(buffer, 0, read);
            }
        }
        if (size != request.expectedSize()) throw retry(sizeMessage(request, size, "truncated response"), -1, null);
        String sha256 = java.util.HexFormat.of().formatHex(digest.digest());
        if (request.expectedSha256().equals(sha256) == false) {
            throw new IOException("SHA-256 mismatch for " + request.uri() + ": expected "
                + request.expectedSha256() + ", received " + sha256);
        }
        return new DownloadResult(request.destination(), size, sha256, crc.getValue());
    }

    private static long retryDelay(String value) {
        if (value == null) return -1;
        try {
            return Math.min(Math.multiplyExact(Long.parseLong(value), 1_000), MAX_DELAY);
        } catch (ArithmeticException | NumberFormatException ignored) { }
        try {
            long delay = Duration.between(ZonedDateTime.now(),
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)).toMillis();
            return Math.min(Math.max(delay, 0), MAX_DELAY);
        } catch (DateTimeParseException exception) { return -1; }
    }

    private static String sizeMessage(DownloadRequest request, long size, String reason) {
        return reason + " for " + request.uri() + ": expected " + request.expectedSize() + " bytes, received " + size;
    }

    private static Retryable retry(String message, long delay, Throwable cause) { return new Retryable(message, delay, cause); }
    private static String message(IOException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    @FunctionalInterface interface Sleeper { void sleep(long milliseconds) throws InterruptedException; }
    private static final class Retryable extends IOException {
        private final long delay;
        private Retryable(String message, long delay, Throwable cause) { super(message, cause); this.delay = delay; }
    }
}

record DownloadRequest(URI uri, Path destination, long expectedSize, String expectedSha256) {
    DownloadRequest {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        if (expectedSize < 1) throw new IllegalArgumentException("Expected size must be greater than zero");
        if (expectedSha256.matches("[0-9a-f]{64}") == false) {
            throw new IllegalArgumentException("Expected SHA-256 must contain 64 lowercase hexadecimal characters");
        }
    }
}

record DownloadResult(Path path, long size, String sha256, long crc32) { }
