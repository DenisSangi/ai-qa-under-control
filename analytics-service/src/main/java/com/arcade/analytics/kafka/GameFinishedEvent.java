package com.arcade.analytics.kafka;

import java.math.BigDecimal;

/**
 * Deliberately a COPY of the producer's record, not a shared library:
 * in microservices the JSON on the wire is the contract, and each side owns
 * its own model. Breaking this contract is exactly what contract testing catches.
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
