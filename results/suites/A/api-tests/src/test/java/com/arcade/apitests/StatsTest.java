package com.arcade.apitests;

import com.arcade.apitests.support.Api.PlayerRef;
import com.arcade.apitests.support.Db;
import com.arcade.apitests.support.Http;
import com.arcade.apitests.support.Json;
import com.arcade.apitests.support.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.arcade.apitests.support.Api.expect;
import static com.arcade.apitests.support.Api.expectError;
import static com.arcade.apitests.support.Api.newPlayer;
import static com.arcade.apitests.support.Api.playRound;
import static com.arcade.apitests.support.Api.startSessionOk;
import static com.arcade.apitests.support.Api.stats;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * GET /api/players/{id}/stats: player-service asks analytics-service over gRPC; analytics builds the
 * aggregates from SESSION_FINISHED events on Kafka, so the tests wait for them to arrive.
 */
@DisplayName("Player stats (Kafka -> analytics -> gRPC)")
class StatsTest extends ApiTest {

    private static final Duration KAFKA = Duration.ofSeconds(20);

    @Test
    @DisplayName("ST-01 a player without finished sessions has no stats (404)")
    void noStats() {
        PlayerRef player = newPlayer();
        expectError(stats(player.id()), 404, "NOT_FOUND");
        expectError(stats(9_000_000_000_000L), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("ST-02 totals and RTP (percent, 2 decimals) after finished sessions")
    void totalsAndRtp() {
        PlayerRef player = newPlayer("100.00");
        playRound(player.id(), "10.00", "0");
        playRound(player.id(), "20.00", "35.00");

        JsonNode stats = awaitSessions(player.id(), 2);

        assertThat(stats.get("playerId").asLong()).isEqualTo(player.id());
        assertThat(stats.get("totalSessions").asLong()).isEqualTo(2);
        assertThat(Json.text(stats, "totalBet")).isEqualTo("30.00");
        assertThat(Json.text(stats, "totalWin")).isEqualTo("35.00");
        assertThat(Json.text(stats, "rtp")).isEqualTo("116.67");
    }

    @Test
    @DisplayName("ST-02 a player who never won has RTP 0.00")
    void zeroRtp() {
        PlayerRef player = newPlayer("20.00");
        playRound(player.id(), "10.00", "0");

        JsonNode stats = awaitSessions(player.id(), 1);

        assertThat(Json.text(stats, "totalWin")).isEqualTo("0.00");
        assertThat(Json.text(stats, "rtp")).isEqualTo("0.00");
    }

    @Test
    @DisplayName("ST-03 expired and still active sessions are not counted")
    void onlyFinishedSessionsCount() {
        PlayerRef player = newPlayer("100.00");
        playRound(player.id(), "10.00", "15.00");
        long expiring = startSessionOk(player.id(), "40.00").get("id").asLong();
        TestClock.advance(Duration.ofSeconds(61));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(Db.sessionStatus(expiring)).isEqualTo("EXPIRED"));
        // Same key -> same partition -> in order: once this round is counted, the expiry event has been consumed too.
        playRound(player.id(), "5.00", "0");
        startSessionOk(player.id(), "7.00");

        JsonNode stats = awaitSessions(player.id(), 2);

        assertThat(stats.get("totalSessions").asLong()).as("stats %s", stats).isEqualTo(2);
        assertThat(Json.text(stats, "totalBet")).isEqualTo("15.00");
        assertThat(Json.text(stats, "totalWin")).isEqualTo("15.00");
        assertThat(Json.text(stats, "rtp")).isEqualTo("100.00");
    }

    /** Waits until analytics has counted at least {@code sessions} sessions and returns the stats. */
    private static JsonNode awaitSessions(long playerId, long sessions) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(KAFKA).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            Http.Resp resp = stats(playerId);
            assertThat(resp.status()).as("stats %s", resp).isEqualTo(200);
            last[0] = resp.json();
            assertThat(last[0].get("totalSessions").asLong()).isGreaterThanOrEqualTo(sessions);
        });
        return expect(stats(playerId), 200).json();
    }
}
