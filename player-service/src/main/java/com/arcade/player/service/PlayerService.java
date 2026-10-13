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
import java.time.Instant;
import java.util.UUID;

@Service
public class PlayerService {

    private final PlayerRepository players;
    private final TransactionRepository transactions;
    private final PaymentClient payment;
    private final EventPublisher events;

    public PlayerService(PlayerRepository players,
                         TransactionRepository transactions,
                         PaymentClient payment,
                         EventPublisher events) {
        this.players = players;
        this.transactions = transactions;
        this.payment = payment;
        this.events = events;
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
        Player player = players.save(new Player(request.username(), request.email(), request.country().toUpperCase()));
        events.publishPlayerEvent(new PlayerEvent(
                UUID.randomUUID().toString(), "REGISTERED", player.getId(), null, Instant.now().toString()));
        return player;
    }

    public Player get(Long id) {
        return players.findById(id)
                .orElseThrow(() -> new NotFoundException("player " + id + " not found"));
    }

    public Page<Player> list(String country, Pageable pageable) {
        return country == null ? players.findAll(pageable) : players.findByCountry(country, pageable);
    }

    @Transactional
    public Player deposit(Long id, BigDecimal amount) {
        Player player = get(id);
        // NOTE: calling an external provider inside a DB transaction is a known
        // anti-pattern (see docs/theory/12-microservices-e2e.md) — kept simple on purpose.
        payment.charge(id, amount);
        player.setBalance(player.getBalance().add(amount));
        transactions.save(new PaymentTransaction(id, TxType.DEPOSIT, amount));
        events.publishPlayerEvent(new PlayerEvent(
                UUID.randomUUID().toString(), "DEPOSIT", id, amount, Instant.now().toString()));
        return player;
    }
}
