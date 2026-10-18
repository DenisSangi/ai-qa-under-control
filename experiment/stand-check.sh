#!/usr/bin/env bash
# Checks that the running stand is in its initial state: the base with no
# seeded bug, the seed data only, the test clock at zero, empty Kafka topics
# with all partitions assigned, the payment-provider stub as defined in its
# mapping files, and nothing that points a test agent back at this repository.
# Every generation run starts from this state, so the runs of different
# branches see the same system.
#
#   experiment/bugs.sh off --fresh && experiment/stand-check.sh
#
# Prints one line per check and exits 1 if any check fails.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
BASE=${AQC_BASE:-sut-v2}
RUN=${AQC_RUN:-$HOME/services/arcade-wallet}
API=http://localhost:8080
ANALYTICS=http://localhost:8081
STUB=http://localhost:8089

failed=0
check() { # name expected actual
  if [ "$2" = "$3" ]; then
    echo "ok    $1: $3"
  else
    echo "FAIL  $1: expected $2, got $3"
    failed=1
  fi
}

sql() { docker exec aqc-postgres psql -U arcade -d arcade -Atc "$1" 2>&1 || true; }
kafka() { docker exec aqc-kafka /opt/kafka/bin/"$@" --bootstrap-server localhost:9092 2>/dev/null || true; }
http_code() { curl -s -o /dev/null -w '%{http_code}' -m 5 "$1" || true; }

# --- services ----------------------------------------------------------------

status=$("$ROOT/experiment/bugs.sh" status)
check "seeded bug" "bug on: none" "$(echo "$status" | grep '^bug on:')"
check "player-service jar" "$RUN/player-service/target/player-service-1.0.0.jar" \
  "$(echo "$status" | sed -n 's/^player-service runs from: //p')"
check "player-service health" 200 "$(http_code "$API/actuator/health")"
check "analytics-service health" 200 "$(http_code "$ANALYTICS/actuator/health")"
check "test clock" '"PT0S"' "$(curl -s -m 5 "$API/api/test/clock" | python3 -c 'import sys,json;print(json.dumps(json.load(sys.stdin).get("offset")))' 2>/dev/null || echo unavailable)"
check "kafka-ui container" "" "$(docker ps -q --filter name=aqc-kafka-ui)"

# --- database ----------------------------------------------------------------

check "migrations applied" 5 "$(sql 'select count(*) from flyway_schema_history where success')"
check "players (count, max id)" "200|200" "$(sql 'select count(*), max(id) from players')"
check "game sessions (count, max id)" "5000|5000" "$(sql 'select count(*), max(id) from game_sessions')"
check "transactions (count, max id)" "3000|3000" "$(sql 'select count(*), max(id) from transactions')"
check "idempotency keys" 0 "$(sql 'select count(*) from idempotency_keys')"
check "deposit limits" 0 "$(sql 'select count(*) from deposit_limits')"

# --- kafka -------------------------------------------------------------------

for topic in player-events game-events; do
  check "$topic partitions" 3 "$(kafka kafka-topics.sh --describe --topic "$topic" | grep -c 'Partition: ')"
  check "$topic messages" 0 "$(kafka kafka-get-offsets.sh --topic "$topic" | awk -F: '{s += $3} END {print s + 0}')"
done
check "analytics consumer partitions" "game-events-0, game-events-1, game-events-2" \
  "$(grep -o 'partitions assigned: \[[^]]*\]' /tmp/analytics-service.log 2>/dev/null | tail -1 | sed 's/.*\[\(.*\)\]/\1/')"

# --- payment-provider stub ---------------------------------------------------

check "stub fixed delay" 0 "$(curl -s -m 5 "$STUB/__admin/settings" | python3 -c 'import sys,json;print(json.load(sys.stdin)["settings"].get("fixedDelay", 0))' 2>/dev/null || echo unavailable)"
check "stub mappings" "$(ls "$RUN"/infra/wiremock/mappings/*.json | wc -l | tr -d ' ')" \
  "$(curl -s -m 5 "$STUB/__admin/mappings" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["mappings"]))' 2>/dev/null || echo unavailable)"
check "stub request journal" 0 "$(curl -s -m 5 "$STUB/__admin/requests" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["requests"]))' 2>/dev/null || echo unavailable)"

# --- isolation ---------------------------------------------------------------
# A test agent reads the health details, the logs, the processes and docker.
# None of them may lead to this repository: services and infrastructure run
# from a copy of the base's stand files with no git, and the services get a
# clean environment.

pids=$(pgrep -f 'player-service-1.0.0.jar|analytics-service-1.0.0.jar' || true)
one_line() { sort -u | tr '\n' ' ' | sed 's/ $//'; }

base_copy=$(mktemp -d)
# shellcheck disable=SC2046  # the file is a word list
git -C "$ROOT" archive --format=tar "$BASE" -- $(grep -v '^#' "$ROOT/experiment/stand-files") | tar -x -C "$base_copy"
cp "$ROOT/experiment/snapshot/README.md" "$base_copy/README.md"
# The stub container creates an empty infra/wiremock/__files when it starts.
differences=$( (diff -rq -x target -x __files "$base_copy" "$RUN" 2>&1 || true) | sed "s|$base_copy|<base>|g" | head -3 | tr '\n' ';')
check "run folder vs the stand files of $BASE" identical "${differences:-identical}"
check "files in the stub's __files" 0 "$(find "$RUN/infra/wiremock/__files" -mindepth 1 2>/dev/null | wc -l | tr -d ' ')"
rm -rf "$base_copy"

check "service working folders" "$RUN" \
  "$(for p in $pids; do lsof -a -p "$p" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p'; done | one_line)"
check "health disk path" "$RUN/." \
  "$(for url in "$API" "$ANALYTICS"; do curl -s -m 5 "$url/actuator/health" | python3 -c 'import sys,json;print(json.load(sys.stdin)["components"]["diskSpace"]["details"]["path"])' 2>/dev/null || echo unavailable; done | one_line)"
check "repository or session in service environment" 0 \
  "$(for p in $pids; do ps eww -o command= -p "$p"; done | grep -oiE 'ai.qa|under.control|claude|IdeaProjects' | wc -l | tr -d ' ')"
check "compose project folder" "$RUN" \
  "$(for c in aqc-postgres aqc-kafka aqc-redis aqc-wiremock; do docker inspect -f '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$c" 2>/dev/null || echo "$c missing"; done | one_line)"
check "stub mount" "$RUN/infra/wiremock" \
  "$(docker inspect -f '{{range .Mounts}}{{.Source}}{{end}}' aqc-wiremock 2>/dev/null || echo missing)"

# --- logs --------------------------------------------------------------------

check "ERROR lines in service logs" 0 \
  "$(cat /tmp/player-service.log /tmp/analytics-service.log 2>/dev/null | grep -c ' ERROR ' || true)"

if [ "$failed" -ne 0 ]; then
  echo "the stand is NOT in its initial state" >&2
  exit 1
fi
echo "the stand is in its initial state"
