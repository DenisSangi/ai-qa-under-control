#!/usr/bin/env bash
# Seeded-bug switch. Each bug is one patch against the base of the system under
# test, so the code a generation agent reads has no flags, profiles or spare
# beans pointing at a bug.
#
#   experiment/bugs.sh edit                 reset the measurement checkout to the base, print its path
#   experiment/bugs.sh save <ID>            write the checkout's changes to <bugs dir>/<ID>.patch
#   experiment/bugs.sh check [ID...]        each patch applies to the base on its own, service code only
#   experiment/bugs.sh on <ID> [--fresh]    run the base with this one patch applied
#   experiment/bugs.sh off [--fresh]        run the base
#   experiment/bugs.sh status               which bug is running, and from where
#   experiment/bugs.sh hash                 write experiment/seeded-bugs.sha256 (commit it before generation)
#
# Environment:
#   AQC_BASE      base ref (default: sut-v1)
#   AQC_BUGS_DIR  catalog and patches (default: notes/bugs, private until the results are out)
#   AQC_MEASURE   measurement checkout, a git worktree next to the repository
#                 (default: ../ai-qa-under-control-measure)
#   AQC_RUN       folder the stand runs from (default: ~/services/arcade-wallet)
#   APP_TESTCLOCK_ENABLED  test clock of the running stand (default: true)
#
# The stand runs from AQC_RUN: a copy of the measurement checkout's stand files
# (experiment/stand-files) with no git. Whatever a test agent sees of the
# running stand (health details, logs, processes, docker) leads there, not to
# this repository. Services are rebuilt from scratch on every switch, so no
# class from a previous patch survives, and start with a clean environment.
# The docker compose infrastructure runs from the same folder and is shared;
# --fresh recreates it with empty volumes (Kafka UI stays down) and is required
# for a patch that touches a database migration.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
BASE=${AQC_BASE:-sut-v1}
BUGS=${AQC_BUGS_DIR:-$ROOT/notes/bugs}
MEASURE=${AQC_MEASURE:-$(dirname "$ROOT")/$(basename "$ROOT")-measure}
RUN=${AQC_RUN:-$HOME/services/arcade-wallet}
STAND_FILES=$(grep -v '^#' "$ROOT/experiment/stand-files")
HASH_FILE="$ROOT/experiment/seeded-bugs.sha256"

# The measured stand is the spec's environment, which includes the test clock.
export APP_TESTCLOCK_ENABLED=${APP_TESTCLOCK_ENABLED:-true}

# A seeded bug changes service code only: not tests, not the payment-provider
# stub, not the build.
ALLOWED='^(player-service|analytics-service)/src/main/'
MIGRATION='/db/migration/'

die() { echo "error: $*" >&2; exit 1; }

base_commit() {
  git -C "$ROOT" rev-parse --verify --quiet "$BASE^{commit}" \
    || die "unknown base ref: $BASE (tag it: git tag sut-v1)"
}

patch_file() {
  case $1 in ''|*[!A-Za-z0-9_-]*) die "bad bug ID: '$1'" ;; esac
  echo "$BUGS/$1.patch"
}

state_file() { echo "$(git -C "$MEASURE" rev-parse --absolute-git-dir)/aqc-bug"; }

# Measurement checkout at the base, with nothing else in it.
reset_checkout() {
  local commit
  commit=$(base_commit)
  if [ ! -e "$MEASURE/.git" ]; then
    [ ! -e "$MEASURE" ] || die "$MEASURE exists and is not a checkout of this repository"
    git -C "$ROOT" worktree prune
    git -C "$ROOT" worktree add -q --detach "$MEASURE" "$commit"
  fi
  git -C "$MEASURE" reset -q --hard
  git -C "$MEASURE" clean -qfdx
  git -C "$MEASURE" checkout -q --detach "$commit"
  rm -f "$(state_file)"
}

# Fails unless the patch applies to the base by itself and touches only
# service code. Prints "migration" when it changes a database migration.
check_one() {
  local id=$1 p paths path idx commit
  p=$(patch_file "$id")
  [ -s "$p" ] || die "$id: no patch at $p"
  paths=$(git -C "$ROOT" apply --numstat "$p" | cut -f3)
  [ -n "$paths" ] || die "$id: patch changes nothing"
  for path in $paths; do
    echo "$path" | grep -qE "$ALLOWED" || die "$id: changes $path (only service code under src/main is allowed)"
  done
  commit=$(base_commit)
  idx=$(mktemp)
  GIT_INDEX_FILE=$idx git -C "$ROOT" read-tree "$commit"
  if ! GIT_INDEX_FILE=$idx git -C "$ROOT" apply --cached --check "$p" 2>/dev/null; then
    rm -f "$idx"
    die "$id: does not apply to $BASE"
  fi
  rm -f "$idx"
  if echo "$paths" | grep -q "$MIGRATION"; then echo migration; fi
}

running_from() {
  local pid
  pid=$(pgrep -f 'player-service-1.0.0.jar' | head -1)
  [ -n "$pid" ] || { echo "not running"; return; }
  lsof -p "$pid" 2>/dev/null | grep -o '/.*player-service-1.0.0.jar' | head -1 || echo "pid $pid"
}

fresh_option() {
  [ -z "${1:-}" ] || [ "$1" = --fresh ] || die "unknown option: $1"
  echo "${1:-}"
}

# Copies the stand files of the measurement checkout, patch included, into the
# run folder. Files are replaced in place: the stub container mounts
# infra/wiremock from there, and removing that folder would cut the mount.
# Build output is dropped, so the services are rebuilt from scratch.
export_stand() {
  local tmp
  case $RUN in /*) ;; *) die "AQC_RUN must be an absolute path: $RUN" ;; esac
  case "$RUN/" in "$ROOT"/*|"$MEASURE"/*) die "AQC_RUN is inside the repository or its measurement checkout: $RUN" ;; esac
  case "$ROOT/" in "$RUN"/*) die "AQC_RUN contains the repository: $RUN" ;; esac
  if [ -e "$RUN" ] && [ -n "$(ls -A "$RUN")" ]; then
    [ -f "$RUN/run.sh" ] && [ -f "$RUN/docker-compose.yml" ] && [ ! -e "$RUN/.git" ] \
      || die "$RUN holds something other than a copy of the stand: remove it or set AQC_RUN"
  fi
  tmp=$(mktemp -d)
  # shellcheck disable=SC2086  # STAND_FILES is a word list
  (cd "$MEASURE" && tar -cf - $STAND_FILES) | tar -xf - -C "$tmp"
  cp "$ROOT/experiment/snapshot/README.md" "$tmp/README.md"
  mkdir -p "$RUN"
  rsync -a --delete "$tmp/" "$RUN/"
  rm -rf "$tmp"
}

# Runs run.sh in the run folder with a clean environment, so the services'
# process environment (PWD, OLDPWD, the caller's variables) names nothing else.
run_services() {
  local mvn v
  local -a vars
  mvn=$(command -v mvn) || die "mvn is not on PATH"
  vars=(HOME="$HOME" USER="${USER:-$(id -un)}" LOGNAME="${LOGNAME:-$(id -un)}" LANG=en_US.UTF-8
        PATH="$(dirname "$mvn"):/usr/bin:/bin:/usr/sbin:/sbin")
  for v in $(compgen -e | grep -E '^(APP_[A-Z0-9_]+|JAVA_HOME)$'); do vars+=("$v=${!v}"); done
  (cd "$RUN" && env -i "${vars[@]}" ./run.sh "$@")
}

# Builds and starts the stand from the run folder. On an empty Kafka,
# analytics-service can subscribe before player-service creates game-events:
# the topic is created with one partition, grows to three, and the consumer
# keeps partition 0 only. A known stand defect outside the spec, left as is;
# after a fresh start the services are restarted to get all three.
start_stand() {
  export_stand
  if [ -n "$1" ]; then
    # Infrastructure started from the repository itself (the developer setup)
    # holds the same container names.
    (cd "$ROOT" && docker compose --profile tools down -v)
    (cd "$RUN" && docker compose --profile tools down -v && docker compose up -d --wait)
    run_services
    run_services --no-build
  else
    run_services
  fi
}

cmd=${1:-}
[ $# -gt 0 ] && shift

case $cmd in
  edit)
    reset_checkout
    echo "$MEASURE is at $BASE: change the service code there, then: $0 save <ID>"
    ;;

  save)
    [ $# -eq 1 ] || die "usage: $0 save <ID>"
    p=$(patch_file "$1")
    [ ! -e "$p" ] || die "$p exists; remove it first to replace it"
    mkdir -p "$BUGS"
    git -C "$MEASURE" add -A
    git -C "$MEASURE" diff --cached > "$p"
    git -C "$MEASURE" reset -q
    [ -s "$p" ] || { rm -f "$p"; die "the checkout has no changes"; }
    if ! kind=$(check_one "$1"); then rm -f "$p"; exit 1; fi
    echo "saved $p${kind:+ (touches a migration: on needs --fresh)}"
    ;;

  check)
    ids="$*"
    if [ -z "$ids" ]; then
      ids=$(cd "$BUGS" 2>/dev/null && ls -- *.patch 2>/dev/null | sed 's/\.patch$//') || true
    fi
    [ -n "$ids" ] || die "no patches in $BUGS"
    for id in $ids; do
      kind=$(check_one "$id")
      echo "ok $id${kind:+ (migration: on needs --fresh)}"
    done
    ;;

  on)
    [ $# -ge 1 ] || die "usage: $0 on <ID> [--fresh]"
    id=$1
    fresh=$(fresh_option "${2:-}")
    kind=$(check_one "$id")
    [ -z "$kind" ] || [ -n "$fresh" ] || die "$id changes a database migration: run with --fresh (recreates the database)"
    reset_checkout
    git -C "$MEASURE" apply "$(patch_file "$id")"
    start_stand "$fresh"
    echo "$id $(shasum -a 256 "$(patch_file "$id")" | cut -d' ' -f1)" > "$(state_file)"
    echo "bug $id is on"
    ;;

  off)
    fresh=$(fresh_option "${1:-}")
    reset_checkout
    start_stand "$fresh"
    echo "no bug is on"
    ;;

  status)
    if [ -e "$MEASURE/.git" ] && [ -f "$(state_file)" ]; then
      echo "bug on: $(cut -d' ' -f1 "$(state_file)")"
    elif [ -e "$MEASURE/.git" ]; then
      echo "bug on: none"
    else
      echo "no measurement checkout yet"
    fi
    echo "player-service runs from: $(running_from)"
    ;;

  hash)
    [ -f "$BUGS/CATALOG.md" ] || die "no $BUGS/CATALOG.md"
    ls "$BUGS"/*.patch >/dev/null 2>&1 || die "no patches in $BUGS"
    (cd "$BUGS" && shasum -a 256 CATALOG.md *.patch) > "$HASH_FILE"
    cat "$HASH_FILE"
    echo "written to $HASH_FILE; verify later with: (cd <bugs dir> && shasum -a 256 -c $HASH_FILE)"
    ;;

  *)
    sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
