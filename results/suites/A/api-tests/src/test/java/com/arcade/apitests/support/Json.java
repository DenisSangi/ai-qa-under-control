package com.arcade.apitests.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;

/** JSON helpers. Money is read as BigDecimal so that 100.00 never turns into 99.99999. */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private Json() {
    }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException("not JSON: " + text, e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** null for a missing field or JSON null. */
    public static BigDecimal dec(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.decimalValue();
    }

    public static Instant instant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : Instant.parse(value.asText());
    }

    public static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
