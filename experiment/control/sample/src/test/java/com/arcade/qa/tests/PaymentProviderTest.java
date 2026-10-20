package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.ProviderStub;
import com.arcade.qa.support.Snapshot;
import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertNotReplayed;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Order(9)
@DisplayName("F-09 Payment provider integration")
class PaymentProviderTest extends ApiTest {

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
    @Tag("GEN-20")
    @DisplayName("CL-116 deposit, provider unreachable: 502 PAYMENT_UNAVAILABLE; nothing changes; key free")
    void depositUnreachable() {
        long p = Api.fundedPlayer("200");
        String key = Api.key();
        Snapshot before = Snapshot.of(p);
        ProviderStub.makeUnreachable(ProviderStub.PAYMENTS);
        Response r = Api.deposit(p, key, "50");
        assertError(r, 502, "PAYMENT_UNAVAILABLE");
        ProviderStub.resetMappings();
        before.assertUnchangedExceptProviderCalls();
        Response retry = Api.deposit(p, key, "50");
        assertStatus(retry, 200);
        assertNotReplayed(retry);
        assertAmount(before.balance().add(new BigDecimal("50")), Api.balance(p), "balance");
    }
}
