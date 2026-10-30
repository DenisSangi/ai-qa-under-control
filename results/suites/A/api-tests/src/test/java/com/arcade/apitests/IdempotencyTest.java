package com.arcade.apitests;

import com.arcade.apitests.support.Api.PlayerRef;
import com.arcade.apitests.support.Db;
import com.arcade.apitests.support.Http;
import com.arcade.apitests.support.Json;
import com.arcade.apitests.support.ProviderJournal;
import com.arcade.apitests.support.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static com.arcade.apitests.support.Api.balance;
import static com.arcade.apitests.support.Api.deposit;
import static com.arcade.apitests.support.Api.depositRaw;
import static com.arcade.apitests.support.Api.expect;
import static com.arcade.apitests.support.Api.expectError;
import static com.arcade.apitests.support.Api.newKey;
import static com.arcade.apitests.support.Api.newPlayer;
import static com.arcade.apitests.support.Api.setLimit;
import static com.arcade.apitests.support.Api.withdraw;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Idempotency-Key")
class IdempotencyTest extends ApiTest {

    private static final String REPLAYED = "Idempotent-Replayed";

    @Test
    @DisplayName("IDEM-01 a repeated deposit (50 = 50.00) is replayed: same body, no second charge, ledger row or balance change")
    void replaysDeposit() {
        PlayerRef player = newPlayer();
        String key = newKey();

        Http.Resp first = expect(deposit(player.id(), "50", key), 200);
        Http.Resp again = expect(deposit(player.id(), "50.00", key), 200);

        assertThat(first.header(REPLAYED)).as("first response is not a replay").isNull();
        assertThat(again.header(REPLAYED)).isEqualTo("true");
        assertThat(again.json()).isEqualTo(first.json());
        assertThat(balance(player.id())).isEqualByComparingTo("50.00");
        assertThat(Db.transactions(player.id(), "DEPOSIT")).hasSize(1);
        assertThat(ProviderJournal.calls(ProviderJournal.PAYMENTS, player.id())).hasSize(1);

        Db.IdempotencyRow row = Db.idempotencyKey(player.id(), key);
        assertThat(row).isNotNull();
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.operation()).isEqualTo("DEPOSIT");
        assertThat(Json.parse(row.responseBody())).isEqualTo(first.json());
    }

    @Test
    @DisplayName("IDEM-01 a repeated withdrawal is replayed and pays out once")
    void replaysWithdrawal() {
        PlayerRef player = newPlayer("100.00");
        String key = newKey();

        Http.Resp first = expect(withdraw(player.id(), "40.00", key), 200);
        Http.Resp again = expect(withdraw(player.id(), "40", key), 200);

        assertThat(again.header(REPLAYED)).isEqualTo("true");
        assertThat(again.json()).isEqualTo(first.json());
        assertThat(balance(player.id())).isEqualByComparingTo("60.00");
        assertThat(Db.transactions(player.id(), "WITHDRAWAL")).hasSize(1);
        assertThat(ProviderJournal.calls(ProviderJournal.PAYOUTS, player.id())).hasSize(1);

        // still a replay once the balance could no longer cover it
        expect(withdraw(player.id(), "60.00", newKey()), 200);
        Http.Resp late = expect(withdraw(player.id(), "40.00", key), 200);
        assertThat(late.header(REPLAYED)).isEqualTo("true");
        assertThat(balance(player.id())).isZero();
    }

    @Test
    @DisplayName("IDEM-02 a replay returns the original response even after the balance changed")
    void replayReturnsOriginalResponse() {
        PlayerRef player = newPlayer();
        String key = newKey();
        expect(deposit(player.id(), "50.00", key), 200);
        expect(deposit(player.id(), "30.00", newKey()), 200);

        Http.Resp replay = expect(deposit(player.id(), "50.00", key), 200);

        assertThat(replay.header(REPLAYED)).isEqualTo("true");
        assertThat(Json.dec(replay.json(), "balance")).isEqualByComparingTo("50.00");
        assertThat(balance(player.id())).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("IDEM-03 a key used for a different amount or operation is IDEMPOTENCY_KEY_REUSED")
    void refusesReusedKey() {
        PlayerRef player = newPlayer();
        String key = newKey();
        expect(deposit(player.id(), "50.00", key), 200);

        expectError(deposit(player.id(), "50.01", key), 422, "IDEMPOTENCY_KEY_REUSED");
        // the key is checked before the amount range
        expectError(deposit(player.id(), "5.00", key), 422, "IDEMPOTENCY_KEY_REUSED");
        expectError(withdraw(player.id(), "50.00", key), 422, "IDEMPOTENCY_KEY_REUSED");

        assertThat(balance(player.id())).isEqualByComparingTo("50.00");
        assertThat(ProviderJournal.calls(ProviderJournal.PAYMENTS, player.id())).hasSize(1);
        assertThat(ProviderJournal.calls(ProviderJournal.PAYOUTS, player.id())).isEmpty();
    }

    static Stream<Arguments> invalidKeys() {
        return Stream.of(
                Arguments.of("missing", null, "IDEMPOTENCY_KEY_REQUIRED"),
                Arguments.of("blank", "   ", "IDEMPOTENCY_KEY_REQUIRED"),
                Arguments.of("65 characters", "k".repeat(65), "IDEMPOTENCY_KEY_INVALID"));
    }

    @ParameterizedTest(name = "key bytes ''{0}''")
    // Latin-1 byte E9, and the UTF-8 bytes C3 A9 of the same letter, both written as raw header bytes.
    @ValueSource(strings = {"key-é", "key-Ã©"})
    @DisplayName("IDEM-04 a key with non-ASCII bytes is IDEMPOTENCY_KEY_INVALID")
    void refusesNonAsciiKey(String key) {
        PlayerRef player = newPlayer("20.00");
        String body = "{\"amount\":10.00}";

        expectError(Http.raw("POST", "/api/players/" + player.id() + "/deposit", body, Map.of("Idempotency-Key", key)),
                400, "IDEMPOTENCY_KEY_INVALID");
        expectError(Http.raw("POST", "/api/players/" + player.id() + "/withdraw", body, Map.of("Idempotency-Key", key)),
                400, "IDEMPOTENCY_KEY_INVALID");

        assertThat(balance(player.id())).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("IDEM-01 keys do not expire: a replay two days later is still a replay")
    void keysDoNotExpire() {
        PlayerRef player = newPlayer();
        String key = newKey();
        Http.Resp first = expect(deposit(player.id(), "15.00", key), 200);

        TestClock.advance(Duration.ofDays(2));
        Http.Resp later = expect(deposit(player.id(), "15.00", key), 200);

        assertThat(later.header(REPLAYED)).isEqualTo("true");
        assertThat(later.json()).isEqualTo(first.json());
        assertThat(balance(player.id())).isEqualByComparingTo("15.00");
    }

    @ParameterizedTest(name = "{0} key -> {2}")
    @MethodSource("invalidKeys")
    @DisplayName("IDEM-04 a missing or invalid key is refused before anything happens")
    void refusesInvalidKey(String name, String key, String code) {
        PlayerRef player = newPlayer("20.00");

        expectError(deposit(player.id(), "10.00", key), 400, code);
        expectError(withdraw(player.id(), "10.00", key), 400, code);

        assertThat(balance(player.id())).isEqualByComparingTo("20.00");
        assertThat(ProviderJournal.calls(ProviderJournal.PAYMENTS, player.id())).hasSize(1);
        assertThat(ProviderJournal.calls(ProviderJournal.PAYOUTS, player.id())).isEmpty();
    }

    @Test
    @DisplayName("IDEM-04 a key of exactly 64 printable ASCII characters is accepted")
    void acceptsLongestKey() {
        PlayerRef player = newPlayer();
        String random = java.util.UUID.randomUUID().toString().replace("-", "");
        String key = ("k !\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~" + random).substring(0, 64);
        assertThat(key).hasSize(64);

        expect(deposit(player.id(), "10.00", key), 200);
        expect(deposit(player.id(), "10.00", key), 200);

        assertThat(balance(player.id())).isEqualByComparingTo("10.00");
        assertThat(Db.idempotencyKey(player.id(), key)).isNotNull();
    }

    @Test
    @DisplayName("IDEM-05 keys are scoped per player and the provider reference differs between players")
    void keysArePerPlayer() {
        PlayerRef a = newPlayer();
        PlayerRef b = newPlayer();
        String key = newKey();

        Http.Resp forA = expect(deposit(a.id(), "25.00", key), 200);
        Http.Resp forB = expect(deposit(b.id(), "25.00", key), 200);

        assertThat(forB.header(REPLAYED)).as("another player's key is not a replay").isNull();
        assertThat(forB.json().get("id").asLong()).isEqualTo(b.id());
        assertThat(forA.json().get("id").asLong()).isEqualTo(a.id());
        assertThat(balance(a.id())).isEqualByComparingTo("25.00");
        assertThat(balance(b.id())).isEqualByComparingTo("25.00");
        String refA = ProviderJournal.calls(ProviderJournal.PAYMENTS, a.id()).get(0).reference();
        String refB = ProviderJournal.calls(ProviderJournal.PAYMENTS, b.id()).get(0).reference();
        assertThat(refA).isNotEqualTo(refB);

        expect(deposit(a.id(), "25.00", newKey()), 200);
        var callsOfA = ProviderJournal.calls(ProviderJournal.PAYMENTS, a.id());
        assertThat(callsOfA).hasSize(2);
        assertThat(callsOfA.get(1).reference()).as("another key of the same player").isNotEqualTo(refA);
    }

    @Test
    @DisplayName("IDEM-06 a key whose deposit was out of range is released and can be used again")
    void outOfRangeReleasesKey() {
        PlayerRef player = newPlayer();
        String key = newKey();

        expectError(deposit(player.id(), "5.00", key), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");
        assertThat(Db.idempotencyKey(player.id(), key)).isNull();

        Http.Resp retry = expect(deposit(player.id(), "50.00", key), 200);
        assertThat(retry.header(REPLAYED)).isNull();
        assertThat(balance(player.id())).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("IDEM-06 a huge out-of-range amount is a 422 (the key row holds any amount), and the key is released")
    void hugeAmountReleasesKey() {
        PlayerRef player = newPlayer();
        String key = newKey();

        expectError(depositRaw(player.id(), "123456789012345678901234567890.12", key), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");

        expect(deposit(player.id(), "10.00", key), 200);
    }

    @Test
    @DisplayName("IDEM-06 a declined deposit releases the key and the retry sends the provider the same reference")
    void declinedReleasesKeyWithStableReference() {
        PlayerRef player = newPlayer();
        String key = newKey();

        expectError(deposit(player.id(), "10000.01", key), 422, "PAYMENT_DECLINED");
        expect(deposit(player.id(), "20.00", key), 200);

        var calls = ProviderJournal.calls(ProviderJournal.PAYMENTS, player.id());
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1).reference()).isEqualTo(calls.get(0).reference());
        assertThat(balance(player.id())).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("IDEM-06 a withdrawal refused for insufficient funds releases the key")
    void insufficientFundsReleasesKey() {
        PlayerRef player = newPlayer("30.00");
        String key = newKey();

        expectError(withdraw(player.id(), "40.00", key), 422, "INSUFFICIENT_FUNDS");
        expect(deposit(player.id(), "20.00", newKey()), 200);
        expect(withdraw(player.id(), "40.00", key), 200);

        assertThat(balance(player.id())).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("IDEM-07 check order: body format (400) -> key (400) -> player (404) -> replay before business rules")
    void checkOrder() {
        Map<String, String> noKey = new HashMap<>();
        long unknown = 9_000_000_000_000L;

        // invalid body wins over a missing key
        expectError(Http.post("/api/players/" + unknown + "/deposit", "{\"amount\":10.001}", noKey), 400, "VALIDATION_ERROR");
        // missing key wins over an unknown player
        expectError(deposit(unknown, "10.00", null), 400, "IDEMPOTENCY_KEY_REQUIRED");
        expectError(deposit(unknown, "10.00", newKey()), 404, "NOT_FOUND");

        // a completed request is replayed even if a limit set afterwards would refuse it now
        PlayerRef player = newPlayer();
        String key = newKey();
        expect(deposit(player.id(), "100.00", key), 200);
        expect(setLimit(player.id(), "daily", "100.00"), 200);
        Http.Resp replay = expect(deposit(player.id(), "100.00", key), 200);
        assertThat(replay.header(REPLAYED)).isEqualTo("true");
        assertThat(balance(player.id())).isEqualByComparingTo("100.00");
    }
}
