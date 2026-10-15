#!/usr/bin/env bash
# Builds the working folder for one test-generation run: a copy of the system
# under test with no git history, no project notes and nothing that describes
# the experiment.
#
#   experiment/snapshot.sh A|B <dest> [ref]
#
# Branch A gets the code; branch B also gets docs/spec/. Files come from <ref>
# (default: the tag sut-v1) through an explicit allowlist, so a file added to
# the repository later cannot leak into a snapshot by accident. Developer tests
# stay out: every test in a measured suite must be the agent's own.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"

usage() { echo "usage: $0 A|B <dest> [ref]" >&2; exit 2; }
die() { echo "error: $*" >&2; exit 1; }

[ $# -ge 2 ] || usage
BRANCH=$1
DEST=$2
REF=${3:-sut-v1}

ALLOW="pom.xml docker-compose.yml run.sh infra/wiremock
  proto/pom.xml proto/src/main
  player-service/pom.xml player-service/Dockerfile player-service/src/main
  analytics-service/pom.xml analytics-service/Dockerfile analytics-service/src/main"

# Words that must not reach a generation agent. Branch A must not see the
# spec's requirement IDs either.
DENY='experiment|seeded|planted|trainer|playground|kept simple|on purpose|anti-pattern|docs/theory|under.control|ai.qa|system under test|qa exercise|natural bug|probe|catalog|portfolio|claude|notes/'

case $BRANCH in
  A) DENY="$DENY|(GEN|DEP|WDR|LIM|SES|LDB)-[0-9]{2}" ;;
  B) ALLOW="$ALLOW docs/spec" ;;
  *) usage ;;
esac

COMMIT=$(git -C "$ROOT" rev-parse --verify --quiet "$REF^{commit}") \
  || die "unknown ref: $REF (tag the base first: git tag sut-v1)"

# --- where the snapshot goes -------------------------------------------------

[ ! -e "$DEST" ] || [ -z "$(ls -A "$DEST")" ] || die "$DEST is not empty"
mkdir -p "$DEST"
DEST=$(cd "$DEST" && pwd -P)

case "$DEST/" in
  "$ROOT"/*) rmdir "$DEST"; die "$DEST is inside the repository" ;;
esac

# A Claude Code session loads CLAUDE.md files from every parent folder and the
# global one, and keeps transcripts and memory per folder path.
d=$(dirname "$DEST")
while :; do
  for f in "$d/CLAUDE.md" "$d/CLAUDE.local.md" "$d/.claude/CLAUDE.md"; do
    [ -f "$f" ] && { rmdir "$DEST"; die "$f would be loaded by a session in the snapshot"; }
  done
  [ "$d" = / ] && break
  d=$(dirname "$d")
done
[ -f "$HOME/.claude/CLAUDE.md" ] && { rmdir "$DEST"; die "global $HOME/.claude/CLAUDE.md exists"; }

PROJECT_DIR="$HOME/.claude/projects/$(printf '%s' "$DEST" | sed 's/[^A-Za-z0-9]/-/g')"
[ ! -e "$PROJECT_DIR" ] || { rmdir "$DEST"; die "a session has already run in $DEST ($PROJECT_DIR): use a new path"; }

# --- copy --------------------------------------------------------------------

# shellcheck disable=SC2086  # ALLOW is a word list on purpose
git -C "$ROOT" archive --format=tar "$COMMIT" -- $ALLOW | tar -x -C "$DEST"
cp "$ROOT/experiment/snapshot/README.md" "$DEST/README.md"

if hits=$(grep -rniE "$DENY" "$DEST"); then
  rm -rf "$DEST"
  echo "$hits" >&2
  die "the snapshot mentions the experiment (lines above); nothing was left in place"
fi

# A fresh repository, so the agent's work can be read later as one diff.
git -C "$DEST" init -q
git -C "$DEST" add -A
git -C "$DEST" -c user.name=import -c user.email=import@localhost commit -qm "Import"

echo "snapshot $BRANCH: $DEST"
echo "  from $REF ($COMMIT), $(git -C "$DEST" ls-files | wc -l | tr -d ' ') files"
