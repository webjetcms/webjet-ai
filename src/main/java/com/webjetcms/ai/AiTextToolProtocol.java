package com.webjetcms.ai;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.webjetcms.ai.internal.ToolJson;

/** Prompt and response format for applications offering functions through ordinary model text. */
public final class AiTextToolProtocol {
    private static final Pattern JSON_BLOCK = Pattern.compile("\\A```(?:json)?[ \\t]*\\R(.*)\\R```\\z", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final JsonFactory TOKENS = new JsonFactory();
    private static final Set<Class<?>> JSON_SCALARS = Set.of(String.class, Boolean.class, Byte.class, Short.class,
        Integer.class, Long.class, Float.class, Double.class, BigInteger.class, BigDecimal.class);

    private AiTextToolProtocol() { }

    /** One model decision. Tool selection, authorization and execution belong to the caller. */
    public sealed interface Decision permits Answer, Call, Invalid { }

    /**
     * Final answer to deliver to the user.
     * @param text ordinary answer or the text from an explicit final decision
     */
    public record Answer(String text) implements Decision {
        @Override
        public String toString() { return "Answer[redacted]"; }
    }

    /**
     * Tool invocation proposed by the model.
     * @param name proposed tool name
     * @param arguments immutable snapshot of decoded JSON fields, including nested maps, lists and null values
     */
    public record Call(String name, Map<String, Object> arguments) implements Decision {
        /** Takes a snapshot of the supplied JSON object. */
        public Call {
            arguments = immutableObject(arguments);
        }

        @Override
        public String toString() { return "Call[redacted]"; }
    }

    private static Map<String, Object> immutableObject(Map<?, ?> fields) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (var field : fields.entrySet()) {
            if (!(field.getKey() instanceof String key)) throw new IllegalArgumentException("Expected a JSON property name.");
            copy.put(key, immutableValue(field.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value == null || JSON_SCALARS.contains(value.getClass())) return value;
        if (value instanceof Map<?, ?> fields) return immutableObject(fields);
        if (value instanceof List<?> items) {
            List<Object> copy = new ArrayList<>(items.size());
            for (Object item : items) copy.add(immutableValue(item));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Number number) return new BigDecimal(number.toString());
        throw new IllegalArgumentException("Expected a decoded JSON value in tool arguments.");
    }

    /**
     * Decision that could not be decoded.
     * @param reason feedback for a new model decision, without the rejected payload
     */
    public record Invalid(String reason) implements Decision { }

    /** {@return instructions to include alongside the application's tool catalog} */
    public static String instructions() {
        return """
            Work one step at a time from the user's request and the returned results.
            To call a tool, return exactly {"type":"call","name":"...","arguments":{}} and wait for its result.
            To answer or ask for a missing user decision, return ordinary text or {"type":"final","text":"..."}.
            """;
    }

    /**
     * Decodes one text decision. A JSON answer can be returned inside an explicit final decision.
     * @param response complete model output
     * @param maxChars maximum accepted length in UTF-16 code units
     * @return the answer, requested call, or feedback explaining why the decision could not be used
     * @throws IllegalArgumentException if the configured limit is not positive
     */
    public static Decision parse(String response, int maxChars) {
        if (maxChars < 1) throw new IllegalArgumentException("A positive response size limit is required.");
        if (response == null || response.length() > maxChars || response.isBlank()) {
            return new Invalid("Return a nonempty answer or a single tool decision within the size limit.");
        }
        String text = response.strip();
        var block = JSON_BLOCK.matcher(text);
        boolean fenced = block.matches();
        String candidate = fenced ? block.group(1).strip() : text;
        boolean fencedObject = fenced && candidate.startsWith("{");
        if (!fencedObject && isOrdinaryText(candidate)) return new Answer(text);

        JsonNode decision;
        try {
            decision = ToolJson.readTree(candidate);
        } catch (JsonProcessingException invalid) {
            var location = invalid.getLocation();
            String at = location == null ? "" : " at line " + location.getLineNr() + ", column " + location.getColumnNr();
            return new Invalid("Return exactly one JSON object. Invalid tool JSON" + at + ". Escape quotes and newlines inside string values.");
        }
        String type = decision.path("type").textValue();
        if ("final".equals(type) && decision.size() == 2 && decision.path("text").isTextual() && !decision.get("text").textValue().isBlank()) {
            return new Answer(decision.get("text").textValue());
        }
        if ("call".equals(type) && decision.size() == 3 && decision.path("name").isTextual() && decision.path("arguments").isObject()) {
            return new Call(decision.get("name").textValue(), ToolJson.fields(decision.get("arguments")));
        }
        return new Invalid("A call needs exactly type, name and an arguments object. A final needs exactly type and nonempty text.");
    }

    /** Looks for decision objects in prose without interpreting their arguments or dispatching a call. */
    private static boolean isOrdinaryText(String text) {
        int start = text.indexOf('{');
        if (start < 0) return true;
        char[] chars = text.toCharArray();
        while (start >= 0) {
            int next = start + 1;
            try (var parser = TOKENS.createParser(chars, start, chars.length - start)) {
                parser.nextToken();
                int openObjects = 1;
                while (openObjects > 0) {
                    JsonToken token = parser.nextToken();
                    if (token == null) return start != 0;
                    if (token == JsonToken.START_OBJECT) openObjects++;
                    else if (token == JsonToken.END_OBJECT) openObjects--;
                    else if (token == JsonToken.VALUE_STRING && "type".equals(parser.currentName())) {
                        String type = parser.getText();
                        if ("call".equals(type) || "final".equals(type)) return false;
                    }
                }
                next = start + (int) parser.currentLocation().getCharOffset();
                if (start == 0) {
                    String suffix = text.substring(next).stripLeading();
                    if (suffix.isEmpty() || suffix.startsWith("{") || suffix.startsWith("[")) return false;
                }
            } catch (IOException invalid) {
                if (start == 0) return false;
                if (invalid instanceof JsonProcessingException syntax && syntax.getLocation() != null) {
                    int offset = (int) syntax.getLocation().getCharOffset();
                    next = Math.max(next, start + offset - 1);
                }
            }
            start = text.indexOf('{', next);
        }
        return true;
    }
}
