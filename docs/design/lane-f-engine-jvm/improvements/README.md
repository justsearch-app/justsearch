# Lane F improvement package (2026-09-25)

Owner-requested analysis of Lane F's current state, with designs and implementation plans for
the improvements it found. Prepared by a Claude (Opus 5.5) session as an independent reviewer
of the lane, read-only against `codex/lane-f-pr1` at `7f469d044` plus the implementing
session's working tree on 2026-09-25. Nothing here is implemented yet.

## Status and authority

- **The owner asked for this package** and for the Lane F orchestrator to implement it. Each
  work package (WP) is authorized implementation work inside the existing Lane F
  authorization (checkpoint commits and pushes to PR727; no merge before stage F).
- **Amendment protocol.** A WP that changes a design decision, a §16 gate row or the §17.3
  stage table is recorded through design §17.6: a dated line in §0 plus the owning section.
  Each WP names the lines it changes.
- **This package adds no acceptance waiver.** Where a WP moves or adds an obligation, it says
  so explicitly.
- **It does not reopen** the 2026-09-24 stage E re-cut (already applied in `fc5b444d6`) or the
  owner's decision to keep feature scope. Extra features are wanted; this package is about
  finishing them reliably and shipping safely.

## Reading order

| File | What | Priority | Size |
|---|---|---|---|
| `00-current-state-analysis.md` | Where Lane F actually stands: item status matrix, defect patterns, cost of reruns, release-safety and inference-host findings | read first | — |
| `WP1-migration-transition-barrier.md` | A deterministic barrier for the Worker migration/cutover lifecycle, so installed and integration proofs stop racing | **do next** (at the current batch boundary) | about 1 session |
| `WP2-release-safety.md` | Downgrade and broken-release safety: the register/code version drift, a "newer data" guard, a downgrade sandbox round, the dead-Engine round as a hard merge blocker, and a fix-forward statement | before stage E | about 2 sessions |
| `WP3-remaining-work-plan.md` | A re-plan of remaining D1/D2 work: verified status, critical path, interleaving of the not-started small items, estimates, re-plan triggers | adopt now (planning only) | — |
| `WP4-inference-host-decision.md` | Keep encoders in-process for Lane F; contain the native machinery so a later host can delete it; measure the host triggers at E | small, with D1-13/14 | under 0.5 session |
| `WP5-design-record-corrections.md` | Docs-only corrections to design §13, §17.1 and §4 found by the review | any batch boundary | under 0.5 session |

## Suggested order

1. **Finish the D1-9 gap-decision batch in progress.** Don't interrupt it.
2. **WP3:** adopt the sequencing and write it into the continuation brief.
3. **WP1** before the next installed D1-8, D1-9 or D1-14 proof round.
4. **WP4** alongside the next D1-13 or D1-14 checkpoint.
5. **WP5** at any batch boundary.
6. **WP2** before stage E starts. Items 2a and 2b can land earlier.

## Evidence base

- Four read-only investigations (Sonnet subagents briefed by the reviewer):
  - D1/D2 status and in-process encoder cost;
  - inference-host feasibility;
  - downgrade and release safety;
  - test races and fixture determinism.
- The reviewer independently re-checked the decisive claims:
  - the `ui-settings` register row `currentVersion: 1` against `UiSettingsStore.CURRENT_SCHEMA_VERSION = 4` (on `origin/main` it was 1 against 2);
  - `OperationFaultBarrier` and its env-gated file protocol;
  - `setMigrationPaused` as an unacknowledged flag;
  - `RESTART_WORKER` still in `CoreOperationCatalog`.

  Every other claim is tagged with its source in the files.
