package com.arcade.apitests.support;

import com.arcade.apitests.support.Http.Resp;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Domain-level calls of player-service. Raw variants return the response; *Ok variants assert success. */
public final class Api {

    /** Provider stub approves a single payment up to this amount (see infra/wiremock mappings). */
    public static final BigDecimal MAX_APPROVED_PAYMENT = new BigDecimal("10000.00");

    public record PlayerRef(long id, String username, String email) {
    }

    private Api() {
    }

    // ---- assertions -----------------------------------------------------------------------------

    public static Resp expect(Resp resp, int status) {
        assertThat(resp.status()).as("HTTP status of %s", resp).isEqualTo(status);
        return resp;
    }

    /** Asserts the status and the ApiError contract: code, message, timestamp. */
    public static JsonNode expectError(Resp resp, int status, String code) {
        expect(resp, status);
        JsonNode body = resp.json();
        assertThat(Json.text(body, "code")).as("error code of %s", resp).isEqualTo(code);
        assertThat(Json.text(body, "message")).as("error message of %s", resp).isNotBlank();
        assertThat(Json.instant(body, "timestamp")).as("error timestamp of %s", resp).isNotNull();
        return body;
    }

    public static String unique(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    public static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    /** Money as a 2-decimal string, for comparing amounts regardless of their JSON scale (50 vs 50.00). */
    public static String fmt(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    // ---- players --------------------------------------------------------------------------------

    public static Resp createPlayer(String username, String email, String country) {
        return Http.post("/api/players", Json.write(Map.of("username", username, "email", email, "country", country)));
    }

    public static PlayerRef newPlayer() {
        String username = unique("at");
        String email = username + "@apitest.example.com";
        JsonNode body = expect(createPlayer(username, email, "DE"), 201).json();
        return new PlayerRef(body.get("id").asLong(), username, email);
    }

    /** A new player with {@code funds} deposited (split into provider-approvable chunks). */
    public static PlayerRef newPlayer(String funds) {
        PlayerRef player = newPlayer();
        fund(player.id(), new BigDecimal(funds));
        return player;
    }

    public static Resp getPlayer(long id) {
        return Http.get("/api/players/" + id);
    }

    public static BigDecimal balance(long id) {
        return Json.dec(expect(getPlayer(id), 200).json(), "balance");
    }

    // ---- money ----------------------------------------------------------------------------------

    /** {@code amountJson} is put into the body verbatim, so tests can send 1e20, "10", null... */
    public static Resp depositRaw(long playerId, String amountJson, String key) {
        return Http.post("/api/players/" + playerId + "/deposit", "{\"amount\":" + amountJson + "}", keyHeader(key));
    }

    public static Resp deposit(long playerId, BigDecimal amount, String key) {
        return depositRaw(playerId, amount.toPlainString(), key);
    }

    public static Resp deposit(long playerId, String amount, String key) {
        return depositRaw(playerId, amount, key);
    }

    public static Resp withdrawRaw(long playerId, String amountJson, String key) {
        return Http.post("/api/players/" + playerId + "/withdraw", "{\"amount\":" + amountJson + "}", keyHeader(key));
    }

    public static Resp withdraw(long playerId, String amount, String key) {
        return withdrawRaw(playerId, amount, key);
    }

    public static void fund(long playerId, BigDecimal total) {
        BigDecimal left = total;
        while (left.signum() > 0) {
            BigDecimal chunk = left.min(MAX_APPROVED_PAYMENT);
            expect(deposit(playerId, chunk, newKey()), 200);
            left = left.subtract(chunk);
        }
    }

    private static Map<String, String> keyHeader(String key) {
        return key == null ? Map.of() : Map.of("Idempotency-Key", key);
    }

    // ---- sessions -------------------------------------------------------------------------------

    public static Resp startSession(long playerId, String game, String bet) {
        return Http.post("/api/sessions", "{\"playerId\":" + playerId + ",\"game\":\"" + game + "\",\"bet\":" + bet + "}");
    }

    public static JsonNode startSessionOk(long playerId, String bet) {
        return expect(startSession(playerId, "slots", bet), 201).json();
    }

    public static Resp finishSession(long sessionId, String win) {
        return Http.post("/api/sessions/" + sessionId + "/finish", "{\"win\":" + win + "}");
    }

    public static JsonNode finishSessionOk(long sessionId, String win) {
        return expect(finishSession(sessionId, win), 200).json();
    }

    public static Resp getSession(long sessionId) {
        return Http.get("/api/sessions/" + sessionId);
    }

    /** Starts and finishes one session. */
    public static long playRound(long playerId, String bet, String win) {
        long sessionId = startSessionOk(playerId, bet).get("id").asLong();
        finishSessionOk(sessionId, win);
        return sessionId;
    }

    // ---- deposit limits -------------------------------------------------------------------------

    public static Resp limits(long playerId) {
        return Http.get("/api/players/" + playerId + "/deposit-limits");
    }

    /** The limit of {@code period} (DAILY/WEEKLY/MONTHLY) from GET, or null if not listed. */
    public static JsonNode limit(long playerId, String period) {
        for (JsonNode node : expect(limits(playerId), 200).json()) {
            if (period.equals(Json.text(node, "period"))) {
                return node;
            }
        }
        return null;
    }

    public static Resp setLimit(long playerId, String period, String amount) {
        return Http.put("/api/players/" + playerId + "/deposit-limits/" + period, "{\"amount\":" + amount + "}");
    }

    public static Resp removeLimit(long playerId, String period) {
        return Http.delete("/api/players/" + playerId + "/deposit-limits/" + period);
    }

    // ---- stats / leaderboard --------------------------------------------------------------------

    public static Resp stats(long playerId) {
        return Http.get("/api/players/" + playerId + "/stats");
    }

    public static JsonNode leaderboard() {
        return expect(Http.get("/api/leaderboard"), 200).json();
    }
}
