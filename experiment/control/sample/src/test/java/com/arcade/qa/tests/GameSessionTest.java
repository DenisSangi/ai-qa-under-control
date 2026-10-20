package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Config;
import com.arcade.qa.support.Events;
import com.arcade.qa.support.Journal;
import com.arcade.qa.support.Snapshot;
import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertDetailsMention;
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertNotReplayed;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Order(5)
@DisplayName("F-04 Game session: bet and win")
class GameSessionTest extends ApiTest {

    static void assertFields(JsonNode node, String... fields) {
        for (String f : fields) {
            assertTrue(node.has(f), () -> "field '" + f + "' missing in " + node);
        }
    }

    @ParameterizedTest(name = "game \"{0}\"")
    @ValueSource(strings = {"Slots", "SLOTS", "chess", ""})
    @Tag("SES-02")
    @DisplayName("CL-055 unknown or wrongly cased game -> 400 VALIDATION_ERROR with game in details")
    void invalidGame(String game) {
        long p = Api.fundedPlayer("100");
        Response r = Api.startSession(Api.key(), p, game, "5");
        assumeTrue(r.statusCode() == 400, () -> "game validation answered " + r.statusCode() + ": " + r.asString());
        JsonNode err = assertError(r, 400, "VALIDATION_ERROR");
        assertDetailsMention(err, "game");
        assertAmount(new BigDecimal("100"), Api.balance(p), "balance");
    }

    @Test
    @Tag("SES-05")
    @DisplayName("CL-059 bet above balance: 422 INSUFFICIENT_FUNDS, nothing changes, key free")
    void insufficientFunds() {
        long p = Api.fundedPlayer("20");
        String key = Api.key();
        Snapshot before = Snapshot.of(p);
        assertStatus(Api.startSession(key, p, "slots", "20.01"), 422);
        before.assertUnchanged();
        Response retry = Api.startSession(key, p, "slots", "20");
        assertStatus(retry, 201);
        assertNotReplayed(retry);
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
    @Tag("SES-07")
    @Tag("GEN-21")
    @DisplayName("CL-063 finish with a win: 200 FINISHED, balance + win, one WIN record, SESSION_FINISHED with all fields")
    void finishWithWin() {
        long q = Api.fundedPlayer("100");
        Response st = Api.startSession(Api.key(), q, "blackjack", "10");
        assertStatus(st, 201);
        long sid = Api.body(st).path("id").asLong();
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
    }
}
