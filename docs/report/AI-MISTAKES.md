# Catalog of AI test-writing mistakes

Every entry is a mistake an agent actually made in this experiment, with the test it is in and what it cost. Code
excerpts are shortened from the delivered suites in [`results/suites/`](../../results/suites). Finding IDs
(`F-…`) refer to [`results/control/findings.json`](../../results/control/findings.json).

Each mistake has a **cause**:
- **task did not ask** — the task or its inputs gave the agent no way to know better;
- **agent ignored the specification** — the specification says what to expect and the test does not check it;
- **agent's own** — neither the task nor the specification explains it.

| ID | Mistake | Suite | Cost in this experiment | Cause | Caught by |
|---|---|---|---|---|---|
| [AIM-01](#aim-01) | The code is taken as the oracle | A | Real defects pinned as correct; B02 missed; 1 of 4 natural bugs | task did not ask | comparison with the spec |
| [AIM-02](#aim-02) | Test data that cannot tell right from plausibly wrong | A | B08 missed | task did not ask | seeded bug |
| [AIM-03](#aim-03) | A negative check made vacuous by the test's own setup | A | B14, B15 missed | agent's own | seeded bug |
| [AIM-04](#aim-04) | Poll until the expected value, then assert it | B | B15 missed | agent ignored the spec | CHK-1 |
| [AIM-05](#aim-05) | "Nothing changed" checked on a subset | B | 9 findings | agent ignored the spec | CHK-1, CHK-4 |
| [AIM-06](#aim-06) | "Exactly one" checked as "at least one" | B | 3 findings | agent ignored the spec | CHK-1 |
| [AIM-07](#aim-07) | An assertion wider than its claim | B | 7 findings | agent ignored the spec | CHK-1 |
| [AIM-08](#aim-08) | A baseline taken before the setup's own async effects | B | 32 findings, 30 of them one pattern | agent ignored the spec | CHK-5 |
| [AIM-09](#aim-09) | Expectations the specification does not make | B | 7 findings | agent ignored the spec | CHK-3A |
| [AIM-10](#aim-10) | A requirement tag the test does not exercise | B | 2 findings | agent's own | CHK-3A |
| [AIM-11](#aim-11) | A requirement no test can fail on | B | 4 mutants caught by no test, 4 only by other tests | agent ignored the spec | CHK-4 |
| [AIM-12](#aim-12) | An overstated claim about its own verification | A | false statement in the report | agent's own | transcript audit |

The pattern behind the table: **without an oracle (A), the agent got expectations wrong; with an oracle (B), it got
observation wrong.** B's suite had no expectation that contradicts the specification (CHK-3A: 0), but 22 checks
that observe less than their names claim. A's suite was not run through the control, so the rate of empty checks
in A is unknown.

---

<a id="aim-01"></a>

## AIM-01 The code is taken as the oracle

**Suite A.** With no specification, the agent read the whole code base before planning and declared it the oracle.

> The source code in this repository (code, Javadoc, comments, migrations, OpenAPI) is used as the
> **specification**. — `results/suites/A/api-tests/REGRESSION_PLAN.md`

The consequences are in the suite. A bet and a withdrawal race for the same money, and the test expects the loser
to get `INSUFFICIENT_FUNDS`:

```java
// A: ConcurrencyTest, CON-07 "a bet and a withdrawal racing for the same money: only one succeeds"
List<Http.Resp> results = parallel(2, i -> i == 0
        ? startSession(player.id(), "blackjack", "60.00")
        : withdraw(player.id(), "60.00", newKey()));
assertThat(results).filteredOn(r -> r.status() / 100 != 2)
        .allSatisfy(r -> assertThat(r.code()).isEqualTo("INSUFFICIENT_FUNDS"));
```

The specification (WDR-03) says a withdrawal is refused with `409 ACTIVE_SESSION_EXISTS` while a session is active.
The system does not do that (a natural bug), and the test pins the defect as correct: when the bet wins the race,
the expected answer is the wrong one. Two of the ten probes — behaviors the specification deliberately leaves
open — were turned into fixed rules the same way: deposits made before a limit existed count against it (Q19), and
a key with spaces is accepted (Q52). A caught 1 of 4 natural bugs; B, with the
specification, caught 4 of 4.

Rules that exist in the code only as the order of statements were not tested at all. Seeded bug B02 swaps two
checks (limit before amount range); A has no test that breaks two rules in one request, so it missed it.

**Cause:** task did not ask — branch A has no specification by design. **Prevent:** give the agent an oracle and say
that the code is not one. Task B did: "The specification is the source of truth for the expected result — not the
code and not the system's current behavior."

<a id="aim-02"></a>

## AIM-02 Test data that cannot tell right from plausibly wrong

**Suite A.** The test is named for exactly the rule seeded bug B08 breaks:

```java
// A: DepositLimitTest#mostRestrictiveLimit, "LIM-09 the most restrictive limit is the one reported"
set(player, "daily", "100.00");
set(player, "weekly", "150.00");
set(player, "monthly", "1000.00");
expect(deposit(player.id(), "100.00", newKey()), 200);
TestClock.advance(Duration.ofDays(1).plusMinutes(1));   // daily remaining 100, weekly remaining 50
JsonNode error = expectError(deposit(player.id(), "60.00", newKey()), 422, "DEPOSIT_LIMIT_EXCEEDED");
assertThat(error.path("details").path("period").asText()).isEqualTo("WEEKLY");
```

A deposit of 60 exceeds only the weekly limit, so "the first exceeded period" and "the most restrictive period" are
the same period. An implementation that reports the first exceeded one (B08) passes. The specification's own
example — daily remaining 50, monthly remaining 0, deposit 60 — exceeds both and tells them apart; B used it and
caught B08.

**Cause:** task did not ask — without the specification the agent had no example designed to discriminate.
**Prevent:** for every rule that chooses between candidates, the data must contain at least two candidates that the
rule ranks differently from the obvious wrong rule.

<a id="aim-03"></a>

## AIM-03 A negative check made vacuous by the test's own setup

**Suite A.** In a fresh leaderboard period the test creates a player whose net result is zero and one with only an
expired session, and asserts neither is listed. Then it adds eleven more winners:

```java
// A: LeaderboardTest#freshPeriodContents
PlayerRef even = newPlayer("100.00");
playRound(even.id(), "10.00", "10.00");                  // net 0
PlayerRef expired = newPlayer("100.00");                 // one session, left to expire
...
for (int net = 10; net <= 110; net += 10) { ... }        // eleven winners with net 10 … 110
JsonNode top = leaderboard();
assertThat(top).hasSize(10);
assertThat(byId).doesNotContainKeys(even.id(), loser.id(), expired.id(), ...);
```

The top holds ten entries, and eleven players have a positive result, so the zero-result player cannot be in it
whether the service lists zero results or not (B14). The expired-session player has nothing but a lost bet, so it
cannot be listed whether expired sessions count or not (B15). Both checks pass on both bugs.

**Cause:** agent's own — the code A used as its oracle says `> 0`, and the agent meant to check it. **Prevent:**
before trusting a negative check, ask whether the setup leaves room for the wrong outcome to happen. A mutant that
breaks exactly that rule answers the question empirically (CHK-4).

<a id="aim-04"></a>

## AIM-04 Poll until the expected value, then assert it

**Suite B — the only seeded bug B missed.**

```java
// B: LeaderboardTest#netResult, "CL-075 netResult = sum(win - bet) over FINISHED sessions;
//    ACTIVE and EXPIRED are not counted"
Api.finishOk(Api.startOk(p, "10").path("id").asLong(), w.toPlainString());
Api.finishOk(Api.startOk(p, "20").path("id").asLong(), "0");
SessionTimingTest.expire(Api.startOk(p, "30").path("id").asLong());
Api.startOk(p, "40"); // stays ACTIVE
BigDecimal expected = w.subtract(new BigDecimal("30"));
JsonNode top = Leaderboard.awaitTop(t -> {
    JsonNode e = Leaderboard.entry(t, p);
    return e != null && Json.sameValue(expected, Json.dec(e.get("netResult")));
}, FRESHNESS);
assertAmount(expected.toPlainString(), e.get("netResult"), "netResult (W - 10 + 0 - 20)");
```

The wait condition is the assertion. The test stops at the first read that shows the right number and then checks
that number. With B15 (expired sessions counted) the right number is already on the board from the earlier finish,
and the expired session enters the sum only at a later refresh, so the test always passes. CHK-1 flagged it (`F-87fb5a5bfc`, "claim point not observed"): "not
counted" can only be observed after an update that follows the expiry. The fixer added a second read once the
30-second freshness bound (LDB-05) has passed since the last session change. C1 caught B15 with this test.

The same shape appears in `LeaderboardPeriodTest#finishedAtDecides` (`F-303aa810dc`): "net ≤ 0 is not listed" is
read on the first poll that shows the winner.

**Cause:** agent ignored the specification — LDB-05 bounds when a change must be visible, which is the moment a
negative claim can be read. **Prevent:** a poll may wait for a precondition; the claim must be read at a moment the
specification guarantees.

<a id="aim-05"></a>

## AIM-05 "Nothing changed" checked on a subset

**Suite B.** The specification lists, for every refusal, what stays unchanged (balance, journal, events, provider
calls, key, session). Several tests check a part of that list and claim the whole:

- `GameSessionTest#negativeWin` (`F-89cbe10939`): "session and balance unchanged" reads the session's status and
  `winAmount` but not `finishedAt`. A service that refuses the request but stamps `finishedAt` passes.
- `SessionTimingTest#finishAtDeadlineRefused` (`F-cc5efca8b0`): "the win is not credited" checks the journal and the
  status; the balance is never read.
- `IdempotencyKeyTest#differentAmount` (`F-1ceaa80246`): a snapshot "before" is taken and never compared.
- `SecurityTest#zeroAndNegativeAmounts` (`F-b492b9bcfd`): the balance is checked once, after a deposit and a
  withdrawal of the same amounts, whose effects cancel out.

The mutant run found one of these the agent check had passed: `GameSessionTest#finishWithWin` checks that the
`SESSION_FINISHED` event has a `finishedAt`, not that it is right; mutant M42, which breaks exactly that field,
passes every test of the suite.

**Cause:** agent ignored the specification. **Prevent:** a shared helper that compares the full unchanged set, and
a review question for every "nothing changes": which item of the requirement's list is not compared?

<a id="aim-06"></a>

## AIM-06 "Exactly one" checked as "at least one"

**Suite B.**

```java
// B: DepositTest#approvedDeposit, "CL-001 approved deposit: ... one DEPOSIT record, one event, one provider call"
assertEquals(1, deposits.size(), "DEPOSIT records");
Events.Event e = Events.awaitOne(Config.PLAYER_EVENTS, p, "DEPOSIT", EVENT_TIMEOUT);
assertAmount("125.50", e.value().get("amount"), "event amount");
assertEquals(1, ProviderStub.count(ProviderStub.PAYMENTS, p), "POST /payments requests");
```

The record and the provider call are counted; the event is not. `awaitOne` returns the first matching event, so a
service that publishes the deposit twice passes (`F-4210ad84c4`; the same in `WithdrawalTest#approvedWithdrawal`).
`ConcurrencyTest#concurrentSessionStarts` claims "exactly one ACTIVE session" and counts `201` answers without ever
reading a session's status (`F-da879650ca`).

**Cause:** agent ignored the specification. **Prevent:** when a claim says "one", every observation point needs a
count, and a wait helper's name should not suggest a count it does not make. The fixer replaced it with
`Events.awaitOnly`: the event, then one second of quiet, then exactly one.

<a id="aim-07"></a>

## AIM-07 An assertion wider than its claim

**Suite B.** The assertion exists, but admits values the claim excludes:

```java
// B: Asserts.assertDetailsMention — "the field name in details"
if (d.has(field)) return;
while (it.hasNext()) {
    if (it.next().getValue().asText("").contains(field)) return;   // "betAmount" contains "bet"
}
```

- Field name in `details` matched by substring (`F-d6efec337c`): `{"field": "betAmount"}` passes for `bet`.
- Error `code` required only to be a non-blank string (`F-630d66bdc3`), where GEN-04 fixes the code of every case.
- "USD only" looks for a foreign currency only under keys whose name contains `currency` (`F-da0942fffb`).
- An "empty top" on the WebSocket also accepts a message with no `top` field at all (`F-7324ee8c77`).

**Cause:** agent ignored the specification. **Prevent:** for every assertion, name one wrong value it would accept;
if one exists and the specification excludes it, narrow the assertion.

<a id="aim-08"></a>

## AIM-08 A baseline taken before the setup's own async effects

**Suite B — the largest single pattern: 30 of 66 test findings.**

```java
// B: WithdrawalTest#outOfRange, "nothing changes"
long p = Api.fundedPlayer("100");          // publishes REGISTERED and DEPOSIT to Kafka, asynchronously
Snapshot before = Snapshot.of(p);          // counts the player's events now
assertError(Api.withdraw(p, key, amount), 422, "WITHDRAWAL_AMOUNT_OUT_OF_RANGE");
before.assertUnchanged();                  // 1 s quiet period: a late DEPOSIT event fails the test
```

The specification sets no delivery bound for events, so a correct service may publish the setup's `DEPOSIT` event
after the snapshot; the "nothing changed" comparison then sees one more event and fails a correct system. On this
system events arrive fast, and 10 runs never showed it (`F-0d9acfe68c` and 29 more). The fixer repaired all of them
with one helper, `Snapshot.afterEvents`, that first waits for the setup's own events.

**Cause:** agent ignored the specification — it assumed a delivery time the specification does not promise, and
knew it did: its own open question OQ-24 notes "no latency bound for most events" and assumes "events arrive in
< 1 s" (see [AIM-09](#aim-09)). **Prevent:** a stable suite on a fast system is not evidence; read every "before"
snapshot against what the specification guarantees about timing.

<a id="aim-09"></a>

## AIM-09 Expectations the specification does not make

**Suite B.** These tests would fail a system that follows the specification:

```java
// B: EventsTest — every player event
assertTrue(e.path("eventId").isTextual() && !e.path("eventId").asText().isBlank(), () -> "eventId: " + e);
```

GEN-21 requires an `eventId` and fixes no type; a numeric id is correct and fails the test (`F-293c1b4bf2`). The
same kind: `netResult` required to be a JSON number (`F-533c02dce4`), the list of limits compared by position
although the specification fixes no order (`F-c4d0e7d6ef`), and a test for "error responses do not disclose
internals", a requirement the specification does not have (`F-b3949173e9`).

The agent saw the gap. Its report lists it as an open question and answers it in the same row:

> OQ-24 | GEN-21, GEN-24 | Formats of `eventId` and `at`; no latency bound for most events. | `eventId` non-empty
> string, `at` ISO-8601 instant; events arrive in < 1 s. — `results/suites/B/api-tests/TEST-REPORT.md`

The assumed answer then went into the assertions as if it were a requirement.

**Cause:** agent ignored the specification — it filled the specification's silence with its own answer.
**Prevent:** an expectation the specification leaves open is an open question, not an assertion. Asking the agent
to record open questions is not enough; the task has to say that an open question is never asserted.

<a id="aim-10"></a>

## AIM-10 A requirement tag the test does not exercise

**Suite B.** `LeaderboardTest#consecutiveMessagesDiffer` is tagged LDB-06 but sends no request of its own: it reads
messages recorded during earlier tests (`F-4cfbe5cb44`). `SecurityTest#nonIntegerPlayerId` is tagged GEN-03, which
is about numbers sent as strings, while the test sends the number `1.5` (`F-3423d209e9`). A traceability matrix
built from tags reports both requirements as covered.

**Cause:** agent's own. **Prevent:** a tag is a claim; check that the test sets up the situation the requirement
governs.

<a id="aim-11"></a>

## AIM-11 A requirement no test can fail on

**Suite B.** Every requirement had at least one tagged test, and the coverage report said 69 of 69. The mutant run
disagreed: for four requirements a patch that breaks only that requirement passed every test of the suite — LIM-10
(removing a limit that does not exist must be `404`), SES-07 (`finishedAt` of the `SESSION_FINISHED` event), LDB-01
(at most 10 entries in the top), GEN-10 (the same key used by two players is two independent keys). For four more,
only tests of other requirements failed.

**Cause:** agent ignored the specification — each of these points is written in it. **Prevent:** coverage by tags
is not coverage; a faulty version per requirement is cheap to run and shows which requirements no test can fail on.

<a id="aim-12"></a>

## AIM-12 An overstated claim about its own verification

**Suite A.** The report says:

> The suite ran four times in a row on the shared stand, with data from earlier runs present and the clock already
> moved forward. Results were identical each time and no test was flaky. — `results/suites/A/api-tests/TEST_REPORT.md`

The transcript shows four runs of four different suites: 138 tests with 10 failures, then 146/9, 152/9, 152/9. The
final suite ran twice.

**Cause:** agent's own. **Prevent:** an agent's statement about what it ran is a claim to check against the
transcript or the CI log, like any other.

---

## Mistakes of the agents that did the control

The control was itself done by agents. Their mistakes matter more, because a wrong check trains everything after it.

| Mistake | Where | What caught it |
|---|---|---|
| **A wrong answer key, all errors in one direction.** The design agent wrote a calibration sample (21 tests) and its key. The checking agents flagged 8 cells the key called clean; their evidence showed real defects in "good" tests. 8 of 63 cells were wrong, all of them "fine" where the test was not. Taken as truth, the key would have tuned the checks to miss real defects. | calibration, round 1 | the author reading the evidence against the key |
| **A calibrated check that misses on the real suite.** CHK-1 flagged `finishWithWin` in 4 of 4 calibration sessions and passed the identical method on suite B. | CHK-1 on B | CHK-4: mutant M42 passed the test |
| **Inconsistency between repetitions.** In round 1, three CHK-1 findings appeared in only one of two repetitions. A brief revision in general terms ("before PASS, go through every claim point") removed it. | calibration | two repetitions per batch |
| **Two checks, opposite directions.** On `SecurityTest#noInternalsDisclosed` CHK-1 asked for a stronger check, CHK-3A for removing an expectation the spec does not have. The fixer chose the specification and said why. | findings for the fixer | the fixer, by the rule "the specification is the oracle" |
| **Rigour without a budget.** Asked to design the control without a cost limit, the design agent proposed about 51 agent sessions. The author's decision cut it to 17 (three checks per session). | control design | the author |
| **A contradiction in the task, followed literally.** The mutant agent's task (written by Claude) said both "skip a requirement the code already fails" and "break one part of a multi-part requirement". The agent took the first literally, left 10 requirements without a mutant, and said so in its final message. One clarifying message added 10 patches. | mutants | the agent's own final message |

## Not mistakes of the agents

- **The specification contradicted itself, and B followed it.** Its recipe for restoring the payment stub also
  clears the stub's request journal, which the same section says not to clear. Agents execute the specification
  literally; it needs the same review as the code.
- **The specification has a gap, and B said so.** It does not say whether `playerId: 1.5` is a format error or an
  unknown player. B wrote a test, and raised the question in its open questions instead of deciding silently.
