package com.arcade.player.service;

import com.arcade.player.domain.GameSession;
import com.arcade.player.domain.PaymentTransaction;
import com.arcade.player.domain.Player;
import com.arcade.player.domain.SessionStatus;
import com.arcade.player.domain.TxType;
import com.arcade.player.dto.SessionDto;
import com.arcade.player.dto.StartSessionRequest;
import com.arcade.player.events.SessionExpiredEvent;
import com.arcade.player.events.SessionFinishedEvent;
import com.arcade.player.events.SessionStartedEvent;
import com.arcade.player.exception.BusinessException;
import com.arcade.player.exception.NotFoundException;
import com.arcade.player.repo.GameSessionRepository;
import com.arcade.player.repo.PlayerRepository;
import com.arcade.player.repo.TransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Game sessions. A session must be finished before startedAt + timeout; after that it can only
 * expire, and expiry refunds the bet ({@link SessionExpiryJob}).
 */
@Service
public class GameService {

    private final PlayerRepository players;
    private final GameSessionRepository sessions;
    private final TransactionRepository transactions;
    private final EventPublisher events;
    private final LeaderboardService leaderboard;
    private final Clock clock;
    private final Duration timeout;

    public GameService(PlayerRepository players,
                       GameSessionRepository sessions,
                       TransactionRepository transactions,
                       EventPublisher events,
                       LeaderboardService leaderboard,
                       Clock clock,
                       @Value("${app.session.timeout}") Duration timeout) {
        this.players = players;
        this.sessions = sessions;
        this.transactions = transactions;
        this.events = events;
        this.leaderboard = leaderboard;
        this.clock = clock;
        this.timeout = timeout;
    }

    /** Check order: player exists (404), bet range (422), no active session (409), balance (422). */
    @Transactional
    public SessionDto start(StartSessionRequest request) {
        Player player = players.findByIdForUpdate(request.playerId())
                .orElseThrow(() -> new NotFoundException("player " + request.playerId() + " not found"));
        AmountRange.BET.check(request.bet());
        if (sessions.existsByPlayerIdAndStatus(player.getId(), SessionStatus.ACTIVE)) {
            throw new BusinessException("ACTIVE_SESSION_EXISTS", HttpStatus.CONFLICT,
                    "player " + player.getId() + " already has an active session");
        }
        if (player.getBalance().compareTo(request.bet()) < 0) {
            throw new BusinessException("INSUFFICIENT_FUNDS", HttpStatus.UNPROCESSABLE_ENTITY,
                    "balance " + player.getBalance() + " is lower than bet " + request.bet());
        }
        Instant now = clock.instant();
        player.setBalance(player.getBalance().subtract(request.bet()));
        GameSession session = sessions.save(new GameSession(player, request.game(), request.bet(), now));
        transactions.save(new PaymentTransaction(player.getId(), TxType.BET, request.bet(), now));
        events.publishSessionEvent(player.getId(), new SessionStartedEvent(
                UUID.randomUUID().toString(), SessionStartedEvent.TYPE, session.getId(), player.getId(),
                session.getGame(), session.getBetAmount(), now.toString()));
        return SessionDto.from(session);
    }

    @Transactional
    public SessionDto finish(Long sessionId, BigDecimal win) {
        // Lock order: session, then player. Nothing locks a player and then a session, so no deadlock.
        GameSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new NotFoundException("session " + sessionId + " not found"));
        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw new BusinessException("SESSION_NOT_ACTIVE", HttpStatus.CONFLICT,
                    "session " + sessionId + " is " + session.getStatus() + ", only ACTIVE sessions can be finished");
        }
        Instant now = clock.instant();
        if (isOverdue(session, now)) {
            throw new BusinessException("SESSION_NOT_ACTIVE", HttpStatus.CONFLICT,
                    "session " + sessionId + " was not finished within " + timeout.toSeconds() + " seconds");
        }
        Player player = players.findByIdForUpdate(session.getPlayer().getId())
                .orElseThrow(() -> new IllegalStateException("session " + sessionId + " has no player"));
        player.setBalance(player.getBalance().add(win));
        session.setWinAmount(win);
        session.setStatus(SessionStatus.FINISHED);
        session.setFinishedAt(now);
        if (win.signum() > 0) {
            transactions.save(new PaymentTransaction(player.getId(), TxType.WIN, win, now));
        }
        events.publishSessionEvent(player.getId(), new SessionFinishedEvent(
                UUID.randomUUID().toString(), SessionFinishedEvent.TYPE, sessionId, player.getId(),
                session.getGame(), session.getBetAmount(), win, now.toString()));
        AfterCommit.run("leaderboard refresh", leaderboard::refresh);
        return SessionDto.from(session);
    }

    @Transactional(readOnly = true)
    public SessionDto get(Long sessionId) {
        return sessions.findById(sessionId)
                .map(SessionDto::from)
                .orElseThrow(() -> new NotFoundException("session " + sessionId + " not found"));
    }

    /** Sessions that are still ACTIVE although their finish deadline has passed. */
    @Transactional(readOnly = true)
    public List<Long> findOverdue() {
        return sessions.findActiveStartedAtOrBefore(clock.instant().minus(timeout));
    }

    /** Expires one overdue session and refunds its bet; does nothing if it was finished meanwhile. */
    @Transactional
    public void expire(Long sessionId) {
        GameSession session = sessions.findByIdForUpdate(sessionId).orElse(null);
        Instant now = clock.instant();
        if (session == null || session.getStatus() != SessionStatus.ACTIVE || !isOverdue(session, now)) {
            return;
        }
        Player player = players.findByIdForUpdate(session.getPlayer().getId())
                .orElseThrow(() -> new IllegalStateException("session " + sessionId + " has no player"));
        player.setBalance(player.getBalance().add(session.getBetAmount()));
        session.setStatus(SessionStatus.EXPIRED);
        transactions.save(new PaymentTransaction(player.getId(), TxType.REFUND, session.getBetAmount(), now));
        events.publishSessionEvent(player.getId(), new SessionExpiredEvent(
                UUID.randomUUID().toString(), SessionExpiredEvent.TYPE, sessionId, player.getId(),
                session.getGame(), session.getBetAmount(), now.toString()));
    }

    private boolean isOverdue(GameSession session, Instant now) {
        return !now.isBefore(session.getStartedAt().plus(timeout));
    }
}
