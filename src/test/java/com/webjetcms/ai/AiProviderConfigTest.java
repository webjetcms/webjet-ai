package com.webjetcms.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AiProviderConfigTest {

    @Test
    void validatesTrustedHeadersAndReplacesNamesCaseInsensitively() {
        AiProviderConfig config = AiProviderConfig.builder("key")
            .trustedHeader("X-Request-Id", "first")
            .trustedHeader("x-request-id", "second")
            .trustedHeader("X-Metadata!#$%&'*+-.^_`|~", "value\twith-tab")
            .build();

        assertEquals(Map.of(
            "x-request-id", "second",
            "X-Metadata!#$%&'*+-.^_`|~", "value\twith-tab"
        ), config.trustedHeaders());

        assertThrows(IllegalArgumentException.class,
            () -> AiProviderConfig.builder("key").trustedHeader("X Invalid", "value"));
        assertThrows(IllegalArgumentException.class,
            () -> AiProviderConfig.builder("key").trustedHeader("X:Invalid", "value"));
        assertThrows(IllegalArgumentException.class,
            () -> AiProviderConfig.builder("key").trustedHeader("X-Test\r\nInjected", "value"));
        assertThrows(IllegalArgumentException.class,
            () -> AiProviderConfig.builder("key").trustedHeader("X-Test", "value\r\nInjected: true"));
        assertThrows(IllegalArgumentException.class,
            () -> AiProviderConfig.builder("key").trustedHeader("X-Test", "value\u0000tail"));
        assertThrows(IllegalArgumentException.class,
            () -> AiProviderConfig.builder("key").trustedHeader("X-Test", "value\u0085tail"));
        assertThrows(IllegalArgumentException.class,
            () -> AiProviderConfig.builder("key").trustedHeader("X-Test", "value\uD800tail"));
    }

    @Test
    void acceptsPositiveMillisecondTimeoutsWithinTheHttpClientRange() {
        AiProviderConfig config = AiProviderConfig.builder("key")
            .connectTimeout(Duration.ofMillis(1))
            .responseTimeout(Duration.ofMillis(Integer.MAX_VALUE))
            .build();

        assertEquals(Duration.ofMillis(1), config.connectTimeout());
        assertEquals(Duration.ofMillis(Integer.MAX_VALUE), config.responseTimeout());
        assertEquals(1, config.connectTimeoutMillis());
        assertEquals(Integer.MAX_VALUE, config.responseTimeoutMillis());

        AiProviderConfig unlimited = AiProviderConfig.builder("key")
            .connectTimeout(Duration.ZERO)
            .responseTimeout(Duration.ZERO)
            .build();
        assertEquals(0, unlimited.connectTimeoutMillis());
        assertEquals(0, unlimited.responseTimeoutMillis());
    }

    @Test
    void rejectsInvalidOrOverflowingTimeoutsCentrally() {
        assertThrows(IllegalArgumentException.class, () -> AiProviderConfig.builder("key")
            .connectTimeout(Duration.ofSeconds(-1))
            .build());
        assertThrows(IllegalArgumentException.class, () -> AiProviderConfig.builder("key")
            .responseTimeout(Duration.ofNanos(1))
            .build());
        assertThrows(IllegalArgumentException.class, () -> AiProviderConfig.builder("key")
            .responseTimeout(Duration.ofMillis((long) Integer.MAX_VALUE + 1))
            .build());
        assertThrows(IllegalArgumentException.class, () -> AiProviderConfig.builder("key")
            .responseTimeout(Duration.ofSeconds(Long.MAX_VALUE))
            .build());
    }

    @Test
    void rejectsApiKeysThatCannotBePlacedInAnHttpHeaderSafely() {
        assertThrows(IllegalArgumentException.class, () ->
            AiProviderConfig.builder("key\r\nX-Injected: value").build()
        );
        assertThrows(IllegalArgumentException.class, () ->
            AiProviderConfig.builder("key\u0001value").build()
        );
        assertThrows(IllegalArgumentException.class, () ->
            AiProviderConfig.builder("key\uD800value").build()
        );
    }

    @Test
    void acceptsHttpsWithoutExposingTheCustomEndpoint() {
        URI endpoint = URI.create("https://compatible.example/v1/tenant-secret/");
        AiProviderConfig config = AiProviderConfig.builder("super-secret")
            .baseUri(endpoint)
            .build();

        assertEquals(endpoint, config.baseUri());
        assertFalse(config.toString().contains("super-secret"));
        assertFalse(config.toString().contains("compatible.example"));
        assertFalse(config.toString().contains("tenant-secret"));
    }

    @Test
    void doesNotExposeTrustedHeaderNamesOrValues() {
        AiProviderConfig config = AiProviderConfig.builder("key")
            .trustedHeader("X-Private-Tenant", "private-tenant-value")
            .build();

        assertFalse(config.toString().contains("X-Private-Tenant"));
        assertFalse(config.toString().contains("private-tenant-value"));
    }

    @Test
    void allowsInsecureHttpOnlyForExplicitLoopbackTesting() {
        URI ipv4Endpoint = URI.create("http://127.0.0.1:8080/v1/");
        URI ipv6Endpoint = URI.create("http://[::1]:8080/v1/");

        assertThrows(IllegalArgumentException.class, () -> AiProviderConfig.builder("key")
            .baseUri(ipv4Endpoint)
            .build());

        assertEquals(ipv4Endpoint, AiProviderConfig.builder("key")
            .baseUri(ipv4Endpoint)
            .allowInsecureHttpForLocalTesting()
            .build()
            .baseUri());
        assertEquals(ipv6Endpoint, AiProviderConfig.builder("key")
            .baseUri(ipv6Endpoint)
            .allowInsecureHttpForLocalTesting()
            .build()
            .baseUri());

        assertThrows(IllegalArgumentException.class, () -> AiProviderConfig.builder("key")
            .baseUri(URI.create("http://example.com/v1/"))
            .allowInsecureHttpForLocalTesting()
            .build());
    }

    @Test
    void rejectsCredentialBearingOrAmbiguousCustomUris() {
        assertThrows(IllegalArgumentException.class, () -> AiProviderConfig.builder("key")
            .baseUri(URI.create("https://user:password@example.com/v1/"))
            .build());
        assertThrows(IllegalArgumentException.class, () -> AiProviderConfig.builder("key")
            .baseUri(URI.create("https://example.com/v1/?api_key=secret"))
            .build());
        assertThrows(IllegalArgumentException.class, () -> AiProviderConfig.builder("key")
            .baseUri(URI.create("https://example.com/v1/#fragment"))
            .build());
        assertThrows(IllegalArgumentException.class, () -> AiProviderConfig.builder("key")
            .baseUri(URI.create("/relative/v1/"))
            .build());
        assertThrows(IllegalArgumentException.class, () -> AiProviderConfig.builder("key")
            .baseUri(URI.create("ftp://example.com/v1/"))
            .build());
        assertThrows(IllegalArgumentException.class, () -> AiProviderConfig.builder("key")
            .baseUri(URI.create("https://example.com:0/v1/"))
            .build());
        assertThrows(IllegalArgumentException.class, () -> AiProviderConfig.builder("key")
            .baseUri(URI.create("https://example.com:99999/v1/"))
            .build());
    }
}
