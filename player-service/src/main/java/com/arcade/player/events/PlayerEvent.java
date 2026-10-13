package com.arcade.player.events;

import java.math.BigDecimal;

/**
 * Published to the "player-events" topic. type: REGISTERED | DEPOSIT.
 */
public record PlayerEvent(
        String eventId,
        String type,
        Long playerId,
        BigDecimal amount,
        String at
) {
}
