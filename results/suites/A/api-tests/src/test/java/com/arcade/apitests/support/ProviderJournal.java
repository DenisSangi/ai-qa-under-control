package com.arcade.apitests.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.arcade.apitests.support.Api.expect;

/**
 * What player-service actually sent to the payment provider, read from the WireMock request journal.
 * Read-only: the stub's mappings and journal are never changed.
 */
public final class ProviderJournal {

    public static final String PAYMENTS = "/payments";
    public static final String PAYOUTS = "/payouts";

    public record Call(String path, long playerId, BigDecimal amount, String reference, Instant at) {
    }

    private ProviderJournal() {
    }

    /** Calls to {@code path} for {@code playerId}, oldest first. */
    public static List<Call> calls(String path, long playerId) {
        String query = Json.write(java.util.Map.of(
                "method", "POST",
                "urlPath", path,
                "bodyPatterns", List.of(java.util.Map.of("matchesJsonPath", "$[?(@.playerId == " + playerId + ")]"))));
        JsonNode body = expect(Http.post(Env.STUB + "/__admin/requests/find", query), 200).json();
        List<Call> calls = new ArrayList<>();
        for (JsonNode request : body.path("requests")) {
            JsonNode sent = Json.parse(request.path("body").asText());
            calls.add(new Call(path, sent.path("playerId").asLong(), Json.dec(sent, "amount"),
                    Json.text(sent, "reference"), Instant.parse(request.path("loggedDateString").asText())));
        }
        calls.sort(Comparator.comparing(Call::at));
        return calls;
    }
}
