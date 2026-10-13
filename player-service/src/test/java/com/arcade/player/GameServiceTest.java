package com.arcade.player;

import com.arcade.player.domain.GameSession;
import com.arcade.player.domain.Player;
import com.arcade.player.domain.SessionStatus;
import com.arcade.player.dto.SessionDto;
import com.arcade.player.dto.StartSessionRequest;
import com.arcade.player.events.GameFinishedEvent;
import com.arcade.player.exception.BusinessException;
import com.arcade.player.repo.GameSessionRepository;
import com.arcade.player.repo.PlayerRepository;
import com.arcade.player.repo.TransactionRepository;
import com.arcade.player.service.EventPublisher;
import com.arcade.player.service.GameService;
import com.arcade.player.service.LeaderboardService;
import com.arcade.player.ws.LeaderboardWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit level of the test pyramid: pure business rules, all collaborators mocked,
 * runs in milliseconds without any infrastructure.
 */
class GameServiceTest {

    private final PlayerRepository players = mock(PlayerRepository.class);
    private final GameSessionRepository sessions = mock(GameSessionRepository.class);
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final LeaderboardService leaderboard = mock(LeaderboardService.class);
    private final LeaderboardWebSocketHandler ws = mock(LeaderboardWebSocketHandler.class);

    private final GameService service = new GameService(
            players, sessions, transactions, events, leaderboard, ws, new ObjectMapper());

    private Player player;

    @BeforeEach
    void setUp() {
        player = new Player("tester", "tester@example.com", "DE");
        ReflectionTestUtils.setField(player, "id", 1L);
        when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void startSession_rejectsWhenBalanceIsLowerThanBet() {
        player.setBalance(new BigDecimal("50.00"));
        when(players.findById(1L)).thenReturn(Optional.of(player));

        assertThatThrownBy(() -> service.start(new StartSessionRequest(1L, "slots", new BigDecimal("100.00"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INSUFFICIENT_FUNDS"));

        verify(sessions, never()).save(any());
    }

    @Test
    void startSession_deductsBetFromBalance() {
        player.setBalance(new BigDecimal("100.00"));
        when(players.findById(1L)).thenReturn(Optional.of(player));

        SessionDto dto = service.start(new StartSessionRequest(1L, "slots", new BigDecimal("40.00")));

        assertThat(player.getBalance()).isEqualByComparingTo("60.00");
        assertThat(dto.status()).isEqualTo("ACTIVE");
        assertThat(dto.betAmount()).isEqualByComparingTo("40.00");
    }

    @Test
    void finishSession_rejectsWhenSessionIsNotActive() {
        GameSession session = new GameSession(player, "slots", new BigDecimal("10.00"));
        session.setStatus(SessionStatus.FINISHED);
        when(sessions.findById(7L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.finish(7L, new BigDecimal("5.00")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SESSION_NOT_ACTIVE"));
    }

    @Test
    void finishSession_addsWinPublishesEventAndInvalidatesCache() {
        player.setBalance(new BigDecimal("60.00"));
        GameSession session = new GameSession(player, "roulette", new BigDecimal("40.00"));
        when(sessions.findById(7L)).thenReturn(Optional.of(session));

        SessionDto dto = service.finish(7L, new BigDecimal("90.00"));

        assertThat(player.getBalance()).isEqualByComparingTo("150.00");
        assertThat(dto.status()).isEqualTo("FINISHED");

        ArgumentCaptor<GameFinishedEvent> captor = ArgumentCaptor.forClass(GameFinishedEvent.class);
        verify(events).publishGameFinished(captor.capture());
        assertThat(captor.getValue().playerId()).isEqualTo(1L);
        assertThat(captor.getValue().win()).isEqualByComparingTo("90.00");

        verify(leaderboard).invalidate();
    }
}
