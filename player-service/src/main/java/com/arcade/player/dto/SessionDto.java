package com.arcade.player.dto;

import com.arcade.player.domain.GameSession;

import java.math.BigDecimal;
import java.time.Instant;

public record SessionDto(
        Long id,
        Long playerId,
        String game,
        BigDecimal betAmount,
        BigDecimal winAmount,
        String status,
        Instant startedAt,
        Instant finishedAt
) {
    public static SessionDto from(GameSession s) {
        return new SessionDto(
                s.getId(),
                s.getPlayer().getId(),
                s.getGame(),
                s.getBetAmount(),
                s.getWinAmount(),
                s.getStatus().name(),
                s.getStartedAt(),
                s.getFinishedAt()
        );
    }
}
