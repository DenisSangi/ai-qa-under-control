package com.arcade.player.web;

import com.arcade.player.domain.Player;
import com.arcade.player.dto.CreatePlayerRequest;
import com.arcade.player.dto.DepositRequest;
import com.arcade.player.dto.PlayerStatsDto;
import com.arcade.player.service.PlayerService;
import com.arcade.player.service.StatsClient;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/players")
public class PlayerController {

    private final PlayerService playerService;
    private final StatsClient statsClient;

    public PlayerController(PlayerService playerService, StatsClient statsClient) {
        this.playerService = playerService;
        this.statsClient = statsClient;
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
    public Player deposit(@PathVariable Long id, @Valid @RequestBody DepositRequest request) {
        return playerService.deposit(id, request.amount());
    }

    /** Aggregates come from analytics-service over gRPC — REST-to-gRPC integration point. */
    @GetMapping("/{id}/stats")
    public PlayerStatsDto stats(@PathVariable Long id) {
        return statsClient.getStats(id);
    }
}
