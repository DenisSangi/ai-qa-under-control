# Arcade — API regression test report

| | |
|---|---|
| Date | 2026-10-18 |
| System under test | The running stand: player-service `:8080`, analytics-service (through gRPC and Kafka), Kafka `:9094`, WireMock `:8089`, Postgres `:5433` |
| Build | The running jars were started from a separate directory (`~/services/arcade-wallet`), not from this repository. The tests are black-box. The source in this repository was used only as the specification. |
| Configuration observed | test clock enabled, cooling-off 24h, session timeout 60s, leaderboard refresh 5s |
| Suite | `api-tests/`: standalone Maven project, Java 17, JUnit 5; plan in [`REGRESSION_PLAN.md`](REGRESSION_PLAN.md) |
| Command | `mvn -B test` in `api-tests/` |

## 1. Summary

| Tests | Passed | Failed | Errors | Skipped | Duration |
|---:|---:|---:|---:|---:|---:|
| 152 | 143 | 9 | 0 | 0 | about 36 s |

All 9 failures come from **one product defect** (DEF-01): the API answers client mistakes with
`500 INTERNAL_ERROR`. Every business rule checked behaves as specified:
- money movement and the ledger;
- idempotency;
- deposit limits and cooling-off;
- sessions and expiry;
- the leaderboard and its WebSocket push;
- Kafka events;
- analytics stats over gRPC;
- concurrency.

The suite ran four times in a row on the shared stand, with data from earlier runs present and the
clock already moved forward. Results were identical each time and no test was flaky.

**Release recommendation:** no blocker in money handling or the regulatory rules. DEF-01 should be
fixed. The risks in section 4, especially R-1 and R-2, need a product decision before real money is involved.

## 2. Results by area

| Area | Plan IDs | Tests | Result |
|---|---|---:|---|
| Players | PL-01…05 | 14 | pass |
| Deposits | DEP-01…05 | 19 | pass |
| Withdrawals | WD-01…04 | 12 | pass |
| Idempotency-Key | IDEM-01…07 | 17 | pass |
| Deposit limits | LIM-01…12 | 27 | pass |
| Game sessions | SES-01…06 | 24 | pass |
| Session expiry and refund | EXP-01…03 | 3 | pass |
| Leaderboard (REST, periods, WebSocket) | LB-01…06 | 6 | pass |
| Stats (Kafka → analytics → gRPC) | ST-01…03 | 4 | pass |
| Kafka event contract | EV-01…03 | 3 | pass |
| Concurrency | CON-01…07 | 7 | pass |
| Ledger consistency | LED-01 | 1 | pass |
| Error contract | ERR-01…03 | 15 | **9 failed** (DEF-01) |

How the results were verified, beyond status codes:
- **Ledger:** rows read from Postgres as `arcade_reader`. LED-01 checks that the balance equals
  deposits + wins + refunds − withdrawals − bets, and that every row carries a service-clock timestamp.
- **Payment provider:** the WireMock request journal, read-only. It shows that a replay never charges
  twice, and that no provider call is made for refused requests or exceeded limits. A retry of a
  released key reuses the same reference; different players and different keys get different references.
- **Leaderboard:** recomputed independently in SQL from the specification and compared with
  `GET /api/leaderboard`. It was tested across three consecutive period boundaries, including a
  session that started before a boundary and finished after it (it counts in the new period).
- **Time-based rules:** checked at ±1 minute of their boundary with the test clock: cooling-off, the
  1/7/30-day rolling windows, and the 60s session timeout. Before the run, the clock is moved at
  least 1h ahead of real time, so a timestamp taken from the system clock would be caught.

## 3. Defects

### DEF-01: client mistakes are answered with `500 INTERNAL_ERROR`

| | |
|---|---|
| Severity | Medium: no money impact; wrong HTTP contract, and operational noise |
| Priority | High: cheap to fix, and it affects every endpoint |
| Failing tests | `ErrorContractTest.clientMistakeIsNot500` [1]–[9] |

| # | Request | Expected | Actual |
|---|---|---|---|
| 1 | `GET /api/players/abc` | 400 | 500 `INTERNAL_ERROR` |
| 2 | `GET /api/sessions/abc` | 400 | 500 `INTERNAL_ERROR` |
| 3 | `POST /api/players/abc/deposit` (valid body) | 400 | 500 `INTERNAL_ERROR` |
| 4 | `GET /api/no-such-endpoint` | 404 | 500 `INTERNAL_ERROR` |
| 5 | `DELETE /api/leaderboard` | 405 | 500 `INTERNAL_ERROR` |
| 6 | `POST /api/players` with `Content-Type: text/plain` | 415 | 500 `INTERNAL_ERROR` |
| 7 | `GET /api/players?page=-1` | 400 | 500 `INTERNAL_ERROR` |
| 8 | `GET /api/players?size=0` | 400 | 500 `INTERNAL_ERROR` |
| 9 | `GET /api/players?page=abc` | 400 | 500 `INTERNAL_ERROR` |

Reproduce: `curl -i http://localhost:8080/api/players/abc` returns
`HTTP/1.1 500 {"code":"INTERNAL_ERROR","message":"unexpected server error",...}`.

**Why it matters:**
- A 5xx tells clients and gateways that the request may succeed on retry. Retry policies will
  resend requests that can never succeed, including requests to the money endpoints (case 3).
- Every such request is logged at ERROR level with a stack trace, so a typo or a scanner looks
  like an outage to monitoring.
- `GlobalExceptionHandler` promises "one stable error contract". The shape is kept, but the
  status is wrong.

**Likely cause** (from the specification source): the catch-all
`@ExceptionHandler(Exception.class)` in `GlobalExceptionHandler` also catches Spring MVC's own
exceptions, which are normally mapped to 4xx:
- `MethodArgumentTypeMismatchException`
- `NoResourceFoundException`
- `HttpRequestMethodNotSupportedException`
- `HttpMediaTypeNotSupportedException`
- the `IllegalArgumentException` that `PageRequest.of` throws for a negative page or a page size
  below 1, which reaches the same catch-all.

**Suggested fix:** let the advice extend `ResponseEntityExceptionHandler` (or map those exceptions
explicitly) and keep the `ApiError` body. Validate `page >= 0` and `1 <= size <= max` in
`PlayerController.list`.

## 4. Risks and observations (not failing tests: the specification does not cover them)

| ID | Observation | Risk |
|---|---|---|
| R-1 | E-mail uniqueness is case-sensitive: `obs_x@ex.com` and `OBS_X@EX.COM` are both accepted as different players. | **High (responsible gambling).** Deposit limits are per account, so a player can bypass their own limit or cooling-off with a second account on the same mailbox. |
| R-2 | The win amount comes from the client (`POST /api/sessions/{id}/finish {"win": ...}`) with no server-side check (up to 10 integer digits). Together with no authentication on any endpoint, any caller can credit any winnings to any player. A win that overflows the balance column is answered with `409 DATA_CONFLICT`. | **High (security/fraud)** in any real-money deployment; acceptable only for a demo stand. |
| R-3 | analytics-service has no deduplication by `eventId` (documented in `StatsService`). A Kafka redelivery double-counts `totalSessions`, `totalBet`, `totalWin` and skews RTP. | Medium: reporting integrity. Not tested: it needs synthetic events injected into the shared topic. |
| R-4 | `country` accepts any 2 characters (`"1!"` is stored). The list filter `?country=mt` finds nothing, although creation upper-cases `mt` to `MT`. | Low: data quality and usability. |

## 5. Not covered, and why

- **Payment provider outage or timeout** (`PAYMENT_UNAVAILABLE`, 502) and the key release after it.
  Simulating them needs a change to the stub's mappings, which is not allowed. The release path is
  covered for declines and range errors.
- **analytics-service outage** (`ANALYTICS_UNAVAILABLE`) and **Redis outage** fallback: both need a
  service stopped, which is not allowed.
- **Performance and load, UI, security testing.**

## 6. Test-side issue found and fixed during triage

The first run had a 10th failure: `IDEM-04 non-ASCII key` was accepted with 200. The database showed
the stored key as `key-??` (hex `3f3f`): the JDK HTTP client had replaced `é`/`ü` with `?` before
sending, so the service had correctly accepted a valid ASCII key. The test now writes the header bytes
raw over a socket (Latin-1 `E9`, and the UTF-8 bytes `C3 A9`). The service refuses both with
`400 IDEMPOTENCY_KEY_INVALID`, as specified. **This was a test defect, not a product defect.**

## 7. Notes for whoever runs the suite

- Tests run sequentially on purpose. The service clock is global, so a clock jump in one test would
  break a session another test has open.
- Each run moves the stand's clock forward by about 70 days. The 7- and 30-day limit windows take
  about 40 days; the four leaderboard period boundaries take about 30. The clock is only moved forward,
  every expectation is relative to the service clock, and every test creates its own players, so
  reruns are independent of earlier data.
- Endpoints can be overridden with `-Dapi.baseUrl`, `-Dws.url`, `-Dkafka.bootstrap`, `-Dstub.baseUrl`,
  `-Ddb.url`, `-Ddb.user` and `-Ddb.password`.
