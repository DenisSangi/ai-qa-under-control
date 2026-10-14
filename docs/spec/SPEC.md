# Player Wallet — Specification

## 1. Purpose and scope

This document specifies the expected behavior of the player wallet of a real-money gaming
platform. All flows below are served by `player-service`.

A player's money moves along one path: a **deposit** through a payment provider, **bets** and
**wins** in game sessions, and a **withdrawal** through the payment provider. Deposit limits exist
for responsible gaming: a player restricts how much they can deposit.

**In scope**

- Deposit and idempotency of money operations
- Deposit limits
- Withdrawal
- Game session: bet, win, expiry
- Leaderboard

**Precondition: a player exists.** A player is created with `POST /api/players` and a JSON body
with a unique `username`, a unique `email` and a country code, for example
`{"username": "alice_01", "email": "alice_01@example.com", "country": "DE"}`. Registration rules are
not specified here.

**Out of scope**

- Registration rules and the player list (`GET /api/players`)
- Player statistics and analytics (`GET /api/players/{id}/stats`)
- Authentication and authorization
- Age verification (18+) and KYC
- Deposit wagering requirements and anti-money-laundering (AML) checks
- Loss limits, wager limits and session-time limits
- Reconciliation with the payment provider, including cases where the provider processed an
  operation that the service did not record
- Payment provider availability in the service health check

**Source of truth.** Where this specification and the service's OpenAPI document (Swagger UI)
disagree, this specification prevails.

## 2. Terms

| Term | Meaning |
|---|---|
| Player | A wallet owner, identified by a numeric `id`. |
| Balance | The player's money, returned in `balance` by `GET /api/players/{id}`. |
| Journal | The record of the player's money movements: table `transactions`, one record per movement, `type` is `DEPOSIT`, `WITHDRAWAL`, `BET`, `WIN` or `REFUND`. Read-only, no REST endpoint. |
| Payment provider | External system that moves real money: `POST /payments` for deposits, `POST /payouts` for withdrawals. |
| Money operation | A deposit, a withdrawal or a session start (bet). |
| Idempotency key | The value of the `Idempotency-Key` request header of a money operation. |
| Fingerprint | The parts of a request that make it "the same request" for its idempotency key (GEN-11). |
| Consumed key | A key bound to an operation that completed successfully. |
| Replay | A request with a consumed key and the same fingerprint. |
| Session | One game round. Status `ACTIVE` after start, then `FINISHED` (finished by the client) or `EXPIRED` (not finished in time). |
| Bet | The amount the player stakes when starting a session. |
| Win | The amount the client reports when finishing a session. A win of 0 is a loss. |
| Deposit limit | A maximum total of deposits for one period: `daily`, `weekly` or `monthly`. |
| Window | The rolling time span of a period: 24 hours (daily), 7 days (weekly), 30 days (monthly), ending at the moment of the check. |
| Pending change | A limit increase or removal that is requested but not yet in effect. |
| Cooling-off | The 24 hours a pending change waits before it takes effect. |
| Net result | For a session: win − bet. For a player: the sum over the player's sessions counted by the leaderboard. |
| Leaderboard period | One third of a calendar month in UTC (LDB-02). |
| Top | The leaderboard list as `GET /api/leaderboard` returns it. |
| Service clock | The current time as the service sees it. Moments and spans of business rules — windows, cooling-off, the session deadline, leaderboard periods, timestamps — are on the service clock. Times a client waits for a response or for a result to become observable (GEN-18, SES-11, LDB-05, LDB-07) are real time. In the test environment the service clock can be advanced (section 10). |

## 3. General rules

### Money

**GEN-01. Currency.** All monetary amounts are in US dollars (USD). No other currency exists.

**GEN-02. Decimal places in request amounts.** Every amount in a request — `amount` of a deposit,
a withdrawal and a deposit limit, `bet`, `win` — has at most 2 decimal places.
- Refusal: `400 VALIDATION_ERROR`.
- Bounds: `10.01` accepted; `10.001` refused.

**GEN-03. Numbers sent as strings.** The numeric request fields `amount` (deposit, withdrawal,
deposit limit), `bet`, `win` and `playerId` must be JSON numbers.
- Refusal: `400 VALIDATION_ERROR`, the field name in `details`.
- Example: `{"amount": "10"}` is refused.

### Requests and errors

**GEN-04. Error body.** Every error response described in this document is a JSON object with a
machine-readable `code` and a `message`; some also carry `details`. Error codes in this document
are values of `code`.

**GEN-05. Required fields.** A required request field that is missing or `null` is refused with
`400 VALIDATION_ERROR`.

**GEN-06. Non-numeric id in the path.** A non-numeric `{id}` in `/api/players/{id}…` or
`/api/sessions/{id}…` is refused with `400 BAD_REQUEST`.

**GEN-07. Unknown player or session.** A request for a player that does not exist is refused with
`404 NOT_FOUND`. A request for a session that does not exist is refused with `404 NOT_FOUND`.

### Idempotency of money operations

These rules apply to the deposit, the withdrawal and the session start.

**GEN-08. Key required.** The `Idempotency-Key` header is required. Missing or empty: `400
IDEMPOTENCY_KEY_REQUIRED`.

**GEN-09. Key format.** The key is 1 to 64 printable ASCII characters. Otherwise: `400
IDEMPOTENCY_KEY_INVALID`.
- Bounds: 1 and 64 characters accepted; 65 characters refused.

**GEN-10. Key scope.** A key belongs to one player: the player in the path (deposit, withdrawal)
or `playerId` in the body (session start). All three operations of a player share one key space:
a key used for a deposit cannot be used for a withdrawal or a session start of the same player
(GEN-13).
The same key value used by two different players is two independent keys.

**GEN-11. Fingerprint.** Deposit and withdrawal: the operation and the amount. Session start: the
operation, the game and the bet. Amounts are compared by value: `50` and `50.00` are the same.

**GEN-12. Replay.** A replay returns the same HTTP status and the same body as the original
response, with the header `Idempotent-Replayed: true`.
- The operation is not performed again: balance, journal and events do not change.
- The original response is returned even if a check would refuse the request now (for example,
  an exhausted deposit limit or a lower balance).
- The current state is read with `GET /api/players/{id}` or `GET /api/sessions/{id}`.

**GEN-13. Same key, different request.** A consumed key with a different fingerprint (another
amount, another operation, another game or bet) is refused with `422 IDEMPOTENCY_KEY_REUSED`.
Nothing changes.

**GEN-14. Same key in progress.** A request whose key belongs to a request of the same player
that is still being processed is refused with `409 IDEMPOTENCY_KEY_IN_PROGRESS`. Nothing changes.

**GEN-15. Unsuccessful requests do not consume the key.** If a request ends with any error, its
key stays free: a later request with the same key is processed as a new request.

**GEN-16. Key lifetime.** A consumed key stays bound to its operation without time limit.
- Example: a replay after the service clock has been advanced by 31 days returns the original
  response.

### Payment provider

These rules apply to the deposit (`POST /payments`) and the withdrawal (`POST /payouts`).

**GEN-17. Operation reference.** Every request to the provider carries a field `reference`.
- A repeated request with the same idempotency key sends the same `reference`.
- Different keys send different `reference` values, including the same key value of different
  players.
- The format of `reference` is not specified.
- Observable in the provider request journal (section 10).

**GEN-18. Provider timeout.** If the provider does not respond within 5 seconds, the request is
refused with `504 PAYMENT_TIMEOUT`. The client receives the response no later than 6 seconds after
sending the request.

**GEN-19. State after a timeout.** After a `504 PAYMENT_TIMEOUT`, balance, journal and events do
not change — also when the provider responds later. The key is not consumed; a request with the
same key is a new attempt.

**GEN-20. Provider unavailable.** If the provider cannot be reached, the request is refused with
`502 PAYMENT_UNAVAILABLE`. Balance, journal and events do not change; the key is not consumed.

### Events

**GEN-21. Topics and types.**
- `player-events`: types `REGISTERED`, `DEPOSIT`, `WITHDRAWAL`. Required fields: `eventId`,
  `type`, `playerId`, `amount`, `at`. `amount` is the operation amount for `DEPOSIT` and
  `WITHDRAWAL` and `null` for `REGISTERED`. `at` is the service-clock time of the operation.
- `game-events`: types `SESSION_STARTED`, `SESSION_FINISHED`, `SESSION_EXPIRED`. Fields: SES-01,
  SES-07, SES-11.

**GEN-22. Events only for completed operations.** An event is published only for an operation that
has taken effect, that is, is visible through REST and the journal. A refused or failed request
publishes no event.

**GEN-23. Amounts in events.** `amount`, `bet` and `win` in events are JSON numbers equal by value
to the amount of the operation: `100`, `100.00` and `1E+2` are equal.

**GEN-24. Event key and order.** The key of every event is the player id. Events of one player
within one topic arrive in the order the operations happened. There is no ordering guarantee
between `player-events` and `game-events`.

## 4. Invariants

**GEN-25. Balance equals journal.** For every player created through the API:
balance = sum of `DEPOSIT`, `WIN` and `REFUND` records − sum of `WITHDRAWAL` and `BET` records.
The seed players are an exception (section 10).

**GEN-26. Concurrent operations.** Operations of one player sent at the same time give the same
result as if they had been processed one after another in some order: the balance matches the
journal (GEN-25) and no change is lost.

## 5. Deposit and idempotency

**DEP-01. Successful deposit.** `POST /api/players/{id}/deposit` with an `Idempotency-Key` and the
body `{"amount": <number>}`, approved by the provider:
- Response: `200` with the player; `balance` has increased by `amount`.
- Journal: one `DEPOSIT` record with `amount`.
- Event: `DEPOSIT` in `player-events`.
- Provider: one `POST /payments` request.

**DEP-02. Deposit amount range.** The amount is from 10.00 to 50000.00 inclusive.
- Refusal: `422 DEPOSIT_AMOUNT_OUT_OF_RANGE`, `details`: `min` = `"10.00"`, `max` = `"50000.00"`.
- Bounds: 10.00 and 50000.00 accepted; 9.99 and 50000.01 refused.
- Unchanged: balance, journal, events. The provider is not called. The key is not consumed.

**DEP-03. Deposit limits.** A deposit that exceeds the player's deposit limits is refused (LIM-12).

**DEP-04. Provider decline.** If the provider declines the payment, the deposit is refused with
`422 PAYMENT_DECLINED`.
- Unchanged: balance, journal, events. The key is not consumed.

**DEP-05. Order of checks.** If a deposit request breaks several rules, the response reports the
first one in this order:
1. request format (`400`);
2. player exists (`404`);
3. idempotency key: replay, `409 IDEMPOTENCY_KEY_IN_PROGRESS`, `422 IDEMPOTENCY_KEY_REUSED`;
4. amount range (DEP-02);
5. deposit limits (LIM-12);
6. provider result (DEP-04, GEN-18, GEN-20).

## 6. Deposit limits

**LIM-01. Period in the path.** The `{period}` path segment of
`/api/players/{id}/deposit-limits/{period}` is exactly `daily`, `weekly` or `monthly`, in lower
case.
- Refusal: `400 VALIDATION_ERROR`.
- Example: `DAILY` and `yearly` are refused.

**LIM-02. Reading limits.** `GET /api/players/{id}/deposit-limits` returns `200` with a list of
objects `{period, amount, used, remaining, pending}`, one per period that has a limit.
- A period without a limit is not in the list. A player without limits gets an empty list.
- `period` is `DAILY`, `WEEKLY` or `MONTHLY` — upper case in responses and in `details` of
  refusals, while the path uses lower case (LIM-01).
- `used` is the amount of deposits counted toward the period's limit (LIM-11).
- `remaining` is the period's own remainder: `amount` − `used`, but not below 0.
- `pending` is `null` when no change is pending; otherwise `{amount, effectiveAt}`.
  `pending.amount` = `null` means the limit is removed at `effectiveAt`.

**LIM-03. Limit amount range.** `PUT /api/players/{id}/deposit-limits/{period}` with
`{"amount": <number>}`: the amount is from 10.00 to 1000000.00 inclusive.
- Refusal: `422 DEPOSIT_LIMIT_OUT_OF_RANGE`, `details`: `min` = `"10.00"`, `max` = `"1000000.00"`.
- Bounds: 10.00 and 1000000.00 accepted; 9.99 and 1000000.01 refused.
- Unchanged: current and pending values of all periods.

**LIM-04. Order of periods.** Limits keep daily ≤ weekly ≤ monthly.
- A new value is compared with the current **and** the pending value of every other period that
  has a limit: it must be ≤ every value of a longer period and ≥ every value of a shorter period.
- Periods without a limit and pending removals take no part in the comparison.
- A request that breaks the order is refused; no other period changes by itself.
- Refusal: `422 DEPOSIT_LIMIT_ORDER_VIOLATED`, `details`: `period` (the requested period),
  `conflictingPeriod`, `conflictingAmount`.
- Unchanged: current and pending values of all periods.
- Examples: daily 100, weekly 300: weekly 100 accepted, weekly 99 refused. Daily 100 with a pending
  increase to 250, weekly 300: weekly 200 refused (conflicts with the pending daily 250).

**LIM-05. Order of checks for setting a limit.** 1. request format (`400`); 2. player exists
(`404`); 3. amount range (LIM-03); 4. order of periods (LIM-04); 5. the change is applied.

**LIM-06. Immediate changes.** Setting a limit for a period without one, or setting a lower amount
than the current one, takes effect immediately.
- Response: `200` with the limit; `amount` is the new value, `pending` is `null`.
- A pending change of that period is discarded.

**LIM-07. Increase after cooling-off.** Setting a higher amount than the current one takes effect
after the cooling-off.
- Response: `200` with the limit; `amount` is unchanged; `pending.amount` is the new value;
  `pending.effectiveAt` is the request time + 24 hours.
- Until `effectiveAt` deposits are checked against the current amount; from `effectiveAt` on,
  `amount` is the new value and `pending` is `null`.

**LIM-08. Repeated and replacing requests.**
- A request equal to the pending change (the same increase, or a removal while a removal is
  pending) changes nothing: `200`, `effectiveAt` stays the same.
- A request with a different value replaces the pending change; a new cooling-off starts if the
  new value is itself an increase.

**LIM-09. Cancelling a pending change.** `PUT` with the current amount cancels any pending change
(increase or removal) immediately: `200`, `pending` is `null`, `amount` unchanged.

**LIM-10. Removing a limit.** `DELETE /api/players/{id}/deposit-limits/{period}` removes the limit
after the cooling-off.
- Response: `200` with the limit; `pending` = `{amount: null, effectiveAt: request time + 24
  hours}`.
- Until `effectiveAt` the limit applies; from `effectiveAt` on, the period is not in the list
  (LIM-02) and does not limit deposits.
- A period without a limit: `404 NOT_FOUND`.

**LIM-11. What is counted.** For each period:
- A deposit outside the window is never counted. A deposit made exactly one window length before
  the check is outside the window.
- A refused deposit is never counted.
- A withdrawal never reduces the counted amount.

**LIM-12. Deposit against limits.** A deposit is accepted only if its amount does not exceed the
amount that can still be deposited: the smallest remaining amount over all periods with a limit.
A deposit exactly equal to it is accepted.
- Refusal: `422 DEPOSIT_LIMIT_EXCEEDED`, `details`:
  - `period`: the most restrictive period, the one with the smallest remaining amount; on a tie,
    the shortest (daily, then weekly, then monthly);
  - `limit`: the limit amount of that period;
  - `remaining`: the amount that can still be deposited.
- Unchanged: balance, journal, events. The provider is not called. The key is not consumed.
- Example: daily remaining 50, monthly remaining 0, deposit 60: `period` = `MONTHLY`, `remaining`
  = 0.

## 7. Withdrawal

**WDR-01. Successful withdrawal.** `POST /api/players/{id}/withdraw` with an `Idempotency-Key` and
the body `{"amount": <number>}`, approved by the provider:
- Response: `200` with the player; `balance` has decreased by `amount`.
- Journal: one `WITHDRAWAL` record with `amount`.
- Event: `WITHDRAWAL` in `player-events`.
- Provider: one `POST /payouts` request.

**WDR-02. Withdrawal amount range.** The amount is from 10.00 to 50000.00 inclusive. A balance
below 10.00 therefore cannot be withdrawn.
- Refusal: `422 WITHDRAWAL_AMOUNT_OUT_OF_RANGE`, `details`: `min` = `"10.00"`, `max` =
  `"50000.00"`.
- Bounds: 10.00 and 50000.00 accepted; 9.99 and 50000.01 refused.
- Unchanged: balance, journal, events. The provider is not called. The key is not consumed.

**WDR-03. No withdrawal during an active session.** A withdrawal is refused while the player has
any session in status `ACTIVE`. An `EXPIRED` session does not block a withdrawal.
- Refusal: `409 ACTIVE_SESSION_EXISTS`.
- Unchanged: balance, journal, events. The provider is not called. The key is not consumed.

**WDR-04. Sufficient balance.** The amount must not exceed the balance. Withdrawing the whole
balance is allowed.
- Refusal: `422 INSUFFICIENT_FUNDS`.
- Unchanged: balance, journal, events. The provider is not called. The key is not consumed.

**WDR-05. Provider decline.** If the provider declines the payout, the withdrawal is refused with
`422 PAYOUT_DECLINED`.
- Unchanged: balance, journal, events. The key is not consumed.

**WDR-06. Order of checks.** 1. request format (`400`); 2. player exists (`404`); 3. idempotency
key; 4. amount range (WDR-02); 5. active session (WDR-03); 6. balance (WDR-04); 7. provider result
(WDR-05, GEN-18, GEN-20).

## 8. Game session

**SES-01. Starting a session.** `POST /api/sessions` with an `Idempotency-Key` and the body
`{"playerId": <number>, "game": <string>, "bet": <number>}`:
- Response: `201` with the session `{id, playerId, game, betAmount, winAmount, status, startedAt,
  finishedAt}`: `status` = `ACTIVE`, `winAmount` = 0, `finishedAt` = `null`.
- Balance decreases by `bet`.
- Journal: one `BET` record with `bet`.
- Event: `SESSION_STARTED` in `game-events` with `eventId`, `type`, `sessionId`, `playerId`,
  `game`, `bet`, `startedAt`.

**SES-02. Game.** `game` is one of `slots`, `roulette`, `blackjack`, `poker`, `crash`, exactly in
lower case.
- Refusal: `400 VALIDATION_ERROR`, `game` in `details`.
- Example: `Slots` and any other string are refused.

**SES-03. Bet range.** The bet is from 1.00 to 500.00 inclusive.
- Refusal: `422 BET_AMOUNT_OUT_OF_RANGE`, `details`: `min` = `"1.00"`, `max` = `"500.00"`.
- Bounds: 1.00 and 500.00 accepted; 0.99 and 500.01 refused.
- Unchanged: balance, journal, events. The key is not consumed.

**SES-04. One active session.** A player has at most one `ACTIVE` session. Starting another one is
refused; an `EXPIRED` session does not count.
- Refusal: `409 ACTIVE_SESSION_EXISTS`.
- Unchanged: balance, journal, events. The key is not consumed.

**SES-05. Sufficient balance.** The bet must not exceed the balance. A bet of the whole balance is
allowed.
- Refusal: `422 INSUFFICIENT_FUNDS`.
- Unchanged: balance, journal, events. The key is not consumed.

**SES-06. Order of checks.** 1. request format (`400`, including SES-02); 2. player exists (`404`);
3. idempotency key; 4. bet range (SES-03); 5. active session (SES-04); 6. balance (SES-05).

**SES-07. Finishing a session.** `POST /api/sessions/{id}/finish` with the body
`{"win": <number>}`, `win` ≥ 0:
- Response: `200` with the session: `status` = `FINISHED`, `winAmount` = `win`, `finishedAt` set.
- Balance increases by `win`.
- Journal: one `WIN` record with `win` if `win` > 0.
- Event: `SESSION_FINISHED` in `game-events` with `eventId`, `type`, `sessionId`, `playerId`,
  `game`, `bet`, `win`, `finishedAt`.
- A negative `win` is refused with `400 VALIDATION_ERROR`, `win` in `details`. A `win` of 0 is
  accepted (SES-08).

**SES-08. Loss.** A win of 0 is a loss: the session becomes `FINISHED` with `winAmount` 0, no `WIN`
record is written, and `SESSION_FINISHED` is published with `win` 0.

**SES-09. Finishing a session that is not active.** Finishing a session in status `FINISHED` or
`EXPIRED` is refused with `409 SESSION_NOT_ACTIVE`. Balance, journal, events and the session do not
change.

**SES-10. Finish deadline.** A finish is accepted only strictly before `startedAt` + 60 seconds.
At `startedAt` + 60 seconds or later it is refused with `409 SESSION_NOT_ACTIVE`, even if the
expiry (SES-11) is not observable yet.
- Bounds: with the service clock at `startedAt` + 59 s the finish is accepted; at `startedAt` +
  60 s it is refused.

**SES-11. Expiry.** A session not finished before `startedAt` + 60 seconds expires:
- `status` = `EXPIRED`.
- Balance increases by the bet.
- Journal: one `REFUND` record with the bet.
- Event: `SESSION_EXPIRED` in `game-events` with `eventId`, `type`, `sessionId`, `playerId`,
  `game`, `bet`, `expiredAt`.
- All of the above is observable through REST, the journal and Kafka no later than 5 seconds of
  real time after the service clock passes `startedAt` + 60 seconds — by the passage of time or by
  advancing the test clock.

**SES-12. After expiry.** An `EXPIRED` session is not active: it does not prevent a new session
(SES-04) or a withdrawal (WDR-03), and the leaderboard does not count it (LDB-03).

**SES-13. Reading a session.** `GET /api/sessions/{id}` returns `200` with the session.

## 9. Leaderboard

**LDB-01. Reading the leaderboard.** `GET /api/leaderboard` returns `200` with a list of at most
10 entries `{playerId, username, netResult}`, sorted by `netResult` in descending order.

**LDB-02. Period.** The leaderboard covers the current leaderboard period: a third of a calendar
month in UTC, by the service clock.
- Periods: from the 1st 00:00 to the 11th 00:00; from the 11th 00:00 to the 21st 00:00; from the
  21st 00:00 to the 1st 00:00 of the next month. The start is included, the end is not.

**LDB-03. Net result.** A player's `netResult` is the sum of win − bet over the player's
`FINISHED` sessions whose `finishedAt` is in the current period. `ACTIVE` and `EXPIRED` sessions
are not counted.

**LDB-04. Who is listed.** Only players with `netResult` > 0 are listed. The list can have fewer
than 10 entries and is empty when no player has a positive net result in the period.

**LDB-05. Freshness.** A finished session is reflected in `GET /api/leaderboard` and on the
WebSocket (LDB-06) no later than 30 seconds after the finish response.

**LDB-06. Live updates.** WebSocket `/ws/leaderboard` sends `{"type": "LEADERBOARD_UPDATED",
"top": [...]}`, where `top` has the format of LDB-01.
- A message is sent if and only if the top differs from the last top sent: other players, another
  order or any other `netResult`.
- The `top` of a message equals what `GET /api/leaderboard` returns at that moment.

**LDB-07. New period.** The start of a new period is a change of the top: `GET /api/leaderboard`
and the WebSocket reflect it no later than 30 seconds of real time after the service clock passes
the period boundary — by the passage of time or by advancing the test clock.

## 10. Environment

**Addresses.**

| Component | Address |
|---|---|
| REST API | `http://localhost:8080` |
| Leaderboard WebSocket | `ws://localhost:8080/ws/leaderboard` |
| Kafka | `localhost:9094` |
| Payment provider stub | `http://localhost:8089` |
| Database | `localhost:5433` |

**Test clock.**
- `GET /api/test/clock` returns the service clock.
- `POST /api/test/clock/advance` with `{"duration": "PT25H"}` (an ISO-8601 duration) moves the
  service clock forward.
- The clock only moves forward. It is shared by the whole service: tests running in parallel affect
  each other. A restart of the service resets it.

**Database (read-only).** PostgreSQL at `localhost:5433`, database `arcade`, user `arcade_reader`,
password `arcade_reader`. The user can only read: any write is refused.

**Payment provider stub.** The provider is a stub with fixed rules:

| Request | Amount | Stub answer |
|---|---|---|
| `POST /payments` | ≤ 10000 | approved |
| `POST /payments` | > 10000 | declined |
| `POST /payouts` | ≤ 5000 | approved |
| `POST /payouts` | > 5000 | declined |

**Changing the provider's behavior.** Tests may use the stub's admin API:
- slow provider: `POST /__admin/settings` with `{"fixedDelay": <milliseconds>}`; restore with
  `{"fixedDelay": 0}`;
- unreachable provider: a mapping with priority 1 and `"fault": "CONNECTION_RESET_BY_PEER"` for
  `/payments` or `/payouts`; restore with `POST /__admin/mappings/reset`;
- provider request journal: `GET /__admin/requests`, read only; filter by `playerId`; do not clear
  it.

A test that changes the provider's behavior restores it afterwards and does not run in parallel
with other tests that use the provider: the change is global. Stopping containers is not allowed.

**Seed data.** The database contains 200 seed players. Their balance intentionally does not match
their journal: GEN-25 holds only for players created through the API.

## 11. Observation points

| Point | What it shows |
|---|---|
| `GET /api/players/{id}` | balance |
| `GET /api/sessions/{id}` | session status, bet, win, times |
| `GET /api/players/{id}/deposit-limits` | limits, counted amounts, pending changes |
| `GET /api/leaderboard`, WebSocket `/ws/leaderboard` | top of the current period |
| Kafka topics `player-events`, `game-events` | events |
| Database table `transactions` (`player_id`, `type`, `amount`, `created_at`) | journal |
| Provider request journal `GET /__admin/requests` | requests to the provider, `reference` |
