#!/usr/bin/env bash
# Stop -> rebuild -> start both services, in that order.
#
# Why a script: a running Spring Boot fat jar must NEVER be overwritten by a
# rebuild. Nested classes are loaded lazily, so a replaced jar makes every
# not-yet-loaded code path die with NoClassDefFoundError and the HTTP request
# hangs with no response (see "Troubleshooting" in README.md).
set -euo pipefail

cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/openjdk-22.0.2/Contents/Home}"
export JAVA_HOME
JAVA="$JAVA_HOME/bin/java"

echo "==> stopping running services"
pkill -f 'player-service-1.0.0.jar' 2>/dev/null || true
pkill -f 'analytics-service-1.0.0.jar' 2>/dev/null || true
sleep 2

if [ "${1:-}" != "--no-build" ]; then
  echo "==> building (JAVA_HOME=$JAVA_HOME)"
  mvn -B -q -DskipTests install
fi

echo "==> starting player-service   (log: /tmp/player-service.log)"
nohup "$JAVA" -jar player-service/target/player-service-1.0.0.jar > /tmp/player-service.log 2>&1 &

echo "==> starting analytics-service (log: /tmp/analytics-service.log)"
nohup "$JAVA" -jar analytics-service/target/analytics-service-1.0.0.jar > /tmp/analytics-service.log 2>&1 &

wait_for() {
  local name=$1 url=$2
  for _ in $(seq 1 40); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' -m 3 "$url" || true)" = "200" ]; then
      echo "    $name UP"
      return 0
    fi
    sleep 2
  done
  echo "    $name did NOT come up — check its log" >&2
  return 1
}

echo "==> waiting for health"
wait_for "player-service   " http://localhost:8080/actuator/health
wait_for "analytics-service" http://localhost:8081/actuator/health

cat <<'EOF'

Ready:
  playground   http://localhost:8080/
  swagger      http://localhost:8080/swagger-ui/index.html
  kafka ui     http://localhost:8090  (docker compose --profile tools up -d)
EOF
