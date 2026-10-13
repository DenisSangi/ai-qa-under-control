-- transactions.type gains WITHDRAWAL (exactly 10 characters, fits VARCHAR(10)).

CREATE TABLE idempotency_keys (
    id              BIGSERIAL PRIMARY KEY,
    player_id       BIGINT        NOT NULL REFERENCES players (id),
    idempotency_key VARCHAR(64)   NOT NULL,
    operation       VARCHAR(10)   NOT NULL, -- DEPOSIT | WITHDRAWAL
    amount          NUMERIC(12,2) NOT NULL,
    status          VARCHAR(12)   NOT NULL, -- IN_PROGRESS | COMPLETED
    response_body   TEXT,                   -- JSON of the original 200 response
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (player_id, idempotency_key)
);

-- One row per player and period. amount NULL = no limit (a removal has taken effect).
-- pending_effective_at set + pending_amount NULL = removal waiting for the cooling-off period.
CREATE TABLE deposit_limits (
    id                   BIGSERIAL PRIMARY KEY,
    player_id            BIGINT        NOT NULL REFERENCES players (id),
    period               VARCHAR(10)   NOT NULL, -- DAILY | WEEKLY | MONTHLY
    amount               NUMERIC(12,2),
    pending_amount       NUMERIC(12,2),
    pending_effective_at TIMESTAMPTZ,
    UNIQUE (player_id, period)
);

-- Deposit limit check sums a player's deposits inside a rolling window.
CREATE INDEX idx_tx_player_type_created ON transactions (player_id, type, created_at);
