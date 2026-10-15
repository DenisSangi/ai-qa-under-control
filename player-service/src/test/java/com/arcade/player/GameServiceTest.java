package com.arcade.player;

import com.arcade.player.domain.GameSession;
import com.arcade.player.domain.Player;
import com.arcade.player.domain.SessionStatus;
import com.arcade.player.dto.SessionDto;
import com.arcade.player.dto.StartSessionRequest;
import com.arcade.player.events.SessionFinishedEvent;
import com.arcade.player.exception.BusinessException;
import com.arcade.player.repo.GameSessionRepository;
import com.arcade.player.repo.PlayerRepository;
import com.arcade.player.repo.TransactionRepository;
import com.arcade.player.service.EventPublisher;
import com.arcade.player.service.GameService;
import com.arcade.player.service.LeaderboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);

    private final GameService service = new GameService(
            players, sessions, transactions, events, leaderboard, clock, Duration.ofSeconds(60));

    private Player player;

    @BeforeEach
    void setUp() {
        player = new Player("tester", "tester@example.com", "DE", clock.instant());
        ReflectionTestUtils.setField(player, "id", 1L);
        when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void startSession_rejectsWhenBalanceIsLowerThanBet() {
        player.setBalance(new BigDecimal("50.00"));
        when(players.findByIdForUpdate(1L)).thenReturn(Optional.of(player));

        assertThatThrownBy(() -> service.start(new StartSessionRequest(1L, "slots", new BigDecimal("100.00"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INSUFFICIENT_FUNDS"));

        verify(sessions, never()).save(any());
    }

    @Test
    void startSession_deductsBetFromBalance() {
        player.setBalance(new BigDecimal("100.00"));
        when(players.findByIdForUpdate(1L)).thenReturn(Optional.of(player));

        SessionDto dto = service.start(new StartSessionRequest(1L, "slots", new BigDecimal("40.00")));

        assertThat(player.getBalance()).isEqualByComparingTo("60.00");
        assertThat(dto.status()).isEqualTo("ACTIVE");
        assertThat(dto.betAmount()).isEqualByComparingTo("40.00");
    }

    @Test
    void finishSession_rejectsWhenSessionIsNotActive() {
        GameSession session = new GameSession(player, "slots", new BigDecimal("10.00"), clock.instant());
        session.setStatus(SessionStatus.FINISHED);
        when(sessions.findByIdForUpdate(7L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.finish(7L, new BigDecimal("5.00")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SESSION_NOT_ACTIVE"));
    }

    @Test
    void finishSession_addsWinPublishesEventAndRefreshesLeaderboard() {
        player.setBalance(new BigDecimal("60.00"));
        GameSession session = new GameSession(player, "roulette", new BigDecimal("40.00"), clock.instant());
        when(sessions.findByIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(players.findByIdForUpdate(1L)).thenReturn(Optional.of(player));

        SessionDto dto = service.finish(7L, new BigDecimal("90.00"));

        assertThat(player.getBalance()).isEqualByComparingTo("150.00");
        assertThat(dto.status()).isEqualTo("FINISHED");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishSessionEvent(eq(1L), captor.capture());
        assertThat(captor.getValue()).isInstanceOfSatisfying(SessionFinishedEvent.class, event -> {
            assertThat(event.type()).isEqualTo("SESSION_FINISHED");
            assertThat(event.win()).isEqualByComparingTo("90.00");
        });

        verify(leaderboard).refresh();
    }
}
