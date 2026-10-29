# Measurement rule — stage 6

Fixed before the first run of any suite against the seeded bugs. Proposed by Claude, accepted by the author on
29.10.2026 without changes. The seeded bugs were frozen by `experiment/seeded-bugs.sha256` before generation; no
suite was run against them until the control set was tagged C2.

## Suites

| Column | Suite | Commit | Base reference (CHK-2) |
|---|---|---|---|
| A | generation folder of A, tag `A` | `f18630b` | new, for this measurement |
| B | generation folder of B, tag `B` | `687f41f` | the CHK-2 of the control stage: same tag, same base, stand files unchanged since |
| C1 | control folder, tag `C1` | `7a26ff3` | new, for this measurement |
| C2 | control folder, tag `C2` | `7a26ff3` | — |

C2 is the same commit as C1: the author's review found nothing, so the C2 column equals C1 and is not run
separately. The first run of A (`A-run1`) stays outside the table and is not measured.

## Runs

- Base `sut-v2`, every suite on JDK 22, as its generating agent ran it.
- Base reference per suite: CHK-2 of `experiment/control/CHECKS.md` — 10 runs, 5 on a fresh stand and 5 on an aged
  one (`checks.py tags` and `checks.py stability`).
- `experiment/matrix.py` per suite: a `base` column, then one column per seeded bug B01–B16. Every column starts from
  a fresh stand (`bugs.sh on <ID> --fresh`).
- B04 is a race. Its catalog entry, fixed before generation, counts it as caught when a test fails in at least one
  of 3 runs: columns `B04`, `B04#2`, `B04#3`. Every other bug runs once.

## When a bug counts as caught

The rule is D4 of the control design with its clarification (`experiment/control/DECISIONS.md`), the same one CHK-4
applied to the faulty versions (`checks.py measure`).

1. An execution **detects** a bug when it is FAIL or ERROR with the bug and either:
   - it is STABLE_GREEN in every CHK-2 run, or
   - it is STABLE_RED in every CHK-2 run with one normalized first failure line, and the line with the bug is a
     different one.
2. A test counts only when every one of its executions is usable as in item 1. A test that is unstable on the base,
   red there with a varying line, or green in CHK-2 but red in the matrix's own base column does not count, for any
   bug. Such tests are listed in `info.jsonl`.
3. A run is invalid when it has no passing execution or at least 90 % ERROR (D3). An invalid run is not repeated
   here and does not count.
4. A suite **catches** a bug when at least one valid run of the bug has at least one detecting test. A bug with no
   valid run is NOT_EVALUATED.

The result per suite is `bugs.csv`: per bug, its runs, valid runs, runs that detect it, CAUGHT / MISSED /
NOT_EVALUATED, the detecting tests, and those that detect only through ERROR.

## Afterwards, for the report only

- Pairs of a faulty version of the control stage and a seeded bug are marked after C2 by the rule of 19.10: the same
  requirement and the same observable violation (same input, same wrong result). B→C is reported over all 16 bugs
  and over the bugs without such a pair. Nothing is removed.
- Time and cost per suite come from the run records of stages 4 and 5; this measurement uses no model tokens.
