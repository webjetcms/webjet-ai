package com.webjetcms.ai.local.tool;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.zip.CRC32;

final class HttpDownloader {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long MAX_RETRY_DELAY_MILLIS = 60_000;

    private final HttpClient client;
    private final int maximumAttempts;
    private final Sleeper sleeper;

    HttpDownloader(HttpClient client, int maximumAttempts, Sleeper sleeper) {
        this.client = client;
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("Maximum attempts must be greater than zero");
        }
        this.maximumAttempts = maximumAttempts;
        this.sleeper = sleeper;
    }

    static HttpDownloader production() {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        return new HttpDownloader(client, 3, Thread::sleep);
    }

    DownloadResult download(DownloadRequest request, Consumer<String> progress) throws IOException {
        IOException finalFailure = null;
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            try {
                return downloadOnce(request);
            } catch (RetryableDownloadException exception) {
                finalFailure = exception;
                deletePartial(request.destination(), exception);
                if (attempt == maximumAttempts) {
                    break;
                }
                long delay = exception.retryDelayMillis() >= 0
                    ? exception.retryDelayMillis()
                    : Math.min(1_000L << (attempt - 1), MAX_RETRY_DELAY_MILLIS);
                progress.accept(
                    "Retrying " + request.uri() + " after " + exception.getMessage()
                        + " (attempt " + (attempt + 1) + "/" + maximumAttempts + ")"
                );
                sleep(delay);
            } catch (IOException exception) {
                deletePartial(request.destination(), exception);
                throw exception;
            }
        }
        throw finalFailure;
    }

    private DownloadResult downloadOnce(DownloadRequest request) throws IOException {
        HttpRequest httpRequest = HttpRequest.newBuilder(request.uri())
            .timeout(Duration.ofHours(2))
            .header("User-Agent", "webjet-ai-local-model-tool")
            .header("Accept-Encoding", "identity")
            .GET()
            .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + request.uri(), exception);
        } catch (IOException exception) {
            throw new RetryableDownloadException("I/O failure: " + message(exception), -1, exception);
        }

        try (InputStream input = response.body()) {
            int status = response.statusCode();
            if (status != 200) {
                if (status == 429 || status >= 500 && status <= 599) {
                    throw new RetryableDownloadException(
                        "HTTP " + status,
                        retryDelayMillis(response.headers().firstValue("Retry-After").orElse(null)),
                        null
                    );
                }
                throw new DownloadValidationException(
                    "Download failed with HTTP " + status + ": " + request.uri()
                );
            }

            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (contentLength > request.expectedSize()) {
                throw new DownloadValidationException(
                    "Response is larger than expected for " + request.uri() + ": expected "
                        + request.expectedSize() + " bytes, received " + contentLength
                );
            }
            if (contentLength >= 0 && contentLength < request.expectedSize()) {
                throw new RetryableDownloadException(
                    "truncated response: expected " + request.expectedSize() + " bytes, received " + contentLength,
                    -1,
                    null
                );
            }
            return copyAndVerify(input, request);
        }
    }

    private DownloadResult copyAndVerify(InputStream input, DownloadRequest request) throws IOException {
        MessageDigest digest = sha256Digest();
        CRC32 crc32 = new CRC32();
        long size = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (var output = Files.newOutputStream(
            request.destination(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )) {
            while (true) {
                int read;
                try {
                    read = input.read(buffer);
                } catch (IOException exception) {
                    throw new RetryableDownloadException(
                        "I/O failure: " + message(exception),
                        -1,
                        exception
                    );
                }
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                size += read;
                if (size > request.expectedSize()) {
                    throw new DownloadValidationException(
                        "Response is larger than expected for " + request.uri() + ": expected "
                            + request.expectedSize() + " bytes"
                    );
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                crc32.update(buffer, 0, read);
            }
        }
        if (size != request.expectedSize()) {
            throw new RetryableDownloadException(
                "truncated response: expected " + request.expectedSize() + " bytes, received " + size,
                -1,
                null
            );
        }
        String sha256 = HexFormat.of().formatHex(digest.digest());
        if (request.expectedSha256().equals(sha256) == false) {
            throw new DownloadValidationException(
                "SHA-256 mismatch for " + request.uri() + ": expected "
                    + request.expectedSha256() + ", received " + sha256
            );
        }
        return new DownloadResult(request.destination(), size, sha256, crc32.getValue());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static long retryDelayMillis(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Math.min(Math.multiplyExact(Long.parseLong(value), 1_000), MAX_RETRY_DELAY_MILLIS);
        } catch (ArithmeticException | NumberFormatException ignored) {
            try {
                long delay = Duration.between(
                    ZonedDateTime.now(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                ).toMillis();
                return Math.min(Math.max(delay, 0), MAX_RETRY_DELAY_MILLIS);
            } catch (DateTimeParseException dateException) {
                return -1;
            }
        }
    }

    private static void deletePartial(Path path, IOException failure) {
        try {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) == false) {
                Files.deleteIfExists(path);
            }
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void sleep(long delayMillis) throws IOException {
        try {
            sleeper.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to retry a download", exception);
        }
    }

    private static String message(IOException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }

    private static final class RetryableDownloadException extends IOException {
        private final long retryDelayMillis;

        private RetryableDownloadException(String message, long retryDelayMillis, Throwable cause) {
            super(message, cause);
            this.retryDelayMillis = retryDelayMillis;
        }

        private long retryDelayMillis() {
            return retryDelayMillis;
        }
    }

    private static final class DownloadValidationException extends IOException {
        private DownloadValidationException(String message) {
            super(message);
        }
    }
}
