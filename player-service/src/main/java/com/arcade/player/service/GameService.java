package com.arcade.player.service;

import com.arcade.player.domain.GameSession;
import com.arcade.player.domain.PaymentTransaction;
import com.arcade.player.domain.Player;
import com.arcade.player.domain.SessionStatus;
import com.arcade.player.domain.TxType;
import com.arcade.player.dto.SessionDto;
import com.arcade.player.dto.StartSessionRequest;
import com.arcade.player.events.GameFinishedEvent;
import com.arcade.player.exception.BusinessException;
import com.arcade.player.exception.NotFoundException;
import com.arcade.player.repo.GameSessionRepository;
import com.arcade.player.repo.PlayerRepository;
import com.arcade.player.repo.TransactionRepository;
import com.arcade.player.ws.LeaderboardWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private final PlayerRepository players;
    private final GameSessionRepository sessions;
    private final TransactionRepository transactions;
    private final EventPublisher events;
    private final LeaderboardService leaderboard;
    private final LeaderboardWebSocketHandler ws;
    private final ObjectMapper mapper;

    public GameService(PlayerRepository players,
                       GameSessionRepository sessions,
                       TransactionRepository transactions,
                       EventPublisher events,
                       LeaderboardService leaderboard,
                       LeaderboardWebSocketHandler ws,
                       ObjectMapper mapper) {
        this.players = players;
        this.sessions = sessions;
        this.transactions = transactions;
        this.events = events;
        this.leaderboard = leaderboard;
        this.ws = ws;
        this.mapper = mapper;
    }

    @Transactional
    public SessionDto start(StartSessionRequest request) {
        Player player = players.findById(request.playerId())
                .orElseThrow(() -> new NotFoundException("player " + request.playerId() + " not found"));
        if (player.getBalance().compareTo(request.bet()) < 0) {
            throw new BusinessException("INSUFFICIENT_FUNDS", HttpStatus.UNPROCESSABLE_ENTITY,
                    "balance " + player.getBalance() + " is lower than bet " + request.bet());
        }
        player.setBalance(player.getBalance().subtract(request.bet()));
        GameSession session = sessions.save(new GameSession(player, request.game(), request.bet()));
        transactions.save(new PaymentTransaction(player.getId(), TxType.BET, request.bet()));
        return SessionDto.from(session);
    }

    @Transactional
    public SessionDto finish(Long sessionId, BigDecimal win) {
        GameSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("session " + sessionId + " not found"));
        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw new BusinessException("SESSION_NOT_ACTIVE", HttpStatus.CONFLICT,
                    "session " + sessionId + " is " + session.getStatus() + ", only ACTIVE sessions can be finished");
        }
        Player player = session.getPlayer();
        player.setBalance(player.getBalance().add(win));
        session.setWinAmount(win);
        session.setStatus(SessionStatus.FINISHED);
        session.setFinishedAt(Instant.now());
        if (win.signum() > 0) {
            transactions.save(new PaymentTransaction(player.getId(), TxType.WIN, win));
        }
        events.publishGameFinished(new GameFinishedEvent(
                UUID.randomUUID().toString(), sessionId, player.getId(), session.getGame(),
                session.getBetAmount(), win, Instant.now().toString()));
        leaderboard.invalidate();
        broadcastLeaderboard();
        return SessionDto.from(session);
    }

    @Transactional(readOnly = true)
    public SessionDto get(Long sessionId) {
        return sessions.findById(sessionId)
                .map(SessionDto::from)
                .orElseThrow(() -> new NotFoundException("session " + sessionId + " not found"));
    }

    private void broadcastLeaderboard() {
        try {
            ws.broadcast(mapper.writeValueAsString(
                    Map.of("type", "LEADERBOARD_UPDATED", "top", leaderboard.getTop())));
        } catch (Exception e) {
            log.warn("websocket broadcast failed: {}", e.getMessage());
        }
    }
}
