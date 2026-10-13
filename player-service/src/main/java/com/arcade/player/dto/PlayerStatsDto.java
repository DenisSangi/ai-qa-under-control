package com.arcade.player.dto;

public record PlayerStatsDto(
        long playerId,
        long totalSessions,
        String totalBet,
        String totalWin,
        String rtp
) {
}
