package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Config;
import com.arcade.qa.support.Events;
import com.arcade.qa.support.Journal;
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

@Order(4)
@DisplayName("F-03 Withdrawal")
class WithdrawalTest extends ApiTest {

    @Test
    @Tag("WDR-01")
    @DisplayName("CL-041 approved withdrawal: 200, balance - amount, one WITHDRAWAL record, one event, one provider call")
    void approvedWithdrawal() {
        long p = Api.fundedPlayer("300");
        Response r = Api.withdraw(p, "120.25");
        assertStatus(r, 200);
        JsonNode body = Api.body(r);
        assertEquals(p, body.path("id").asLong(), "response is the player");
        assertAmount("179.75", body.get("balance"), "balance in the response");
        assertAmount(new BigDecimal("179.75"), Api.balance(p), "balance");
        List<Journal.Tx> w = Journal.records(p, "WITHDRAWAL");
        assertEquals(1, w.size(), "WITHDRAWAL records");
        assertAmount(new BigDecimal("120.25"), w.get(0).amount(), "journal amount");
        Events.Event e = Events.awaitOnly(Config.PLAYER_EVENTS, p, "WITHDRAWAL", EVENT_TIMEOUT);
        assertAmount("120.25", e.value().get("amount"), "event amount");
        assertEquals(1, ProviderStub.count(ProviderStub.PAYOUTS, p), "POST /payouts requests");
    }

    @Test
    @Tag("WDR-02")
    @DisplayName("CL-042 withdrawal 10.00 accepted; 50000.00 passes the range check (-> INSUFFICIENT_FUNDS)")
    void boundsAccepted() {
        long p = Api.fundedPlayer("100");
        assertStatus(Api.withdraw(p, "10.00"), 200);
        assertAmount(new BigDecimal("90.00"), Api.balance(p), "balance");
        assertError(Api.withdraw(p, "50000.00"), 422, "INSUFFICIENT_FUNDS");
    }

    @ParameterizedTest(name = "amount {0}")
    @ValueSource(strings = {"9.99", "50000.01"})
    @Tag("WDR-02")
    @DisplayName("CL-043 withdrawal out of range: 422 WITHDRAWAL_AMOUNT_OUT_OF_RANGE, nothing changes, key free")
    void outOfRange(String amount) {
        long p = Api.fundedPlayer("100");
        String key = Api.key();
        Snapshot before = Snapshot.afterEvents(p, 2, 0); // REGISTERED, DEPOSIT
        JsonNode err = assertError(Api.withdraw(p, key, amount), 422, "WITHDRAWAL_AMOUNT_OUT_OF_RANGE");
        assertMinMax(err, "10.00", "50000.00");
        before.assertUnchanged();
        Response retry = Api.withdraw(p, key, "10");
        assertStatus(retry, 200);
        assertNotReplayed(retry);
    }

    @Test
    @Tag("WDR-02")
    @DisplayName("CL-044 a balance below 10.00 cannot be withdrawn")
    void smallBalanceNotWithdrawable() {
        long p = Api.fundedPlayer("10");
        long s = Api.startOk(p, "5").path("id").asLong();
        Api.finishOk(s, "0"); // balance 5.00
        assertError(Api.withdraw(p, "5"), 422, "WITHDRAWAL_AMOUNT_OUT_OF_RANGE");
        assertAmount(new BigDecimal("5"), Api.balance(p), "balance");
    }

    @Test
    @Tag("WDR-03")
    @DisplayName("CL-045 active session: 409 ACTIVE_SESSION_EXISTS, nothing changes, provider not called, key free")
    void activeSessionBlocks() {
        long p = Api.fundedPlayer("100");
        long s = Api.startOk(p, "10").path("id").asLong();
        String key = Api.key();
        Snapshot before = Snapshot.afterEvents(p, 2, 1); // REGISTERED, DEPOSIT; SESSION_STARTED
        assertError(Api.withdraw(p, key, "20"), 409, "ACTIVE_SESSION_EXISTS");
        before.assertUnchanged();
        Api.finishOk(s, "0");
        Response retry = Api.withdraw(p, key, "20");
        assertStatus(retry, 200);
        assertNotReplayed(retry);
        assertAmount(new BigDecimal("70"), Api.balance(p), "balance");
    }

    @Test
    @Tag("WDR-03")
    @Tag("SES-12")
    @DisplayName("CL-046 EXPIRED and FINISHED sessions do not block a withdrawal")
    void expiredAndFinishedDoNotBlock() {
        long p = Api.fundedPlayer("100");
        long finished = Api.startOk(p, "10").path("id").asLong();
        Api.finishOk(finished, "5");
        assertStatus(Api.withdraw(p, "10"), 200);
        long expired = Api.startOk(p, "10").path("id").asLong();
        SessionTimingTest.expire(expired);
        assertStatus(Api.withdraw(p, "10"), 200);
    }

    @Test
    @Tag("WDR-04")
    @DisplayName("CL-047 amount above balance: 422 INSUFFICIENT_FUNDS, nothing changes, provider not called, key free")
    void insufficientFunds() {
        long p = Api.fundedPlayer("100");
        String key = Api.key();
        Snapshot before = Snapshot.afterEvents(p, 2, 0); // REGISTERED, DEPOSIT
        assertError(Api.withdraw(p, key, "100.01"), 422, "INSUFFICIENT_FUNDS");
        before.assertUnchanged();
        Response retry = Api.withdraw(p, key, "100");
        assertStatus(retry, 200);
        assertNotReplayed(retry);
    }

    @Test
    @Tag("WDR-04")
    @DisplayName("CL-048 withdrawing the whole balance is allowed")
    void wholeBalance() {
        long p = Api.fundedPlayer("123.45");
        Response r = Api.withdraw(p, "123.45");
        assertStatus(r, 200);
        assertAmount("0", Api.body(r).get("balance"), "balance in the response");
        assertAmount(BigDecimal.ZERO, Api.balance(p), "balance");
    }

    @Test
    @Tag("WDR-05")
    @DisplayName("CL-049 payout decline: 422 PAYOUT_DECLINED, nothing changes, key free; 5000.00 approved")
    void payoutDecline() {
        long p = Api.fundedPlayer("6000");
        String key = Api.key();
        Snapshot before = Snapshot.afterEvents(p, 2, 0); // REGISTERED, DEPOSIT
        assertError(Api.withdraw(p, key, "5000.01"), 422, "PAYOUT_DECLINED");
        before.assertUnchangedExceptProviderCalls();
        Response ok = Api.withdraw(p, key, "5000.00");
        assertStatus(ok, 200);
        assertNotReplayed(ok);
        assertAmount(new BigDecimal("1000.00"), Api.balance(p), "balance");
    }

    @Test
    @Tag("WDR-06")
    @DisplayName("CL-050 order of checks: 400 before 404, 404 before range")
    void formatAndPlayerFirst() {
        assertError(Api.withdraw(Config.UNKNOWN_ID, "\"100\""), 400, "VALIDATION_ERROR");
        assertError(Api.withdraw(Config.UNKNOWN_ID, "10.001"), 400, "VALIDATION_ERROR");
        assertError(Api.withdraw(Config.UNKNOWN_ID, "5"), 404, "NOT_FOUND");
    }

    @Test
    @Tag("WDR-06")
    @DisplayName("CL-051 order of checks: idempotency key before range, range before active session")
    void keyBeforeRangeBeforeSession() {
        long p = Api.fundedPlayer("100");
        String key = Api.key();
        assertStatus(Api.withdraw(p, key, "10"), 200);
        assertError(Api.withdraw(p, key, "5"), 422, "IDEMPOTENCY_KEY_REUSED");
        Api.startOk(p, "10");
        assertError(Api.withdraw(p, "5"), 422, "WITHDRAWAL_AMOUNT_OUT_OF_RANGE");
    }

    @Test
    @Tag("WDR-06")
    @DisplayName("CL-052 order of checks: active session before balance, balance before provider")
    void sessionBeforeBalanceBeforeProvider() {
        long p = Api.fundedPlayer("100");
        long s = Api.startOk(p, "10").path("id").asLong();
        assertError(Api.withdraw(p, "1000"), 409, "ACTIVE_SESSION_EXISTS");
        Api.finishOk(s, "0");
        Snapshot before = Snapshot.afterEvents(p, 2, 2); // REGISTERED, DEPOSIT; SESSION_STARTED, SESSION_FINISHED
        // 6000 exceeds the balance and would also be declined by the stub: the balance must answer first.
        assertError(Api.withdraw(p, "6000"), 422, "INSUFFICIENT_FUNDS");
        before.assertUnchanged();
    }

    @Test
    @Tag("WDR-06")
    @DisplayName("CL-137 order of checks: amount range before balance (balance 0: 9.99 and 60000 -> WITHDRAWAL_AMOUNT_OUT_OF_RANGE)")
    void rangeBeforeBalance() {
        long p = Api.createPlayer(); // balance 0: both amounts also exceed the balance (WDR-04, step 6)
        for (String amount : new String[]{"9.99", "60000"}) {
            JsonNode err = assertError(Api.withdraw(p, amount), 422, "WITHDRAWAL_AMOUNT_OUT_OF_RANGE");
            assertMinMax(err, "10.00", "50000.00");
        }
        assertAmount(BigDecimal.ZERO, Api.balance(p), "balance");
    }
}
