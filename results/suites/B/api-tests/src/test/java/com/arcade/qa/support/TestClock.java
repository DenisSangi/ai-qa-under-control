package com.arcade.qa.support;

import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The service clock (SPEC §10). It only moves forward and is shared by the whole stand. */
public final class TestClock {

    private TestClock() {
    }

    private static JsonNode read() {
        Response r = Api.req().get("/api/test/clock");
        assertEquals(200, r.statusCode(), () -> "GET /api/test/clock: " + r.asString());
        return Json.parse(r.asString());
    }

    public static Instant now() {
        return Instant.parse(read().path("now").asText());
    }

    public static Duration offset() {
        return Duration.parse(read().path("offset").asText());
    }

    public static Instant advance(Duration d) {
        Response r = Api.req().body("{\"duration\": \"" + d + "\"}").post("/api/test/clock/advance");
        assertEquals(200, r.statusCode(), () -> "advance clock by " + d + ": " + r.asString());
        return Instant.parse(Json.parse(r.asString()).path("now").asText());
    }

    /** Moves the service clock to {@code target} (a few ms later, because real time keeps running). */
    public static Instant advanceTo(Instant target) {
        Duration d = Duration.between(now(), target);
        return d.isNegative() || d.isZero() ? now() : advance(d);
    }

    /** Makes the service clock distinguishable from real time (for "on the service clock" checks). */
    public static void ensureOffsetAtLeast(Duration min) {
        Duration current = offset();
        if (current.compareTo(min) < 0) {
            advance(min.minus(current).plusSeconds(1));
        }
    }
}
