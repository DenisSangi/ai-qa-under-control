package com.arcade.player.service;

import com.arcade.player.dto.LeaderboardEntry;
import com.arcade.player.repo.GameSessionRepository;
import com.arcade.player.ws.LeaderboardWebSocketHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top 10 players by net result (win - bet) of the current leaderboard period, a third of a calendar
 * month in UTC on the service clock.
 *
 * <p>{@link #refresh} is the only writer: it recomputes the top from Postgres, stores it in Redis
 * (cache-aside for {@link #getTop}) and sends LEADERBOARD_UPDATED over the WebSocket only if the top
 * differs from the last one sent. It runs after every finished session and on a schedule, which
 * catches period boundaries. Redis being down degrades to direct DB reads instead of failing.
 */
@Service
public class LeaderboardService {

    static final String KEY = "leaderboard:top";

    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);

    private final StringRedisTemplate redis;
    private final GameSessionRepository sessions;
    private final LeaderboardWebSocketHandler ws;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration ttl;

    /** null until the first refresh after start; that first top counts as sent without sending it. */
    private List<LeaderboardEntry> lastSent;

    public LeaderboardService(StringRedisTemplate redis,
                              GameSessionRepository sessions,
                              LeaderboardWebSocketHandler ws,
                              ObjectMapper mapper,
                              Clock clock,
                              @Value("${app.leaderboard-cache-ttl-seconds}") long ttlSeconds) {
        this.redis = redis;
        this.sessions = sessions;
        this.ws = ws;
        this.mapper = mapper;
        this.clock = clock;
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
        return refresh();
    }

    @Scheduled(fixedDelayString = "${app.leaderboard.refresh-interval}")
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("scheduled leaderboard refresh failed: {}", e.getMessage());
        }
    }

    public synchronized List<LeaderboardEntry> refresh() {
        Instant[] period = currentPeriod(clock.instant());
        List<LeaderboardEntry> top = sessions.findTop(period[0], period[1], PageRequest.of(0, 10));
        try {
            redis.opsForValue().set(KEY, mapper.writeValueAsString(top), ttl);
        } catch (Exception e) {
            log.warn("redis write failed: {}", e.getMessage());
        }
        if (lastSent == null) {
            lastSent = top;
        } else if (!top.equals(lastSent)) {
            broadcast(top);
            lastSent = top;
        }
        return top;
    }

    /** [start, end) of the period that contains {@code now}: 1st-11th, 11th-21st, 21st-1st of next month. */
    static Instant[] currentPeriod(Instant now) {
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        int startDay = day.getDayOfMonth() < 11 ? 1 : day.getDayOfMonth() < 21 ? 11 : 21;
        LocalDate start = day.withDayOfMonth(startDay);
        LocalDate end = startDay == 21 ? start.withDayOfMonth(1).plusMonths(1) : start.plusDays(10);
        return new Instant[] {start.atStartOfDay(ZoneOffset.UTC).toInstant(), end.atStartOfDay(ZoneOffset.UTC).toInstant()};
    }

    private void broadcast(List<LeaderboardEntry> top) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "LEADERBOARD_UPDATED");
            message.put("top", top);
            ws.broadcast(mapper.writeValueAsString(message));
        } catch (Exception e) {
            log.warn("websocket broadcast failed: {}", e.getMessage());
        }
    }
}
