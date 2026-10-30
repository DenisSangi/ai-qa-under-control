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

    /**
     * The last top of a poll, and when the last earlier request that did not match was sent ({@link System#nanoTime()},
     * {@link Long#MIN_VALUE} if the first read matched).
     */
    public record Read(JsonNode top, long lastMissSentAtNanos) {

        /**
         * True if a request sent after {@code nanos} still did not match. A time bound is judged by this, not by
         * when the polling loop noticed the match, so a change at any moment up to the bound passes.
         */
        public boolean missedAfter(long nanos) {
            return lastMissSentAtNanos != Long.MIN_VALUE && lastMissSentAtNanos - nanos > 0;
        }
    }

    /** Polls GET /api/leaderboard until the top matches; returns the last top read, matching or not. */
    public static JsonNode awaitTop(Predicate<JsonNode> match, Duration timeout) {
        return awaitTopRead(match, timeout).top();
    }

    /** As {@link #awaitTop}, with the send time of the last request that did not match. */
    public static Read awaitTopRead(Predicate<JsonNode> match, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long lastMiss = Long.MIN_VALUE;
        long sent = System.nanoTime();
        JsonNode top = Api.leaderboard();
        while (!match.test(top) && System.nanoTime() < deadline) {
            lastMiss = sent;
            Events.sleep(250);
            sent = System.nanoTime();
            top = Api.leaderboard();
        }
        return new Read(top, match.test(top) ? lastMiss : sent);
    }

    public static boolean sameTop(JsonNode a, JsonNode b) {
        return Json.semanticEquals(a, b);
    }
}
