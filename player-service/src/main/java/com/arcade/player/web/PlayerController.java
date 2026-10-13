package com.arcade.player.web;

import com.arcade.player.domain.Player;
import com.arcade.player.domain.TxType;
import com.arcade.player.dto.CreatePlayerRequest;
import com.arcade.player.dto.DepositRequest;
import com.arcade.player.dto.PlayerStatsDto;
import com.arcade.player.dto.WithdrawRequest;
import com.arcade.player.service.IdempotencyService;
import com.arcade.player.service.PlayerService;
import com.arcade.player.service.StatsClient;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/players")
public class PlayerController {

    private final PlayerService playerService;
    private final StatsClient statsClient;
    private final IdempotencyService idempotency;

    public PlayerController(PlayerService playerService, StatsClient statsClient, IdempotencyService idempotency) {
        this.playerService = playerService;
        this.statsClient = statsClient;
        this.idempotency = idempotency;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Player create(@Valid @RequestBody CreatePlayerRequest request) {
        return playerService.create(request);
    }

    @GetMapping("/{id}")
    public Player get(@PathVariable Long id) {
        return playerService.get(id);
    }

    @GetMapping
    public Page<Player> list(@RequestParam(required = false) String country,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        return playerService.list(country, PageRequest.of(page, size));
    }

    @PostMapping("/{id}/deposit")
    public ResponseEntity<?> deposit(@PathVariable Long id,
                                     @RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
                                     @Valid @RequestBody DepositRequest request) {
        return idempotent(id, key, TxType.DEPOSIT, request.amount(),
                () -> playerService.deposit(id, request.amount(), key));
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<?> withdraw(@PathVariable Long id,
                                      @RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
                                      @Valid @RequestBody WithdrawRequest request) {
        return idempotent(id, key, TxType.WITHDRAWAL, request.amount(),
                () -> playerService.withdraw(id, request.amount(), key));
    }

    /** Aggregates come from analytics-service over gRPC — REST-to-gRPC integration point. */
    @GetMapping("/{id}/stats")
    public PlayerStatsDto stats(@PathVariable Long id) {
        return statsClient.getStats(id);
    }

    /**
     * Check order: body and key format (400), player exists (404), key claim — replay,
     * in progress (409) or reused (422), then the operation itself.
     */
    private ResponseEntity<?> idempotent(Long playerId, String key, TxType operation, BigDecimal amount,
                                         Supplier<Player> action) {
        idempotency.validate(key);
        playerService.get(playerId);
        Optional<String> stored = idempotency.claim(playerId, key, operation, amount);
        if (stored.isPresent()) {
            return ResponseEntity.ok()
                    .header(IdempotencyService.REPLAYED_HEADER, "true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(stored.get());
        }
        try {
            return ResponseEntity.ok(action.get());
        } catch (RuntimeException e) {
            idempotency.release(playerId, key);
            throw e;
        }
    }
}
