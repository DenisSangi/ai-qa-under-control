package com.arcade.apitests;

import com.arcade.apitests.support.Api.PlayerRef;
import com.arcade.apitests.support.Json;
import com.arcade.apitests.support.ProviderJournal;
import com.arcade.apitests.support.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static com.arcade.apitests.support.Api.balance;
import static com.arcade.apitests.support.Api.deposit;
import static com.arcade.apitests.support.Api.expect;
import static com.arcade.apitests.support.Api.expectError;
import static com.arcade.apitests.support.Api.limit;
import static com.arcade.apitests.support.Api.limits;
import static com.arcade.apitests.support.Api.newKey;
import static com.arcade.apitests.support.Api.newPlayer;
import static com.arcade.apitests.support.Api.playRound;
import static com.arcade.apitests.support.Api.removeLimit;
import static com.arcade.apitests.support.Api.setLimit;
import static com.arcade.apitests.support.Api.withdraw;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Responsible-gambling deposit limits. Cooling-off is 24h on the stand; the rolling windows are
 * 1, 7 and 30 days. Time-based rules are checked by moving the service clock forward.
 */
@DisplayName("Deposit limits")
class DepositLimitTest extends ApiTest {

    private static final Duration COOLING_OFF = Duration.ofHours(24);
    private static final long UNKNOWN = 9_000_000_000_000L;

    @Test
    @DisplayName("LIM-01 the first limit applies at once and counts deposits already made in the window")
    void firstLimitAppliesAtOnce() {
        PlayerRef player = newPlayer();
        assertThat(expect(limits(player.id()), 200).json()).isEmpty();
        expect(deposit(player.id(), "50.00", newKey()), 200);

        JsonNode set = set(player, "daily", "200");

        assertLimit(set, "DAILY", "200.00", "50.00", "150.00");
        assertThat(set.get("pending").isNull()).isTrue();
        JsonNode listed = expect(limits(player.id()), 200).json();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0)).isEqualTo(set);
    }

    @Test
    @DisplayName("LIM-02 a decrease applies at once; an increase waits 24h while the old amount keeps applying")
    void increaseIsPending() {
        PlayerRef player = newPlayer();
        set(player, "daily", "200.00");

        JsonNode decreased = set(player, "daily", "150.00");
        assertLimit(decreased, "DAILY", "150.00", "0.00", "150.00");
        assertThat(decreased.get("pending").isNull()).isTrue();

        Instant before = TestClock.now();
        JsonNode increased = set(player, "daily", "300.00");
        Instant after = TestClock.now();

        assertLimit(increased, "DAILY", "150.00", "0.00", "150.00");
        assertThat(Json.dec(increased.get("pending"), "amount")).isEqualByComparingTo("300.00");
        assertThat(pendingAt(increased)).isBetween(before.plus(COOLING_OFF), after.plus(COOLING_OFF));
        expectError(deposit(player.id(), "150.01", newKey()), 422, "DEPOSIT_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("LIM-03 a pending increase takes effect after the cooling-off period, not a minute before")
    void increaseTakesEffectAfterCoolingOff() {
        PlayerRef player = newPlayer();
        set(player, "daily", "100.00");
        set(player, "daily", "300.00");

        TestClock.advance(COOLING_OFF.minusMinutes(1));
        JsonNode stillPending = limit(player.id(), "DAILY");
        assertThat(Json.dec(stillPending, "amount")).isEqualByComparingTo("100.00");
        assertThat(stillPending.get("pending").isNull()).isFalse();
        expectError(deposit(player.id(), "150.00", newKey()), 422, "DEPOSIT_LIMIT_EXCEEDED");

        TestClock.advance(Duration.ofMinutes(2));
        JsonNode effective = limit(player.id(), "DAILY");
        assertLimit(effective, "DAILY", "300.00", "0.00", "300.00");
        assertThat(effective.get("pending").isNull()).isTrue();
        expect(deposit(player.id(), "250.00", newKey()), 200);
    }

    @Test
    @DisplayName("LIM-04 repeating a pending increase keeps its effectiveAt; another increase replaces it; the current amount cancels it")
    void pendingIncreaseRules() {
        PlayerRef player = newPlayer();
        set(player, "weekly", "100.00");
        Instant first = pendingAt(set(player, "weekly", "300.00"));

        TestClock.advance(Duration.ofHours(1));
        JsonNode repeated = set(player, "weekly", "300");
        assertThat(pendingAt(repeated)).isEqualTo(first);

        Instant before = TestClock.now();
        JsonNode replaced = set(player, "weekly", "400.00");
        assertThat(Json.dec(replaced.get("pending"), "amount")).isEqualByComparingTo("400.00");
        assertThat(pendingAt(replaced)).isAfterOrEqualTo(before.plus(COOLING_OFF));

        JsonNode cancelled = set(player, "weekly", "100.00");
        assertLimit(cancelled, "WEEKLY", "100.00", "0.00", "100.00");
        assertThat(cancelled.get("pending").isNull()).isTrue();
    }

    @Test
    @DisplayName("LIM-05 a removal waits 24h (repeating it keeps effectiveAt), then the limit is gone")
    void removalIsPending() {
        PlayerRef player = newPlayer();
        set(player, "weekly", "500.00");

        Instant before = TestClock.now();
        JsonNode removal = expect(removeLimit(player.id(), "weekly"), 200).json();
        Instant after = TestClock.now();

        assertLimit(removal, "WEEKLY", "500.00", "0.00", "500.00");
        assertThat(removal.get("pending").has("amount")).isTrue();
        assertThat(removal.get("pending").get("amount").isNull()).as("pending removal has no amount").isTrue();
        assertThat(pendingAt(removal)).isBetween(before.plus(COOLING_OFF), after.plus(COOLING_OFF));
        expectError(deposit(player.id(), "500.01", newKey()), 422, "DEPOSIT_LIMIT_EXCEEDED");

        TestClock.advance(Duration.ofHours(1));
        assertThat(pendingAt(expect(removeLimit(player.id(), "weekly"), 200).json())).isEqualTo(pendingAt(removal));

        TestClock.advance(COOLING_OFF.minusHours(1).plusMinutes(1));
        assertThat(limit(player.id(), "WEEKLY")).as("removed limit is no longer listed").isNull();
        expectError(removeLimit(player.id(), "weekly"), 404, "NOT_FOUND");
        expect(deposit(player.id(), "600.00", newKey()), 200);

        JsonNode again = set(player, "weekly", "1000.00");
        assertLimit(again, "WEEKLY", "1000.00", "600.00", "400.00");
        assertThat(again.get("pending").isNull()).as("a limit set after a removal is a first limit").isTrue();
    }

    @Test
    @DisplayName("LIM-03 a due pending change is applied by a deposit or a PUT on their own, without a GET first")
    void dueChangeAppliedLazily() {
        PlayerRef increased = newPlayer();
        set(increased, "daily", "100.00");
        set(increased, "daily", "300.00");
        PlayerRef removed = newPlayer();
        set(removed, "daily", "100.00");
        expect(removeLimit(removed.id(), "daily"), 200);
        PlayerRef resetAfterIncrease = newPlayer();
        set(resetAfterIncrease, "daily", "100.00");
        set(resetAfterIncrease, "daily", "300.00");

        TestClock.advance(COOLING_OFF.plusMinutes(1));

        expect(deposit(increased.id(), "250.00", newKey()), 200);
        expect(deposit(removed.id(), "500.00", newKey()), 200);
        JsonNode decreased = set(resetAfterIncrease, "daily", "200.00");
        assertLimit(decreased, "DAILY", "200.00", "0.00", "200.00");
        assertThat(decreased.get("pending").isNull()).as("200 is a decrease from the effective 300").isTrue();
    }

    @Test
    @DisplayName("LIM-05 removing a limit that was never set is 404")
    void removeMissingLimit() {
        PlayerRef player = newPlayer();
        expectError(removeLimit(player.id(), "monthly"), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("LIM-05 during a pending removal a decrease applies at once and an increase replaces the removal")
    void changesDuringPendingRemoval() {
        PlayerRef player = newPlayer();
        set(player, "daily", "200.00");
        expect(removeLimit(player.id(), "daily"), 200);

        JsonNode decreased = set(player, "daily", "150.00");
        assertLimit(decreased, "DAILY", "150.00", "0.00", "150.00");
        assertThat(decreased.get("pending").isNull()).isTrue();

        expect(removeLimit(player.id(), "daily"), 200);
        JsonNode increased = set(player, "daily", "300.00");
        assertThat(Json.dec(increased, "amount")).isEqualByComparingTo("150.00");
        assertThat(Json.dec(increased.get("pending"), "amount")).isEqualByComparingTo("300.00");

        JsonNode removal = expect(removeLimit(player.id(), "daily"), 200).json();
        assertThat(removal.get("pending").get("amount").isNull()).as("a removal replaces the pending increase").isTrue();
    }

    @Test
    @DisplayName("LIM-06 daily <= weekly <= monthly is enforced against current amounts")
    void orderAgainstCurrent() {
        PlayerRef player = newPlayer();
        set(player, "daily", "100.00");

        JsonNode error = expectError(setLimit(player.id(), "weekly", "99.99"), 422, "DEPOSIT_LIMIT_ORDER_VIOLATED");
        assertThat(error.path("details").path("period").asText()).isEqualTo("WEEKLY");
        assertThat(error.path("details").path("conflictingPeriod").asText()).isEqualTo("DAILY");
        assertThat(error.path("details").path("conflictingAmount").asText()).isEqualTo("100.00");

        set(player, "weekly", "100.00");
        JsonNode monthly = expectError(setLimit(player.id(), "monthly", "99.99"), 422, "DEPOSIT_LIMIT_ORDER_VIOLATED");
        assertThat(monthly.path("details").path("conflictingPeriod").asText()).isIn("DAILY", "WEEKLY");
        set(player, "monthly", "100.00");

        JsonNode daily = expectError(setLimit(player.id(), "daily", "100.01"), 422, "DEPOSIT_LIMIT_ORDER_VIOLATED");
        assertThat(daily.path("details").path("conflictingPeriod").asText()).isIn("WEEKLY", "MONTHLY");
        assertThat(limit(player.id(), "DAILY").get("pending").isNull()).as("refused increase is not scheduled").isTrue();
    }

    @Test
    @DisplayName("LIM-06 the order is also checked against a pending amount, and a pending removal still has its current amount")
    void orderAgainstPending() {
        PlayerRef player = newPlayer();
        set(player, "daily", "50.00");
        set(player, "weekly", "200.00");
        set(player, "daily", "150.00"); // pending increase

        JsonNode error = expectError(setLimit(player.id(), "weekly", "120.00"), 422, "DEPOSIT_LIMIT_ORDER_VIOLATED");
        assertThat(error.path("details").path("conflictingPeriod").asText()).isEqualTo("DAILY");
        assertThat(error.path("details").path("conflictingAmount").asText()).isEqualTo("150.00");
        set(player, "weekly", "150.00");

        PlayerRef other = newPlayer();
        set(other, "daily", "50.00");
        expect(removeLimit(other.id(), "daily"), 200);
        expectError(setLimit(other.id(), "weekly", "40.00"), 422, "DEPOSIT_LIMIT_ORDER_VIOLATED");
    }

    @ParameterizedTest(name = "limit {0} is out of range")
    @ValueSource(strings = {"9.99", "0", "-50.00", "1000000.01"})
    @DisplayName("LIM-07 a limit outside 10.00-1000000.00 is refused")
    void limitRange(String amount) {
        PlayerRef player = newPlayer();

        JsonNode error = expectError(setLimit(player.id(), "daily", amount), 422, "DEPOSIT_LIMIT_OUT_OF_RANGE");

        assertThat(error.path("details").path("min").asText()).isEqualTo("10.00");
        assertThat(error.path("details").path("max").asText()).isEqualTo("1000000.00");
        assertThat(expect(limits(player.id()), 200).json()).isEmpty();
    }

    @Test
    @DisplayName("LIM-07 the bounds 10.00 and 1000000.00 are accepted")
    void limitRangeBounds() {
        PlayerRef player = newPlayer();
        assertLimit(set(player, "daily", "10.00"), "DAILY", "10.00", "0.00", "10.00");
        assertLimit(set(player, "monthly", "1000000.00"), "MONTHLY", "1000000.00", "0.00", "1000000.00");
    }

    @Test
    @DisplayName("LIM-07 a malformed amount or an unknown period is a 400")
    void limitValidation() {
        PlayerRef player = newPlayer();
        assertThat(expectError(setLimit(player.id(), "daily", "100.001"), 400, "VALIDATION_ERROR")
                .path("details").has("amount")).isTrue();
        assertThat(expectError(setLimit(player.id(), "daily", "\"100\""), 400, "VALIDATION_ERROR")
                .path("details").has("amount")).isTrue();
        assertThat(expectError(setLimit(player.id(), "daily", "null"), 400, "VALIDATION_ERROR")
                .path("details").has("amount")).isTrue();
        assertThat(expectError(setLimit(player.id(), "yearly", "100.00"), 400, "VALIDATION_ERROR")
                .path("details").has("period")).isTrue();
        assertThat(expectError(removeLimit(player.id(), "yearly"), 400, "VALIDATION_ERROR")
                .path("details").has("period")).isTrue();
    }

    @Test
    @DisplayName("LIM-08 a deposit may use exactly the remaining amount; one cent more is refused before the provider is called")
    void enforcement() {
        PlayerRef player = newPlayer();
        set(player, "daily", "100.00");
        expect(deposit(player.id(), "60.00", newKey()), 200);

        JsonNode error = expectError(deposit(player.id(), "40.01", newKey()), 422, "DEPOSIT_LIMIT_EXCEEDED");
        assertThat(error.path("details").path("period").asText()).isEqualTo("DAILY");
        assertThat(error.path("details").path("limit").asText()).isEqualTo("100.00");
        assertThat(error.path("details").path("remaining").asText()).isEqualTo("40.00");
        assertThat(ProviderJournal.calls(ProviderJournal.PAYMENTS, player.id())).hasSize(1);

        expect(deposit(player.id(), "40.00", newKey()), 200);
        JsonNode exhausted = expectError(deposit(player.id(), "10.00", newKey()), 422, "DEPOSIT_LIMIT_EXCEEDED");
        assertThat(exhausted.path("details").path("remaining").asText()).isEqualTo("0.00");

        assertLimit(limit(player.id(), "DAILY"), "DAILY", "100.00", "100.00", "0.00");
        assertThat(balance(player.id())).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("LIM-08 a decrease below what is already used leaves remaining at 0.00, never negative")
    void remainingNeverNegative() {
        PlayerRef player = newPlayer();
        expect(deposit(player.id(), "150.00", newKey()), 200);

        assertLimit(set(player, "daily", "100.00"), "DAILY", "100.00", "150.00", "0.00");
        JsonNode error = expectError(deposit(player.id(), "10.00", newKey()), 422, "DEPOSIT_LIMIT_EXCEEDED");
        assertThat(error.path("details").path("remaining").asText()).isEqualTo("0.00");
    }

    @Test
    @DisplayName("LIM-09 the most restrictive limit is the one reported")
    void mostRestrictiveLimit() {
        PlayerRef player = newPlayer();
        set(player, "daily", "100.00");
        set(player, "weekly", "150.00");
        set(player, "monthly", "1000.00");
        expect(deposit(player.id(), "100.00", newKey()), 200);

        TestClock.advance(Duration.ofDays(1).plusMinutes(1));
        assertLimit(limit(player.id(), "DAILY"), "DAILY", "100.00", "0.00", "100.00");
        assertLimit(limit(player.id(), "WEEKLY"), "WEEKLY", "150.00", "100.00", "50.00");

        JsonNode error = expectError(deposit(player.id(), "60.00", newKey()), 422, "DEPOSIT_LIMIT_EXCEEDED");
        assertThat(error.path("details").path("period").asText()).isEqualTo("WEEKLY");
        assertThat(error.path("details").path("limit").asText()).isEqualTo("150.00");
        assertThat(error.path("details").path("remaining").asText()).isEqualTo("50.00");
        expect(deposit(player.id(), "50.00", newKey()), 200);
    }

    @Test
    @DisplayName("LIM-09 on a tie the shortest period is reported")
    void tieReportsShortestPeriod() {
        PlayerRef player = newPlayer();
        set(player, "daily", "100.00");
        set(player, "weekly", "100.00");
        set(player, "monthly", "100.00");

        JsonNode error = expectError(deposit(player.id(), "150.00", newKey()), 422, "DEPOSIT_LIMIT_EXCEEDED");
        assertThat(error.path("details").path("period").asText()).isEqualTo("DAILY");
        assertThat(error.path("details").path("remaining").asText()).isEqualTo("100.00");
    }

    @ParameterizedTest(name = "{0}: window {1}")
    @CsvSource({"daily, P1D", "weekly, P7D", "monthly, P30D"})
    @DisplayName("LIM-10 a deposit counts for exactly its rolling window (boundary +-1 minute)")
    void rollingWindow(String period, Duration window) {
        PlayerRef player = newPlayer();
        set(player, period, "100.00");
        expect(deposit(player.id(), "100.00", newKey()), 200);

        TestClock.advance(window.minusMinutes(1));
        JsonNode error = expectError(deposit(player.id(), "10.00", newKey()), 422, "DEPOSIT_LIMIT_EXCEEDED");
        assertThat(error.path("details").path("period").asText()).isEqualTo(period.toUpperCase());
        assertThat(Json.dec(limit(player.id(), period.toUpperCase()), "used")).isEqualByComparingTo("100.00");

        TestClock.advance(Duration.ofMinutes(2));
        assertThat(Json.dec(limit(player.id(), period.toUpperCase()), "used")).isEqualByComparingTo("0.00");
        expect(deposit(player.id(), "100.00", newKey()), 200);
    }

    @Test
    @DisplayName("LIM-11 withdrawals do not free the limit")
    void withdrawalsDoNotFreeLimit() {
        PlayerRef player = newPlayer();
        set(player, "daily", "200.00");
        expect(deposit(player.id(), "150.00", newKey()), 200);
        expect(withdraw(player.id(), "100.00", newKey()), 200);

        assertLimit(limit(player.id(), "DAILY"), "DAILY", "200.00", "150.00", "50.00");
        expectError(deposit(player.id(), "50.01", newKey()), 422, "DEPOSIT_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("LIM-11 wins and refunds credit the balance but do not use the limit")
    void winsDoNotUseLimit() {
        PlayerRef player = newPlayer();
        set(player, "daily", "100.00");
        expect(deposit(player.id(), "50.00", newKey()), 200);

        playRound(player.id(), "10.00", "200.00");

        assertLimit(limit(player.id(), "DAILY"), "DAILY", "100.00", "50.00", "50.00");
        assertThat(balance(player.id())).isEqualByComparingTo("240.00");
    }

    @Test
    @DisplayName("LIM-11 a deposit declined by the provider does not use the limit")
    void declinedDepositDoesNotUseLimit() {
        PlayerRef player = newPlayer();
        set(player, "daily", "20000.00");

        expectError(deposit(player.id(), "10000.01", newKey()), 422, "PAYMENT_DECLINED");

        assertLimit(limit(player.id(), "DAILY"), "DAILY", "20000.00", "0.00", "20000.00");
    }

    @Test
    @DisplayName("LIM-12 limits of an unknown player are 404")
    void unknownPlayer() {
        expectError(limits(UNKNOWN), 404, "NOT_FOUND");
        expectError(setLimit(UNKNOWN, "daily", "100.00"), 404, "NOT_FOUND");
        expectError(removeLimit(UNKNOWN, "daily"), 404, "NOT_FOUND");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static JsonNode set(PlayerRef player, String period, String amount) {
        return expect(setLimit(player.id(), period, amount), 200).json();
    }

    private static Instant pendingAt(JsonNode limit) {
        assertThat(limit.get("pending").isNull()).as("pending change of %s", limit).isFalse();
        return Json.instant(limit.get("pending"), "effectiveAt");
    }

    private static void assertLimit(JsonNode limit, String period, String amount, String used, String remaining) {
        assertThat(limit).as("limit %s", period).isNotNull();
        assertThat(Json.text(limit, "period")).isEqualTo(period);
        assertThat(Json.dec(limit, "amount")).as("amount of %s", limit).isEqualByComparingTo(amount);
        assertThat(Json.dec(limit, "used")).as("used of %s", limit).isEqualByComparingTo(used);
        assertThat(Json.dec(limit, "remaining")).as("remaining of %s", limit).isEqualByComparingTo(remaining);
    }
}
