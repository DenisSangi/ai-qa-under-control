package com.arcade.apitests;

import com.arcade.apitests.support.Api.PlayerRef;
import com.arcade.apitests.support.Db;
import com.arcade.apitests.support.Http;
import com.arcade.apitests.support.Json;
import com.arcade.apitests.support.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static com.arcade.apitests.support.Api.balance;
import static com.arcade.apitests.support.Api.expect;
import static com.arcade.apitests.support.Api.expectError;
import static com.arcade.apitests.support.Api.finishSession;
import static com.arcade.apitests.support.Api.finishSessionOk;
import static com.arcade.apitests.support.Api.fmt;
import static com.arcade.apitests.support.Api.getSession;
import static com.arcade.apitests.support.Api.newPlayer;
import static com.arcade.apitests.support.Api.startSession;
import static com.arcade.apitests.support.Api.startSessionOk;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Game sessions")
class GameSessionTest extends ApiTest {

    private static final long UNKNOWN = 9_000_000_000_000L;

    @Test
    @DisplayName("SES-01 starting a session takes the bet and writes a BET ledger row")
    void startTakesBet() {
        PlayerRef player = newPlayer("100.00");
        Instant before = TestClock.now();

        JsonNode session = expect(startSession(player.id(), "roulette", "25.50"), 201).json();
        Instant after = TestClock.now();

        assertThat(session.get("id").asLong()).isPositive();
        assertThat(session.get("playerId").asLong()).isEqualTo(player.id());
        assertThat(Json.text(session, "game")).isEqualTo("roulette");
        assertThat(Json.dec(session, "betAmount")).isEqualByComparingTo("25.50");
        assertThat(Json.dec(session, "winAmount")).isZero();
        assertThat(Json.text(session, "status")).isEqualTo("ACTIVE");
        assertThat(Json.instant(session, "startedAt")).isBetween(before, after);
        assertThat(session.get("finishedAt").isNull()).isTrue();

        assertThat(balance(player.id())).isEqualByComparingTo("74.50");
        assertThat(Db.transactions(player.id(), "BET")).extracting(tx -> fmt(tx.amount())).containsExactly("25.50");
        assertThat(Json.text(expect(getSession(session.get("id").asLong()), 200).json(), "status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("SES-02 finishing credits the win, writes a WIN ledger row and sets finishedAt")
    void finishCreditsWin() {
        PlayerRef player = newPlayer("100.00");
        JsonNode started = startSessionOk(player.id(), "10.00");
        long id = started.get("id").asLong();

        JsonNode finished = finishSessionOk(id, "40.25");

        assertThat(Json.text(finished, "status")).isEqualTo("FINISHED");
        assertThat(Json.dec(finished, "winAmount")).isEqualByComparingTo("40.25");
        assertThat(Json.dec(finished, "betAmount")).isEqualByComparingTo("10.00");
        assertThat(Json.instant(finished, "finishedAt")).isAfterOrEqualTo(Json.instant(started, "startedAt"));
        assertThat(balance(player.id())).isEqualByComparingTo("130.25");
        assertThat(Db.transactions(player.id(), "WIN")).extracting(tx -> fmt(tx.amount())).containsExactly("40.25");

        JsonNode fetched = expect(getSession(id), 200).json();
        assertThat(Json.text(fetched, "status")).isEqualTo("FINISHED");
        assertThat(Json.dec(fetched, "winAmount")).isEqualByComparingTo("40.25");
    }

    @Test
    @DisplayName("SES-02 a zero win writes no WIN ledger row")
    void zeroWin() {
        PlayerRef player = newPlayer("50.00");
        long id = startSessionOk(player.id(), "20.00").get("id").asLong();

        finishSessionOk(id, "0");

        assertThat(balance(player.id())).isEqualByComparingTo("30.00");
        assertThat(Db.transactions(player.id(), "WIN")).isEmpty();
    }

    @Test
    @DisplayName("SES-03 a player can have only one active session")
    void oneActiveSession() {
        PlayerRef player = newPlayer("100.00");
        startSessionOk(player.id(), "10.00");

        expectError(startSession(player.id(), "poker", "10.00"), 409, "ACTIVE_SESSION_EXISTS");

        assertThat(balance(player.id())).isEqualByComparingTo("90.00");
        assertThat(Db.transactions(player.id(), "BET")).hasSize(1);
    }

    @ParameterizedTest(name = "bet {0} is out of range")
    @ValueSource(strings = {"0.99", "0", "-1.00", "500.01"})
    @DisplayName("SES-04 a bet outside 1.00-500.00 is refused")
    void betRange(String bet) {
        PlayerRef player = newPlayer("1000.00");

        JsonNode error = expectError(startSession(player.id(), "slots", bet), 422, "BET_AMOUNT_OUT_OF_RANGE");

        assertThat(error.path("details").path("min").asText()).isEqualTo("1.00");
        assertThat(error.path("details").path("max").asText()).isEqualTo("500.00");
        assertThat(balance(player.id())).isEqualByComparingTo("1000.00");
    }

    @ParameterizedTest(name = "bet {0} is accepted")
    @ValueSource(strings = {"1.00", "500.00"})
    @DisplayName("SES-04 the bet bounds are accepted")
    void betBounds(String bet) {
        PlayerRef player = newPlayer("500.00");
        startSessionOk(player.id(), bet);
        assertThat(balance(player.id())).isEqualByComparingTo(new java.math.BigDecimal("500.00").subtract(new java.math.BigDecimal(bet)));
    }

    @Test
    @DisplayName("SES-04 a bet above the balance is INSUFFICIENT_FUNDS; the whole balance can be bet")
    void betAboveBalance() {
        PlayerRef player = newPlayer("20.00");

        expectError(startSession(player.id(), "slots", "20.01"), 422, "INSUFFICIENT_FUNDS");
        startSessionOk(player.id(), "20.00");
        assertThat(balance(player.id())).isZero();
    }

    @ParameterizedTest(name = "game {0}")
    @ValueSource(strings = {"slots", "roulette", "blackjack", "poker", "crash"})
    @DisplayName("SES-04 every supported game can be played")
    void supportedGames(String game) {
        PlayerRef player = newPlayer("20.00");
        JsonNode session = expect(startSession(player.id(), game, "1.50"), 201).json();
        assertThat(Json.text(session, "game")).isEqualTo(game);
        finishSessionOk(session.get("id").asLong(), "0");
        assertThat(balance(player.id())).isEqualByComparingTo("18.50");
    }

    @ParameterizedTest(name = "game {0} is refused")
    @ValueSource(strings = {"bingo", "Slots", "", "slots "})
    @DisplayName("SES-04 an unknown game is a validation error")
    void unknownGame(String game) {
        PlayerRef player = newPlayer("20.00");
        JsonNode error = expectError(startSession(player.id(), game, "5.00"), 400, "VALIDATION_ERROR");
        assertThat(error.path("details").has("game")).isTrue();
    }

    @Test
    @DisplayName("SES-04 malformed start requests are validation errors; an unknown player is 404")
    void startValidation() {
        PlayerRef player = newPlayer("20.00");
        assertThat(expectError(startSession(player.id(), "slots", "5.001"), 400, "VALIDATION_ERROR")
                .path("details").has("bet")).isTrue();
        assertThat(expectError(Http.post("/api/sessions", "{\"game\":\"slots\",\"bet\":5}"), 400, "VALIDATION_ERROR")
                .path("details").has("playerId")).isTrue();
        assertThat(expectError(startSession(player.id(), "slots", "\"5\""), 400, "VALIDATION_ERROR")
                .path("details").has("bet")).isTrue();
        expectError(startSession(UNKNOWN, "slots", "5.00"), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("SES-05 check order: player (404) -> bet range (422) -> active session (409) -> balance (422)")
    void startCheckOrder() {
        expectError(startSession(UNKNOWN, "slots", "9999"), 404, "NOT_FOUND");

        PlayerRef player = newPlayer("30.00");
        startSessionOk(player.id(), "10.00");
        expectError(startSession(player.id(), "slots", "600.00"), 422, "BET_AMOUNT_OUT_OF_RANGE");
        expectError(startSession(player.id(), "slots", "100.00"), 409, "ACTIVE_SESSION_EXISTS");
    }

    @Test
    @DisplayName("SES-06 a session can be finished only once; the win is credited once")
    void finishOnce() {
        PlayerRef player = newPlayer("50.00");
        long id = startSessionOk(player.id(), "10.00").get("id").asLong();
        finishSessionOk(id, "30.00");

        expectError(finishSession(id, "30.00"), 409, "SESSION_NOT_ACTIVE");

        assertThat(balance(player.id())).isEqualByComparingTo("70.00");
        assertThat(Db.transactions(player.id(), "WIN")).hasSize(1);
    }

    @Test
    @DisplayName("SES-06 invalid finish requests are refused and leave the session active")
    void finishValidation() {
        PlayerRef player = newPlayer("50.00");
        long id = startSessionOk(player.id(), "10.00").get("id").asLong();

        assertThat(expectError(finishSession(id, "-0.01"), 400, "VALIDATION_ERROR").path("details").has("win")).isTrue();
        assertThat(expectError(finishSession(id, "1.001"), 400, "VALIDATION_ERROR").path("details").has("win")).isTrue();
        assertThat(expectError(finishSession(id, "null"), 400, "VALIDATION_ERROR").path("details").has("win")).isTrue();
        assertThat(expectError(finishSession(id, "12345678901.00"), 400, "VALIDATION_ERROR").path("details").has("win"))
                .as("win has at most 10 integer digits").isTrue();
        expectError(finishSession(UNKNOWN, "1.00"), 404, "NOT_FOUND");
        expectError(getSession(UNKNOWN), 404, "NOT_FOUND");

        assertThat(Json.text(expect(getSession(id), 200).json(), "status")).isEqualTo("ACTIVE");
        assertThat(balance(player.id())).isEqualByComparingTo("40.00");
    }
}
