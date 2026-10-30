package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Config;
import com.arcade.qa.support.Events;
import com.arcade.qa.support.Json;
import com.arcade.qa.support.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Order(10)
@DisplayName("F-10 Events")
class EventsTest extends ApiTest {

    /** GEN-21 lists the required fields; it fixes no JSON type for eventId and no representation for at. */
    private static void assertRequiredPlayerEventFields(JsonNode e) {
        GameSessionTest.assertFields(e, "eventId", "type", "playerId", "amount", "at");
        assertFalse(e.path("eventId").isNull(), () -> "eventId: " + e);
    }

    @Test
    @Tag("GEN-21")
    @DisplayName("CL-118 REGISTERED in player-events with eventId, type, playerId, amount null, at")
    void registeredEvent() {
        TestClock.ensureOffsetAtLeast(Duration.ofHours(1)); // the service-clock time differs from real time
        Instant before = TestClock.now();
        long p = Api.createPlayer();
        Instant after = TestClock.now();
        JsonNode e = Events.awaitOne(Config.PLAYER_EVENTS, p, "REGISTERED", EVENT_TIMEOUT).value();
        assertRequiredPlayerEventFields(e);
        assertEquals(p, e.path("playerId").asLong(), "playerId");
        assertTrue(e.path("amount").isNull(), () -> "amount must be null for REGISTERED: " + e);
        GameSessionTest.assertWithin(Json.instant(e.get("at")), before, after, "'at' of REGISTERED");
    }

    @Test
    @Tag("GEN-21")
    @DisplayName("CL-119 DEPOSIT and WITHDRAWAL carry all fields, the operation amount and the service-clock time")
    void depositAndWithdrawalEvents() {
        TestClock.ensureOffsetAtLeast(Duration.ofHours(1));
        long p = Api.createPlayer();
        Instant before = TestClock.now();
        Api.depositOk(p, "80");
        assertStatus(Api.withdraw(p, "30"), 200);
        Instant after = TestClock.now();
        JsonNode dep = Events.awaitOne(Config.PLAYER_EVENTS, p, "DEPOSIT", EVENT_TIMEOUT).value();
        JsonNode wdr = Events.awaitOne(Config.PLAYER_EVENTS, p, "WITHDRAWAL", EVENT_TIMEOUT).value();
        for (JsonNode e : List.of(dep, wdr)) {
            assertRequiredPlayerEventFields(e);
            assertEquals(p, e.path("playerId").asLong(), "playerId");
            GameSessionTest.assertWithin(Json.instant(e.get("at")), before, after, "'at' of " + e.path("type"));
        }
        assertAmount("80", dep.get("amount"), "DEPOSIT amount");
        assertAmount("30", wdr.get("amount"), "WITHDRAWAL amount");
    }

    @Test
    @Tag("GEN-22")
    @DisplayName("CL-120 refused and failed requests publish no event")
    void noEventsForRefusals() {
        long p = Api.createPlayer();
        Events.awaitOne(Config.PLAYER_EVENTS, p, "REGISTERED", EVENT_TIMEOUT);
        assertError(Api.deposit(p, "9.99"), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");
        assertError(Api.deposit(p, "10000.01"), 422, "PAYMENT_DECLINED");
        assertError(Api.deposit(p, null, "100"), 400, "IDEMPOTENCY_KEY_REQUIRED");
        assertError(Api.withdraw(p, "10"), 422, "INSUFFICIENT_FUNDS");
        assertError(Api.startSession(p, "5"), 422, "INSUFFICIENT_FUNDS");
        assertError(Api.startSession(p, "0.5"), 422, "BET_AMOUNT_OUT_OF_RANGE");
        Api.putLimitOk(p, "daily", "10");
        assertError(Api.deposit(p, "20"), 422, "DEPOSIT_LIMIT_EXCEEDED");
        Events.sleep(1500);
        List<Events.Event> pe = Events.of(Config.PLAYER_EVENTS, p);
        List<Events.Event> ge = Events.of(Config.GAME_EVENTS, p);
        assertAll(
                () -> assertEquals(1, pe.size(), () -> "only REGISTERED expected: " + pe.stream().map(Events.Event::raw).toList()),
                () -> assertTrue(ge.isEmpty(), () -> "no game events expected: " + ge.stream().map(Events.Event::raw).toList()));
    }

    @Test
    @Tag("GEN-23")
    @DisplayName("CL-121 amount, bet and win in events are JSON numbers equal by value to the operation amounts")
    void amountsInEvents() {
        long p = Api.createPlayer();
        Api.depositOk(p, "100.50");
        long sid = Api.startOk(p, "2.5").path("id").asLong();
        Api.finishOk(sid, "0.10");
        JsonNode dep = Events.awaitOne(Config.PLAYER_EVENTS, p, "DEPOSIT", EVENT_TIMEOUT).value();
        JsonNode st = Events.awaitOne(Config.GAME_EVENTS, p, "SESSION_STARTED", EVENT_TIMEOUT).value();
        JsonNode fin = Events.awaitOne(Config.GAME_EVENTS, p, "SESSION_FINISHED", EVENT_TIMEOUT).value();
        assertAll(
                () -> assertTrue(dep.path("amount").isNumber(), () -> "amount must be a JSON number: " + dep),
                () -> assertTrue(st.path("bet").isNumber(), () -> "bet must be a JSON number: " + st),
                () -> assertTrue(fin.path("bet").isNumber() && fin.path("win").isNumber(),
                        () -> "bet/win must be JSON numbers: " + fin),
                () -> assertAmount("100.5", dep.get("amount"), "DEPOSIT amount"),
                () -> assertAmount("2.50", st.get("bet"), "SESSION_STARTED bet"),
                () -> assertAmount("2.5", fin.get("bet"), "SESSION_FINISHED bet"),
                () -> assertAmount("0.1", fin.get("win"), "SESSION_FINISHED win"));
    }

    @Test
    @Tag("GEN-24")
    @DisplayName("CL-122 event key is the player id; events of one player arrive in operation order per topic")
    void keyAndOrder() {
        long p = Api.createPlayer();
        Api.depositOk(p, "100");
        assertStatus(Api.withdraw(p, "20"), 200);
        Api.depositOk(p, "30");
        long s1 = Api.startOk(p, "5").path("id").asLong();
        Api.finishOk(s1, "1");
        long s2 = Api.startOk(p, "6").path("id").asLong();
        Api.finishOk(s2, "0");
        List<Events.Event> pe = Events.await(Config.PLAYER_EVENTS, p, e -> true, 4, EVENT_TIMEOUT);
        List<Events.Event> ge = Events.await(Config.GAME_EVENTS, p, e -> true, 4, EVENT_TIMEOUT);
        for (Events.Event e : pe) {
            assertEquals(Long.toString(p), e.key(), () -> "key of " + e.raw());
        }
        for (Events.Event e : ge) {
            assertEquals(Long.toString(p), e.key(), () -> "key of " + e.raw());
        }
        List<String> playerOrder = pe.stream().map(e -> e.type() + ":" + e.value().path("amount").decimalValue()
                .stripTrailingZeros().toPlainString()).toList();
        assertEquals(List.of("REGISTERED:0", "DEPOSIT:100", "WITHDRAWAL:20", "DEPOSIT:30"), playerOrder,
                "player-events order");
        List<String> gameOrder = ge.stream().map(e -> e.type() + ":" + e.value().path("sessionId").asLong()).toList();
        assertEquals(List.of("SESSION_STARTED:" + s1, "SESSION_FINISHED:" + s1, "SESSION_STARTED:" + s2,
                "SESSION_FINISHED:" + s2), gameOrder, "game-events order");
    }
}
