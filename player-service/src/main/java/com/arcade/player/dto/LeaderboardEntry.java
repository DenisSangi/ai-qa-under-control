package com.arcade.player.dto;

import java.math.BigDecimal;

/** netResult: sum of win - bet over the player's finished sessions of the current period. */
public record LeaderboardEntry(Long playerId, String username, BigDecimal netResult) {
}
