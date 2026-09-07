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
# So determinism is MEASURED rather than assumed. Each side runs N times on one build (default 3),
# each on a fresh ingest of the same corpus. A field that a side reproduces exactly against itself
# every time is one whose cross-side difference can only be the build; a field ANY TWO of a side
# captures disagree on is withdrawn from the verdict, because the same build produces that
# difference against itself.
#
# WHY THREE AND NOT TWO: gate run 3 had q05 and q12 differ across sides while being stable within
# BOTH sides two-capture pairs, on one build. Two draws under-sample -- a field can agree once and
# disagree on the next -- so the pair reported stability it had not established.
#
# The withdrawal is bounded: `maxNoisyFraction` (fixture key, 0.05) refuses the whole run when a
# side noise pair moves more than that share of compared fields. Without it a pipeline degraded on
# BOTH sides -- a dropped reranker, an unfinished enrichment -- would present as a very quiet diff
# with most fields silently excluded, and "almost nothing was compared" would read as "nothing
# regressed".
#
# Usage (repo root):
#   # one invocation per side; capture-1 is the primary, the rest are noise captures
#   bash scripts/jseval/lane-f/fixture-pair.sh tmp/gate/split  compact 33221 3
#   bash scripts/jseval/lane-f/fixture-pair.sh tmp/gate/single compact 33221 3
#   bash scripts/jseval/lane-f/fixture-gate.sh tmp/gate/split tmp/gate/single [report.json]
#
# Exit 0 = pass, 1 = regression / missing field / unhealthy capture, 2 = usage error.
set -u
baseline_dir=${1:?baseline side directory (from fixture-pair.sh)}
candidate_dir=${2:?candidate side directory (from fixture-pair.sh)}
report=${3:-}

baseline_abs=$(cd "$baseline_dir" && pwd) || exit 2
candidate_abs=$(cd "$candidate_dir" && pwd) || exit 2

# capture-1 is the primary; every other capture-<digits>.json in the directory is a noise
# capture, so adding a cycle to fixture-pair.sh automatically deepens the gate with no change
# here. The selection is `workflow-fixture side-captures`, NOT a shell glob: the glob was
# `capture-*.json`, which also matched the cycle script's per-capture diagnostics
# (`capture-1-ai-status.json`) sitting in the same directory. The gate then reported "captures
# per side: baseline=6" for three real captures and marked the three diagnostic files UNHEALTHY
# with `chatProfile None` -- a verdict computed over files that are not captures. One definition
# of the rule, in python, where a test can point a decoy at it.
args=()
for side in baseline candidate; do
  if [[ $side == baseline ]]; then dir=$baseline_abs; else dir=$candidate_abs; fi
  # Run bare and keep the status in a variable: a pipe here would report the PIPE's status
  # and swallow the refusal, and a later $? read would be stale.
  listing=$( (cd scripts/jseval && python -m jseval workflow-fixture side-captures "$dir") 2>&1 )
  rc=$?
  if [[ $rc -ne 0 ]]; then
    echo "fixture-gate: $listing" >&2
    exit 2
  fi
  # click writes CRLF on Windows and `mapfile -t` strips only the LF, so the CR would ride
  # into the path and the differ would reject a file that exists.
  listing=$(printf '%s' "$listing" | tr -d '\r')
  mapfile -t captures <<< "$listing"
  args+=(--"$side" "${captures[0]}")
  n=0
  for f in "${captures[@]:1}"; do
    args+=(--"$side"-noise "$f")
    n=$((n + 1))
  done
  if [[ $n -eq 0 ]]; then
    # The differ refuses this too, but failing here says which directory to re-capture.
    echo "fixture-gate: $side has only capture-1.json — a side with one capture cannot be" >&2
    echo "  gated: nothing of it was measured for stability, so every field of it would count" >&2
    echo "  as stable by default. Re-run: fixture-pair.sh $dir <profile> <port> 3" >&2
    exit 2
  fi
  echo "fixture-gate: $side = 1 primary + $n noise capture(s)"
done
if [[ -n $report ]]; then
  mkdir -p "$(dirname "$report")"
  args+=(--report-out "$(cd "$(dirname "$report")" && pwd)/$(basename "$report")")
fi

(cd scripts/jseval && python -m jseval workflow-fixture diff "${args[@]}")
