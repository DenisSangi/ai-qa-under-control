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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertDetailsMention;
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertMinMax;
import static com.arcade.qa.support.Asserts.assertNotReplayed;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Order(5)
@DisplayName("F-04 Game session: bet and win")
class GameSessionTest extends ApiTest {

    static void assertFields(JsonNode node, String... fields) {
        for (String f : fields) {
            assertTrue(node.has(f), () -> "field '" + f + "' missing in " + node);
        }
    }

    static void assertWithin(Instant t, Instant from, Instant to, String what) {
        Instant lo = from.minusSeconds(2);
        Instant hi = to.plusSeconds(2);
        assertTrue(!t.isBefore(lo) && !t.isAfter(hi),
                () -> what + " " + t + " is not the service-clock time [" + from + ", " + to + "]");
    }

    @Test
    @Tag("SES-01")
    @Tag("GEN-21")
    @DisplayName("CL-053 start: 201 ACTIVE session, balance - bet, one BET record, SESSION_STARTED with all fields")
    void startSession() {
        long p = Api.fundedPlayer("100");
        Response r = Api.startSession(Api.key(), p, "roulette", "12.50");
        assertStatus(r, 201);
        JsonNode s = Api.body(r);
        assertFields(s, "id", "playerId", "game", "betAmount", "winAmount", "status", "startedAt", "finishedAt");
        assertAll("session " + s,
                () -> assertEquals(p, s.path("playerId").asLong()),
                () -> assertEquals("roulette", s.path("game").asText()),
                () -> assertAmount("12.50", s.get("betAmount"), "betAmount"),
                () -> assertAmount("0", s.get("winAmount"), "winAmount"),
                () -> assertEquals("ACTIVE", s.path("status").asText()),
                () -> assertTrue(s.path("finishedAt").isNull(), "finishedAt must be null"));
        assertAmount(new BigDecimal("87.50"), Api.balance(p), "balance");
        List<Journal.Tx> bets = Journal.records(p, "BET");
        assertEquals(1, bets.size(), "BET records");
        assertAmount(new BigDecimal("12.50"), bets.get(0).amount(), "BET amount");
        JsonNode e = Events.awaitOne(Config.GAME_EVENTS, p, "SESSION_STARTED", EVENT_TIMEOUT).value();
        assertFields(e, "eventId", "type", "sessionId", "playerId", "game", "bet", "startedAt");
        assertEquals(s.path("id").asLong(), e.path("sessionId").asLong(), "event sessionId");
        assertEquals("roulette", e.path("game").asText(), "event game");
        assertAmount("12.50", e.get("bet"), "event bet");
    }

    @Test
    @Tag("SES-02")
    @DisplayName("CL-054 every game slots, roulette, blackjack, poker, crash is accepted")
    void allGamesAccepted() {
        long p = Api.fundedPlayer("100");
        for (String game : Api.GAMES) {
            Response r = Api.startSession(Api.key(), p, game, "1");
            assertStatus(r, 201);
            assertEquals(game, Api.body(r).path("game").asText());
            Api.finishOk(Api.body(r).path("id").asLong(), "0");
        }
    }

    @ParameterizedTest(name = "game \"{0}\"")
    @ValueSource(strings = {"Slots", "SLOTS", "chess", ""})
    @Tag("SES-02")
    @DisplayName("CL-055 unknown or wrongly cased game -> 400 VALIDATION_ERROR with game in details")
    void invalidGame(String game) {
        long p = Api.fundedPlayer("100");
        JsonNode err = assertError(Api.startSession(Api.key(), p, game, "5"), 400, "VALIDATION_ERROR");
        assertDetailsMention(err, "game");
        assertAmount(new BigDecimal("100"), Api.balance(p), "balance");
    }

    @Test
    @Tag("SES-03")
    @DisplayName("CL-056 bet bounds 1.00 and 500.00 are accepted")
    void betBoundsAccepted() {
        long p = Api.fundedPlayer("600");
        long s1 = Api.body(assertCreated(Api.startSession(Api.key(), p, "slots", "1.00"))).path("id").asLong();
        Api.finishOk(s1, "0");
        assertCreated(Api.startSession(Api.key(), p, "slots", "500.00"));
        assertAmount(new BigDecimal("99.00"), Api.balance(p), "balance");
    }

    private static Response assertCreated(Response r) {
        assertStatus(r, 201);
        return r;
    }

    @ParameterizedTest(name = "bet {0}")
    @ValueSource(strings = {"0.99", "500.01"})
    @Tag("SES-03")
    @DisplayName("CL-057 bet out of range: 422 BET_AMOUNT_OUT_OF_RANGE with min/max, nothing changes, key free")
    void betOutOfRange(String bet) {
        long p = Api.fundedPlayer("1000");
        String key = Api.key();
        Snapshot before = Snapshot.afterEvents(p, 2, 0); // REGISTERED, DEPOSIT
        JsonNode err = assertError(Api.startSession(key, p, "slots", bet), 422, "BET_AMOUNT_OUT_OF_RANGE");
        assertMinMax(err, "1.00", "500.00");
        before.assertUnchanged();
        Response retry = Api.startSession(key, p, "slots", "5");
        assertStatus(retry, 201);
        assertNotReplayed(retry);
    }

    @Test
    @Tag("SES-04")
    @DisplayName("CL-058 second start while ACTIVE: 409 ACTIVE_SESSION_EXISTS, nothing changes, key free")
    void oneActiveSession() {
        long p = Api.fundedPlayer("100");
        long s = Api.startOk(p, "10").path("id").asLong();
        String key = Api.key();
        Snapshot before = Snapshot.afterEvents(p, 2, 1); // REGISTERED, DEPOSIT; SESSION_STARTED
        assertError(Api.startSession(key, p, "poker", "5"), 409, "ACTIVE_SESSION_EXISTS");
        before.assertUnchanged();
        Api.finishOk(s, "0");
        Response retry = Api.startSession(key, p, "poker", "5");
        assertStatus(retry, 201);
        assertNotReplayed(retry);
    }

    @Test
    @Tag("SES-05")
    @DisplayName("CL-059 bet above balance: 422 INSUFFICIENT_FUNDS, nothing changes, key free")
    void insufficientFunds() {
        long p = Api.fundedPlayer("20");
        String key = Api.key();
        Snapshot before = Snapshot.afterEvents(p, 2, 0); // REGISTERED, DEPOSIT
        assertError(Api.startSession(key, p, "slots", "20.01"), 422, "INSUFFICIENT_FUNDS");
        before.assertUnchanged();
        Response retry = Api.startSession(key, p, "slots", "20");
        assertStatus(retry, 201);
        assertNotReplayed(retry);
    }

    @Test
    @Tag("SES-05")
    @DisplayName("CL-060 a bet of the whole balance is allowed")
    void wholeBalanceBet() {
        long p = Api.fundedPlayer("37.45");
        assertStatus(Api.startSession(Api.key(), p, "crash", "37.45"), 201);
        assertAmount(BigDecimal.ZERO, Api.balance(p), "balance");
    }

    @Test
    @Tag("SES-06")
    @DisplayName("CL-061 order of checks: 400 (game) before 404, 404 before bet range")
    void formatAndPlayerFirst() {
        JsonNode err = assertError(Api.startSession(Api.key(), Config.UNKNOWN_ID, "Slots", "5"), 400, "VALIDATION_ERROR");
        assertDetailsMention(err, "game");
        assertError(Api.startSession(Api.key(), Config.UNKNOWN_ID, "slots", "\"5\""), 400, "VALIDATION_ERROR");
        assertError(Api.startSession(Api.key(), Config.UNKNOWN_ID, "slots", "0.5"), 404, "NOT_FOUND");
    }

    @Test
    @Tag("SES-06")
    @DisplayName("CL-062 order of checks: key before bet range, bet range before active session, active session before balance")
    void keyRangeSessionBalanceOrder() {
        long p = Api.fundedPlayer("100");
        String key = Api.key();
        long s = Api.body(assertCreated(Api.startSession(key, p, "slots", "10"))).path("id").asLong();
        assertError(Api.startSession(key, p, "slots", "0.5"), 422, "IDEMPOTENCY_KEY_REUSED");
        assertError(Api.startSession(Api.key(), p, "slots", "0.5"), 422, "BET_AMOUNT_OUT_OF_RANGE");
        assertError(Api.startSession(Api.key(), p, "slots", "200"), 409, "ACTIVE_SESSION_EXISTS");
        Api.finishOk(s, "0");
        assertError(Api.startSession(Api.key(), p, "slots", "200"), 422, "INSUFFICIENT_FUNDS");
    }

    @Test
    @Tag("SES-07")
    @Tag("GEN-21")
    @DisplayName("CL-063 finish with a win: 200 FINISHED, balance + win, one WIN record, SESSION_FINISHED with all fields")
    void finishWithWin() {
        long q = Api.fundedPlayer("100");
        Response st = Api.startSession(Api.key(), q, "blackjack", "10");
        assertStatus(st, 201);
        long sid = Api.body(st).path("id").asLong();
        // The finish comes 5 s after the start on the service clock, so finishedAt cannot be mistaken for startedAt.
        TestClock.advance(Duration.ofSeconds(5));
        Response r = Api.finish(sid, "25.30");
        assertStatus(r, 200);
        JsonNode s = Api.body(r);
        assertAll("finished session " + s,
                () -> assertEquals("FINISHED", s.path("status").asText()),
                () -> assertAmount("25.30", s.get("winAmount"), "winAmount"),
                () -> assertFalse(s.path("finishedAt").isNull() || s.path("finishedAt").isMissingNode(),
                        "finishedAt must be set"));
        assertAmount(new BigDecimal("115.30"), Api.balance(q), "balance");
        List<Journal.Tx> wins = Journal.records(q, "WIN");
        assertEquals(1, wins.size(), "WIN records");
        assertAmount(new BigDecimal("25.30"), wins.get(0).amount(), "WIN amount");
        JsonNode e = Events.awaitOne(Config.GAME_EVENTS, q, "SESSION_FINISHED", EVENT_TIMEOUT).value();
        assertFields(e, "eventId", "type", "sessionId", "playerId", "game", "bet", "win", "finishedAt");
        assertEquals(sid, e.path("sessionId").asLong(), "event sessionId");
        assertEquals("blackjack", e.path("game").asText(), "event game");
        assertAmount("10", e.get("bet"), "event bet");
        assertAmount("25.30", e.get("win"), "event win");
        Instant finishedAt = Json.instant(Api.session(sid).get("finishedAt"));
        Instant eventFinishedAt = Json.instant(e.get("finishedAt"));
        assertTrue(Duration.between(finishedAt, eventFinishedAt).abs().compareTo(Duration.ofSeconds(1)) < 0,
                () -> "event finishedAt " + eventFinishedAt + " must be the session's finishedAt " + finishedAt);
    }

    @Test
    @Tag("SES-07")
    @DisplayName("CL-064 negative win -> 400 VALIDATION_ERROR with win in details; session and balance unchanged")
    void negativeWin() {
        long p = Api.fundedPlayer("100");
        long sid = Api.startOk(p, "10").path("id").asLong();
        Snapshot before = Snapshot.afterEvents(p, 2, 1); // REGISTERED, DEPOSIT; SESSION_STARTED
        JsonNode sessionBefore = Api.session(sid);
        JsonNode err = assertError(Api.finish(sid, "-5"), 400, "VALIDATION_ERROR");
        assertDetailsMention(err, "win");
        before.assertUnchanged();
        JsonNode s = Api.session(sid);
        assertEquals("ACTIVE", s.path("status").asText(), "session status");
        assertAmount("0", s.get("winAmount"), "winAmount");
        assertTrue(s.path("finishedAt").isNull(), () -> "finishedAt must stay null: " + s);
        assertTrue(Json.semanticEquals(sessionBefore, s),
                () -> "the session must not change\nbefore: " + sessionBefore + "\nafter:  " + s);
    }

    @Test
    @Tag("SES-08")
    @DisplayName("CL-065 win 0 is a loss: FINISHED, winAmount 0, no WIN record, SESSION_FINISHED with win 0")
    void loss() {
        long p = Api.fundedPlayer("100");
        long sid = Api.startOk(p, "10").path("id").asLong();
        Response r = Api.finish(sid, "0");
        assertStatus(r, 200);
        JsonNode s = Api.body(r);
        assertEquals("FINISHED", s.path("status").asText(), "status");
        assertAmount("0", s.get("winAmount"), "winAmount");
        assertAmount(new BigDecimal("90"), Api.balance(p), "balance");
        assertEquals(0, Journal.records(p, "WIN").size(), "no WIN record for a loss");
        JsonNode e = Events.awaitOne(Config.GAME_EVENTS, p, "SESSION_FINISHED", EVENT_TIMEOUT).value();
        assertAmount("0", e.get("win"), "event win");
    }

    @Test
    @Tag("SES-09")
    @DisplayName("CL-066 finishing a FINISHED session -> 409 SESSION_NOT_ACTIVE; the win is not credited twice")
    void finishTwice() {
        long p = Api.fundedPlayer("100");
        long sid = Api.startOk(p, "10").path("id").asLong();
        JsonNode finished = Api.finishOk(sid, "30");
        Snapshot before = Snapshot.afterEvents(p, 2, 2); // REGISTERED, DEPOSIT; SESSION_STARTED, SESSION_FINISHED
        assertError(Api.finish(sid, "30"), 409, "SESSION_NOT_ACTIVE");
        assertError(Api.finish(sid, "0"), 409, "SESSION_NOT_ACTIVE");
        before.assertUnchanged();
        JsonNode s = Api.session(sid);
        assertEquals("FINISHED", s.path("status").asText(), "status");
        assertAmount("30", s.get("winAmount"), "winAmount");
        assertEquals(finished.path("finishedAt").asText(), s.path("finishedAt").asText(), "finishedAt unchanged");
    }

    @Test
    @Tag("SES-13")
    @DisplayName("CL-067 GET /api/sessions/{id} returns 200 with the current session state")
    void readSession() {
        long p = Api.fundedPlayer("100");
        JsonNode started = Api.startOk(p, "7");
        long sid = started.path("id").asLong();
        Response r = Api.getSession(sid);
        assertStatus(r, 200);
        JsonNode s = Api.body(r);
        assertFields(s, "id", "playerId", "game", "betAmount", "winAmount", "status", "startedAt", "finishedAt");
        assertAll("session before the finish " + s,
                () -> assertEquals(sid, s.path("id").asLong(), "id"),
                () -> assertEquals(p, s.path("playerId").asLong(), "playerId"),
                () -> assertEquals("slots", s.path("game").asText(), "game"),
                () -> assertAmount("7", s.get("betAmount"), "betAmount"),
                () -> assertAmount("0", s.get("winAmount"), "winAmount"),
                () -> assertEquals("ACTIVE", s.path("status").asText(), "status"),
                () -> assertEquals(Json.instant(started.get("startedAt")), Json.instant(s.get("startedAt")), "startedAt"),
                () -> assertTrue(s.path("finishedAt").isNull(), "finishedAt must be null"));
        JsonNode finished = Api.finishOk(sid, "3");
        JsonNode f = Api.session(sid);
        assertAll("session after the finish " + f,
                () -> assertEquals(sid, f.path("id").asLong(), "id"),
                () -> assertEquals(p, f.path("playerId").asLong(), "playerId"),
                () -> assertEquals("slots", f.path("game").asText(), "game"),
                () -> assertAmount("7", f.get("betAmount"), "betAmount"),
                () -> assertAmount("3", f.get("winAmount"), "winAmount"),
                () -> assertEquals("FINISHED", f.path("status").asText(), "status"),
                () -> assertEquals(Json.instant(started.get("startedAt")), Json.instant(f.get("startedAt")), "startedAt"),
                () -> assertEquals(Json.instant(finished.get("finishedAt")), Json.instant(f.get("finishedAt")),
                        "finishedAt of the finish response"));
    }

    @Test
    @Tag("SES-01")
    @Tag("SES-07")
    @DisplayName("CL-068 startedAt and finishedAt are service-clock times")
    void timesOnServiceClock() {
        TestClock.ensureOffsetAtLeast(Duration.ofHours(1));
        long p = Api.fundedPlayer("100");
        Instant before = TestClock.now();
        JsonNode s = Api.startOk(p, "5");
        Instant after = TestClock.now();
        assertWithin(Instant.parse(s.path("startedAt").asText()), before, after, "startedAt");
        before = TestClock.now();
        JsonNode f = Api.finishOk(s.path("id").asLong(), "1");
        after = TestClock.now();
        assertWithin(Instant.parse(f.path("finishedAt").asText()), before, after, "finishedAt");
    }
}
