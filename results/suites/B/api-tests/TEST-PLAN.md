# Test Plan — Player Wallet money flows (regression suite)

| | |
|---|---|
| System under test | `player-service` (player wallet), as specified in `docs/spec/SPEC.md` |
| Test basis | `docs/spec/SPEC.md` — the **only** source of expected results (not the code, not the OpenAPI document, not current behavior) |
| Test level / type | API integration tests, black box, regression |
| Tooling | Java 17, JUnit 5 (+ JUnit Platform Suite), REST Assured, Kafka client, JDBC (read-only), `java.net.http` WebSocket, Surefire |
| Deliverables | this plan (with checklist), autotests in `api-tests/`, Surefire results in `api-tests/results/`, `api-tests/TEST-REPORT.md` |

## 1. Scope

**In scope** (SPEC §1) — every numbered requirement of these areas is covered by at least one check:

| Area | Requirements |
|---|---|
| General rules: money, requests and errors | GEN-01 … GEN-07 |
| Idempotency of money operations | GEN-08 … GEN-16 |
| Payment provider | GEN-17 … GEN-20 |
| Events | GEN-21 … GEN-24 |
| Invariants | GEN-25, GEN-26 |
| Deposit | DEP-01 … DEP-05 |
| Deposit limits | LIM-01 … LIM-12 |
| Withdrawal | WDR-01 … WDR-06 |
| Game session: bet, win, expiry | SES-01 … SES-13 |
| Leaderboard | LDB-01 … LDB-07 |

**Out of scope** (SPEC §1): registration rules and `GET /api/players`, player statistics, authentication and
authorization, age/KYC, wagering and AML, loss/wager/session-time limits, reconciliation with the provider,
provider availability in the health check. UI, unit and E2E tests are not part of this plan. Player creation
is used only as a precondition.

## 2. Approach

- **Black box over the network.** The suite talks to the running stand only: REST `:8080`, WebSocket
  `/ws/leaderboard`, Kafka `:9094` (consumer only), PostgreSQL `:5433` as `arcade_reader` (read only), WireMock
  admin API `:8089`. No service classes are reused.
- **Observation points (SPEC §11).** Every money check verifies all applicable observation points, not only the
  HTTP response: balance (`GET /api/players/{id}`), journal (`transactions`), events (`player-events`,
  `game-events`), provider request journal (`GET /__admin/requests`, filtered by `playerId`).
- **"Nothing changes" checks.** A refusal is verified by a snapshot taken before the request: balance, journal
  records, events of the player in both topics (after a 1 s quiet period), provider requests of the player.
- **Key not consumed (GEN-15).** After a refusal, the same key is re-sent with a valid request; it must be
  processed as a new request (success, no `Idempotent-Replayed` header).
- **Boundary values** for every range: lowest accepted, highest accepted, just below, just above. When the
  stub's fixed rules make the highest accepted value unreachable (deposit 50000.00 is declined by the stub,
  a 50000.00 withdrawal needs a balance), the check proves that the value **passes the range check** by
  observing the next check in the specified order (DEP-05, WDR-06).
- **Order of checks** (DEP-05, LIM-05, WDR-06, SES-06) is verified pairwise: a request that breaks two adjacent
  rules must report the earlier one.
- **Test clock.** Windows, cooling-off, session deadline, key lifetime and leaderboard periods are tested by
  advancing the service clock (`POST /api/test/clock/advance`). Targets are computed from the service clock
  read right before the operation, so the suite is independent of the initial clock. The clock only moves
  forward; real time keeps moving too, therefore exact-instant bounds are tested as "just inside / just
  outside" (see OQ-14).
- **Shared, long-lived stand.** Every test creates its own fresh players (unique usernames per run); no test
  assumes an empty database, an initial clock or an empty leaderboard. Leaderboard checks compute winning
  amounts from the current top and move away from a period boundary when one is near.
- **Provider stub.** Fixed rules: payments ≤ 10000 approved / > 10000 declined, payouts ≤ 5000 approved /
  > 5000 declined. Slow and unreachable provider are simulated through the admin API and always restored in
  `finally` (`fixedDelay: 0`, `POST /__admin/mappings/reset`). The request journal is never cleared.
- **Execution.** One JUnit Platform `@Suite` (`RegressionSuite`), run by `mvn -B test`; classes run
  sequentially in a fixed order; each autotest runs once; each autotest carries `@Tag("<requirement>")`
  and a display name starting with its checklist ID. Target duration < 5 minutes.

## 3. Flows under test, grouped by risk category

Risk categories:

- **FUN — functional risks**: wrong money result, wrong refusal, wrong state transition.
- **SEC — security risks**: balance manipulation, double credit, cross-player data leakage, injection,
  information disclosure, unbounded inputs.
- **REQ — requirements risks**: ambiguity, gaps, contradictions or untestable statements in the
  specification. Every REQ risk is recorded as an open question (§6) and carried into the test report.
- **INT — integration / reliability risks** (added): payment provider timeouts and outages, event publishing.
- **DAT — data integrity and concurrency risks** (added): balance ≠ journal, lost updates, races.

| Flow | Requirements | FUN | SEC | INT | DAT | REQ (open questions) |
|---|---|---|---|---|---|---|
| F-01 Deposit | DEP-01…05, GEN-15 | CL-001…011 | CL-132, CL-133 | CL-114, CL-116 | CL-125 | OQ-04, OQ-05, OQ-07 |
| F-02 Deposit limits | LIM-01…12, DEP-03 | CL-012…040 | CL-133 | — | — | OQ-11, OQ-12, OQ-13, OQ-14, OQ-15 |
| F-03 Withdrawal | WDR-01…06 | CL-041…052 | CL-132, CL-133 | CL-115, CL-117 | CL-126, CL-128 | OQ-05, OQ-16 |
| F-04 Game session (bet, win) | SES-01…09, SES-13 | CL-053…068 | CL-066, CL-130, CL-132, CL-133 | — | CL-127, CL-130 | OQ-17, OQ-18, OQ-19 |
| F-05 Session deadline and expiry | SES-10…12 | CL-069…073 | — | — | — | OQ-20 |
| F-06 Leaderboard | LDB-01…07 | CL-074…082 | — | — | — | OQ-21, OQ-22, OQ-23 |
| F-07 Idempotency of money operations | GEN-08…16 | CL-083…101 | CL-087, CL-131, CL-134 | CL-096, CL-097 | CL-129 | OQ-05, OQ-06, OQ-08, OQ-09 |
| F-08 Request validation and errors | GEN-01…07 | CL-102…111 | CL-135, CL-136 | — | — | OQ-01, OQ-02, OQ-03, OQ-04, OQ-26 |
| F-09 Payment provider integration | GEN-17…20 | — | CL-113 | CL-112…117 | — | OQ-10 |
| F-10 Events | GEN-21…24 | — | — | CL-118…122 | — | OQ-24 |
| F-11 Invariants and concurrency | GEN-25, GEN-26 | — | CL-129, CL-130 | — | CL-123…130 | OQ-25 |
| F-12 Security (cross-cutting) | GEN-03, GEN-04, GEN-07, GEN-09, GEN-10, DEP-02, WDR-02, SES-03, LIM-03 | — | CL-131…136 | — | — | OQ-17, OQ-26 |

## 4. Checklist

Priority: **P1** — money moves wrongly or twice / money-safety rule broken; **P2** — wrong refusal, wrong state or
contract; **P3** — format and ordering details. Risk column: primary category first.

### F-01 Deposit

| ID | Req | Check | Risk | Prio |
|---|---|---|---|---|
| CL-001 | DEP-01 | Approved deposit: `200` with the player, `balance` + amount; exactly one `DEPOSIT` journal record with the amount; one `DEPOSIT` event; exactly one `POST /payments` | FUN | P1 |
| CL-002 | DEP-02 | Lower bound `10.00` is accepted | FUN | P2 |
| CL-003 | DEP-02 | Upper bound `50000.00` passes the range check (reaches the provider: stub declines → `422 PAYMENT_DECLINED`, not out-of-range) | FUN | P2 |
| CL-004 | DEP-02 | `9.99` and `50000.01` → `422 DEPOSIT_AMOUNT_OUT_OF_RANGE`, `details.min="10.00"`, `details.max="50000.00"`; balance, journal, events unchanged; provider not called | FUN | P2 |
| CL-005 | DEP-02, GEN-15 | The key of an out-of-range deposit is not consumed | FUN | P2 |
| CL-006 | DEP-04 | Provider decline (`10000.01`) → `422 PAYMENT_DECLINED`, nothing changes, key not consumed; `10000.00` is approved | FUN | P1 |
| CL-007 | DEP-05 | Order: request format (`400`) before unknown player (`404`) | FUN | P3 |
| CL-008 | DEP-05 | Order: unknown player (`404`) before amount range | FUN | P3 |
| CL-009 | DEP-05 | Order: idempotency key (`422 IDEMPOTENCY_KEY_REUSED`) before amount range | FUN | P3 |
| CL-010 | DEP-05 | Order: amount range before deposit limits | FUN | P3 |
| CL-011 | DEP-05, DEP-03 | Order: deposit limits before provider — limit refusal for an amount the provider would decline; provider not called | FUN | P2 |

### F-02 Deposit limits

| ID | Req | Check | Risk | Prio |
|---|---|---|---|---|
| CL-012 | LIM-01 | `{period}` = `DAILY`, `Daily`, `yearly` → `400 VALIDATION_ERROR` for PUT and DELETE; nothing changes | FUN | P3 |
| CL-013 | LIM-02 | Player without limits → `200 []` | FUN | P3 |
| CL-014 | LIM-02 | Item format `{period, amount, used, remaining, pending}`; `period` upper case; `pending=null`; only periods with a limit are listed | FUN | P2 |
| CL-015 | LIM-02 | `used` = counted deposits, `remaining` = `amount − used` | FUN | P2 |
| CL-016 | LIM-02 | `remaining` is not below 0 after lowering a limit below `used` | FUN | P2 |
| CL-017 | LIM-03 | Bounds `10.00` and `1000000.00` accepted | FUN | P2 |
| CL-018 | LIM-03 | `9.99`, `1000000.01` → `422 DEPOSIT_LIMIT_OUT_OF_RANGE`, `details.min="10.00"`, `details.max="1000000.00"`; current and pending values unchanged | FUN | P2 |
| CL-019 | LIM-04 | Spec example 1: daily 100, weekly 300 → weekly 100 accepted, weekly 99 → `422 DEPOSIT_LIMIT_ORDER_VIOLATED` (`period`, `conflictingPeriod`, `conflictingAmount`); nothing changes | FUN | P2 |
| CL-020 | LIM-04 | Spec example 2: pending daily increase 250 conflicts with weekly 200 | FUN | P2 |
| CL-021 | LIM-04 | A shorter period above a longer period's value is refused (daily > weekly; weekly > monthly current while monthly increase is pending) | FUN | P2 |
| CL-022 | LIM-04 | Periods without a limit and pending removals take no part in the comparison | FUN | P2 |
| CL-023 | LIM-05 | Order: `400` before `404`; `404` before range; range before period order | FUN | P3 |
| CL-024 | LIM-06 | First limit and a lower amount take effect immediately (`pending=null`) | FUN | P1 |
| CL-025 | LIM-06 | A lower amount discards a pending increase | FUN | P2 |
| CL-026 | LIM-07 | Increase: `amount` unchanged, `pending.amount` new, `pending.effectiveAt` = request time + 24 h | FUN | P1 |
| CL-027 | LIM-07 | Before `effectiveAt` deposits are checked against the current amount; from `effectiveAt` the new amount applies and `pending=null` | FUN | P1 |
| CL-028 | LIM-08 | Repeating the same increase later changes nothing (`effectiveAt` unchanged) | FUN | P2 |
| CL-029 | LIM-08 | A different increase replaces the pending one and restarts the cooling-off | FUN | P2 |
| CL-030 | LIM-08 | Repeated removal keeps `effectiveAt`; an increase replaces a pending removal; a removal replaces a pending increase | FUN | P2 |
| CL-031 | LIM-09 | PUT with the current amount cancels a pending increase and a pending removal | FUN | P2 |
| CL-032 | LIM-10 | DELETE → `200`, `pending={amount:null, effectiveAt:+24h}`; the limit applies until `effectiveAt`; afterwards the period is not listed and does not limit deposits | FUN | P1 |
| CL-033 | LIM-10 | DELETE for a period without a limit → `404 NOT_FOUND` | FUN | P3 |
| CL-034 | LIM-11 | Daily window: a deposit is counted just before 24 h and not counted at/after 24 h | FUN | P1 |
| CL-035 | LIM-11 | Weekly (7 d) and monthly (30 d) windows | FUN | P1 |
| CL-036 | LIM-11 | Refused deposits (out of range, limit exceeded, provider declined) are never counted | FUN | P1 |
| CL-037 | LIM-11 | A withdrawal does not reduce the counted amount | SEC | P1 |
| CL-038 | LIM-12, DEP-03 | Deposit equal to the remaining amount accepted; +0.01 → `422 DEPOSIT_LIMIT_EXCEEDED` (`period`, `limit`, `remaining`); nothing changes; provider not called; key not consumed | FUN | P1 |
| CL-039 | LIM-12 | Most restrictive period wins (spec example: daily remaining 50, monthly remaining 0 → `MONTHLY`, `remaining=0`) | FUN | P2 |
| CL-040 | LIM-12 | Tie on remaining → shortest period (`DAILY` vs `WEEKLY`; `WEEKLY` vs `MONTHLY`) | FUN | P3 |

### F-03 Withdrawal

| ID | Req | Check | Risk | Prio |
|---|---|---|---|---|
| CL-041 | WDR-01 | Approved withdrawal: `200`, balance − amount; one `WITHDRAWAL` record; one `WITHDRAWAL` event; one `POST /payouts` | FUN | P1 |
| CL-042 | WDR-02 | `10.00` accepted; `50000.00` passes the range check (→ `INSUFFICIENT_FUNDS`) | FUN | P2 |
| CL-043 | WDR-02 | `9.99`, `50000.01` → `422 WITHDRAWAL_AMOUNT_OUT_OF_RANGE` with min/max; nothing changes; provider not called; key not consumed | FUN | P2 |
| CL-044 | WDR-02 | A balance below 10.00 cannot be withdrawn | FUN | P3 |
| CL-045 | WDR-03 | Active session → `409 ACTIVE_SESSION_EXISTS`; nothing changes; provider not called; key not consumed | SEC | P1 |
| CL-046 | WDR-03, SES-12 | `EXPIRED` and `FINISHED` sessions do not block a withdrawal | FUN | P2 |
| CL-047 | WDR-04 | Amount > balance → `422 INSUFFICIENT_FUNDS`; nothing changes; provider not called; key not consumed | SEC | P1 |
| CL-048 | WDR-04 | Withdrawing the whole balance is allowed (balance 0) | FUN | P2 |
| CL-049 | WDR-05 | Payout decline (`5000.01`) → `422 PAYOUT_DECLINED`; nothing changes; key not consumed; `5000.00` approved | FUN | P1 |
| CL-050 | WDR-06 | Order: `400` before `404`; `404` before range | FUN | P3 |
| CL-051 | WDR-06 | Order: idempotency key before range; range before active session | FUN | P3 |
| CL-052 | WDR-06 | Order: active session before balance; balance before provider (provider not called) | FUN | P2 |

### F-04 Game session: bet and win

| ID | Req | Check | Risk | Prio |
|---|---|---|---|---|
| CL-053 | SES-01, GEN-21 | Start: `201` `{id, playerId, game, betAmount, winAmount=0, status=ACTIVE, startedAt, finishedAt=null}`; balance − bet; one `BET` record; `SESSION_STARTED` with all fields | FUN | P1 |
| CL-054 | SES-02 | `slots`, `roulette`, `blackjack`, `poker`, `crash` accepted | FUN | P2 |
| CL-055 | SES-02 | `Slots`, `SLOTS`, `chess`, `""` → `400 VALIDATION_ERROR`, `game` in details | FUN | P3 |
| CL-056 | SES-03 | Bounds `1.00` and `500.00` accepted | FUN | P2 |
| CL-057 | SES-03 | `0.99`, `500.01` → `422 BET_AMOUNT_OUT_OF_RANGE` with min/max; nothing changes; key not consumed | FUN | P2 |
| CL-058 | SES-04 | Second start while `ACTIVE` → `409 ACTIVE_SESSION_EXISTS`; nothing changes; key not consumed | FUN | P1 |
| CL-059 | SES-05 | Bet > balance → `422 INSUFFICIENT_FUNDS`; nothing changes; key not consumed | SEC | P1 |
| CL-060 | SES-05 | A bet of the whole balance is allowed | FUN | P2 |
| CL-061 | SES-06 | Order: `400` (game) before `404`; `404` before range | FUN | P3 |
| CL-062 | SES-06 | Order: idempotency key before bet range; bet range before active session; active session before balance | FUN | P3 |
| CL-063 | SES-07, GEN-21 | Finish with win: `200`, `FINISHED`, `winAmount`, `finishedAt`; balance + win; one `WIN` record; `SESSION_FINISHED` with all fields | FUN | P1 |
| CL-064 | SES-07 | Negative win → `400 VALIDATION_ERROR`, `win` in details; session and balance unchanged | SEC | P1 |
| CL-065 | SES-08 | Win 0: `FINISHED`, `winAmount=0`, no `WIN` record, `SESSION_FINISHED` with `win=0` | FUN | P2 |
| CL-066 | SES-09 | Finishing a `FINISHED` session → `409 SESSION_NOT_ACTIVE`; win not credited twice; nothing changes | SEC | P1 |
| CL-067 | SES-13 | `GET /api/sessions/{id}` → `200` with the current session state | FUN | P3 |
| CL-068 | SES-01, SES-07 | `startedAt` / `finishedAt` are service-clock times | FUN | P2 |

### F-05 Session deadline and expiry

| ID | Req | Check | Risk | Prio |
|---|---|---|---|---|
| CL-069 | SES-10 | Finish with the service clock at `startedAt + 59 s` is accepted | FUN | P1 |
| CL-070 | SES-10 | Finish at `startedAt + 60 s` → `409 SESSION_NOT_ACTIVE`; win not credited | SEC | P1 |
| CL-071 | SES-11, GEN-21 | Expiry: `EXPIRED`, `finishedAt=null`, balance + bet, one `REFUND` record, `SESSION_EXPIRED` with `expiredAt ≥ startedAt+60 s` — all within 5 s real time | FUN | P1 |
| CL-072 | SES-09 | Finishing an `EXPIRED` session → `409 SESSION_NOT_ACTIVE`; nothing changes | SEC | P1 |
| CL-073 | SES-04, SES-12 | An `EXPIRED` session does not prevent a new session | FUN | P2 |

### F-06 Leaderboard

| ID | Req | Check | Risk | Prio |
|---|---|---|---|---|
| CL-074 | LDB-01, LDB-04 | `200`, ≤ 10 entries `{playerId, username, netResult}`, sorted by `netResult` descending, all `netResult > 0` | FUN | P2 |
| CL-075 | LDB-03, SES-12 | `netResult` = Σ(win − bet) of `FINISHED` sessions; `ACTIVE` and `EXPIRED` sessions are not counted | FUN | P1 |
| CL-076 | LDB-05 | A finished session is reflected in `GET /api/leaderboard` within 30 s | FUN | P2 |
| CL-077 | LDB-06 | A new entry in the top → `LEADERBOARD_UPDATED` within 30 s; its `top` equals `GET /api/leaderboard` | FUN | P2 |
| CL-078 | LDB-06 | A change of only `netResult` of a listed player → a message | FUN | P2 |
| CL-079 | LDB-06 | No message while the top does not change (incl. a session that does not change the top) | FUN | P3 |
| CL-080 | LDB-06 | No two consecutive messages carry the same top | FUN | P3 |
| CL-081 | LDB-02, LDB-07, LDB-04 | At a period boundary the top is recomputed for the new period within 30 s (previous period's results dropped, empty list) and the change is sent on the WebSocket | FUN | P2 |
| CL-082 | LDB-02, LDB-03, LDB-04 | A session finished after the boundary counts in the new period (by `finishedAt`); players with net 0 or negative are not listed | FUN | P2 |

### F-07 Idempotency of money operations

| ID | Req | Check | Risk | Prio |
|---|---|---|---|---|
| CL-083 | GEN-08 | Missing key → `400 IDEMPOTENCY_KEY_REQUIRED` (deposit, withdrawal, session start); nothing changes | FUN | P2 |
| CL-084 | GEN-08 | Empty key → `400 IDEMPOTENCY_KEY_REQUIRED` (3 operations) | FUN | P2 |
| CL-085 | GEN-09 | Keys of 1 and 64 characters accepted | FUN | P2 |
| CL-086 | GEN-09 | 65 characters → `400 IDEMPOTENCY_KEY_INVALID` (3 operations) | FUN | P2 |
| CL-087 | GEN-09 | Non-printable / non-ASCII key (TAB, `é`) → `400 IDEMPOTENCY_KEY_INVALID` | SEC | P2 |
| CL-088 | GEN-10, GEN-13 | One key space per player: a deposit key reused for a withdrawal or a session start, and a session key reused for a deposit → `422 IDEMPOTENCY_KEY_REUSED`; nothing changes | FUN | P1 |
| CL-089 | GEN-11 | Amounts compared by value: `50` then `50.00` with one key → replay | FUN | P2 |
| CL-090 | GEN-11, GEN-13 | Session start fingerprint: same game+bet → replay; other game or other bet → `422 IDEMPOTENCY_KEY_REUSED` | FUN | P2 |
| CL-091 | GEN-12 | Deposit replay: same status and body, `Idempotent-Replayed: true` (absent/not true on the original); balance, journal, events, provider calls unchanged | SEC | P1 |
| CL-092 | GEN-12 | Withdrawal replay (same checks) | SEC | P1 |
| CL-093 | GEN-12 | Session start replay (same checks; no second `BET`) | SEC | P1 |
| CL-094 | GEN-12 | Replay returns the original response although a check would refuse now: exhausted deposit limit, lower balance, session still active | FUN | P2 |
| CL-095 | GEN-13 | Consumed key, different amount (deposit and withdrawal) → `422 IDEMPOTENCY_KEY_REUSED`; nothing changes | FUN | P1 |
| CL-096 | GEN-14 | Deposit with the same key while the first is still processed → `409 IDEMPOTENCY_KEY_IN_PROGRESS`; performed once | INT | P1 |
| CL-097 | GEN-14 | Withdrawal with the same key while the first is still processed → `409 IDEMPOTENCY_KEY_IN_PROGRESS`; performed once | INT | P1 |
| CL-098 | GEN-15 | Deposit errors (400, range, limit, provider decline) do not consume the key | FUN | P2 |
| CL-099 | GEN-15 | Withdrawal errors (range, active session, insufficient funds, payout decline) do not consume the key | FUN | P2 |
| CL-100 | GEN-15 | Session start errors (bet range, insufficient funds, active session) do not consume the key | FUN | P2 |
| CL-101 | GEN-16 | Replay after the clock is advanced by 31 days returns the original response | FUN | P2 |

### F-08 Request validation and errors

| ID | Req | Check | Risk | Prio |
|---|---|---|---|---|
| CL-102 | GEN-02 | 2 decimals accepted: deposit, withdrawal, limit `10.01`, bet `1.01`, win `0.01` | FUN | P2 |
| CL-103 | GEN-02 | 3 decimals (`10.001`…) → `400 VALIDATION_ERROR` for all 5 amount fields; balance unchanged | FUN | P2 |
| CL-104 | GEN-02 | Decimals counted as written: `10.010` → `400 VALIDATION_ERROR` for all 5 amount fields | FUN | P3 |
| CL-105 | GEN-03 | Numbers sent as strings (`amount` ×3, `bet`, `win`, `playerId`) → `400 VALIDATION_ERROR`, field name in details | FUN | P2 |
| CL-106 | GEN-05 | Missing or `null` required fields (`amount` ×3, `playerId`, `game`, `bet`, `win`) → `400 VALIDATION_ERROR` | FUN | P2 |
| CL-107 | GEN-06 | Non-numeric `{id}` on every `/api/players/{id}…` and `/api/sessions/{id}…` endpoint → `400 BAD_REQUEST` | FUN | P3 |
| CL-108 | GEN-07 | Unknown player on every player endpoint and in session start → `404 NOT_FOUND` | FUN | P2 |
| CL-109 | GEN-07 | Unknown session (read, finish) → `404 NOT_FOUND` | FUN | P2 |
| CL-110 | GEN-04 | Error responses are JSON objects with string `code` and `message` (sample of every error status; also asserted in every negative check) | FUN | P3 |
| CL-111 | GEN-01 | USD only: no other currency in responses or provider requests | FUN | P3 |

### F-09 Payment provider integration

| ID | Req | Check | Risk | Prio |
|---|---|---|---|---|
| CL-112 | GEN-17 | Every provider request carries `reference`; a repeated request with the same key sends the same `reference` (deposit and withdrawal) | INT | P1 |
| CL-113 | GEN-17 | Different keys → different `reference`; the same key value of two players → different `reference` | SEC | P1 |
| CL-114 | GEN-18, GEN-19 | Deposit, provider slower than 5 s → `504 PAYMENT_TIMEOUT` within 6 s; balance, journal, events unchanged also after the late provider answer; key not consumed (retry = new attempt) | INT | P1 |
| CL-115 | GEN-18, GEN-19 | Withdrawal, same as CL-114 | INT | P1 |
| CL-116 | GEN-20 | Deposit, provider unreachable → `502 PAYMENT_UNAVAILABLE`; nothing changes; key not consumed | INT | P1 |
| CL-117 | GEN-20 | Withdrawal, provider unreachable → `502 PAYMENT_UNAVAILABLE`; nothing changes; key not consumed | INT | P1 |

### F-10 Events

| ID | Req | Check | Risk | Prio |
|---|---|---|---|---|
| CL-118 | GEN-21 | `REGISTERED` in `player-events` with `eventId`, `type`, `playerId`, `amount=null`, `at` | INT | P3 |
| CL-119 | GEN-21 | `DEPOSIT` / `WITHDRAWAL` with all required fields; `amount` = operation amount; `at` = service-clock time | INT | P2 |
| CL-120 | GEN-22 | Refused and failed requests publish no event (aggregate check; also part of every "nothing changes" check) | INT | P1 |
| CL-121 | GEN-23 | `amount`, `bet`, `win` are JSON numbers equal by value to the operation amounts | INT | P2 |
| CL-122 | GEN-24 | Event key = player id; events of one player arrive in operation order within each topic | INT | P2 |

### F-11 Invariants and concurrency

| ID | Req | Check | Risk | Prio |
|---|---|---|---|---|
| CL-123 | GEN-25 | Balance = journal after a mixed flow (deposit, win, loss, refund, withdrawal) | DAT | P1 |
| CL-124 | GEN-25 | Balance = journal for every player created by the run (final sweep) | DAT | P1 |
| CL-125 | GEN-26 | Concurrent deposits with different keys: no lost update | DAT | P1 |
| CL-126 | GEN-26 | Concurrent withdrawals exceeding the balance: serializable result, no overdraft | DAT | P1 |
| CL-127 | GEN-26, SES-04 | Concurrent session starts: exactly one `ACTIVE` session, one `BET` | DAT | P1 |
| CL-128 | GEN-26 | Concurrent withdrawal and session start competing for the same money: exactly one succeeds | DAT | P1 |
| CL-129 | GEN-26, GEN-14 | Concurrent requests with one key: performed exactly once | DAT | P1 |
| CL-130 | GEN-26, SES-09 | Concurrent finishes of one session: win credited exactly once | SEC | P1 |

### F-12 Security (cross-cutting)

| ID | Req | Check | Risk | Prio |
|---|---|---|---|---|
| CL-131 | GEN-10 | Same key value used by another player is an independent operation: no replay of the other player's response (deposit, withdrawal, session start) | SEC | P1 |
| CL-132 | DEP-02, WDR-02, SES-03 | Zero and negative amounts (deposit, withdrawal, bet) are refused with the range error; balance unchanged | SEC | P1 |
| CL-133 | DEP-02, WDR-02, SES-03, LIM-03 | Huge amounts (`100000000000000000000`) are refused with the range error, never `500` | SEC | P2 |
| CL-134 | GEN-09 | Injection-like printable keys (`' OR '1'='1`, `<script>`) are treated as plain keys (accepted, replayable) | SEC | P2 |
| CL-135 | GEN-04 | Error responses (including malformed JSON) do not disclose internals (stack traces, exception or class names) | SEC | P3 |
| CL-136 | GEN-03, GEN-07 | A non-integer `playerId` (`N.5`) is refused (`400`/`404`), never truncated to player `N`; nothing changes | SEC | P1 |

## 5. Requirements coverage

Every requirement in scope has at least one check (100 %). The full matrix "requirement – checklist item –
autotest" is in `TEST-REPORT.md`.

| Req | Checks | Req | Checks |
|---|---|---|---|
| GEN-01 | CL-111 | LIM-01 | CL-012 |
| GEN-02 | CL-102, 103, 104 | LIM-02 | CL-013, 014, 015, 016 |
| GEN-03 | CL-105, 136 | LIM-03 | CL-017, 018, 133 |
| GEN-04 | CL-110, 135 (+ every negative check) | LIM-04 | CL-019, 020, 021, 022 |
| GEN-05 | CL-106 | LIM-05 | CL-023 |
| GEN-06 | CL-107 | LIM-06 | CL-024, 025 |
| GEN-07 | CL-108, 109, 136 | LIM-07 | CL-026, 027 |
| GEN-08 | CL-083, 084 | LIM-08 | CL-028, 029, 030 |
| GEN-09 | CL-085, 086, 087, 134 | LIM-09 | CL-031 |
| GEN-10 | CL-088, 131 | LIM-10 | CL-032, 033 |
| GEN-11 | CL-089, 090 | LIM-11 | CL-034, 035, 036, 037 |
| GEN-12 | CL-091, 092, 093, 094 | LIM-12 | CL-038, 039, 040 |
| GEN-13 | CL-088, 090, 095 | WDR-01 | CL-041 |
| GEN-14 | CL-096, 097, 129 | WDR-02 | CL-042, 043, 044, 132, 133 |
| GEN-15 | CL-005, 098, 099, 100 | WDR-03 | CL-045, 046 |
| GEN-16 | CL-101 | WDR-04 | CL-047, 048 |
| GEN-17 | CL-112, 113 | WDR-05 | CL-049 |
| GEN-18 | CL-114, 115 | WDR-06 | CL-050, 051, 052 |
| GEN-19 | CL-114, 115 | SES-01 | CL-053, 068 |
| GEN-20 | CL-116, 117 | SES-02 | CL-054, 055 |
| GEN-21 | CL-118, 119, 053, 063, 071 | SES-03 | CL-056, 057, 132, 133 |
| GEN-22 | CL-120 (+ every "nothing changes" check) | SES-04 | CL-058, 073, 127 |
| GEN-23 | CL-121 | SES-05 | CL-059, 060 |
| GEN-24 | CL-122 | SES-06 | CL-061, 062 |
| GEN-25 | CL-123, 124 | SES-07 | CL-063, 064, 068 |
| GEN-26 | CL-125 … 130 | SES-08 | CL-065 |
| DEP-01 | CL-001 | SES-09 | CL-066, 072, 130 |
| DEP-02 | CL-002, 003, 004, 005, 132, 133 | SES-10 | CL-069, 070 |
| DEP-03 | CL-011, 038 | SES-11 | CL-071 |
| DEP-04 | CL-006 | SES-12 | CL-046, 073, 075 |
| DEP-05 | CL-007 … 011 | SES-13 | CL-067 |
| LDB-01 | CL-074 | LDB-05 | CL-076 |
| LDB-02 | CL-081, 082 | LDB-06 | CL-077, 078, 079, 080, 081 |
| LDB-03 | CL-075, 082 | LDB-07 | CL-081 |
| LDB-04 | CL-074, 081, 082 | | |

Known partial coverage (environment constraints, see OQ-14, OQ-22): the exact instant "one window length" in
LIM-11 and the "after the service starts" clause of LDB-06 (a restart is forbidden) cannot be hit exactly; they
are covered by the nearest observable behavior.

## 6. Requirements risks — open questions

| ID | Req | Open question |
|---|---|---|
| OQ-01 | GEN-01 | "No other currency exists" is not testable as written: no request or response carries a currency. Should a request with a `currency` field (e.g. `"EUR"`) be refused or ignored? |
| OQ-02 | GEN-02 | How are decimal places counted "as written" for exponent notation (`1.001E1`, `1E+1`, `1e-2`)? Are exponents allowed in requests at all (events allow `1E+2`, GEN-23)? |
| OQ-03 | GEN-03, GEN-05 | The format of `details` for `VALIDATION_ERROR` is not specified ("the field name in `details`" — as a key or as a value?). Is `details` required for GEN-05? What `code` does a malformed JSON body get? |
| OQ-04 | DEP-05, WDR-06, SES-06, GEN-08/09 | Are `400 IDEMPOTENCY_KEY_REQUIRED/INVALID` part of step 1 "request format (400)" or of step 3 "idempotency key"? Example: unknown player + missing key → `400` or `404`? Which 400 wins when both the key and the body are invalid? |
| OQ-05 | GEN-12 | Must the original (non-replayed) response carry `Idempotent-Replayed: false`, or no header? Is "the same body" byte-identical or semantically equal (number scale `100` vs `100.00`)? |
| OQ-06 | GEN-09 | Is the space character (0x20) "printable"? HTTP strips leading/trailing spaces, so `" a"` and `"a"` reach the service as the same key. DEL (0x7F) is refused by the web container with a non-JSON 400 before reaching the service — is that acceptable under GEN-04? |
| OQ-07 | GEN-13, GEN-15 | After a refusal the key is free; GEN-17 says a repeated request with the same key sends the same `reference`. If the retry carries a *different amount*, must the `reference` be the same? A provider that de-duplicates by `reference` would then reject or mis-apply the second amount. |
| OQ-08 | GEN-14 | "Still being processed" has no observable definition for session start (no external call); the in-progress rule can only be verified for deposit/withdrawal. What is the response for a same-key request with a *different* fingerprint while the first is in progress (`409` assumed)? |
| OQ-09 | GEN-16 | Unlimited key lifetime means unbounded storage growth; is a retention period acceptable to the business? |
| OQ-10 | GEN-18, GEN-19 | After `504 PAYMENT_TIMEOUT` the provider may still charge/pay out (late success). The spec keeps the wallet unchanged and defers to reconciliation (out of scope) — money can be taken from a card without being credited until reconciliation. Is this accepted? How should a non-2xx provider answer other than a decline (e.g. `500`) be classified — decline (`422`) or unavailable (`502`)? |
| OQ-11 | LIM-04 | "Periods without a limit and **pending removals** take no part": is only the pending (null) value ignored, or is the whole period with a pending removal ignored (its current value still applies until `effectiveAt`)? The second reading allows daily > weekly during the cooling-off. |
| OQ-12 | LIM-02, LIM-12 | Types of `details.limit` / `details.remaining` / `conflictingAmount` are not specified (DEP-02 uses strings like `"10.00"`). |
| OQ-13 | LIM-07, LIM-10 | `effectiveAt = request time + 24 h` — required precision/tolerance? Is the pending change applied exactly at `effectiveAt` or by a background job with a lag (how long)? |
| OQ-14 | LIM-11 | "A deposit made exactly one window length before the check is outside" cannot be verified black-box: the service clock keeps moving in real time and its resolution is microseconds. Only "just inside / just after" is testable. |
| OQ-15 | LIM-06/07 | What does a PUT equal to the current amount return when nothing is pending (no-op `200` assumed)? |
| OQ-16 | WDR-02 | A balance below 10.00 can never be withdrawn — is stranding the remainder an accepted business rule? |
| OQ-17 | SES-07 | `win` has no upper bound and is reported by the client; any client can credit an arbitrary amount (fraud risk). What is the maximum win (e.g. a multiple of the bet)? What happens above the storage precision? |
| OQ-18 | SES-07, SES-09 | Finish has no idempotency key: a client that retries a finish after a lost response gets `409 SESSION_NOT_ACTIVE` and cannot tell whether its own finish succeeded. Should finish be idempotent? |
| OQ-19 | SES-06, SES-07, GEN-07 | Order of checks for finish is not specified (unknown session + negative win: `400` or `404`?). |
| OQ-20 | SES-11 | `expiredAt` has a lower bound only; upper bound (e.g. ≤ `startedAt+60s` + poll interval) not specified. |
| OQ-21 | LDB-01 | Order of entries with equal `netResult` is not specified. |
| OQ-22 | LDB-06 | "After the service starts, the top at that moment counts as the last top sent" is not testable without a restart (forbidden). A newly connected client receives nothing until the top changes — is that intended (clients must `GET` first)? |
| OQ-23 | LDB-05, LDB-07 | The 30 s freshness applies to `GET`; the service caches the leaderboard. Is a stale `GET` that disagrees with the last WebSocket message acceptable inside the 30 s (LDB-06 says the message equals `GET` "at that moment")? |
| OQ-24 | GEN-21, GEN-24 | Formats of `eventId` (uniqueness?) and `at` (ISO-8601? precision?) are not specified; there is no latency bound for `player-events` and for `SESSION_STARTED/FINISHED` (only SES-11 has 5 s). |
| OQ-25 | GEN-25 | How to identify seed players (ids 1–200? username prefix)? The invariant is checked only for players created by this suite. |
| OQ-26 | GEN-03, GEN-07 | `playerId` must be a JSON number, but integrality is not stated: should `1.5` be `400 VALIDATION_ERROR` or `404 NOT_FOUND`? (Either is accepted by CL-136; silently using player 1 is not.) |

## 7. Entry and exit criteria

**Entry:** the stand answers on `:8080` (`GET /api/test/clock` = `200`), the stub admin API on `:8089`, Kafka
on `:9094` and PostgreSQL on `:5433` are reachable; the test clock is enabled. If not — testing stops, nothing
is started by the tester.

**Exit:** 100 % of the checklist checks are executed (each by its autotest, in one suite run), regardless of
the results; the Surefire report is saved in `api-tests/results/`; every failed check is analysed (test defect
vs. system defect) and every system defect is in the report's bug table.

**Suspension:** the stand becomes unreachable; the stub cannot be restored.

## 8. Test data and environment rules

- Fresh players per test: `qa<run>_<n>`, `<username>@example.com`, country `DE`; funded through approved
  deposits (≤ 10000 each).
- Idempotency keys: random UUID-based per request unless a check needs a specific key.
- Unknown ids: `999999999` (player and session).
- No writes to the database; no stop/restart of containers; no changes outside `api-tests/`; the provider's
  request journal is never cleared; provider behavior changes are restored in `finally`.
