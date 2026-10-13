package com.arcade.player.web;

import com.arcade.player.dto.FinishSessionRequest;
import com.arcade.player.dto.SessionDto;
import com.arcade.player.dto.StartSessionRequest;
import com.arcade.player.service.GameService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final GameService gameService;

    public SessionController(GameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionDto start(@Valid @RequestBody StartSessionRequest request) {
        return gameService.start(request);
    }

    @PostMapping("/{id}/finish")
    public SessionDto finish(@PathVariable Long id, @Valid @RequestBody FinishSessionRequest request) {
        return gameService.finish(id, request.win());
    }

    @GetMapping("/{id}")
    public SessionDto get(@PathVariable Long id) {
        return gameService.get(id);
    }
}
