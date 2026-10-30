package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Leaderboard;
import com.arcade.qa.support.LeaderboardSocket;
import com.arcade.qa.support.TestClock;
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

import java.time.Duration;
import java.time.Instant;

import static com.arcade.qa.support.Asserts.assertAmount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Crosses a leaderboard period boundary with the test clock. The new period is in the future of every earlier
 * run (the clock only moves forward), so it starts with no finished sessions.
 */
@Order(14)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("F-06 Leaderboard: periods")
class LeaderboardPeriodTest extends ApiTest {

    private LeaderboardSocket socket;
    private long playerC;
    private long sessionC;
    private long playerD;
    private long playerE;

    @BeforeAll
    void openSocket() {
        socket = LeaderboardSocket.open();
    }

    @AfterAll
    void closeSocket() {
        if (socket != null) {
            socket.close();
        }
    }

    @Test
    @Order(1)
    @Tag("LDB-02")
    @Tag("LDB-07")
    @Tag("LDB-04")
    @DisplayName("CL-081 at a period boundary the top is recomputed within 30 s (empty) and sent on the WebSocket")
    void newPeriodRecomputed() {
        Instant now = TestClock.now();
        Instant boundary = Leaderboard.nextBoundary(now);
        if (Duration.between(now, boundary).compareTo(Duration.ofMinutes(3)) < 0) {
            boundary = Leaderboard.nextBoundary(boundary.plusSeconds(1));
        }
        TestClock.advanceTo(boundary.minusSeconds(120));

        long a = Api.fundedPlayer("10");
        Api.finishOk(Api.startOk(a, "1").path("id").asLong(), Leaderboard.winningAmount().toPlainString());
        JsonNode before = Leaderboard.awaitTop(t -> Leaderboard.entry(t, a) != null, LeaderboardTest.FRESHNESS);
        if (Leaderboard.entry(before, a) == null) {
            fail("precondition failed: the winner of the old period is not in the top: " + before);
        }

        playerC = Api.fundedPlayer("10");
        playerD = Api.fundedPlayer("10");
        playerE = Api.fundedPlayer("10");
        TestClock.advanceTo(boundary.minusSeconds(10));
        sessionC = Api.startOk(playerC, "1").path("id").asLong(); // started in the old period
        int from = socket.size();
        TestClock.advanceTo(boundary.plusSeconds(2));
        long t0 = System.nanoTime();

        JsonNode top = Leaderboard.awaitTop(JsonNode::isEmpty, LeaderboardTest.FRESHNESS);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(top.isEmpty(), () -> "the new period has no finished sessions, the top must be empty; GET after "
                + ms + " ms: " + top);
        Duration left = LeaderboardTest.FRESHNESS.minusNanos(System.nanoTime() - t0);
        LeaderboardSocket.Message m = socket.await(from, b -> b.path("top").isEmpty(),
                left.isNegative() ? Duration.ZERO : left);
        assertNotNull(m, () -> "LDB-07: no message with the new period's top within 30 s; messages: "
                + socket.messages().subList(from, socket.size()));
    }

    @Test
    @Order(2)
    @Tag("LDB-02")
    @Tag("LDB-03")
    @Tag("LDB-04")
    @DisplayName("CL-082 a session finished after the boundary counts in the new period; net <= 0 is not listed")
    void finishedAtDecides() {
        if (sessionC == 0) {
            fail("precondition failed: the boundary scenario (CL-081) did not reach the new period");
        }
        Api.finishOk(Api.startOk(playerD, "5").path("id").asLong(), "5");   // net 0
        Api.finishOk(Api.startOk(playerE, "5").path("id").asLong(), "0");   // net -5
        Api.finishOk(sessionC, "101");                                       // net 100, started in the old period
        int from = 0;
        JsonNode top = Leaderboard.awaitTop(t -> Leaderboard.entry(t, playerC) != null, LeaderboardTest.FRESHNESS);
        JsonNode c = Leaderboard.entry(top, playerC);
        assertNotNull(c, () -> "C finished in the new period and must be listed: " + top);
        assertAmount("100", c.get("netResult"), "netResult of C");
        assertEquals(1, top.size(), () -> "only C has a positive net result in the new period: " + top);
        LeaderboardSocket.Message m = socket.await(from,
                b -> b.path("top").size() == 1 && Leaderboard.entry(b.path("top"), playerC) != null,
                LeaderboardTest.FRESHNESS);
        assertNotNull(m, () -> "no message with C in the top; messages: " + socket.messages());
    }
}
