package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Journal;
import com.arcade.qa.support.Json;
import com.arcade.qa.support.Parallel;
import com.arcade.qa.support.ProviderStub;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Order(11)
@DisplayName("F-11 Concurrency")
class ConcurrencyTest extends ApiTest {

    static void assertBalanceMatchesJournal(long p) {
        BigDecimal balance = Api.balance(p);
        BigDecimal journal = Journal.journalBalance(p);
        assertTrue(Json.sameValue(balance, journal),
                () -> "GEN-25: balance " + balance + " != journal " + journal + " for player " + p);
    }

    @Test
    @Tag("GEN-26")
    @DisplayName("CL-125 concurrent deposits with different keys: every deposit applied, no lost update")
    void concurrentDeposits() {
        long p = Api.createPlayer();
        List<Response> rs = Parallel.run(10, i -> Api.deposit(p, "100"));
        assertEquals(10, Parallel.count(rs, 200), () -> Parallel.describe(rs));
        assertAmount(new BigDecimal("1000"), Api.balance(p), "balance");
        assertEquals(10, Journal.records(p, "DEPOSIT").size(), "DEPOSIT records");
        assertBalanceMatchesJournal(p);
    }

    @Test
    @Tag("GEN-26")
    @DisplayName("CL-126 concurrent withdrawals exceeding the balance: serializable result, no overdraft")
    void concurrentWithdrawals() {
        long p = Api.fundedPlayer("100");
        List<Response> rs = Parallel.run(5, i -> Api.withdraw(p, "30"));
        long ok = Parallel.count(rs, 200);
        assertEquals(3, ok, () -> "exactly 3 of 5 withdrawals of 30 fit into 100: " + Parallel.describe(rs));
        rs.stream().filter(r -> r.statusCode() != 200).forEach(r -> assertError(r, 422, "INSUFFICIENT_FUNDS"));
        assertAmount(new BigDecimal("10"), Api.balance(p), "balance");
        assertEquals(3, Journal.records(p, "WITHDRAWAL").size(), "WITHDRAWAL records");
        assertBalanceMatchesJournal(p);
    }

    @Test
    @Tag("GEN-26")
    @Tag("SES-04")
    @DisplayName("CL-127 concurrent session starts: exactly one ACTIVE session and one BET")
    void concurrentSessionStarts() {
        long p = Api.fundedPlayer("100");
        List<Response> rs = Parallel.run(5, i -> Api.startSession(p, "10"));
        assertEquals(1, Parallel.count(rs, 201), () -> Parallel.describe(rs));
        rs.stream().filter(r -> r.statusCode() != 201).forEach(r -> assertError(r, 409, "ACTIVE_SESSION_EXISTS"));
        assertAmount(new BigDecimal("90"), Api.balance(p), "balance");
        assertEquals(1, Journal.records(p, "BET").size(), "BET records");
        assertBalanceMatchesJournal(p);
    }

    @Test
    @Tag("GEN-26")
    @DisplayName("CL-128 concurrent withdrawal and session start for the same money: exactly one succeeds")
    void withdrawalVersusSessionStart() {
        for (int round = 0; round < 3; round++) {
            long p = Api.fundedPlayer("100");
            List<Response> rs = Parallel.run(2, i -> i == 0 ? Api.withdraw(p, "100") : Api.startSession(p, "100"));
            Response w = rs.get(0);
            Response s = rs.get(1);
            int okCount = (w.statusCode() == 200 ? 1 : 0) + (s.statusCode() == 201 ? 1 : 0);
            assertEquals(1, okCount, () -> "exactly one must succeed: " + Parallel.describe(rs));
            if (w.statusCode() == 200) {
                assertError(s, 422, "INSUFFICIENT_FUNDS");
            } else {
                assertError(w, 409, "ACTIVE_SESSION_EXISTS");
            }
            assertAmount(BigDecimal.ZERO, Api.balance(p), "balance");
            assertBalanceMatchesJournal(p);
        }
    }

    @Test
    @Tag("GEN-26")
    @Tag("GEN-14")
    @DisplayName("CL-129 concurrent requests with one key: the operation is performed exactly once")
    void concurrentSameKey() {
        long p = Api.createPlayer();
        String key = Api.key();
        List<Response> rs = Parallel.run(5, i -> Api.deposit(p, key, "100"));
        long fresh = rs.stream().filter(r -> r.statusCode() == 200 && !Api.replayed(r)).count();
        assertEquals(1, fresh, () -> "exactly one new deposit: " + Parallel.describe(rs));
        for (Response r : rs) {
            assertTrue(r.statusCode() == 200 || r.statusCode() == 409,
                    () -> "200 (new or replay) or 409 IN_PROGRESS expected: " + Parallel.describe(rs));
            if (r.statusCode() == 409) {
                assertError(r, 409, "IDEMPOTENCY_KEY_IN_PROGRESS");
            }
        }
        assertAmount(new BigDecimal("100"), Api.balance(p), "balance");
        assertEquals(1, Journal.records(p, "DEPOSIT").size(), "DEPOSIT records");
        assertEquals(1, ProviderStub.count(ProviderStub.PAYMENTS, p), "POST /payments requests");
    }

    @Test
    @Tag("GEN-26")
    @Tag("SES-09")
    @DisplayName("CL-130 concurrent finishes of one session: the win is credited exactly once")
    void concurrentFinishes() {
        long p = Api.fundedPlayer("100");
        long sid = Api.startOk(p, "10").path("id").asLong();
        List<Response> rs = Parallel.run(5, i -> Api.finish(sid, "50"));
        assertEquals(1, Parallel.count(rs, 200), () -> Parallel.describe(rs));
        rs.stream().filter(r -> r.statusCode() != 200).forEach(r -> assertError(r, 409, "SESSION_NOT_ACTIVE"));
        assertAmount(new BigDecimal("140"), Api.balance(p), "balance");
        assertEquals(1, Journal.records(p, "WIN").size(), "WIN records");
        assertBalanceMatchesJournal(p);
    }
}
