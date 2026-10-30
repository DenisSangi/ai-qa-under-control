package com.arcade.apitests;

import com.arcade.apitests.support.Api.PlayerRef;
import com.arcade.apitests.support.Db;
import com.arcade.apitests.support.Json;
import com.arcade.apitests.support.ProviderJournal;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.arcade.apitests.support.Api.balance;
import static com.arcade.apitests.support.Api.expect;
import static com.arcade.apitests.support.Api.expectError;
import static com.arcade.apitests.support.Api.fmt;
import static com.arcade.apitests.support.Api.newKey;
import static com.arcade.apitests.support.Api.newPlayer;
import static com.arcade.apitests.support.Api.withdraw;
import static com.arcade.apitests.support.Api.withdrawRaw;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Withdrawals")
class WithdrawalTest extends ApiTest {

    @Test
    @DisplayName("WD-01 a withdrawal debits the balance, writes a ledger row and pays out once; the full balance can be withdrawn")
    void withdrawalDebitsBalance() {
        PlayerRef player = newPlayer("300.00");

        JsonNode body = expect(withdraw(player.id(), "120.50", newKey()), 200).json();
        assertThat(Json.dec(body, "balance")).isEqualByComparingTo("179.50");

        expect(withdraw(player.id(), "179.50", newKey()), 200);
        assertThat(balance(player.id())).isEqualByComparingTo("0.00");

        assertThat(Db.transactions(player.id(), "WITHDRAWAL")).extracting(tx -> fmt(tx.amount()))
                .containsExactly("120.50", "179.50");
        assertThat(ProviderJournal.calls(ProviderJournal.PAYOUTS, player.id()))
                .extracting(call -> fmt(call.amount()))
                .containsExactly("120.50", "179.50");
    }

    @Test
    @DisplayName("WD-02 a withdrawal above the balance is INSUFFICIENT_FUNDS and the provider is not called")
    void insufficientFunds() {
        PlayerRef player = newPlayer("50.00");

        expectError(withdraw(player.id(), "50.01", newKey()), 422, "INSUFFICIENT_FUNDS");

        assertThat(balance(player.id())).isEqualByComparingTo("50.00");
        assertThat(Db.transactions(player.id(), "WITHDRAWAL")).isEmpty();
        assertThat(ProviderJournal.calls(ProviderJournal.PAYOUTS, player.id())).isEmpty();
    }

    @Test
    @DisplayName("WD-03 a payout declined by the provider changes nothing; 5000.00 is still approved")
    void declinedPayout() {
        PlayerRef player = newPlayer("6000.00");

        expectError(withdraw(player.id(), "5000.01", newKey()), 422, "PAYOUT_DECLINED");
        assertThat(balance(player.id())).isEqualByComparingTo("6000.00");
        assertThat(Db.transactions(player.id(), "WITHDRAWAL")).isEmpty();

        expect(withdraw(player.id(), "5000.00", newKey()), 200);
        assertThat(balance(player.id())).isEqualByComparingTo("1000.00");
    }

    @ParameterizedTest(name = "amount {0} is out of range")
    @ValueSource(strings = {"9.99", "0", "-10.00", "-100", "50000.01"})
    @DisplayName("WD-04 amounts outside 10.00-50000.00 are refused (a negative withdrawal must never credit)")
    void refusesAmountsOutOfRange(String amount) {
        PlayerRef player = newPlayer("100.00");

        JsonNode error = expectError(withdrawRaw(player.id(), amount, newKey()), 422, "WITHDRAWAL_AMOUNT_OUT_OF_RANGE");

        assertThat(error.path("details").path("min").asText()).isEqualTo("10.00");
        assertThat(error.path("details").path("max").asText()).isEqualTo("50000.00");
        assertThat(balance(player.id())).isEqualByComparingTo("100.00");
        assertThat(ProviderJournal.calls(ProviderJournal.PAYOUTS, player.id())).isEmpty();
    }

    @ParameterizedTest(name = "amount {0}")
    @ValueSource(strings = {"10.001", "\"10\"", "null"})
    @DisplayName("WD-04 an amount of a wrong format is a 400 validation error")
    void refusesMalformedAmount(String amount) {
        PlayerRef player = newPlayer("100.00");

        JsonNode error = expectError(withdrawRaw(player.id(), amount, newKey()), 400, "VALIDATION_ERROR");

        assertThat(error.path("details").has("amount")).isTrue();
        assertThat(balance(player.id())).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("WD-04 the lower bound 10.00 is accepted")
    void acceptsLowerBound() {
        PlayerRef player = newPlayer("10.00");

        expect(withdraw(player.id(), "10.00", newKey()), 200);
        assertThat(balance(player.id())).isZero();
    }
}
