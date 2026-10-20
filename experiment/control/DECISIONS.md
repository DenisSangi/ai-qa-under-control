# Decisions on the control design

`CHECKS.md`, `SAMPLE-KEY.md` and `sample/` are the output of the control-design agent, unchanged (task:
`experiment/prompts/control-design.md`). The agent listed 20 choices with alternatives in `CHECKS.md` §7. The author
asked Claude for advice on each and accepted it in full. Where a decision below differs from `CHECKS.md`, the decision
applies.

Origin: **A** — the design agent's recommendation, confirmed by Claude; **C** — Claude's variant instead of the agent's.

| D | Decision | Origin |
|---|---|---|
| D1 | (b): 10 runs = 5 on a fresh system + 5 on an aged one | A |
| D2 | (a) is a finding, (b) is info; a disturbed run is repeated once | A |
| D3 | (a): a detection is FAIL or ERROR (ERROR-only detections marked); a run is invalid at ≥ 90 % ERROR and repeated once | A |
| D4 | (b) only: a test red on the base detects a faulty version when its normalized first failure line changes. (c) does not apply: the defects of the base stay by design. Clarification: this counts only for tests whose normalized line is the same in every base run of CHK-2 (not RED_VARIES); two runs of the unchanged suite showed 4 of 24 red executions with per-run data in that line | C (the agent's option without (c), plus the clarification) |
| D5 | (b): secondary pairs are info and go to the audit | A |
| D6 | (a): OWN_TESTS_MISS is a finding (medium) | A |
| D7 | own requirement = all tags; parameterized rows = worst-of | A |
| D8 | (b): the fixer gets the requirement, the part broken and how to observe it, not "what changed" | A |
| D9 | (c): no service sources, TEST-PLAN or TEST-REPORT in the checking agents' folder; calibration the same | A |
| D10 | **One session per batch runs CHK-1, CHK-3A and CHK-5 together**, each with its own brief and its own verdict file; calibration in the same combination. 13 sessions on the suite + 2 batches × R = 2 = 4 for calibration: **17 sessions instead of 51** | C |
| D11 | R = 2, both without misses; the kind of defect is info | A |
| D12 | (b): a brief is revised in general terms, without sample identifiers, at most 2 rounds | A |
| D13 | (a): one pass + audit; (b) for a check whose audit error exceeds 10 % | A |
| D14 | (a): UNCLEAR goes to the human | A |
| D15 | (a): CHK-1 is anchored on the claim in `@DisplayName` | A |
| D16 | quiet period for "no event" 1 s; wait for an event without a spec bound 10 s; H4 margin 500 ms; the 1 s period is a known limit | A |
| D17 | (b): a display name may be narrowed only when CHK-3A shows the claim goes beyond the spec | A |
| D18 | (a): the `protected` list | A |
| D19 | selection per §5; withholding threshold 10 % | A |
| D20 | (a): CHK-6 runs | A |

## Outside the list

| Topic | Decision | Origin |
|---|---|---|
| Starting the agent sessions | without a human: `claude -p` from a script, verdicts written to files | C |
| Model and effort of the checking agents | as in generation: `claude-opus-5-5`, `xhigh`, `--safe-mode` | C |

## Summary

20 of 20 accepted as recommended: 18 from the design agent, 2 Claude's variants (D4, D10). None rejected; no choice of
the author's own.
