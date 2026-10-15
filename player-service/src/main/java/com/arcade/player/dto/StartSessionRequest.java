package com.arcade.player.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/** Only the format is validated here; the bet range is a business rule (AmountRange, 422). */
public record StartSessionRequest(
        @NotNull Long playerId,
        @NotNull
        @Pattern(regexp = "slots|roulette|blackjack|poker|crash",
                message = "must be one of slots, roulette, blackjack, poker, crash")
        String game,
        @NotNull
        @Digits(integer = Integer.MAX_VALUE, fraction = 2, message = "must have at most 2 decimal places")
        BigDecimal bet
) {
}
