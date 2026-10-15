package com.arcade.player.service;

import com.arcade.player.events.PlayerEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Kafka producer. Messages are keyed by playerId so that all events of one
 * player land in the same partition and keep their order. An event is sent
 * only after the operation's transaction commits (see {@link AfterCommit}).
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

    /** One of the session events in {@code com.arcade.player.events}. */
    public void publishSessionEvent(Long playerId, Object event) {
        send(GAME_EVENTS, String.valueOf(playerId), event);
    }

    public void publishPlayerEvent(PlayerEvent event) {
        send(PLAYER_EVENTS, String.valueOf(event.playerId()), event);
    }

    private void send(String topic, String key, Object payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize event", e);
        }
        AfterCommit.run("event to " + topic, () -> kafka.send(topic, key, json));
    }
}
