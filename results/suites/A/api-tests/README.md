# Arcade API regression tests

Black-box regression suite for the running Arcade stand. It is a standalone Maven project, not a
module of the root build, and it reaches the system over the network only.

```bash
cd api-tests
mvn -B test          # results: target/surefire-reports
```

Requires the stand to be up with the test clock enabled (`app.test-clock.enabled=true`). The
suite runs in about 40 s and never starts, stops or changes the stand's configuration.

- `REGRESSION_PLAN.md`: scope, risks, test cases (IDs used in the test display names)
- `TEST_REPORT.md`: results of the last run, defects and risks

## Layout

| Package / class | Purpose |
|---|---|
| `support.Http`, `support.Api` | HTTP client and domain calls (players, money, sessions, limits) |
| `support.TestClock` | service clock (`/api/test/clock`), leaderboard period computed from the spec |
| `support.Db` | read-only Postgres oracle (`arcade_reader`): ledger, idempotency keys, leaderboard SQL |
| `support.EventCapture` | Kafka consumer without a group (manual assignment, no commits) |
| `support.LeaderboardSocket` | WebSocket client of `/ws/leaderboard` |
| `support.ProviderJournal` | read-only WireMock request journal: what was sent to the payment provider |
| `*Test` | one class per area of the plan |

## Rules the tests follow on the shared stand

- Every test creates its own players (unique names). No test assumes an empty database or a
  particular clock value.
- The clock is only moved forward, and only as far as the rule under test needs. Tests run
  sequentially because the clock is global.
- No writes to the database or to the WireMock stub. All changes go through the public API.
