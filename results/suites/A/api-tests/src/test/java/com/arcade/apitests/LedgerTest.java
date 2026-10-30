package com.arcade.apitests;

import com.arcade.apitests.support.Api.PlayerRef;
import com.arcade.apitests.support.Db;
import com.arcade.apitests.support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.arcade.apitests.support.Api.balance;
import static com.arcade.apitests.support.Api.deposit;
import static com.arcade.apitests.support.Api.expect;
import static com.arcade.apitests.support.Api.expectError;
import static com.arcade.apitests.support.Api.fmt;
import static com.arcade.apitests.support.Api.newKey;
import static com.arcade.apitests.support.Api.newPlayer;
import static com.arcade.apitests.support.Api.playRound;
import static com.arcade.apitests.support.Api.startSession;
import static com.arcade.apitests.support.Api.startSessionOk;
import static com.arcade.apitests.support.Api.withdraw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Ledger consistency")
class LedgerTest extends ApiTest {

    @Test
    @DisplayName("LED-01 after a mixed scenario the balance equals the ledger, which holds exactly the operations that took effect")
    void balanceEqualsLedger() {
        // A shifted clock makes a timestamp taken from the system clock instead of the service clock visible.
        TestClock.ensureShifted(Duration.ofHours(1));
        PlayerRef player = newPlayer();
        Instant start = TestClock.now();
        String key = newKey();

        expect(deposit(player.id(), "500.00", key), 200);
        expect(deposit(player.id(), "250.75", newKey()), 200);
        expect(withdraw(player.id(), "100.25", newKey()), 200);
        expect(deposit(player.id(), "500.00", key), 200);                                   // replay
        expectError(deposit(player.id(), "5.00", newKey()), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");
        expectError(withdraw(player.id(), "9000.00", newKey()), 422, "INSUFFICIENT_FUNDS");
        playRound(player.id(), "50.00", "0");
        playRound(player.id(), "20.00", "75.50");
        expectError(startSession(player.id(), "slots", "700.00"), 422, "BET_AMOUNT_OUT_OF_RANGE");
        long expiring = startSessionOk(player.id(), "30.00").get("id").asLong();
        TestClock.advance(Duration.ofSeconds(61));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(Db.sessionStatus(expiring)).isEqualTo("EXPIRED"));
        Instant end = TestClock.now();

        List<Db.Tx> ledger = Db.transactions(player.id());
        assertThat(ledger).extracting(tx -> tx.type() + " " + fmt(tx.amount())).containsExactly(
                "DEPOSIT 500.00", "DEPOSIT 250.75", "WITHDRAWAL 100.25",
                "BET 50.00", "BET 20.00", "WIN 75.50", "BET 30.00", "REFUND 30.00");
        assertThat(ledger).allSatisfy(tx -> assertThat(tx.createdAt()).as("created_at of %s", tx).isBetween(start, end));

        BigDecimal signed = ledger.stream()
                .map(tx -> switch (tx.type()) {
                    case "DEPOSIT", "WIN", "REFUND" -> tx.amount();
                    case "WITHDRAWAL", "BET" -> tx.amount().negate();
                    default -> throw new AssertionError("unexpected ledger type " + tx);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(balance(player.id())).isEqualByComparingTo(signed).isEqualByComparingTo("656.00");
    }
}
