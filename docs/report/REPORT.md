# Report: what an AI-generated API test suite catches, and what the human adds

Experiment run 13–29 October 2026. One system, one frozen specification, 16 seeded bugs, three test suites written
by AI agents. Every number below comes from a run record; where a number was not recorded, the report says so.

## 1. Summary

| Suite | What produced it | Seeded bugs caught | Bugs with no twin among the control mutants |
|---|---|---|---|
| **A** | an agent with a three-line task and the code, no specification | **12 / 16** | 5 / 7 |
| **B** | an agent with the specification and a task that names risks and deliverables | **15 / 16** | 6 / 7 |
| **C1** | B after agent-run checks, a calibrated audit and an agent that fixed the findings | **16 / 16** | 7 / 7 |
| **C2** | C1 after the author's review of meaning | 16 / 16 (same commit as C1) | 7 / 7 |

- **The task and the specification were worth more than the control (A → B: +3; B → C1: +1).** Without a
  specification the agent made the source code its oracle and pinned real defects as correct behavior. With the
  specification it caught all four natural bugs of the system instead of one.
- **"Some AI tests check nothing" holds, but not as tests with no assertions.** In suite B, 56 of 136 tests (41 %)
  got at least one finding. The typical defect was a check that observes less than its name claims: "nothing
  changed" read on a few fields, "exactly one event" accepted as "at least one", a value polled until it matches and
  then asserted. One of these hid the only bug B missed (B15). The fix of that one finding caught it.
- **Findings cluster by cause.** 30 of the 66 test findings were one pattern (a "before" snapshot taken before the
  setup's own events arrive), and the fixing agent repaired it once, in a support helper. The control produced 74 findings; the fixer applied 73 and declined one with a reason
  from the specification.
- **Agents checking agents also make mistakes, and the human sits exactly there.** The answer key an agent wrote for
  calibrating the checks was wrong in 8 of 63 cells, all in the same direction. A calibrated check later passed a
  test it had flagged four times out of four on the sample. Both were caught by a second, independent source and a
  human decision, not by another agent.
- **Cost.** Writing suite B took one agent session: 37 min, $9.27 (API-price equivalent). The control added $39.23
  per suite plus $32.86 of one-time setup, and the measurement used no model tokens. All model spend of the
  experiment, pilot included: $104.59.

The full catalog of AI test-writing mistakes, each with a real example: [AI-MISTAKES.md](AI-MISTAKES.md).

## 2. Question and design

AI agents write tests quickly. The question is how much of that volume can be trusted, and what a QA engineer has
to contribute before and after the agent works. Three branches separate the two contributions:

- **A — minimal task.** What a busy developer would write, three lines: "You are a Senior QA Engineer agent. Your
  task is to prepare a regression plan and carry out high-quality testing. Write a test report based on the
  results." The agent gets the code and the running system, not the specification.
  ([prompt](../../experiment/prompts/A.md))
- **B — the author's task.** The specification is the source of truth, not the code or the current behavior; a test
  plan with risk categories, a checklist mapped to requirements, automated API tests with a requirement tag on every
  test, a report with open questions. No human in the loop. This is the honest "AI only".
  ([prompt](../../experiment/prompts/B.md))
- **C — B after control.** C1 after the agent-run and script checks; C2 after the author's review of meaning.

A → B measures the value of the specification and the task; B → C measures the value of control. Both A and B
received the same 32-line harness block: where the tests go, how to run them, the environment, and the rule "do not
adjust a red test to the system" ([harness](../../experiment/prompts/harness.md)).

Methodological rules fixed in advance:

1. **Bugs before tests.** The seeded bugs were described and committed as checksums
   ([`seeded-bugs.sha256`](../../experiment/seeded-bugs.sha256), commit `6412e54`, 15 Oct) three days before
   generation (18 Oct). The patches are published in [`experiment/seeded-bugs/`](../../experiment/seeded-bugs) and
   match those checksums.
2. **No suite saw the bugs until the control was finished.** A, B and C were not run against the seeded bugs before
   the C2 tag. The measurement rule was committed before the first measured run
   ([`MEASURE.md`](../../experiment/MEASURE.md), `6fb8a90`).
3. **Control is independent of the bug catalog.** The faulty versions used by the control (mutants) were written by
   a separate agent that never saw the catalog: one patch per requirement. Pairs of a mutant and a seeded bug with
   the same requirement and the same observable violation ("twins") were marked only after the C2 tag, and B → C is
   reported both over all 16 bugs and over the 7 without a twin.
4. **Isolation.** Every agent worked in a fresh snapshot outside this repository (`git archive` of a whitelist, one
   `Import` commit, no history), with no `CLAUDE.md`, plugins, hooks or MCP servers (`--safe-mode`). Every transcript
   was searched afterwards for references to the experiment. Section 10 lists what this did not cover.
5. **One model, recorded.** Claude Code CLI 2.1.302, model `claude-opus-5-5`, effort `xhigh`, permission mode `auto`,
   no human interventions in any generation run.

## 3. System under test and specification

A small real-money gaming wallet: `player-service` (players, deposits, withdrawals, idempotency keys, deposit
limits, game sessions, leaderboard), `analytics-service`, Postgres, Kafka, Redis and a WireMock payment provider. It
is a training system, the object of testing here, not the subject of the project.

The specification ([`docs/spec/SPEC.md`](../spec/SPEC.md), frozen as `spec-v1`) has 69 numbered requirements:
general rules (GEN, 26), deposit (DEP, 5), deposit limits (LIM, 12), withdrawal (WDR, 6), game session (SES, 13) and
leaderboard (LDB, 7). Observation points are REST, Kafka, WebSocket and the database read-only. Before the
specification, an agent explored the running system and wrote 47 questions; the author decided 7 findings,
54 questions and 28 clarifications. The text was then written by Claude strictly from those decisions.

The system keeps four **natural bugs** on purpose: the specification states the expected behavior and the code does
not meet it — withdrawal during an active game session (WDR-03), no 5-second provider timeout (GEN-18/19), no
idempotency key on session start (GEN-08), and a non-numeric id answered with 500 (GEN-06). Ten **probes** are
behaviors the specification deliberately leaves open, to see what agents do with uncertainty.

## 4. Seeded bugs

Sixteen bugs, each a patch to `src/main` that applies on its own. Claude proposed 30 candidates; the author chose 16
and changed none (section 10). Every bug breaks at least one numbered requirement, leaves the main path working, and
is observable through the allowed points.

| Bug | Tag | Requirement | What breaks | Kind | A | B | C1 |
|---|---|---|---|---|---|---|---|
| B01 | money | GEN-11, GEN-12 | Idempotency fingerprint compares amounts with `equals`: `50` then `50.00` with one key → `422 IDEMPOTENCY_KEY_REUSED` instead of a replay | number representation | ✓ | ✓ | ✓ |
| B02 | money | DEP-05 | Limit checked before the amount range: exhausted limit and a deposit below the range → limit error instead of range error | order of checks | — | ✓ | ✓ |
| B03 | money | GEN-22, WDR-05 | `WITHDRAWAL` event published for a payout the provider declined | async | ✓ | ✓ | ✓ |
| B04 | money, igaming | SES-09, GEN-26 | Session finish without a lock: parallel finishes credit the win several times | race (3 runs) | ✓ | ✓ | ✓ |
| B05 | igaming | LIM-08 | Repeating the same pending increase restarts the cooling-off | time | ✓ | ✓ | ✓ |
| B06 | igaming | LIM-11 | Withdrawals free deposit limit | combination | ✓ | ✓ | ✓ |
| B07 | igaming | LIM-11 | Monthly window is 31 days instead of 30 | time | ✓ | ✓ | ✓ |
| B08 | igaming | LIM-12 | Refusal names the first exceeded period, not the most restrictive one | response details | — | ✓ | ✓ |
| B09 | igaming | LIM-04 | Period order ignores pending values | combination | ✓ | ✓ | ✓ |
| B10 | money | SES-11, GEN-25 | Expiry refunds the bet without a `REFUND` journal record | database invariant | ✓ | ✓ | ✓ |
| B11 | igaming | SES-04, SES-12 | An expired session blocks starting a new one | time | ✓ | ✓ | ✓ |
| B12 | money | SES-08 | A loss writes a `WIN` record of 0.00 | database | ✓ | ✓ | ✓ |
| B13 | igaming | GEN-24 | `game-events` keyed by session id instead of player id | async, message key | ✓ | ✓ | ✓ |
| B14 | igaming | LDB-04 | A player with a zero net result is listed in the top | boundary | — | ✓ | ✓ |
| B15 | igaming | LDB-03, SES-12 | The leaderboard counts expired sessions | combination, time | — | — | ✓ |
| B16 | money | GEN-03 | Amounts sent as strings are accepted | format | ✓ | ✓ | ✓ |

## 5. Generation: suites A and B

| | A | B |
|---|---|---|
| Agent time / cost | 28 min / $7.35 | 37 min / $9.27 |
| Output tokens (of which thinking) | 161k (64k) | 232k (100k) |
| Human interventions | 0 | 0 |
| Tests (executions) / classes | 152 (152) / 13 | 136 (189) / 15 |
| Red on the base system / distinct causes | 9 / 1 | 24 / 7 |
| False failures against the specification | 0 (6 of the 9 red cases expect behavior the spec leaves open or out of scope) | 0 (one disputed: `playerId: 1.5`, which the spec does not settle and the agent raised as an open question) |
| Natural bugs caught | 1 of 4 | 4 of 4 |
| Probes: pinned current behavior / flagged as open / assumed an expectation / did not notice | 2 (+1 partly) / 3 / 2 / 2 | 0 (+1 flagged and pinned in one test) / 5 / 0 / 5 |
| Oracle the agent chose | the source code ("used as the specification") | the specification |

**A.** The agent read the whole code base before planning and wrote in its plan that the source code "is used as
the specification". It found one defect class (client errors answered with 500) and pinned other defects as correct:
its concurrency test expects `INSUFFICIENT_FUNDS` when a bet and a withdrawal race, where the specification requires
the withdrawal to be refused with `409 ACTIVE_SESSION_EXISTS` while a session is active. Its report says the final
suite "ran four times in a row" with identical results; the transcript shows the suite changed between those runs
(138/10 → 146/9 → 152/9 → 152/9).

**B.** A 391-line test plan (checklist CL-001…CL-136 over 12 flows, requirement → item → test matrix), a 364-line
report with 7 defects, 26 open questions and a No-Go verdict, and 136 tests with a requirement tag on each. All four
natural bugs were caught. Half of the probes went unnoticed, although the task asks to record requirements risks as
open questions. The suite also followed a self-contradictory recipe of the specification literally (section 10).

A first run of A (217 tests, 3 defect causes, $9.17) was replaced because the running system exposed a path to the
measurement copy of this repository; it is outside the table. The two runs of the same task differ noticeably:
generation is not deterministic, and every branch here has one measured run.

## 6. Control: from B to C1 and C2

### 6.1 Checks

An agent designed the checks and a calibration sample from a task, without seeing the bug catalog; the author
decided its 20 open design choices (section 10). Seven checks, three run by agents and four by scripts
([`CHECKS.md`](../../experiment/control/CHECKS.md)):

| Check | Finds | Performer | Result on B |
|---|---|---|---|
| CHK-1 | Empty checks: a test that can pass although the system breaks what its name claims | agent | 22 findings (over-permissive assertion 13, claimed point not observed 9) |
| CHK-2 | Instability: 10 runs, 5 on a fresh system and 5 on an aged one | script | 0 unstable |
| CHK-3S | Requirement tags and coverage | script | 0 findings, all 69 requirements tagged |
| CHK-3A | Link to the requirement in content: wrong tag, expectation that contradicts or exceeds the spec | agent | 9 findings (expectation beyond the spec 7, tag not exercised 2); 0 contradictions |
| CHK-4 | Failure on faulty versions: 64 mutants, one per requirement | script over a run matrix | 56 caught by their own tests, 4 only by other tests, 4 by no test |
| CHK-5 | Unstable by construction: premature reads of async effects, unrestored state, timing | agent | 35 findings (premature observation 32, timing 3) |
| CHK-6 | Order dependence | script | 0 |

### 6.2 Calibration and audit

Each agent check had to pass a calibration sample before it was used: 21 tests, 13 with a known defect and 8
without. In round 1 all 13 defective tests were caught in both repetitions, but 8 "false" flags appeared. Reading
the agents' evidence showed the key was wrong, not the checks: the key's author (the design agent) had missed real
defects in 8 of 63 cells, all in the direction of "this test is fine". The author accepted a written adjudication;
the key itself was not edited. CHK-1 was still inconsistent between repetitions, so its brief got one revision in
general terms (no examples from the sample). Round 2: 21 of 21 for all three checks in both repetitions. Calibration
cost $14.03.

On suite B the three checks produced 408 verdicts (136 tests × 3). Instead of rereading all of them, the author
audited 59: every verdict where CHK-1 disagreed with the mutant results (25), plus a random sample of each check.
Disagreement with the agents: **0 of 59**. One disagreement the audit did not need to settle was found by the
script: `GameSessionTest#finishWithWin` is byte-for-byte a sample test that all four calibration sessions flagged,
yet on the suite CHK-1 passed it; mutant M42 confirmed the defect.

### 6.3 Fixing and review

The findings went to a fixing agent in a fresh folder, with one rule: the specification is the oracle; change only
the tests; never delete or disable a test; keep the 17 stably red tests red for the same reason. Result in 33 min
and $10.23, with no questions and no interventions: 73 of 74 findings fixed, one declined with a reason (two checks
gave opposite directions for one test; the fixer followed the specification). Common causes were fixed once in
support code: all 32 premature-observation findings by one helper that waits for the setup's own events. The suite
grew to 137 tests (190 executions). One test, rewritten because it demanded more than the specification, now checks
the status code the specification does require and turned red on a natural bug (GEN-06), which is correct.

The author then reviewed C1 against the specification for meaning: 30 minutes, 0 findings. C2 is therefore the
same commit as C1. This report cannot tell apart "C1 had nothing left to find" from "30 minutes was not enough to
find it": C1 already catches every seeded bug.

## 7. Measurement

The rule ([`MEASURE.md`](../../experiment/MEASURE.md)): every suite runs on a fresh system once per bug (B04, a race,
three times); a bug is caught when an execution that is stably green on the base system fails with the bug, or a
stably red one fails with a different first line. Tests that are red on the base with a varying message do not
count for any bug. The run took 184 minutes of system time and no model tokens; all 57 columns were valid.

Every catch was "green on the base → red with the bug"; no catch depended on comparing failure messages.
Results per suite: [`results/measure/`](../../results/measure).

**A → B (+3: B02, B08, B14).** A never combined two violations in one request (B02), used test data in which the
first exceeded limit was also the most restrictive (B08), and its check for "net result zero is not listed" ran on a
top already filled by ten winners (B14). Details in [AI-MISTAKES.md](AI-MISTAKES.md).

**B → C1 (+1: B15).** B's `LeaderboardTest#netResult` polled the leaderboard until the expected value appeared and
then asserted that value. With B15 the service shows the right value first and the wrong one at the next refresh, so
the test passed. CHK-1 flagged it ("claimed point not observed"); the fix reads the top again after the 30-second
freshness bound. C1 lost no catch of B: for every bug, B's detecting tests are a subset of C1's. B10 is detected by
7 tests in C1 instead of 3.

**Twins.** 9 bugs have a twin among the control mutants (B01, B02, B05, B06, B09, B11, B12, B13, B14), 7 do not. B15
has none, so the one gain of the control did not come from the mutants. Two partial overlaps are not counted as
twins because the observable input differs, and are named here: on the input of B04 (ten parallel finishes) mutant
M44 gives the same wrong result, but not the other way round; the patch of B16 also accepts strings in integer
fields, so on the input of M03 it gives the same wrong result, but not the other way round. Neither changes B → C.

| Subset | A | B | C1 | C2 |
|---|---|---|---|---|
| All 16 | 12 | 15 | 16 | 16 |
| With a twin (9) | 7 | 9 | 9 | 9 |
| Without a twin (7) | 5 | 6 | 7 | 7 |

## 8. Reading the numbers

- **Seeded bugs are a coarse instrument for control.** B already caught 15 of 16, so the control could add at most
  one. What the control changed is visible in the suite itself: 73 test defects fixed; the 4 requirements on which
  a mutant passed every test (LIM-10, SES-07, LDB-01, GEN-10) got strengthened tests; B10 is caught by 7 tests
  instead of 3. Whether those strengthened tests now catch their mutants was not measured (the mutant matrix was
  not re-run on C1), and the 35 "unstable by construction" findings were never reproduced in 10 runs: these are
  claims of the control, not measurements.
- **The specification moved more than the control.** The minimal task did not fail by writing fewer tests (A wrote
  152 test methods, B 136). It failed by choosing the code as the oracle: rules that exist only as code order or as one branch
  of a condition were either not tested or were tested with data that could not tell a right implementation from a
  plausible wrong one.
- **With an oracle, the errors move from "wrong expectation" to "weak observation".** B's CHK-3A found no expectation
  that contradicts the specification; CHK-1 found 22 checks that observe less than they claim. The hypothesis that
  empty checks come mostly from tasks without an oracle could not be tested: the control ran on B only.

## 9. Cost and time

Model cost is the API-price equivalent reported by Claude Code (`cost-state`); runs used a subscription. Human time
is the author's, as recorded in the run log.

| Item | Agent time | Model cost | Author's time |
|---|---|---|---|
| Specification (exploration, decisions, text) | one session | ≈ 40 % of a 5-hour Opus usage window; no dollar figure | 120 min recorded |
| A — generation | 28 min | $7.35 | 45 min |
| B — generation | 37 min | $9.27 | 50 min |
| Control, one-time: mutants | 41 min | $9.23 | 50 min |
| Control, one-time: design of checks and sample | 34 min | $9.60 | 90 min |
| Control, one-time: calibration, 2 rounds | 8 sessions | $14.03 | not recorded |
| Control, per suite: agent checks on B (13 sessions) | 108 min of sessions | $29.00 (of which $3.04 lost to a usage-limit abort) | — |
| Control, per suite: script checks | 27 min of system time | $0 | — |
| Control, per suite: audit of 59 verdicts | — | $0 | not recorded |
| Control, per suite: fixing agent → C1 | 33 min | $10.23 | 49 min (estimate: agent time + 50 %) |
| Review of meaning → C2 | — | $0 | 30 min |
| Measurement | 184 min of system time | $0 | launch only |
| Out of the table: pilot, first run of A | 28 + 41 min | $6.71 + $9.17 | 60 + 60 min |

Per caught seeded bug: A $0.61 (12 bugs), B $0.62 (15 bugs) in generation cost. The control's one extra bug cost
$39.23 of per-suite control plus $32.86 of one-time setup. Priced by seeded bugs alone the control looks expensive;
the same money bought 73 fixed test defects, and the one-time part is reusable for the next suite.

## 10. Limitations

- **One measured generation run per branch.** Two runs of task A differed (217 tests and 3 causes against 152 and
  1). A → B and B → C are differences between single runs.
- **Sixteen bugs, one system, one model family.** The bugs were proposed by Claude and chosen by the author without
  changes; the tests, the mutants and the agent checks come from Claude models too. Shared blind spots are possible.
  The bug patches were written by Claude from the catalog descriptions and accepted by the author.
- **Authorship of texts given to agents.** The specification text, the harness block, the English versions of
  prompts A and B, and the tasks for the mutant, control-design and fixing agents were written by Claude from the
  author's decisions. The author of prompt B and of the fixer's task knew the bug catalog; the rule was that every
  sentence refers to a requirement or general practice, never to a specific bug. The fixer's task contains one
  general hint ("several findings may share one cause in a helper") written after seeing such a pattern.
- **Who designed the control.** The original plan had the author design the checks. In the end an agent designed
  them; the author asked Claude for advice on all 20 design decisions and accepted it (18 recommendations of the
  agent, 2 variants of Claude, none of his own).
- **This report.** The text of this report, the mistake catalog and the README was drafted by Claude from the run
  records and the author's decisions; the author reviewed it. The practice-journal entries that the catalog draws
  on were also written by Claude.
- **Isolation residue.** The agents ran under the author's user account and could technically read the home
  directory; isolation rested on removing pointers and auditing every transcript afterwards (no access found). The
  environment block of every agent listed four directory paths of an unrelated project (paths only, never opened).
  Agents could use `docker`; one read Kafka through `docker exec`.
- **Specification defects.** Section 10 of the specification gives a stub-recovery recipe that also clears the
  stub's request journal, which the same section says not to clear; suite B followed the recipe. Whether a
  non-integer `playerId` is a format error or an unknown player is not specified.
- **Seed data.** Generation ran on a base with random seed data; the measurement ran on the same code with a fixed
  seed. B's base result on both was identical (189 executions, the same 24 red).
- **Unrecorded human time** for prompt writing, bug selection, calibration adjudication and the audit.

## 11. Pilot

Before the main runs, task B was tried on one flow (withdrawal) on the bug-free system: 28 min, $6.71, 80 tests, 4
defects, all real violations of the specification, no false ones. The prompt did not change; the procedure did:
a fresh system and a state check before every run, no IDE open on the snapshot. The pilot suite was never run
against the seeded bugs and is not in the table.

## 12. Artifacts

| What | Where |
|---|---|
| Specification | [`docs/spec/SPEC.md`](../spec/SPEC.md) |
| Prompts and harness | [`experiment/prompts/`](../../experiment/prompts) |
| Seeded bugs (patches) and their checksums | [`experiment/seeded-bugs/`](../../experiment/seeded-bugs), [`seeded-bugs.sha256`](../../experiment/seeded-bugs.sha256) |
| Suites A, B, C1 as delivered by the agents | [`results/suites/`](../../results/suites) |
| Control design, calibration sample and key adjudication | [`experiment/control/`](../../experiment/control) |
| Control mutants | [`experiment/control/mutants/`](../../experiment/control/mutants) |
| Findings given to the fixing agent | [`results/control/findings.json`](../../results/control/findings.json) |
| Fixer's account of every finding | [`results/suites/C1/api-tests/FIXES.md`](../../results/suites/C1/api-tests/FIXES.md) |
| Measurement rule, scripts, results | [`experiment/MEASURE.md`](../../experiment/MEASURE.md), [`experiment/`](../../experiment), [`results/measure/`](../../results/measure) |
