package com.arcade.analytics.kafka;

import java.math.BigDecimal;

/**
 * Deliberately a COPY of the producer's record, not a shared library:
 * in microservices the JSON on the wire is the contract, and each side owns
 * its own model.
 */
public record GameFinishedEvent(
        String eventId,
        String type,
        Long sessionId,
        Long playerId,
        String game,
        BigDecimal bet,
        BigDecimal win,
        String finishedAt
) {
}
