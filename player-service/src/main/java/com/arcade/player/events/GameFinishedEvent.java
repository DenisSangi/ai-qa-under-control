package com.arcade.player.events;

import java.math.BigDecimal;

/**
 * Published to the "game-events" topic (key = playerId, value = JSON).
 * finishedAt is an ISO-8601 string to keep the cross-service contract trivial.
 */
public record GameFinishedEvent(
        String eventId,
        Long sessionId,
        Long playerId,
        String game,
        BigDecimal bet,
        BigDecimal win,
        String finishedAt
) {
}
