package com.arcade.qa.support;

import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Assertions shared by all checks. Every error assertion also verifies the GEN-04 error body. */
public final class Asserts {

    private Asserts() {
    }

    public static void assertStatus(Response r, int status) {
        assertEquals(status, r.statusCode(), () -> "HTTP status, body: " + Json.abbreviate(r.asString()));
    }

    /** Status + GEN-04 body: a JSON object with a string {@code code} equal to the expected one and a {@code message}. */
    public static JsonNode assertError(Response r, int status, String code) {
        String raw = r.asString();
        assertEquals(status, r.statusCode(),
                () -> "expected " + status + " " + code + ", got body: " + Json.abbreviate(raw));
        JsonNode body;
        try {
            body = Json.MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("GEN-04: error body is not JSON: " + Json.abbreviate(raw));
        }
        assertTrue(body != null && body.isObject(), () -> "GEN-04: error body is not a JSON object: " + raw);
        assertTrue(body.path("code").isTextual(), () -> "GEN-04: no string 'code': " + raw);
        assertEquals(code, body.path("code").asText(), () -> "error code, body: " + raw);
        assertTrue(body.path("message").isTextual() && !body.path("message").asText().isBlank(),
                () -> "GEN-04: no 'message': " + raw);
        return body;
    }

    /** {@code details.min} / {@code details.max} as specified, e.g. "10.00" / "50000.00". */
    public static void assertMinMax(JsonNode error, String min, String max) {
        JsonNode d = error.path("details");
        assertEquals(min, d.path("min").asText(null), () -> "details.min in " + error);
        assertEquals(max, d.path("max").asText(null), () -> "details.max in " + error);
    }

    /**
     * {@code details} names the request field itself — as a key or as a whole value, not as a substring of another
     * name such as {@code betAmount} — GEN-03, SES-02, SES-07.
     */
    public static void assertDetailsMention(JsonNode error, String field) {
        JsonNode d = error.path("details");
        assertTrue(d.isObject() || d.isArray(), () -> "no 'details' naming '" + field + "' in " + error);
        if (d.has(field)) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = d.fields();
        while (it.hasNext()) {
            if (field.equals(it.next().getValue().asText(null))) {
                return;
            }
        }
        for (JsonNode e : d) {
            if (field.equals(e.asText(null)) || e.has(field)) {
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> inner = e.fields();
            while (inner.hasNext()) {
                if (field.equals(inner.next().getValue().asText(null))) {
                    return;
                }
            }
        }
        fail("'details' does not name the field '" + field + "': " + error);
    }

    public static void assertAmount(String expected, JsonNode actual, String what) {
        BigDecimal a = Json.dec(actual);
        assertTrue(a != null && a.compareTo(new BigDecimal(expected)) == 0,
                () -> what + ": expected " + expected + " but was " + actual);
    }

    public static void assertAmount(BigDecimal expected, BigDecimal actual, String what) {
        assertTrue(Json.sameValue(expected, actual), () -> what + ": expected " + expected + " but was " + actual);
    }

    public static void assertNotReplayed(Response r) {
        assertFalse(Api.replayed(r), () -> "a new request must not carry Idempotent-Replayed: true");
    }

    public static void assertReplayOf(Response original, Response replay) {
        assertEquals(original.statusCode(), replay.statusCode(),
                () -> "GEN-12: replay status, replay body: " + Json.abbreviate(replay.asString()));
        assertTrue(Api.replayed(replay), () -> "GEN-12: replay must carry Idempotent-Replayed: true, headers: "
                + replay.headers());
        JsonNode a = Json.parse(original.asString());
        JsonNode b = Json.parse(replay.asString());
        assertTrue(Json.semanticEquals(a, b), () -> "GEN-12: replay body differs\noriginal: " + a + "\nreplay:   " + b);
    }
}
