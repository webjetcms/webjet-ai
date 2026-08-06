package com.webjetcms.ai;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Structured failure returned by an AI provider or its transport. */
public final class AiProviderException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Stable identifier of the provider that failed.
     *
     * @serial stable identifier of the provider that failed
     */
    private final String providerId;

    /**
     * Associated HTTP status code.
     *
     * @serial HTTP status code, or {@code -1} when no HTTP response was received
     */
    private final int statusCode;

    /**
     * Raw provider response retained for diagnostics after provider-boundary redaction.
     *
     * @serial raw provider response, when available
     */
    private final String rawResponse;

    /**
     * Retry classification supplied by the provider adapter.
     *
     * @serial whether retrying the same operation may succeed
     */
    private final boolean retryable;

    /**
     * Creates a non-retryable provider failure without an HTTP response.
     *
     * @param providerId stable identifier of the provider that failed
     * @param message human-readable failure description
     */
    public AiProviderException(String providerId, String message) {
        this(providerId, -1, message, null, false, null);
    }

    /**
     * Creates a non-retryable provider failure caused before an HTTP response was received.
     *
     * @param providerId stable identifier of the provider that failed
     * @param message human-readable failure description
     * @param cause underlying validation, transport, parsing, or callback failure
     */
    public AiProviderException(String providerId, String message, Throwable cause) {
        this(providerId, -1, message, null, false, cause);
    }

    /**
     * Creates a structured provider failure from an HTTP or protocol response.
     *
     * @param providerId stable identifier of the provider that failed
     * @param statusCode HTTP status code, or {@code -1} if unavailable
     * @param message human-readable failure description
     * @param rawResponse unparsed provider response, or {@code null} if unavailable
     * @param retryable whether retrying the same operation may succeed
     */
    public AiProviderException(String providerId, int statusCode, String message, String rawResponse, boolean retryable) {
        this(providerId, statusCode, message, rawResponse, retryable, null);
    }

    /**
     * Creates a structured provider failure with its underlying cause.
     *
     * @param providerId stable identifier of the provider that failed
     * @param statusCode HTTP status code, or {@code -1} if unavailable
     * @param message human-readable failure description
     * @param rawResponse unparsed provider response, or {@code null} if unavailable
     * @param retryable whether retrying the same operation may succeed
     * @param cause underlying validation, transport, parsing, or callback failure
     */
    public AiProviderException(String providerId, int statusCode, String message, String rawResponse, boolean retryable, Throwable cause) {
        super(message, cause);
        this.providerId = providerId;
        this.statusCode = statusCode;
        this.rawResponse = rawResponse;
        this.retryable = retryable;
    }

    /**
     * Returns the provider that reported the failure.
     *
     * @return the stable provider identifier
     */
    public String providerId() { return providerId; }

    /**
     * Returns the associated HTTP status code.
     *
     * @return the HTTP status code, or {@code -1} when no response was received
     */
    public int statusCode() { return statusCode; }

    /**
     * Returns the unparsed provider response retained for diagnostics. Built-in
     * providers remove the configured API key and trusted-header values before
     * exposing it, but callers should still treat it as potentially sensitive.
     *
     * @return the raw response body, or {@code null} when unavailable
     */
    public String rawResponse() { return rawResponse; }

    /**
     * Indicates whether retrying the operation may succeed.
     *
     * @return {@code true} for failures classified as retryable
     */
    public boolean retryable() { return retryable; }

    /**
     * Returns an equivalent failure with configured credentials removed from every
     * exposed text field, cause, suppressed exception, and stack-trace element.
     * Provider implementations should call this before a failure crosses their
     * public boundary.
     *
     * @param config provider configuration whose API key and trusted-header values must be protected
     * @return this exception when no sensitive value exists, otherwise a redacted copy
     */
    public AiProviderException redactSecrets(AiProviderConfig config) {
        if (config == null || config.hasSensitiveValues() == false) return this;

        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(this);
        AiProviderException redacted = new AiProviderException(
            config.redactSensitiveValue(providerId),
            statusCode,
            config.redactSensitiveValue(getMessage()),
            config.redactSensitiveValue(rawResponse),
            retryable,
            redactThrowable(getCause(), config, visited)
        );
        redacted.setStackTrace(redactStackTrace(getStackTrace(), config));
        for (Throwable suppressed : getSuppressed()) {
            redacted.addSuppressed(redactThrowable(suppressed, config, visited));
        }
        return redacted;
    }

    private static Throwable redactThrowable(
        Throwable source,
        AiProviderConfig config,
        Set<Throwable> visited
    ) {
        if (source == null) return null;
        if (visited.add(source) == false) {
            return new Exception("[cyclic exception omitted]");
        }

        Exception redacted = new Exception(config.redactSensitiveValue(source.toString()));
        redacted.setStackTrace(redactStackTrace(source.getStackTrace(), config));
        Throwable cause = source.getCause();
        if (cause != null) {
            redacted.initCause(redactThrowable(cause, config, visited));
        }
        for (Throwable suppressed : source.getSuppressed()) {
            redacted.addSuppressed(redactThrowable(suppressed, config, visited));
        }
        return redacted;
    }

    private static StackTraceElement[] redactStackTrace(
        StackTraceElement[] source,
        AiProviderConfig config
    ) {
        StackTraceElement[] redacted = new StackTraceElement[source.length];
        for (int index = 0; index < source.length; index++) {
            StackTraceElement element = source[index];
            redacted[index] = new StackTraceElement(
                config.redactSensitiveValue(element.getClassName()),
                config.redactSensitiveValue(element.getMethodName()),
                config.redactSensitiveValue(element.getFileName()),
                element.getLineNumber()
            );
        }
        return redacted;
    }
}
