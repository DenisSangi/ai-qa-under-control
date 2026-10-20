# Review of the API test suite: checks, findings format, decisions

Object of the review: `api-tests/` (136 autotests, 189 executions; JUnit 5 and REST Assured). Oracle:
`docs/spec/SPEC.md`. This document designs the review. It does not run it.

## 0. Ground rules and terms

- **The specification is the only oracle.** None of these is evidence of correct behavior: the service code, the
  service's current behavior, `TEST-PLAN.md`, `TEST-REPORT.md`, the OpenAPI document, or comments in the tests.
- **Test:** a `@Test` or `@ParameterizedTest` method, identified as `Class#method` (simple class name).
  **Execution:** one invocation of a test (a parameterized test has several).
- **Claim:** the `@DisplayName` text after the checklist ID (`CL-nnn`). **Own requirement** of a test: any ID in its
  `@Tag` set.
- **Base:** the original service. **Faulty version M01…M64:** a build that breaks one requirement, `req(M)`.
- **Green:** pass. **Red:** FAIL or ERROR. **Skip:** skipped or aborted (for example, a failed JUnit assumption).
- **Fresh system:** recreated from scratch, so the database, Kafka, stub and service clock are all reset (about 45 s).
  **Aged system:** a system that earlier runs have already used.
- The review never edits `api-tests/`. All review output goes to a separate workspace, `review/`.

## 1. Checks at a glance

| ID | Check | Finds | Performer | Suite runs | Cost |
|---|---|---|---|---|---|
| CHK-1 | Empty checks | Tests that can pass although the system breaks what the test claims to check | Agent | 0 | 13 sessions (≈ 2–3 h agent time, can run in parallel) |
| CHK-2 | Instability across runs | Tests whose outcome differs between runs of the same system | Script | 10 | ≈ 19 min system time |
| CHK-3S | Link to the requirement: IDs and coverage | Tags that are not spec IDs, untagged tests, duplicate names, requirements without a test | Script | 0 | seconds |
| CHK-3A | Link to the requirement: content | A tag the test does not exercise; expectations that contradict the spec or go beyond it | Agent | 0 | 13 sessions |
| CHK-4 | Failure on faulty versions | Tests green on the faulty version of their own requirement; faulty versions no test catches | Script (over `matrix.csv`) | 65 (base + 64) | ≈ 2 h 26 min system time; analysis in seconds |
| CHK-5 | Unstable by construction | Timing, concurrency, shared-state and environment hazards that runs rarely reveal | Agent | 0 | 13 sessions |
| CHK-6 | Order dependence | Tests whose outcome depends on which classes ran before them | Script | ≈ 2 (15 class runs + 1 reversed run) | ≈ 8 min system time |

The checks are designed so that each test defect belongs to exactly one check:

- CHK-1: the test is too weak. A broken system passes.
- CHK-3A: the test is wrong or mislabelled. A correct system fails it, or it tests a requirement other than its tag.
- CHK-5 and CHK-2: the outcome depends on timing, interleaving or state.
- CHK-4: measures empirically, through the faulty versions, what CHK-1 judges statically.
- CHK-6: measures empirically, through order changes, part of what CHK-5 judges statically.

When an agent verdict and the matching empirical result disagree, the verdict goes to the human audit (§5).

## 2. How the review runs

### 2.1 Order

| Step | What | Uses the system | Depends on |
|---|---|---|---|
| 1 | CHK-3S, which produces `review/tagmap.csv` | no | — |
| 2 | Calibrate CHK-1, CHK-3A and CHK-5 on `control/sample/` (§4) | no | — |
| 3 | CHK-2: 10 suite runs | yes | — |
| 4 | CHK-6: order runs | yes | 3 |
| 5 | Matrix: base and M01–M64 (`matrix.csv`; skip if a current one exists) | yes | — |
| 6 | CHK-1, CHK-3A and CHK-5 on the suite: 13 batches each | no | 2 |
| 7 | CHK-4 analysis | no | 1, 3, 5 |
| 8 | Assembly: merge outputs, select the audit sample, build `review/findings.json` | no | all |
| 9 | Human audit of the selected verdicts (§5); then hand `findings.json` to the fixing agent | no | 8 |

Steps 2 and 6 run in parallel with steps 3 to 5. The system steps run one after another, about 2 h 55 min in total.
The agent sessions are independent of each other. **Agent checks never receive script outputs.** That way the
comparison between agent verdicts and empirical results in the audit is a real cross-check.

### 2.2 Agent folder (calibration and real runs are prepared the same way)

1. Copy the repository into a fresh folder.
2. Delete `control/` (it holds the key and the sample), `api-tests/TEST-PLAN.md`, `api-tests/TEST-REPORT.md`,
   `api-tests/results/`, `api-tests/target/` and `review/`. Under D9 (recommended), also delete the service sources:
   `player-service/`, `analytics-service/`, `proto/`, `infra/`.
3. Calibration only: replace `api-tests/` with a copy of `control/sample/` (taken before step 2).
4. Start a fresh session with the common brief (§3.0), the check's brief and the batch list. The agent writes
   `review-out/<CHECK>-<batch>.jsonl` and edits nothing else.

### 2.3 Batches

At most 12 tests per batch (D10). In large classes, a batch lists method names; the agent still reads the whole file.

| Batch | Tests |
|---|---|
| B01 | `ValidationTest` (10) |
| B02 | `IdempotencyKeyTest` CL-083…CL-092 (10) |
| B03 | `IdempotencyKeyTest` CL-093…CL-101 (9) |
| B04 | `DepositTest` (11) |
| B05 | `WithdrawalTest` (12) |
| B06 | `GameSessionTest` CL-053…CL-062 (10) |
| B07 | `GameSessionTest` CL-063…CL-068 (6) + `SessionTimingTest` (5) |
| B08 | `DepositLimitsTest` CL-012…CL-023 (12) |
| B09 | `DepositLimitsTest` CL-024…CL-033 (10) |
| B10 | `DepositLimitCountingTest` (7) + `EventsTest` (5) |
| B11 | `PaymentProviderTest` (6) + `ConcurrencyTest` (6) |
| B12 | `SecurityTest` (6) + `InvariantTest` (2) |
| B13 | `LeaderboardTest` (7) + `LeaderboardPeriodTest` (2) |

Calibration batches: S1 = `DepositTest`, `GameSessionTest`, `SessionTimingTest`, `DepositLimitsTest`;
S2 = `IdempotencyKeyTest`, `EventsTest`, `ConcurrencyTest`, `PaymentProviderTest`.

## 3. The checks

### 3.0 Common brief for every agent check

The harness puts this text in front of the check's own brief:

```text
You review automated API tests (JUnit 5, REST Assured) in api-tests/ against the specification
docs/spec/SPEC.md. The specification is the only oracle. What the service currently does, what test
authors wrote in plans or comments, and how the code is built are not evidence of correct behavior.

Read: SPEC.md completely; every file in api-tests/src/test/java/com/arcade/qa/support/ (helpers contain
assertions, waits and global changes); the test classes of your batch, including helpers in those classes
and helpers they call in other test classes.

Judge every test method in your batch list. A parameterized test is one test; consider all its arguments.
Do not run anything, do not send requests, do not edit files.

Output: one JSON object per test, one per line, in review-out/<CHECK>-<batch>.jsonl:
{"check": "<CHECK>", "test": "Class#method", "display_name": "...", "tags": ["..."],
 "verdict": "PASS" | "FLAG" | "UNCLEAR", "kind": "<kind>" | null, "line": <int> | null,
 "evidence": "...", "spec": "<ID>: \"<short quote>\"", "fix_hint": "..."}
- PASS: kind null; evidence = one sentence saying why the test is fine for your check.
- FLAG: kind and line of the defect; evidence = the concrete witness or scenario your check asks for;
  spec = the requirement and a short quote; fix_hint = what the corrected test must establish, in
  specification terms, without code.
- UNCLEAR: only when the specification itself is ambiguous for the point; give the readings in evidence.
One verdict per test. If a test has several defects of your check, report the most severe as kind and
mention the others in evidence. Stay inside your check's scope: the "Not in scope" list says what other
checks judge, so do not flag those things.
```

### CHK-1 Empty checks (agent)

**What it finds and why it matters.** It finds tests that would stay green although the system breaks the very
point the test claims to check. These tests give false confidence and hide regressions: the report counts them as
coverage. The anchor is the test's claim (its display name). That is precise, and it is the unit the checklist
promises. Whether the claims of all tests together cover their requirement is a separate question, which CHK-4
measures empirically and the human review of meaning judges (D15).

**Performer: agent.** The check has to read the spec, understand the scenario, and construct a violating behavior
(a "witness") that passes every assertion. A script cannot do this. Counting assertions misses tautologies,
neutralized assertions and missing observation points.

**Inputs.** SPEC.md; the test class; the support code.

**Procedure and criterion.** As in the brief. A test is a finding (**FLAG**) when the agent can state a witness: a
concrete behavior of the service that breaks at least one claim point, in the scenario the test sets up, under which
the test does not fail. "Does not fail" includes pass, skip and abort.

**Verdict format.** The common JSONL. `kind` ∈ E1–E5. `evidence` = the witness.

**Known false positives.**
- An agent may extend the claim with details the display name does not name. The brief forbids this.
- An agent may treat precondition assertions as missing. The brief lists the precondition helpers.
- An agent may call a wait too short for a negative check when it meets the standard (≥ 1 s quiet, D16).

**Known false negatives.**
- Requirement points that no display name claims (CHK-4 covers these).
- Witnesses that only appear after the suite's standard waits, for example an event published 5 s late after a
  refusal (D16).
- Weakness hidden in support code that the agent trusts. Mitigation: support code is read in full, and CHK-4 catches
  it empirically.

**Cost.** No suite runs. 13 sessions, each reading SPEC.md (~500 lines), the support code (~1,000 lines) and 1–2
test classes. Roughly 40–80k tokens and 5–15 min per session. Calibration: 2 sessions per repetition.

**Brief:**

```text
CHECK CHK-1: EMPTY CHECKS.
Question: can this test pass although the system breaks what the test claims to check?

1. Claim. The claim is the @DisplayName text after the checklist ID. Split it into claim points: each
   outcome it names (status and error code, a response field, the balance, a journal record, an event,
   a provider call or its absence, the key being consumed or free, a session or limit state, a
   leaderboard or WebSocket content, a time bound). Use the specification to give each point its precise
   meaning in the scenario the test sets up. For example, "nothing changes" after a refusal means what
   the governing requirement lists as unchanged. Do not add points that the claim does not name.
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
   - Asynchronous outcomes: assume an effect becomes observable within the test's wait when the wait is
     at least the standard one. Positive Kafka checks: Events.await/awaitOne with EVENT_TIMEOUT (10 s).
     Negative checks (no event, no message): a quiet period of at least 1 s (Snapshot.assertUnchanged
     waits 1 s). Session expiry: 5 s. Leaderboard and WebSocket: 30 s. A negative check with no wait or
     a shorter one does not observe its point (kind E4).
4. Verdict: FLAG if a witness exists for at least one claim point, otherwise PASS. Kinds:
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

### CHK-2 Instability across runs (script)

**What it finds and why it matters.** It finds tests whose outcome changes between runs of the same system.
- An unstable test gives no information.
- It corrupts CHK-4: a random red on a faulty version looks like a catch, and a random green looks like a miss.
- The fixing agent may "fix" it into a weaker test.

The check also produces the base outcome that CHK-4, CHK-6 and the protected list (§6) use.

**Performer: script.** It needs real runs, and comparing outcomes is mechanical.

**Inputs.** `api-tests/` (unchanged); the recreate tooling; N (D1).

**Procedure.**
1. Do every run in a pristine scratch copy of `api-tests/`. The suite writes reports into its own `results/`.
2. Fresh runs 1–5: recreate the system, then run `mvn -B test`. Aged runs 6–10: after run 5, run `mvn -B test` five
   more times without recreating. Record the service clock (`GET /api/test/clock`) before each run.
3. Parse `results/surefire-reports/*.xml`. For each `testcase`, read the class, the name (display name plus
   invocation), the outcome (pass, FAIL from `failure`, ERROR from `error`, skip from `skipped`), the first line of
   the failure message and the time. Normalize messages: digit runs become `#` and UUIDs become `U`. Map each
   execution to its test through `review/tagmap.csv` (CHK-3S).
4. Disturbed runs (D2): if more than 10 % of the executions in a run differ from their majority outcome, mark the run
   disturbed and repeat it once. If the repeat is disturbed too, keep both and report it.
5. Per execution, classify the outcomes as STABLE_GREEN, STABLE_RED, STABLE_SKIP or UNSTABLE (more than one of
   green, red and skip). A test takes its worst execution: UNSTABLE > STABLE_RED > STABLE_SKIP > STABLE_GREEN.

**Criterion.**
- **Finding UNSTABLE:** a test with at least one UNSTABLE execution.
- **Info only:** RED_VARIES, a STABLE_RED execution whose normalized messages differ between runs or that flips
  between FAIL and ERROR (D2). REPORT_MISMATCH, a test whose stable outcome differs from the PASS/FAIL that
  TEST-REPORT.md §1 lists for it. TEST-REPORT lists 17 failing tests (24 executions). They are expected to be
  STABLE_RED, with one likely exception. `ConcurrencyTest#withdrawalVersusSessionStart` depends on a race while
  BUG-04 is open: it passes whenever the withdrawal wins all 3 rounds, so it may come out UNSTABLE.

**Verdict format.** `review/chk2/stability.csv` with the columns
`class, display_name, invocation, runs, pass, fail, error, skip, verdict, fresh_vs_aged, messages`. The column
`fresh_vs_aged` shows whether the flip happens only on aged runs. Findings go to JSONL in the common finding schema
(§6).

**Known false positives.** Flips caused by the environment rather than the test: a slow Docker start after a
recreate, or a Kafka rebalance. The disturbed-run rule mitigates this. A flip that happens only on aged runs is a
real finding: the plan claims the suite works on a long-lived stand.

**Known false negatives.**
- Flaky tests with a low failure rate. With 10 runs a 20 % flake is caught with 89 % probability and a 10 % flake
  with 65 %. CHK-5 covers these statically.
- Flakiness that appears only under other load or on another host.
- State effects that need more than 5 consecutive runs.

**Cost.** 10 suite runs and 5 recreates: 5 × (45 s + 90 s) + 5 × 90 s ≈ 19 min. Analysis takes seconds.

### CHK-3 Link to the requirement

The check has two parts. CHK-3S is mechanical and covers the whole suite in seconds. CHK-3A needs reading
comprehension.

#### CHK-3S IDs and coverage (script)

**What it finds and why it matters.** It finds tags that name no requirement, tests without a requirement tag,
duplicate test names, and requirements with no test. Tags are the join key for CHK-4, for the report's coverage
matrix and for the human review. A broken key silently removes tests from all of them.

**Performer: script.** These are exact string facts.

**Inputs.** SPEC.md; the test sources; for S6 only, `TEST-PLAN.md` §4.

**Procedure.**
1. Spec IDs: every match of `^\*\*([A-Z]{3}-\d{2})\.` in SPEC.md. There must be exactly 69; if not, stop and report.
2. Parse the test sources with a Java parser, or with code that handles annotations spanning several lines (the
   suite has multi-line `@CsvSource`). For every `@Test` or `@ParameterizedTest` method, record class, method, line,
   `@DisplayName` and its `@Tag` values (method- and class-level). Self-check: the parser must find 136 tests. If
   it does not, stop: a naive line-based parse finds 132.
3. Write `review/tagmap.csv`. Then check:

| Sub-check | Rule | Result |
|---|---|---|
| S1 | Every tag value equals a spec ID (exact, case-sensitive) | Finding L1 |
| S2 | Every test has at least one spec-ID tag | Finding UNTAGGED |
| S3 | The display name starts with `CL-\d{3} ` | Finding NAME_FORMAT |
| S4 | (class, display name) is unique, which matrix rows require | Finding DUPLICATE_NAME |
| S5 | Every spec ID is a tag of at least one test | Requirement-level finding UNCOVERED |
| S6 | Tags agree with the Req column for the same CL-ID in `TEST-PLAN.md` §4 | Info only, passed to the human audit; never to agents |

**Criterion.** As in the table. **Verdict format:** `review/tagmap.csv` plus findings JSONL (§6).

**Known false positives.** Non-requirement tags added on purpose (for example `slow`). If any are introduced, they
go on a whitelist.

**Known false negatives.** A real but wrong ID. CHK-3A catches it.

**Cost.** Seconds. **Sanity test:** on `control/sample/` the script must report exactly one S1 finding
(SAMPLE-KEY.md §7).

#### CHK-3A Content of the link (agent)

**What it finds and why it matters.** It finds three problems:
- A tag the test does not exercise.
- A claim whose governing requirement is not tagged.
- An expectation that disagrees with the specification: it contradicts the spec, or it demands something the spec
  leaves open.

A wrong expectation is the most harmful test defect. It fails a correct system, or worse, it encodes a system defect
as the expected behavior and so passes a broken system. Under the rule "the specification is the oracle", the fixing
agent needs exactly these findings. They also decide which failing tests must be protected (§6).

**Performer: agent.** It must determine, from the specification, the outcome each request should have, including
order-of-checks and replay rules. This is reading comprehension, not string matching.

**Inputs.** SPEC.md; the test class; the support code. Not the service code or the current behavior (D9).

**Procedure and criterion.** As in the brief. **Finding:** any of L1–L4.

**Verdict format.** The common JSONL. `kind` ∈ L1–L4. `evidence` = the spec's prescribed outcome against the test's
expectation.

**Known false positives.**
- The agent reads an ambiguous spec point as clear. The open questions in TEST-PLAN §6 (for example OQ-04, OQ-11,
  OQ-12, OQ-26) are typical; the agent must answer UNCLEAR for them.
- The agent treats tolerant expectations (accepting several outcomes) as wrong. That is CHK-1's question, not L3.

**Known false negatives.**
- A wrong expectation inside a support helper the agent trusts.
- A contradiction that only follows from combining several requirements, if the agent applies them one by one. The
  brief names the cross-cutting rules.

**Cost.** As CHK-1: 13 sessions; calibration 2 per repetition.

**Brief:**

```text
CHECK CHK-3A: LINK TO THE REQUIREMENT.
Question: are the test's tags real requirements that the test exercises, and does every expectation in
the test agree with what the specification states?

1. Tags. Each @Tag value must be an ID defined in SPEC.md (a bold "**XXX-NN.**" heading). If not: L1.
2. Exercise. For each tag: does the test send at least one request in the situation this requirement
   governs, whose outcome the requirement determines? Setup steps (creating and funding players, setting
   limits as a precondition) do not count as exercising a requirement. If a tag is not exercised: L2.
   It is also L2 if the requirement that governs the claim (the @DisplayName after the CL-ID) is not
   among the tags.
3. Expectations. For every assertion, including assertions in helpers, determine the outcome the
   specification prescribes in this scenario. When several rules could apply, use the order-of-checks
   rules (DEP-05, LIM-05, WDR-06, SES-06) and GEN-12 (a replay returns the original response even if a
   check would refuse the request now).
   L3 contradiction: the specification prescribes a different outcome (status, error code, field value,
      bound, which rule wins, the clock a time is on, a count). A service that follows the specification
      fails the test.
   L4 unspecified expectation: the assertion requires something the specification leaves open (exact
      message text, a number format or type that is not fixed, the order of equal entries, the absence
      of other fields, internals beyond the journal and the provider request journal). A service that
      follows the specification but differs in that detail fails the test. Values the specification
      states literally (for example details.min = "10.00") are not L4.
   Judge the scenario as written: run once on a fresh system, with each step completing in the order the
   test intends.
4. Verdict. FLAG if any of L1–L4 applies. Report the most severe as kind (L3 > L4 > L2 > L1) and mention
   the others in evidence. Otherwise PASS. UNCLEAR only if the specification is genuinely ambiguous for
   an asserted point: give the readings and say which one the test takes.

Not in scope: whether the assertions are strong enough (CHK-1); which of several concurrent requests is
served first, how long asynchronous effects take, waits, and interference from data of earlier runs or
other tests (CHK-5). Tolerant expectations that accept the specified outcome among others are not L3.
```

### CHK-4 Failure on faulty versions (script)

**What it finds and why it matters.** It finds three things:
- Tests that stay green on the faulty version of their own requirement.
- Faulty versions that no test catches.
- Faulty versions caught only incidentally, by tests of other requirements.

This is the empirical counterpart of CHK-1: a requirement whose faulty version passes the whole suite is effectively
untested, whatever the tests claim.

**Performer: script.** The analysis is a deterministic join over the matrix, the tag map and the stability data.
The check deliberately has no agent triage. A triage agent could not be calibrated on the sample, because the sample
has no faulty versions. Instead, the choices that triage would make are listed as decisions (D3–D7), and the results
are cross-checked against CHK-1 in the audit.

**Inputs.**
- `matrix.csv`: one row per test (class, display name); one column per run (base, M01…M64); values pass, FAIL,
  ERROR, skip.
- `faults.csv`: ID, requirement, part broken, what changed, how to observe.
- `review/tagmap.csv` (CHK-3S) and `review/chk2/stability.csv` (CHK-2).
- If D4(b) is chosen: the normalized first failure line of each red cell. The matrix script must be extended to keep
  it from its Surefire XML.

**Procedure.**
1. Normalize the values. If a key (class, display name) has several rows (invocations), aggregate each column
   worst-of: ERROR > FAIL > skip > pass. Join the rows to the tag map. Any row that does not join is JOIN_ERROR:
   stop and fix the join first.
2. Run validity (D3): a column M is INVALID if it has no pass at all, or if ≥ 90 % of its cells are ERROR (the
   system was unreachable). Re-run M once. If it is still INVALID, its status is NOT_EVALUATED.
3. Usable tests U = tests that CHK-2 rates STABLE_GREEN. UNSTABLE tests are excluded and listed. A test that is
   green in CHK-2 but red in the matrix's base column is excluded and reported.
4. `detects(t, M)` holds when t ∈ U and `M(t)` ∈ {FAIL, ERROR}. ERROR-only detections are marked. Under D4(b), it
   also holds when t is STABLE_RED and `M(t)` is red with a normalized first failure line that differs from base.
   A skip on M is never a detection.
5. For each fault M with requirement R:
   - `own(R)` = tests tagged R (all tags count, D7).
   - `D_own` = { t ∈ own(R) : detects(t, M) }.
   - `D_other` = { t ∉ own(R) : detects(t, M) }.
6. Assign a status to each fault:

   | Status | Condition |
   |---|---|
   | NOT_EVALUATED | The run is invalid |
   | CAUGHT | `D_own` ≠ ∅ |
   | OWN_TESTS_MISS | `D_own` = ∅ and `D_other` ≠ ∅ |
   | UNCAUGHT | `D_own` = ∅, `D_other` = ∅, and some own test is in U |
   | NOT_EVALUABLE | Nothing detects M, and every own test is red or unstable on base |

7. Green-on-own-fault pairs: for every t ∈ own(R) ∩ U with `M(t)` ∈ {pass, skip}, record a pair (t, M). The pair is
   **primary** when the status of M is UNCAUGHT or OWN_TESTS_MISS, and **secondary** when it is CAUGHT. A skip is
   marked `skipped` because it suggests a neutralized assertion.
8. Report as info: the requirements without a faulty version (69 − 64 = 5), listed as NOT_MUTATED; the collateral
   detections `D_other` per fault, which may point to a missing tag (input to the audit); and ERROR-only detections.

**Criterion.**
- **Requirement-level finding** for each fault with status UNCAUGHT (high) or OWN_TESTS_MISS (medium). It lists the
  primary green own tests as candidate tests, the part broken and how to observe it (D8).
- **Test-level primary pairs** are attached to those findings.
- **Not sent to the fixer:** secondary pairs (D5), and the statuses NOT_EVALUABLE, NOT_EVALUATED and NOT_MUTATED.
  These go to the human.

**Verdict format.**
- `review/chk4/faults.csv`: `id, requirement, part_broken, status, own_tests, D_own, D_other, error_only`.
- `review/chk4/green_own.csv`: `test, fault, base, fault_value, pair` (primary or secondary), `skipped`.
- Findings JSONL (§6).

**Known false positives.**
- Faults that the environment cannot observe (for example a defect visible only after a service restart, which
  §10 forbids). They come out UNCAUGHT but cannot be fixed; the human decides.
- Secondary pairs, which are usually tests of another part of the requirement. That is why they are not sent.
- Faults whose symptom equals an existing base defect (for example on WDR-03, which the base already violates).
  They come out NOT_EVALUABLE or UNCAUGHT although the tests are right.

**Known false negatives.**
- One faulty version per requirement: other ways of breaking it are never probed, so CAUGHT does not prove the
  tests are strong.
- A detection by a crash or by an unrelated assertion counts as CAUGHT. ERROR-only detections are marked for this
  reason.
- A detection by a test that is unstable but stayed green in all CHK-2 runs.

**Cost.** 65 suite runs: 65 × (45 s + 90 s) ≈ 2 h 26 min, plus re-runs of invalid columns. Cap each run at 10 min
so that hanging faulty versions do not block the queue. The analysis takes seconds.

### CHK-5 Unstable by construction (agent)

**What it finds and why it matters.** It finds tests whose outcome can change between runs of an unchanged,
spec-conforming system, or tests that can break other tests, because of how they are built. There are four causes:
- Premature observation of asynchronous effects.
- Global changes that are not restored on every path.
- Data or state the test does not create.
- Unenforced ordering.

CHK-2 sees only what happens in 10 runs. Rare races, damage that appears only when a test fails (a restore that never
happens), and collisions that need a long-lived database are mostly invisible there. On faulty versions, the second
kind turns whole matrix columns red.

**Performer: agent.** It requires reasoning about what the specification guarantees (bounds, ordering, key scope) and
what the code assumes.

**Inputs.** SPEC.md (§10 especially); the test class; the support code.

**Procedure and criterion.** As in the brief. **Finding:** any of H1–H4, with a concrete failing run described.

**Verdict format.** The common JSONL. `kind` ∈ H1–H4. `evidence` = the timings, interleaving or prior state.

**Known false positives.**
- Flagging deliberate quiet periods of negative checks. The brief excludes them.
- Flagging fixed values whose scope is a fresh player. The brief excludes them.
- Margins that are fine in practice but under the threshold (D16).

**Known false negatives.**
- Hazards inside the service under load, which no static reading of the test shows.
- Hazards in support code that the agent trusts.
- Hazards that need knowledge of the stub beyond SPEC §10.

**Cost.** As CHK-1: 13 sessions; calibration 2 per repetition.

**Brief:**

```text
CHECK CHK-5: UNSTABLE BY CONSTRUCTION.
Question: can this test's outcome, or the outcome of other tests, change between runs on the same
unchanged system that follows the specification, because of how the test is built?

Environment (SPEC section 10 and the suite's setup):
- One long-lived shared system. Runs are sequential, and tests in a run are sequential in one JVM unless
  the test itself starts threads.
- The database keeps everything between runs.
- The service clock only moves forward and is shared; a run moves it by weeks.
- Provider stub settings and mappings are global. The provider request journal is never cleared.
- The Kafka collector sees events published after the suite started.
- Config.UNKNOWN_ID is an id that no player or session has.

Look for:
H1 premature observation: an asynchronous or deadline-driven outcome (Kafka event, leaderboard GET or
   WebSocket message, session expiry with its status, refund, journal record and event, a pending limit
   change) is asserted before the specification guarantees it is observable. That is: with no wait for
   that outcome, with a wait shorter than the specification's bound (SES-11: 5 s; LDB-05 and LDB-07:
   30 s), or by waiting for one effect and then reading another effect that has its own guarantee.
   For events with no bound in the specification, waiting with Events.await/awaitOne and EVENT_TIMEOUT
   (10 s) is acceptable; reading them without waiting is not. Results of a synchronous request (its
   response, the balance, the journal, limit state, the provider request journal) need no wait.
   Negative checks after a quiet period are not H1.
H2 environment not restored: the test changes global state other tests rely on (provider stub delay,
   mappings or faults, ...) and does not restore it on every path, including failed assertions and
   exceptions. The restore must be in finally, or in an @AfterEach/@AfterAll that always runs.
   Advancing the service clock is allowed and is not H2.
H3 state the test does not create: the outcome depends on data or state that the test did not create in
   this run and that can differ between runs. Examples: fixed values that must be unique (username, email)
   or that collide with what earlier runs left; seed players; the absolute service-clock value or the
   distance to a period boundary, unless the test reads it and adapts; the current leaderboard or other
   players' results; data created by other tests, or an assumed order of test classes (an explicit
   @Order inside one class is allowed); an assumed empty table, topic or journal. Fixed values scoped to
   a player the test has just created (for example an idempotency key, GEN-10) are not H3.
H4 unenforced ordering or timing: the outcome depends on which of several concurrent requests is
   processed first, or on a real-time duration that the test assumes but does not enforce: a fixed
   sleep that must outlast or precede another action, or a margin shorter than 500 ms to a time bound.

Verdict: FLAG with kind, and in evidence the concrete run (timings, interleaving or prior state) under
which a system that follows the specification makes this test fail, or under which this test makes
later tests fail. Otherwise PASS.

Not in scope: assertion strength (CHK-1); whether expected values agree with the specification (CHK-3A).
```

### CHK-6 Order dependence (script)

**What it finds and why it matters.** It finds tests whose outcome depends on which classes ran before them. The
fixing agent will change tests. Hidden coupling between classes (data, clock position, stub state) then breaks the
suite in ways CHK-2 cannot attribute.

**Performer: script.** It needs runs, and comparing outcomes is mechanical.

**Inputs.** `api-tests/`; `review/chk2/stability.csv`.

**Procedure.**
1. On a freshly recreated system, in a scratch copy, run each class alone in the standard class order:
   `mvn -B test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`. Method order inside a class follows its own
   `@TestMethodOrder`, or JUnit's default.
2. Then run the full suite once with the class order reversed: generate a `@Suite` with `@SelectClasses` in reverse
   `@Order` in the scratch copy. `InvariantTest` stays last, because its sweep is designed to run at the end.
3. If a test differs from its CHK-2 class, repeat that variant once to rule out a rare flake.

**Criterion.** **Finding ORDER_DEPENDENT:** a test that is STABLE_GREEN or STABLE_RED in CHK-2 and whose outcome
class differs, in both attempts, in a class-alone run or in the reversed run.

**Verdict format.** `review/chk6/order.csv` (`test, chk2_verdict, alone, reversed, verdict`) plus findings JSONL.

**Known false positives.** None designed in; cross-class dependencies are not documented anywhere except
InvariantTest-last. A rare flake that hits both attempts is possible but unlikely.

**Known false negatives.** Dependencies inside a class (method order is kept); orders other than the two probed.

**Cost.** 1 recreate (45 s), 15 class runs (≈ 15 s start-up each, plus ≈ 90 s of tests in total) and 1 reversed run
(90 s): ≈ 8 min.

## 4. Calibration of the agent checks (CHK-1, CHK-3A, CHK-5)

- **Sample:** `control/sample/`. It is a complete Maven project shaped like `api-tests/`, with 21 tests in 8
  classes and two batches (S1, S2). It compiles with `mvn -B -q test-compile`. Good tests are verified suite tests.
  Bad tests are copies with one defect each, and every defect kind of each check (E1–E5, L1–L4, H1–H4) appears at
  least once. The answer key is `control/SAMPLE-KEY.md`; checking agents never see it (§2.2).
- **Run:**
  1. Prepare the agent folder (§2.2) with the sample in place of `api-tests/`.
  2. Run the check on S1 and S2 in fresh sessions, with exactly the briefs that will be used on the suite.
  3. Score the JSONL against the CSV in SAMPLE-KEY.md §3.
- **Pass:** every bad test is FLAG and every other test is PASS, in each of R repetitions (D11). UNCLEAR, a missing
  verdict or a duplicate verdict counts as a miss. A kind mismatch is reported but does not fail calibration.
- **Fail:** the brief is revised (D12) and the full calibration runs again. After 2 failed revision rounds, the check
  is dropped and its scope goes to the human review. Any change to a brief after calibration requires
  re-calibration. The common brief is part of every check, so a change to it re-calibrates all three.
- **Cost relative to the suite:** one calibration repetition is 2 sessions, against 13 on the suite (≈ 15 %), and
  570 lines of test code against about 3,400.
- **Script checks** are not calibrated on the sample. CHK-3S has a sanity test on it (SAMPLE-KEY.md §7). CHK-2,
  CHK-4 and CHK-6 are verified by reviewing their code against this document.

## 5. Audit of agent verdicts (selection only)

The human audits a subset of verdicts before `findings.json` reaches the fixing agent (D19). The assembly step
selects:
1. **Contradictions between agent verdicts and empirical results:**
   - CHK-1 PASS on a test that has a primary green-on-own-fault pair.
   - CHK-1 FLAG on a test in `D_own` of some fault.
   - CHK-5 PASS on a test that CHK-2 or CHK-6 marked.
   - CHK-3A FLAG L1 that CHK-3S did not report, or the reverse.
2. **Every CHK-3A FLAG of kind L3:** these reverse a test's expectation, so they carry the highest impact.
3. **Every UNCLEAR.**
4. **A random sample of the rest:** 10 % of the remaining FLAGs and 5 % of the PASSes per agent check, at least 5
   of each.

Audited-wrong verdicts are removed. If more than 10 % of the audited verdicts of a check are wrong, all of its
unaudited findings are withheld and the check is re-calibrated.

## 6. Findings file for the fixing agent

The fixing agent receives `review/findings.json`, the specification and `api-tests/`.

It does not receive:
- PASS verdicts.
- UNCLEAR verdicts. They go to the human.
- Audit notes.
- Info-only items.
- Secondary pairs.
- The "what changed" of faulty versions (D8).
- The sample and the key.

The assembly step (a script) builds the file after the audit:
1. Take every FLAG of CHK-1, CHK-3A and CHK-5 that survived the audit.
2. Add the script findings of CHK-2, CHK-3S, CHK-4 and CHK-6.
3. Attach to each test-level finding the test's CHK-2 verdict and its CHK-4 pairs.
4. Compute the `protected` list (D18): tests that are STABLE_RED in CHK-2 and have a CHK-3A PASS. UNSTABLE tests are
   never protected.

### 6.1 Format

One JSON document:

```json
{
  "meta": {
    "suite": "api-tests @ <git commit>",
    "spec": "docs/spec/SPEC.md @ <git commit>",
    "generated": "2026-10-20T12:00:00Z",
    "checks": {"CHK-1": "calibrated 2/2", "CHK-2": "10 runs", "CHK-3S": "ok", "CHK-3A": "calibrated 2/2",
               "CHK-4": "65 runs, 0 not evaluated", "CHK-5": "calibrated 2/2", "CHK-6": "ok"},
    "rules": "The specification is the oracle. Cite the requirement ID for every change."
  },
  "protected": [
    {
      "test": "<Class>#<method>",
      "display_name": "CL-nnn <claim>",
      "base": "STABLE_RED",
      "reason": "Fails on the current system, and CHK-3A confirms every expectation against <ID>: the failure is a system defect.",
      "rule": "Do not change its expected outcomes. Other findings on this test may still be fixed."
    }
  ],
  "findings": [
    {
      "id": "F-<hash>",
      "check": "CHK-1",
      "kind": "E4",
      "severity": "medium",
      "target": {
        "type": "test",
        "test": "<Class>#<method>",
        "display_name": "CL-nnn <claim naming 'provider not called'>",
        "tags": ["<ID>"],
        "file": "api-tests/src/test/java/com/arcade/qa/tests/<Class>.java",
        "line": 0
      },
      "requirement": "<ID>",
      "spec_quote": "<ID>: \"... The provider is not called. ...\"",
      "problem": "The claim point 'provider not called' has no assertion.",
      "evidence": "Witness: the service sends POST /payments, then refuses the request as claimed; every assertion passes.",
      "expected_after_fix": "The test fails when the service calls the provider in this scenario.",
      "fix_scope": "assertion",
      "base_outcome": "STABLE_GREEN",
      "chk4": {"green_on_own_fault": ["Mnn (primary)"]},
      "chk5": null,
      "audit": "not sampled"
    },
    {
      "id": "F-<hash>",
      "check": "CHK-4",
      "kind": "UNCAUGHT",
      "severity": "high",
      "target": {"type": "requirement", "requirement": "<ID>", "fault": "Mnn"},
      "spec_quote": "<ID>: \"<sentence of the part broken>\"",
      "problem": "No test fails when this part is broken: <part broken>. How to observe: <how to observe>.",
      "evidence": "Own tests that stay green on Mnn: <Class>#<method>, <Class>#<method>.",
      "expected_after_fix": "At least one test tagged <ID> fails on Mnn and passes on base.",
      "fix_scope": "assertion | new-test",
      "audit": "n/a"
    }
  ]
}
```

Field rules:
- `id` is stable across re-assembly: a hash of check, kind and target.
- `severity` follows the table in §6.2.
- `fix_scope` is one of `assertion`, `scenario`, `expectation`, `tag`, `wait`, `isolation`, `new-test`.
- `expected_after_fix` is phrased so that a re-run of the producing check can verify it.
- Findings are ordered by file, then line. Requirement-level findings come last.
- One finding per (check, kind, target). The fixer handles all findings on a test together.

### 6.2 What the fixing agent does with each kind

| Kind (check) | Severity | Fix | Must not |
|---|---|---|---|
| E1, E2, E5 (CHK-1) | high | Add the missing assertion or the independent expected value; remove the neutralizer | Narrow the claim or display name (D17); remove assertions |
| E3, E4 (CHK-1) | medium | Make the assertion exact; observe the claimed point | Same |
| L3 (CHK-3A) | high | Change the expectation to the spec's (quote it). If the test then fails on base, it joins `protected` | Adapt the expectation to the current behavior |
| L4 (CHK-3A) | medium | Relax to what the spec states: compare by value, check presence only | Delete the whole check |
| L1, L2 (CHK-3A, CHK-3S S1/S2) | low | Correct the tags to the requirements the test exercises and its claim's governing requirement | Change test behavior |
| S3, S4 (CHK-3S) | low | Rename so that the convention holds and names are unique | — |
| UNCOVERED (CHK-3S S5) | high | Add a test for the requirement | — |
| UNSTABLE (CHK-2), H1–H4 (CHK-5), ORDER_DEPENDENT (CHK-6) | high (UNSTABLE, H2); medium (others) | Remove the cause: poll up to the spec's bound, restore in `finally`, create own data, assert order-independent outcomes | Lengthen expectations beyond the spec's bounds; delete or skip the test |
| UNCAUGHT, OWN_TESTS_MISS (CHK-4) | high / medium | Strengthen a candidate test, or add one, so that the described part is observed | Target the faulty version's implementation; only spec-level behavior counts |
| Any finding on a `protected` test | — | Fix the finding | Change the protected expectations |

General rules for the fixer:
- Every change cites a requirement ID.
- Changes to `support/` affect every test. List them, and expect CHK-2 and CHK-4 to be re-run.
- Do not add tests for out-of-scope features (SPEC §1).
- Keep the `@Tag` and `@DisplayName` conventions.

## 7. Decisions for the reviewer

| ID | Decision | Options | Recommendation | Why |
|---|---|---|---|---|
| D1 | CHK-2: number of runs and system state | (a) 5 fresh; (b) 10 = 5 fresh + 5 aged; (c) 20 (10 + 10) | **(b)** | Aged runs test the plan's claim that the suite works on a long-lived stand. (c) doubles the cost (≈ 38 min) for p = 0.1 flakes (88 % detection instead of 65 %). CHK-5 covers rare flakes statically |
| D2 | CHK-2: what is an instability finding; disturbed runs | (a) any green/red/skip flip; (b) also FAIL↔ERROR or a changed failure message within red; (c) only flips seen ≥ 2 times. Disturbed: count all, or repeat a run once when > 10 % of executions flip | **(a)** as finding, (b) as info; repeat disturbed runs once | (b) matters for base-red tests but does not change their verdict. (c) loses half the rare flakes |
| D3 | CHK-4: what is a detection; invalid runs | (a) FAIL or ERROR; (b) FAIL only. Invalid: no pass at all or ≥ 90 % ERROR, re-run once (thresholds 80/90/100 %) | **(a)**, with ERROR-only detections marked; 90 % | Many faulty versions legitimately make tests ERROR (e.g. a broken response shape). A broken start-up is recognizable by near-total ERROR |
| D4 | CHK-4: tests red on base (17 today) | (a) exclude them; (b) count a changed normalized first failure line as a detection (extend the matrix script to keep it); (c) re-run the matrix after the system defects are fixed | **(b)** now, **(c)** after the fix | 16 requirements have a red own test. Under (a), GEN-06, GEN-08, GEN-18 and GEN-19, whose own tests are all red, cannot be evaluated at all, and the other 12 only partly |
| D5 | CHK-4: secondary green-on-own-fault pairs (another own test catches M) | (a) send to the fixer; (b) info and audit only | **(b)** | They are mostly tests of another part of the requirement. Sending them invites pointless "strengthening" |
| D6 | CHK-4: fault caught only by tests of other requirements | (a) finding OWN_TESTS_MISS (medium); (b) info | **(a)** | Incidental catches disappear when the other test changes, which the fix step will do |
| D7 | CHK-4: what is the own requirement; parameterized rows | Own: (a) all tags; (b) first tag only. Rows: (a) worst-of per test; (b) per invocation | **all tags; worst-of** | The suite uses several tags per test on purpose (e.g. SES-07 + GEN-21). The matrix is per test by definition |
| D8 | What the fixer learns about faulty versions | (a) everything incl. "what changed"; (b) requirement + part broken + how to observe; (c) requirement only | **(b)** | (a) invites tests tailored to one code mutation; (c) is too vague to act on. (b) is spec-level |
| D9 | Agent folder contents | (a) keep service sources, TEST-PLAN and TEST-REPORT; (b) remove TEST-PLAN and TEST-REPORT only; (c) remove both and the service sources | **(c)** | The spec is the oracle. The code and the report both encode current behavior, including its defects, and the sample includes this trap. Calibration must use the same choice |
| D10 | Agent session granularity | (a) one test; (b) batches of ≤ 12 tests (13 sessions); (c) one class (15 sessions, up to 22 tests) | **(b)** | (a) costs 136 sessions per check. (c) overloads a session with DepositLimitsTest. The sample's two batches have the same size as the real ones |
| D11 | Calibration repetitions; kind mismatches | R = 1, 2 (both perfect) or 3 (majority); kind: scored or info | **R = 2, both perfect; kind = info** | Agent verdicts vary between sessions. One perfect run is weak evidence, and a majority rule would accept a check that misses sometimes |
| D12 | Revising a failed check (risk of overfitting to the sample) | (a) free revision; (b) the reviser sees which tests and verdicts failed, revises in general terms (no sample identifiers, no new examples taken from the sample), max 2 rounds; (c) as (b) plus a hold-out of half the sample, not shown during revision | **(b)** | (c) halves an already small sample (some kinds would have no case left). (b) keeps the key's reasons away from the brief |
| D13 | Passes per agent check on the suite | (a) 1 pass + audit; (b) 2 independent passes; findings only where both FLAG, disagreements to the human | **(a)**, switch to (b) for a check whose audit error exceeds 10 % | Calibration already bounds the error. (b) doubles agent cost for every check |
| D14 | UNCLEAR verdicts | (a) to the human; (b) to the fixer with both readings; (c) treat as FLAG | **(a)** | Resolving spec ambiguity is a product decision, not a test fix (TEST-PLAN §6 already lists 26 open questions) |
| D15 | CHK-1 anchor | (a) the claim in the display name; (b) everything the tagged requirement states | **(a)** | Under (b) nearly every test is "empty", because single tests cover parts of requirements by design. CHK-4 measures requirement-level coverage, and the human review judges meaning |
| D16 | Wait and margin standards used by CHK-1 and CHK-5 | Negative-event quiet period: 1 s (suite's), 3 s, or a marker event (a later event of the same player in the same topic; ordering by GEN-24). Positive events without a spec bound: 10 s or 30 s. H4 margin: 250 ms, 500 ms or 1 s | **1 s; 10 s; 500 ms** | These are the suite's own conventions. Requiring markers would flag every "nothing changes" test. A 500 ms threshold flags real races without flagging CL-069 (≈ 1 s margin, the spec's own example) or CL-096/097 (0.8 s/1.7 s margins). Record the 1 s quiet period as a known limit |
| D17 | May the fixer narrow a display name instead of strengthening a test? | (a) never; (b) only when CHK-3A shows the claim goes beyond the spec; (c) freely | **(b)** | Narrowing claims is the cheapest way to make CHK-1 findings disappear and silently loses coverage |
| D18 | Protected list | (a) tests that are STABLE_RED in CHK-2 and pass CHK-3A, whose expectations the fixer must not change; (b) no list, the fixer decides | **(a)** | Otherwise the fixer will adapt the 17 failing tests to the 7 known system defects |
| D19 | Audit selection and withholding | Selection per §5, or random only; withhold threshold 5 / 10 / 20 % | **§5 selection; 10 %** | Contradictions and L3 flags are where agent errors are both likely and costly |
| D20 | Run CHK-6? | (a) run; (b) skip | **(a)** | It costs about 8 min, and the fix step will change tests whose cross-class coupling is otherwise invisible |
