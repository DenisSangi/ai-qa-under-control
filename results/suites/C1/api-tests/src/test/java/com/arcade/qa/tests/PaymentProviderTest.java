package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Events;
import com.arcade.qa.support.ProviderStub;
import com.arcade.qa.support.Snapshot;
import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertNotReplayed;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** These checks change the stub's global behavior; each restores it in {@code finally}. */
@Order(9)
@DisplayName("F-09 Payment provider integration")
class PaymentProviderTest extends ApiTest {

    /** The stub answers later than the 5 s provider timeout (GEN-18). */
    private static final int SLOW_PROVIDER_MS = 7000;

    private static String reference(JsonNode providerRequest) {
        JsonNode ref = providerRequest.get("reference");
        assertTrue(ref != null && !ref.isNull() && !ref.asText().isBlank(),
                () -> "GEN-17: provider request without 'reference': " + providerRequest);
        return ref.asText();
    }

    @Test
    @Tag("GEN-17")
    @DisplayName("CL-112 every provider request carries reference; a retry with the same key sends the same reference")
    void sameKeySameReference() {
        long p = Api.createPlayer();
        String key = Api.key();
        assertError(Api.deposit(p, key, "10000.01"), 422, "PAYMENT_DECLINED");
        assertError(Api.deposit(p, key, "10000.01"), 422, "PAYMENT_DECLINED");
        List<JsonNode> pay = ProviderStub.requests(ProviderStub.PAYMENTS, p);
        assertEquals(2, pay.size(), "POST /payments requests");
        assertEquals(reference(pay.get(0)), reference(pay.get(1)), "deposit retry must send the same reference");

        long q = Api.fundedPlayer("6000");
        String wkey = Api.key();
        assertError(Api.withdraw(q, wkey, "5000.01"), 422, "PAYOUT_DECLINED");
        assertError(Api.withdraw(q, wkey, "5000.01"), 422, "PAYOUT_DECLINED");
        List<JsonNode> out = ProviderStub.requests(ProviderStub.PAYOUTS, q);
        assertEquals(2, out.size(), "POST /payouts requests");
        assertEquals(reference(out.get(0)), reference(out.get(1)), "withdrawal retry must send the same reference");
    }

    @Test
    @Tag("GEN-17")
    @DisplayName("CL-113 different keys send different references, also the same key value of two players")
    void differentKeysDifferentReferences() {
        long a = Api.createPlayer();
        long b = Api.createPlayer();
        String shared = Api.key();
        assertStatus(Api.deposit(a, Api.key(), "10"), 200);
        assertStatus(Api.deposit(a, Api.key(), "10"), 200);
        assertStatus(Api.deposit(a, shared, "10"), 200);
        assertStatus(Api.deposit(b, shared, "10"), 200);
        assertStatus(Api.withdraw(a, Api.key(), "10"), 200);
        Set<String> refs = new HashSet<>();
        int total = 0;
        for (long p : new long[]{a, b}) {
            for (String path : new String[]{ProviderStub.PAYMENTS, ProviderStub.PAYOUTS}) {
                for (JsonNode r : ProviderStub.requests(path, p)) {
                    refs.add(reference(r));
                    total++;
                }
            }
        }
        assertEquals(5, total, "provider requests");
        assertEquals(total, refs.size(), () -> "references must differ between keys and players: " + refs);
    }

    private static void timeout(boolean deposit) {
        long p = Api.fundedPlayer("200");
        String key = Api.key();
        Snapshot before = Snapshot.afterEvents(p, 2, 0); // REGISTERED, DEPOSIT
        Response r;
        long started;
        long elapsedMs;
        ProviderStub.setFixedDelay(SLOW_PROVIDER_MS);
        try {
            started = System.nanoTime(); // GEN-18: timed from sending the request, with the delay in place
            r = deposit ? Api.deposit(p, key, "50") : Api.withdraw(p, key, "50");
            elapsedMs = (System.nanoTime() - started) / 1_000_000;
        } finally {
            ProviderStub.setFixedDelay(0);
        }
        assertError(r, 504, "PAYMENT_TIMEOUT");
        long ms = elapsedMs;
        assertTrue(ms <= 6000, () -> "GEN-18: the response must arrive within 6 s, took " + ms + " ms");
        // GEN-19: nothing changes, also after the provider's late answer.
        long lateAnswer = started + (SLOW_PROVIDER_MS + 1000) * 1_000_000L;
        Events.sleep(Math.max(0, (lateAnswer - System.nanoTime()) / 1_000_000));
        before.assertUnchangedExceptProviderCalls();
        // The key is not consumed: the same key is a new attempt, with the same reference (GEN-17).
        Response retry = deposit ? Api.deposit(p, key, "50") : Api.withdraw(p, key, "50");
        assertStatus(retry, 200);
        assertNotReplayed(retry);
        BigDecimal expected = before.balance().add(new BigDecimal(deposit ? "50" : "-50"));
        assertAmount(expected, Api.balance(p), "balance after the retry");
        List<JsonNode> reqs = ProviderStub.requests(deposit ? ProviderStub.PAYMENTS : ProviderStub.PAYOUTS, p);
        JsonNode last = reqs.get(reqs.size() - 1);
        JsonNode timedOut = reqs.get(reqs.size() - 2);
        assertEquals(reference(timedOut), reference(last), "GEN-17: the retry after a timeout sends the same reference");
    }

    @Test
    @Tag("GEN-18")
    @Tag("GEN-19")
    @DisplayName("CL-114 deposit, provider slower than 5 s: 504 PAYMENT_TIMEOUT within 6 s; nothing changes; key free")
    void depositTimeout() {
        timeout(true);
    }

    @Test
    @Tag("GEN-18")
    @Tag("GEN-19")
    @DisplayName("CL-115 withdrawal, provider slower than 5 s: 504 PAYMENT_TIMEOUT within 6 s; nothing changes; key free")
    void withdrawalTimeout() {
        timeout(false);
    }

    private static void unreachable(boolean deposit) {
        long p = Api.fundedPlayer("200");
        String key = Api.key();
        Snapshot before = Snapshot.afterEvents(p, 2, 0); // REGISTERED, DEPOSIT
        Response r;
        ProviderStub.makeUnreachable(deposit ? ProviderStub.PAYMENTS : ProviderStub.PAYOUTS);
        try {
            r = deposit ? Api.deposit(p, key, "50") : Api.withdraw(p, key, "50");
        } finally {
            ProviderStub.resetMappings();
        }
        assertError(r, 502, "PAYMENT_UNAVAILABLE");
        before.assertUnchangedExceptProviderCalls();
        Response retry = deposit ? Api.deposit(p, key, "50") : Api.withdraw(p, key, "50");
        assertStatus(retry, 200);
        assertNotReplayed(retry);
        assertAmount(before.balance().add(new BigDecimal(deposit ? "50" : "-50")), Api.balance(p), "balance");
    }

    @Test
    @Tag("GEN-20")
    @DisplayName("CL-116 deposit, provider unreachable: 502 PAYMENT_UNAVAILABLE; nothing changes; key free")
    void depositUnreachable() {
        unreachable(true);
    }

    @Test
    @Tag("GEN-20")
    @DisplayName("CL-117 withdrawal, provider unreachable: 502 PAYMENT_UNAVAILABLE; nothing changes; key free")
    void withdrawalUnreachable() {
        unreachable(false);
    }
}
