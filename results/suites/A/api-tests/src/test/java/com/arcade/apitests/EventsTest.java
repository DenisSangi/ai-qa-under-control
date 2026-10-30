package com.arcade.apitests;

import com.arcade.apitests.support.Api.PlayerRef;
import com.arcade.apitests.support.EventCapture;
import com.arcade.apitests.support.EventCapture.Event;
import com.arcade.apitests.support.Json;
import com.arcade.apitests.support.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.arcade.apitests.support.Api.deposit;
import static com.arcade.apitests.support.Api.expect;
import static com.arcade.apitests.support.Api.expectError;
import static com.arcade.apitests.support.Api.finishSession;
import static com.arcade.apitests.support.Api.finishSessionOk;
import static com.arcade.apitests.support.Api.newKey;
import static com.arcade.apitests.support.Api.newPlayer;
import static com.arcade.apitests.support.Api.setLimit;
import static com.arcade.apitests.support.Api.startSession;
import static com.arcade.apitests.support.Api.startSessionOk;
import static com.arcade.apitests.support.Api.withdraw;
import static com.arcade.apitests.support.EventCapture.ofPlayer;
import static org.assertj.core.api.Assertions.assertThat;

/** Kafka contract: player-events and game-events, keyed by playerId, published only for operations that took effect. */
@DisplayName("Kafka events")
class EventsTest extends ApiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test
    @DisplayName("EV-01 REGISTERED, DEPOSIT and WITHDRAWAL are published to player-events in order, keyed by playerId")
    void playerEvents() {
        try (EventCapture capture = EventCapture.open(EventCapture.PLAYER_EVENTS)) {
            Instant before = TestClock.now();
            PlayerRef player = newPlayer();
            expect(deposit(player.id(), "100.50", newKey()), 200);
            expect(withdraw(player.id(), "20.25", newKey()), 200);
            Instant after = TestClock.now();

            List<Event> events = capture.await(ofPlayer(player.id()), 3, WAIT);

            assertThat(events).extracting(Event::type).containsExactly("REGISTERED", "DEPOSIT", "WITHDRAWAL");
            assertThat(events).extracting(Event::key).containsOnly(String.valueOf(player.id()));
            assertThat(events).extracting(Event::partition).containsOnly(events.get(0).partition());
            assertThat(events).extracting(e -> e.value().path("playerId").asLong()).containsOnly(player.id());
            assertThat(events).extracting(e -> Json.text(e.value(), "eventId")).doesNotHaveDuplicates()
                    .allSatisfy(id -> assertThat(UUID.fromString(id)).isNotNull());
            assertThat(events).allSatisfy(e -> assertThat(Json.instant(e.value(), "at")).isBetween(before, after));
            assertThat(events.get(0).value().get("amount").isNull()).isTrue();
            assertThat(Json.dec(events.get(1).value(), "amount")).isEqualByComparingTo("100.50");
            assertThat(Json.dec(events.get(2).value(), "amount")).isEqualByComparingTo("20.25");
        }
    }

    @Test
    @DisplayName("EV-02 SESSION_STARTED and SESSION_FINISHED are published to game-events in order with the session's data")
    void gameEvents() {
        PlayerRef player = newPlayer("100.00");
        try (EventCapture capture = EventCapture.open(EventCapture.GAME_EVENTS)) {
            JsonNode started = startSessionOk(player.id(), "12.34");
            long id = started.get("id").asLong();
            JsonNode finished = finishSessionOk(id, "56.78");

            List<Event> events = capture.await(ofPlayer(player.id()), 2, WAIT);

            assertThat(events).extracting(Event::type).containsExactly("SESSION_STARTED", "SESSION_FINISHED");
            assertThat(events).extracting(Event::key).containsOnly(String.valueOf(player.id()));
            assertThat(events.get(1).partition()).isEqualTo(events.get(0).partition());
            assertThat(events.get(1).offset()).isGreaterThan(events.get(0).offset());

            JsonNode start = events.get(0).value();
            assertThat(start.path("sessionId").asLong()).isEqualTo(id);
            assertThat(start.path("playerId").asLong()).isEqualTo(player.id());
            assertThat(Json.text(start, "game")).isEqualTo("slots");
            assertThat(Json.dec(start, "bet")).isEqualByComparingTo("12.34");
            assertThat(Json.instant(start, "startedAt")).isEqualTo(Json.instant(started, "startedAt"));

            JsonNode finish = events.get(1).value();
            assertThat(finish.path("sessionId").asLong()).isEqualTo(id);
            assertThat(Json.dec(finish, "bet")).isEqualByComparingTo("12.34");
            assertThat(Json.dec(finish, "win")).isEqualByComparingTo("56.78");
            assertThat(Json.instant(finish, "finishedAt")).isEqualTo(Json.instant(finished, "finishedAt"));
        }
    }

    @Test
    @DisplayName("EV-03 nothing is published for refused, declined or replayed operations")
    void noEventsForFailures() {
        PlayerRef player = newPlayer();
        String key = newKey();
        expect(deposit(player.id(), "6000.00", key), 200);

        try (EventCapture capture = EventCapture.open(EventCapture.PLAYER_EVENTS, EventCapture.GAME_EVENTS)) {
            expect(deposit(player.id(), "6000.00", key), 200);                                 // replay
            expectError(deposit(player.id(), "5.00", newKey()), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");
            expectError(deposit(player.id(), "10000.01", newKey()), 422, "PAYMENT_DECLINED");
            expectError(withdraw(player.id(), "7000.00", newKey()), 422, "INSUFFICIENT_FUNDS");
            expectError(withdraw(player.id(), "5000.01", newKey()), 422, "PAYOUT_DECLINED");
            expect(setLimit(player.id(), "daily", "6000.00"), 200);
            expectError(deposit(player.id(), "20.00", newKey()), 422, "DEPOSIT_LIMIT_EXCEEDED");
            expectError(startSession(player.id(), "slots", "600.00"), 422, "BET_AMOUNT_OUT_OF_RANGE");
            long id = startSessionOk(player.id(), "10.00").get("id").asLong();
            expectError(startSession(player.id(), "slots", "10.00"), 409, "ACTIVE_SESSION_EXISTS");
            finishSessionOk(id, "0");
            expectError(finishSession(id, "5.00"), 409, "SESSION_NOT_ACTIVE");

            List<Event> events = capture.during(ofPlayer(player.id()), Duration.ofSeconds(3));

            assertThat(events).extracting(e -> e.topic() + "/" + e.type())
                    .containsExactly("game-events/SESSION_STARTED", "game-events/SESSION_FINISHED");
        }
    }
}
