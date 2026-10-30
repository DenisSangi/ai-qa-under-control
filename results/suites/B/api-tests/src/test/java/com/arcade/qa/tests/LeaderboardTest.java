package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Json;
import com.arcade.qa.support.Leaderboard;
import com.arcade.qa.support.LeaderboardSocket;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static com.arcade.qa.support.Asserts.assertAmount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Order(13)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("F-06 Leaderboard: top, freshness, live updates")
class LeaderboardTest extends ApiTest {

    static final Duration FRESHNESS = Duration.ofSeconds(30);
    private static final Duration QUIET = Duration.ofSeconds(6);

    private LeaderboardSocket socket;
    private long risingPlayer;
    private BigDecimal risingNet;

    @BeforeAll
    void openSocket() {
        // Every check below must happen inside one leaderboard period.
        Leaderboard.ensureAwayFromBoundary(Duration.ofMinutes(15));
        socket = LeaderboardSocket.open();
    }

    @AfterAll
    void closeSocket() {
        if (socket != null) {
            socket.close();
        }
    }

    /** Finishes one session of a fresh player with {@code win} for bet 1; returns the player id. */
    private static long winner(BigDecimal win) {
        long p = Api.fundedPlayer("10");
        Api.finishOk(Api.startOk(p, "1").path("id").asLong(), win.toPlainString());
        return p;
    }

    static void assertTopFormat(JsonNode top) {
        assertTrue(top.isArray(), () -> "top must be a list: " + top);
        assertTrue(top.size() <= 10, () -> "at most 10 entries: " + top.size());
        BigDecimal prev = null;
        for (JsonNode e : top) {
            GameSessionTest.assertFields(e, "playerId", "username", "netResult");
            assertTrue(e.path("playerId").isIntegralNumber() && e.path("username").isTextual()
                    && e.path("netResult").isNumber(), () -> "entry types: " + e);
            BigDecimal n = e.path("netResult").decimalValue();
            assertTrue(n.signum() > 0, () -> "LDB-04: only positive netResult is listed: " + e);
            BigDecimal p = prev;
            assertTrue(p == null || p.compareTo(n) >= 0, () -> "sorted by netResult descending: " + top);
            prev = n;
        }
    }

    @Test
    @Order(1)
    @Tag("LDB-01")
    @Tag("LDB-04")
    @DisplayName("CL-074 GET /api/leaderboard: 200, at most 10 {playerId, username, netResult}, descending, positive")
    void topFormat() {
        long p = winner(Leaderboard.winningAmount());
        Leaderboard.awaitTop(t -> Leaderboard.entry(t, p) != null, FRESHNESS);
        JsonNode top = Api.leaderboard();
        assertTopFormat(top);
        JsonNode e = Leaderboard.entry(top, p);
        assertNotNull(e, () -> "the winner must be listed: " + top);
        assertEquals(Api.username(p), e.path("username").asText(), "username");
    }

    @Test
    @Order(2)
    @Tag("LDB-03")
    @Tag("SES-12")
    @DisplayName("CL-075 netResult = sum(win - bet) over FINISHED sessions; ACTIVE and EXPIRED are not counted")
    void netResult() {
        long p = Api.fundedPlayer("1000");
        BigDecimal w = Leaderboard.winningAmount();
        Api.finishOk(Api.startOk(p, "10").path("id").asLong(), w.toPlainString());
        Api.finishOk(Api.startOk(p, "20").path("id").asLong(), "0");
        SessionTimingTest.expire(Api.startOk(p, "30").path("id").asLong());
        Api.startOk(p, "40"); // stays ACTIVE
        BigDecimal expected = w.subtract(new BigDecimal("30"));
        JsonNode top = Leaderboard.awaitTop(t -> {
            JsonNode e = Leaderboard.entry(t, p);
            return e != null && Json.sameValue(expected, Json.dec(e.get("netResult")));
        }, FRESHNESS);
        JsonNode e = Leaderboard.entry(top, p);
        assertNotNull(e, () -> "player must be listed: " + top);
        assertAmount(expected.toPlainString(), e.get("netResult"), "netResult (W - 10 + 0 - 20)");
    }

    @Test
    @Order(3)
    @Tag("LDB-05")
    @DisplayName("CL-076 a finished session is reflected in GET /api/leaderboard within 30 s")
    void freshness() {
        long p = Api.fundedPlayer("10");
        long sid = Api.startOk(p, "1").path("id").asLong();
        BigDecimal w = Leaderboard.winningAmount();
        Api.finishOk(sid, w.toPlainString());
        long t0 = System.nanoTime();
        BigDecimal expected = w.subtract(BigDecimal.ONE);
        JsonNode top = Leaderboard.awaitTop(t -> Leaderboard.entry(t, p) != null, FRESHNESS.plusSeconds(1));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        JsonNode e = Leaderboard.entry(top, p);
        assertNotNull(e, () -> "not reflected within 30 s: " + top);
        assertTrue(ms <= FRESHNESS.toMillis(), () -> "reflected after " + ms + " ms");
        assertAmount(expected.toPlainString(), e.get("netResult"), "netResult");
    }

    @Test
    @Order(4)
    @Tag("LDB-06")
    @DisplayName("CL-077 a new entry in the top sends LEADERBOARD_UPDATED within 30 s; its top equals GET")
    void messageOnNewEntry() {
        socket.awaitQuiet(QUIET, FRESHNESS.plusSeconds(5));
        int from = socket.size();
        BigDecimal w = Leaderboard.winningAmount();
        long p = winner(w);
        LeaderboardSocket.Message m = socket.await(from,
                b -> Leaderboard.entry(b.path("top"), p) != null, FRESHNESS);
        assertNotNull(m, () -> "no message with the new entry within 30 s; messages: "
                + socket.messages().subList(from, socket.size()));
        JsonNode get = Api.leaderboard();
        assertEquals("LEADERBOARD_UPDATED", m.body().path("type").asText(), "type");
        assertTopFormat(m.body().path("top"));
        assertTrue(Leaderboard.sameTop(m.body().path("top"), get),
                () -> "message top must equal GET /api/leaderboard\nmessage: " + m.body().path("top") + "\nGET:     " + get);
        risingPlayer = p;
        risingNet = w.subtract(BigDecimal.ONE);
    }

    @Test
    @Order(5)
    @Tag("LDB-06")
    @DisplayName("CL-078 a change of only netResult of a listed player sends a message")
    void messageOnNetResultChange() {
        long p = risingPlayer != 0 ? risingPlayer : winner(Leaderboard.winningAmount());
        if (risingPlayer == 0) {
            risingNet = Json.dec(Leaderboard.entry(
                    Leaderboard.awaitTop(t -> Leaderboard.entry(t, p) != null, FRESHNESS), p).get("netResult"));
        }
        socket.awaitQuiet(QUIET, FRESHNESS.plusSeconds(5));
        int from = socket.size();
        Api.depositOk(p, "10");
        Api.finishOk(Api.startOk(p, "1").path("id").asLong(), "50");
        BigDecimal expected = risingNet.add(new BigDecimal("49"));
        LeaderboardSocket.Message m = socket.await(from, b -> {
            JsonNode e = Leaderboard.entry(b.path("top"), p);
            return e != null && Json.sameValue(expected, Json.dec(e.get("netResult")));
        }, FRESHNESS);
        assertNotNull(m, () -> "no message with netResult " + expected + " within 30 s; messages: "
                + socket.messages().subList(from, socket.size()));
    }

    @Test
    @Order(6)
    @Tag("LDB-06")
    @DisplayName("CL-079 no message while the top does not change (a session outside the top)")
    void noMessageWithoutChange() {
        socket.awaitQuiet(QUIET, FRESHNESS.plusSeconds(5));
        JsonNode topBefore = Api.leaderboard();
        int from = socket.size();
        long p = Api.fundedPlayer("10");
        Api.finishOk(Api.startOk(p, "1").path("id").asLong(), "0"); // net -1: not listed, top unchanged
        socket.awaitQuiet(Duration.ofSeconds(8), Duration.ofSeconds(8));
        List<LeaderboardSocket.Message> extra = socket.messages().subList(from, socket.size());
        assertTrue(Leaderboard.sameTop(topBefore, Api.leaderboard()), "precondition: the top did not change");
        assertTrue(extra.isEmpty(), () -> "messages sent although the top did not change: " + extra);
    }

    @Test
    @Order(7)
    @Tag("LDB-06")
    @DisplayName("CL-080 no two consecutive messages carry the same top")
    void consecutiveMessagesDiffer() {
        List<LeaderboardSocket.Message> all = socket.messages();
        assertTrue(!all.isEmpty(), "precondition: messages were received during this class");
        for (int i = 1; i < all.size(); i++) {
            JsonNode a = all.get(i - 1).body().path("top");
            JsonNode b = all.get(i).body().path("top");
            int idx = i;
            assertTrue(!Leaderboard.sameTop(a, b), () -> "messages " + (idx - 1) + " and " + idx + " repeat the top " + b);
        }
    }
}
