CREATE TABLE player_stats (
    player_id      BIGINT PRIMARY KEY,
    total_sessions BIGINT        NOT NULL DEFAULT 0,
    total_bet      NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_win      NUMERIC(14,2) NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
