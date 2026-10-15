package com.arcade.player.events;

import java.math.BigDecimal;

/**
 * Published to the "game-events" topic (key = playerId, value = JSON) when the client finishes a session.
 * Times are ISO-8601 strings to keep the cross-service contract trivial.
 */
public record SessionFinishedEvent(
        String eventId,
        String type,
        Long sessionId,
        Long playerId,
        String game,
        BigDecimal bet,
        BigDecimal win,
        String finishedAt
) {
    public static final String TYPE = "SESSION_FINISHED";
}
