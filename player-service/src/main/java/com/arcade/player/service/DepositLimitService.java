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
import java.time.Clock;
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
    private final Clock clock;

    public DepositLimitService(DepositLimitRepository limits,
                               TransactionRepository transactions,
                               PlayerRepository players,
                               @Value("${app.deposit-limit.cooling-off}") Duration coolingOff,
                               Clock clock) {
        this.limits = limits;
        this.transactions = transactions;
        this.players = players;
        this.coolingOff = coolingOff;
        this.clock = clock;
    }

    @Transactional
    public List<DepositLimitDto> list(Long playerId) {
        if (!players.existsById(playerId)) {
            throw playerNotFound(playerId);
        }
        Instant now = clock.instant();
        return activeLimits(playerId, now).stream().map(limit -> toDto(limit, now)).toList();
    }

    /**
     * First limit and decreases apply at once; increases wait for the cooling-off period. Setting the
     * current amount cancels a pending change; repeating the pending increase changes nothing.
     */
    @Transactional
    public DepositLimitDto set(Long playerId, DepositLimitPeriod period, BigDecimal amount) {
        lockPlayer(playerId);
        AmountRange.DEPOSIT_LIMIT.check(amount);
        Instant now = clock.instant();
        checkOrder(playerId, period, amount, now);
        DepositLimit limit = limits.findByPlayerIdAndPeriod(playerId, period)
                .orElseGet(() -> limits.save(new DepositLimit(playerId, period)));
        limit.applyDuePendingChange(now);
        if (!limit.isActive() || amount.compareTo(limit.getAmount()) <= 0) {
            limit.applyNow(amount);
        } else if (!limit.isPending(amount)) {
            limit.schedule(amount, now.plus(coolingOff));
        }
        return toDto(limit, now);
    }

    /** Removal waits for the cooling-off period, like an increase; repeating it changes nothing. */
    @Transactional
    public DepositLimitDto remove(Long playerId, DepositLimitPeriod period) {
        lockPlayer(playerId);
        Instant now = clock.instant();
        DepositLimit limit = limits.findByPlayerIdAndPeriod(playerId, period)
                .orElseThrow(() -> limitNotFound(playerId, period));
        limit.applyDuePendingChange(now);
        if (!limit.isActive()) {
            throw limitNotFound(playerId, period);
        }
        if (!limit.isPending(null)) {
            limit.schedule(null, now.plus(coolingOff));
        }
        return toDto(limit, now);
    }

    /**
     * Runs inside the deposit transaction after the player row is locked. The deposit may use what
     * is left of the most restrictive limit, exactly up to it; on a tie the shortest period is reported.
     */
    public void check(Long playerId, BigDecimal amount) {
        Instant now = clock.instant();
        DepositLimit restrictive = null;
        BigDecimal allowed = null;
        for (DepositLimit limit : activeLimits(playerId, now)) {
            BigDecimal remaining = remaining(limit.getAmount(), used(playerId, limit.getPeriod(), now));
            if (allowed == null || remaining.compareTo(allowed) < 0) {
                restrictive = limit;
                allowed = remaining;
            }
        }
        if (restrictive != null && amount.compareTo(allowed) > 0) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("period", restrictive.getPeriod().name());
            details.put("limit", money(restrictive.getAmount()).toPlainString());
            details.put("remaining", allowed.toPlainString());
            throw new BusinessException("DEPOSIT_LIMIT_EXCEEDED", HttpStatus.UNPROCESSABLE_ENTITY,
                    "deposit of " + money(amount) + " exceeds the " + restrictive.getPeriod().name().toLowerCase()
                            + " deposit limit, remaining " + allowed, details);
        }
    }

    /**
     * Keeps daily <= weekly <= monthly. The new amount is compared with the current and the pending
     * amount of every other period that has a limit; a pending removal has no amount to compare.
     */
    private void checkOrder(Long playerId, DepositLimitPeriod period, BigDecimal amount, Instant now) {
        for (DepositLimit other : activeLimits(playerId, now)) {
            if (other.getPeriod() == period) {
                continue;
            }
            boolean longer = other.getPeriod().compareTo(period) > 0;
            List<BigDecimal> values = new ArrayList<>();
            values.add(other.getAmount());
            if (other.getPendingAmount() != null) {
                values.add(other.getPendingAmount());
            }
            for (BigDecimal value : values) {
                if (longer ? amount.compareTo(value) > 0 : amount.compareTo(value) < 0) {
                    Map<String, String> details = new LinkedHashMap<>();
                    details.put("period", period.name());
                    details.put("conflictingPeriod", other.getPeriod().name());
                    details.put("conflictingAmount", money(value).toPlainString());
                    throw new BusinessException("DEPOSIT_LIMIT_ORDER_VIOLATED", HttpStatus.UNPROCESSABLE_ENTITY,
                            period.name().toLowerCase() + " limit " + money(amount) + " must be "
                                    + (longer ? "at most" : "at least") + " the " + other.getPeriod().name().toLowerCase()
                                    + " limit " + money(value), details);
                }
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
