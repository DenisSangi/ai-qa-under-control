package com.arcade.player.service;

import com.arcade.player.events.GameFinishedEvent;
import com.arcade.player.events.PlayerEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Kafka producer. Messages are keyed by playerId so that all events of one
 * player land in the same partition and keep their order.
 */
@Service
public class EventPublisher {

    public static final String GAME_EVENTS = "game-events";
    public static final String PLAYER_EVENTS = "player-events";

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    public void publishGameFinished(GameFinishedEvent event) {
        send(GAME_EVENTS, String.valueOf(event.playerId()), event);
    }

    public void publishPlayerEvent(PlayerEvent event) {
        send(PLAYER_EVENTS, String.valueOf(event.playerId()), event);
    }

    private void send(String topic, String key, Object payload) {
        try {
            kafka.send(topic, key, mapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize event", e);
        }
    }
}
