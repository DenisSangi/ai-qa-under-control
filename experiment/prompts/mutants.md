You are a Senior QA Engineer agent.
Your task is to prepare faulty versions of the service for mutation testing: for every numbered
requirement of the specification, one small code change that makes the service stop meeting that
requirement. These versions will show whether a test suite notices when a requirement breaks.

The specification is docs/spec/SPEC.md. Numbered requirements have IDs such as GEN-01 or DEP-02.
The service is player-service.

Work from the code and the specification only. There is no running system for this task: do not
start one (no ./run.sh, no docker) and do not send requests to anything on localhost.

**What each change must be.**
1. It breaks the requirement as the specification states it: a client that follows the
   specification gets a different result. If the requirement has several parts, break one and name
   it.
2. The difference is visible through the observation points of sections 10 and 11 of the
   specification: REST, the leaderboard WebSocket, Kafka, the database (read-only) or the payment
   provider's request journal. If it shows only under concurrent requests or after the test clock
   moves, say so.
3. It is narrow: it breaks as few other requirements as possible, and the main path of the flow
   keeps working. List the other requirements it also breaks.
4. It is small and plausible: the kind of mistake a developer could make while implementing the
   requirement, not sabotage.
5. It changes existing files under player-service/src/main/ only. No new files, no database
   migrations (db/migration), no build files, no other folders.
6. It applies to the original code on its own: changes do not build on each other.
7. The project still builds with it: mvn -B -q -DskipTests package from the root.
8. No two changes are the same.

If the code already does not meet a requirement, a change cannot make it fail: do not write one.
If a requirement cannot be broken within these rules, do not write one either. List both kinds
with the reason; for "already not met", give the file and line.

**Output.**
- mutants/M01.patch, M02.patch, … in the order of the specification, one per requirement. Make
  each one with git diff against the original code, then restore the original code before the next
  one. Do not commit.
- mutants/MUTANTS.md with two tables:
  1. One row per patch: ID, requirement, the part broken, file(s), what changed (one line), how to
     observe it (preconditions and request → expected by the specification → result with the
     change), other requirements it also breaks.
  2. One row per requirement without a patch: requirement, reason (already not met, with file and
     line / cannot be broken within the rules / not observable).

**Before you finish.**
- Every numbered requirement of the specification appears exactly once, in one of the two tables.
- Each patch applies to the original code with git apply --check, and the project builds with it.
- The working tree is the original code plus mutants/ (and build output).

In your final message, give the number of requirements, of patches and of requirements without a
patch.
