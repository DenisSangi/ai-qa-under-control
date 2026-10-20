package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Config;
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

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertMinMax;
import static com.arcade.qa.support.Asserts.assertNotReplayed;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Order(3)
@DisplayName("F-01 Deposit")
class DepositTest extends ApiTest {

    @ParameterizedTest(name = "amount {0}")
    @ValueSource(strings = {"9.99", "50000.01"})
    @Tag("DEP-02")
    @DisplayName("CL-004 deposit out of range: 422 DEPOSIT_AMOUNT_OUT_OF_RANGE, nothing changes, provider not called")
    void outOfRangeRefused(String amount) {
        long p = Api.fundedPlayer("100");
        Snapshot before = Snapshot.of(p);
        JsonNode err = assertError(Api.deposit(p, amount), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");
        assertMinMax(err, "10.00", "50000.00");
        before.assertUnchangedExceptProviderCalls();
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
    @Tag("DEP-05")
    @DisplayName("CL-008 order of checks: unknown player (404) before amount range")
    void unknownPlayerBeforeRange() {
        assertError(Api.deposit(Config.UNKNOWN_ID, "100"), 404, "NOT_FOUND");
        JsonNode err = assertError(Api.deposit(Config.UNKNOWN_ID, "5"), 404, "NOT_FOUND");
        assertEquals("player " + Config.UNKNOWN_ID + " not found", err.path("message").asText(), "message");
    }
}
