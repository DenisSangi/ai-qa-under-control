package com.arcade.player.events;

import java.math.BigDecimal;

/** Published to the "game-events" topic when a session was not finished in time and the bet is refunded. */
public record SessionExpiredEvent(
        String eventId,
        String type,
        Long sessionId,
        Long playerId,
        String game,
        BigDecimal bet,
        String expiredAt
) {
    public static final String TYPE = "SESSION_EXPIRED";
}
