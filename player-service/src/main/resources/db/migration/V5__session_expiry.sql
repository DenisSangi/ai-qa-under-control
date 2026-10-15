-- transactions.type gains REFUND: the bet of an expired session goes back to the player.
-- game_sessions.status gains EXPIRED (CANCELED was never used).

-- An out-of-range amount is refused after its idempotency key is claimed, so the key row must
-- hold any amount a request can carry, not only amounts that fit a balance.
ALTER TABLE idempotency_keys ALTER COLUMN amount TYPE NUMERIC;

-- The expiry job looks for ACTIVE sessions started before the deadline.
CREATE INDEX idx_sessions_status_started ON game_sessions (status, started_at);
