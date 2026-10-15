package com.arcade.player.service;

import com.arcade.player.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Inclusive range of a request amount. Out of range is a 422 with {@code min} and {@code max} in details. */
public record AmountRange(String code, BigDecimal min, BigDecimal max) {

    public static final AmountRange DEPOSIT =
            new AmountRange("DEPOSIT_AMOUNT_OUT_OF_RANGE", new BigDecimal("10.00"), new BigDecimal("50000.00"));
    public static final AmountRange WITHDRAWAL =
            new AmountRange("WITHDRAWAL_AMOUNT_OUT_OF_RANGE", new BigDecimal("10.00"), new BigDecimal("50000.00"));
    public static final AmountRange DEPOSIT_LIMIT =
            new AmountRange("DEPOSIT_LIMIT_OUT_OF_RANGE", new BigDecimal("10.00"), new BigDecimal("1000000.00"));
    public static final AmountRange BET =
            new AmountRange("BET_AMOUNT_OUT_OF_RANGE", new BigDecimal("1.00"), new BigDecimal("500.00"));

    public void check(BigDecimal amount) {
        if (amount.compareTo(min) < 0 || amount.compareTo(max) > 0) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("min", min.toPlainString());
            details.put("max", max.toPlainString());
            throw new BusinessException(code, HttpStatus.UNPROCESSABLE_ENTITY,
                    "amount " + amount + " is outside the range " + min + " - " + max, details);
        }
    }
}
