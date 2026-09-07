#!/usr/bin/env bash
# Lane F: the GATE. Diffs two sides against each other while excluding, per field, whatever each
# side own same-build noise pair already moves.
#
# WHY A NOISE PAIR (design 16 relation refinement). Five paired rounds established that some
# fields are not stable on this stack: index-time GPU embedding jitter moves fusion candidates,
# and no candidate-budget pin removes it because it happens upstream of every budget. The two
# alternatives were both worse. Declaring those fields non-exact would blind the gate to real
# regressions in them forever -- the class would swallow the signal along with the noise. Pinning
# the pipeline hard enough to silence them (CPU execution provider for the encoders) works, but
# measured on this corpus it puts a cycle past an hour and stops measuring the shipping
# configuration at all.
#
# So determinism is MEASURED rather than assumed. Each side runs TWICE on one build, on two fresh
# ingests of the same corpus. A field that a side reproduces exactly against itself is one whose
# cross-side difference can only be the build; a field its own build already moves is withdrawn
# from the verdict, because the same build produces that difference against itself.
#
# The withdrawal is bounded: `maxNoisyFraction` (fixture key, 0.10) refuses the whole run when a
# side noise pair moves more than that share of compared fields. Without it a pipeline degraded on
# BOTH sides -- a dropped reranker, an unfinished enrichment -- would present as a very quiet diff
# with most fields silently excluded, and "almost nothing was compared" would read as "nothing
# regressed".
#
# Usage (repo root):
#   # one invocation per side, each producing capture-1 (primary) + capture-2 (noise)
#   bash scripts/jseval/lane-f/fixture-pair.sh tmp/gate/split  compact 33221 2
#   bash scripts/jseval/lane-f/fixture-pair.sh tmp/gate/single compact 33221 2
#   bash scripts/jseval/lane-f/fixture-gate.sh tmp/gate/split tmp/gate/single [report.json]
#
# Exit 0 = pass, 1 = regression / missing field / unhealthy capture, 2 = usage error.
set -u
baseline_dir=${1:?baseline side directory (from fixture-pair.sh)}
candidate_dir=${2:?candidate side directory (from fixture-pair.sh)}
report=${3:-}

for f in "$baseline_dir/capture-1.json" "$baseline_dir/capture-2.json" \
         "$candidate_dir/capture-1.json" "$candidate_dir/capture-2.json"; do
  if [[ ! -f $f ]]; then
    echo "fixture-gate: missing $f — run fixture-pair.sh with cycles>=2 for BOTH sides" >&2
    exit 2
  fi
done

baseline_abs=$(cd "$baseline_dir" && pwd)
candidate_abs=$(cd "$candidate_dir" && pwd)
args=(
  --baseline "$baseline_abs/capture-1.json"
  --baseline-noise "$baseline_abs/capture-2.json"
  --candidate "$candidate_abs/capture-1.json"
  --candidate-noise "$candidate_abs/capture-2.json"
)
if [[ -n $report ]]; then
  mkdir -p "$(dirname "$report")"
  args+=(--report-out "$(cd "$(dirname "$report")" && pwd)/$(basename "$report")")
fi

(cd scripts/jseval && python -m jseval workflow-fixture diff "${args[@]}")
