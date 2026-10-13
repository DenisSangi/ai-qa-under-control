package com.arcade.player.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic gameEvents() {
        return TopicBuilder.name("game-events").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic playerEvents() {
        return TopicBuilder.name("player-events").partitions(3).replicas(1).build();
    }
}
