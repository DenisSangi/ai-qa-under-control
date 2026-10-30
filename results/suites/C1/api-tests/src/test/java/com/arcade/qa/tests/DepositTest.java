package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Config;
import com.arcade.qa.support.Events;
import com.arcade.qa.support.Journal;
import com.arcade.qa.support.Json;
import com.arcade.qa.support.ProviderStub;
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
import static com.arcade.qa.support.Asserts.assertMinMax;
import static com.arcade.qa.support.Asserts.assertNotReplayed;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Order(3)
@DisplayName("F-01 Deposit")
class DepositTest extends ApiTest {

    @Test
    @Tag("DEP-01")
    @DisplayName("CL-001 approved deposit: 200, balance + amount, one DEPOSIT record, one event, one provider call")
    void approvedDeposit() {
        long p = Api.createPlayer();
        Response r = Api.deposit(p, "125.50");
        assertStatus(r, 200);
        JsonNode body = Api.body(r);
        assertEquals(p, body.path("id").asLong(), "response is the player");
        assertAmount("125.50", body.get("balance"), "balance in the response");
        assertAmount(new BigDecimal("125.50"), Api.balance(p), "balance");
        List<Journal.Tx> deposits = Journal.records(p, "DEPOSIT");
        assertEquals(1, deposits.size(), "DEPOSIT records");
        assertAmount(new BigDecimal("125.50"), deposits.get(0).amount(), "journal amount");
        assertEquals(1, Journal.records(p).size(), "journal has only the deposit");
        Events.Event e = Events.awaitOnly(Config.PLAYER_EVENTS, p, "DEPOSIT", EVENT_TIMEOUT);
        assertAmount("125.50", e.value().get("amount"), "event amount");
        assertEquals(1, ProviderStub.count(ProviderStub.PAYMENTS, p), "POST /payments requests");
    }

    @Test
    @Tag("DEP-02")
    @DisplayName("CL-002 deposit 10.00 (lower bound) is accepted")
    void lowerBoundAccepted() {
        long p = Api.createPlayer();
        assertStatus(Api.deposit(p, "10.00"), 200);
        assertAmount(new BigDecimal("10.00"), Api.balance(p), "balance");
    }

    @Test
    @Tag("DEP-02")
    @DisplayName("CL-003 deposit 50000.00 (upper bound) passes the range check and reaches the provider")
    void upperBoundPassesRange() {
        long p = Api.createPlayer();
        // The stub declines payments > 10000, so an in-range 50000.00 must end with the provider's decline.
        assertError(Api.deposit(p, "50000.00"), 422, "PAYMENT_DECLINED");
        assertEquals(1, ProviderStub.count(ProviderStub.PAYMENTS, p), "the provider was asked");
    }

    @ParameterizedTest(name = "amount {0}")
    @ValueSource(strings = {"9.99", "50000.01"})
    @Tag("DEP-02")
    @DisplayName("CL-004 deposit out of range: 422 DEPOSIT_AMOUNT_OUT_OF_RANGE, nothing changes, provider not called")
    void outOfRangeRefused(String amount) {
        long p = Api.fundedPlayer("100");
        Snapshot before = Snapshot.afterEvents(p, 2, 0); // REGISTERED, DEPOSIT
        JsonNode err = assertError(Api.deposit(p, amount), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");
        assertMinMax(err, "10.00", "50000.00");
        before.assertUnchanged();
    }

    @Test
    @Tag("DEP-02")
    @Tag("GEN-15")
    @DisplayName("CL-005 the key of an out-of-range deposit is not consumed")
    void outOfRangeKeepsKeyFree() {
        long p = Api.createPlayer();
        String key = Api.key();
        assertError(Api.deposit(p, key, "9.99"), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");
        Response retry = Api.deposit(p, key, "20");
        assertStatus(retry, 200);
        assertNotReplayed(retry);
        assertAmount(new BigDecimal("20"), Api.balance(p), "balance");
    }

    @Test
    @Tag("DEP-04")
    @DisplayName("CL-006 provider decline: 422 PAYMENT_DECLINED, nothing changes, key not consumed; 10000.00 approved")
    void providerDecline() {
        long p = Api.fundedPlayer("50");
        String key = Api.key();
        Snapshot before = Snapshot.afterEvents(p, 2, 0); // REGISTERED, DEPOSIT
        assertError(Api.deposit(p, key, "10000.01"), 422, "PAYMENT_DECLINED");
        before.assertUnchangedExceptProviderCalls();
        Response ok = Api.deposit(p, key, "10000.00");
        assertStatus(ok, 200);
        assertNotReplayed(ok);
        assertAmount(new BigDecimal("10050.00"), Api.balance(p), "balance");
    }

    @Test
    @Tag("DEP-05")
    @DisplayName("CL-007 order of checks: request format (400) before unknown player (404)")
    void formatBeforeUnknownPlayer() {
        assertError(Api.deposit(Config.UNKNOWN_ID, "\"100\""), 400, "VALIDATION_ERROR");
        assertError(Api.deposit(Config.UNKNOWN_ID, "100.001"), 400, "VALIDATION_ERROR");
        assertError(Api.depositRaw(Config.UNKNOWN_ID, Api.key(), "{}"), 400, "VALIDATION_ERROR");
    }

    @Test
    @Tag("DEP-05")
    @DisplayName("CL-008 order of checks: unknown player (404) before amount range")
    void unknownPlayerBeforeRange() {
        assertError(Api.deposit(Config.UNKNOWN_ID, "100"), 404, "NOT_FOUND");
        assertError(Api.deposit(Config.UNKNOWN_ID, "5"), 404, "NOT_FOUND");
    }

    @Test
    @Tag("DEP-05")
    @DisplayName("CL-009 order of checks: idempotency key (422 IDEMPOTENCY_KEY_REUSED) before amount range")
    void keyBeforeRange() {
        long p = Api.createPlayer();
        String key = Api.key();
        assertStatus(Api.deposit(p, key, "100"), 200);
        Snapshot before = Snapshot.afterEvents(p, 2, 0); // REGISTERED, DEPOSIT
        assertError(Api.deposit(p, key, "5"), 422, "IDEMPOTENCY_KEY_REUSED");
        before.assertUnchanged();
    }

    @Test
    @Tag("DEP-05")
    @DisplayName("CL-010 order of checks: amount range before deposit limits")
    void rangeBeforeLimits() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "10");
        Api.depositOk(p, "10"); // daily limit exhausted
        assertError(Api.deposit(p, "9.99"), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");
        assertError(Api.deposit(p, "50000.01"), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");
    }

    @Test
    @Tag("DEP-05")
    @Tag("DEP-03")
    @DisplayName("CL-011 order of checks: deposit limits before the provider (provider not called)")
    void limitsBeforeProvider() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "12000");
        Snapshot before = Snapshot.afterEvents(p, 1, 0); // REGISTERED
        // 15000 exceeds the limit and would also be declined by the stub: the limit must answer first.
        assertError(Api.deposit(p, "15000"), 422, "DEPOSIT_LIMIT_EXCEEDED");
        before.assertUnchanged();
        assertEquals(0, ProviderStub.count(ProviderStub.PAYMENTS, p), "POST /payments requests");
        assertEquals(0, Json.dec(Api.limit(p, "DAILY").get("used")).signum(), "refused deposit is not counted");
    }
}
