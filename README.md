# AI QA Under Control

AI agents write tests fast, and some of those tests check nothing. This project measures how much an AI-generated
API test suite actually catches on a microservice system, and what a QA engineer has to add before and after the
agent works for the suite to be trusted.

One system, one frozen specification, 16 bugs seeded and committed as checksums before any test was written, three
suites written by AI agents:

| Suite | What produced it | Seeded bugs caught |
|---|---|---|
| **A** | an agent with a three-line task and the code, no specification | **12 / 16** |
| **B** | an agent with the specification and a task that names risks and deliverables | **15 / 16** |
| **C1** | B after agent-run checks, a calibrated human audit and an agent that fixed the findings | **16 / 16** |
| **C2** | C1 after the human review of meaning (no findings) | **16 / 16** |

The specification and the task moved more than the control: A → B +3, B → C1 +1. The one bug B missed hid behind a
test that polls until the expected value appears and then asserts it; the control flagged that test, and the fix
caught the bug. In B, 56 of 136 tests (41 %) had at least one finding, most of them checks that observe less than
their names claim.

**Read:** [the report](docs/report/REPORT.md) · [catalog of AI test-writing mistakes](docs/report/AI-MISTAKES.md)

## The process: who does what

Agents produce the volume; the human owns the definition of "correct", before the agent starts and after it
finishes. Every step has a number from the experiment.

| Step | Human | Agents | From the experiment |
|---|---|---|---|
| 1. Define "correct" | decides requirements, risks and the oracle | explore the system and ask questions | an agent asked 47 questions; the author decided 7 findings, 54 questions, 28 clarifications. Without a specification the agent made the code its oracle and pinned real defects as correct: natural bugs caught 1 of 4 (A) against 4 of 4 (B) |
| 2. Split the work | which agent does what: input, output, acceptance | — | separate agents for generation, mutants, control design, checks, fixing; none of them saw the bug catalog |
| 3. Set the task | context, constraints, deliverable, how it is accepted | — | task A: 3 lines; task B: 47 lines plus a 32-line harness shared with A. A → B: +3 seeded bugs |
| 4. Run | does not intervene | write the plan, the checklist and the tests | B: 37 min, $9.27, 136 tests, 0 interventions |
| 5a. Check the volume | designs or accepts the checks, calibrates them on a sample with known defects, audits a sample of verdicts | run the mechanical checks | calibration caught a wrong answer key (8 of 63 cells); audit: 0 of 59 verdicts wrong; 74 findings; B → C1: +1 seeded bug, 73 test defects fixed |
| 5b. Check the meaning | reviews the cases against the specification, all of them | prepare the material | 30 min, 0 findings: C1 → C2 = 0 |
| 6. Fix the cause | changes the task, not the result | fix the findings by the rule "the specification is the oracle" | 30 of 66 test findings were one pattern, fixed once in a helper |
| 7. Measure | owns the rule, fixed before the run | run the matrix | 3 suites × 19 runs, 184 min of system time, 0 model tokens |

## The experiment

1. **System under test** — a small real-money gaming wallet on microservices (below).
2. **Specification** — 69 numbered requirements for deposits with idempotency, deposit limits, withdrawals, game
   sessions and the leaderboard ([`docs/spec/SPEC.md`](docs/spec/SPEC.md)). This is the test oracle.
3. **Generation** — agents write the test plan and automated API tests: branch A with a minimal task, branch B with
   the specification and the author's task ([`experiment/prompts/`](experiment/prompts)).
4. **Control** — seven checks over suite B: empty checks, instability, link to the requirement, failure on one
   faulty version per requirement, instability by construction, order dependence
   ([`experiment/control/`](experiment/control)). The faulty versions come from an agent that never saw the seeded
   bugs.
5. **Measurement** — every suite runs once per seeded bug on a fresh system; the rule was committed before the first
   run ([`experiment/MEASURE.md`](experiment/MEASURE.md)).
6. **Findings** — the results table and the catalog of AI test-writing mistakes, each with a real example.

Seeded bugs are tagged `money` (wallet correctness that matters in fintech and iGaming alike) and `igaming`
(gaming-specific behaviour). Agents: Claude Code CLI 2.1.302, model `claude-opus-5-5`, effort `xhigh`. Limitations,
including one generation run per branch and one model family for bugs, tests and checks, are in
[section 10 of the report](docs/report/REPORT.md#10-limitations).

## Repository map

| Path | Contents |
|---|---|
| [`docs/spec/`](docs/spec) | the specification (tag `spec-v1`) |
| [`docs/report/`](docs/report) | the report and the catalog of AI mistakes |
| [`experiment/prompts/`](experiment/prompts) | every task given to an agent |
| [`experiment/seeded-bugs/`](experiment/seeded-bugs) | the 16 seeded bugs as patches; checksums in [`seeded-bugs.sha256`](experiment/seeded-bugs.sha256), committed before generation |
| [`experiment/control/`](experiment/control) | control design, calibration sample, adjudication, mutants |
| [`experiment/`](experiment) | scripts: snapshot for generation, bug switch, checks, measurement |
| [`results/suites/`](results/suites) | suites A, B and C1 exactly as the agents delivered them |
| [`results/control/`](results/control) | the findings given to the fixing agent |
| [`results/measure/`](results/measure) | caught / missed per suite and bug, with the detecting tests |
| `player-service/`, `analytics-service/`, `infra/` | the system under test |

## System under test

A small training microservice stand, originally built as a QA practice playground. It is the object of testing
here, not the subject of the project.

```
 player-service :8080  ──REST──▶  WireMock :8089  (payment provider stub)
    │   │   │ └──gRPC──▶ analytics-service :8081 / :6565
    │   │   └──Kafka topic game-events──▶ analytics-service ──▶ Postgres (schema analytics)
    │   └── Redis :6379 (leaderboard cache, TTL 30s)
    └── Postgres :5433 (players / game_sessions / transactions, Flyway)
```

Java 17 · Spring Boot 3.3 · Maven · Postgres 16 · Kafka 3.8 · Redis 7 · gRPC · WireMock

## Quick start

```bash
docker compose up -d     # infrastructure (Postgres on host port 5433)
./run.sh                 # build and start both services, wait for health
open http://localhost:8080/
```

`./run.sh --no-build` restarts without rebuilding. Kafka UI: `docker compose --profile tools up -d`
→ http://localhost:8090.

## Running a suite against a seeded bug

The measured base is the tag `sut-v2`. [`experiment/bugs.sh`](experiment/bugs.sh) runs it with one patch applied,
rebuilt from scratch, from a copy outside the repository (see the script header for the folders it uses):

```bash
experiment/bugs.sh on B15 --fresh                     # the base with seeded bug B15
(cd results/suites/C1/api-tests && mvn -B test)       # the suite fails on B15
experiment/bugs.sh off --fresh                        # back to the base
```

The suites ran on JDK 22 and expect a fresh system. Some of their tests are red on the base by design: they catch
natural bugs the system keeps on purpose, listed in the report.

## License

[MIT](LICENSE): the code, the suites, the specification, the report and the prompts.
