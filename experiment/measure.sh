#!/usr/bin/env bash
# Stage 6 for one suite (experiment/MEASURE.md): the base reference, the suite
# against every seeded bug, the count.
#
#   experiment/measure.sh <suite repo> <ref> <out> [<checks dir>]
#
# Without <checks dir>, the tag map (CHK-3S) and CHK-2 of the suite go to
# <out>/checks. With it, the ones there are used (B: the CHK-2 of the control
# stage). Then matrix.py runs base, B01-B16 and two more runs of B04 into
# <out>/matrix, and checks.py measure writes <out>/bugs.csv. The stand ends on
# the base. Run with the JDK of the generating agent.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"

usage() { echo "usage: $0 <suite repo> <ref> <out> [<checks dir>]" >&2; exit 2; }
die() { echo "error: $*" >&2; exit 1; }

[ $# -ge 3 ] || usage
SUITE=$1
REF=$2
OUT=$3
CHECKS=${4:-$OUT/checks}

git -C "$SUITE" rev-parse --verify --quiet "$REF^{commit}" >/dev/null \
  || die "no $REF in $SUITE"
[ -e "$OUT/matrix" ] && die "$OUT/matrix exists: choose another <out>"

COLUMNS=(base $(seq -f 'B%02g' 1 16) 'B04#2' 'B04#3')  # B04 is a race: 3 runs (MEASURE.md)

if [ $# -lt 4 ]; then
  "$ROOT/experiment/checks.py" tags "$CHECKS" --suite "$SUITE" "$REF"
  "$ROOT/experiment/checks.py" stability "$CHECKS" "$SUITE" "$REF"
fi
[ -f "$CHECKS/chk2/stability.csv" ] || die "no CHK-2 in $CHECKS"

"$ROOT/experiment/matrix.py" "$SUITE" "$REF" "$OUT/matrix" "${COLUMNS[@]}"
"$ROOT/experiment/checks.py" measure "$OUT" "$CHECKS" "$OUT/matrix"
