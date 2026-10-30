package com.arcade.qa.support;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State of one player over all observation points (SPEC §11): balance, journal, events in both topics, provider
 * requests. Used to verify "balance, journal and events do not change" and "the provider is not called".
 */
public final class Snapshot {

    /** Time to let a wrongly published event reach the collector before asserting that none was published. */
    public static final long EVENT_QUIET_MILLIS = 1000;

    private final long playerId;
    private final BigDecimal balance;
    private final List<Journal.Tx> journal;
    private final int playerEvents;
    private final int gameEvents;
    private final int payments;
    private final int payouts;

    private Snapshot(long playerId) {
        this.playerId = playerId;
        this.balance = Api.balance(playerId);
        this.journal = Journal.records(playerId);
        this.playerEvents = Events.of(Config.PLAYER_EVENTS, playerId).size();
        this.gameEvents = Events.of(Config.GAME_EVENTS, playerId).size();
        this.payments = ProviderStub.count(ProviderStub.PAYMENTS, playerId);
        this.payouts = ProviderStub.count(ProviderStub.PAYOUTS, playerId);
    }

    public static Snapshot of(long playerId) {
        return new Snapshot(playerId);
    }

    public BigDecimal balance() {
        return balance;
    }

    /** Balance, journal and events unchanged; the provider was not called. */
    public void assertUnchanged() {
        assertUnchanged(true);
    }

    /** As {@link #assertUnchanged()}, but provider calls are allowed (refusals that come from the provider). */
    public void assertUnchangedExceptProviderCalls() {
        assertUnchanged(false);
    }

    private void assertUnchanged(boolean providerNotCalled) {
        Events.sleep(EVENT_QUIET_MILLIS);
        BigDecimal nowBalance = Api.balance(playerId);
        List<Journal.Tx> nowJournal = Journal.records(playerId);
        List<Events.Event> pe = Events.of(Config.PLAYER_EVENTS, playerId);
        List<Events.Event> ge = Events.of(Config.GAME_EVENTS, playerId);
        assertAll("nothing must change for player " + playerId,
                () -> assertTrue(Json.sameValue(balance, nowBalance),
                        () -> "balance changed: " + balance + " -> " + nowBalance),
                () -> assertEquals(journal, nowJournal, "journal changed"),
                () -> assertEquals(playerEvents, pe.size(),
                        () -> "unexpected player-events: " + pe.subList(Math.min(playerEvents, pe.size()), pe.size())
                                .stream().map(Events.Event::raw).toList()),
                () -> assertEquals(gameEvents, ge.size(),
                        () -> "unexpected game-events: " + ge.subList(Math.min(gameEvents, ge.size()), ge.size())
                                .stream().map(Events.Event::raw).toList()),
                () -> {
                    if (providerNotCalled) {
                        assertEquals(payments, ProviderStub.count(ProviderStub.PAYMENTS, playerId),
                                "the provider must not be called (POST /payments)");
                        assertEquals(payouts, ProviderStub.count(ProviderStub.PAYOUTS, playerId),
                                "the provider must not be called (POST /payouts)");
                    }
                });
    }
}
