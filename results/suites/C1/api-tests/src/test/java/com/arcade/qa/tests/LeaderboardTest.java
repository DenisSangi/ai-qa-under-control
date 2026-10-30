package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Events;
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
import java.util.ArrayList;
import java.util.List;

import static com.arcade.qa.support.Asserts.assertAmount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private record Winner(long id, String username, BigDecimal net) {
    }

    /** As {@link #winner}, for a player created with a known username; net = win - 1. */
    private static Winner namedWinner(BigDecimal win) {
        String username = Api.newUsername();
        long p = Api.body(Api.createPlayerResponse(username)).path("id").asLong();
        Api.depositOk(p, "10");
        Api.finishOk(Api.startOk(p, "1").path("id").asLong(), win.toPlainString());
        return new Winner(p, username, win.subtract(BigDecimal.ONE));
    }

    /** LDB-01 format: fields present, values judged by value (the specification fixes no JSON types here). */
    static void assertTopFormat(JsonNode top) {
        assertTrue(top.isArray(), () -> "top must be a list: " + top);
        assertTrue(top.size() <= 10, () -> "at most 10 entries: " + top.size() + " " + top);
        BigDecimal prev = null;
        for (JsonNode e : top) {
            GameSessionTest.assertFields(e, "playerId", "username", "netResult");
            BigDecimal n = Json.dec(e.get("netResult"));
            assertTrue(n != null && n.signum() > 0, () -> "LDB-04: only positive netResult is listed: " + e);
            BigDecimal p = prev;
            assertTrue(p == null || p.compareTo(n) >= 0, () -> "sorted by netResult descending: " + top);
            prev = n;
        }
    }

    @Test
    @Order(1)
    @Tag("LDB-01")
    @Tag("LDB-04")
    @Tag("LDB-03")
    @DisplayName("CL-074 GET /api/leaderboard: 200, at most 10 {playerId, username, netResult}, descending, positive")
    void topFormat() {
        // 11 players with a net result above every other player's: the top must list the best 10 of them only.
        BigDecimal base = Leaderboard.winningAmount();
        List<Winner> winners = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            winners.add(namedWinner(base.add(BigDecimal.valueOf(i))));
        }
        Winner eleventh = winners.get(0);
        List<Winner> best = winners.subList(1, 11);
        JsonNode top = Leaderboard.awaitTop(t -> best.stream().allMatch(w -> Leaderboard.entry(t, w.id()) != null),
                FRESHNESS);
        assertTopFormat(top);
        for (Winner w : best) {
            JsonNode e = Leaderboard.entry(top, w.id());
            assertNotNull(e, () -> "player " + w.id() + " must be listed: " + top);
            assertEquals(w.username(), e.path("username").asText(), "username");
            assertAmount(w.net().toPlainString(), e.get("netResult"), "netResult = win - bet of player " + w.id());
        }
        assertNull(Leaderboard.entry(top, eleventh.id()),
                () -> "at most 10 entries: the 11th best player " + eleventh.id() + " must not be listed: " + top);
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
        long lastChange = System.nanoTime();
        BigDecimal expected = w.subtract(new BigDecimal("30"));
        JsonNode top = Leaderboard.awaitTop(t -> {
            JsonNode e = Leaderboard.entry(t, p);
            return e != null && Json.sameValue(expected, Json.dec(e.get("netResult")));
        }, FRESHNESS);
        JsonNode e = Leaderboard.entry(top, p);
        assertNotNull(e, () -> "player must be listed: " + top);
        assertAmount(expected.toPlainString(), e.get("netResult"), "netResult (W - 10 + 0 - 20)");
        // The EXPIRED and the ACTIVE session must not be counted by any later update either: read again once the
        // freshness bound (LDB-05) has passed since the last session change.
        Events.sleep(Math.max(0, FRESHNESS.toMillis() - (System.nanoTime() - lastChange) / 1_000_000));
        JsonNode later = Api.leaderboard();
        JsonNode l = Leaderboard.entry(later, p);
        assertNotNull(l, () -> "player must still be listed 30 s after the last session change: " + later);
        assertAmount(expected.toPlainString(), l.get("netResult"),
                "netResult 30 s after the last session change (EXPIRED 30 and ACTIVE 40 not counted)");
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
        Leaderboard.Read read = Leaderboard.awaitTopRead(t -> Leaderboard.entry(t, p) != null, FRESHNESS.plusSeconds(1));
        JsonNode top = read.top();
        JsonNode e = Leaderboard.entry(top, p);
        assertNotNull(e, () -> "not reflected within 30 s: " + top);
        // Judged by the requests' send times, so polling granularity cannot fail a reflection made within 30 s.
        assertFalse(read.missedAfter(t0 + FRESHNESS.toNanos()),
                () -> "a GET sent more than 30 s after the finish response did not show the session yet");
        assertAmount(expected.toPlainString(), e.get("netResult"), "netResult");
    }

    @Test
    @Order(4)
    @Tag("LDB-06")
    @Tag("LDB-05")
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
        int from = socket.size();
        // Own actions: a top change, two finishes that leave the top unchanged, another top change.
        long x = winner(Leaderboard.winningAmount());
        assertNotNull(socket.await(from, b -> Leaderboard.entry(b.path("top"), x) != null, FRESHNESS),
                () -> "precondition: no message with the new entry " + x + "; messages: " + socket.messages());
        long loser = Api.fundedPlayer("10");
        Api.finishOk(Api.startOk(loser, "1").path("id").asLong(), "0"); // net -1: not listed
        Api.finishOk(Api.startOk(x, "5").path("id").asLong(), "5");     // net change 0: the top stays the same
        long y = winner(Leaderboard.winningAmount());
        assertNotNull(socket.await(from, b -> Leaderboard.entry(b.path("top"), y) != null, FRESHNESS),
                () -> "precondition: no message with the new entry " + y + "; messages: " + socket.messages());
        socket.awaitQuiet(QUIET, FRESHNESS.plusSeconds(5));
        List<LeaderboardSocket.Message> all = socket.messages();
        assertTrue(all.size() - from >= 2, "precondition: the two top changes were sent");
        for (int i = 1; i < all.size(); i++) {
            JsonNode a = all.get(i - 1).body().path("top");
            JsonNode b = all.get(i).body().path("top");
            int idx = i;
            assertTrue(!Leaderboard.sameTop(a, b), () -> "messages " + (idx - 1) + " and " + idx + " repeat the top " + b);
        }
    }
}
