package com.arcade.player.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record StartSessionRequest(
        @NotNull Long playerId,
        @NotBlank String game,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal bet
) {
}
