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
 * A claimed Idempotency-Key of one player. Rows are inserted by a native
 * INSERT ... ON CONFLICT DO NOTHING (see IdempotencyRepository), never by JPA.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    public enum Status {
        IN_PROGRESS,
        COMPLETED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "idempotency_key", nullable = false)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TxType operation;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    /** Same key, same request: the operation and the amount match ({@code 50} equals {@code 50.00}). */
    public boolean matches(TxType operation, BigDecimal amount) {
        return this.operation == operation && this.amount.compareTo(amount) == 0;
    }

    public void complete(String responseBody) {
        this.status = Status.COMPLETED;
        this.responseBody = responseBody;
    }

    public Long getId() {
        return id;
    }

    public Long getPlayerId() {
        return playerId;
    }

    public String getKey() {
        return key;
    }

    public TxType getOperation() {
        return operation;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Status getStatus() {
        return status;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
