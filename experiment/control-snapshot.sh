#!/usr/bin/env bash
# Builds the working folder for the control step: a branch-B snapshot of the
# system (code and spec) plus one generated suite exactly as delivered, without
# its run reports. Both go into one "Import" commit, so whatever the control
# agents change later reads as one diff per step. The suite's own repository,
# its tag and history stay behind.
#
#   experiment/control-snapshot.sh <dest> <suite repo> <suite tag> [ref]
#
# <ref> is the base the code comes from (default: sut-v2, the measured base).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"

usage() { echo "usage: $0 <dest> <suite repo> <suite tag> [ref]" >&2; exit 2; }
die() { echo "error: $*" >&2; exit 1; }

[ $# -ge 3 ] || usage
DEST=$1
SUITE=$2
TAG=$3
REF=${4:-sut-v2}

git -C "$SUITE" rev-parse --verify --quiet "$TAG^{commit}" >/dev/null \
  || die "no tag $TAG in $SUITE"
git -C "$SUITE" cat-file -e "$TAG:api-tests" 2>/dev/null \
  || die "$TAG in $SUITE has no api-tests/"

# The snapshot itself: allowlist, neutral README, checks on the destination
# path and on words that must not reach an agent.
"$ROOT/experiment/snapshot.sh" B "$DEST" "$REF"
DEST=$(cd "$DEST" && pwd -P)

# The suite as delivered, minus the reports of the generating agent's last run.
git -C "$SUITE" archive --format=tar "$TAG" -- api-tests \
  | tar -x -C "$DEST" --exclude 'api-tests/results'

git -C "$DEST" add -A
git -C "$DEST" -c user.name=import -c user.email=import@localhost commit -q --amend --no-edit

echo "control folder: $DEST"
echo "  suite $TAG from $SUITE, $(git -C "$DEST" ls-files api-tests | wc -l | tr -d ' ') files in api-tests/"
echo "  $(git -C "$DEST" ls-files | wc -l | tr -d ' ') files, one commit $(git -C "$DEST" rev-parse --short HEAD)"
