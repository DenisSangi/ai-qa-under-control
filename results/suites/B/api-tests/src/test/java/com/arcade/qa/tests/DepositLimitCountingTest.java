package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.ProviderStub;
import com.arcade.qa.support.Snapshot;
import com.arcade.qa.support.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertNotReplayed;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Order(8)
@DisplayName("F-02 Deposit limits: counting and enforcement")
class DepositLimitCountingTest extends ApiTest {

    private static void assertUsed(long p, String period, String used) {
        assertAmount(used, Api.limit(p, period).get("used"), period + " used");
    }

    private static void assertExceeded(Response r, String period, String limit, String remaining) {
        JsonNode err = assertError(r, 422, "DEPOSIT_LIMIT_EXCEEDED");
        JsonNode d = err.path("details");
        assertAll("details of " + err,
                () -> assertEquals(period, d.path("period").asText(), "details.period"),
                () -> assertAmount(limit, d.path("limit"), "details.limit"),
                () -> assertAmount(remaining, d.path("remaining"), "details.remaining"));
    }

    @Test
    @Tag("LIM-11")
    @DisplayName("CL-034 daily window: a deposit is counted just before 24h and not counted from 24h on")
    void dailyWindow() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Instant before = TestClock.now();
        Api.depositOk(p, "100");
        Instant after = TestClock.now();
        TestClock.advanceTo(before.plus(Duration.ofHours(24)).minusSeconds(30));
        assertUsed(p, "DAILY", "100");
        assertError(Api.deposit(p, "10"), 422, "DEPOSIT_LIMIT_EXCEEDED");
        TestClock.advanceTo(after.plus(Duration.ofHours(24)).plusSeconds(1));
        assertUsed(p, "DAILY", "0");
        assertAmount("100", Api.limit(p, "DAILY").get("remaining"), "DAILY remaining");
        assertStatus(Api.deposit(p, "100"), 200);
    }

    @Test
    @Tag("LIM-11")
    @DisplayName("CL-035 weekly window is 7 days and monthly window is 30 days")
    void weeklyAndMonthlyWindows() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "weekly", "200");
        Api.putLimitOk(p, "monthly", "300");
        Instant before = TestClock.now();
        Api.depositOk(p, "100");
        Instant after = TestClock.now();
        TestClock.advanceTo(before.plus(Duration.ofDays(7)).minusSeconds(30));
        assertUsed(p, "WEEKLY", "100");
        assertUsed(p, "MONTHLY", "100");
        TestClock.advanceTo(after.plus(Duration.ofDays(7)).plusSeconds(1));
        assertUsed(p, "WEEKLY", "0");
        assertUsed(p, "MONTHLY", "100");
        TestClock.advanceTo(before.plus(Duration.ofDays(30)).minusSeconds(30));
        assertUsed(p, "MONTHLY", "100");
        TestClock.advanceTo(after.plus(Duration.ofDays(30)).plusSeconds(1));
        assertUsed(p, "MONTHLY", "0");
    }

    @Test
    @Tag("LIM-11")
    @DisplayName("CL-036 refused deposits (out of range, limit exceeded, provider declined) are never counted")
    void refusedNotCounted() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "20000");
        assertError(Api.deposit(p, "9.99"), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");
        assertError(Api.deposit(p, "25000"), 422, "DEPOSIT_LIMIT_EXCEEDED");
        assertError(Api.deposit(p, "10000.01"), 422, "PAYMENT_DECLINED");
        assertUsed(p, "DAILY", "0");
        assertAmount("20000", Api.limit(p, "DAILY").get("remaining"), "DAILY remaining");
    }

    @Test
    @Tag("LIM-11")
    @DisplayName("CL-037 a withdrawal does not reduce the counted amount")
    void withdrawalDoesNotReduceUsed() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Api.depositOk(p, "100");
        assertStatus(Api.withdraw(p, "50"), 200);
        assertUsed(p, "DAILY", "100");
        assertAmount("0", Api.limit(p, "DAILY").get("remaining"), "DAILY remaining");
        assertError(Api.deposit(p, "10"), 422, "DEPOSIT_LIMIT_EXCEEDED");
    }

    @Test
    @Tag("LIM-12")
    @Tag("DEP-03")
    @DisplayName("CL-038 deposit equal to remaining accepted; +0.01 -> 422 DEPOSIT_LIMIT_EXCEEDED, nothing changes, key free")
    void depositAgainstLimit() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Api.depositOk(p, "60");
        String key = Api.key();
        Snapshot before = Snapshot.of(p);
        assertExceeded(Api.deposit(p, key, "40.01"), "DAILY", "100", "40");
        before.assertUnchanged();
        assertEquals(1, ProviderStub.count(ProviderStub.PAYMENTS, p), "the provider is not called for the refusal");
        Response ok = Api.deposit(p, key, "40");
        assertStatus(ok, 200);
        assertNotReplayed(ok);
        assertAmount(new BigDecimal("100"), Api.balance(p), "balance");
        assertAmount("0", Api.limit(p, "DAILY").get("remaining"), "DAILY remaining");
    }

    @Test
    @Tag("LIM-12")
    @DisplayName("CL-039 most restrictive period wins (spec example: daily remaining 50, monthly 0 -> MONTHLY, 0)")
    void mostRestrictivePeriod() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Api.putLimitOk(p, "monthly", "150");
        Api.depositOk(p, "100");
        TestClock.advance(Duration.ofHours(25));
        assertStatus(Api.deposit(p, "50"), 200); // equal to the remaining 50 of MONTHLY
        assertAmount("50", Api.limit(p, "DAILY").get("remaining"), "DAILY remaining");
        assertAmount("0", Api.limit(p, "MONTHLY").get("remaining"), "MONTHLY remaining");
        assertExceeded(Api.deposit(p, "60"), "MONTHLY", "150", "0");
    }

    @Test
    @Tag("LIM-12")
    @DisplayName("CL-040 tie on remaining -> the shortest period (DAILY over WEEKLY, WEEKLY over MONTHLY)")
    void tieGoesToShortestPeriod() {
        long a = Api.createPlayer();
        Api.putLimitOk(a, "daily", "100");
        Api.putLimitOk(a, "weekly", "100");
        Api.depositOk(a, "50");
        assertExceeded(Api.deposit(a, "60"), "DAILY", "100", "50");

        long b = Api.createPlayer();
        Api.putLimitOk(b, "daily", "100");
        Api.putLimitOk(b, "weekly", "150");
        Api.putLimitOk(b, "monthly", "150");
        Api.depositOk(b, "100");
        TestClock.advance(Duration.ofHours(25));
        assertExceeded(Api.deposit(b, "60"), "WEEKLY", "150", "50");
    }
}
