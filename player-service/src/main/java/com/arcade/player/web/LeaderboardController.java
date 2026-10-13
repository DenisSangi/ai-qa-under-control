package com.arcade.player.web;

import com.arcade.player.dto.LeaderboardEntry;
import com.arcade.player.service.LeaderboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    public LeaderboardController(LeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    @GetMapping("/api/leaderboard")
    public List<LeaderboardEntry> top() {
        return leaderboardService.getTop();
    }
}
