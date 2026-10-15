package com.arcade.player.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires sessions that were not finished in time. Polls once a second on the service clock, so a
 * session expires within about a second after its deadline, also when the test clock jumps forward.
 */
@Component
public class SessionExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiryJob.class);

    private final GameService games;

    public SessionExpiryJob(GameService games) {
        this.games = games;
    }

    @Scheduled(fixedDelayString = "${app.session.expiry-poll-interval}")
    public void expireOverdueSessions() {
        for (Long sessionId : games.findOverdue()) {
            try {
                games.expire(sessionId);
            } catch (RuntimeException e) {
                log.error("failed to expire session {}", sessionId, e);
            }
        }
    }
}
