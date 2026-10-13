package com.arcade.player.dto;

import com.arcade.player.domain.DepositLimitPeriod;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code used} is the sum of successful deposits inside the rolling window,
 * {@code remaining} is {@code amount - used}, never below zero.
 * {@code pending} is null without a pending change; {@code pending.amount} is null for a pending removal.
 */
public record DepositLimitDto(
        DepositLimitPeriod period,
        BigDecimal amount,
        BigDecimal used,
        BigDecimal remaining,
        Pending pending
) {
    public record Pending(BigDecimal amount, Instant effectiveAt) {
    }
}
