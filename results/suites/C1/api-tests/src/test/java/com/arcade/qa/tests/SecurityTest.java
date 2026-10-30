package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Config;
import com.arcade.qa.support.Json;
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
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertNotReplayed;
import static com.arcade.qa.support.Asserts.assertReplayOf;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Order(12)
@DisplayName("F-12 Security")
class SecurityTest extends ApiTest {

    private static final String HUGE = "100000000000000000000";

    @Test
    @Tag("GEN-10")
    @DisplayName("CL-131 the same key value of another player is an independent operation, never a replay of it")
    void keyIsolationBetweenPlayers() {
        long a = Api.createPlayer();
        long b = Api.createPlayer();
        String key = Api.key();
        Response ra = Api.deposit(a, key, "100");
        assertStatus(ra, 200);
        Response rb = Api.deposit(b, key, "100");
        assertStatus(rb, 200);
        assertNotReplayed(rb);
        assertEquals(b, Api.body(rb).path("id").asLong(), "the response must be player B, not player A");
        // Two independent keys: each player's repeat is a replay of that player's own deposit.
        assertReplayOf(rb, Api.deposit(b, key, "100"));
        assertReplayOf(ra, Api.deposit(a, key, "100"));
        assertAmount(new BigDecimal("100"), Api.balance(a), "balance A");
        assertAmount(new BigDecimal("100"), Api.balance(b), "balance B");

        String withdrawalKey = Api.key();
        Response wa = Api.withdraw(a, withdrawalKey, "10");
        assertStatus(wa, 200);
        Response wb = Api.withdraw(b, withdrawalKey, "10");
        assertStatus(wb, 200);
        assertNotReplayed(wb);
        assertEquals(b, Api.body(wb).path("id").asLong(), "the response must be player B, not player A");
        assertReplayOf(wb, Api.withdraw(b, withdrawalKey, "10"));
        assertReplayOf(wa, Api.withdraw(a, withdrawalKey, "10"));
        assertAmount(new BigDecimal("90"), Api.balance(a), "balance A");
        assertAmount(new BigDecimal("90"), Api.balance(b), "balance B");

        String sessionKey = Api.key();
        Response sa = Api.startSession(sessionKey, a, "slots", "5");
        assertStatus(sa, 201);
        Response sb = Api.startSession(sessionKey, b, "slots", "5");
        assertStatus(sb, 201);
        assertNotReplayed(sb);
        assertEquals(b, Api.body(sb).path("playerId").asLong(), "session of player B");
        assertFalse(Api.body(sa).path("id").asLong() == Api.body(sb).path("id").asLong(), "two sessions");
        assertAmount(new BigDecimal("85"), Api.balance(b), "balance B");
    }

    @Test
    @Tag("DEP-02")
    @Tag("WDR-02")
    @Tag("SES-03")
    @DisplayName("CL-132 zero and negative amounts are refused with the range error; the balance cannot be manipulated")
    void zeroAndNegativeAmounts() {
        long p = Api.fundedPlayer("100");
        Snapshot before = Snapshot.afterEvents(p, 2, 0); // REGISTERED, DEPOSIT
        // The balance is compared after every refusal: a deposit and a withdrawal of one amount could cancel out.
        for (String amount : List.of("0", "-100", "-0.01")) {
            assertError(Api.deposit(p, amount), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");
            assertAmount(before.balance(), Api.balance(p), "balance after the refused deposit of " + amount);
            assertError(Api.withdraw(p, amount), 422, "WITHDRAWAL_AMOUNT_OUT_OF_RANGE");
            assertAmount(before.balance(), Api.balance(p), "balance after the refused withdrawal of " + amount);
            assertError(Api.startSession(p, amount), 422, "BET_AMOUNT_OUT_OF_RANGE");
            assertAmount(before.balance(), Api.balance(p), "balance after the refused bet of " + amount);
        }
        before.assertUnchanged();
    }

    @Test
    @Tag("DEP-02")
    @Tag("WDR-02")
    @Tag("SES-03")
    @Tag("LIM-03")
    @DisplayName("CL-133 huge amounts are refused with the range error, never 500")
    void hugeAmounts() {
        long p = Api.fundedPlayer("100");
        Snapshot before = Snapshot.afterEvents(p, 2, 0); // REGISTERED, DEPOSIT
        assertError(Api.deposit(p, HUGE), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");
        assertError(Api.withdraw(p, HUGE), 422, "WITHDRAWAL_AMOUNT_OUT_OF_RANGE");
        assertError(Api.startSession(p, HUGE), 422, "BET_AMOUNT_OUT_OF_RANGE");
        assertError(Api.putLimit(p, "daily", HUGE), 422, "DEPOSIT_LIMIT_OUT_OF_RANGE");
        before.assertUnchanged();
    }

    @ParameterizedTest(name = "key {0}")
    @ValueSource(strings = {"' OR '1'='1' --", "<script>alert(1)</script>", "../../etc/passwd", "${jndi:x}"})
    @Tag("GEN-09")
    @DisplayName("CL-134 injection-like printable ASCII keys are treated as plain keys")
    void injectionLikeKeys(String raw) {
        long p = Api.createPlayer();
        String key = raw + " " + Api.key().substring(0, 8);
        Response first = Api.deposit(p, key, "10");
        assertStatus(first, 200);
        assertReplayOf(first, Api.deposit(p, key, "10"));
        assertAmount(new BigDecimal("10"), Api.balance(p), "balance");
    }

    @Test
    @Tag("GEN-07")
    @DisplayName("CL-136 a non-integer playerId (N.5) is refused, never truncated to player N")
    void nonIntegerPlayerId() {
        long p = Api.fundedPlayer("100");
        Snapshot before = Snapshot.afterEvents(p, 2, 0); // REGISTERED, DEPOSIT
        Response r = Api.startSessionRaw(Api.key(), "{\"playerId\": " + p + ".5, \"game\": \"slots\", \"bet\": 5}");
        // No player has the id N.5: VALIDATION_ERROR (not an id) or NOT_FOUND (GEN-07) are both acceptable.
        assertTrue(r.statusCode() == 400 || r.statusCode() == 404,
                () -> "playerId " + p + ".5 must be refused, got " + r.statusCode() + ": " + r.asString());
        assertError(r, r.statusCode(), r.statusCode() == 400 ? "VALIDATION_ERROR" : "NOT_FOUND");
        before.assertUnchanged();
    }

    /** Malformed JSON is a request-format error (400, DEP-05 step 1); the specification names no code for it. */
    private static void assertMalformedJsonError(Response r) {
        String raw = r.asString();
        assertEquals(400, r.statusCode(), () -> "malformed JSON, body: " + Json.abbreviate(raw));
        JsonNode b = Json.parse(raw);
        assertTrue(b.isObject() && b.path("code").isTextual() && !b.path("code").asText().isBlank()
                        && b.path("message").isTextual() && !b.path("message").asText().isBlank(),
                () -> "GEN-04: a JSON object with a code and a message expected: " + raw);
    }

    @Test
    @Tag("GEN-04")
    @DisplayName("CL-135 error responses (also for malformed JSON) are JSON objects with the specified code and a message")
    void noInternalsDisclosed() {
        long p = Api.fundedPlayer("100");
        Response truncated = Api.depositRaw(p, Api.key(), "{\"amount\": ");
        Response notJson = Api.depositRaw(p, Api.key(), "not json");
        Response amountText = Api.deposit(p, "\"ten\"");
        Response sessionAbc = Api.getSession("abc");
        Response playerAbc = Api.getPlayer("abc");
        Response unknown = Api.getPlayer(Config.UNKNOWN_ID);
        // GEN-04 with the code of the governing rule: GEN-03, GEN-06, GEN-07.
        assertAll(
                () -> assertMalformedJsonError(truncated),
                () -> assertMalformedJsonError(notJson),
                () -> assertError(amountText, 400, "VALIDATION_ERROR"),
                () -> assertError(sessionAbc, 400, "BAD_REQUEST"),
                () -> assertError(playerAbc, 400, "BAD_REQUEST"),
                () -> assertError(unknown, 404, "NOT_FOUND"));
    }
}
