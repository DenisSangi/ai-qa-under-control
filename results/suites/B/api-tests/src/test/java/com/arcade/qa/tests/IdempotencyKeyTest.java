package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Config;
import com.arcade.qa.support.Events;
import com.arcade.qa.support.Journal;
import com.arcade.qa.support.ProviderStub;
import com.arcade.qa.support.Snapshot;
import com.arcade.qa.support.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertNotReplayed;
import static com.arcade.qa.support.Asserts.assertReplayOf;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Order(2)
@DisplayName("F-07 Idempotency of money operations")
class IdempotencyKeyTest extends ApiTest {

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    @Test
    @Tag("GEN-08")
    @DisplayName("CL-083 missing Idempotency-Key -> 400 IDEMPOTENCY_KEY_REQUIRED for deposit, withdrawal, session start")
    void missingKey() {
        long p = Api.fundedPlayer("100");
        Snapshot before = Snapshot.of(p);
        assertError(Api.deposit(p, null, "50"), 400, "IDEMPOTENCY_KEY_REQUIRED");
        assertError(Api.withdraw(p, null, "50"), 400, "IDEMPOTENCY_KEY_REQUIRED");
        assertError(Api.startSession(null, p, "slots", "5"), 400, "IDEMPOTENCY_KEY_REQUIRED");
        before.assertUnchanged();
    }

    @Test
    @Tag("GEN-08")
    @DisplayName("CL-084 empty Idempotency-Key -> 400 IDEMPOTENCY_KEY_REQUIRED for deposit, withdrawal, session start")
    void emptyKey() {
        long p = Api.fundedPlayer("100");
        Snapshot before = Snapshot.of(p);
        assertError(Api.deposit(p, "", "50"), 400, "IDEMPOTENCY_KEY_REQUIRED");
        assertError(Api.withdraw(p, "", "50"), 400, "IDEMPOTENCY_KEY_REQUIRED");
        assertError(Api.startSession("", p, "slots", "5"), 400, "IDEMPOTENCY_KEY_REQUIRED");
        before.assertUnchanged();
    }

    @Test
    @Tag("GEN-09")
    @DisplayName("CL-085 keys of 1 and 64 printable ASCII characters are accepted")
    void keyLengthBoundsAccepted() {
        long p = Api.createPlayer();
        assertStatus(Api.deposit(p, "x", "10"), 200);
        String k64 = (Api.key() + repeat('~', 64)).substring(0, 64);
        assertStatus(Api.deposit(p, k64, "10"), 200);
        assertStatus(Api.startSession(repeat('S', 64), p, "slots", "1"), 201);
    }

    @Test
    @Tag("GEN-09")
    @DisplayName("CL-086 a key of 65 characters -> 400 IDEMPOTENCY_KEY_INVALID for deposit, withdrawal, session start")
    void keyTooLong() {
        long p = Api.fundedPlayer("100");
        String k65 = (Api.key() + repeat('z', 65)).substring(0, 65);
        Snapshot before = Snapshot.of(p);
        assertError(Api.deposit(p, k65, "50"), 400, "IDEMPOTENCY_KEY_INVALID");
        assertError(Api.withdraw(p, k65, "50"), 400, "IDEMPOTENCY_KEY_INVALID");
        assertError(Api.startSession(k65, p, "slots", "5"), 400, "IDEMPOTENCY_KEY_INVALID");
        before.assertUnchanged();
    }

    @Test
    @Tag("GEN-09")
    @DisplayName("CL-087 non-printable or non-ASCII key (TAB, é) -> 400 IDEMPOTENCY_KEY_INVALID")
    void keyNotPrintableAscii() {
        long p = Api.fundedPlayer("100");
        Snapshot before = Snapshot.of(p);
        for (String key : new String[]{"tab\tkey", "kéy"}) {
            assertError(Api.deposit(p, key, "50"), 400, "IDEMPOTENCY_KEY_INVALID");
            assertError(Api.withdraw(p, key, "50"), 400, "IDEMPOTENCY_KEY_INVALID");
            assertError(Api.startSession(key, p, "slots", "5"), 400, "IDEMPOTENCY_KEY_INVALID");
        }
        before.assertUnchanged();
    }

    @Test
    @Tag("GEN-10")
    @Tag("GEN-13")
    @DisplayName("CL-088 one key space per player: deposit key reused for withdrawal/session, session key for deposit -> 422")
    void keySharedAcrossOperations() {
        long p = Api.fundedPlayer("200");
        String depositKey = Api.key();
        assertStatus(Api.deposit(p, depositKey, "50"), 200);
        String sessionKey = Api.key();
        long sid = Api.body(Api.startSession(sessionKey, p, "slots", "5")).path("id").asLong();
        Api.finishOk(sid, "0");
        Snapshot before = Snapshot.of(p);
        assertError(Api.withdraw(p, depositKey, "50"), 422, "IDEMPOTENCY_KEY_REUSED");
        assertError(Api.startSession(depositKey, p, "slots", "50"), 422, "IDEMPOTENCY_KEY_REUSED");
        assertError(Api.deposit(p, sessionKey, "50"), 422, "IDEMPOTENCY_KEY_REUSED");
        assertError(Api.withdraw(p, sessionKey, "50"), 422, "IDEMPOTENCY_KEY_REUSED");
        before.assertUnchanged();
    }

    @Test
    @Tag("GEN-11")
    @DisplayName("CL-089 amounts are compared by value: 50 then 50.00 with one key is a replay")
    void amountComparedByValue() {
        long p = Api.createPlayer();
        String key = Api.key();
        Response first = Api.deposit(p, key, "50");
        assertStatus(first, 200);
        Response second = Api.deposit(p, key, "50.00");
        assertReplayOf(first, second);
        assertAmount(new BigDecimal("50"), Api.balance(p), "balance");
        assertEquals(1, Journal.records(p, "DEPOSIT").size(), "DEPOSIT records");
    }

    @Test
    @Tag("GEN-11")
    @Tag("GEN-13")
    @DisplayName("CL-090 session start fingerprint: same game and bet -> replay; other game or bet -> 422 REUSED")
    void sessionFingerprint() {
        long p = Api.fundedPlayer("100");
        String key = Api.key();
        Response first = Api.startSession(key, p, "slots", "5");
        assertStatus(first, 201);
        Snapshot before = Snapshot.of(p);
        assertError(Api.startSession(key, p, "roulette", "5"), 422, "IDEMPOTENCY_KEY_REUSED");
        assertError(Api.startSession(key, p, "slots", "6"), 422, "IDEMPOTENCY_KEY_REUSED");
        before.assertUnchanged();
        assertReplayOf(first, Api.startSession(key, p, "slots", "5.00"));
    }

    @Test
    @Tag("GEN-12")
    @DisplayName("CL-091 deposit replay: same status/body + Idempotent-Replayed; balance, journal, events, provider unchanged")
    void depositReplay() {
        long p = Api.createPlayer();
        String key = Api.key();
        Response first = Api.deposit(p, key, "100");
        assertStatus(first, 200);
        assertNotReplayed(first);
        Events.awaitOne(Config.PLAYER_EVENTS, p, "DEPOSIT", EVENT_TIMEOUT);
        Snapshot before = Snapshot.of(p);
        Response replay = Api.deposit(p, key, "100");
        assertReplayOf(first, replay);
        before.assertUnchanged();
    }

    @Test
    @Tag("GEN-12")
    @DisplayName("CL-092 withdrawal replay: same status/body + header; balance, journal, events, provider unchanged")
    void withdrawalReplay() {
        long p = Api.fundedPlayer("100");
        String key = Api.key();
        Response first = Api.withdraw(p, key, "30");
        assertStatus(first, 200);
        assertNotReplayed(first);
        Events.awaitOne(Config.PLAYER_EVENTS, p, "WITHDRAWAL", EVENT_TIMEOUT);
        Snapshot before = Snapshot.of(p);
        assertReplayOf(first, Api.withdraw(p, key, "30"));
        before.assertUnchanged();
    }

    @Test
    @Tag("GEN-12")
    @DisplayName("CL-093 session start replay: 201 same body + header; no second BET, no second event")
    void sessionStartReplay() {
        long p = Api.fundedPlayer("100");
        String key = Api.key();
        Response first = Api.startSession(key, p, "poker", "10");
        assertStatus(first, 201);
        assertNotReplayed(first);
        Events.awaitOne(Config.GAME_EVENTS, p, "SESSION_STARTED", EVENT_TIMEOUT);
        Snapshot before = Snapshot.of(p);
        assertReplayOf(first, Api.startSession(key, p, "poker", "10"));
        before.assertUnchanged();
        assertEquals(1, Journal.records(p, "BET").size(), "BET records");
    }

    @Test
    @Tag("GEN-12")
    @DisplayName("CL-094 replay returns the original response although a check would refuse now")
    void replayIgnoresCurrentChecks() {
        // exhausted deposit limit
        long a = Api.createPlayer();
        Api.putLimitOk(a, "daily", "100");
        String k1 = Api.key();
        Response dep = Api.deposit(a, k1, "100");
        assertStatus(dep, 200);
        assertReplayOf(dep, Api.deposit(a, k1, "100"));
        // lower balance
        long b = Api.fundedPlayer("100");
        String k2 = Api.key();
        Response wdr = Api.withdraw(b, k2, "60");
        assertStatus(wdr, 200);
        assertStatus(Api.withdraw(b, "40"), 200); // balance 0
        assertReplayOf(wdr, Api.withdraw(b, k2, "60"));
        assertAmount(BigDecimal.ZERO, Api.balance(b), "balance");
        // session still active (SES-04 would refuse a new start)
        long c = Api.fundedPlayer("100");
        String k3 = Api.key();
        Response st = Api.startSession(k3, c, "crash", "10");
        assertStatus(st, 201);
        assertReplayOf(st, Api.startSession(k3, c, "crash", "10"));
        assertAmount(new BigDecimal("90"), Api.balance(c), "balance");
    }

    @Test
    @Tag("GEN-13")
    @DisplayName("CL-095 consumed key with a different amount (deposit, withdrawal) -> 422 IDEMPOTENCY_KEY_REUSED; nothing changes")
    void differentAmount() {
        long p = Api.createPlayer();
        String key = Api.key();
        assertStatus(Api.deposit(p, key, "100"), 200);
        Snapshot before = Snapshot.of(p);
        assertError(Api.deposit(p, key, "100.01"), 422, "IDEMPOTENCY_KEY_REUSED");
        assertError(Api.deposit(p, key, "200"), 422, "IDEMPOTENCY_KEY_REUSED");
        String wkey = Api.key();
        assertStatus(Api.withdraw(p, wkey, "40"), 200);
        Snapshot afterWithdrawal = Snapshot.of(p);
        assertError(Api.withdraw(p, wkey, "40.01"), 422, "IDEMPOTENCY_KEY_REUSED");
        assertError(Api.withdraw(p, wkey, "10"), 422, "IDEMPOTENCY_KEY_REUSED");
        afterWithdrawal.assertUnchanged();
        assertAmount(before.balance().subtract(new BigDecimal("40")), Api.balance(p), "balance");
    }

    private static void inProgress(boolean deposit) throws Exception {
        long p = Api.fundedPlayer("500");
        String key = Api.key();
        Snapshot before = Snapshot.of(p);
        Response second;
        Response third;
        Response first;
        ProviderStub.setFixedDelay(2500);
        try {
            CompletableFuture<Response> f = CompletableFuture.supplyAsync(() ->
                    deposit ? Api.deposit(p, key, "100") : Api.withdraw(p, key, "100"));
            Events.sleep(800);
            second = deposit ? Api.deposit(p, key, "100") : Api.withdraw(p, key, "100");
            third = deposit ? Api.deposit(p, key, "200") : Api.withdraw(p, key, "200");
            first = f.get(20, TimeUnit.SECONDS);
        } finally {
            ProviderStub.setFixedDelay(0);
        }
        assertStatus(first, 200);
        assertError(second, 409, "IDEMPOTENCY_KEY_IN_PROGRESS");
        assertError(third, 409, "IDEMPOTENCY_KEY_IN_PROGRESS");
        BigDecimal expected = before.balance().add(new BigDecimal(deposit ? "100" : "-100"));
        assertAmount(expected, Api.balance(p), "balance: the operation is performed once");
        String type = deposit ? "DEPOSIT" : "WITHDRAWAL";
        long records = Journal.records(p, type).size();
        assertEquals(deposit ? 2 : 1, records, type + " records (funding deposit + this one)");
        assertEquals(deposit ? 2 : 1, ProviderStub.count(deposit ? ProviderStub.PAYMENTS : ProviderStub.PAYOUTS, p),
                "provider requests");
        assertReplayOf(first, deposit ? Api.deposit(p, key, "100") : Api.withdraw(p, key, "100"));
    }

    @Test
    @Tag("GEN-14")
    @DisplayName("CL-096 deposit with the same key while the first is processed -> 409 IDEMPOTENCY_KEY_IN_PROGRESS")
    void depositInProgress() throws Exception {
        inProgress(true);
    }

    @Test
    @Tag("GEN-14")
    @DisplayName("CL-097 withdrawal with the same key while the first is processed -> 409 IDEMPOTENCY_KEY_IN_PROGRESS")
    void withdrawalInProgress() throws Exception {
        inProgress(false);
    }

    @Test
    @Tag("GEN-15")
    @DisplayName("CL-098 deposit errors (400, range, limit, provider decline) do not consume the key")
    void depositErrorsKeepKeyFree() {
        long p = Api.createPlayer();
        Api.putLimitOk(p, "daily", "50");
        String key = Api.key();
        assertError(Api.deposit(p, key, "10.001"), 400, "VALIDATION_ERROR");
        assertError(Api.deposit(p, key, "9.99"), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE");
        assertError(Api.deposit(p, key, "60"), 422, "DEPOSIT_LIMIT_EXCEEDED");
        Response ok = Api.deposit(p, key, "20");
        assertStatus(ok, 200);
        assertNotReplayed(ok);
        assertAmount(new BigDecimal("20"), Api.balance(p), "balance");

        long q = Api.createPlayer();
        String k2 = Api.key();
        assertError(Api.deposit(q, k2, "10000.01"), 422, "PAYMENT_DECLINED");
        Response ok2 = Api.deposit(q, k2, "100");
        assertStatus(ok2, 200);
        assertNotReplayed(ok2);
        assertAmount(new BigDecimal("100"), Api.balance(q), "balance");
    }

    @Test
    @Tag("GEN-15")
    @DisplayName("CL-099 withdrawal errors (range, active session, insufficient funds, payout decline) keep the key free")
    void withdrawalErrorsKeepKeyFree() {
        long p = Api.fundedPlayer("6000");
        String key = Api.key();
        assertError(Api.withdraw(p, key, "9.99"), 422, "WITHDRAWAL_AMOUNT_OUT_OF_RANGE");
        long sid = Api.startOk(p, "10").path("id").asLong();
        assertError(Api.withdraw(p, key, "20"), 409, "ACTIVE_SESSION_EXISTS");
        Api.finishOk(sid, "10");
        assertError(Api.withdraw(p, key, "20000"), 422, "INSUFFICIENT_FUNDS");
        assertError(Api.withdraw(p, key, "5000.01"), 422, "PAYOUT_DECLINED");
        Response ok = Api.withdraw(p, key, "20");
        assertStatus(ok, 200);
        assertNotReplayed(ok);
        assertAmount(new BigDecimal("5980"), Api.balance(p), "balance");
    }

    @Test
    @Tag("GEN-15")
    @DisplayName("CL-100 session start errors (bet range, insufficient funds, active session) keep the key free")
    void sessionErrorsKeepKeyFree() {
        long p = Api.fundedPlayer("50");
        String key = Api.key();
        assertError(Api.startSession(key, p, "slots", "0.99"), 422, "BET_AMOUNT_OUT_OF_RANGE");
        assertError(Api.startSession(key, p, "slots", "60"), 422, "INSUFFICIENT_FUNDS");
        long other = Api.startOk(p, "5").path("id").asLong();
        assertError(Api.startSession(key, p, "slots", "5"), 409, "ACTIVE_SESSION_EXISTS");
        Api.finishOk(other, "5");
        Response ok = Api.startSession(key, p, "slots", "5");
        assertStatus(ok, 201);
        assertNotReplayed(ok);
        assertAmount(new BigDecimal("45"), Api.balance(p), "balance");
    }

    @Test
    @Tag("GEN-16")
    @DisplayName("CL-101 a replay after the clock is advanced by 31 days returns the original response")
    void keyLifetime() {
        long p = Api.createPlayer();
        String key = Api.key();
        Response first = Api.deposit(p, key, "100");
        assertStatus(first, 200);
        TestClock.advance(Duration.ofDays(31));
        Response replay = Api.deposit(p, key, "100");
        assertReplayOf(first, replay);
        assertAmount(new BigDecimal("100"), Api.balance(p), "balance");
        assertTrue(Journal.records(p, "DEPOSIT").size() == 1, "one DEPOSIT record");
    }
}
