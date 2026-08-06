package com.webjetcms.ai;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable provider configuration resolved by the host application.
 * Credential values are deliberately redacted from {@link #toString()}.
 */
public final class AiProviderConfig {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofMinutes(2);
    private static final long MAX_TIMEOUT_MILLIS = Integer.MAX_VALUE;

    private final String apiKey;
    private final URI baseUri;
    private final Duration connectTimeout;
    private final Duration responseTimeout;
    private final Map<String, String> trustedHeaders;

    private AiProviderConfig(Builder builder) {
        this.apiKey = validateApiKey(builder.apiKey);
        this.baseUri = validateBaseUri(builder.baseUri, builder.insecureLocalHttpAllowed);
        this.connectTimeout = validateTimeout(
            Objects.requireNonNullElse(builder.connectTimeout, DEFAULT_CONNECT_TIMEOUT),
            "connectTimeout"
        );
        this.responseTimeout = validateTimeout(
            Objects.requireNonNullElse(builder.responseTimeout, DEFAULT_RESPONSE_TIMEOUT),
            "responseTimeout"
        );
        this.trustedHeaders = Map.copyOf(builder.trustedHeaders);
    }

    /**
     * Starts a configuration builder for a provider credential.
     *
     * @param apiKey provider API key; a null or blank value produces an unconfigured instance
     * @return a new configuration builder
     */
    public static Builder builder(String apiKey) { return new Builder(apiKey); }

    /**
     * Returns the provider credential.
     *
     * @return the API key supplied by the host application, possibly {@code null} or blank
     */
    public String apiKey() { return apiKey; }

    /**
     * Returns the custom provider endpoint, when one was configured.
     *
     * @return an HTTPS base URI, an explicitly enabled loopback test URI, or {@code null} for the provider default
     */
    public URI baseUri() { return baseUri; }

    /**
     * Returns the maximum time allowed to establish a connection.
     *
     * @return a positive duration of at least one millisecond, or zero to disable the timeout
     */
    public Duration connectTimeout() { return connectTimeout; }

    /**
     * Returns the maximum time allowed to wait for response data.
     *
     * @return a positive duration of at least one millisecond, or zero to disable the timeout
     */
    public Duration responseTimeout() { return responseTimeout; }

    /**
     * Returns the connection timeout in the unit expected by the bundled HTTP transport.
     *
     * @return the connection timeout in milliseconds, or zero to disable it
     */
    public int connectTimeoutMillis() { return Math.toIntExact(connectTimeout.toMillis()); }

    /**
     * Returns the response timeout in the unit expected by the bundled HTTP transport.
     *
     * @return the response timeout in milliseconds, or zero to disable it
     */
    public int responseTimeoutMillis() { return Math.toIntExact(responseTimeout.toMillis()); }

    /**
     * Returns host-supplied HTTP headers that providers may forward.
     *
     * @return an immutable map of trusted header names to values
     */
    public Map<String, String> trustedHeaders() { return trustedHeaders; }

    /**
     * Checks whether a non-blank provider credential is available.
     *
     * @return {@code true} when the API key is neither {@code null} nor blank
     */
    public boolean isConfigured() {
        return apiKey != null && apiKey.isBlank() == false;
    }

    String redactSensitiveValue(String value) {
        if (value == null) return null;

        List<String> sensitiveValues = sensitiveValues();
        String redacted = value;
        String previous;
        do {
            previous = redacted;
            for (String sensitiveValue : sensitiveValues) {
                redacted = redacted.replace(sensitiveValue, "");
            }
        } while (redacted.equals(previous) == false);
        return redacted;
    }

    boolean hasSensitiveValues() {
        return sensitiveValues().isEmpty() == false;
    }

    private List<String> sensitiveValues() {
        LinkedHashSet<String> originalValues = new LinkedHashSet<>();
        if (apiKey != null && apiKey.isBlank() == false) {
            originalValues.add(apiKey);
        }
        trustedHeaders.values().stream()
            .filter(value -> value != null && value.isBlank() == false)
            .forEach(originalValues::add);

        LinkedHashSet<String> distinctValues = new LinkedHashSet<>();
        for (String originalValue : originalValues) {
            distinctValues.add(originalValue);
            distinctValues.add(jsonEscape(originalValue, false, false));
            distinctValues.add(jsonEscape(originalValue, true, false));
            distinctValues.add(jsonEscape(originalValue, true, true));

            String urlEncoded = URLEncoder.encode(originalValue, StandardCharsets.UTF_8);
            distinctValues.add(urlEncoded);
            distinctValues.add(urlEncoded.replace("+", "%20"));
            distinctValues.add(lowercasePercentEscapes(urlEncoded));
            distinctValues.add(lowercasePercentEscapes(urlEncoded).replace("+", "%20"));

            byte[] bytes = originalValue.getBytes(StandardCharsets.UTF_8);
            distinctValues.add(Base64.getEncoder().encodeToString(bytes));
            distinctValues.add(Base64.getEncoder().withoutPadding().encodeToString(bytes));
            distinctValues.add(Base64.getUrlEncoder().encodeToString(bytes));
            distinctValues.add(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        }
        distinctValues.removeIf(String::isEmpty);

        List<String> result = new ArrayList<>(distinctValues);
        result.sort(Comparator.comparingInt(String::length).reversed());
        return result;
    }

    private static String jsonEscape(String value, boolean escapeNonAscii, boolean uppercaseHex) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20 || escapeNonAscii && character > 0x7e) {
                        String hex = Integer.toHexString(character);
                        if (uppercaseHex) {
                            hex = hex.toUpperCase(Locale.ROOT);
                        }
                        escaped.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String lowercasePercentEscapes(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '%' && index + 2 < value.length()) {
                normalized.append('%')
                    .append(Character.toLowerCase(value.charAt(index + 1)))
                    .append(Character.toLowerCase(value.charAt(index + 2)));
                index += 2;
            } else {
                normalized.append(character);
            }
        }
        return normalized.toString();
    }

    @Override
    public String toString() {
        String description = "AiProviderConfig[apiKey=[REDACTED], baseUri="
            + (baseUri == null ? "[DEFAULT]" : "[CUSTOM]")
            + ", connectTimeout=" + connectTimeout
            + ", responseTimeout=" + responseTimeout
            + ", trustedHeaderCount=" + trustedHeaders.size() + "]";
        return redactSensitiveValue(description);
    }

    private static Duration validateTimeout(Duration timeout, String name) {
        if (timeout.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        if (timeout.isZero()) {
            return timeout;
        }

        final long millis;
        try {
            millis = timeout.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is too large", exception);
        }
        if (millis < 1) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
        if (millis > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(name + " must not exceed " + MAX_TIMEOUT_MILLIS + " milliseconds");
        }
        return timeout;
    }

    private static URI validateBaseUri(URI baseUri, boolean insecureLocalHttpAllowed) {
        if (baseUri == null) return null;
        if (baseUri.isAbsolute() == false || baseUri.isOpaque()) {
            throw new IllegalArgumentException("baseUri must be an absolute hierarchical URI");
        }
        if (baseUri.getHost() == null || baseUri.getHost().isBlank()) {
            throw new IllegalArgumentException("baseUri must contain a host");
        }
        if (baseUri.getPort() == 0 || baseUri.getPort() > 65535) {
            throw new IllegalArgumentException("baseUri port must be between 1 and 65535");
        }
        if (baseUri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("baseUri must not contain user information");
        }
        if (baseUri.getRawQuery() != null || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUri must not contain a query or fragment");
        }

        String scheme = baseUri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) return baseUri;
        if ("http".equalsIgnoreCase(scheme)
                && insecureLocalHttpAllowed
                && isLoopbackHost(baseUri.getHost())) {
            return baseUri;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                "HTTP baseUri is allowed only for an explicitly enabled local loopback test endpoint"
            );
        }
        throw new IllegalArgumentException("baseUri must use HTTPS");
    }

    private static boolean isLoopbackHost(String host) {
        String normalized = host.startsWith("[") && host.endsWith("]")
            ? host.substring(1, host.length() - 1)
            : host;
        return "localhost".equalsIgnoreCase(normalized)
            || "127.0.0.1".equals(normalized)
            || "::1".equals(normalized);
    }

    private static boolean isHttpToken(String value) {
        if (value.isEmpty()) return false;

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (isAlphaNumeric(character)
                    || character == '!'
                    || character == '#'
                    || character == '$'
                    || character == '%'
                    || character == '&'
                    || character == '\''
                    || character == '*'
                    || character == '+'
                    || character == '-'
                    || character == '.'
                    || character == '^'
                    || character == '_'
                    || character == '`'
                    || character == '|'
                    || character == '~') {
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean isAlphaNumeric(char character) {
        return character >= '0' && character <= '9'
            || character >= 'A' && character <= 'Z'
            || character >= 'a' && character <= 'z';
    }

    private static boolean isValidHeaderValue(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\t') continue;
            if (Character.isISOControl(character) || Character.isSurrogate(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsCrOrLf(String value) {
        return value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0);
    }

    private static String validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return apiKey;
        if (containsCrOrLf(apiKey) || isValidHeaderValue(apiKey) == false) {
            throw new IllegalArgumentException("apiKey contains invalid HTTP header characters");
        }
        return apiKey;
    }

    /** Builds an immutable {@link AiProviderConfig}. */
    public static final class Builder {
        private final String apiKey;
        private URI baseUri;
        private Duration connectTimeout;
        private Duration responseTimeout;
        private boolean insecureLocalHttpAllowed;
        private final Map<String, String> trustedHeaders = new LinkedHashMap<>();

        private Builder(String apiKey) { this.apiKey = apiKey; }

        /**
         * Selects a custom provider endpoint.
         *
         * @param baseUri absolute base URI, or {@code null} to use the provider default
         * @return this builder
         */
        public Builder baseUri(URI baseUri) { this.baseUri = baseUri; return this; }

        /**
         * Sets the connection timeout.
         *
         * @param connectTimeout positive duration, zero to disable the timeout, or {@code null} for the ten-second default
         * @return this builder
         */
        public Builder connectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; return this; }

        /**
         * Sets the response timeout.
         *
         * @param responseTimeout positive duration, zero to disable the timeout, or {@code null} for the two-minute default
         * @return this builder
         */
        public Builder responseTimeout(Duration responseTimeout) { this.responseTimeout = responseTimeout; return this; }

        /**
         * Allows an HTTP base URI only when it targets localhost or a literal loopback address.
         * Intended solely for local integration tests; production custom endpoints must use HTTPS.
         *
         * @return this builder
         */
        public Builder allowInsecureHttpForLocalTesting() {
            this.insecureLocalHttpAllowed = true;
            return this;
        }

        /**
         * Adds a host-controlled header for providers that support trusted metadata headers.
         * Null, blank, or incomplete entries are ignored. Non-blank names must use the
         * HTTP token syntax and values must not contain control characters. Header names
         * are compared case-insensitively; adding the same name again replaces its prior
         * spelling and value.
         *
         * @param name HTTP header name
         * @param value HTTP header value
         * @return this builder
         * @throws IllegalArgumentException if a non-blank name or value contains invalid HTTP characters
         */
        public Builder trustedHeader(String name, String value) {
            if (containsCrOrLf(name) || containsCrOrLf(value)) {
                throw new IllegalArgumentException("trusted header must not contain CR or LF characters");
            }
            if (name == null || name.isBlank() || value == null || value.isBlank()) return this;
            if (isHttpToken(name) == false) {
                throw new IllegalArgumentException("trusted header name must use HTTP token syntax");
            }
            if (isValidHeaderValue(value) == false) {
                throw new IllegalArgumentException("trusted header value contains invalid characters");
            }

            trustedHeaders.keySet().removeIf(existingName -> existingName.equalsIgnoreCase(name));
            trustedHeaders.put(name, value);
            return this;
        }

        /**
         * Validates the accumulated settings and creates an immutable configuration.
         *
         * @return a validated provider configuration
         * @throws IllegalArgumentException if the credential, custom URI, timeout, or trusted header is invalid
         */
        public AiProviderConfig build() { return new AiProviderConfig(this); }
    }
}
