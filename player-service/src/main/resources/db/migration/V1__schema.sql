CREATE TABLE players (
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(50)   NOT NULL UNIQUE,
    email      VARCHAR(100)  NOT NULL UNIQUE,
    country    VARCHAR(2)    NOT NULL,
    level      INT           NOT NULL DEFAULT 1,
    balance    NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE game_sessions (
    id          BIGSERIAL PRIMARY KEY,
    player_id   BIGINT        NOT NULL REFERENCES players (id),
    game        VARCHAR(30)   NOT NULL,
    bet_amount  NUMERIC(12,2) NOT NULL,
    win_amount  NUMERIC(12,2) NOT NULL DEFAULT 0,
    status      VARCHAR(20)   NOT NULL,
    started_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_sessions_player ON game_sessions (player_id);
CREATE INDEX idx_sessions_status ON game_sessions (status);

CREATE TABLE transactions (
    id         BIGSERIAL PRIMARY KEY,
    player_id  BIGINT        NOT NULL REFERENCES players (id),
    type       VARCHAR(10)   NOT NULL, -- DEPOSIT | BET | WIN
    amount     NUMERIC(12,2) NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_tx_player ON transactions (player_id);
