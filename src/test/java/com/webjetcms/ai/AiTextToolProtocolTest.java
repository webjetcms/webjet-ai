package com.webjetcms.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiTextToolProtocolTest {
    @Test
    void parsesPlainAndExplicitAnswersAndFencedCalls() {
        assertEquals(new AiTextToolProtocol.Answer("Hello"), AiTextToolProtocol.parse("  Hello  ", 32));
        assertEquals(new AiTextToolProtocol.Answer("{\"id\":7}"),
            AiTextToolProtocol.parse("{\"type\":\"final\",\"text\":\"{\\\"id\\\":7}\"}", 256));
        var decision = AiTextToolProtocol.parse("""
            ```json
            {"type":"call","name":"lookup","arguments":{"price":1234567890.0123456789,"filter":null}}
            ```
            """, 256);
        var call = assertInstanceOf(AiTextToolProtocol.Call.class, decision);
        assertEquals("lookup", call.name());
        assertEquals(new BigDecimal("1234567890.0123456789"), call.arguments().get("price"));
        assertTrue(call.arguments().containsKey("filter"));
        assertNull(call.arguments().get("filter"));
        assertThrows(UnsupportedOperationException.class, () -> call.arguments().clear());
    }

    @Test
    void snapshotsAllArgumentsInThePublicConstructor() {
        var count = new AtomicInteger(2);
        var fields = new LinkedHashMap<String, Object>();
        fields.put("count", count);
        fields.put("filter", null);
        var items = new ArrayList<>(Arrays.asList(fields, null));
        var input = new LinkedHashMap<String, Object>();
        input.put("items", items);
        var call = new AiTextToolProtocol.Call("lookup", input);
        count.incrementAndGet();
        fields.clear();
        items.clear();
        input.clear();

        var snapshot = (List<?>) call.arguments().get("items");
        var item = (Map<?, ?>) snapshot.get(0);
        assertEquals(new BigDecimal("2"), item.get("count"));
        assertTrue(item.containsKey("filter"));
        assertNull(item.get("filter"));
        assertNull(snapshot.get(1));
        assertThrows(UnsupportedOperationException.class, item::clear);
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertThrows(UnsupportedOperationException.class, () -> call.arguments().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"The record is {\"name\":\"Ada\"}.", "Its schema is {\"type\":\"string\"}.",
        "The log contains {\"arguments\":{\"mode\":\"read\"}}.", "{\"name\":\"Ada\"} is the matching record.",
        "Use {placeholder} for the record {\"name\":\"Ada\"}."})
    void preservesProseWithInlineData(String text) {
        assertEquals(new AiTextToolProtocol.Answer(text), AiTextToolProtocol.parse(text, 256));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "before {\"type\":\"call\"}", "{\"type\":\"final\",\"text\":\" \"}",
        "before {\"name\":\"lookup\",\"arguments\":{},\"type\":\"call\"}", "before {\"text\":\"done\",\"type\":\"final\"}",
        "Data {\"name\":\"Ada\"}, then {\"name\":\"lookup\",\"arguments\":{},\"t\\u0079pe\":\"c\\u0061ll\"}",
        "```json\n{\"name\":\"Ada\"} trailing text\n```",
        "{\"type\":\"final\",\"text\":\"hello\"} {}", "{\"type\":\"call\",\"name\":\"x\",\"arguments\":[]}",
        "{\"type\":\"call\",\"name\":\"x\",\"arguments\":{\"secret\":1,\"secret\":2}}"})
    void rejectsAmbiguousOrMalformedDecisions(String output) {
        var invalid = assertInstanceOf(AiTextToolProtocol.Invalid.class, AiTextToolProtocol.parse(output, 256));
        assertFalse(invalid.reason().contains("secret"));
    }

    @Test
    void enforcesTheCallerSelectedLimit() {
        assertInstanceOf(AiTextToolProtocol.Invalid.class, AiTextToolProtocol.parse("abcd", 3));
        assertInstanceOf(AiTextToolProtocol.Answer.class, AiTextToolProtocol.parse("abc", 3));
        assertThrows(IllegalArgumentException.class, () -> AiTextToolProtocol.parse("a", 0));
    }
}
