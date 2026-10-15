# Arcade

A small real-money gaming wallet on microservices.

```
 player-service :8080  ──REST──▶  WireMock :8089  (payment provider stub)
    │   │   │ └──gRPC──▶ analytics-service :8081 / :6565
    │   │   └──Kafka topic game-events──▶ analytics-service ──▶ Postgres (schema analytics)
    │   └── Redis :6379 (leaderboard cache)
    └── Postgres :5433 (Flyway)
```

Java 17 · Spring Boot 3.3 · Maven · Postgres 16 · Kafka 3.8 · Redis 7 · gRPC · WireMock

## Run

```bash
docker compose up -d     # infrastructure (Postgres on host port 5433)
./run.sh                 # build and start both services, wait for health
```

UI: http://localhost:8080/ · Swagger: http://localhost:8080/swagger-ui/index.html

`./run.sh --no-build` restarts without rebuilding. Kafka UI: `docker compose --profile tools up -d`
→ http://localhost:8090.
