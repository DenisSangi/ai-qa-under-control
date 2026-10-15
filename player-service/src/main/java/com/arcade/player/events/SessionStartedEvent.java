package com.arcade.player.events;

import java.math.BigDecimal;

/** Published to the "game-events" topic when a session starts and the bet is taken. */
public record SessionStartedEvent(
        String eventId,
        String type,
        Long sessionId,
        Long playerId,
        String game,
        BigDecimal bet,
        String startedAt
) {
    public static final String TYPE = "SESSION_STARTED";
}
