package com.arcade.analytics.web;

import com.arcade.analytics.domain.PlayerStats;
import com.arcade.analytics.repo.PlayerStatsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Same data as the gRPC endpoint — lets you compare REST vs gRPC side by side.
 */
@RestController
public class StatsController {

    public record StatsDto(long playerId, long totalSessions, BigDecimal totalBet, BigDecimal totalWin) {

        static StatsDto from(PlayerStats s) {
            return new StatsDto(s.getPlayerId(), s.getTotalSessions(), s.getTotalBet(), s.getTotalWin());
        }
    }

    private final PlayerStatsRepository repo;

    public StatsController(PlayerStatsRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/api/stats/{playerId}")
    public ResponseEntity<StatsDto> get(@PathVariable Long playerId) {
        return repo.findById(playerId)
                .map(s -> ResponseEntity.ok(StatsDto.from(s)))
                .orElse(ResponseEntity.notFound().build());
    }
}
