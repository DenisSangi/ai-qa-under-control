package com.arcade.player;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration level of the pyramid: the REAL service against REAL Postgres,
 * Kafka and Redis started by Testcontainers; only the external payment
 * provider is a WireMock stub. Requires Docker: mvn verify -Pit
 */
@Tag("it")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlayerFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static WireMockServer wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        wiremock.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("payment.base-url", wiremock::baseUrl);
    }

    @Autowired
    TestRestTemplate rest;

    @BeforeAll
    static void stubPayment() {
        wiremock.stubFor(post(urlEqualTo("/payments")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"APPROVED\",\"transactionId\":\"tx-test-1\",\"reason\":null}")));
    }

    @AfterAll
    static void stopWiremock() {
        wiremock.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullPlayerFlow_depositPlayFinish_producesKafkaEvent() {
        String username = "it_" + UUID.randomUUID().toString().substring(0, 8);

        // 1. register
        ResponseEntity<Map> created = rest.postForEntity("/api/players",
                Map.of("username", username, "email", username + "@example.com", "country", "DE"), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Number playerId = (Number) created.getBody().get("id");

        // 2. deposit through the stubbed payment provider
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<Map> deposited = rest.postForEntity("/api/players/" + playerId + "/deposit",
                new HttpEntity<>(Map.of("amount", 100), headers), Map.class);
        assertThat(deposited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Double.parseDouble(deposited.getBody().get("balance").toString())).isEqualTo(100.0);

        // 3. start a session — bet is deducted
        ResponseEntity<Map> started = rest.postForEntity("/api/sessions",
                Map.of("playerId", playerId, "game", "slots", "bet", 30), Map.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Number sessionId = (Number) started.getBody().get("id");

        // 4. finish with a win
        ResponseEntity<Map> finished = rest.postForEntity("/api/sessions/" + sessionId + "/finish",
                Map.of("win", 50), Map.class);
        assertThat(finished.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(finished.getBody().get("status")).isEqualTo("FINISHED");

        ResponseEntity<Map> player = rest.getForEntity("/api/players/" + playerId, Map.class);
        assertThat(Double.parseDouble(player.getBody().get("balance").toString())).isEqualTo(120.0);

        // 5. the game-events topic must contain our event
        List<String> seen = new CopyOnWriteArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps())) {
            consumer.subscribe(List.of("game-events"));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    seen.add(r.value());
                }
                assertThat(seen).anyMatch(v -> v.contains("\"sessionId\":" + sessionId));
            });
        }
    }

    private static Properties consumerProps() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return props;
    }
}
