package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Config;
import com.arcade.qa.support.Events;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.arcade.qa.support.Asserts.assertError;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Order(10)
@DisplayName("F-10 Events")
class EventsTest extends ApiTest {

    private static void assertRequiredPlayerEventFields(JsonNode e) {
        GameSessionTest.assertFields(e, "eventId", "type", "playerId", "amount", "at");
        assertTrue(e.path("eventId").isTextual() && !e.path("eventId").asText().isBlank(), () -> "eventId: " + e);
    }

    @Test
    @Tag("GEN-21")
    @DisplayName("CL-118 REGISTERED in player-events with eventId, type, playerId, amount null, at")
    void registeredEvent() {
        long p = Api.createPlayer();
        List<Events.Event> registered = Events.ofType(Config.PLAYER_EVENTS, p, "REGISTERED");
        assertEquals(1, registered.size(), () -> "REGISTERED events of player " + p + ": " + registered);
        JsonNode e = registered.get(0).value();
        assertRequiredPlayerEventFields(e);
        assertEquals(p, e.path("playerId").asLong(), "playerId");
        assertTrue(e.path("amount").isNull(), () -> "amount must be null for REGISTERED: " + e);
        Instant.parse(e.path("at").asText());
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
}
