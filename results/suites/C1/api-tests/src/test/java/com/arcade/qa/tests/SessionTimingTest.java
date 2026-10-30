package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Config;
import com.arcade.qa.support.Events;
import com.arcade.qa.support.Journal;
import com.arcade.qa.support.Json;
import com.arcade.qa.support.Snapshot;
import com.arcade.qa.support.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Order(6)
@DisplayName("F-05 Session deadline and expiry")
class SessionTimingTest extends ApiTest {

    /** SES-11: observable no later than 5 s of real time after the clock passes startedAt + 60 s. */
    static final Duration EXPIRY_OBSERVABLE = Duration.ofSeconds(5);

    static Instant startedAt(JsonNode session) {
        return Instant.parse(session.path("startedAt").asText());
    }

    /** Moves the service clock to {@code target}; returns the {@link System#nanoTime()} just before the advance request. */
    static long advanceTo(Instant target) {
        Duration d = Duration.between(TestClock.now(), target);
        long sent = System.nanoTime();
        if (!d.isNegative() && !d.isZero()) {
            TestClock.advance(d);
        }
        return sent;
    }

    /** Reads until {@code done} holds or {@code limitNanos} ({@link System#nanoTime()}) has passed; returns the last read. */
    static <T> T pollUntil(Supplier<T> read, Predicate<T> done, long limitNanos) {
        T value = read.get();
        while (!done.test(value)) {
            long left = limitNanos - System.nanoTime();
            if (left <= 0) {
                return value;
            }
            Events.sleep(Math.max(1, Math.min(100, left / 1_000_000)));
            value = read.get();
        }
        return value;
    }

    /**
     * Precondition helper: moves the clock past the deadline and waits, up to the SES-11 bound, until the session is
     * EXPIRED and its REFUND record is in the journal. Returns that bound as a {@link System#nanoTime()}.
     */
    static long expire(long sessionId) {
        JsonNode s = Api.session(sessionId);
        long playerId = s.path("playerId").asLong();
        int refunds = Journal.records(playerId, "REFUND").size();
        long limit = advanceTo(startedAt(s).plusSeconds(61)) + EXPIRY_OBSERVABLE.toNanos();
        JsonNode session = pollUntil(() -> Api.session(sessionId),
                x -> "EXPIRED".equals(x.path("status").asText()), limit);
        int refundsNow = pollUntil(() -> Journal.records(playerId, "REFUND").size(), n -> n > refunds, limit);
        if (!"EXPIRED".equals(session.path("status").asText()) || refundsNow <= refunds) {
            fail("precondition failed: session " + sessionId + " did not expire with a refund within "
                    + EXPIRY_OBSERVABLE + ": " + session + ", REFUND records " + refundsNow);
        }
        return limit;
    }

    @Test
    @Tag("SES-10")
    @DisplayName("CL-069 finish with the service clock at startedAt + 59 s is accepted")
    void finishJustBeforeDeadline() {
        long p = Api.fundedPlayer("100");
        JsonNode s = Api.startOk(p, "10");
        TestClock.advanceTo(startedAt(s).plusSeconds(59));
        Response r = Api.finish(s.path("id").asLong(), "15");
        assertStatus(r, 200);
        assertEquals("FINISHED", Api.body(r).path("status").asText());
        assertAmount(new BigDecimal("105"), Api.balance(p), "balance");
    }

    @Test
    @Tag("SES-10")
    @DisplayName("CL-070 finish at startedAt + 60 s -> 409 SESSION_NOT_ACTIVE; the win is not credited")
    void finishAtDeadlineRefused() {
        long p = Api.fundedPlayer("100");
        JsonNode s = Api.startOk(p, "10");
        long sid = s.path("id").asLong();
        TestClock.advanceTo(startedAt(s).plusSeconds(60));
        assertError(Api.finish(sid, "50"), 409, "SESSION_NOT_ACTIVE");
        Events.sleep(Snapshot.EVENT_QUIET_MILLIS);
        assertEquals(0, Journal.records(p, "WIN").size(), "no WIN record");
        assertTrue(Events.ofType(Config.GAME_EVENTS, p, "SESSION_FINISHED").isEmpty(), "no SESSION_FINISHED");
        JsonNode after = Api.session(sid);
        assertFalse("FINISHED".equals(after.path("status").asText()), () -> "session must not be FINISHED: " + after);
        assertAmount("0", after.get("winAmount"), "winAmount: the refused win must not be recorded");
        BigDecimal balance = Api.balance(p);
        assertTrue(Json.sameValue(new BigDecimal("90"), balance) || Json.sameValue(new BigDecimal("100"), balance),
                () -> "the win must not be credited: balance " + balance + ", expected 90 (or 100 after the expiry refund)");
    }

    @Test
    @Tag("SES-11")
    @Tag("GEN-21")
    @DisplayName("CL-071 expiry within 5 s: EXPIRED, finishedAt null, bet refunded, one REFUND, SESSION_EXPIRED")
    void expiry() {
        long p = Api.fundedPlayer("100");
        JsonNode s = Api.startOk(p, "25");
        long sid = s.path("id").asLong();
        Instant deadline = startedAt(s).plusSeconds(60);
        // SES-11: every effect is observable within 5 s after the clock passes the deadline, i.e. after the advance.
        long limit = advanceTo(deadline.plusSeconds(1)) + EXPIRY_OBSERVABLE.toNanos();

        JsonNode session = pollUntil(() -> Api.session(sid), x -> "EXPIRED".equals(x.path("status").asText()), limit);
        assertEquals("EXPIRED", session.path("status").asText(), () -> "status within 5 s: " + session);
        assertTrue(session.path("finishedAt").isNull(), () -> "finishedAt must stay null: " + session);

        List<Journal.Tx> refunds = pollUntil(() -> Journal.records(p, "REFUND"), r -> !r.isEmpty(), limit);
        assertEquals(1, refunds.size(), "REFUND records within 5 s");
        assertAmount(new BigDecimal("25"), refunds.get(0).amount(), "REFUND amount");
        BigDecimal balance = pollUntil(() -> Api.balance(p), b -> Json.sameValue(new BigDecimal("100"), b), limit);
        assertAmount(new BigDecimal("100"), balance, "balance after refund within 5 s");
        List<Events.Event> expired = pollUntil(() -> Events.ofType(Config.GAME_EVENTS, p, "SESSION_EXPIRED"),
                l -> !l.isEmpty(), limit);
        assertFalse(expired.isEmpty(), () -> "no SESSION_EXPIRED in game-events within 5 s; events of the player: "
                + Events.of(Config.GAME_EVENTS, p).stream().map(Events.Event::raw).toList());
        Events.Event e = expired.get(0);
        GameSessionTest.assertFields(e.value(), "eventId", "type", "sessionId", "playerId", "game", "bet", "expiredAt");
        assertEquals(sid, e.value().path("sessionId").asLong(), "event sessionId");
        assertEquals("slots", e.value().path("game").asText(), "event game");
        assertAmount("25", e.value().get("bet"), "event bet");
        Instant expiredAt = Json.instant(e.value().get("expiredAt"));
        assertFalse(expiredAt.isBefore(deadline), () -> "expiredAt " + expiredAt + " is before startedAt + 60 s " + deadline);
    }

    @Test
    @Tag("SES-09")
    @DisplayName("CL-072 finishing an EXPIRED session -> 409 SESSION_NOT_ACTIVE; nothing changes")
    void finishExpired() {
        long p = Api.fundedPlayer("100");
        long sid = Api.startOk(p, "10").path("id").asLong();
        expire(sid);
        Snapshot before = Snapshot.afterEvents(p, 2, 2); // REGISTERED, DEPOSIT; SESSION_STARTED, SESSION_EXPIRED
        JsonNode sessionBefore = Api.session(sid);
        assertError(Api.finish(sid, "40"), 409, "SESSION_NOT_ACTIVE");
        before.assertUnchanged();
        JsonNode s = Api.session(sid);
        assertEquals("EXPIRED", s.path("status").asText(), "status");
        assertAmount("0", s.get("winAmount"), "winAmount");
        assertTrue(s.path("finishedAt").isNull(), "finishedAt");
        assertTrue(Json.semanticEquals(sessionBefore, s),
                () -> "the session must not change\nbefore: " + sessionBefore + "\nafter:  " + s);
    }

    @Test
    @Tag("SES-04")
    @Tag("SES-12")
    @DisplayName("CL-073 an EXPIRED session does not prevent a new session")
    void newSessionAfterExpiry() {
        long p = Api.fundedPlayer("100");
        long sid = Api.startOk(p, "10").path("id").asLong();
        expire(sid);
        assertStatus(Api.startSession(p, "10"), 201);
    }
}
