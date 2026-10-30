package com.arcade.apitests.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Records what is published to Kafka from the moment it is opened. Uses manual partition assignment
 * without a consumer group, so it never commits offsets or disturbs the analytics consumer group.
 */
public final class EventCapture implements AutoCloseable {

    public static final String GAME_EVENTS = "game-events";
    public static final String PLAYER_EVENTS = "player-events";

    public record Event(String topic, int partition, long offset, String key, JsonNode value) {

        public String type() {
            return Json.text(value, "type");
        }

        public long playerId() {
            return value.path("playerId").asLong(-1);
        }
    }

    private final KafkaConsumer<String, String> consumer;
    private final List<Event> seen = new ArrayList<>();

    private EventCapture(KafkaConsumer<String, String> consumer) {
        this.consumer = consumer;
    }

    public static EventCapture open(String... topics) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Env.KAFKA);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "api-tests-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        List<TopicPartition> partitions = new ArrayList<>();
        for (String topic : topics) {
            for (PartitionInfo info : consumer.partitionsFor(topic, Duration.ofSeconds(10))) {
                partitions.add(new TopicPartition(topic, info.partition()));
            }
        }
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        // seekToEnd is lazy: resolve the positions now, before the test acts.
        partitions.forEach(p -> consumer.position(p, Duration.ofSeconds(10)));
        return new EventCapture(consumer);
    }

    /** Polls until {@code count} events match or the timeout passes; returns the matching events. */
    public List<Event> await(Predicate<Event> filter, int count, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        List<Event> matched = matching(filter);
        while (matched.size() < count && Instant.now().isBefore(deadline)) {
            pollOnce();
            matched = matching(filter);
        }
        return matched;
    }

    /** Polls for the whole {@code window} and returns everything matching; for "nothing was sent" checks. */
    public List<Event> during(Predicate<Event> filter, Duration window) {
        Instant deadline = Instant.now().plus(window);
        while (Instant.now().isBefore(deadline)) {
            pollOnce();
        }
        return matching(filter);
    }

    public static Predicate<Event> ofPlayer(long playerId) {
        return e -> String.valueOf(playerId).equals(e.key()) || e.playerId() == playerId;
    }

    private void pollOnce() {
        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
            JsonNode value;
            try {
                value = Json.parse(r.value());
            } catch (RuntimeException e) {
                continue; // not ours: someone else's non-JSON message
            }
            seen.add(new Event(r.topic(), r.partition(), r.offset(), r.key(), value));
        }
    }

    private List<Event> matching(Predicate<Event> filter) {
        return seen.stream().filter(filter).toList();
    }

    @Override
    public void close() {
        consumer.close(Duration.ofSeconds(2));
    }
}
