package com.arcade.qa.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.function.Predicate;

/** Leaderboard helpers: periods (LDB-02) and polling of the top. */
public final class Leaderboard {

    private Leaderboard() {
    }

    /** Start of the next leaderboard period after {@code t}: the 11th, the 21st or the 1st, 00:00 UTC. */
    public static Instant nextBoundary(Instant t) {
        ZonedDateTime z = t.atZone(ZoneOffset.UTC);
        ZonedDateTime day0 = z.toLocalDate().atStartOfDay(ZoneOffset.UTC);
        int d = z.getDayOfMonth();
        ZonedDateTime next = d < 11 ? day0.withDayOfMonth(11) : d < 21 ? day0.withDayOfMonth(21)
                : day0.withDayOfMonth(1).plusMonths(1);
        return next.toInstant();
    }

    /** If a period boundary is closer than {@code margin}, moves the clock just past it. */
    public static void ensureAwayFromBoundary(Duration margin) {
        Instant now = TestClock.now();
        Instant b = nextBoundary(now);
        if (Duration.between(now, b).compareTo(margin) < 0) {
            TestClock.advanceTo(b.plusSeconds(2));
        }
    }

    public static JsonNode entry(JsonNode top, long playerId) {
        for (JsonNode e : top) {
            if (e.path("playerId").asLong() == playerId) {
                return e;
            }
        }
        return null;
    }

    /** A win that puts its player at the top of the current leaderboard: max(netResult) + 1000. */
    public static BigDecimal winningAmount() {
        BigDecimal max = BigDecimal.ZERO;
        for (JsonNode e : Api.leaderboard()) {
            BigDecimal n = Json.dec(e.get("netResult"));
            if (n != null && n.compareTo(max) > 0) {
                max = n;
            }
        }
        return max.setScale(0, java.math.RoundingMode.CEILING).add(new BigDecimal("1000"));
    }

    /** Polls GET /api/leaderboard until the top matches; returns the last top read, matching or not. */
    public static JsonNode awaitTop(Predicate<JsonNode> match, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode top = Api.leaderboard();
        while (!match.test(top) && System.nanoTime() < deadline) {
            Events.sleep(250);
            top = Api.leaderboard();
        }
        return top;
    }

    public static boolean sameTop(JsonNode a, JsonNode b) {
        return Json.semanticEquals(a, b);
    }
}
