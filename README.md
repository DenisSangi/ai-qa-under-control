# AI QA Under Control

> **Status: work in progress.** The experiment has not been run yet; results will appear here.

AI agents write tests fast — but some of those tests check nothing. This project measures how
much an AI-generated test suite actually catches on a real microservice system, and what a
human-owned QA process has to add to make it trustworthy.

## The experiment

1. **System under test** — a small real-money gaming wallet on microservices (below).
2. **Specification** — human-written requirements for the core flows. This is the test oracle.
3. **Generation** — AI agents produce test cases and automated API/integration tests from the spec.
4. **Control** — human review gates: cases against requirements, every test must fail on a broken
   build, empty assertions, flakiness.
5. **Measurement** — a catalog of realistic seeded bugs, each switchable on and off. How many does
   the AI-only suite catch, and how many after the control gates? How much time did each take?
6. **Findings** — results table and a catalog of AI test-writing mistakes, each with a real example.

Seeded bugs are tagged `money` (wallet correctness that matters in fintech and iGaming alike) and
`igaming` (gaming-specific behaviour).

## System under test

A small training microservice stand, originally built as a QA practice playground. It is the
object of testing here, not the subject of the project.

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
