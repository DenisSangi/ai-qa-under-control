package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Config;
import com.arcade.qa.support.Json;
import com.arcade.qa.support.ProviderStub;
import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertDetailsMention;
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Order(1)
@DisplayName("F-08 Request validation and errors")
class ValidationTest extends ApiTest {

    /** Sends {@code literal} in the amount field named {@code field}; returns the response and the player. */
    private record Sent(Response response, long playerId) {
    }

    private static Sent send(String field, String literal) {
        switch (field) {
            case "deposit amount" -> {
                long p = Api.createPlayer();
                return new Sent(Api.deposit(p, literal), p);
            }
            case "withdrawal amount" -> {
                long p = Api.fundedPlayer("100");
                return new Sent(Api.withdraw(p, literal), p);
            }
            case "limit amount" -> {
                long p = Api.createPlayer();
                return new Sent(Api.putLimit(p, "daily", literal), p);
            }
            case "bet" -> {
                long p = Api.fundedPlayer("100");
                return new Sent(Api.startSession(p, literal), p);
            }
            case "win" -> {
                long p = Api.fundedPlayer("100");
                long sid = Api.startOk(p, "1").path("id").asLong();
                return new Sent(Api.finish(sid, literal), p);
            }
            default -> throw new IllegalArgumentException(field);
        }
    }

    private static BigDecimal balanceBefore(String field) {
        return switch (field) {
            case "deposit amount", "limit amount" -> BigDecimal.ZERO;
            case "win" -> new BigDecimal("99");
            default -> new BigDecimal("100");
        };
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({"deposit amount, 10.01", "withdrawal amount, 10.01", "limit amount, 10.01", "bet, 1.01", "win, 0.01"})
    @Tag("GEN-02")
    @DisplayName("CL-102 amounts with 2 decimal places are accepted")
    void twoDecimalsAccepted(String field, String literal) {
        Response r = send(field, literal).response();
        assertTrue(r.statusCode() == 200 || r.statusCode() == 201,
                () -> field + " " + literal + " must be accepted: " + r.statusCode() + " " + r.asString());
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({"deposit amount, 10.001", "withdrawal amount, 10.001", "limit amount, 10.001", "bet, 1.001",
            "win, 0.001"})
    @Tag("GEN-02")
    @DisplayName("CL-103 amounts with 3 decimal places -> 400 VALIDATION_ERROR")
    void threeDecimalsRefused(String field, String literal) {
        Sent s = send(field, literal);
        assertError(s.response(), 400, "VALIDATION_ERROR");
        assertAmount(balanceBefore(field), Api.balance(s.playerId()), "balance");
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({"deposit amount, 10.010", "withdrawal amount, 10.010", "limit amount, 10.010", "bet, 1.010",
            "win, 0.010"})
    @Tag("GEN-02")
    @DisplayName("CL-104 decimal places are counted as written: a trailing zero (10.010) -> 400 VALIDATION_ERROR")
    void trailingZeroCounted(String field, String literal) {
        Sent s = send(field, literal);
        assertError(s.response(), 400, "VALIDATION_ERROR");
        assertAmount(balanceBefore(field), Api.balance(s.playerId()), "balance");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"deposit amount", "withdrawal amount", "limit amount", "bet", "win", "playerId"})
    @Tag("GEN-03")
    @DisplayName("CL-105 numbers sent as strings -> 400 VALIDATION_ERROR with the field name in details")
    void numbersAsStrings(String field) {
        Response r;
        String name;
        if (field.equals("playerId")) {
            long p = Api.fundedPlayer("100");
            r = Api.startSessionRaw(Api.key(), "{\"playerId\": \"" + p + "\", \"game\": \"slots\", \"bet\": 5}");
            name = "playerId";
        } else {
            r = send(field, field.equals("bet") ? "\"5\"" : field.equals("win") ? "\"5\"" : "\"10\"").response();
            name = field.endsWith("amount") ? "amount" : field;
        }
        JsonNode err = assertError(r, 400, "VALIDATION_ERROR");
        assertDetailsMention(err, name);
    }

    static Stream<Arguments> requiredFields() {
        List<Arguments> out = new ArrayList<>();
        for (boolean asNull : new boolean[]{false, true}) {
            String how = asNull ? "null" : "missing";
            out.add(Arguments.of("deposit amount " + how, "amount", asNull));
            out.add(Arguments.of("withdrawal amount " + how, "amount", asNull));
            out.add(Arguments.of("limit amount " + how, "amount", asNull));
            out.add(Arguments.of("session playerId " + how, "playerId", asNull));
            out.add(Arguments.of("session game " + how, "game", asNull));
            out.add(Arguments.of("session bet " + how, "bet", asNull));
            out.add(Arguments.of("finish win " + how, "win", asNull));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requiredFields")
    @Tag("GEN-05")
    @DisplayName("CL-106 a missing or null required field -> 400 VALIDATION_ERROR")
    void requiredFieldMissingOrNull(String name, String field, boolean asNull) {
        long p = Api.fundedPlayer("100");
        String amountBody = asNull ? "{\"amount\": null}" : "{}";
        Response r;
        if (name.startsWith("deposit")) {
            r = Api.depositRaw(p, Api.key(), amountBody);
        } else if (name.startsWith("withdrawal")) {
            r = Api.withdrawRaw(p, Api.key(), amountBody);
        } else if (name.startsWith("limit")) {
            r = Api.putLimitRaw(p, "daily", amountBody);
        } else if (name.startsWith("finish")) {
            long sid = Api.startOk(p, "1").path("id").asLong();
            r = Api.finishRaw(sid, asNull ? "{\"win\": null}" : "{}");
        } else {
            StringBuilder b = new StringBuilder("{");
            Map<String, String> all = Map.of("playerId", Long.toString(p), "game", "\"slots\"", "bet", "5");
            for (String f : new String[]{"playerId", "game", "bet"}) {
                if (f.equals(field)) {
                    if (asNull) {
                        b.append('"').append(f).append("\": null,");
                    }
                } else {
                    b.append('"').append(f).append("\": ").append(all.get(f)).append(',');
                }
            }
            b.setLength(b.length() - 1);
            r = Api.startSessionRaw(Api.key(), b.append('}').toString());
        }
        assertError(r, 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"GET, /api/players/abc", "POST, /api/players/abc/deposit", "POST, /api/players/abc/withdraw",
            "GET, /api/players/abc/deposit-limits", "PUT, /api/players/abc/deposit-limits/daily",
            "DELETE, /api/players/abc/deposit-limits/daily", "GET, /api/sessions/abc", "POST, /api/sessions/abc/finish"})
    @Tag("GEN-06")
    @DisplayName("CL-107 a non-numeric id in the path -> 400 BAD_REQUEST")
    void nonNumericId(String method, String path) {
        String body = path.endsWith("finish") ? "{\"win\": 1}" : "{\"amount\": 100}";
        Response r = Api.req().header(Api.KEY_HEADER, Api.key()).body(body).request(method, path);
        assertError(r, 400, "BAD_REQUEST");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GET player", "deposit", "withdrawal", "GET limits", "PUT limit", "DELETE limit",
            "session start"})
    @Tag("GEN-07")
    @DisplayName("CL-108 an unknown player -> 404 NOT_FOUND")
    void unknownPlayer(String endpoint) {
        long id = Config.UNKNOWN_ID;
        Response r = switch (endpoint) {
            case "GET player" -> Api.getPlayer(id);
            case "deposit" -> Api.deposit(id, "100");
            case "withdrawal" -> Api.withdraw(id, "100");
            case "GET limits" -> Api.limits(id);
            case "PUT limit" -> Api.putLimit(id, "daily", "100");
            case "DELETE limit" -> Api.deleteLimit(id, "daily");
            default -> Api.startSession(Api.key(), id, "slots", "5");
        };
        assertError(r, 404, "NOT_FOUND");
    }

    @Test
    @Tag("GEN-07")
    @DisplayName("CL-109 an unknown session -> 404 NOT_FOUND for read and finish")
    void unknownSession() {
        assertError(Api.getSession(Config.UNKNOWN_ID), 404, "NOT_FOUND");
        assertError(Api.finish(Config.UNKNOWN_ID, "1"), 404, "NOT_FOUND");
    }

    @Test
    @Tag("GEN-04")
    @DisplayName("CL-110 error responses are JSON objects with a machine-readable code and a message")
    void errorBodyFormat() {
        long p = Api.fundedPlayer("100");
        long sid = Api.startOk(p, "10").path("id").asLong();
        List<Response> errors = List.of(
                Api.deposit(p, "\"10\""),                         // 400 VALIDATION_ERROR
                Api.deposit(p, null, "10"),                       // 400 IDEMPOTENCY_KEY_REQUIRED
                Api.deposit(p, "x".repeat(65), "10"),             // 400 IDEMPOTENCY_KEY_INVALID
                Api.getPlayer(Config.UNKNOWN_ID),                 // 404
                Api.startSession(p, "5"),                         // 409 ACTIVE_SESSION_EXISTS
                Api.deposit(p, "5"),                              // 422 DEPOSIT_AMOUNT_OUT_OF_RANGE
                Api.putLimit(p, "daily", "5"),                    // 422 DEPOSIT_LIMIT_OUT_OF_RANGE
                Api.deposit(p, "10000.01"));                      // 422 PAYMENT_DECLINED
        Api.finishOk(sid, "0");
        List<Response> more = List.of(Api.finish(sid, "1"),      // 409 SESSION_NOT_ACTIVE
                Api.withdraw(p, "1000"));                         // 422 INSUFFICIENT_FUNDS
        List<Executable> checks = new ArrayList<>();
        Stream.concat(errors.stream(), more.stream()).forEach(r -> checks.add(() -> {
            assertTrue(r.statusCode() >= 400, () -> "an error was expected: " + r.statusCode() + " " + r.asString());
            JsonNode b = Json.parse(r.asString());
            assertTrue(b.isObject() && b.path("code").isTextual() && !b.path("code").asText().isBlank()
                            && b.path("message").isTextual(),
                    () -> "GEN-04 error body for " + r.statusCode() + ": " + r.asString());
        }));
        assertAll(checks);
    }

    @Test
    @Tag("GEN-01")
    @DisplayName("CL-111 USD only: no other currency appears in responses or provider requests")
    void usdOnly() {
        long p = Api.createPlayer();
        JsonNode player = Api.depositOk(p, "100");
        Api.putLimitOk(p, "daily", "1000");
        JsonNode session = Api.startOk(p, "5");
        List<JsonNode> seen = new ArrayList<>(List.of(player, session, Api.limitList(p)));
        seen.addAll(ProviderStub.requests(ProviderStub.PAYMENTS, p));
        for (JsonNode node : seen) {
            assertNoForeignCurrency(node);
        }
        assertEquals(1, ProviderStub.count(ProviderStub.PAYMENTS, p));
    }

    private static void assertNoForeignCurrency(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().toLowerCase().contains("currency")) {
                    assertEquals("USD", e.getValue().asText(), () -> "currency other than USD in " + node);
                }
                assertNoForeignCurrency(e.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(ValidationTest::assertNoForeignCurrency);
        }
    }
}
