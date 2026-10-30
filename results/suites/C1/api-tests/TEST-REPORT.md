# Test Report — Player Wallet money flows (regression)

| | |
|---|---|
| System under test | `player-service` on the shared stand (`http://localhost:8080`), test clock enabled |
| Test basis | `docs/spec/SPEC.md` (source of truth); plan and checklist: `api-tests/TEST-PLAN.md` |
| Suite | `com.arcade.qa.RegressionSuite` (JUnit Platform `@Suite`), run with `mvn -B test` in `api-tests/` |
| Run | 2026-10-18 (real time). Service clock during the run: 2026-12 … 2027-03 (it had been advanced by an earlier run) |
| Duration | 88 s suite time, 1 min 31 s including Maven (limit: 5 min) |
| Raw results | Surefire XML/TXT: `api-tests/results/surefire-reports/` (one file per test class + `RegressionSuite`) |
| Verdict | **Not ready for release** — 7 defects, 2 of them critical money defects (§6) |

## 1. Requirements coverage matrix

Each checklist item has exactly one autotest, tagged with its requirement numbers (`@Tag`), and its display name
starts with the checklist ID. "Passed / runs" counts parameterized invocations. The Bug column links a failed
check to §2.

Cross-cutting assertions not repeated in the matrix: **GEN-04** (error body) is asserted in every negative
check, and **GEN-22** (no events for refused requests) in every "nothing changes" check, through the shared
snapshot.

| Requirement | Checklist item | Autotest (`class#method`) | Passed / runs | Result  Bug |
|---|---|---|---|---|---|
| GEN-01 | CL-111 USD only: no other currency appears in responses or provider requests | `ValidationTest#usdOnly` | 1/1 | PASS |  |
| GEN-02 | CL-102 amounts with 2 decimal places are accepted | `ValidationTest#twoDecimalsAccepted` | 5/5 | PASS |  |
| GEN-02 | CL-103 amounts with 3 decimal places -> 400 VALIDATION_ERROR | `ValidationTest#threeDecimalsRefused` | 5/5 | PASS |  |
| GEN-02 | CL-104 decimal places are counted as written: a trailing zero (10.010) -> 400 VALIDATION_ERROR | `ValidationTest#trailingZeroCounted` | 5/5 | PASS |  |
| GEN-03 | CL-105 numbers sent as strings -> 400 VALIDATION_ERROR with the field name in details | `ValidationTest#numbersAsStrings` | 6/6 | PASS |  |
| GEN-03 | CL-136 a non-integer playerId (N.5) is refused, never truncated to player N | `SecurityTest#nonIntegerPlayerId` | 0/1 | **FAIL** | BUG-07 |
| GEN-04 | CL-110 error responses are JSON objects with a machine-readable code and a message | `ValidationTest#errorBodyFormat` | 1/1 | PASS |  |
| GEN-04 | CL-135 error responses (also for malformed JSON) do not disclose internals | `SecurityTest#noInternalsDisclosed` | 1/1 | PASS |  |
| GEN-05 | CL-106 a missing or null required field -> 400 VALIDATION_ERROR | `ValidationTest#requiredFieldMissingOrNull` | 14/14 | PASS |  |
| GEN-06 | CL-107 a non-numeric id in the path -> 400 BAD_REQUEST | `ValidationTest#nonNumericId` | 0/8 | **FAIL** | BUG-06 |
| GEN-07 | CL-108 an unknown player -> 404 NOT_FOUND | `ValidationTest#unknownPlayer` | 7/7 | PASS |  |
| GEN-07 | CL-109 an unknown session -> 404 NOT_FOUND for read and finish | `ValidationTest#unknownSession` | 1/1 | PASS |  |
| GEN-07 | CL-136 a non-integer playerId (N.5) is refused, never truncated to player N | `SecurityTest#nonIntegerPlayerId` | 0/1 | **FAIL** | BUG-07 |
| GEN-08 | CL-083 missing Idempotency-Key -> 400 IDEMPOTENCY_KEY_REQUIRED for deposit, withdrawal, session start | `IdempotencyKeyTest#missingKey` | 0/1 | **FAIL** | BUG-01 |
| GEN-08 | CL-084 empty Idempotency-Key -> 400 IDEMPOTENCY_KEY_REQUIRED for deposit, withdrawal, session start | `IdempotencyKeyTest#emptyKey` | 0/1 | **FAIL** | BUG-01 |
| GEN-09 | CL-085 keys of 1 and 64 printable ASCII characters are accepted | `IdempotencyKeyTest#keyLengthBoundsAccepted` | 1/1 | PASS |  |
| GEN-09 | CL-086 a key of 65 characters -> 400 IDEMPOTENCY_KEY_INVALID for deposit, withdrawal, session start | `IdempotencyKeyTest#keyTooLong` | 0/1 | **FAIL** | BUG-01 |
| GEN-09 | CL-087 non-printable or non-ASCII key (TAB, é) -> 400 IDEMPOTENCY_KEY_INVALID | `IdempotencyKeyTest#keyNotPrintableAscii` | 0/1 | **FAIL** | BUG-01 |
| GEN-09 | CL-134 injection-like printable ASCII keys are treated as plain keys | `SecurityTest#injectionLikeKeys` | 4/4 | PASS |  |
| GEN-10 | CL-088 one key space per player: deposit key reused for withdrawal/session, session key for deposit -> 422 | `IdempotencyKeyTest#keySharedAcrossOperations` | 0/1 | **FAIL** | BUG-03 |
| GEN-10 | CL-131 the same key value of another player is an independent operation, never a replay of it | `SecurityTest#keyIsolationBetweenPlayers` | 1/1 | PASS |  |
| GEN-11 | CL-089 amounts are compared by value: 50 then 50.00 with one key is a replay | `IdempotencyKeyTest#amountComparedByValue` | 1/1 | PASS |  |
| GEN-11 | CL-090 session start fingerprint: same game and bet -> replay; other game or bet -> 422 REUSED | `IdempotencyKeyTest#sessionFingerprint` | 0/1 | **FAIL** | BUG-03 |
| GEN-12 | CL-091 deposit replay: same status/body + Idempotent-Replayed; balance, journal, events, provider unchanged | `IdempotencyKeyTest#depositReplay` | 1/1 | PASS |  |
| GEN-12 | CL-092 withdrawal replay: same status/body + header; balance, journal, events, provider unchanged | `IdempotencyKeyTest#withdrawalReplay` | 1/1 | PASS |  |
| GEN-12 | CL-093 session start replay: 201 same body + header; no second BET, no second event | `IdempotencyKeyTest#sessionStartReplay` | 0/1 | **FAIL** | BUG-02 |
| GEN-12 | CL-094 replay returns the original response although a check would refuse now | `IdempotencyKeyTest#replayIgnoresCurrentChecks` | 0/1 | **FAIL** | BUG-02 |
| GEN-13 | CL-088 one key space per player: deposit key reused for withdrawal/session, session key for deposit -> 422 | `IdempotencyKeyTest#keySharedAcrossOperations` | 0/1 | **FAIL** | BUG-03 |
| GEN-13 | CL-090 session start fingerprint: same game and bet -> replay; other game or bet -> 422 REUSED | `IdempotencyKeyTest#sessionFingerprint` | 0/1 | **FAIL** | BUG-03 |
| GEN-13 | CL-095 consumed key with a different amount (deposit, withdrawal) -> 422 IDEMPOTENCY_KEY_REUSED; nothing changes | `IdempotencyKeyTest#differentAmount` | 1/1 | PASS |  |
| GEN-14 | CL-096 deposit with the same key while the first is processed -> 409 IDEMPOTENCY_KEY_IN_PROGRESS | `IdempotencyKeyTest#depositInProgress` | 1/1 | PASS |  |
| GEN-14 | CL-097 withdrawal with the same key while the first is processed -> 409 IDEMPOTENCY_KEY_IN_PROGRESS | `IdempotencyKeyTest#withdrawalInProgress` | 1/1 | PASS |  |
| GEN-14 | CL-129 concurrent requests with one key: the operation is performed exactly once | `ConcurrencyTest#concurrentSameKey` | 1/1 | PASS |  |
| GEN-15 | CL-005 the key of an out-of-range deposit is not consumed | `DepositTest#outOfRangeKeepsKeyFree` | 1/1 | PASS |  |
| GEN-15 | CL-098 deposit errors (400, range, limit, provider decline) do not consume the key | `IdempotencyKeyTest#depositErrorsKeepKeyFree` | 1/1 | PASS |  |
| GEN-15 | CL-099 withdrawal errors (range, active session, insufficient funds, payout decline) keep the key free | `IdempotencyKeyTest#withdrawalErrorsKeepKeyFree` | 0/1 | **FAIL** | BUG-04 |
| GEN-15 | CL-100 session start errors (bet range, insufficient funds, active session) keep the key free | `IdempotencyKeyTest#sessionErrorsKeepKeyFree` | 1/1 | PASS |  |
| GEN-16 | CL-101 a replay after the clock is advanced by 31 days returns the original response | `IdempotencyKeyTest#keyLifetime` | 1/1 | PASS |  |
| GEN-17 | CL-112 every provider request carries reference; a retry with the same key sends the same reference | `PaymentProviderTest#sameKeySameReference` | 1/1 | PASS |  |
| GEN-17 | CL-113 different keys send different references, also the same key value of two players | `PaymentProviderTest#differentKeysDifferentReferences` | 1/1 | PASS |  |
| GEN-18 | CL-114 deposit, provider slower than 5 s: 504 PAYMENT_TIMEOUT within 6 s; nothing changes; key free | `PaymentProviderTest#depositTimeout` | 0/1 | **FAIL** | BUG-05 |
| GEN-18 | CL-115 withdrawal, provider slower than 5 s: 504 PAYMENT_TIMEOUT within 6 s; nothing changes; key free | `PaymentProviderTest#withdrawalTimeout` | 0/1 | **FAIL** | BUG-05 |
| GEN-19 | CL-114 deposit, provider slower than 5 s: 504 PAYMENT_TIMEOUT within 6 s; nothing changes; key free | `PaymentProviderTest#depositTimeout` | 0/1 | **FAIL** | BUG-05 |
| GEN-19 | CL-115 withdrawal, provider slower than 5 s: 504 PAYMENT_TIMEOUT within 6 s; nothing changes; key free | `PaymentProviderTest#withdrawalTimeout` | 0/1 | **FAIL** | BUG-05 |
| GEN-20 | CL-116 deposit, provider unreachable: 502 PAYMENT_UNAVAILABLE; nothing changes; key free | `PaymentProviderTest#depositUnreachable` | 1/1 | PASS |  |
| GEN-20 | CL-117 withdrawal, provider unreachable: 502 PAYMENT_UNAVAILABLE; nothing changes; key free | `PaymentProviderTest#withdrawalUnreachable` | 1/1 | PASS |  |
| GEN-21 | CL-053 start: 201 ACTIVE session, balance - bet, one BET record, SESSION_STARTED with all fields | `GameSessionTest#startSession` | 1/1 | PASS |  |
| GEN-21 | CL-063 finish with a win: 200 FINISHED, balance + win, one WIN record, SESSION_FINISHED with all fields | `GameSessionTest#finishWithWin` | 1/1 | PASS |  |
| GEN-21 | CL-071 expiry within 5 s: EXPIRED, finishedAt null, bet refunded, one REFUND, SESSION_EXPIRED | `SessionTimingTest#expiry` | 1/1 | PASS |  |
| GEN-21 | CL-118 REGISTERED in player-events with eventId, type, playerId, amount null, at | `EventsTest#registeredEvent` | 1/1 | PASS |  |
| GEN-21 | CL-119 DEPOSIT and WITHDRAWAL carry all fields, the operation amount and the service-clock time | `EventsTest#depositAndWithdrawalEvents` | 1/1 | PASS |  |
| GEN-22 | CL-120 refused and failed requests publish no event | `EventsTest#noEventsForRefusals` | 1/1 | PASS |  |
| GEN-23 | CL-121 amount, bet and win in events are JSON numbers equal by value to the operation amounts | `EventsTest#amountsInEvents` | 1/1 | PASS |  |
| GEN-24 | CL-122 event key is the player id; events of one player arrive in operation order per topic | `EventsTest#keyAndOrder` | 1/1 | PASS |  |
| GEN-25 | CL-123 balance equals the journal after deposit, win, loss, refund and withdrawal | `InvariantTest#mixedFlow` | 1/1 | PASS |  |
| GEN-25 | CL-124 balance equals the journal for every player created by this run | `InvariantTest#sweep` | 1/1 | PASS |  |
| GEN-26 | CL-125 concurrent deposits with different keys: every deposit applied, no lost update | `ConcurrencyTest#concurrentDeposits` | 1/1 | PASS |  |
| GEN-26 | CL-126 concurrent withdrawals exceeding the balance: serializable result, no overdraft | `ConcurrencyTest#concurrentWithdrawals` | 1/1 | PASS |  |
| GEN-26 | CL-127 concurrent session starts: exactly one ACTIVE session and one BET | `ConcurrencyTest#concurrentSessionStarts` | 1/1 | PASS |  |
| GEN-26 | CL-128 concurrent withdrawal and session start for the same money: exactly one succeeds | `ConcurrencyTest#withdrawalVersusSessionStart` | 0/1 | **FAIL** | BUG-04 |
| GEN-26 | CL-129 concurrent requests with one key: the operation is performed exactly once | `ConcurrencyTest#concurrentSameKey` | 1/1 | PASS |  |
| GEN-26 | CL-130 concurrent finishes of one session: the win is credited exactly once | `ConcurrencyTest#concurrentFinishes` | 1/1 | PASS |  |
| DEP-01 | CL-001 approved deposit: 200, balance + amount, one DEPOSIT record, one event, one provider call | `DepositTest#approvedDeposit` | 1/1 | PASS |  |
| DEP-02 | CL-002 deposit 10.00 (lower bound) is accepted | `DepositTest#lowerBoundAccepted` | 1/1 | PASS |  |
| DEP-02 | CL-003 deposit 50000.00 (upper bound) passes the range check and reaches the provider | `DepositTest#upperBoundPassesRange` | 1/1 | PASS |  |
| DEP-02 | CL-004 deposit out of range: 422 DEPOSIT_AMOUNT_OUT_OF_RANGE, nothing changes, provider not called | `DepositTest#outOfRangeRefused` | 2/2 | PASS |  |
| DEP-02 | CL-005 the key of an out-of-range deposit is not consumed | `DepositTest#outOfRangeKeepsKeyFree` | 1/1 | PASS |  |
| DEP-02 | CL-132 zero and negative amounts are refused with the range error; the balance cannot be manipulated | `SecurityTest#zeroAndNegativeAmounts` | 1/1 | PASS |  |
| DEP-02 | CL-133 huge amounts are refused with the range error, never 500 | `SecurityTest#hugeAmounts` | 1/1 | PASS |  |
| DEP-03 | CL-011 order of checks: deposit limits before the provider (provider not called) | `DepositTest#limitsBeforeProvider` | 1/1 | PASS |  |
| DEP-03 | CL-038 deposit equal to remaining accepted; +0.01 -> 422 DEPOSIT_LIMIT_EXCEEDED, nothing changes, key free | `DepositLimitCountingTest#depositAgainstLimit` | 1/1 | PASS |  |
| DEP-04 | CL-006 provider decline: 422 PAYMENT_DECLINED, nothing changes, key not consumed; 10000.00 approved | `DepositTest#providerDecline` | 1/1 | PASS |  |
| DEP-05 | CL-007 order of checks: request format (400) before unknown player (404) | `DepositTest#formatBeforeUnknownPlayer` | 1/1 | PASS |  |
| DEP-05 | CL-008 order of checks: unknown player (404) before amount range | `DepositTest#unknownPlayerBeforeRange` | 1/1 | PASS |  |
| DEP-05 | CL-009 order of checks: idempotency key (422 IDEMPOTENCY_KEY_REUSED) before amount range | `DepositTest#keyBeforeRange` | 1/1 | PASS |  |
| DEP-05 | CL-010 order of checks: amount range before deposit limits | `DepositTest#rangeBeforeLimits` | 1/1 | PASS |  |
| DEP-05 | CL-011 order of checks: deposit limits before the provider (provider not called) | `DepositTest#limitsBeforeProvider` | 1/1 | PASS |  |
| LIM-01 | CL-012 period path must be daily|weekly|monthly in lower case: DAILY, Daily, yearly -> 400 | `DepositLimitsTest#periodPathValidated` | 1/1 | PASS |  |
| LIM-02 | CL-013 player without limits gets 200 and an empty list | `DepositLimitsTest#emptyList` | 1/1 | PASS |  |
| LIM-02 | CL-014 list item {period, amount, used, remaining, pending}: upper-case period, only periods with a limit | `DepositLimitsTest#listFormat` | 1/1 | PASS |  |
| LIM-02 | CL-015 used = counted deposits, remaining = amount - used | `DepositLimitsTest#usedAndRemaining` | 1/1 | PASS |  |
| LIM-02 | CL-016 remaining is not below 0 after lowering the limit below used | `DepositLimitsTest#remainingNotNegative` | 1/1 | PASS |  |
| LIM-03 | CL-017 limit bounds 10.00 and 1000000.00 are accepted | `DepositLimitsTest#limitBoundsAccepted` | 1/1 | PASS |  |
| LIM-03 | CL-018 limit out of range: 422 DEPOSIT_LIMIT_OUT_OF_RANGE, current and pending values unchanged | `DepositLimitsTest#limitOutOfRange` | 2/2 | PASS |  |
| LIM-03 | CL-133 huge amounts are refused with the range error, never 500 | `SecurityTest#hugeAmounts` | 1/1 | PASS |  |
| LIM-04 | CL-019 spec example: daily 100, weekly 300 -> weekly 100 accepted, weekly 99 refused with details | `DepositLimitsTest#orderExampleOne` | 1/1 | PASS |  |
| LIM-04 | CL-020 spec example: daily 100 with pending 250, weekly 300 -> weekly 200 refused (conflict DAILY 250) | `DepositLimitsTest#orderExampleTwo` | 1/1 | PASS |  |
| LIM-04 | CL-021 a value above a longer period's value is refused (daily > weekly; weekly > monthly current) | `DepositLimitsTest#shorterAboveLongerRefused` | 1/1 | PASS |  |
| LIM-04 | CL-022 periods without a limit and pending removals take no part in the comparison | `DepositLimitsTest#noLimitAndPendingRemovalIgnored` | 1/1 | PASS |  |
| LIM-05 | CL-023 order of checks: 400 before 404, 404 before range, range before period order | `DepositLimitsTest#orderOfChecks` | 1/1 | PASS |  |
| LIM-06 | CL-024 a first limit and a lower amount take effect immediately | `DepositLimitsTest#immediateChanges` | 1/1 | PASS |  |
| LIM-06 | CL-025 a lower amount discards a pending increase | `DepositLimitsTest#lowerDiscardsPending` | 1/1 | PASS |  |
| LIM-07 | CL-026 increase: amount unchanged, pending.amount new, effectiveAt = request time + 24h | `DepositLimitsTest#increaseIsPending` | 1/1 | PASS |  |
| LIM-07 | CL-027 before effectiveAt the current amount applies, from effectiveAt the new one | `DepositLimitsTest#increaseAppliesAtEffectiveAt` | 1/1 | PASS |  |
| LIM-08 | CL-028 repeating the same increase later changes nothing (effectiveAt unchanged) | `DepositLimitsTest#repeatedIncrease` | 1/1 | PASS |  |
| LIM-08 | CL-029 a different increase replaces the pending one and restarts the cooling-off | `DepositLimitsTest#replacingIncrease` | 1/1 | PASS |  |
| LIM-08 | CL-030 repeated removal keeps effectiveAt; increase replaces a pending removal and vice versa | `DepositLimitsTest#removalReplacements` | 1/1 | PASS |  |
| LIM-09 | CL-031 PUT with the current amount cancels a pending increase and a pending removal | `DepositLimitsTest#cancelPending` | 1/1 | PASS |  |
| LIM-10 | CL-032 DELETE: pending removal +24h; limit applies until effectiveAt; afterwards not listed, not limiting | `DepositLimitsTest#removalLifecycle` | 1/1 | PASS |  |
| LIM-10 | CL-033 DELETE for a period without a limit -> 404 NOT_FOUND | `DepositLimitsTest#deleteWithoutLimit` | 1/1 | PASS |  |
| LIM-11 | CL-034 daily window: a deposit is counted just before 24h and not counted from 24h on | `DepositLimitCountingTest#dailyWindow` | 1/1 | PASS |  |
| LIM-11 | CL-035 weekly window is 7 days and monthly window is 30 days | `DepositLimitCountingTest#weeklyAndMonthlyWindows` | 1/1 | PASS |  |
| LIM-11 | CL-036 refused deposits (out of range, limit exceeded, provider declined) are never counted | `DepositLimitCountingTest#refusedNotCounted` | 1/1 | PASS |  |
| LIM-11 | CL-037 a withdrawal does not reduce the counted amount | `DepositLimitCountingTest#withdrawalDoesNotReduceUsed` | 1/1 | PASS |  |
| LIM-12 | CL-038 deposit equal to remaining accepted; +0.01 -> 422 DEPOSIT_LIMIT_EXCEEDED, nothing changes, key free | `DepositLimitCountingTest#depositAgainstLimit` | 1/1 | PASS |  |
| LIM-12 | CL-039 most restrictive period wins (spec example: daily remaining 50, monthly 0 -> MONTHLY, 0) | `DepositLimitCountingTest#mostRestrictivePeriod` | 1/1 | PASS |  |
| LIM-12 | CL-040 tie on remaining -> the shortest period (DAILY over WEEKLY, WEEKLY over MONTHLY) | `DepositLimitCountingTest#tieGoesToShortestPeriod` | 1/1 | PASS |  |
| WDR-01 | CL-041 approved withdrawal: 200, balance - amount, one WITHDRAWAL record, one event, one provider call | `WithdrawalTest#approvedWithdrawal` | 1/1 | PASS |  |
| WDR-02 | CL-042 withdrawal 10.00 accepted; 50000.00 passes the range check (-> INSUFFICIENT_FUNDS) | `WithdrawalTest#boundsAccepted` | 1/1 | PASS |  |
| WDR-02 | CL-043 withdrawal out of range: 422 WITHDRAWAL_AMOUNT_OUT_OF_RANGE, nothing changes, key free | `WithdrawalTest#outOfRange` | 2/2 | PASS |  |
| WDR-02 | CL-044 a balance below 10.00 cannot be withdrawn | `WithdrawalTest#smallBalanceNotWithdrawable` | 1/1 | PASS |  |
| WDR-02 | CL-132 zero and negative amounts are refused with the range error; the balance cannot be manipulated | `SecurityTest#zeroAndNegativeAmounts` | 1/1 | PASS |  |
| WDR-02 | CL-133 huge amounts are refused with the range error, never 500 | `SecurityTest#hugeAmounts` | 1/1 | PASS |  |
| WDR-03 | CL-045 active session: 409 ACTIVE_SESSION_EXISTS, nothing changes, provider not called, key free | `WithdrawalTest#activeSessionBlocks` | 0/1 | **FAIL** | BUG-04 |
| WDR-03 | CL-046 EXPIRED and FINISHED sessions do not block a withdrawal | `WithdrawalTest#expiredAndFinishedDoNotBlock` | 1/1 | PASS |  |
| WDR-04 | CL-047 amount above balance: 422 INSUFFICIENT_FUNDS, nothing changes, provider not called, key free | `WithdrawalTest#insufficientFunds` | 1/1 | PASS |  |
| WDR-04 | CL-048 withdrawing the whole balance is allowed | `WithdrawalTest#wholeBalance` | 1/1 | PASS |  |
| WDR-05 | CL-049 payout decline: 422 PAYOUT_DECLINED, nothing changes, key free; 5000.00 approved | `WithdrawalTest#payoutDecline` | 1/1 | PASS |  |
| WDR-06 | CL-050 order of checks: 400 before 404, 404 before range | `WithdrawalTest#formatAndPlayerFirst` | 1/1 | PASS |  |
| WDR-06 | CL-051 order of checks: idempotency key before range, range before active session | `WithdrawalTest#keyBeforeRangeBeforeSession` | 1/1 | PASS |  |
| WDR-06 | CL-052 order of checks: active session before balance, balance before provider | `WithdrawalTest#sessionBeforeBalanceBeforeProvider` | 0/1 | **FAIL** | BUG-04 |
| SES-01 | CL-053 start: 201 ACTIVE session, balance - bet, one BET record, SESSION_STARTED with all fields | `GameSessionTest#startSession` | 1/1 | PASS |  |
| SES-01 | CL-068 startedAt and finishedAt are service-clock times | `GameSessionTest#timesOnServiceClock` | 1/1 | PASS |  |
| SES-02 | CL-054 every game slots, roulette, blackjack, poker, crash is accepted | `GameSessionTest#allGamesAccepted` | 1/1 | PASS |  |
| SES-02 | CL-055 unknown or wrongly cased game -> 400 VALIDATION_ERROR with game in details | `GameSessionTest#invalidGame` | 4/4 | PASS |  |
| SES-03 | CL-056 bet bounds 1.00 and 500.00 are accepted | `GameSessionTest#betBoundsAccepted` | 1/1 | PASS |  |
| SES-03 | CL-057 bet out of range: 422 BET_AMOUNT_OUT_OF_RANGE with min/max, nothing changes, key free | `GameSessionTest#betOutOfRange` | 2/2 | PASS |  |
| SES-03 | CL-132 zero and negative amounts are refused with the range error; the balance cannot be manipulated | `SecurityTest#zeroAndNegativeAmounts` | 1/1 | PASS |  |
| SES-03 | CL-133 huge amounts are refused with the range error, never 500 | `SecurityTest#hugeAmounts` | 1/1 | PASS |  |
| SES-04 | CL-058 second start while ACTIVE: 409 ACTIVE_SESSION_EXISTS, nothing changes, key free | `GameSessionTest#oneActiveSession` | 1/1 | PASS |  |
| SES-04 | CL-073 an EXPIRED session does not prevent a new session | `SessionTimingTest#newSessionAfterExpiry` | 1/1 | PASS |  |
| SES-04 | CL-127 concurrent session starts: exactly one ACTIVE session and one BET | `ConcurrencyTest#concurrentSessionStarts` | 1/1 | PASS |  |
| SES-05 | CL-059 bet above balance: 422 INSUFFICIENT_FUNDS, nothing changes, key free | `GameSessionTest#insufficientFunds` | 1/1 | PASS |  |
| SES-05 | CL-060 a bet of the whole balance is allowed | `GameSessionTest#wholeBalanceBet` | 1/1 | PASS |  |
| SES-06 | CL-061 order of checks: 400 (game) before 404, 404 before bet range | `GameSessionTest#formatAndPlayerFirst` | 1/1 | PASS |  |
| SES-06 | CL-062 order of checks: key before bet range, bet range before active session, active session before balance | `GameSessionTest#keyRangeSessionBalanceOrder` | 0/1 | **FAIL** | BUG-03 |
| SES-07 | CL-063 finish with a win: 200 FINISHED, balance + win, one WIN record, SESSION_FINISHED with all fields | `GameSessionTest#finishWithWin` | 1/1 | PASS |  |
| SES-07 | CL-064 negative win -> 400 VALIDATION_ERROR with win in details; session and balance unchanged | `GameSessionTest#negativeWin` | 1/1 | PASS |  |
| SES-07 | CL-068 startedAt and finishedAt are service-clock times | `GameSessionTest#timesOnServiceClock` | 1/1 | PASS |  |
| SES-08 | CL-065 win 0 is a loss: FINISHED, winAmount 0, no WIN record, SESSION_FINISHED with win 0 | `GameSessionTest#loss` | 1/1 | PASS |  |
| SES-09 | CL-066 finishing a FINISHED session -> 409 SESSION_NOT_ACTIVE; the win is not credited twice | `GameSessionTest#finishTwice` | 1/1 | PASS |  |
| SES-09 | CL-072 finishing an EXPIRED session -> 409 SESSION_NOT_ACTIVE; nothing changes | `SessionTimingTest#finishExpired` | 1/1 | PASS |  |
| SES-09 | CL-130 concurrent finishes of one session: the win is credited exactly once | `ConcurrencyTest#concurrentFinishes` | 1/1 | PASS |  |
| SES-10 | CL-069 finish with the service clock at startedAt + 59 s is accepted | `SessionTimingTest#finishJustBeforeDeadline` | 1/1 | PASS |  |
| SES-10 | CL-070 finish at startedAt + 60 s -> 409 SESSION_NOT_ACTIVE; the win is not credited | `SessionTimingTest#finishAtDeadlineRefused` | 1/1 | PASS |  |
| SES-11 | CL-071 expiry within 5 s: EXPIRED, finishedAt null, bet refunded, one REFUND, SESSION_EXPIRED | `SessionTimingTest#expiry` | 1/1 | PASS |  |
| SES-12 | CL-046 EXPIRED and FINISHED sessions do not block a withdrawal | `WithdrawalTest#expiredAndFinishedDoNotBlock` | 1/1 | PASS |  |
| SES-12 | CL-073 an EXPIRED session does not prevent a new session | `SessionTimingTest#newSessionAfterExpiry` | 1/1 | PASS |  |
| SES-12 | CL-075 netResult = sum(win - bet) over FINISHED sessions; ACTIVE and EXPIRED are not counted | `LeaderboardTest#netResult` | 1/1 | PASS |  |
| SES-13 | CL-067 GET /api/sessions/{id} returns 200 with the current session state | `GameSessionTest#readSession` | 1/1 | PASS |  |
| LDB-01 | CL-074 GET /api/leaderboard: 200, at most 10 {playerId, username, netResult}, descending, positive | `LeaderboardTest#topFormat` | 1/1 | PASS |  |
| LDB-02 | CL-081 at a period boundary the top is recomputed within 30 s (empty) and sent on the WebSocket | `LeaderboardPeriodTest#newPeriodRecomputed` | 1/1 | PASS |  |
| LDB-02 | CL-082 a session finished after the boundary counts in the new period; net <= 0 is not listed | `LeaderboardPeriodTest#finishedAtDecides` | 1/1 | PASS |  |
| LDB-03 | CL-075 netResult = sum(win - bet) over FINISHED sessions; ACTIVE and EXPIRED are not counted | `LeaderboardTest#netResult` | 1/1 | PASS |  |
| LDB-03 | CL-082 a session finished after the boundary counts in the new period; net <= 0 is not listed | `LeaderboardPeriodTest#finishedAtDecides` | 1/1 | PASS |  |
| LDB-04 | CL-074 GET /api/leaderboard: 200, at most 10 {playerId, username, netResult}, descending, positive | `LeaderboardTest#topFormat` | 1/1 | PASS |  |
| LDB-04 | CL-081 at a period boundary the top is recomputed within 30 s (empty) and sent on the WebSocket | `LeaderboardPeriodTest#newPeriodRecomputed` | 1/1 | PASS |  |
| LDB-04 | CL-082 a session finished after the boundary counts in the new period; net <= 0 is not listed | `LeaderboardPeriodTest#finishedAtDecides` | 1/1 | PASS |  |
| LDB-05 | CL-076 a finished session is reflected in GET /api/leaderboard within 30 s | `LeaderboardTest#freshness` | 1/1 | PASS |  |
| LDB-06 | CL-077 a new entry in the top sends LEADERBOARD_UPDATED within 30 s; its top equals GET | `LeaderboardTest#messageOnNewEntry` | 1/1 | PASS |  |
| LDB-06 | CL-078 a change of only netResult of a listed player sends a message | `LeaderboardTest#messageOnNetResultChange` | 1/1 | PASS |  |
| LDB-06 | CL-079 no message while the top does not change (a session outside the top) | `LeaderboardTest#noMessageWithoutChange` | 1/1 | PASS |  |
| LDB-06 | CL-080 no two consecutive messages carry the same top | `LeaderboardTest#consecutiveMessagesDiffer` | 1/1 | PASS |  |
| LDB-07 | CL-081 at a period boundary the top is recomputed within 30 s (empty) and sent on the WebSocket | `LeaderboardPeriodTest#newPeriodRecomputed` | 1/1 | PASS |  |

## 2. Bugs found

Severity: **Critical** — money is charged/paid out wrongly; **High** — a specified money-safety or contract rule
is not enforced; **Medium** — a wrong error contract without money impact.

| ID | Title | Requirement | Preconditions and test data | Steps to reproduce | Expected result | Actual result |
|---|---|---|---|---|---|---|
| BUG-01 (High) | Session start accepts a missing, empty or malformed `Idempotency-Key` | GEN-08, GEN-09 (SES-01) | Player P created with `POST /api/players`; deposit 100 (key `d-1`) → balance 100. Keys: none, `""`, 65 × `z`, `tab<TAB>key`, `kéy`. Checks: CL-083, CL-084, CL-086, CL-087 | 1. `POST /api/sessions` **without** the header, body `{"playerId": P, "game": "slots", "bet": 5}`.<br>2. Repeat with `Idempotency-Key:` (empty).<br>3. Repeat with a 65-character key.<br>4. Repeat with `tab<TAB>key` and with `kéy`.<br>(Finish the previous session before each step.) | Steps 1–2: `400 IDEMPOTENCY_KEY_REQUIRED`; steps 3–4: `400 IDEMPOTENCY_KEY_INVALID`. No session, balance/journal/events unchanged. (Deposit and withdrawal behave like this.) | Every request returns `201 Created`: a new `ACTIVE` session is created, the bet is charged (a `BET` record) and `SESSION_STARTED` is published. The header is ignored; it is also absent from the OpenAPI description of `POST /api/sessions`. |
| BUG-02 (Critical) | A retried session start with the same key charges the bet again: there is no replay | GEN-12 (GEN-11, GEN-16) | Player P, balance 100. Key K. Checks: CL-093, CL-094. Reproduced manually: player 560 → sessions 5164 and 5165 | 1. `POST /api/sessions`, key K, `{"playerId": P, "game": "slots", "bet": 10}` → `201`, session S1.<br>2. Send the identical request again (same K, same body) while S1 is `ACTIVE`.<br>3. `POST /api/sessions/S1/finish` `{"win": 0}`, then send the identical request a third time.<br>4. `GET /api/players/P`; read `transactions` of P. | Steps 2 and 3: `201` with the body of step 1 (session S1), header `Idempotent-Replayed: true`. No new session, balance 90, exactly one `BET` record, one `SESSION_STARTED`. | Step 2: `409 ACTIVE_SESSION_EXISTS`. Step 3: `201` with a **new** session S2, no replay header. Balance 80, **two `BET` records of 10**. A client that retries after a lost response is charged twice. |
| BUG-03 (High) | Session start does not detect a reused key (other operation, other game or other bet) | GEN-10, GEN-13, SES-06 (step 3) | Player P, balance 200; deposit 50 with key D → `200`. Checks: CL-088, CL-090, CL-062 | 1. `POST /api/sessions` with key **D** (the deposit key), `{"playerId": P, "game": "slots", "bet": 50}`.<br>2. Finish it. `POST /api/sessions`, key K, `{"game": "slots", "bet": 5}` → `201`; then key K with `{"game": "roulette", "bet": 5}`.<br>3. Key K with `{"game": "slots", "bet": 0.5}`. | Step 1: `422 IDEMPOTENCY_KEY_REUSED` (one key space per player). Steps 2 and 3: `422 IDEMPOTENCY_KEY_REUSED` (the key check comes before the active-session and bet-range checks). Nothing changes. | Step 1: `201`, a new session, bet 50 charged. Step 2: `409 ACTIVE_SESSION_EXISTS`. Step 3: `422 BET_AMOUNT_OUT_OF_RANGE`. |
| BUG-04 (Critical) | A withdrawal is allowed while the player has an `ACTIVE` session | WDR-03, WDR-06 | Player P, balance 100 (deposit 100). Checks: CL-045, CL-052; secondary failures: CL-099 (GEN-15), CL-128 (GEN-26). Reproduced manually: player 379 | 1. `POST /api/sessions`, key K1, `{"playerId": P, "game": "slots", "bet": 10}` → `201 ACTIVE`.<br>2. `POST /api/players/P/withdraw`, key K2, `{"amount": 20}`.<br>3. With the session still active: `POST /api/players/P/withdraw`, key K3, `{"amount": 1000}`. | Step 2: `409 ACTIVE_SESSION_EXISTS`; balance 90, no `WITHDRAWAL` record, no event, no `POST /payouts`. Step 3: `409 ACTIVE_SESSION_EXISTS` (active session is checked before balance). | Step 2: `200`, balance 70, a `WITHDRAWAL` record, a `WITHDRAWAL` event, the payout is sent to the provider. Step 3: `422 INSUFFICIENT_FUNDS`. A player can cash out while a bet is in play. |
| BUG-05 (High) | No 5-second timeout on the payment provider: the request waits for a slow provider and succeeds | GEN-18, GEN-19 | Player P, balance 200. Slow stub: `POST http://localhost:8089/__admin/settings` `{"fixedDelay": 7000}` (restore: `{"fixedDelay": 0}`). Checks: CL-114, CL-115 | 1. `POST /api/players/P/deposit`, key K, `{"amount": 50}`; measure the response time.<br>2. `POST /api/players/P/withdraw`, key K2, `{"amount": 50}`; measure.<br>3. Restore `fixedDelay: 0`. | `504 PAYMENT_TIMEOUT` no later than 6 s after sending. Balance, journal and events unchanged, also after the provider answers at 7 s; the key is not consumed. | After ≈ 7.0 s: `200` with the player; balance changed by ±50, a journal record and an event are written. GEN-19 (state after a timeout) cannot be verified because the timeout never happens. |
| BUG-06 (Medium) | A non-numeric id in the path returns `500 INTERNAL_ERROR` instead of `400 BAD_REQUEST` | GEN-06 | None. Check: CL-107 (8 endpoints) | Send each with a valid body/key: `GET /api/players/abc`; `POST /api/players/abc/deposit`; `POST /api/players/abc/withdraw`; `GET /api/players/abc/deposit-limits`; `PUT /api/players/abc/deposit-limits/daily`; `DELETE /api/players/abc/deposit-limits/daily`; `GET /api/sessions/abc`; `POST /api/sessions/abc/finish`. | `400 BAD_REQUEST` with a JSON error body. | `500 {"code": "INTERNAL_ERROR", "message": "unexpected server error"}` on all 8 endpoints. |
| BUG-07 (High) | A non-integer `playerId` is truncated: a session of player N is started and charged | GEN-03, GEN-07 (see OQ-26) | Player P with id N, balance 100. Check: CL-136 | `POST /api/sessions`, key K, `{"playerId": N.5, "game": "slots", "bet": 5}` (e.g. `540.5`). | Refused: `400 VALIDATION_ERROR` (not an id) or `404 NOT_FOUND` (no player has id N.5). Nothing changes. | `201`, session created for player **N** (`"playerId": N`), bet 5 charged from player N. In the first run, `playerId: 1.5` started a session for **seed player 1**. |

## 3. Results summary (key metrics)

| Metric | Value |
|---|---|
| Requirements in scope | 69 (GEN-01…26, DEP-01…05, LIM-01…12, WDR-01…06, SES-01…13, LDB-01…07) |
| Requirements covered by ≥ 1 check / autotest | **69 / 69 (100 %)** |
| Requirements with all checks passed | 53 (76.8 %) |
| Requirements with a failed check | 16: GEN-06, GEN-08, GEN-09, GEN-10, GEN-11, GEN-12, GEN-13, GEN-18, GEN-19, WDR-03, WDR-06, SES-06, GEN-03, GEN-07 (violated or not verifiable) + GEN-15, GEN-26 (failed only as a consequence of BUG-04) |
| Checklist items executed | **136 / 136 (100 %)** — exit criterion met |
| Checklist items passed / failed | 119 (87.5 %) / 17 (12.5 %) |
| Autotests (methods) / test invocations | 136 / 189 (parameterized tests expand) |
| Invocations passed / failed / errors / skipped | 165 / 24 / 0 / 0 |
| Failed P1 checks | 7 of 51 P1 checks (CL-045, 088, 093, 114, 115, 128, 136) |
| Defects | 7: Critical 2 (BUG-02, BUG-04), High 4 (BUG-01, BUG-03, BUG-05, BUG-07), Medium 1 (BUG-06) |
| Open questions (requirements risks) | 26 (OQ-01…OQ-26, §5.3) |
| Stability | 2 full runs; each defect failure reproduced in both. The first run also had 2 test defects (fixed: CL-110 used a sample that depends on BUG-04; CL-135 mixed in the truncation case now covered by CL-136) |

Results by flow:

| Flow | Checks | Passed | Failed | Defects |
|---|---|---|---|---|
| F-01 Deposit | 11 | 11 | 0 | — |
| F-02 Deposit limits | 29 | 29 | 0 | — |
| F-03 Withdrawal | 12 | 10 | 2 | BUG-04 |
| F-04 Game session: bet and win | 16 | 15 | 1 | BUG-03 |
| F-05 Session deadline and expiry | 5 | 5 | 0 | — |
| F-06 Leaderboard | 9 | 9 | 0 | — |
| F-07 Idempotency | 19 | 10 | 9 | BUG-01, BUG-02, BUG-03, BUG-04 |
| F-08 Request validation and errors | 10 | 9 | 1 | BUG-06 |
| F-09 Payment provider integration | 6 | 4 | 2 | BUG-05 |
| F-10 Events | 5 | 5 | 0 | — |
| F-11 Invariants and concurrency | 8 | 7 | 1 | BUG-04 |
| F-12 Security | 6 | 5 | 1 | BUG-07 |
| **Total** | **136** | **119** | **17** | 7 |

## 4. Coverage assessment and criteria

**Criteria applied**

| Criterion | Target | Achieved |
|---|---|---|
| Requirement coverage: every in-scope requirement has ≥ 1 checklist item and autotest | 100 % | 100 % (69/69) |
| Checklist execution (exit criterion) | 100 % executed, regardless of result | 100 % (136/136) |
| Every observation point of SPEC §11 used | REST, journal, Kafka, provider journal, WebSocket | all five used |
| Ranges: lowest/highest accepted, just below/above | every range in DEP-02, WDR-02, LIM-03, SES-03, GEN-09 | all, plus zero, negative and overflow values |
| Order of checks (DEP-05, LIM-05, WDR-06, SES-06) | every adjacent pair | every adjacent pair |
| "Nothing changes" after a refusal | balance + journal + events + provider calls | asserted by a shared snapshot in every refusal check |
| Release gate | 0 open Critical/High defects, 0 failed P1 checks | **not met** (2 Critical, 4 High open, 7 P1 failed) |

**What is well covered and passes:** deposit (incl. provider decline and ordering), the whole deposit-limit
lifecycle (immediate/pending changes, cooling-off, replacement, cancel, removal, rolling 24 h / 7 d / 30 d
windows on the service clock, most-restrictive and tie rules), withdrawal amounts/balance/payout decline,
session bet/win/loss, the 60 s deadline and expiry with refund, leaderboard (computation, freshness,
WebSocket updates, period switch), events (fields, service-clock timestamps, keys, order, no events for
refusals), provider references and outage handling, idempotency of deposit and withdrawal (replay, reuse,
in-progress, key lifetime), concurrency of deposits/withdrawals/sessions/finishes, and the balance = journal
invariant for every player created by the run.

**Partial coverage (and why):**

- Session start idempotency (GEN-08…16 for sessions): BUG-01…03 stop the affected autotests at the first
  violated assertion, so later assertions in the same test (e.g. "session key reused for a deposit" in CL-088)
  were not evaluated. They must be re-verified after the fix.
- GEN-19 (state after a timeout and the late provider answer): not verifiable while BUG-05 is open.
- WDR-03 positive cases (CL-046, CL-051 range before active session) pass trivially while BUG-04 is open: no
  active-session check exists at all. Re-verify after the fix.
- GEN-14 for session start: the start makes no external call, so "in progress" is not observable (OQ-08);
  verified for deposit and withdrawal only.
- LIM-11 "exactly one window length": the clock keeps running in real time, so only "30 s inside / 1 s
  outside" is tested (OQ-14).
- LDB-06 "after the service starts": a restart is forbidden. Covered indirectly: a connected client receives
  nothing while the top does not change (CL-079) (OQ-22).

## 5. Risks and remaining issues

### 5.1 Product risks from open defects

| Risk | Defects | Impact |
|---|---|---|
| Double charge of bets on client retries | BUG-02 (with BUG-01, BUG-03) | Players lose money on network retries; every retry opens a new round. Complaints, chargebacks, regulatory exposure. |
| Cash-out while a bet is in play | BUG-04 | A player can withdraw the balance that a running round depends on; breaks the responsible-gaming and money-safety rule. |
| Hanging payment requests | BUG-05 | Payment calls block for as long as the provider is slow; clients time out and retry, threads pile up; the specified 504 contract is broken. |
| Acting on the wrong account | BUG-07 | `playerId: 1.5` charges player 1: wrong-account debits from malformed or crafted input. |
| 500 on malformed ids | BUG-06 | Wrong error contract, noisy alerts; no money impact. |

### 5.2 Test and environment risks

- **Shared clock.** Each run moves the service clock forward by about 70 days (windows, cooling-off,
  31-day key lifetime, one leaderboard period). This is allowed by SPEC §10 but is visible to anyone using
  the stand. The suite does not depend on the start value.
- **Global stub changes.** CL-096/097 (delay 2.5 s), CL-114/115 (delay 7 s) and CL-116/117 (fault mapping)
  change the stub for a few seconds and restore it in `finally`; the suite must not run in parallel with other
  provider users.
- **Timing-based checks** (SES-11 5 s, LDB-05/07 30 s, the 1 s quiet period for "no event published") can
  become flaky on an overloaded host. No flakiness was observed in 2 runs.
- **Side effect on seed data.** In the first run, the truncation defect (BUG-07) opened a session for seed
  player 1 (bet 5). It expired and was refunded by the service; seed players are excluded from GEN-25. Since
  then the check uses only a player created by the test.
- **Out-of-scope security gaps** (no authentication: any client can finish any session with any win) remain;
  see OQ-17.

### 5.3 Requirements risks — open questions for discussion

"Observed" is what the system does today: background for the discussion, not a verdict.

| ID | Req | Open question | Observed |
|---|---|---|---|
| OQ-01 | GEN-01 | Should a request with a `currency` field (e.g. `"EUR"`) be refused or ignored? "No other currency exists" is not testable as written. | `{"amount": 10, "currency": "EUR"}` is accepted and credited as 10 USD. |
| OQ-02 | GEN-02 | How are decimals counted "as written" for exponent notation; are exponents allowed in requests? | `1E+2` accepted as 100; `1.0001E1` → `400 VALIDATION_ERROR`. |
| OQ-03 | GEN-03, GEN-05 | Format of `details` for `VALIDATION_ERROR`? Code for a malformed JSON body? | `details` = `{field: message}`; malformed JSON → `400 MALFORMED_JSON` (code not in the spec). |
| OQ-04 | DEP-05, WDR-06, SES-06 | Are `IDEMPOTENCY_KEY_REQUIRED/INVALID` part of step 1 (format) or step 3 (key)? | Unknown player + missing key → `400 IDEMPOTENCY_KEY_REQUIRED` (treated as format). |
| OQ-05 | GEN-12 | Header on the original response (`false` or absent)? "Same body": byte-identical or equal by value? | Header absent on the original; replay bodies equal. |
| OQ-06 | GEN-09 | Is space (0x20) printable? A key of spaces reaches the service trimmed. DEL is refused by the web container with an HTML 400 — acceptable under GEN-04? | Key `" "` → `400 IDEMPOTENCY_KEY_REQUIRED`; DEL → Tomcat HTML page. |
| OQ-07 | GEN-15, GEN-17 | After a refusal, a retry with the same key but a **different amount** sends the same `reference`? A de-duplicating provider would reject or mis-apply it. | Not observed (only same-amount retries tested). |
| OQ-08 | GEN-14 | "In progress" is not observable for session start. Same-key request with a different fingerprint while in progress: `409`? | Deposit/withdrawal: `409 IDEMPOTENCY_KEY_IN_PROGRESS` for both (CL-096/097). |
| OQ-09 | GEN-16 | Unlimited key lifetime means unbounded storage: is a retention period acceptable? | Replay works after 31 days. |
| OQ-10 | GEN-18, GEN-19 | A late provider success after `504` takes money without crediting it until reconciliation — accepted? How should a provider `5xx` be classified (`422` or `502`)? | No timeout exists (BUG-05). |
| OQ-11 | LIM-04 | "Pending removals take no part": is only the pending (null) value ignored, or the whole period? | The current value of a period with a pending removal is still compared (daily 100 pending removal → weekly 50 refused). |
| OQ-12 | LIM-04, LIM-12 | Types of `details.limit`, `remaining`, `conflictingAmount`? | Strings, e.g. `"100.00"`. |
| OQ-13 | LIM-07, LIM-10 | Precision of `effectiveAt`; is a pending change applied exactly at `effectiveAt`? | Applied by `effectiveAt + 1 s`; `effectiveAt` within the tested ±5 s of request time + 24 h. |
| OQ-14 | LIM-11 | "Exactly one window length" cannot be verified black-box. | — |
| OQ-15 | LIM-06/07 | Result of a PUT equal to the current amount with nothing pending? | Not observed. |
| OQ-16 | WDR-02 | A balance below 10.00 can never be withdrawn — accepted business rule? | Confirmed (CL-044). |
| OQ-17 | SES-07 | `win` is unbounded and client-reported: maximum win? Behavior above storage precision? | A win of several thousand on a bet of 1 is credited; `99999999999` → `400 VALIDATION_ERROR` (storage bound). |
| OQ-18 | SES-07, SES-09 | Finish has no idempotency: a client retrying after a lost response gets `409` and cannot tell if it succeeded. | `409 SESSION_NOT_ACTIVE` on the second finish. |
| OQ-19 | SES-07, GEN-07 | Order of checks for finish is unspecified. | Unknown session + negative win → `400 VALIDATION_ERROR`. |
| OQ-20 | SES-11 | Only a lower bound for `expiredAt`. | Expiry observed within ≈ 1 s. |
| OQ-21 | LDB-01 | Order of equal `netResult` entries? | — |
| OQ-22 | LDB-06 | The "after the service starts" clause is untestable without a restart; a new client receives nothing until a change — intended? | Nothing is sent to a new client (CL-079). |
| OQ-23 | LDB-05, LDB-06 | `GET` is cached: may it lag the last WebSocket message within 30 s? | Equal in this run (CL-077). |
| OQ-24 | GEN-21, GEN-24 | Formats of `eventId` and `at`; no latency bound for most events. | `eventId` non-empty string, `at` ISO-8601 instant; events arrive in < 1 s. |
| OQ-25 | GEN-25 | How are seed players identified? | Ids 1–200, usernames `seed_player_<n>`. |
| OQ-26 | GEN-03, GEN-07 | Must `playerId` be an integer; is `1.5` a `400` or a `404`? | Truncated to 1 (BUG-07). |

## 6. Final QA conclusion

**The system is not ready for release (No-Go).**

The deposit flow, deposit limits, game rounds, expiry, the leaderboard, events, outage handling and the
balance = journal invariant behave as specified, and the regression suite covers 100 % of the in-scope
requirements, with 100 % of the checklist executed.

Two core money-safety rules are broken:

- **Retries double-charge bets.** Session start has no idempotency (BUG-01…03); a client retry charges the
  bet again (BUG-02, Critical).
- **Cash-out during a bet.** A withdrawal is not blocked while a bet is in play (BUG-04, Critical).

Further defects:

- The payment provider is called without the specified 5 s timeout (BUG-05, High).
- A non-integer `playerId` debits another player's account (BUG-07, High).
- Malformed ids return 500 (BUG-06, Medium).

**Conditions for a Go:**

1. BUG-01…BUG-05 and BUG-07 are fixed, and BUG-06 is fixed or explicitly accepted.
2. The full suite passes with 0 failures. The checks listed under "partial coverage" (§4) that were masked by
   these defects are re-verified.
3. Product decisions on the open questions with money impact: OQ-07, OQ-10, OQ-11, OQ-17, OQ-18, OQ-26.
