package com.arcade.apitests;

import com.arcade.apitests.support.Api.PlayerRef;
import com.arcade.apitests.support.Db;
import com.arcade.apitests.support.Http;
import com.arcade.apitests.support.Json;
import com.arcade.apitests.support.ProviderJournal;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.stream.Stream;

import static com.arcade.apitests.support.Api.balance;
import static com.arcade.apitests.support.Api.deposit;
import static com.arcade.apitests.support.Api.depositRaw;
import static com.arcade.apitests.support.Api.expect;
import static com.arcade.apitests.support.Api.expectError;
import static com.arcade.apitests.support.Api.fmt;
import static com.arcade.apitests.support.Api.newKey;
import static com.arcade.apitests.support.Api.newPlayer;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Deposits")
class DepositTest extends ApiTest {

    @Test
    @DisplayName("DEP-01 a deposit credits the balance, writes one ledger row and charges the provider once")
    void depositCreditsBalance() {
        PlayerRef player = newPlayer();

        JsonNode body = expect(deposit(player.id(), "100.50", newKey()), 200).json();

        assertThat(body.get("id").asLong()).isEqualTo(player.id());
        assertThat(Json.dec(body, "balance")).isEqualByComparingTo("100.50");
        assertThat(balance(player.id())).isEqualByComparingTo("100.50");
        assertThat(Db.transactions(player.id()))
                .singleElement()
                .satisfies(tx -> {
                    assertThat(tx.type()).isEqualTo("DEPOSIT");
                    assertThat(tx.amount()).isEqualByComparingTo("100.50");
                });
        assertThat(ProviderJournal.calls(ProviderJournal.PAYMENTS, player.id()))
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.amount()).isEqualByComparingTo("100.50");
                    assertThat(call.reference()).isNotBlank();
                });
    }

    @ParameterizedTest(name = "amount {0} is accepted")
    @ValueSource(strings = {"10.00", "10", "9999.99", "10000.00"})
    @DisplayName("DEP-02 amounts inside the range (and approved by the provider) are credited")
    void acceptsAmountsInRange(String amount) {
        PlayerRef player = newPlayer();

        expect(deposit(player.id(), amount, newKey()), 200);

        assertThat(balance(player.id())).isEqualByComparingTo(amount);
    }

    @ParameterizedTest(name = "amount {0} is out of range")
    @ValueSource(strings = {"9.99", "0", "-10.00", "-0.01", "50000.01", "1e20"})
    @DisplayName("DEP-02 amounts outside 10.00-50000.00 are refused before the provider is called")
    void refusesAmountsOutOfRange(String amount) {
        PlayerRef player = newPlayer();

        JsonNode error = expectError(depositRaw(player.id(), amount, newKey()), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");

        assertThat(error.path("details").path("min").asText()).isEqualTo("10.00");
        assertThat(error.path("details").path("max").asText()).isEqualTo("50000.00");
        assertThat(balance(player.id())).isZero();
        assertThat(Db.transactions(player.id())).isEmpty();
        assertThat(ProviderJournal.calls(ProviderJournal.PAYMENTS, player.id())).isEmpty();
    }

    @Test
    @DisplayName("DEP-02 the upper bound 50000.00 passes the range check (the stub provider then declines it)")
    void upperBoundIsInsideRange() {
        PlayerRef player = newPlayer();

        expectError(deposit(player.id(), "50000.00", newKey()), 422, "PAYMENT_DECLINED");
        assertThat(balance(player.id())).isZero();
    }

    static Stream<Arguments> malformedAmounts() {
        return Stream.of(
                Arguments.of("10.001", "VALIDATION_ERROR", "amount"),
                Arguments.of("\"10\"", "VALIDATION_ERROR", "amount"),
                Arguments.of("null", "VALIDATION_ERROR", "amount"),
                Arguments.of("[10]", "VALIDATION_ERROR", "amount"));
    }

    @ParameterizedTest(name = "amount {0} -> {1}")
    @MethodSource("malformedAmounts")
    @DisplayName("DEP-03 an amount of a wrong format is a 400 validation error of the amount field")
    void refusesMalformedAmount(String amount, String code, String field) {
        PlayerRef player = newPlayer();

        JsonNode error = expectError(depositRaw(player.id(), amount, newKey()), 400, code);

        assertThat(error.path("details").has(field)).as("details of %s", error).isTrue();
        assertThat(balance(player.id())).isZero();
        assertThat(ProviderJournal.calls(ProviderJournal.PAYMENTS, player.id())).isEmpty();
    }

    @Test
    @DisplayName("DEP-03 an unreadable body is MALFORMED_JSON")
    void refusesMalformedJson() {
        PlayerRef player = newPlayer();

        expectError(Http.post("/api/players/" + player.id() + "/deposit", "{\"amount\": 10",
                Map.of("Idempotency-Key", newKey())), 400, "MALFORMED_JSON");
        expectError(Http.post("/api/players/" + player.id() + "/deposit", "",
                Map.of("Idempotency-Key", newKey(), "Content-Type", "application/json")), 400, "MALFORMED_JSON");
        assertThat(balance(player.id())).isZero();
    }

    @Test
    @DisplayName("DEP-04 a deposit declined by the provider changes nothing")
    void declinedDepositChangesNothing() {
        PlayerRef player = newPlayer();
        expect(deposit(player.id(), "20.00", newKey()), 200);

        String declinedKey = newKey();
        expectError(deposit(player.id(), "10000.01", declinedKey), 422, "PAYMENT_DECLINED");

        assertThat(balance(player.id())).isEqualByComparingTo("20.00");
        assertThat(Db.transactions(player.id())).extracting(tx -> tx.type() + " " + fmt(tx.amount()))
                .containsExactly("DEPOSIT 20.00");
        assertThat(Db.idempotencyKey(player.id(), declinedKey)).as("a failed deposit releases its key").isNull();
    }

    @Test
    @DisplayName("DEP-05 a deposit to an unknown player is 404")
    void unknownPlayer() {
        expectError(deposit(9_000_000_000_000L, "10.00", newKey()), 404, "NOT_FOUND");
    }
}
