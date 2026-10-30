package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Config;
import com.arcade.qa.support.Events;
import com.arcade.qa.support.Journal;
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

    /** Precondition helper: moves the clock past the deadline and waits until the session is EXPIRED. */
    static void expire(long sessionId) {
        TestClock.advanceTo(startedAt(Api.session(sessionId)).plusSeconds(61));
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if ("EXPIRED".equals(Api.session(sessionId).path("status").asText())) {
                return;
            }
            Events.sleep(200);
        }
        fail("precondition failed: session " + sessionId + " did not expire: " + Api.session(sessionId));
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
        assertEquals(0, Journal.records(p, "WIN").size(), "no WIN record");
        assertTrue(Events.ofType(Config.GAME_EVENTS, p, "SESSION_FINISHED").isEmpty(), "no SESSION_FINISHED");
        assertFalse("FINISHED".equals(Api.session(sid).path("status").asText()), "session must not be FINISHED");
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
        TestClock.advanceTo(deadline.plusSeconds(1));
        long limit = System.nanoTime() + EXPIRY_OBSERVABLE.toNanos();

        JsonNode session = Api.session(sid);
        while (!"EXPIRED".equals(session.path("status").asText()) && System.nanoTime() < limit) {
            Events.sleep(100);
            session = Api.session(sid);
        }
        JsonNode finalSession = session;
        assertEquals("EXPIRED", session.path("status").asText(), () -> "status within 5 s: " + finalSession);
        assertTrue(session.path("finishedAt").isNull(), () -> "finishedAt must stay null: " + finalSession);

        Duration left = Duration.ofNanos(Math.max(0, limit - System.nanoTime()));
        List<Journal.Tx> refunds = Journal.records(p, "REFUND");
        assertEquals(1, refunds.size(), "REFUND records within 5 s");
        assertAmount(new BigDecimal("25"), refunds.get(0).amount(), "REFUND amount");
        assertAmount(new BigDecimal("100"), Api.balance(p), "balance after refund");
        Events.Event e = Events.awaitOne(Config.GAME_EVENTS, p, "SESSION_EXPIRED", left.plusMillis(200));
        GameSessionTest.assertFields(e.value(), "eventId", "type", "sessionId", "playerId", "game", "bet", "expiredAt");
        assertEquals(sid, e.value().path("sessionId").asLong(), "event sessionId");
        assertEquals("slots", e.value().path("game").asText(), "event game");
        assertAmount("25", e.value().get("bet"), "event bet");
        Instant expiredAt = Instant.parse(e.value().path("expiredAt").asText());
        assertFalse(expiredAt.isBefore(deadline), () -> "expiredAt " + expiredAt + " is before startedAt + 60 s " + deadline);
    }

    @Test
    @Tag("SES-09")
    @DisplayName("CL-072 finishing an EXPIRED session -> 409 SESSION_NOT_ACTIVE; nothing changes")
    void finishExpired() {
        long p = Api.fundedPlayer("100");
        long sid = Api.startOk(p, "10").path("id").asLong();
        expire(sid);
        Events.await(Config.GAME_EVENTS, p, e -> "SESSION_EXPIRED".equals(e.type()), 1, EVENT_TIMEOUT);
        Snapshot before = Snapshot.of(p);
        assertError(Api.finish(sid, "40"), 409, "SESSION_NOT_ACTIVE");
        before.assertUnchanged();
        JsonNode s = Api.session(sid);
        assertEquals("EXPIRED", s.path("status").asText(), "status");
        assertTrue(s.path("finishedAt").isNull(), "finishedAt");
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
