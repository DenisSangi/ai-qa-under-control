package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Journal;
import com.arcade.qa.support.Json;
import com.arcade.qa.support.Parallel;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertStatus;
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
    @Tag("SES-09")
    @DisplayName("CL-130 concurrent finishes of one session: the win is credited exactly once")
    void concurrentFinishes() {
        long p = Api.fundedPlayer("100");
        long sid = Api.startOk(p, "10").path("id").asLong();
        List<Response> rs = Parallel.run(5, i -> Api.finish(sid, "50"));
        assertStatus(rs.get(0), 200);
        rs.subList(1, rs.size()).forEach(r -> assertError(r, 409, "SESSION_NOT_ACTIVE"));
        assertAmount(new BigDecimal("140"), Api.balance(p), "balance");
        assertEquals(1, Journal.records(p, "WIN").size(), "WIN records");
        assertBalanceMatchesJournal(p);
    }
}
