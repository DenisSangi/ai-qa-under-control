# Adjudication of the calibration key

`SAMPLE-KEY.md` is the design agent's answer key and stays as it was committed. In calibration round 1 (two
repetitions of CHK-1, CHK-3A and CHK-5 on the sample), every bad test was flagged in both repetitions, and every miss
was a FLAG where the key expected PASS. For 8 such verdicts the checking agents' evidence shows a defect the key did not
record. Claude read each one against the specification and the check's brief; the author accepted all 8 readings on
2026-10-20. Scoring applies the table below on top of the key (`experiment/review.py score --adjust`).

Several of these tests are byte-identical copies of tests in suite B, so the defects are in suite B as well.

| # | Test | Check | Key | Adjudicated | Evidence (from the agents' verdicts) |
|---|---|---|---|---|---|
| 1 | GameSessionTest#finishWithWin | CHK-1 | PASS | FLAG E3 | The event fields are checked with `has()`: a SESSION_FINISHED with `eventId` and `finishedAt` null passes, although the claim is "with all fields" (SES-07). Both repetitions |
| 2 | EventsTest#registeredEvent | CHK-3A | PASS | FLAG L4 | The test requires `eventId` as a string and `at` in ISO-8601; GEN-21 fixes neither type nor format. Both repetitions |
| 3 | DepositTest#outOfRangeRefused | CHK-5 | PASS | FLAG H1 | The baseline snapshot counts player-events right after the setup deposit, before its events are guaranteed to be observable (no bound in the spec); a late setup event in the quiet period fails the test. Both repetitions |
| 4 | GameSessionTest#insufficientFunds | CHK-5 | PASS | FLAG H1 | As 3. Both repetitions |
| 5 | IdempotencyKeyTest#sameKeyWhileActive | CHK-5 | PASS | FLAG H1 | As 3; the test waits only for SESSION_STARTED, and there is no order between the topics (GEN-24). Both repetitions |
| 6 | IdempotencyKeyTest#amountComparedByValue | CHK-1 | PASS | FLAG E4 | The claim "is a replay" means, by GEN-12, that events do not change; events are not checked. Repetition 1 only |
| 7 | EventsTest#registeredEvent | CHK-1 | PASS | FLAG E3 | `at` is only parsed, so any time passes; GEN-21 requires the service-clock time. Repetition 1 only |
| 8 | ConcurrencyTest#concurrentSessionStarts | CHK-1 | PASS | FLAG E4 | "Exactly one ACTIVE session" is not observed: only the 201 answers are counted. Repetition 1 only |

```csv
test,check,verdict,kind
GameSessionTest#finishWithWin,CHK-1,FLAG,E3
EventsTest#registeredEvent,CHK-3A,FLAG,L4
DepositTest#outOfRangeRefused,CHK-5,FLAG,H1
GameSessionTest#insufficientFunds,CHK-5,FLAG,H1
IdempotencyKeyTest#sameKeyWhileActive,CHK-5,FLAG,H1
IdempotencyKeyTest#amountComparedByValue,CHK-1,FLAG,E4
EventsTest#registeredEvent,CHK-1,FLAG,E3
ConcurrencyTest#concurrentSessionStarts,CHK-1,FLAG,E4
```
