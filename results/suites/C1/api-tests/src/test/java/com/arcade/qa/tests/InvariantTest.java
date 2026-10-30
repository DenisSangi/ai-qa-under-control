package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Events;
import com.arcade.qa.support.Journal;
import com.arcade.qa.support.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs last: the sweep covers every player created by the suite. */
@Order(99)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("F-11 Invariants")
class InvariantTest extends ApiTest {

    @Test
    @Order(1)
    @Tag("GEN-25")
    @DisplayName("CL-123 balance equals the journal after deposit, win, loss, refund and withdrawal")
    void mixedFlow() {
        long p = Api.createPlayer();
        Api.depositOk(p, "500");
        Api.finishOk(Api.startOk(p, "10").path("id").asLong(), "25");
        Api.finishOk(Api.startOk(p, "20").path("id").asLong(), "0");
        long expiring = Api.startOk(p, "30").path("id").asLong();
        long bound = SessionTimingTest.expire(expiring);
        // SES-11: the refund itself (not only the status) must be in effect before the withdrawal.
        BigDecimal refunded = SessionTimingTest.pollUntil(() -> Api.balance(p),
                b -> Json.sameValue(new BigDecimal("495"), b), bound);
        assertAmount(new BigDecimal("495"), refunded, "balance after the refund");
        assertStatus(Api.withdraw(p, "100"), 200);
        assertAmount(new BigDecimal("395"), Api.balance(p), "balance");
        ConcurrencyTest.assertBalanceMatchesJournal(p);
    }

    @Test
    @Order(2)
    @Tag("GEN-25")
    @DisplayName("CL-124 balance equals the journal for every player created by this run")
    void sweep() {
        List<String> mismatches = new ArrayList<>();
        for (long p : Api.createdPlayers()) {
            BigDecimal balance = Api.balance(p);
            BigDecimal journal = Journal.journalBalance(p);
            if (!Json.sameValue(balance, journal)) {
                Events.sleep(500); // a refund may be in flight; read once more
                balance = Api.balance(p);
                journal = Journal.journalBalance(p);
                if (!Json.sameValue(balance, journal)) {
                    mismatches.add("player " + p + ": balance " + balance + ", journal " + journal);
                }
            }
        }
        assertTrue(mismatches.isEmpty(), () -> "GEN-25 violated for " + mismatches.size() + " of "
                + Api.createdPlayers().size() + " players: " + mismatches);
    }
}
