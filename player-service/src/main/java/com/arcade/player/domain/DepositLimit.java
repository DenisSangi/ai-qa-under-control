package com.arcade.player.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Responsible-gambling deposit limit of one player for one period.
 *
 * <p>A decrease (or the first limit) applies at once; an increase or a removal waits for the
 * cooling-off period as a pending change, and the current amount keeps applying until then.
 * Pending changes are applied lazily by {@link #applyDuePendingChange} on every access.
 */
@Entity
@Table(name = "deposit_limits")
public class DepositLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DepositLimitPeriod period;

    /** null = no limit. */
    private BigDecimal amount;

    /** null together with a set {@link #pendingEffectiveAt} = pending removal. */
    @Column(name = "pending_amount")
    private BigDecimal pendingAmount;

    @Column(name = "pending_effective_at")
    private Instant pendingEffectiveAt;

    protected DepositLimit() {
    }

    public DepositLimit(Long playerId, DepositLimitPeriod period) {
        this.playerId = playerId;
        this.period = period;
    }

    public void applyDuePendingChange(Instant now) {
        if (pendingEffectiveAt != null && !now.isBefore(pendingEffectiveAt)) {
            amount = pendingAmount;
            clearPending();
        }
    }

    public boolean isActive() {
        return amount != null;
    }

    public boolean hasPendingChange() {
        return pendingEffectiveAt != null;
    }

    /** {@code newAmount == null} asks about a pending removal. */
    public boolean isPending(BigDecimal newAmount) {
        if (!hasPendingChange()) {
            return false;
        }
        return newAmount == null ? pendingAmount == null : pendingAmount != null && pendingAmount.compareTo(newAmount) == 0;
    }

    /** Applies at once and cancels any pending change. */
    public void applyNow(BigDecimal newAmount) {
        amount = newAmount;
        clearPending();
    }

    /** Replaces any pending change. {@code newAmount == null} schedules a removal. */
    public void schedule(BigDecimal newAmount, Instant effectiveAt) {
        pendingAmount = newAmount;
        pendingEffectiveAt = effectiveAt;
    }

    private void clearPending() {
        pendingAmount = null;
        pendingEffectiveAt = null;
    }

    public Long getId() {
        return id;
    }

    public Long getPlayerId() {
        return playerId;
    }

    public DepositLimitPeriod getPeriod() {
        return period;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getPendingAmount() {
        return pendingAmount;
    }

    public Instant getPendingEffectiveAt() {
        return pendingEffectiveAt;
    }
}
