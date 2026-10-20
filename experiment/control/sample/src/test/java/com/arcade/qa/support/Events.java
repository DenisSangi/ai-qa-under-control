package com.arcade.qa.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Collects every record of {@code player-events} and {@code game-events} published after the suite started.
 * The consumer has no group: it is assigned all partitions and starts at their end.
 */
public final class Events {

    public record Event(String topic, int partition, long offset, String key, JsonNode value, String raw) {
        public String type() {
            return value.path("type").asText();
        }
    }

    private static final List<Event> EVENTS = new CopyOnWriteArrayList<>();
    private static volatile boolean started;

    private Events() {
    }

    public static synchronized void start() {
        if (started) {
            return;
        }
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Config.KAFKA);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        CountDownLatch ready = new CountDownLatch(1);
        Thread t = new Thread(() -> consume(p, ready), "qa-kafka-collector");
        t.setDaemon(true);
        t.start();
        try {
            if (!ready.await(30, TimeUnit.SECONDS)) {
                fail("Kafka at " + Config.KAFKA + " is not reachable: event checks cannot run");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        started = true;
    }

    private static void consume(Properties p, CountDownLatch ready) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            List<TopicPartition> parts = new ArrayList<>();
            for (String topic : List.of(Config.PLAYER_EVENTS, Config.GAME_EVENTS)) {
                List<PartitionInfo> infos = consumer.partitionsFor(topic, Duration.ofSeconds(20));
                for (PartitionInfo i : infos) {
                    parts.add(new TopicPartition(topic, i.partition()));
                }
            }
            consumer.assign(parts);
            consumer.seekToEnd(parts);
            parts.forEach(consumer::position); // resolve the end offsets now, before any test acts
            ready.countDown();
            while (true) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(100))) {
                    JsonNode value;
                    try {
                        value = Json.MAPPER.readTree(r.value());
                    } catch (Exception e) {
                        value = Json.MAPPER.createObjectNode().put("_unparseable", r.value());
                    }
                    EVENTS.add(new Event(r.topic(), r.partition(), r.offset(), r.key(), value, r.value()));
                }
            }
        } catch (Exception e) {
            System.err.println("Kafka collector stopped: " + e);
        }
    }

    /** Events of the player in the topic (by {@code playerId} in the value), in arrival order. */
    public static List<Event> of(String topic, long playerId) {
        return EVENTS.stream()
                .filter(e -> e.topic().equals(topic) && e.value().path("playerId").asLong(-1) == playerId)
                .toList();
    }

    public static List<Event> ofType(String topic, long playerId, String type) {
        return of(topic, playerId).stream().filter(e -> type.equals(e.type())).toList();
    }

    /** Waits until at least {@code count} events of the player in the topic match; returns all that match. */
    public static List<Event> await(String topic, long playerId, Predicate<Event> filter, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            List<Event> found = of(topic, playerId).stream().filter(filter).toList();
            if (found.size() >= count || System.nanoTime() > deadline) {
                return found;
            }
            sleep(50);
        }
    }

    public static Event awaitOne(String topic, long playerId, String type, Duration timeout) {
        List<Event> found = await(topic, playerId, e -> type.equals(e.type()), 1, timeout);
        if (found.isEmpty()) {
            fail("no " + type + " event in " + topic + " for player " + playerId + " within " + timeout
                    + "; events of the player: " + of(topic, playerId).stream().map(Event::raw).toList());
        }
        return found.get(0);
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
