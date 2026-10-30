package com.arcade.apitests.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static com.arcade.apitests.support.Api.expect;

/**
 * The service clock (/api/test/clock). It is global for the stand and only moves forward, so tests
 * always work relative to {@link #now()} and never assume the initial value.
 */
public final class TestClock {

    private TestClock() {
    }

    public static Instant now() {
        return Json.instant(expect(Http.get("/api/test/clock"), 200).json(), "now");
    }

    public static Instant advance(Duration by) {
        JsonNode body = expect(Http.post("/api/test/clock/advance", "{\"duration\":\"" + by + "\"}"), 200).json();
        return Json.instant(body, "now");
    }

    public static Duration offset() {
        return Duration.parse(Json.text(expect(Http.get("/api/test/clock"), 200).json(), "offset"));
    }

    /**
     * Makes sure the service clock is at least {@code min} ahead of real time, so that a timestamp
     * taken from the system clock instead of the service clock is detectable.
     */
    public static void ensureShifted(Duration min) {
        Duration offset = offset();
        if (offset.compareTo(min) < 0) {
            advance(min.minus(offset));
        }
    }

    /** Moves the clock to {@code target} if it is still in the future; returns the new now. */
    public static Instant advanceTo(Instant target) {
        Instant now = now();
        return target.isAfter(now) ? advance(Duration.between(now, target)) : now;
    }

    /**
     * Leaderboard period containing {@code now} as [start, end): 1st-11th, 11th-21st, 21st-1st of the
     * next month, UTC. Written from the specification, independently of the service.
     */
    public static Instant[] leaderboardPeriod(Instant now) {
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        int d = day.getDayOfMonth();
        LocalDate start = day.withDayOfMonth(d < 11 ? 1 : d < 21 ? 11 : 21);
        LocalDate end = d < 21 ? start.plusDays(10) : start.withDayOfMonth(1).plusMonths(1);
        return new Instant[] {start.atStartOfDay(ZoneOffset.UTC).toInstant(), end.atStartOfDay(ZoneOffset.UTC).toInstant()};
    }
}
