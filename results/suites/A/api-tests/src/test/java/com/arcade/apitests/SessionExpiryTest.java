package com.arcade.apitests;

import com.arcade.apitests.support.Api.PlayerRef;
import com.arcade.apitests.support.Db;
import com.arcade.apitests.support.EventCapture;
import com.arcade.apitests.support.Json;
import com.arcade.apitests.support.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.arcade.apitests.support.Api.balance;
import static com.arcade.apitests.support.Api.expect;
import static com.arcade.apitests.support.Api.expectError;
import static com.arcade.apitests.support.Api.finishSession;
import static com.arcade.apitests.support.Api.finishSessionOk;
import static com.arcade.apitests.support.Api.fmt;
import static com.arcade.apitests.support.Api.getSession;
import static com.arcade.apitests.support.Api.newPlayer;
import static com.arcade.apitests.support.Api.startSessionOk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** A session must be finished before startedAt + 60s; otherwise it expires within about a second and the bet is refunded. */
@DisplayName("Session expiry")
class SessionExpiryTest extends ApiTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Test
    @DisplayName("EXP-01 an overdue session expires, the bet is refunded and SESSION_EXPIRED is published")
    void overdueSessionExpires() {
        PlayerRef player = newPlayer("100.00");
        try (EventCapture events = EventCapture.open(EventCapture.GAME_EVENTS)) {
            JsonNode session = startSessionOk(player.id(), "30.00");
            long id = session.get("id").asLong();
            assertThat(balance(player.id())).isEqualByComparingTo("70.00");

            Instant moved = TestClock.advance(TIMEOUT.plusSeconds(1));

            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertThat(Json.text(expect(getSession(id), 200).json(), "status")).isEqualTo("EXPIRED"));
            assertThat(balance(player.id())).isEqualByComparingTo("100.00");
            List<Db.Tx> refunds = Db.transactions(player.id(), "REFUND");
            assertThat(refunds).extracting(tx -> fmt(tx.amount())).containsExactly("30.00");
            assertThat(refunds.get(0).createdAt()).as("refund time is on the service clock").isAfterOrEqualTo(moved.minusSeconds(1));

            List<EventCapture.Event> expired = events.await(
                    e -> "SESSION_EXPIRED".equals(e.type()) && e.value().path("sessionId").asLong() == id, 1, Duration.ofSeconds(10));
            assertThat(expired).hasSize(1);
            JsonNode event = expired.get(0).value();
            assertThat(expired.get(0).key()).isEqualTo(String.valueOf(player.id()));
            assertThat(event.path("playerId").asLong()).isEqualTo(player.id());
            assertThat(Json.text(event, "game")).isEqualTo("slots");
            assertThat(Json.dec(event, "bet")).isEqualByComparingTo("30.00");
            assertThat(Json.instant(event, "expiredAt")).isAfterOrEqualTo(Json.instant(session, "startedAt").plus(TIMEOUT));

            expectError(finishSession(id, "100.00"), 409, "SESSION_NOT_ACTIVE");
            assertThat(balance(player.id())).isEqualByComparingTo("100.00");
            assertThat(Db.transactions(player.id(), "WIN")).isEmpty();
        }
    }

    @Test
    @DisplayName("EXP-02 a session can be finished at 59s but not at 60s")
    void finishDeadline() {
        PlayerRef player = newPlayer("100.00");

        long inTime = startSessionOk(player.id(), "10.00").get("id").asLong();
        TestClock.advance(TIMEOUT.minusSeconds(1));
        finishSessionOk(inTime, "15.00");
        assertThat(balance(player.id())).isEqualByComparingTo("105.00");

        long late = startSessionOk(player.id(), "10.00").get("id").asLong();
        TestClock.advance(TIMEOUT);
        expectError(finishSession(late, "50.00"), 409, "SESSION_NOT_ACTIVE");

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(Db.sessionStatus(late)).isEqualTo("EXPIRED"));
        assertThat(balance(player.id())).as("late win not credited, bet refunded").isEqualByComparingTo("105.00");
        assertThat(Db.transactions(player.id(), "WIN")).extracting(tx -> fmt(tx.amount())).containsExactly("15.00");
    }

    @Test
    @DisplayName("EXP-03 after an expiry the player can start a new session")
    void newSessionAfterExpiry() {
        PlayerRef player = newPlayer("50.00");
        long id = startSessionOk(player.id(), "10.00").get("id").asLong();
        TestClock.advance(TIMEOUT.plusSeconds(1));
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(Db.sessionStatus(id)).isEqualTo("EXPIRED"));

        JsonNode next = startSessionOk(player.id(), "50.00");

        assertThat(Json.text(next, "status")).isEqualTo("ACTIVE");
        assertThat(Db.activeSessions(player.id())).isEqualTo(1);
    }
}
