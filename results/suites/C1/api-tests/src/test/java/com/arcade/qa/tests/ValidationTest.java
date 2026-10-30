package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Config;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        // Each error must carry the code the specification documents for its case and a non-empty message.
        record Case(Response response, int status, String code) {
        }
        List<Case> cases = new ArrayList<>(List.of(
                new Case(Api.deposit(p, "\"10\""), 400, "VALIDATION_ERROR"),                   // GEN-03
                new Case(Api.depositRaw(p, Api.key(), "{}"), 400, "VALIDATION_ERROR"),       // GEN-05
                new Case(Api.deposit(p, null, "10"), 400, "IDEMPOTENCY_KEY_REQUIRED"),      // GEN-08
                new Case(Api.deposit(p, "x".repeat(65), "10"), 400, "IDEMPOTENCY_KEY_INVALID"), // GEN-09
                new Case(Api.getPlayer(Config.UNKNOWN_ID), 404, "NOT_FOUND"),               // GEN-07
                new Case(Api.startSession(p, "5"), 409, "ACTIVE_SESSION_EXISTS"),          // SES-04
                new Case(Api.deposit(p, "5"), 422, "DEPOSIT_AMOUNT_OUT_OF_RANGE"),         // DEP-02
                new Case(Api.putLimit(p, "daily", "5"), 422, "DEPOSIT_LIMIT_OUT_OF_RANGE"), // LIM-03
                new Case(Api.deposit(p, "10000.01"), 422, "PAYMENT_DECLINED")));          // DEP-04
        Api.finishOk(sid, "0");
        cases.add(new Case(Api.finish(sid, "1"), 409, "SESSION_NOT_ACTIVE"));            // SES-09
        cases.add(new Case(Api.withdraw(p, "1000"), 422, "INSUFFICIENT_FUNDS"));          // WDR-04
        List<Executable> checks = new ArrayList<>();
        cases.forEach(c -> checks.add(() -> assertError(c.response(), c.status(), c.code())));
        assertAll(checks);
    }

    @Test
    @Tag("GEN-01")
    @DisplayName("CL-111 USD only: no other currency appears in responses or provider requests")
    void usdOnly() {
        JsonNode created = Api.body(Api.createPlayerResponse(Api.newUsername()));
        long p = created.path("id").asLong();
        JsonNode player = Api.depositOk(p, "100");
        JsonNode limit = Api.putLimitOk(p, "daily", "1000");
        JsonNode session = Api.startOk(p, "5");
        List<JsonNode> payments = ProviderStub.requests(ProviderStub.PAYMENTS, p);
        assertEquals(1, payments.size(), "POST /payments requests");
        assertAmount("100", payments.get(0).get("amount"), "provider request amount (the USD amount of the deposit)");
        List<JsonNode> seen = new ArrayList<>(List.of(created, player, limit, session, Api.limitList(p)));
        seen.addAll(payments);
        for (JsonNode node : seen) {
            assertNoForeignCurrency(node, node);
        }
    }

    /** ISO 4217 codes (and common crypto codes) of currencies other than USD. */
    private static final Set<String> FOREIGN_CURRENCIES = Set.of((
            "AED AFN ALL AMD ANG AOA ARS AUD AWG AZN BAM BBD BDT BGN BHD BIF BMD BND BOB BOV BRL BSD BTN BWP BYN BZD "
            + "CAD CDF CHE CHF CHW CLF CLP CNY COP COU CRC CUC CUP CVE CZK DJF DKK DOP DZD EGP ERN ETB EUR FJD FKP "
            + "GBP GEL GHS GIP GMD GNF GTQ GYD HKD HNL HRK HTG HUF IDR ILS INR IQD IRR ISK JMD JOD JPY KES KGS KHR "
            + "KMF KPW KRW KWD KYD KZT LAK LBP LKR LRD LSL LYD MAD MDL MGA MKD MMK MNT MOP MRU MUR MVR MWK MXN MXV "
            + "MYR MZN NAD NGN NIO NOK NPR NZD OMR PAB PEN PGK PHP PKR PLN PYG QAR RON RSD RUB RWF SAR SBD SCR SDG "
            + "SEK SGD SHP SLE SLL SOS SRD SSP STN SVC SYP SZL THB TJS TMT TND TOP TRY TTD TWD TZS UAH UGX USN UYI "
            + "UYU UYW UZS VED VES VND VUV WST XAF XAG XAU XBA XBB XBC XBD XCD XDR XOF XPD XPF XPT XSU XTS XUA XXX "
            + "YER ZAR ZMW ZWL BTC ETH").split(" "));
    private static final Pattern THREE_CAPITALS = Pattern.compile("(?<![A-Z])[A-Z]{3}(?![A-Z])");
    private static final Pattern CURRENCY_SIGN = Pattern.compile("[\\p{Sc}&&[^$]]");

    /**
     * No key or value of {@code node} designates a currency other than USD, whatever its form: a currency field,
     * a currency code inside any key or text value ("100.00 EUR", "ccy"), or a currency sign other than $.
     */
    private static void assertNoForeignCurrency(JsonNode node, JsonNode whole) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey().toLowerCase();
                if (key.contains("currency") || key.equals("ccy")) {
                    assertEquals("USD", e.getValue().asText(), () -> "currency other than USD in " + whole);
                }
                assertNoForeignCurrencyText(e.getKey(), whole);
                assertNoForeignCurrency(e.getValue(), whole);
            }
        } else if (node.isArray()) {
            node.forEach(n -> assertNoForeignCurrency(n, whole));
        } else if (node.isTextual()) {
            assertNoForeignCurrencyText(node.asText(), whole);
        }
    }

    private static void assertNoForeignCurrencyText(String text, JsonNode whole) {
        Matcher m = THREE_CAPITALS.matcher(text);
        while (m.find()) {
            String code = m.group();
            assertTrue(!FOREIGN_CURRENCIES.contains(code), () -> "currency " + code + " in " + whole);
        }
        assertTrue(!CURRENCY_SIGN.matcher(text).find(), () -> "currency sign other than $ in " + whole);
    }
}
