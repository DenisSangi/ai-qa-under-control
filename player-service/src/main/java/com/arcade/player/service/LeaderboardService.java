package com.arcade.player.service;

import com.arcade.player.dto.LeaderboardEntry;
import com.arcade.player.repo.GameSessionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Cache-aside pattern on Redis: read the cache, on miss go to Postgres and
 * store the result with a TTL. finish-session invalidates the key.
 * Redis being down degrades to direct DB reads instead of failing.
 */
@Service
public class LeaderboardService {

    static final String KEY = "leaderboard:top";

    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);

    private final StringRedisTemplate redis;
    private final GameSessionRepository sessions;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public LeaderboardService(StringRedisTemplate redis,
                              GameSessionRepository sessions,
                              ObjectMapper mapper,
                              @Value("${app.leaderboard-cache-ttl-seconds}") long ttlSeconds) {
        this.redis = redis;
        this.sessions = sessions;
        this.mapper = mapper;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public List<LeaderboardEntry> getTop() {
        try {
            String cached = redis.opsForValue().get(KEY);
            if (cached != null) {
                return mapper.readValue(cached, new TypeReference<List<LeaderboardEntry>>() {
                });
            }
        } catch (Exception e) {
            log.warn("redis read failed, falling back to DB: {}", e.getMessage());
        }
        List<LeaderboardEntry> top = sessions.findTop(PageRequest.of(0, 10));
        try {
            redis.opsForValue().set(KEY, mapper.writeValueAsString(top), ttl);
        } catch (Exception e) {
            log.warn("redis write failed: {}", e.getMessage());
        }
        return top;
    }

    public void invalidate() {
        try {
            redis.delete(KEY);
        } catch (Exception e) {
            log.warn("redis invalidation failed: {}", e.getMessage());
        }
    }
}
