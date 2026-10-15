package com.arcade.analytics.kafka;

import com.arcade.analytics.service.StatsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class GameEventsListener {

    private static final Logger log = LoggerFactory.getLogger(GameEventsListener.class);

    private final ObjectMapper mapper;
    private final StatsService stats;

    public GameEventsListener(ObjectMapper mapper, StatsService stats) {
        this.mapper = mapper;
        this.stats = stats;
    }

    @KafkaListener(topics = "game-events", groupId = "analytics")
    public void onMessage(String payload) {
        GameFinishedEvent event;
        try {
            event = mapper.readValue(payload, GameFinishedEvent.class);
        } catch (JsonProcessingException e) {
            // "poison pill" handling: skip instead of blocking the partition forever.
            // Production systems route these to a dead-letter topic.
            log.error("skipping malformed game event: {}", payload);
            return;
        }
        // game-events also carries SESSION_STARTED and SESSION_EXPIRED; statistics count finished sessions only.
        if (!"SESSION_FINISHED".equals(event.type())) {
            return;
        }
        log.info("consumed game event {} for player {}", event.eventId(), event.playerId());
        stats.apply(event);
    }
}
