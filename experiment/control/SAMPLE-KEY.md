# Calibration sample: answer key

**Never copy this file, or anything else under `control/`, into a checking agent's folder.**

The sample is in `control/sample/`. The checks it calibrates (CHK-1, CHK-3A, CHK-5) and the calibration
procedure are in `control/CHECKS.md` §3–§4.

## 1. What the sample is

- A complete Maven project in the same shape as `api-tests/`. `pom.xml`, `.gitignore`, `RegressionSuite.java` and the
  11 support classes it needs are byte-identical copies of `api-tests/`. `Leaderboard` and `LeaderboardSocket` are left
  out because no sample test uses them. `mvn -B -q test-compile` succeeds (checked offline on 2026-10-19; `target/`
  was deleted afterwards).
- 21 test methods in 8 classes, 570 lines of test code. The suite has 136 tests and about 3,400 lines.
  - **Good (8):** byte-identical copies of suite tests. Each was checked against `docs/spec/SPEC.md` (§6).
  - **Bad (13):** copies of suite tests, also checked against the spec, with exactly one defect each. The original of
    a bad test is never in the sample, so an agent cannot find the defect by comparing two tests. The diffs against
    the originals were checked mechanically: each bad test differs only at the lines given in §4.
- Bad and good tests are mixed in each class. Within a class, tests are in checklist-ID order, as in the suite.
  Names, tags, comments and imports follow the suite's conventions. Nothing marks a test as bad. Three bad tests carry
  their defect in a tag or in the display name, because that is the defect itself (CL-033 and CL-089: a tag; CL-093:
  the claim).
- Calibration batches (one fresh agent session per batch):
  - **S1:** `DepositTest`, `GameSessionTest`, `SessionTimingTest`, `DepositLimitsTest` (12 tests).
  - **S2:** `IdempotencyKeyTest`, `EventsTest`, `ConcurrencyTest`, `PaymentProviderTest` (9 tests).

## 2. Scoring

- Each check returns one verdict per test method (`Class#method`). A parameterized test counts as one test.
- An expected FLAG must come back as FLAG, and an expected PASS as PASS. UNCLEAR, a missing verdict or a duplicate
  verdict counts as a miss.
- A check passes calibration only if it has 0 misses in every repetition (CHECKS.md D11 recommends 2 repetitions).
- The `kind` of a FLAG is compared with the expected kind and reported, but it does not decide pass or fail. Where a
  second kind is also correct, it is given in brackets.

## 3. Answer table (machine-readable)

```csv
test,status,origin,defect,CHK-1,CHK-3A,CHK-5
DepositTest#outOfRangeRefused,bad,CL-004 DepositTest#outOfRangeRefused,E4,FLAG,PASS,PASS
DepositTest#outOfRangeKeepsKeyFree,good,CL-005 DepositTest#outOfRangeKeepsKeyFree,-,PASS,PASS,PASS
DepositTest#unknownPlayerBeforeRange,bad,CL-008 DepositTest#unknownPlayerBeforeRange,L4,PASS,FLAG,PASS
GameSessionTest#invalidGame,bad,CL-055 GameSessionTest#invalidGame,E5,FLAG,PASS,PASS
GameSessionTest#insufficientFunds,bad,CL-059 GameSessionTest#insufficientFunds,E3,FLAG,PASS,PASS
GameSessionTest#formatAndPlayerFirst,good,CL-061 GameSessionTest#formatAndPlayerFirst,-,PASS,PASS,PASS
GameSessionTest#finishWithWin,good,CL-063 GameSessionTest#finishWithWin,-,PASS,PASS,PASS
SessionTimingTest#newSessionAfterExpiry,bad,CL-073 SessionTimingTest#newSessionAfterExpiry,E1,FLAG,PASS,PASS
DepositLimitsTest#emptyList,bad,CL-013 DepositLimitsTest#emptyList,H3,PASS,PASS,FLAG
DepositLimitsTest#remainingNotNegative,bad,CL-016 DepositLimitsTest#remainingNotNegative,E2,FLAG,PASS,PASS
DepositLimitsTest#orderExampleOne,good,CL-019 DepositLimitsTest#orderExampleOne,-,PASS,PASS,PASS
DepositLimitsTest#deleteWithoutLimit,bad,CL-033 DepositLimitsTest#deleteWithoutLimit,L1,PASS,FLAG,PASS
IdempotencyKeyTest#keyLengthBoundsAccepted,good,CL-085 IdempotencyKeyTest#keyLengthBoundsAccepted,-,PASS,PASS,PASS
IdempotencyKeyTest#amountComparedByValue,bad,CL-089 IdempotencyKeyTest#amountComparedByValue,L2,PASS,FLAG,PASS
IdempotencyKeyTest#sameKeyWhileActive,bad,CL-093 IdempotencyKeyTest#sessionStartReplay,L3,PASS,FLAG,PASS
EventsTest#registeredEvent,bad,CL-118 EventsTest#registeredEvent,H1,PASS,PASS,FLAG
EventsTest#noEventsForRefusals,good,CL-120 EventsTest#noEventsForRefusals,-,PASS,PASS,PASS
ConcurrencyTest#concurrentSessionStarts,good,CL-127 ConcurrencyTest#concurrentSessionStarts,-,PASS,PASS,PASS
ConcurrencyTest#concurrentFinishes,bad,CL-130 ConcurrencyTest#concurrentFinishes,H4,PASS,PASS,FLAG
PaymentProviderTest#sameKeySameReference,good,CL-112 PaymentProviderTest#sameKeySameReference,-,PASS,PASS,PASS
PaymentProviderTest#depositUnreachable,bad,CL-116 PaymentProviderTest#depositUnreachable,H2,PASS,PASS,FLAG
```

Each check therefore has:

| Check | Must FLAG | Must PASS (good tests) | Must PASS (bad tests of other checks) |
|---|---|---|---|
| CHK-1 | 5 (E1–E5) | 8 | 8 |
| CHK-3A | 4 (L1–L4) | 8 | 9 |
| CHK-5 | 4 (H1–H4) | 8 | 9 |

## 4. Bad tests: the defect and the expected verdicts

Paths are relative to `control/sample/src/test/java/com/arcade/qa/tests/`.

### B1 `DepositTest#outOfRangeRefused`: CL-004, DEP-02. Defect E4 (CHK-1)

- **Change** (line 38): `before.assertUnchanged()` became `before.assertUnchangedExceptProviderCalls()`. The
  display name still claims "provider not called", and DEP-02 says "The provider is not called". The test no longer
  compares the player's `POST /payments` counts.
- **CHK-1 FLAG E4.** Witness: for 9.99 the service sends `POST /payments`, then answers 422
  `DEPOSIT_AMOUNT_OUT_OF_RANGE` with the correct `min`/`max` and changes nothing else. Every assertion passes.
- **CHK-3A PASS.** Every expectation (422, the code, `min` = "10.00", `max` = "50000.00", balance, journal and events
  unchanged) is stated in DEP-02.
- **CHK-5 PASS.** The refusal is synchronous, and the Snapshot waits its 1 s quiet period before the negative event check.

### B2 `DepositTest#unknownPlayerBeforeRange`: CL-008, DEP-05. Defect L4 (CHK-3A)

- **Change** (lines 60–61): the error body of the second request is kept, and its `message` must equal
  `"player 999999999 not found"`. GEN-04 requires a `message` but does not specify its text.
- **CHK-1 PASS.** Both claimed order pairs are asserted with an exact status and code (`404 NOT_FOUND` for a valid
  amount and for an out-of-range amount).
- **CHK-3A FLAG L4.** A service that follows the spec and answers `404 NOT_FOUND` with any other message text, for
  example "Player not found", fails the test. (It passes on the current service, whose message is exactly this text.)
- **CHK-5 PASS.** `Config.UNKNOWN_ID` is an id no player has, and the requests are synchronous.

### B3 `GameSessionTest#invalidGame`: CL-055, SES-02. Defect E5 (CHK-1)

- **Change** (lines 48–49): `assumeTrue(r.statusCode() == 400, …)` runs before the assertions. Any status other than
  400 aborts the test (reported as skipped) instead of failing it.
- **CHK-1 FLAG E5.** Witness: the service accepts game `"Slots"` (201, a session is started and 5 is charged). The
  assumption fails, the test is skipped, and nothing fails. The same happens for a refusal with the wrong status
  (for example 422).
- **CHK-3A PASS.** The expectations (400 `VALIDATION_ERROR`, `game` in `details`, balance unchanged) are SES-02's.
  The assumption itself expects nothing the spec contradicts.
- **CHK-5 PASS.** The outcome is deterministic, and every step is a synchronous request.

### B4 `GameSessionTest#insufficientFunds`: CL-059, SES-05. Defect E3 (CHK-1)

- **Change** (line 62): `assertError(…, 422, "INSUFFICIENT_FUNDS")` became `assertStatus(…, 422)`.
- **CHK-1 FLAG E3 [E4 also correct].** Witness: the service refuses the bet of 20.01 with 422
  `BET_AMOUNT_OUT_OF_RANGE`, or with any other 422 code or a non-JSON body, and changes nothing. The key stays free,
  so every assertion passes. The claim names "422 INSUFFICIENT_FUNDS".
- **CHK-3A PASS.** SES-05 prescribes 422. Nothing in the test contradicts the spec.
- **CHK-5 PASS.**

### B5 `SessionTimingTest#newSessionAfterExpiry`: CL-073, SES-04 and SES-12. Defect E1 (CHK-1)

- **Change** (lines 48–49): the response of the new session start is ignored. The only assertion after it checks
  that the first session is still `EXPIRED`, which is the precondition the scenario already established.
- **CHK-1 FLAG E1.** Witness: the service counts the `EXPIRED` session as active and refuses the new start with
  `409 ACTIVE_SESSION_EXISTS`. The first session is still `EXPIRED`, so the test passes.
- **CHK-3A PASS.** The test sends a session start while the player has an `EXPIRED` session, which is exactly the
  situation SES-04 and SES-12 govern. The asserted status `EXPIRED` agrees with SES-11 and SES-12.
- **CHK-5 PASS.** `expire()` polls the status for up to 15 s (more than the 5 s of SES-11) and the test waits for
  `EXPIRED`. A bet of 10 fits whether or not the refund has arrived yet (balance 90 or 100). Once `EXPIRED`, the
  status cannot change.

### B6 `DepositLimitsTest#emptyList`: CL-013, LIM-02. Defect H3 (CHK-5)

- **Change** (line 29): the player is created with the fixed username `qa_limits_empty` through `createPlayerRaw`,
  and its id is read from the response without a check.
- **CHK-1 PASS.** The claim "200 and an empty list" is asserted exactly.
- **CHK-3A PASS.** LIM-02: "A player without limits gets an empty list." On a fresh system, in one run, the scenario
  is the intended one.
- **CHK-5 FLAG H3.** The first run on a database creates the player. Every later run on the same, long-lived
  database sends a duplicate username. Registration of a duplicate is refused (usernames must be unique, SPEC §1;
  the service answers 409). The response has no `id`, so `p` = 0, `GET /api/players/0/deposit-limits` returns 404, and
  `assertStatus(r, 200)` fails.

### B7 `DepositLimitsTest#remainingNotNegative`: CL-016, LIM-02. Defect E2 (CHK-1)

- **Change** (lines 47–48): the expected `remaining` is no longer `"0"`. It is read from a second
  `GET /api/players/{id}/deposit-limits`.
- **CHK-1 FLAG E2.** Witness: the service reports `remaining` = amount − used = −30.00, with no floor at 0, on every
  read. Both reads agree, so the test passes. The claim point "remaining is not below 0" has no independent expected
  value. `amount` (50) and `used` (80) are still checked, but `remaining` is not.
- **CHK-3A PASS.** Expecting two reads of an unchanged state to agree is consistent with the spec. The other
  expectations are LIM-06's (lower amount applies at once) and LIM-02's (`used`).
- **CHK-5 PASS.** No deposit can leave the 24 h window between two reads a few milliseconds apart.

### B8 `DepositLimitsTest#deleteWithoutLimit`: CL-033. Defect L1 (CHK-3A)

- **Change** (line 68): `@Tag("LIM-10")` became `@Tag("LIM-13")`. The spec defines LIM-01 to LIM-12.
- **CHK-1 PASS.** Both claimed 404 refusals are asserted with status and code, and the other period's limit is
  checked as unchanged.
- **CHK-3A FLAG L1 [L2 also correct].** LIM-13 does not exist, and LIM-10, which governs "DELETE for a period without
  a limit → 404", is not tagged.
- **CHK-5 PASS.**
- The script check CHK-3S also flags this test (S1).

### B9 `IdempotencyKeyTest#amountComparedByValue`: CL-089. Defect L2 (CHK-3A)

- **Change** (line 44): `@Tag("GEN-11")` became `@Tag("GEN-14")`.
- **CHK-1 PASS.** The claim "50 then 50.00 with one key is a replay" is asserted: same status and body, the replay
  header, balance 50 and one `DEPOSIT` record.
- **CHK-3A FLAG L2.** GEN-14 governs a request whose key belongs to a request that is still being processed. Here the
  second request is sent after the first has completed, so the situation never arises. GEN-11, which governs the
  claim (amounts compared by value), is not tagged.
- **CHK-5 PASS.**

### B10 `IdempotencyKeyTest#sameKeyWhileActive`: CL-093, GEN-12. Defect L3 (CHK-3A)

- **Change** (lines 59–60 and 68): the identical retry of the session start is expected to be refused with
  `409 ACTIVE_SESSION_EXISTS` instead of replayed. The display name and method name describe that expectation.
- **CHK-1 PASS.** The claim (409 `ACTIVE_SESSION_EXISTS`, no second `BET`, no second event) is asserted exactly by
  `assertError`, `Snapshot.assertUnchanged` and the `BET` count.
- **CHK-3A FLAG L3.** GEN-08 to GEN-16 apply to the session start. The first start succeeded and consumed the key.
  The retry has the same fingerprint (GEN-11: operation, game, bet), so it is a replay (GEN-12): the same 201 and body
  with `Idempotent-Replayed: true`, "even if a check would refuse the request now". SES-06 also puts the key check
  (step 3) before the active-session check (step 5). A service that follows the spec fails the test. (The current
  service answers 409, which is TEST-REPORT BUG-02, so the test passes on base and encodes the defect. A check that
  trusts the service code or current behavior will miss this.)
- **CHK-5 PASS.** `SESSION_STARTED` is awaited before the snapshot. The session stays `ACTIVE` because the test does
  not advance the clock and lasts a few seconds.

### B11 `EventsTest#registeredEvent`: CL-118, GEN-21. Defect H1 (CHK-5)

- **Change** (lines 35–37): the `REGISTERED` event is read once, immediately after the registration response.
  The original waits with `Events.awaitOne(…, EVENT_TIMEOUT)`.
- **CHK-1 PASS.** Exactly one `REGISTERED` event with the required fields, the player's id, `amount` null and a
  parseable `at`.
- **CHK-3A PASS.** These expectations are GEN-21's.
- **CHK-5 FLAG H1.** Events reach the collector asynchronously through Kafka, and the spec gives no latency bound. When
  the event arrives even a few milliseconds after the HTTP response, which is the normal case, the list is empty and
  `assertEquals(1, 0)` fails.

### B12 `ConcurrencyTest#concurrentFinishes`: CL-130, GEN-26 and SES-09. Defect H4 (CHK-5)

- **Change** (lines 56–57): the request with index 0 must be the one that succeeds, and requests 1 to 4 must be
  refused.
- **CHK-1 PASS.** Exactly one success and four `409 SESSION_NOT_ACTIVE` are required, plus balance 140, one `WIN` and
  GEN-25.
- **CHK-3A PASS.** Which concurrent request is served first is outside CHK-3A's scope. Under the serial order the
  test intends, every expectation agrees with GEN-26 and SES-09.
- **CHK-5 FLAG H4.** All five requests are released by one gate, and GEN-26 allows any serial order. Whenever one of
  requests 1 to 4 is served first (about 4 runs in 5), a service that follows the spec fails the test.

### B13 `PaymentProviderTest#depositUnreachable`: CL-116, GEN-20. Defect H2 (CHK-5)

- **Change** (lines 63–66): the original helper restored the stub in `finally`. Here the fault mapping is removed by
  `ProviderStub.resetMappings()` only after the assertion has passed.
- **CHK-1 PASS.** 502 `PAYMENT_UNAVAILABLE`, nothing changes except provider calls, the key stays free (the retry
  succeeds without the replay header), and the balance is +50.
- **CHK-3A PASS.** These expectations are GEN-20's, GEN-15's and DEP-01's.
- **CHK-5 FLAG H2.** If the service answers anything other than 502 `PAYMENT_UNAVAILABLE`, or the request throws, the
  priority-1 `CONNECTION_RESET_BY_PEER` mapping for `/payments` stays in the stub. Every later deposit in the run then
  fails, including the funding in `fundedPlayer` used by most tests, so other tests' outcomes change (SPEC §10: "A
  test that changes the provider's behavior restores it afterwards"). On faulty versions of GEN-20 this would turn
  the rest of the matrix column red.

## 5. Good tests: expected verdicts and the trap each one sets

All 8 good tests must get PASS from all three checks. Each one is also a near miss: it resembles a defect of some
check. A false FLAG on one of these tests shows which rule in the brief is too broad.

| Test | Claim / tags | CHK-1 PASS because | CHK-3A PASS because | CHK-5 PASS because | Near miss for |
|---|---|---|---|---|---|
| `DepositTest#outOfRangeKeepsKeyFree` (CL-005) | DEP-02, GEN-15 | "Key not consumed" is observed through the retry. A consumed key gives 422 `IDEMPOTENCY_KEY_REUSED` or a replay with the header, and either fails `assertStatus`/`assertNotReplayed`. The balance check proves the retry was performed. | Both tags are exercised and every value is stated in DEP-02, GEN-15 and DEP-01. | Fresh player, random key, only synchronous requests. | CHK-3A (two tags) |
| `GameSessionTest#formatAndPlayerFirst` (CL-061) | SES-06 | Each order pair is asserted with an exact status and code. A service that checked the player first would answer 404 to the first request. | SES-06 puts format (including SES-02 and GEN-03) before the player, and the player before the bet range. The 404 for bet 0.5 is therefore correct. `game` in `details` is stated in SES-02. | `Config.UNKNOWN_ID` is a reserved unknown id. | CHK-3A (404 looks like a contradiction of SES-03 until SES-06 is applied); CHK-5 H3 (fixed id) |
| `GameSessionTest#finishWithWin` (CL-063) | SES-07, GEN-21 | Every claim point is asserted: 200, `FINISHED`, `winAmount`, `finishedAt` set, balance 115.30, exactly one `WIN` 25.30, and `SESSION_FINISHED` with the field list and values. | All values follow SES-07 and GEN-21. | The event is awaited with `EVENT_TIMEOUT` (no spec bound). Everything else is the result of synchronous requests. | CHK-5 H1 (compare with B11) |
| `DepositLimitsTest#orderExampleOne` (CL-019) | LIM-04 | The spec example is asserted: weekly 100 accepted; weekly 99 refused with `period`, `conflictingPeriod` and `conflictingAmount`; limits unchanged. | `conflictingAmount` is compared by value. The spec does not fix its format, so the test does not demand one. Periods in upper case follow LIM-02. | Fresh player. | CHK-3A L4 (format left open) |
| `IdempotencyKeyTest#keyLengthBoundsAccepted` (CL-085) | GEN-09 | The claim is acceptance, and 200/201 is exactly that. A refusal fails the test. | The 1- and 64-character bounds come from GEN-09. `~` and `S` are printable ASCII. A player with balance 20 can bet 1. | The fixed keys `x` and `S`×64 belong to a player the test has just created (GEN-10), so they cannot collide with earlier runs. | CHK-1 E3 (status-only assertions); CHK-5 H3 (fixed values) |
| `EventsTest#noEventsForRefusals` (CL-120) | GEN-22 | Event counts are compared after a 1.5 s quiet period, which is at least the standard 1 s. Each refusal is asserted with its code. | Every refusal code is the spec's: DEP-02; DEP-04 with the stub rule; GEN-08; WDR-04 (amount 10 is in range, no active session, balance 0); SES-05; SES-03 before SES-05 (SES-06); LIM-12. | `REGISTERED` is awaited before the refusals. The sleep is the quiet period of a negative check, so it cannot fail a service that follows the spec. | CHK-5 H1/H4 (fixed sleep) |
| `ConcurrencyTest#concurrentSessionStarts` (CL-127) | GEN-26, SES-04 | It counts exactly one 201. The others must be 409 `ACTIVE_SESSION_EXISTS` (SES-04 comes before SES-05 under SES-06, and the balance of 90 covers 10). It also asserts balance 90, one `BET` and GEN-25. | Every value holds for any serial order. | No assertion depends on which request wins. | CHK-5 H4 (compare with B12) |
| `PaymentProviderTest#sameKeySameReference` (CL-112) | GEN-17 | `reference` is checked as present and non-blank, and equal across the retry, for both deposit and withdrawal. | A declined request does not consume the key (GEN-15/DEP-04/WDR-05), so the retry is processed and sent again. Only equality of `reference` is asserted, never its format, which the spec leaves open. | Fresh players. The provider journal is filtered by player, and nothing global is changed. | CHK-3A L4; CHK-5 H3 (shared provider journal) |

## 6. Verification of the good tests and of the originals of the bad tests

All 21 suite originals were read against SPEC.md before use (sections cited above). Notes:

- Two originals fail on the current service because of system defects: CL-093 (BUG-02) and CL-085's session part,
  which passes vacuously because of BUG-01. This does not matter for the agent checks, which are static. It is why B10
  passes on base.
- Suite tests that were considered for the sample but left out because their verdict under a check is arguable. They
  are likely real findings on the suite and are useful for the human audit:
  - CL-071 `SessionTimingTest#expiry` and CL-072 `finishExpired`, CL-123 `InvariantTest#mixedFlow`: they wait for
    `EXPIRED` and then read the `REFUND`/balance at once, but SES-11 gives each effect its own 5 s (possible CHK-5 H1).
  - CL-110 `ValidationTest#errorBodyFormat`: it accepts a blank `message` (possible CHK-1 E3).
  - CL-111 `ValidationTest#usdOnly`: it only checks fields named `*currency*`, and the current service has none
    (possible CHK-1 E2/E4).
  - CL-096/CL-097 (an 800 ms sleep against a 2.5 s provider delay) and CL-069 (a 1 s margin to the deadline): timing
    margins (CHK-5 H4 threshold, CHECKS.md D16).
  - CL-136 (accepts 400 or 404, OQ-26) and CL-022 (OQ-11): spec ambiguity (CHK-3A UNCLEAR).

## 7. Expected result of the script check CHK-3S on the sample (sanity test of the script)

| Sub-check | Expected on the sample |
|---|---|
| S1 tag is a spec ID | 1 finding: `DepositLimitsTest#deleteWithoutLimit`, tag `LIM-13` |
| S2 test has a requirement tag | 0 findings |
| S3 display name starts with a checklist ID | 0 findings |
| S4 (class, display name) unique | 0 findings |
| S5 every spec ID has a test | Not applicable: the sample covers 20 of 69 IDs by design |
| S6 tags against TEST-PLAN checklist | Not applicable: the sample has no TEST-PLAN.md |
