# Arcade — regression plan (API level)

## 1. Scope and approach

System under test: the running Arcade stand — `player-service` (REST, WebSocket), `analytics-service`
(reached through `player-service` over gRPC and fed through Kafka), Postgres, Redis, Kafka, and the
WireMock payment provider stub.

The tests are black-box: they reach the system only over the network. The source code in this
repository (code, Javadoc, comments, migrations, OpenAPI) is used as the **specification**. The
running build was started from a different directory, so it is not assumed to match this source.

Oracles used besides the HTTP response itself:

| Oracle | What it proves |
|---|---|
| Postgres (read-only user `arcade_reader`) | ledger rows (`transactions`), idempotency records, session rows, leaderboard recomputed independently by SQL |
| Kafka (`game-events`, `player-events`) | event contract, key = playerId, nothing published for operations that did not take effect |
| WireMock request journal (read-only `/__admin/requests/find`) | what was actually sent to the payment provider: no double charge on replay, stable operation reference |
| WebSocket `/ws/leaderboard` | push contract of `LEADERBOARD_UPDATED` |
| Test clock `/api/test/clock` | time-based rules (cooling-off, rolling windows, session timeout, leaderboard periods) without waiting |

## 2. Constraints that shape the tests

- **Shared, long-lived state.** Every test creates its own players with unique names; nothing relies
  on an empty database, on ids, or on the absolute clock value. Expectations are computed relative
  to the service clock read at run time.
- **The clock only moves forward and is global.** Tests run sequentially (no parallel test
  execution), so a clock jump in one test cannot hit a session another test has open. Every jump
  is as small as the rule under test allows.
- **Read-only database, untouched stub.** No writes to Postgres, no changes to WireMock mappings;
  the only writes go through the public API. Provider outage (`PAYMENT_UNAVAILABLE`) therefore
  cannot be simulated and is out of scope.
- Runtime budget: the whole suite within 5 minutes (`mvn -B test`).

## 3. Risk-based priorities

| Priority | Area | Why |
|---|---|---|
| P1 | Money movement: deposit, withdrawal, bet, win, refund, balance = ledger | real money; any error is a financial loss or a regulatory incident |
| P1 | Idempotency of deposit/withdrawal | client retries must never charge/pay twice |
| P1 | Deposit limits (responsible gambling) | regulatory: limits must be enforced, increases/removals must wait for cooling-off |
| P1 | Concurrency of balance changes | lost updates, negative balances, double finish |
| P2 | Session lifecycle and expiry/refund | stuck bets, late finishes |
| P2 | Leaderboard (REST, cache, periods, WebSocket push) | visible to all players; period boundaries |
| P2 | Analytics stats via Kafka + gRPC | cross-service contract |
| P2 | Event contract on Kafka | downstream consumers |
| P3 | Validation and error contract | stable `ApiError` shape, 4xx vs 5xx |

## 4. Test cases

### Players (PL)
- PL-01 create a player: 201, defaults (level 1, balance 0), country upper-cased, `createdAt` on the service clock
- PL-02 duplicate username → 409 `DUPLICATE_USERNAME`; duplicate email → 409 `DUPLICATE_EMAIL`
- PL-03 field validation (username 3–50, e-mail format, country exactly 2) → 400 `VALIDATION_ERROR` with a detail per field
- PL-04 get unknown player → 404 `NOT_FOUND`
- PL-05 list: pagination metadata, page size honoured (default 20), country filter returns only that country

### Deposits (DEP)
- DEP-01 deposit credits the balance, writes one `DEPOSIT` ledger row, sends one charge to the provider
- DEP-02 amount range 10.00–50000.00 inclusive (9.99, 10.00, 50000.00 vs. 50000.01, negative, 1e20) → 422 `DEPOSIT_AMOUNT_OUT_OF_RANGE` with `min`/`max`
- DEP-03 amount format: 3 decimals, string, missing, malformed JSON → 400
- DEP-04 provider declines (> 10000) → 422 `PAYMENT_DECLINED`, balance and ledger unchanged
- DEP-05 unknown player → 404

### Withdrawals (WD)
- WD-01 withdrawal debits the balance, writes one `WITHDRAWAL` ledger row; withdrawing the full balance leaves 0
- WD-02 insufficient funds → 422 `INSUFFICIENT_FUNDS`, provider not called
- WD-03 provider declines (> 5000) → 422 `PAYOUT_DECLINED`, balance unchanged
- WD-04 amount range 10.00–50000.00 → 422 `WITHDRAWAL_AMOUNT_OUT_OF_RANGE` (negative amounts never credit); wrong format → 400

### Idempotency (IDEM)
- IDEM-01 replay with the same key and amount (`50` = `50.00`) → 200, `Idempotent-Replayed: true`, original body, no second charge, no second ledger row, no second event; keys do not expire (replay 2 days later); a withdrawal replay works even when the balance could no longer cover it
- IDEM-02 replay returns the original response even after the balance changed
- IDEM-03 same key, different amount or different operation → 422 `IDEMPOTENCY_KEY_REUSED` (also for an out-of-range amount: the key is checked before the range)
- IDEM-04 header missing/blank → 400 `IDEMPOTENCY_KEY_REQUIRED`; > 64 chars or non-ASCII bytes (sent raw over a socket: the JDK client rewrites them to `?`) → 400 `IDEMPOTENCY_KEY_INVALID`; exactly 64 printable chars accepted
- IDEM-05 keys are scoped per player; provider references differ between players and between keys
- IDEM-06 failed operation (out of range, declined) releases the key: the same key can be retried; the provider receives the same reference for the retry
- IDEM-07 check order: body format (400) before player (404) before key claim

### Deposit limits (LIM)
- LIM-01 first limit applies at once; GET lists it with `used`/`remaining`
- LIM-02 decrease applies at once; increase becomes pending with `effectiveAt = now + 24h`, the old amount still applies
- LIM-03 increase takes effect after the cooling-off period (not 1 minute before it); a due change is applied by a deposit or a PUT on its own, without a GET first
- LIM-04 repeating the pending increase keeps `effectiveAt`; setting the current amount cancels the pending change
- LIM-05 removal is pending for 24h, then the limit disappears; repeating the removal keeps `effectiveAt`; removing a missing limit → 404; a limit set after the removal took effect is a first limit (applies at once); during a pending removal a decrease applies at once and an increase or removal replaces the pending change
- LIM-06 order daily ≤ weekly ≤ monthly, including pending amounts → 422 `DEPOSIT_LIMIT_ORDER_VIOLATED`
- LIM-07 limit amount range 10.00–1000000.00 → 422 `DEPOSIT_LIMIT_OUT_OF_RANGE`; bad period → 400
- LIM-08 enforcement: deposit exactly up to the remaining amount succeeds, one cent over → 422 `DEPOSIT_LIMIT_EXCEEDED` with `period`, `limit`, `remaining`; provider not called; a decrease below `used` gives `remaining` 0.00, never negative
- LIM-09 most restrictive limit is reported; tie → shortest period
- LIM-10 rolling windows: daily (1 day), weekly (7 days) and monthly (30 days), boundary ±1 minute
- LIM-11 withdrawals, wins and declined deposits do not change `used`
- LIM-12 unknown player → 404

### Game sessions (SES) and expiry (EXP)
- SES-01 start takes the bet (balance, `BET` ledger row), status ACTIVE
- SES-02 finish credits the win (`WIN` ledger row only if win > 0), status FINISHED, `finishedAt` set
- SES-03 one active session per player → 409 `ACTIVE_SESSION_EXISTS`
- SES-04 bet range 1.00–500.00 → 422; insufficient funds → 422; unknown game/player → 400/404; all five games playable; win with > 10 integer digits → 400
- SES-05 check order: player (404) → bet range (422) → active session (409) → balance (422)
- SES-06 finish twice → 409 `SESSION_NOT_ACTIVE`; negative win / 3 decimals → 400; unknown session → 404
- EXP-01 a session not finished within 60s expires: status EXPIRED, bet refunded (`REFUND` ledger row), `SESSION_EXPIRED` event
- EXP-02 finish at 59s succeeds; finish at ≥ 60s → 409 `SESSION_NOT_ACTIVE`, no win credited
- EXP-03 after expiry the player can start a new session

### Leaderboard (LB)
- LB-01 REST top equals an independent SQL computation for the current period (FINISHED only, net > 0, top 10, desc)
- LB-02 a finished winning session is visible immediately (cache refreshed after commit)
- LB-03 in a fresh period: results summed per player; losing / zero-net players and expired sessions not listed; at most 10 entries, best first
- LB-04 three consecutive period boundaries (so the 1st, 11th and 21st are each crossed once): previous-period results drop out, a push is sent, and a session started before the boundary but finished after it counts in the new period
- LB-05 WebSocket pushes `LEADERBOARD_UPDATED` with the new top to every connected client when it changes; the pushed top equals the REST top
- LB-06 no push when the top does not change

### Stats (ST)
- ST-01 no stats for a player without finished sessions → 404
- ST-02 totals and RTP (2 decimals, HALF_UP) after finished sessions; strings as decimal
- ST-03 expired and active sessions are not counted

### Events (EV)
- EV-01 `REGISTERED`, `DEPOSIT`, `WITHDRAWAL` on `player-events`, keyed by playerId, amount exact
- EV-02 `SESSION_STARTED`, `SESSION_FINISHED` on `game-events`, same partition, in order
- EV-03 no event for declined / out-of-range / limit-exceeded operations and for replays

### Concurrency (CON)
- CON-01 parallel deposits with one key → exactly one executes
- CON-02 parallel withdrawals exceeding the balance → balance never negative, ledger consistent
- CON-03 parallel deposits with different keys → no lost update
- CON-04 parallel starts → one active session; parallel finishes → one success, win credited once

### Ledger (LED)
- LED-01 after a mixed scenario, balance = Σ deposits + wins + refunds − withdrawals − bets in the ledger

### Error contract (ERR)
- ERR-01 every error has `code`, `message`, `timestamp` (service clock)
- ERR-02 client mistakes answer 4xx, not 500: non-numeric path id (400), unknown route (404),
  wrong method (405), unsupported content type (415), invalid paging (400)
- ERR-03 the test clock refuses zero/negative/malformed durations and does not move

## 5. Out of scope
- Provider outage/timeout (`PAYMENT_UNAVAILABLE`) and analytics outage (`ANALYTICS_UNAVAILABLE`):
  they need a change of the stub or stopping a service, both forbidden here.
- Redis outage fallback; load/performance; UI; security (authN/authZ do not exist in the API).

## 6. Entry / exit criteria
- Entry: `/actuator/health` UP, test clock endpoint present.
- Exit: every test executed; each failure triaged as test defect (fixed) or product defect (left
  failing and reported in `TEST_REPORT.md`).
