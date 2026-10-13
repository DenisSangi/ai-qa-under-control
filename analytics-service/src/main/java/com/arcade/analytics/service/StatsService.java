package com.arcade.analytics.service;

import com.arcade.analytics.domain.PlayerStats;
import com.arcade.analytics.kafka.GameFinishedEvent;
import com.arcade.analytics.repo.PlayerStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class StatsService {

    private final PlayerStatsRepository repo;

    public StatsService(PlayerStatsRepository repo) {
        this.repo = repo;
    }

    /**
     * Kafka gives at-least-once delivery, so this may run twice for the same
     * event — aggregates would then double-count. Idempotency (dedup by
     * eventId) is left out on purpose: finding this is a QA exercise.
     */
    @Transactional
    public void apply(GameFinishedEvent event) {
        PlayerStats stats = repo.findById(event.playerId())
                .orElseGet(() -> new PlayerStats(event.playerId()));
        stats.setTotalSessions(stats.getTotalSessions() + 1);
        stats.setTotalBet(stats.getTotalBet().add(event.bet()));
        stats.setTotalWin(stats.getTotalWin().add(event.win()));
        stats.setUpdatedAt(Instant.now());
        repo.save(stats);
    }
}
