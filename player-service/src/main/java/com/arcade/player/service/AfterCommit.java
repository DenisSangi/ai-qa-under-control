package com.arcade.player.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Side effects that must not run for an operation that did not take effect: events, the leaderboard
 * broadcast. Inside a transaction the action waits for the commit and is dropped on a rollback;
 * outside one it runs at once.
 */
final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    static void run(String what, Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runLogged(what, action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runLogged(what, action);
            }
        });
    }

    // The operation has already taken effect: a failure here must not turn its response into an error.
    private static void runLogged(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("{} failed after the operation took effect", what, e);
        }
    }
}
