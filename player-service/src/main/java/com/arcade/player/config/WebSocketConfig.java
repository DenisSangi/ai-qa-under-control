package com.arcade.player.config;

import com.arcade.player.ws.LeaderboardWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LeaderboardWebSocketHandler leaderboardHandler;

    public WebSocketConfig(LeaderboardWebSocketHandler leaderboardHandler) {
        this.leaderboardHandler = leaderboardHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(leaderboardHandler, "/ws/leaderboard").setAllowedOrigins("*");
    }
}
