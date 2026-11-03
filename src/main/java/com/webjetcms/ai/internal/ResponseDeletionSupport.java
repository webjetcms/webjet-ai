package com.webjetcms.ai.internal;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.apache.http.HttpEntity;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.webjetcms.ai.AiProviderException;

/** Shared input and payload validation for remote response deletion. */
public final class ResponseDeletionSupport {

    private static final int MAX_RESPONSE_BYTES = 65536;
    private static final ObjectReader READER = new ObjectMapper().reader()
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private ResponseDeletionSupport() { }

    /**
     * Validates an opaque ID and encodes it as one URI path segment, without normalization.
     *
     * @param providerId provider identifier for validation failures
     * @param responseId opaque provider resource ID
     * @return encoded path segment
     * @throws AiProviderException if the ID is missing, a dot path segment, or contains malformed Unicode
     */
    public static String encodedId(String providerId, String responseId) throws AiProviderException {
        if (responseId == null || responseId.isBlank() || ".".equals(responseId) || "..".equals(responseId)) {
            throw new AiProviderException(providerId, "Response ID must be non-blank and must not be a dot path segment");
        }
        if (StandardCharsets.UTF_8.newEncoder().canEncode(responseId) == false) {
            throw new AiProviderException(providerId, "Response ID must contain valid Unicode");
        }
        return URLEncoder.encode(responseId, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Reads a small UTF-8 deletion response without accepting an unbounded payload.
     *
     * @param entity HTTP response entity, possibly absent
     * @return body text, empty when no entity exists
     * @throws ResponseTooLargeException if the body exceeds the limit
     * @throws IOException if reading fails
     */
    public static String readBody(HttpEntity entity) throws IOException {
        if (entity == null) return "";
        byte[] bytes = entity.getContent().readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new ResponseTooLargeException();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Decodes a single, unambiguous JSON object for provider-specific confirmation checks.
     *
     * @param payload response body
     * @return parsed object
     * @throws IOException if the body is empty, malformed, duplicated, or not an object
     */
    public static JsonNode readObject(String payload) throws IOException {
        JsonNode body = READER.readTree(payload);
        if (body == null || body.isObject() == false) {
            throw new IOException("Deletion response must contain a JSON object");
        }
        return body;
    }

    /** Distinguishes the response size limit from transport failures while reading a body. */
    public static final class ResponseTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        private ResponseTooLargeException() {
            super("Deletion response exceeds " + MAX_RESPONSE_BYTES + " bytes");
        }
    }
}
