package com.arcade.qa.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Map;

/** JSON parsing with exact decimals; monetary values are always compared by value. */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    private Json() {
    }

    public static JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new AssertionError("response is not JSON: " + abbreviate(text), e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A number node, or a numeric string, as BigDecimal; null for missing/null nodes. */
    public static BigDecimal dec(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        return new BigDecimal(node.asText());
    }

    /**
     * A point in time whatever its representation, which the specification does not fix: an ISO-8601 instant or
     * offset date-time string, or epoch seconds / epoch milliseconds as a number or numeric string. Fails if the
     * node is none of these.
     */
    public static Instant instant(JsonNode node) {
        if (node != null && !node.isNull() && !node.isMissingNode()) {
            String text = node.asText();
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException ignored) {
                // not an ISO-8601 date-time; try an epoch number
            }
            try {
                BigDecimal n = new BigDecimal(text);
                BigDecimal millis = n.abs().compareTo(new BigDecimal("100000000000")) >= 0 ? n
                        : n.multiply(new BigDecimal("1000"));
                return Instant.ofEpochMilli(millis.longValue());
            } catch (NumberFormatException ignored) {
                // not a number either
            }
        }
        throw new AssertionError("not a point in time: " + node);
    }

    public static boolean sameValue(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /** Structural equality where numbers are equal by value (100 == 100.00). */
    public static boolean semanticEquals(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.getNodeType() != b.getNodeType()) {
            return false;
        }
        if (a.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> it = a.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!b.has(e.getKey()) || !semanticEquals(e.getValue(), b.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!semanticEquals(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    public static String abbreviate(String s) {
        return s == null ? "null" : s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }
}
