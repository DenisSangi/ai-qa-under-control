package com.arcade.player.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Only the format is validated here; the allowed range is a business rule (AmountRange, 422). */
public record DepositLimitRequest(
        @NotNull
        @Digits(integer = Integer.MAX_VALUE, fraction = 2, message = "must have at most 2 decimal places")
        BigDecimal amount
) {
}
