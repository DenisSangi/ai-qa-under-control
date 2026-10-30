package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Config;
import com.arcade.qa.support.Json;
import com.arcade.qa.support.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertMinMax;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Order(7)
@DisplayName("F-02 Deposit limits: rules and lifecycle")
class DepositLimitsTest extends ApiTest {

    private static final Duration COOLING_OFF = Duration.ofHours(24);
    private static final Duration TOLERANCE = Duration.ofSeconds(5);

    /** {@code pending.effectiveAt} must be within [before, after] + 24 h (± tolerance). */
    private static Instant assertEffectiveAt(JsonNode limit, Instant before, Instant after) {
        JsonNode pending = limit.path("pending");
        assertTrue(pending.isObject(), () -> "pending change expected: " + limit);
        Instant eff = Instant.parse(pending.path("effectiveAt").asText());
        Instant lo = before.plus(COOLING_OFF).minus(TOLERANCE);
        Instant hi = after.plus(COOLING_OFF).plus(TOLERANCE);
        assertTrue(!eff.isBefore(lo) && !eff.isAfter(hi),
                () -> "effectiveAt " + eff + " must be request time + 24h, i.e. in [" + lo + ", " + hi + "]");
        return eff;
    }

    private static void assertNoPending(JsonNode limit) {
        assertTrue(limit.path("pending").isNull(), () -> "pending must be null: " + limit);
    }

    private static void assertPendingAmount(JsonNode limit, String amount) {
        if (amount == null) {
            assertTrue(limit.path("pending").has("amount") && limit.path("pending").path("amount").isNull(),
                    () -> "pending removal (amount null) expected: " + limit);
        } else {
            assertAmount(amount, limit.path("pending").path("amount"), "pending.amount");
        }
    }

    private static Instant effectiveAt(JsonNode limit) {
        return Instant.parse(limit.path("pending").path("effectiveAt").asText());
    }

    /** The list items by period: LIM-02 fixes which items the list has, not their order. */
    private static Map<String, JsonNode> byPeriod(JsonNode list) {
        Map<String, JsonNode> out = new HashMap<>();
        list.forEach(item -> out.put(item.path("period").asText(), item));
        return out;
    }

    /** The same periods are listed, each with the same current amount and the same pending change (amount, effectiveAt). */
    private static void assertLimitsUnchanged(JsonNode before, JsonNode after) {
        Map<String, JsonNode> b = byPeriod(before);
        Map<String, JsonNode> a = byPeriod(after);
        assertEquals(before.size(), after.size(), () -> "limits changed after a refusal: " + before + " -> " + after);
        assertEquals(b.keySet(), a.keySet(), () -> "limits changed after a refusal: " + before + " -> " + after);
        for (String period : b.keySet()) {
            JsonNode x = b.get(period);
            JsonNode y = a.get(period);
            String what = period + " changed after a refusal: " + x + " -> " + y;
            assertAmount(Json.dec(x.get("amount")), Json.dec(y.get("amount")), what);
            JsonNode px = x.path("pending");
            JsonNode py = y.path("pending");
            assertEquals(px.isNull(), py.isNull(), what);
            if (!px.isNull()) {
                assertTrue(Json.sameValue(Json.dec(px.get("amount")), Json.dec(py.get("amount"))), what);
                assertEquals(Json.instant(px.get("effectiveAt")), Json.instant(py.get("effectiveAt")), what);
            }
        }
    }

    @Test
    @Tag("LIM-01")
    @DisplayName("CL-012 period path must be daily|weekly|monthly in lower case: DAILY, Daily, yearly -> 400")
    void periodPathValidated() {
        long p = Api.createPlayer();
        for (String period : new String[]{"DAILY", "Daily", "yearly"}) {
            assertError(Api.putLimit(p, period, "100"), 400, "VALIDATION_ERROR");
            assertError(Api.deleteLimit(p, period), 400, "VALIDATION_ERROR");
        }
        assertEquals(0, Api.limitList(p).size(), "nothing was set");
        for (String period : new String[]{"daily", "weekly", "monthly"}) {
            assertStatus(Api.putLimit(p, period, "100"), 200);
        }
    }

    @Test
    @Tag("LIM-02")
    @DisplayName("CL-013 player without limits gets 200 and an empty list")
    void emptyList() {
        long p = Api.createPlayer();
        Response r = Api.limits(p);
        assertStatus(r, 200);
        JsonNode list = Api.body(r);
        assertTrue(list.isArray() && list.isEmpty(), () -> "empty list expected: " + list);
    }

    @Test
    @Tag("LIM-02")
    @DisplayName("CL-014 list item {period, amount, used, remaining, pending}: upper-case period, only periods with a limit")
    void listFormat() {
        long p = Api.createPlayer();
        JsonNode put = Api.putLimitOk(p, "weekly", "300");
        assertEquals("WEEKLY", put.path("period").asText(), "period in the PUT response");
        JsonNode list = Api.limitList(p);
        assertEquals(1, list.size(), () -> "only WEEKLY must be listed: " + list);
        JsonNode w = list.get(0);
        for (String f : new String[]{"period", "amount", "used", "remaining", "pending"}) {
            assertTrue(w.has(f), () -> "field " + f + " missing: " + w);
        }
        assertEquals("WEEKLY", w.path("period").asText());
        assertAmount("300", w.get("amount"), "amount");
        assertAmount("0", w.get("used"), "used");
        assertAmount("300", w.get("remaining"), "remaining");
        assertNoPending(w);
        Api.putLimitOk(p, "daily", "100");
        Api.putLimitOk(p, "monthly", "500");
        Set<String> periods = new HashSet<>();
        Api.limitList(p).forEach(i -> periods.add(i.path("period").asText()));
        assertEquals(Set.of("DAILY", "WEEKLY", "MONTHLY"), periods);
    }

    @Test
    @Tag("LIM-02")
    @DisplayName("CL-015 used = counted deposits, remaining = amount - used")
    void usedAndRemaining() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Api.putLimitOk(p, "monthly", "1000");
        Api.depositOk(p, "30");
        Api.depositOk(p, "12.50");
        JsonNode d = Api.limit(p, "DAILY");
        assertAmount("42.50", d.get("used"), "daily used");
        assertAmount("57.50", d.get("remaining"), "daily remaining");
        JsonNode m = Api.limit(p, "MONTHLY");
        assertAmount("42.50", m.get("used"), "monthly used");
        assertAmount("957.50", m.get("remaining"), "monthly remaining");
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
        assertAmount("0", d.get("remaining"), "remaining");
    }

    @Test
    @Tag("LIM-03")
    @DisplayName("CL-017 limit bounds 10.00 and 1000000.00 are accepted")
    void limitBoundsAccepted() {
        long a = Api.createPlayer();
        assertAmount("10.00", Api.putLimitOk(a, "daily", "10.00").get("amount"), "daily amount");
        long b = Api.createPlayer();
        assertAmount("1000000.00", Api.putLimitOk(b, "monthly", "1000000.00").get("amount"), "monthly amount");
    }

    @ParameterizedTest(name = "amount {0}")
    @ValueSource(strings = {"9.99", "1000000.01"})
    @Tag("LIM-03")
    @DisplayName("CL-018 limit out of range: 422 DEPOSIT_LIMIT_OUT_OF_RANGE, current and pending values unchanged")
    void limitOutOfRange(String amount) {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Api.putLimitOk(p, "daily", "200"); // pending increase
        JsonNode before = Api.limitList(p);
        JsonNode err = assertError(Api.putLimit(p, "daily", amount), 422, "DEPOSIT_LIMIT_OUT_OF_RANGE");
        assertMinMax(err, "10.00", "1000000.00");
        assertError(Api.putLimit(p, "weekly", amount), 422, "DEPOSIT_LIMIT_OUT_OF_RANGE");
        assertLimitsUnchanged(before, Api.limitList(p));
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
        assertLimitsUnchanged(before, Api.limitList(p));
    }

    @Test
    @Tag("LIM-04")
    @DisplayName("CL-020 spec example: daily 100 with pending 250, weekly 300 -> weekly 200 refused (conflict DAILY 250)")
    void orderExampleTwo() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "weekly", "300");
        Api.putLimitOk(p, "daily", "100");
        assertPendingAmount(Api.putLimitOk(p, "daily", "250"), "250");
        JsonNode before = Api.limitList(p);
        JsonNode err = assertError(Api.putLimit(p, "weekly", "200"), 422, "DEPOSIT_LIMIT_ORDER_VIOLATED");
        assertEquals("WEEKLY", err.path("details").path("period").asText(), "details.period");
        assertEquals("DAILY", err.path("details").path("conflictingPeriod").asText(), "details.conflictingPeriod");
        assertAmount("250", err.path("details").path("conflictingAmount"), "details.conflictingAmount");
        assertLimitsUnchanged(before, Api.limitList(p));
    }

    @Test
    @Tag("LIM-04")
    @DisplayName("CL-021 a value above a longer period's value is refused (daily > weekly; weekly > monthly current)")
    void shorterAboveLongerRefused() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "weekly", "300");
        JsonNode err = assertError(Api.putLimit(p, "daily", "301"), 422, "DEPOSIT_LIMIT_ORDER_VIOLATED");
        assertEquals("DAILY", err.path("details").path("period").asText(), "details.period");
        assertEquals("WEEKLY", err.path("details").path("conflictingPeriod").asText(), "details.conflictingPeriod");
        assertAmount("300", err.path("details").path("conflictingAmount"), "details.conflictingAmount");
        assertStatus(Api.putLimit(p, "daily", "300"), 200); // equal is allowed

        long q = Api.createPlayer();
        Api.putLimitOk(q, "monthly", "500");
        assertPendingAmount(Api.putLimitOk(q, "monthly", "1000"), "1000");
        // 800 is below the pending 1000 but above the current 500: still refused.
        JsonNode err2 = assertError(Api.putLimit(q, "weekly", "800"), 422, "DEPOSIT_LIMIT_ORDER_VIOLATED");
        assertEquals("MONTHLY", err2.path("details").path("conflictingPeriod").asText(), "details.conflictingPeriod");
        assertAmount("500", err2.path("details").path("conflictingAmount"), "details.conflictingAmount");
        assertNull(Api.limit(q, "WEEKLY"), "no weekly limit was created");
    }

    @Test
    @Tag("LIM-04")
    @DisplayName("CL-022 periods without a limit and pending removals take no part in the comparison")
    void noLimitAndPendingRemovalIgnored() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "500");
        assertStatus(Api.putLimit(p, "monthly", "600"), 200); // weekly has no limit: not compared

        long q = Api.createPlayer();
        Api.putLimitOk(q, "weekly", "300");
        assertStatus(Api.deleteLimit(q, "weekly"), 200); // pending removal
        // The pending removal (amount null) must not act as a value: 100 <= current weekly 300.
        assertStatus(Api.putLimit(q, "daily", "100"), 200);
        assertStatus(Api.putLimit(q, "monthly", "400"), 200);
    }

    @Test
    @Tag("LIM-05")
    @DisplayName("CL-023 order of checks: 400 before 404, 404 before range, range before period order")
    void orderOfChecks() {
        assertError(Api.putLimit(Config.UNKNOWN_ID, "DAILY", "100"), 400, "VALIDATION_ERROR");
        assertError(Api.putLimit(Config.UNKNOWN_ID, "daily", "\"100\""), 400, "VALIDATION_ERROR");
        assertError(Api.putLimit(Config.UNKNOWN_ID, "daily", "5"), 404, "NOT_FOUND");
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        assertError(Api.putLimit(p, "weekly", "5"), 422, "DEPOSIT_LIMIT_OUT_OF_RANGE");
    }

    @Test
    @Tag("LIM-06")
    @DisplayName("CL-024 a first limit and a lower amount take effect immediately")
    void immediateChanges() {
        long p = Api.createPlayer();
        JsonNode first = Api.putLimitOk(p, "daily", "100");
        assertAmount("100", first.get("amount"), "amount");
        assertNoPending(first);
        JsonNode lower = Api.putLimitOk(p, "daily", "60");
        assertAmount("60", lower.get("amount"), "amount");
        assertNoPending(lower);
        assertAmount("60", Api.limit(p, "DAILY").get("amount"), "listed amount");
        Api.depositOk(p, "60");
        assertError(Api.deposit(p, "10"), 422, "DEPOSIT_LIMIT_EXCEEDED");
    }

    @Test
    @Tag("LIM-06")
    @DisplayName("CL-025 a lower amount discards a pending increase")
    void lowerDiscardsPending() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Api.putLimitOk(p, "daily", "200");
        JsonNode lower = Api.putLimitOk(p, "daily", "80");
        assertAmount("80", lower.get("amount"), "amount");
        assertNoPending(lower);
        assertNoPending(Api.limit(p, "DAILY"));
    }

    @Test
    @Tag("LIM-07")
    @DisplayName("CL-026 increase: amount unchanged, pending.amount new, effectiveAt = request time + 24h")
    void increaseIsPending() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Instant before = TestClock.now();
        JsonNode r = Api.putLimitOk(p, "daily", "200");
        Instant after = TestClock.now();
        assertAmount("100", r.get("amount"), "amount");
        assertPendingAmount(r, "200");
        assertEffectiveAt(r, before, after);
        JsonNode listed = Api.limit(p, "DAILY");
        assertAmount("100", listed.get("amount"), "listed amount");
        assertPendingAmount(listed, "200");
    }

    @Test
    @Tag("LIM-07")
    @DisplayName("CL-027 before effectiveAt the current amount applies, from effectiveAt the new one")
    void increaseAppliesAtEffectiveAt() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Instant eff = effectiveAt(Api.putLimitOk(p, "daily", "200"));
        assertError(Api.deposit(p, "150"), 422, "DEPOSIT_LIMIT_EXCEEDED");
        TestClock.advanceTo(eff.minusSeconds(60));
        JsonNode stillPending = Api.limit(p, "DAILY");
        assertAmount("100", stillPending.get("amount"), "amount 1 minute before effectiveAt");
        assertPendingAmount(stillPending, "200");
        assertError(Api.deposit(p, "150"), 422, "DEPOSIT_LIMIT_EXCEEDED");
        TestClock.advanceTo(eff.plusSeconds(1));
        JsonNode applied = Api.limit(p, "DAILY");
        assertNotNull(applied, "limit must still exist");
        assertAmount("200", applied.get("amount"), "amount after effectiveAt");
        assertNoPending(applied);
        assertStatus(Api.deposit(p, "150"), 200);
    }

    @Test
    @Tag("LIM-08")
    @DisplayName("CL-028 repeating the same increase later changes nothing (effectiveAt unchanged)")
    void repeatedIncrease() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Instant eff = effectiveAt(Api.putLimitOk(p, "daily", "200"));
        TestClock.advance(Duration.ofHours(1));
        JsonNode again = Api.putLimitOk(p, "daily", "200");
        assertAmount("100", again.get("amount"), "amount");
        assertPendingAmount(again, "200");
        assertEquals(eff, effectiveAt(again), "effectiveAt must stay the same");
    }

    @Test
    @Tag("LIM-08")
    @DisplayName("CL-029 a different increase replaces the pending one and restarts the cooling-off")
    void replacingIncrease() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Api.putLimitOk(p, "daily", "200");
        TestClock.advance(Duration.ofHours(1));
        Instant before = TestClock.now();
        JsonNode r = Api.putLimitOk(p, "daily", "300");
        Instant after = TestClock.now();
        assertAmount("100", r.get("amount"), "amount");
        assertPendingAmount(r, "300");
        assertEffectiveAt(r, before, after);
    }

    @Test
    @Tag("LIM-08")
    @DisplayName("CL-030 repeated removal keeps effectiveAt; increase replaces a pending removal and vice versa")
    void removalReplacements() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Response del = Api.deleteLimit(p, "daily");
        assertStatus(del, 200);
        Instant eff = effectiveAt(Api.body(del));
        TestClock.advance(Duration.ofHours(1));
        Response again = Api.deleteLimit(p, "daily");
        assertStatus(again, 200);
        assertPendingAmount(Api.body(again), null);
        assertEquals(eff, effectiveAt(Api.body(again)), "repeated removal must keep effectiveAt");

        Instant before = TestClock.now();
        JsonNode inc = Api.putLimitOk(p, "daily", "200");
        Instant after = TestClock.now();
        assertPendingAmount(inc, "200");
        assertEffectiveAt(inc, before, after);

        TestClock.advance(Duration.ofHours(1));
        before = TestClock.now();
        Response del2 = Api.deleteLimit(p, "daily");
        after = TestClock.now();
        assertStatus(del2, 200);
        assertPendingAmount(Api.body(del2), null);
        assertEffectiveAt(Api.body(del2), before, after);
        assertAmount("100", Api.limit(p, "DAILY").get("amount"), "amount stays until effectiveAt");
    }

    @Test
    @Tag("LIM-09")
    @DisplayName("CL-031 PUT with the current amount cancels a pending increase and a pending removal")
    void cancelPending() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Api.putLimitOk(p, "daily", "200");
        JsonNode c1 = Api.putLimitOk(p, "daily", "100");
        assertAmount("100", c1.get("amount"), "amount");
        assertNoPending(c1);
        assertStatus(Api.deleteLimit(p, "daily"), 200);
        JsonNode c2 = Api.putLimitOk(p, "daily", "100");
        assertAmount("100", c2.get("amount"), "amount");
        assertNoPending(c2);
        assertNoPending(Api.limit(p, "DAILY"));
    }

    @Test
    @Tag("LIM-10")
    @DisplayName("CL-032 DELETE: pending removal +24h; limit applies until effectiveAt; afterwards not listed, not limiting")
    void removalLifecycle() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "100");
        Instant before = TestClock.now();
        Response del = Api.deleteLimit(p, "daily");
        Instant after = TestClock.now();
        assertStatus(del, 200);
        JsonNode body = Api.body(del);
        assertAmount("100", body.get("amount"), "amount until effectiveAt");
        assertPendingAmount(body, null);
        Instant eff = assertEffectiveAt(body, before, after);
        assertError(Api.deposit(p, "150"), 422, "DEPOSIT_LIMIT_EXCEEDED");
        TestClock.advanceTo(eff.minusSeconds(60));
        assertNotNull(Api.limit(p, "DAILY"), "the limit is listed until effectiveAt");
        assertError(Api.deposit(p, "150"), 422, "DEPOSIT_LIMIT_EXCEEDED");
        TestClock.advanceTo(eff.plusSeconds(1));
        assertNull(Api.limit(p, "DAILY"), "the period must not be listed after effectiveAt");
        assertStatus(Api.deposit(p, "150"), 200);
    }

    @Test
    @Tag("LIM-10")
    @DisplayName("CL-033 DELETE for a period without a limit -> 404 NOT_FOUND")
    void deleteWithoutLimit() {
        long p = Api.createPlayer();
        assertError(Api.deleteLimit(p, "daily"), 404, "NOT_FOUND");
        Api.putLimitOk(p, "weekly", "100");
        assertError(Api.deleteLimit(p, "monthly"), 404, "NOT_FOUND");
        assertEquals(new BigDecimal("100").compareTo(Json.dec(Api.limit(p, "WEEKLY").get("amount"))), 0);

        // A period whose limit was removed (the removal has taken effect) is a period without a limit too.
        long q = Api.createPlayer();
        Api.putLimitOk(q, "daily", "100");
        Response del = Api.deleteLimit(q, "daily");
        assertStatus(del, 200);
        TestClock.advanceTo(effectiveAt(Api.body(del)).plusSeconds(1));
        assertNull(Api.limit(q, "DAILY"), "precondition: the removal took effect");
        assertError(Api.deleteLimit(q, "daily"), 404, "NOT_FOUND");
    }
}
