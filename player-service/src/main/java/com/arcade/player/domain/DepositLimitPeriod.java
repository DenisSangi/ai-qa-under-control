package com.arcade.player.domain;

import com.arcade.player.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Map;

/** Rolling windows. Declaration order is the order in which limits are checked. */
public enum DepositLimitPeriod {
    DAILY(Duration.ofDays(1)),
    WEEKLY(Duration.ofDays(7)),
    MONTHLY(Duration.ofDays(30));

    private final Duration window;

    DepositLimitPeriod(Duration window) {
        this.window = window;
    }

    public Duration getWindow() {
        return window;
    }

    /** Path segment: daily | weekly | monthly. */
    public static DepositLimitPeriod fromPath(String value) {
        for (DepositLimitPeriod period : values()) {
            if (period.name().toLowerCase().equals(value)) {
                return period;
            }
        }
        throw new BusinessException("VALIDATION_ERROR", HttpStatus.BAD_REQUEST, "request validation failed",
                Map.of("period", "must be one of daily, weekly, monthly"));
    }
}
