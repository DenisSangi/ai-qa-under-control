# Brief revisions after calibration (D12)

`CHECKS.md` stays as the design agent wrote it. A brief revised after a failed calibration is given here in full;
`experiment/review.py` uses the latest revision of a check instead of its brief in `CHECKS.md`. A revision states the
cause in general terms and uses no identifier or example taken from the sample (D12). Any revision is followed by a
full calibration.

## CHK-1, revision 1 (2026-10-20)

Calibration round 1, adjudicated key (`KEY-ADJUDICATION.md`): repetition 1 had no miss, repetition 2 missed three bad
tests (PASS instead of FLAG). In all three, a claim point was taken at face value instead of with the meaning the
specification gives it, or was accepted as observed through an assertion on a neighbouring observation point or on
the mere presence of a value. The revision makes three general rules explicit: a defined term in the claim carries
its full meaning; a point is observed only where it shows and against what the specification fixes; a PASS must
account for every claim point.

```text
CHECK CHK-1: EMPTY CHECKS.
Question: can this test pass although the system breaks what the test claims to check?

1. Claim. The claim is the @DisplayName text after the checklist ID. Split it into claim points: each
   outcome it names (status and error code, a response field, the balance, a journal record, an event,
   a provider call or its absence, the key being consumed or free, a session or limit state, a
   leaderboard or WebSocket content, a time bound). Use the specification to give each point its precise
   meaning in the scenario the test sets up. For example, "nothing changes" after a refusal means what
   the governing requirement lists as unchanged. A word in the claim that the specification defines (a
   term, a state, a field) carries everything the specification attaches to it in this scenario; giving
   it that meaning is not adding a point. Do not add points that the claim does not refer to.
2. Assertions. Find every assertion that can fail the test after the action under test, including those
   in helpers (Asserts.*, Snapshot.assertUnchanged*, Events.await*, fail(...), assertAll). Assertions in
   precondition helpers that set up the scenario (createPlayer, fundedPlayer, depositOk, startOk,
   finishOk, putLimitOk, expire) check the setup, not claim points.
3. Witness. For each claim point, try to construct a witness: a concrete behavior of the service that
   breaks that claim point in this scenario while the test does not fail (it passes, or is skipped or
   aborted). Be concrete, for example: "the service answers 422 BET_AMOUNT_OUT_OF_RANGE and changes
   nothing".
   - Take the test's own exact expected values as the claim. If a wrong service passes only because the
     test expects exactly that wrong value, that is not CHK-1.
   - A claim point is observed only by an assertion on the observation point that shows it, against what
     the specification fixes for it. An assertion on another observation point that usually goes along
     with it does not observe the point (E4). An assertion of presence, type or format does not observe a
     value that the specification fixes (E3).
   - Asynchronous outcomes: assume an effect becomes observable within the test's wait when the wait is
     at least the standard one. Positive Kafka checks: Events.await/awaitOne with EVENT_TIMEOUT (10 s).
     Negative checks (no event, no message): a quiet period of at least 1 s (Snapshot.assertUnchanged
     waits 1 s). Session expiry: 5 s. Leaderboard and WebSocket: 30 s. A negative check with no wait or
     a shorter one does not observe its point (kind E4).
4. Verdict: FLAG if a witness exists for at least one claim point, otherwise PASS. Before you give PASS,
   go through the claim points one by one: PASS only if each of them has an assertion that observes it.
   For this check, the evidence of a PASS lists every claim point with the line that observes it. Kinds:
   E1 subject not asserted: the action under test is performed, but no assertion examines its outcome.
      Only preconditions or unrelated state are asserted.
   E2 no independent expected value: the assertion compares system output with other system output that
      the violation would change in the same way (a second read, another field computed by the same rule,
      a value echoed from the request), or it is a tautology.
   E3 over-permissive assertion: the point is asserted, but the assertion admits a value the claim
      excludes (status without the error code, a range or >= instead of equality, contains or subset
      instead of equality, "one of" several outcomes, a prefix match, a blank value accepted).
   E4 claim point not observed: a point the claim names (for example the journal, events, "provider not
      called", "key not consumed", a session state) has no assertion, or only a negative check that is
      too short.
   E5 neutralized assertion: an assertion that cannot fail the test. It is skipped by an assumption
      (Assumptions.assume*), caught or swallowed, sits in a branch or loop that does not run in this
      scenario, comes after an early return, or runs in an async task whose result is never awaited.
   Put the witness in evidence and the line of the defect in line.

Not in scope: tags, and whether expected values are the specification's (CHK-3A); whether a wait is long
enough for a positive outcome, concurrency, and test-data reuse (CHK-5); outcomes of a requirement that the
claim does not name. Assertions stronger than needed are fine.
```
