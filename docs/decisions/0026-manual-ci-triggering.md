---
title: "ADR-0026: Manual-Only CI Triggering"
type: decision
status: accepted - narrowed by ADR-0044 and ADR-0052
description: "Manual-only workflow policy for the former self-hosted/local-runner model; ADR-0044 narrows it for public hosted CI and ADR-0052 schedules CodeQL weekly while keeping manual dispatch."
date: 2026-04-22
probes:
  - adr-0026-workflow-triggers-checked
last_reviewed: 2026-10-20
---

# ADR-0026: Manual-Only CI Triggering

## Status
Accepted, narrowed by [ADR-0044](0044-public-hosted-ci-fact-lanes.md) and
[ADR-0052](0052-weekly-codeql-on-hosted-runners.md). CodeQL now runs weekly on Mondays at
04:17 UTC and remains manually dispatchable; the manual-only security statement below is historical.

ADR-0044 supersedes this ADR for the public repository's standard GitHub-hosted
`CI` lane: that workflow may run on `pull_request`, `push`, and
`workflow_dispatch`. This ADR remains the historical basis for manual-only
self-hosted and specialty workflows unless a later decision changes them.

## Context

CI runs on a single self-hosted Windows runner (`[self-hosted, Windows, X64, justsearch-perf]`). The runner is not guaranteed to be online for auto-triggered runs — it is powered on when the operator is working, not continuously. Historically `ci.yml` had `push:main`, `pull_request:main` (both path-filtered), and `workflow_dispatch` triggers. Auto-triggered runs against an offline runner either queue indefinitely or fail with no-runner-available errors, producing noise without value.

The project has always relied on local-first verification as the primary discipline — the `CLAUDE.md` "Verification Workflow" section documents the full sequence: `./gradlew.bat build -x test` for compile, `./gradlew.bat test` for unit tests, `./gradlew.bat spotlessApply` for formatting, and the per-subject pre-merge checks at step 5 (workflow-triggers, README, contract-governance kernel — invoked individually when their subjects change). Per slice 3a-1-8f §B.12 the prior three-layer wrapper chain (`scripts/gate.ps1` → `local-agent-gate-win.ps1` → DAG runner) was retired because the inner DAG runner had been broken since 2026-03-16, and reviewing whether a replacement wrapper was useful concluded that bundling unrelated checks behind a label was a coping mechanism, not a workflow value. The `CLAUDE.md` CI note has asserted for some time that "CI does not run automatically on push to `main`. Trigger it manually when you need remote verification" — but the `ci.yml` configuration has never matched that assertion; push and pull_request triggers existed in parallel with the documented policy.

This ADR canonicalises the documented behaviour by making `ci.yml`'s configuration match its intent.

The forces at play:
- The self-hosted runner is the only runner that can exercise the Windows+ORT+CUDA stack the test suite depends on. Cloud-hosted alternatives (`ubuntu-latest`) can't run the full suite without rewriting tests.
- The repository's single-developer / small-agent-fleet operating model means auto-triggered "regression trap" workflows are low-value — agents verify locally before push, and a human-in-the-loop reviews merge readiness.
- Stress tests (`@Tag("stress")`), added in tempdoc 397 §14.21 R3, were initially gated behind a weekly cron. Under the same runner-availability reality, a weekly cron produces the same queue-or-fail problem as auto-triggered runs.
- A manual-dispatch policy requires agent discipline: if CI is never auto-triggered, regressions can reach `main` undetected unless agents explicitly remember to trigger it.

## Original decision

This was the 2026-04-22 decision for the former self-hosted/local-runner
primary CI model. ADR-0044 supersedes it for the public repository's standard
GitHub-hosted `CI` lane.

At the time, `.github/workflows/ci.yml` was changed to run on
`workflow_dispatch` only. Specifically:

1. **Triggers**: `workflow_dispatch` with two boolean inputs (`skipFrontend`, `runStress`). No `push:`, no `pull_request:`, no `schedule:`.
2. **Jobs**: `full_build` (always runs on dispatch; the former `fast_build` is deleted) and `stress_tests` (opt-in via `runStress=true`).
3. **Invocation**: Agents call `gh workflow run ci.yml` (optionally with `-f skipFrontend=true` or `-f runStress=true`) and watch completion with `gh run list --workflow=ci.yml --limit=1`.
4. **Local gate is mandatory**: agents complete `./gradlew.bat build -x test` and `./gradlew.bat test` (plus the per-subject pre-merge checks in CLAUDE.md "Verification Workflow" step 5 — workflow-triggers, README, contract-governance kernel — when their subjects change) before every commit that touches build-gated surfaces. CI is a secondary remote-verification tool, not a replacement.

Branch-protection rules on `main` **must not** require this historical manual
CI check. Requiring a check under the old policy would have deadlocked merges
because CI never auto-ran; an agent would have had to dispatch CI and wait for
it before every merge, which defeated the local-first discipline. ADR-0044
later created stable public-hosted check names that may be required by future
branch protection, but this ADR does not configure those settings.

## Alternatives considered

1. **Keep auto-triggers with path filters (status quo before this ADR).** Rejected: path filtering is orthogonal to runner availability. A push that hits the path filters still fails when the runner is offline, producing failure emails with no actionable signal.

2. **Migrate to `ubuntu-latest` (GitHub-hosted) for auto-triggers.** Rejected: the test suite exercises Windows-specific paths (ORT DLL loading, file locking, NSIS packaging), native CUDA-linked ORT, and self-hosted-runner-specific caches. Migrating is a multi-week rewrite without a clear payoff.

3. **Scheduled-only CI (nightly or weekly).** Rejected: same runner-availability problem. A Monday 08:00 UTC cron fires whether the runner is online or not. Nightly-specialty workflows (the surviving `phase-3-observability-nightly.yml` and — at the time of authoring — the now-retired `agent-live-eval-nightly.yml` and `rr219-resilience-*.yml` family) already accept this tradeoff because their failure mode is "occasional missed run", but the primary CI suite shouldn't have that failure mode.

4. **Event-driven via a GitHub App webhook**. Rejected: too much infrastructure for a single-runner single-developer operating model. The `gh workflow run` command is one CLI invocation and gives agents direct control.

## Consequences

**Positive at the time:**
- CI runs always have a runner. No "no runner available" failures cluttering the notification inbox.
- CI minutes (or self-hosted wall time) spent only when intentionally requested.
- Workflow config matches documented policy — new agents reading either source land on the same answer.
- Stress tests run only when an agent is working on concurrency-relevant code, the moment when their signal matters most.
- Branch-protection configuration simplifies: no required CI check, no flaky external gates on merge readiness.

**Negative accepted at the time:**
- **No auto-regression trap on `main`.** Under the old manual-only model, if an agent forgot to trigger CI after a risky change, a regression could reach `main` unnoticed until the next manual run. ADR-0044 supersedes this for the public hosted `CI` lane.
- **Stress-test cadence is discipline-dependent.** The weekly cron that tempdoc 397 §14.21 R3 assumed for concurrency-regression safety is gone. Agents working on `NativeSessionHandle` / ORT session state should run the local Gradle stress-test opt-in; the public hosted `CI` lane no longer has a `runStress` dispatch input.
- **PR review couldn't lean on a CI check.** Under the old policy, human reviewers had to verify the author ran CI before merging, or run CI themselves. ADR-0044 supersedes this for the public hosted `CI` lane, though branch protection is still a separate repository-settings decision.

**Neutral at the time:**
- The historical workflow supported `skipFrontend=true` and `runStress=true` as dispatch inputs. ADR-0044's public hosted `CI` lane does not.
- Other workflows (`docs-lint.yml`, nightly eval/observability/resilience soak, installer builds, dependabot automerge) retained their existing triggers at this stage. Later amendments narrowed that behavior, and ADR-0044 later narrowed it again for public hosted `CI`.

## Related

- **CLAUDE.md** "Verification Workflow" — primary local discipline. Step 6 cites this ADR.
- **`docs/reference/contributing/agent-guide.md`** — expanded §3.5 area with "When to trigger CI" guidance.
- **Tempdoc 397 §14.21 R3** — origin of the `@Tag("stress")` gating; under this ADR, stress runs become dispatch-opt-in instead of weekly-cron.
- **Tempdoc 282 / 228** — prior CI-health work that assumed auto-triggers; this ADR supersedes the auto-trigger model those tempdocs were written against.

## Amendment 2026-04-26: policy extended to all self-hosted workflows

A scheduled `Phase 3 Observability Nightly` run queued while the runner host was offline. When the host booted hours later, the runner picked up the queued job and spawned a Gradle daemon plus 28 Java workers (~30 java.exe processes total, multiple GB resident) — a substantial unwanted resource event for an interactive workstation. The "Neutral" carve-out above (which retained schedule/push triggers for non-`ci.yml` workflows) is the proximate cause: queue-or-fail is one failure mode, but **queue-then-execute-at-boot is a worse one** because the operator is not present to abort.

This amendment retires the carve-out and extends the manual-only policy to every workflow on `[self-hosted, Windows, X64, justsearch-perf]`.

**Removed triggers:**

| Workflow | Removed trigger |
|---|---|
| `phase-3-observability-nightly.yml` | `schedule: '0 3 * * *'` (and the schedule-conditional `Open issue on drift` step, which would have been dead code) |
| `agent-live-eval-nightly.yml` | `schedule: '30 5 * * *'` |
| `rr219-resilience-governance-nightly.yml` | `schedule: '45 5 * * *'` |
| `rr219-resilience-soak-weekly.yml` | `schedule: '15 7 * * 0'` |
| `docs-lint.yml` | `push: branches: [main]` and `pull_request: branches: [main]` (both paths-filtered to `docs/**`, etc.) |

**Retained triggers (superseded by the 2026-05-08 amendment below):**

- `build-installer.yml`: `push: tags: 'v*'` is preserved. The job runs on `windows-latest` (GitHub-hosted), not the local self-hosted runner, so tag-driven release builds carry no local-resource cost. A tag push is also an intentional, operator-initiated act, not a regression trap.

**Additional consequences (beyond the original "Negative"):**

- **Nightly observability / agent-eval / resilience soft-gate cadence is gone.** Drift signals (Phase 3 σ(nDCG@10), agent live eval scorecards, RR-219 G0 advisory) are produced only when an agent manually dispatches the relevant workflow. Subject-touching changes are the trigger point.
- **`docs-lint` no longer auto-runs on docs PRs.** Run the regeneration scripts locally before pushing: `node scripts/docs/llmstxt-generate.mjs`, `node scripts/architecture/module-deps.mjs --check-canonical`.
- **Auto-issue-opening on Phase 3 drift is gone.** The schedule-conditional `Open issue on drift` step was removed because `github.event_name` can no longer equal `'schedule'`. Manual dispatchers read the gate report directly from the run.

**Workflow signal health script** (`scripts/ci/workflow-signal-health.mjs`) remains useful for diagnosing failures of manually-dispatched runs; only the "scheduled GitHub Actions failure" framing in the docs becomes obsolete.

## Amendment 2026-05-08: policy extended to all repository workflows

The 2026-04-26 amendment retained a `build-installer.yml` tag-push
exception because that workflow runs on GitHub-hosted `windows-latest`
rather than the self-hosted workstation runner. The user decision on
2026-05-08 retires that carve-out: **all repository workflows are
manual-only, regardless of runner class**.

Policy at the time of this amendment:

1. Every `.github/workflows/*.yml` workflow must expose
   `workflow_dispatch`.
2. No workflow may use top-level `push`, `pull_request`, or `schedule`
   triggers.
3. Release/installer workflows are dispatched manually. If a release
   workflow needs tag context, dispatch it against the tag ref explicitly
   instead of relying on tag-push automation.
4. Security workflows such as CodeQL are also manually dispatched unless
   this ADR is amended again.

ADR-0044 later narrowed this repository-wide rule for the public hosted `CI`
lane, which may run on `pull_request`, `push`, and `workflow_dispatch`. The
mechanical guard is now `scripts/ci/check-workflow-triggers.mjs`, which fails
when actual workflow triggers do not match
`scripts/ci/workflow-signal-policy.v1.json`.

## Amendment 2026-07-07: `phase-3-observability-nightly.yml` deleted

The workflow never ran automatically at any point in its history — the
2026-04-26 amendment above removed its `schedule:` trigger before the
workflow ever fired on that cron, leaving only `workflow_dispatch`, which
nobody had exercised. Its header comment still described the pre-amendment
cron/auto-issue behavior, creating a live risk that an agent would read the
comment, conclude the trigger was a bug, and "fix" it by reinstating a
schedule — reopening the exact unattended-boot-execution failure mode this
ADR exists to prevent. The self-hosted runner is still the operator's
workstation, online only when in use; going public (ADR-0044) resolved the
GitHub Actions-minutes constraint for the hosted `CI` lane but has no bearing
on this runner's intermittent uptime.

The workflow file, its `workflow-signal-policy.v1.json` entry, and its
references in `agent-guide.md` and the `ci-triage` skill were removed.
The how-to page `recalibrate-phase3-baseline.md` was rewritten in place (rather
than deleted, since three other how-to docs link to it) to describe a manual
`jseval calibrate` / `jseval gate` procedure instead of a nightly-gate
rebase; that doc is now the sole recorded source of truth for the current
baseline value, since it previously lived only in this workflow's env block.
The underlying `jseval calibrate` / `jseval run` / `jseval gate` CLI
capability is unaffected and remains available for manual use.

> **Superseded in part (2026-09-05, tempdoc 930 §18.1 row 7).** The
> cohort-envelope machinery this section preserved was removed: `jseval
> calibrate`, `calibrate-drift-baseline`, `recalibrate-nightly-baseline`, the
> `cohort_baselines/` registry, and the four how-to pages named above
> (`recalibrate-phase3-baseline.md`, `calibrate-drift-baseline.md`,
> `triage-psi-drift.md`, `envelope-staleness-policy.md`) are gone. No baseline
> was ever captured on any machine, so the σ(nDCG@10) band the gate asserted
> was unresolvable. `jseval gate` survives as a projection-presence check and
> `jseval run` is unchanged. This ADR's actual decision — manual triggering for
> self-hosted workflows — stands; only the named artefacts are stale.

## Amendment 2026-09-05: measured axe runs on PRs as an advisory lane

Two of this ADR's descendants — `jseval ui-a11y-gate` and `jseval
ui-proportion-gate` — carried "Local-first (ADR-0026): a runnable gate, not a
CI-wired kernel gate" in their own docstrings. That citation was load-bearing
for the a11y gate in a way this ADR never intended. ADR-0026 is about *runner
availability*: a self-hosted workstation runner cannot be relied on to be
online, so nothing may auto-trigger against it. It says nothing about a check
that needs no self-hosted runner at all.

`ui-a11y-gate` is exactly that check. Its deterministic `--fixtures` capture
state intercepts every `/api/*` request inside Playwright and answers from
committed schema-valid fixtures (`scripts/jseval/jseval/ui_fixtures.py`), and
`ui-shot` starts its own Vite dev server out of `modules/ui-web`
(`ui_shot.py::_start_vite_server`). No backend, no Gradle, no GPU, no dev
stack — Node, Python and headless Chromium. Measured at 350s wall for the 20
surfaces in `governance/ui-a11y-baseline.v1.json`, including a cold Vite
start, exit 0.

Tempdoc 930 chunk H retired twelve static a11y/token oracles in favour of
measurement, but only the four-palette contrast sweep
(`check-contrast-matrix`) actually ran on PRs; the measured-axe half stayed
local. That was a real reduction in automated accessibility coverage, tracked
as §22.2 follow-up 5.

**Decision.** `.github/workflows/ci.yml` gains a `measured-axe` job
(`Measured axe (advisory)`, `ubuntu-latest`) running `jseval ui-a11y-gate` on
`pull_request` for affected frontend inputs, `push` to `main` and
`workflow_dispatch` (current scheduling: ADR-0053). This adds no trigger and
touches no self-hosted runner, so the manual-only policy for self-hosted and
specialty workflows is unchanged.

**The lane is ADVISORY, not required.** Like `integration-tests`, it is
deliberately absent from `scripts/ci/workflow-signal-policy.v1.json`'s
`requiredStatusChecks` and from `scripts/ci/public-ci-local-repro.v1.json` —
adding a non-required context to the latter fails
`validatePublicCiLocalRepro` by design. Promotion to a required check is a
separate decision that wants stability evidence from this lane first (a hosted
Linux Chromium renders differently from the Windows machine the register's
`knownRules` were captured on, and only real runs can show whether that is a
source of NEW-violation noise). That is the path `jseval-suite` walked: added
as an unrequired hosted job by §18.1 row 7, promoted to a required check only
after it had run.

The first hosted run (PR #672, run 33958715012, job 101286643288) came back
`exit_code: 0` with per-surface rows **identical to both local Windows runs**,
`health`'s one accepted `color-contrast` included, in 168s wall for the whole
job. That is encouraging but it is one run — the promotion question is whether
it stays that way, which only a series of runs can answer.

`ui-proportion-gate` keeps its local-only status and its ADR-0026 citation for
now: its baseline is pixel geometry, which is far more runner-dependent than
an axe rule id. That is a separate judgement, not an oversight.

## Amendment 2026-10-20: later exceptions to the all-workflow rule

The 2026-05-08 amendment's requirement that every workflow expose manual
dispatch is historical. [ADR-0044](0044-public-hosted-ci-fact-lanes.md) permits
event-driven public hosted checks; `.github/workflows/cla.yml` handles
`issue_comment` and `pull_request_target` without
`workflow_dispatch`.

[ADR-0052](0052-weekly-codeql-on-hosted-runners.md) also narrows item 4 of that
amendment: `.github/workflows/codeql.yml` declares `schedule: '17 4 * * 1'` as
well as `workflow_dispatch`, on hosted runners. Self-hosted workstation workflows
remain manual. Use `scripts/ci/workflow-signal-policy.v1.json` and its trigger
check for the per-workflow posture rather than reinstating the historical
repository-wide ban.

## Amendment 2026-10-21: one PR run

[ADR-0053](0053-one-pr-ci-run-and-squash-auto-merge.md) supersedes the hosted CI event-selection
and publication rules. Main and dispatch run all slow suites; PRs run their input closure.

## Amendment 2026-10-24: Measured axe becomes required

The advisory decision is superseded: `measured-axe` reports as **Measured axe** and is
included in the CI signal policy's required checks. Since 2026-10-21 it passed all 59
selected PR runs in the promotion record, with zero failures. This satisfies the stable
advisory record required by ADR-0026 and ADR-0044. Serious and critical accessibility
violations block merges.

Selection is unchanged: affected frontend inputs on PRs, always on main pushes and manual
dispatch. An unselected PR reports the literal job as skipped, matching `jseval Python suite`.
No merge-group trigger is added. The gate still fails on every new rule regardless of impact,
on serious/critical violations even in baselined rules, and on missing, null or errored scans;
its job and gate step do not continue on error.

Required-lane bookkeeping includes the local reproduction entry, a measured wall-clock policy
row and `ci-walltime.needs` dependency. The dated advisory history above remains historical.
Branch protection is updated by the maintainer after this change merges; repository settings
are outside this change.
