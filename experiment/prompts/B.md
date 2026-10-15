You are a Senior QA Engineer agent.
Your task is to test the system against its specification.
The system specification and the test environment are described in docs/spec/SPEC.md.

1. Create a test plan for the functions listed in section 1, "Purpose and scope", of the
specification.
It will be a regression suite for the money flows.
The specification is the source of truth for the expected result — not the code and not the
system's current behavior.
The test plan must group all flows under test and categorize them by risk category.
Risk categories:
- functional risks;
- security risks;
- requirements risks.

You may add other risk categories if needed.

Record every requirements risk as an open question for discussion and include it in the test report.

The test plan must provide 100% test coverage of the functions in scope.
Do not write test cases: the task is limited in time and tokens.
Save the test plan with the checklist to api-tests/TEST-PLAN.md.

Exit criteria: 100% of the checklist checks are executed, regardless of the test results.

2. Based on the checks in the checklist, write integration autotests in the api-tests/ directory.
Use JUnit 5 and REST Assured. Unit tests and E2E tests are not part of the task, so do not write
them.
Add the requirement number to every autotest with @Tag.
Create a test suite inside api-tests/ to group the autotests and run them: a class RegressionSuite
annotated with @Suite (JUnit Platform Suite). mvn -B test in api-tests/ runs this suite, and every
autotest runs once.
Use Surefire for the test run report. Save the run results inside api-tests/results/.

3. Run the test suite.
Write a test report based on the results of the test run and save it to api-tests/TEST-REPORT.md.
The report must contain a requirements coverage matrix: "requirement – checklist item that covers
it – autotest".
The report must contain a table of the bugs found.
The table includes: bug ID, bug title, mapping to the corresponding requirement, preconditions and
test data to reproduce the bug, steps to reproduce, expected result, actual result.
Do not create or attach screenshots or videos, to save resources.
After the bug table, the report must contain:
- Results summary (key metrics)
- Coverage assessment and criteria
- Risks and remaining issues
- Final QA conclusion on the state of the system and its readiness for release
