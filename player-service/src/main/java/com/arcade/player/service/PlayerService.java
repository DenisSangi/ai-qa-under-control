package com.arcade.player.service;

import com.arcade.player.domain.PaymentTransaction;
import com.arcade.player.domain.Player;
import com.arcade.player.domain.TxType;
import com.arcade.player.dto.CreatePlayerRequest;
import com.arcade.player.events.PlayerEvent;
import com.arcade.player.exception.BusinessException;
import com.arcade.player.exception.NotFoundException;
import com.arcade.player.repo.PlayerRepository;
import com.arcade.player.repo.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

@Service
public class PlayerService {

    private final PlayerRepository players;
    private final TransactionRepository transactions;
    private final PaymentClient payment;
    private final EventPublisher events;
    private final DepositLimitService depositLimits;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public PlayerService(PlayerRepository players,
                         TransactionRepository transactions,
                         PaymentClient payment,
                         EventPublisher events,
                         DepositLimitService depositLimits,
                         IdempotencyService idempotency,
                         Clock clock) {
        this.players = players;
        this.transactions = transactions;
        this.payment = payment;
        this.events = events;
        this.depositLimits = depositLimits;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Transactional
    public Player create(CreatePlayerRequest request) {
        if (players.existsByUsername(request.username())) {
            throw new BusinessException("DUPLICATE_USERNAME", HttpStatus.CONFLICT,
                    "username '" + request.username() + "' is already taken");
        }
        if (players.existsByEmail(request.email())) {
            throw new BusinessException("DUPLICATE_EMAIL", HttpStatus.CONFLICT,
                    "email '" + request.email() + "' is already registered");
        }
        Player player = players.save(new Player(request.username(), request.email(), request.country().toUpperCase(),
                clock.instant()));
        events.publishPlayerEvent(new PlayerEvent(
                UUID.randomUUID().toString(), "REGISTERED", player.getId(), null, clock.instant().toString()));
        return player;
    }

    public Player get(Long id) {
        return players.findById(id)
                .orElseThrow(() -> new NotFoundException("player " + id + " not found"));
    }

    public Page<Player> list(String country, Pageable pageable) {
        return country == null ? players.findAll(pageable) : players.findByCountry(country, pageable);
    }

    /** The idempotency key must already be claimed by IdempotencyService. */
    @Transactional
    public Player deposit(Long id, BigDecimal amount, String idempotencyKey) {
        AmountRange.DEPOSIT.check(amount);
        Player player = lock(id);
        depositLimits.check(id, amount);
        // NOTE: calling an external provider inside a DB transaction is a known
        // anti-pattern (see docs/theory/12-microservices-e2e.md) — kept simple on purpose.
        payment.charge(id, amount, IdempotencyService.reference(id, idempotencyKey));
        player.setBalance(player.getBalance().add(amount));
        transactions.save(new PaymentTransaction(id, TxType.DEPOSIT, amount, clock.instant()));
        idempotency.complete(id, idempotencyKey, player);
        events.publishPlayerEvent(new PlayerEvent(
                UUID.randomUUID().toString(), "DEPOSIT", id, amount, clock.instant().toString()));
        return player;
    }

    /** The idempotency key must already be claimed by IdempotencyService. */
    @Transactional
    public Player withdraw(Long id, BigDecimal amount, String idempotencyKey) {
        AmountRange.WITHDRAWAL.check(amount);
        Player player = lock(id);
        if (player.getBalance().compareTo(amount) < 0) {
            throw new BusinessException("INSUFFICIENT_FUNDS", HttpStatus.UNPROCESSABLE_ENTITY,
                    "balance " + player.getBalance() + " is lower than withdrawal " + amount);
        }
        payment.payout(id, amount, IdempotencyService.reference(id, idempotencyKey));
        player.setBalance(player.getBalance().subtract(amount));
        transactions.save(new PaymentTransaction(id, TxType.WITHDRAWAL, amount, clock.instant()));
        idempotency.complete(id, idempotencyKey, player);
        events.publishPlayerEvent(new PlayerEvent(
                UUID.randomUUID().toString(), "WITHDRAWAL", id, amount, clock.instant().toString()));
        return player;
    }

    private Player lock(Long id) {
        return players.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("player " + id + " not found"));
    }
}
