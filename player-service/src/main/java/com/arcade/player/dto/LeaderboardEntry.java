package com.arcade.player.dto;

import java.math.BigDecimal;

public record LeaderboardEntry(Long playerId, String username, BigDecimal totalWin) {
}
