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
import java.util.regex.Pattern;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertNotReplayed;
import static com.arcade.qa.support.Asserts.assertReplayOf;
import static com.arcade.qa.support.Asserts.assertStatus;
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
        assertStatus(Api.deposit(a, key, "100"), 200);
        Response rb = Api.deposit(b, key, "100");
        assertStatus(rb, 200);
        assertNotReplayed(rb);
        assertEquals(b, Api.body(rb).path("id").asLong(), "the response must be player B, not player A");
        assertAmount(new BigDecimal("100"), Api.balance(a), "balance A");
        assertAmount(new BigDecimal("100"), Api.balance(b), "balance B");

        String withdrawalKey = Api.key();
        assertStatus(Api.withdraw(a, withdrawalKey, "10"), 200);
        Response wb = Api.withdraw(b, withdrawalKey, "10");
        assertStatus(wb, 200);
        assertNotReplayed(wb);
        assertEquals(b, Api.body(wb).path("id").asLong(), "the response must be player B, not player A");
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
        Snapshot before = Snapshot.of(p);
        for (String amount : List.of("0", "-100", "-0.01")) {
            assertError(Api.deposit(p, amount), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");
            assertError(Api.withdraw(p, amount), 422, "WITHDRAWAL_AMOUNT_OUT_OF_RANGE");
            assertError(Api.startSession(p, amount), 422, "BET_AMOUNT_OUT_OF_RANGE");
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
        Snapshot before = Snapshot.of(p);
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
    @Tag("GEN-03")
    @Tag("GEN-07")
    @DisplayName("CL-136 a non-integer playerId (N.5) is refused, never truncated to player N")
    void nonIntegerPlayerId() {
        long p = Api.fundedPlayer("100");
        Snapshot before = Snapshot.of(p);
        Response r = Api.startSessionRaw(Api.key(), "{\"playerId\": " + p + ".5, \"game\": \"slots\", \"bet\": 5}");
        // No player has the id N.5: VALIDATION_ERROR (not an id) or NOT_FOUND (GEN-07) are both acceptable.
        assertTrue(r.statusCode() == 400 || r.statusCode() == 404,
                () -> "playerId " + p + ".5 must be refused, got " + r.statusCode() + ": " + r.asString());
        assertError(r, r.statusCode(), r.statusCode() == 400 ? "VALIDATION_ERROR" : "NOT_FOUND");
        before.assertUnchanged();
    }

    private static final Pattern LEAK = Pattern.compile(
            "(?i)(exception|stack ?trace|at [a-z]+\\.[a-z]+\\.|org\\.springframework|java\\.|hibernate|sql|jdbc)");

    @Test
    @Tag("GEN-04")
    @DisplayName("CL-135 error responses (also for malformed JSON) do not disclose internals")
    void noInternalsDisclosed() {
        long p = Api.fundedPlayer("100");
        List<Response> errors = List.of(
                Api.depositRaw(p, Api.key(), "{\"amount\": "),
                Api.depositRaw(p, Api.key(), "not json"),
                Api.deposit(p, "\"ten\""),
                Api.getSession("abc"),
                Api.getPlayer("abc"),
                Api.getPlayer(Config.UNKNOWN_ID));
        for (Response r : errors) {
            // Status codes are verified by CL-107/CL-108; this check is only about what the body discloses.
            assertTrue(r.statusCode() >= 400,
                    () -> "an error was expected, got " + r.statusCode() + ": " + Json.abbreviate(r.asString()));
            String text = r.asString();
            JsonNode b = Json.parse(text);
            assertFalse(b.has("trace") || b.has("exception") || b.has("stackTrace"),
                    () -> "internal fields in the error body: " + text);
            assertFalse(LEAK.matcher(b.path("message").asText("") + " " + b.path("details")).find(),
                    () -> "internal details in the error message: " + text);
        }
    }
}
