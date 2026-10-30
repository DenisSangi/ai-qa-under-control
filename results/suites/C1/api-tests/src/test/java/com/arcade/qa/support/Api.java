package com.arcade.qa.support;

import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * REST client of player-service. Amounts are passed as JSON literals ("10.010", "\"10\"", "null") so that a
 * test controls exactly what is written on the wire (GEN-02, GEN-03). A null key omits the header.
 */
public final class Api {

    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotent-Replayed";
    public static final List<String> GAMES = List.of("slots", "roulette", "blackjack", "poker", "crash");

    private static final String RUN = Long.toString(System.currentTimeMillis() % 100_000_000L, 36);
    private static final AtomicInteger SEQ = new AtomicInteger();
    /** Every player created by this run; GEN-25 is swept over them at the end of the suite. */
    private static final ConcurrentLinkedQueue<Long> CREATED = new ConcurrentLinkedQueue<>();

    private Api() {
    }

    public static RequestSpecification req() {
        return given().baseUri(Config.API).contentType(ContentType.JSON).accept(ContentType.JSON);
    }

    private static RequestSpecification withKey(String key) {
        RequestSpecification spec = req();
        return key == null ? spec : spec.header(KEY_HEADER, key);
    }

    public static String key() {
        return "qa-" + UUID.randomUUID();
    }

    public static List<Long> createdPlayers() {
        return List.copyOf(CREATED);
    }

    // ---- players ----

    public static Response createPlayerRaw(String username) {
        String body = "{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.com\",\"country\":\"DE\"}";
        return req().body(body).post("/api/players");
    }

    /** A username no player has: unique per run and per call. */
    public static String newUsername() {
        return "qa" + RUN + "_" + SEQ.incrementAndGet();
    }

    /** Creates a fresh player (precondition of every flow, SPEC §1) and returns its id. */
    public static long createPlayer() {
        return Json.parse(createPlayerResponse(newUsername()).asString()).path("id").asLong();
    }

    /** As {@link #createPlayer()} with the given username; returns the creation response. */
    public static Response createPlayerResponse(String username) {
        Response r = createPlayerRaw(username);
        if (r.statusCode() != 201 && r.statusCode() != 200) {
            fail("precondition failed: cannot create a player: " + r.statusCode() + " " + r.asString());
        }
        CREATED.add(Json.parse(r.asString()).path("id").asLong());
        return r;
    }

    public static Response getPlayer(Object id) {
        return req().get("/api/players/" + id);
    }

    public static BigDecimal balance(long id) {
        Response r = getPlayer(id);
        assertEquals(200, r.statusCode(), () -> "GET player " + id + ": " + r.asString());
        return Json.dec(Json.parse(r.asString()).get("balance"));
    }

    public static String username(long id) {
        return Json.parse(getPlayer(id).asString()).path("username").asText();
    }

    // ---- deposit / withdrawal ----

    public static Response depositRaw(Object playerId, String key, String body) {
        return withKey(key).body(body).post("/api/players/" + playerId + "/deposit");
    }

    public static Response deposit(Object playerId, String key, String amount) {
        return depositRaw(playerId, key, "{\"amount\": " + amount + "}");
    }

    public static Response deposit(Object playerId, String amount) {
        return deposit(playerId, key(), amount);
    }

    public static JsonNode depositOk(long playerId, String amount) {
        Response r = deposit(playerId, amount);
        assertEquals(200, r.statusCode(), () -> "precondition deposit " + amount + ": " + r.asString());
        return Json.parse(r.asString());
    }

    public static Response withdrawRaw(Object playerId, String key, String body) {
        return withKey(key).body(body).post("/api/players/" + playerId + "/withdraw");
    }

    public static Response withdraw(Object playerId, String key, String amount) {
        return withdrawRaw(playerId, key, "{\"amount\": " + amount + "}");
    }

    public static Response withdraw(Object playerId, String amount) {
        return withdraw(playerId, key(), amount);
    }

    /** A fresh player whose balance is {@code amount}, funded by approved deposits of at most 10000 each. */
    public static long fundedPlayer(String amount) {
        long id = createPlayer();
        BigDecimal left = new BigDecimal(amount);
        BigDecimal chunk = new BigDecimal("10000");
        while (left.signum() > 0) {
            BigDecimal part = left.min(chunk);
            depositOk(id, part.toPlainString());
            left = left.subtract(part);
        }
        return id;
    }

    // ---- sessions ----

    public static Response startSessionRaw(String key, String body) {
        return withKey(key).body(body).post("/api/sessions");
    }

    public static Response startSession(String key, Object playerId, String game, String bet) {
        String g = game == null ? "null" : "\"" + game + "\"";
        return startSessionRaw(key, "{\"playerId\": " + playerId + ", \"game\": " + g + ", \"bet\": " + bet + "}");
    }

    public static Response startSession(long playerId, String bet) {
        return startSession(key(), playerId, "slots", bet);
    }

    public static JsonNode startOk(long playerId, String bet) {
        Response r = startSession(playerId, bet);
        assertEquals(201, r.statusCode(), () -> "precondition session start bet " + bet + ": " + r.asString());
        return Json.parse(r.asString());
    }

    public static Response finishRaw(Object sessionId, String body) {
        return req().body(body).post("/api/sessions/" + sessionId + "/finish");
    }

    public static Response finish(Object sessionId, String win) {
        return finishRaw(sessionId, "{\"win\": " + win + "}");
    }

    public static JsonNode finishOk(long sessionId, String win) {
        Response r = finish(sessionId, win);
        assertEquals(200, r.statusCode(), () -> "precondition finish win " + win + ": " + r.asString());
        return Json.parse(r.asString());
    }

    public static Response getSession(Object sessionId) {
        return req().get("/api/sessions/" + sessionId);
    }

    public static JsonNode session(long sessionId) {
        Response r = getSession(sessionId);
        assertEquals(200, r.statusCode(), () -> "GET session " + sessionId + ": " + r.asString());
        return Json.parse(r.asString());
    }

    // ---- deposit limits ----

    public static Response limits(Object playerId) {
        return req().get("/api/players/" + playerId + "/deposit-limits");
    }

    public static JsonNode limitList(long playerId) {
        Response r = limits(playerId);
        assertEquals(200, r.statusCode(), () -> "GET deposit-limits: " + r.asString());
        return Json.parse(r.asString());
    }

    /** The list item of {@code period} (upper case) or null when the period has no limit. */
    public static JsonNode limit(long playerId, String period) {
        for (JsonNode item : limitList(playerId)) {
            if (period.equals(item.path("period").asText())) {
                return item;
            }
        }
        return null;
    }

    public static Response putLimitRaw(Object playerId, String period, String body) {
        return req().body(body).put("/api/players/" + playerId + "/deposit-limits/" + period);
    }

    public static Response putLimit(Object playerId, String period, String amount) {
        return putLimitRaw(playerId, period, "{\"amount\": " + amount + "}");
    }

    public static JsonNode putLimitOk(long playerId, String period, String amount) {
        Response r = putLimit(playerId, period, amount);
        assertEquals(200, r.statusCode(), () -> "PUT limit " + period + " " + amount + ": " + r.asString());
        return Json.parse(r.asString());
    }

    public static Response deleteLimit(Object playerId, String period) {
        return req().delete("/api/players/" + playerId + "/deposit-limits/" + period);
    }

    // ---- leaderboard ----

    public static JsonNode leaderboard() {
        Response r = req().get("/api/leaderboard");
        assertEquals(200, r.statusCode(), () -> "GET leaderboard: " + r.asString());
        return Json.parse(r.asString());
    }

    // ---- response helpers ----

    public static JsonNode body(Response r) {
        return Json.parse(r.asString());
    }

    public static boolean replayed(Response r) {
        String h = r.header(REPLAY_HEADER);
        return h != null && h.trim().equalsIgnoreCase("true");
    }
}
