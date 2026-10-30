package com.arcade.apitests;

import com.arcade.apitests.support.Api.PlayerRef;
import com.arcade.apitests.support.Db;
import com.arcade.apitests.support.Json;
import com.arcade.apitests.support.LeaderboardSocket;
import com.arcade.apitests.support.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.arcade.apitests.support.Api.expect;
import static com.arcade.apitests.support.Api.finishSessionOk;
import static com.arcade.apitests.support.Api.fmt;
import static com.arcade.apitests.support.Api.getSession;
import static com.arcade.apitests.support.Api.leaderboard;
import static com.arcade.apitests.support.Api.newPlayer;
import static com.arcade.apitests.support.Api.playRound;
import static com.arcade.apitests.support.Api.startSessionOk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Top 10 by net result (win - bet) of FINISHED sessions in the current period (1st-11th, 11th-21st,
 * 21st-1st, UTC, service clock), positive results only. Refreshed after every finish and every 5s.
 */
@DisplayName("Leaderboard")
class LeaderboardTest extends ApiTest {

    /** Scheduled refresh interval is 5s; allow a margin. */
    private static final Duration REFRESH = Duration.ofSeconds(12);

    @Test
    @DisplayName("LB-01 the REST top equals an independent computation from the database")
    void matchesDatabase() {
        await().atMost(REFRESH).pollInterval(Duration.ofMillis(500)).untilAsserted(this::assertMatchesDatabase);
    }

    @Test
    @DisplayName("LB-02 a finished winning session is on the leaderboard right after the finish response")
    void winnerVisibleImmediately() {
        PlayerRef player = newPlayer("10.00");
        BigDecimal win = currentBestNet().add(new BigDecimal("1000.00"));

        playRound(player.id(), "1.00", win.toPlainString());

        JsonNode top = leaderboard();
        assertThat(top).isNotEmpty();
        assertThat(top.get(0).get("playerId").asLong()).as("leader of %s", top).isEqualTo(player.id());
        assertThat(Json.text(top.get(0), "username")).isEqualTo(player.username());
        assertThat(Json.dec(top.get(0), "netResult")).isEqualByComparingTo(win.subtract(BigDecimal.ONE));
    }

    @Test
    @DisplayName("LB-04 at three consecutive period boundaries (11th, 21st, 1st in some order) the previous period drops out and a push is sent")
    void periodBoundaries() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            crossBoundary();
        }
    }

    @Test
    @DisplayName("LB-03 in a fresh period: results are summed per player, only positive FINISHED results count, at most 10, best first")
    void freshPeriodContents() {
        Instant now = TestClock.now();
        TestClock.advanceTo(TestClock.leaderboardPeriod(now)[1].plusSeconds(1));

        PlayerRef winner = newPlayer("100.00");
        playRound(winner.id(), "10.00", "80.00");
        playRound(winner.id(), "20.00", "0");
        PlayerRef even = newPlayer("100.00");
        playRound(even.id(), "10.00", "10.00");
        PlayerRef loser = newPlayer("100.00");
        playRound(loser.id(), "10.00", "5.00");
        PlayerRef expired = newPlayer("100.00");
        long expiring = startSessionOk(expired.id(), "10.00").get("id").asLong();
        TestClock.advance(Duration.ofSeconds(61));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(Json.text(expect(getSession(expiring), 200).json(), "status")).isEqualTo("EXPIRED"));
        // eleven more winners with net results 10, 20, ..., 110
        List<PlayerRef> graded = new ArrayList<>();
        for (int net = 10; net <= 110; net += 10) {
            PlayerRef p = newPlayer("10.00");
            playRound(p.id(), "1.00", String.valueOf(net + 1));
            graded.add(p);
        }

        JsonNode top = leaderboard();

        assertThat(top).hasSize(10);
        List<String> nets = new ArrayList<>();
        top.forEach(e -> nets.add(fmt(Json.dec(e, "netResult"))));
        assertThat(nets).containsExactly("110.00", "100.00", "90.00", "80.00", "70.00", "60.00", "50.00", "50.00",
                "40.00", "30.00");
        Map<Long, JsonNode> byId = byId(top);
        assertThat(Json.dec(byId.get(winner.id()), "netResult")).as("winner's two rounds summed").isEqualByComparingTo("50.00");
        assertThat(byId).doesNotContainKeys(even.id(), loser.id(), expired.id(), graded.get(0).id(), graded.get(1).id());
        assertMatchesDatabase();
    }

    @Test
    @DisplayName("LB-05 a change of the top is pushed over the WebSocket")
    void pushOnChange() throws InterruptedException {
        try (LeaderboardSocket first = LeaderboardSocket.connect(); LeaderboardSocket second = LeaderboardSocket.connect()) {
            PlayerRef player = newPlayer("10.00");
            BigDecimal win = currentBestNet().add(new BigDecimal("1000.00"));

            playRound(player.id(), "1.00", win.toPlainString());

            for (LeaderboardSocket socket : List.of(first, second)) {
                JsonNode push = socket.await(m -> "LEADERBOARD_UPDATED".equals(Json.text(m, "type"))
                        && m.path("top").path(0).path("playerId").asLong() == player.id(), Duration.ofSeconds(5));
                assertThat(push).as("LEADERBOARD_UPDATED with the new leader on every client").isNotNull();
                assertThat(Json.dec(push.get("top").get(0), "netResult")).isEqualByComparingTo(win.subtract(BigDecimal.ONE));
                assertThat(push.get("top").size()).isLessThanOrEqualTo(10);
                assertThat(entries(push.get("top"))).containsExactlyElementsOf(entries(leaderboard()));
            }
        }
    }

    @Test
    @DisplayName("LB-06 nothing is pushed when the top does not change")
    void noPushWithoutChange() throws InterruptedException {
        // An earlier clock jump may have crossed a period boundary: let the scheduled refresh catch up first.
        await().atMost(REFRESH).pollInterval(Duration.ofMillis(500)).untilAsserted(this::assertMatchesDatabase);
        try (LeaderboardSocket socket = LeaderboardSocket.connect()) {
            socket.during(Duration.ofSeconds(1));
            socket.clear();
            PlayerRef loser = newPlayer("20.00");

            playRound(loser.id(), "10.00", "0");

            List<JsonNode> received = socket.during(Duration.ofSeconds(7));
            assertThat(received).as("messages after a finish that does not change the top").isEmpty();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void assertMatchesDatabase() {
        JsonNode top = leaderboard();
        Instant[] period = TestClock.leaderboardPeriod(TestClock.now());
        List<Db.LeaderRow> expected = Db.leaderboard(period[0], period[1]);

        assertThat(top.size()).as("size of %s", top).isEqualTo(Math.min(10, expected.size()));
        // Ties may be ordered either way, so compare the sequence of results and each entry on its own.
        List<String> nets = new ArrayList<>();
        top.forEach(e -> nets.add(fmt(Json.dec(e, "netResult"))));
        assertThat(nets).containsExactlyElementsOf(
                expected.stream().limit(10).map(r -> fmt(r.netResult())).toList());
        Map<Long, Db.LeaderRow> rows = expected.stream().collect(Collectors.toMap(Db.LeaderRow::playerId, Function.identity()));
        for (JsonNode entry : top) {
            Db.LeaderRow row = rows.get(entry.get("playerId").asLong());
            assertThat(row).as("entry %s in the database top", entry).isNotNull();
            assertThat(Json.text(entry, "username")).isEqualTo(row.username());
            assertThat(Json.dec(entry, "netResult")).isEqualByComparingTo(row.netResult());
        }
    }

    /** Best net result of the current period according to the database (0 if none). */
    private static BigDecimal currentBestNet() {
        Instant[] period = TestClock.leaderboardPeriod(TestClock.now());
        List<Db.LeaderRow> rows = Db.leaderboard(period[0], period[1]);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0).netResult().max(BigDecimal.ZERO);
    }

    /**
     * Puts a winner on the board just before a boundary and starts a session 30s before it; crosses
     * the boundary; the winner drops out, a push is sent, and the straddling session finished after
     * the boundary counts in the new period (sessions belong to the period they finished in).
     */
    private void crossBoundary() throws InterruptedException {
        Instant end = moveCloseToPeriodEnd();
        PlayerRef previous = newPlayer("10.00");
        playRound(previous.id(), "1.00", currentBestNet().add(new BigDecimal("1000.00")).toPlainString());
        assertThat(ids(leaderboard())).as("winner just before %s", end).contains(previous.id());

        TestClock.advanceTo(end.minusSeconds(30));
        PlayerRef straddling = newPlayer("10.00");
        long session = startSessionOk(straddling.id(), "1.00").get("id").asLong();

        try (LeaderboardSocket socket = LeaderboardSocket.connect()) {
            Instant after = TestClock.advanceTo(end.plusSeconds(1));
            assertThat(TestClock.leaderboardPeriod(after)[0]).isEqualTo(end);

            await().atMost(REFRESH).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                    assertThat(ids(leaderboard())).as("previous-period winner after %s", end).doesNotContain(previous.id()));
            JsonNode push = socket.await(m -> "LEADERBOARD_UPDATED".equals(Json.text(m, "type"))
                    && !ids(m.get("top")).contains(previous.id()), REFRESH);
            assertThat(push).as("LEADERBOARD_UPDATED after the rollover at %s", end).isNotNull();
        }

        finishSessionOk(session, "11.00");
        Map<Long, JsonNode> top = byId(leaderboard());
        assertThat(top).as("session started before and finished after %s", end).containsKey(straddling.id());
        assertThat(Json.dec(top.get(straddling.id()), "netResult")).isEqualByComparingTo("10.00");
    }

    /** Puts the clock 2 minutes before the end of a leaderboard period; returns that end. */
    private static Instant moveCloseToPeriodEnd() {
        Instant now = TestClock.now();
        Instant end = TestClock.leaderboardPeriod(now)[1];
        if (Duration.between(now, end).compareTo(Duration.ofMinutes(1)) < 0) {
            // too close to finish a round safely before this boundary: use the next one
            now = TestClock.advanceTo(end.plusSeconds(1));
            end = TestClock.leaderboardPeriod(now)[1];
        }
        TestClock.advanceTo(end.minus(Duration.ofMinutes(2)));
        return end;
    }

    private static List<Long> ids(JsonNode top) {
        List<Long> ids = new ArrayList<>();
        top.forEach(e -> ids.add(e.get("playerId").asLong()));
        return ids;
    }

    private static Map<Long, JsonNode> byId(JsonNode top) {
        Map<Long, JsonNode> map = new java.util.LinkedHashMap<>();
        top.forEach(e -> map.put(e.get("playerId").asLong(), e));
        return map;
    }

    private static List<String> entries(JsonNode top) {
        List<String> list = new ArrayList<>();
        top.forEach(e -> list.add(e.get("playerId").asLong() + ":" + Json.text(e, "username") + ":" + fmt(Json.dec(e, "netResult"))));
        return list;
    }
}
