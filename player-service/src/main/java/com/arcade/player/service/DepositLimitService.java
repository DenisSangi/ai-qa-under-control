package com.arcade.player.service;

import com.arcade.player.domain.DepositLimit;
import com.arcade.player.domain.DepositLimitPeriod;
import com.arcade.player.domain.TxType;
import com.arcade.player.dto.DepositLimitDto;
import com.arcade.player.exception.BusinessException;
import com.arcade.player.exception.NotFoundException;
import com.arcade.player.repo.DepositLimitRepository;
import com.arcade.player.repo.PlayerRepository;
import com.arcade.player.repo.TransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deposit limits (responsible gambling). A limit counts successful deposits inside a rolling
 * window that ends now; withdrawals do not free the limit.
 */
@Service
public class DepositLimitService {

    private final DepositLimitRepository limits;
    private final TransactionRepository transactions;
    private final PlayerRepository players;
    private final Duration coolingOff;

    public DepositLimitService(DepositLimitRepository limits,
                               TransactionRepository transactions,
                               PlayerRepository players,
                               @Value("${app.deposit-limit.cooling-off}") Duration coolingOff) {
        this.limits = limits;
        this.transactions = transactions;
        this.players = players;
        this.coolingOff = coolingOff;
    }

    @Transactional
    public List<DepositLimitDto> list(Long playerId) {
        if (!players.existsById(playerId)) {
            throw playerNotFound(playerId);
        }
        Instant now = Instant.now();
        return activeLimits(playerId, now).stream().map(limit -> toDto(limit, now)).toList();
    }

    /** First limit and decreases apply at once; increases wait for the cooling-off period. */
    @Transactional
    public DepositLimitDto set(Long playerId, DepositLimitPeriod period, BigDecimal amount) {
        lockPlayer(playerId);
        Instant now = Instant.now();
        DepositLimit limit = limits.findByPlayerIdAndPeriod(playerId, period)
                .orElseGet(() -> limits.save(new DepositLimit(playerId, period)));
        limit.applyDuePendingChange(now);
        if (!limit.isActive() || amount.compareTo(limit.getAmount()) <= 0) {
            limit.applyNow(amount);
        } else {
            limit.schedule(amount, now.plus(coolingOff));
        }
        return toDto(limit, now);
    }

    /** Removal waits for the cooling-off period, like an increase. */
    @Transactional
    public DepositLimitDto remove(Long playerId, DepositLimitPeriod period) {
        lockPlayer(playerId);
        Instant now = Instant.now();
        DepositLimit limit = limits.findByPlayerIdAndPeriod(playerId, period)
                .orElseThrow(() -> limitNotFound(playerId, period));
        limit.applyDuePendingChange(now);
        if (!limit.isActive()) {
            throw limitNotFound(playerId, period);
        }
        limit.schedule(null, now.plus(coolingOff));
        return toDto(limit, now);
    }

    /**
     * Runs inside the deposit transaction after the player row is locked. A deposit that
     * reaches a limit exactly is allowed; the first exceeded limit in DAILY, WEEKLY, MONTHLY
     * order is reported.
     */
    public void check(Long playerId, BigDecimal amount) {
        Instant now = Instant.now();
        for (DepositLimit limit : activeLimits(playerId, now)) {
            BigDecimal used = used(playerId, limit.getPeriod(), now);
            if (used.add(amount).compareTo(limit.getAmount()) > 0) {
                BigDecimal remaining = remaining(limit.getAmount(), used);
                Map<String, String> details = new LinkedHashMap<>();
                details.put("period", limit.getPeriod().name());
                details.put("limit", money(limit.getAmount()).toPlainString());
                details.put("remaining", remaining.toPlainString());
                throw new BusinessException("DEPOSIT_LIMIT_EXCEEDED", HttpStatus.UNPROCESSABLE_ENTITY,
                        "deposit of " + money(amount) + " exceeds the " + limit.getPeriod().name().toLowerCase()
                                + " deposit limit, remaining " + remaining, details);
            }
        }
    }

    private List<DepositLimit> activeLimits(Long playerId, Instant now) {
        List<DepositLimit> active = new ArrayList<>();
        for (DepositLimit limit : limits.findByPlayerId(playerId)) {
            limit.applyDuePendingChange(now);
            if (limit.isActive()) {
                active.add(limit);
            }
        }
        active.sort(Comparator.comparing(DepositLimit::getPeriod));
        return active;
    }

    private BigDecimal used(Long playerId, DepositLimitPeriod period, Instant now) {
        BigDecimal sum = transactions.sumSince(playerId, TxType.DEPOSIT, now.minus(period.getWindow()));
        return money(sum == null ? BigDecimal.ZERO : sum);
    }

    private DepositLimitDto toDto(DepositLimit limit, Instant now) {
        BigDecimal used = used(limit.getPlayerId(), limit.getPeriod(), now);
        DepositLimitDto.Pending pending = limit.hasPendingChange()
                ? new DepositLimitDto.Pending(
                        limit.getPendingAmount() == null ? null : money(limit.getPendingAmount()),
                        limit.getPendingEffectiveAt())
                : null;
        return new DepositLimitDto(limit.getPeriod(), money(limit.getAmount()), used,
                remaining(limit.getAmount(), used), pending);
    }

    private void lockPlayer(Long playerId) {
        players.findByIdForUpdate(playerId).orElseThrow(() -> playerNotFound(playerId));
    }

    private static BigDecimal remaining(BigDecimal limit, BigDecimal used) {
        return money(limit.subtract(used).max(BigDecimal.ZERO));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static NotFoundException playerNotFound(Long playerId) {
        return new NotFoundException("player " + playerId + " not found");
    }

    private static NotFoundException limitNotFound(Long playerId, DepositLimitPeriod period) {
        return new NotFoundException(period.name().toLowerCase() + " deposit limit is not set for player " + playerId);
    }
}
