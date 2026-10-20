package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Order(7)
@DisplayName("F-02 Deposit limits: rules and lifecycle")
class DepositLimitsTest extends ApiTest {

    @Test
    @Tag("LIM-02")
    @DisplayName("CL-013 player without limits gets 200 and an empty list")
    void emptyList() {
        long p = Api.body(Api.createPlayerRaw("qa_limits_empty")).path("id").asLong();
        Response r = Api.limits(p);
        assertStatus(r, 200);
        JsonNode list = Api.body(r);
        assertTrue(list.isArray() && list.isEmpty(), () -> "empty list expected: " + list);
    }

    @Test
    @Tag("LIM-02")
    @DisplayName("CL-016 remaining is not below 0 after lowering the limit below used")
    void remainingNotNegative() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Api.depositOk(p, "80");
        JsonNode lowered = Api.putLimitOk(p, "daily", "50");
        assertAmount("50", lowered.get("amount"), "amount");
        JsonNode d = Api.limit(p, "DAILY");
        assertAmount("80", d.get("used"), "used");
        BigDecimal remaining = Json.dec(Api.limit(p, "DAILY").get("remaining"));
        assertAmount(remaining, Json.dec(d.get("remaining")), "remaining");
    }

    @Test
    @Tag("LIM-04")
    @DisplayName("CL-019 spec example: daily 100, weekly 300 -> weekly 100 accepted, weekly 99 refused with details")
    void orderExampleOne() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Api.putLimitOk(p, "weekly", "300");
        assertAmount("100", Api.putLimitOk(p, "weekly", "100").get("amount"), "weekly");
        JsonNode before = Api.limitList(p);
        JsonNode err = assertError(Api.putLimit(p, "weekly", "99"), 422, "DEPOSIT_LIMIT_ORDER_VIOLATED");
        assertEquals("WEEKLY", err.path("details").path("period").asText(), "details.period");
        assertEquals("DAILY", err.path("details").path("conflictingPeriod").asText(), "details.conflictingPeriod");
        assertAmount("100", err.path("details").path("conflictingAmount"), "details.conflictingAmount");
        assertTrue(Json.semanticEquals(before, Api.limitList(p)), "limits changed after a refusal");
    }

    @Test
    @Tag("LIM-13")
    @DisplayName("CL-033 DELETE for a period without a limit -> 404 NOT_FOUND")
    void deleteWithoutLimit() {
        long p = Api.createPlayer();
        assertError(Api.deleteLimit(p, "daily"), 404, "NOT_FOUND");
        Api.putLimitOk(p, "weekly", "100");
        assertError(Api.deleteLimit(p, "monthly"), 404, "NOT_FOUND");
        assertEquals(new BigDecimal("100").compareTo(Json.dec(Api.limit(p, "WEEKLY").get("amount"))), 0);
    }
}
