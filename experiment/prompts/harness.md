## Test environment and delivery

**Where the tests go.** Create a standalone Maven project in `api-tests/` at the repository root:
its own `pom.xml`, not a module of the root build. Java 17, JUnit 5, any other libraries from Maven
Central. The tests reach the system over the network only: do not depend on the services' modules
and do not copy their classes.

**The system is already running.** It was started for you with the test clock enabled. Do not
start, stop, restart or rebuild it, and do not run `./run.sh`.

| Component | Address |
|---|---|
| REST API | `http://localhost:8080` (OpenAPI: `/swagger-ui/index.html`) |
| Leaderboard WebSocket | `ws://localhost:8080/ws/leaderboard` |
| Kafka | `localhost:9094` |
| Payment provider stub (WireMock) | `http://localhost:8089` |
| Database | PostgreSQL `localhost:5433`, database `arcade`, user `arcade_reader`, password `arcade_reader` |

**Shared state.**
- The system is long-lived and shared between runs. It may already hold data from earlier runs, and
  its clock may already be moved forward: do not assume an empty database or the initial clock.
- The database is for reading only. Do not use any other database user.
- Do not stop or recreate containers. Change nothing outside `api-tests/`: not the services, the
  build, the compose file or the stub's mapping files.
- If the system is not reachable, stop and say so. Do not start it yourself.

**How the tests will be run.** `mvn -B test` in `api-tests/` against the running system; results
are read from the Surefire reports. The whole suite should finish within 5 minutes.

**Before you finish.** Run the suite. When a test fails, decide whether the test or the system is
wrong. Do not change an expected result only to make a test pass: leave a test that shows a defect
failing, and list such tests with the reason in your final message.
