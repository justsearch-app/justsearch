---
title: "ADR-0053: One PR CI run and validated squash auto-merge"
type: decision
status: accepted
description: "Select PR lanes by their inputs, merge after that run, and run the full suite serially on main."
date: 2026-10-21
amends:
  - "0026-manual-ci-triggering.md"
  - "0044-public-hosted-ci-fact-lanes.md"
  - "0045-public-main-history-publication.md"
probes:
  - adr-0053-one-run-policy
last_reviewed: 2026-10-21
---

# ADR-0053: One PR CI run and validated squash auto-merge

## Status

Accepted. Supersedes the hosted event selection and publication path in ADR-0026,
ADR-0044 and ADR-0045. Their stable named checks, public-safe caches, CLA, squash-message
and review-record requirements remain.

## Decision

`node scripts/dev/run-gh.mjs enqueue N` validates the squash preview and managed review
record, then enables squash auto-merge bound to the validated head, title and body. A new push
requires a new enqueue. The PR runs the union of lanes that consume its changed files; all
required names report, including intentional skips. Unknown inputs run every lane. Strict stays
off, so an older green base needs no update push. There is no merge queue.

After merging, run `node scripts/dev/run-gh.mjs run-wait-sha <merge commit>`. Main runs the full
suite, including Integration tests, Windows stop-census, supervisor conformance and Measured axe.
Its running CI is never cancelled; only the newest pending commit runs, and the wait command
follows a newer run that covers the requested commit. If main fails in your change's area, open
a fix or revert PR at once through the normal path. Report a failure in a test listed in
`scripts/ci/pr-flakes.tsv`; do not revert for that listed flake.

Secret scan and Public claims always run. The classifier fails safe for unknown paths, workflow
changes, missing diff evidence and non-PR events. Fixed jobs preserve the twelve required names;
aggregates accept success or intentional skip and reject failure or cancellation. Main writes the
Gradle cache that PRs read. PR pushes cancel their predecessor; main keeps its running run.

The PR advisory gate compares attributed lockfile/package/version findings with the actual PR
base. New advisories against versions already on the base do not block a PR; added vulnerable
versions do, including nested versions. Missing base or provider evidence fails closed. Accepted
baselines and severity tracking remain in force. On PRs, introducing an affected version
covered by a baseline pin still requires a declared acceptance changeset.

## Consequences

The target is one scoped run before merge (under twenty minutes including runner contention),
with full-suite regressions visible on main. Hosted timings are measured after rollout. Listed
flake cases run on main and dispatch; deterministic failures in selected PR lanes still block.
The queue ruleset must be drained and removed before rollout by its owner; this code never
requires a queue. No new PR secrets or write permissions are introduced.

The Linux Java lanes use disjoint class shards balanced from hosted per-class attribution.
Selected PR lanes run supervisor conformance with only the six listed exclusions; main and
manual runs exercise every case. Repository-wide static documentation checks run in Public claims.
