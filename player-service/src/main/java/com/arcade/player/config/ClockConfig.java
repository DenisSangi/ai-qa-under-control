package com.arcade.player.config;

import com.arcade.player.clock.ShiftableClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    @Bean
    public ShiftableClock clock() {
        return new ShiftableClock(Clock.systemUTC());
    }
}
