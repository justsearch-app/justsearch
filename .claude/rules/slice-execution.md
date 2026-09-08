<!-- budget: always-loaded; ceiling in scripts/ci/always-loaded-budget.v1.json (ratchets down) — tempdoc 620. Full reference lives in docs/reference/contributing/slice-execution.md. -->

# Slice Execution Discipline

Multi-phase tempdoc slices use the bidirectional pass pattern below. <!-- rule:bidirectional-pass -->
Full reference: `docs/reference/contributing/slice-execution.md`.

## Quick reference

For each phase:

1. **Before writing code** — pre-implementation spec-tightening pass.
   Re-read the spec for phase N with phase N-1's lessons in hand. For
   every claim about the codebase (field paths, method signatures,
   visibility, type semantics), verify verbatim against source. Update
   owning sections with primary-source citations; keep a short dated index.

2. **After code is in** — post-implementation critical-analysis pass.
   Walk the diff with "what would catch what tests missed?" lens.
   Specifically check: wrong-gate / wrong-flag mistakes, audit
   conclusions not independently verified, test precision (right reason
   vs wrong reason), tri-state lookups (don't conflate unknown with
   healthy), stale-flag short-circuits, asymmetric lifecycle
   (`start()` without `stop()`), WARN dedup.

If the post-impl pass finds 0 actionable findings, record the review result
without attributing it to the earlier pass. Slice 430 Phases 6-10 produced 5
consecutive zero-finding outcomes.

## For Resource-instance slices

Run the pre-flight emit-path probe before phase 1: for each declared
emit ID,

```bash
git grep -nE '"<id>"' modules/<producing-module>/src/main/java
```

If no producer exists, the ID is phantom / forward-compat / FE-only —
mark explicitly, not implicitly. Slice 430 §B.AF caught 3 phantom IDs
this way; the slice headline shifted from "23 backend + 4 FE-only" to
"24 fire-able + 3 phantom."

## For slice closure

The implementing agent and the validating agent should not be the same.
Single-agent self-validation rests on the same blind spots as the
implementation. <!-- rule:independent-reviewer-required --> The CONFLICT-LEDGER closure protocol formalizes this
("the agent or reviewer resolving the conflict should not be the same
agent who originally flagged it") — apply the same discipline to slice
closure. This was `gate`-enforced (tempdoc 550 thesis V) via the
`independent-review` discipline gate, but **that gate was retired (tempdoc 563)**,
so it is **honor-system guidance now, not build-enforced**: an independent
(second-agent, reviewer ≠ committer) review with live verification before closing
a substrate slice remains expected practice — but no gate fails the build if it is
skipped (`docs/reference/contributing/discipline-gate-kernel.md`).

A follow-up agent's source-verbatim verification pass (§B.AF-style) is a
higher-fidelity check than the implementing agent's own §B.AC-style
outcome record.

For **presentation-authority** work (UI surfaces, overlays, messaging,
evidence rendering), closure additionally requires an independent, *measured*
whole-screen UX audit: an independent auditor (≠ committer) must run a measured
(axe / contrast oracle — not eyeballed), live-verified audit before the work
closes. <!-- rule:ux-audit-closure --> This was briefly `gate`-enforced
(tempdoc 559 §6-7) via the `ux-audit-closure` discipline gate, but **that gate was
retired (tempdoc 563)**, so it is **honor-system guidance now, not build-enforced**:
an independent, *measured* (axe / contrast oracle — not eyeballed), live-verified
whole-screen UX audit by an auditor ≠ committer before closing presentation-authority
work remains expected practice — but no gate fails the build if it is skipped
(`docs/reference/contributing/discipline-gate-kernel.md`).

## Verification gate items

Auto-verifiable items (build green, unit tests, schema idempotency) →
implementing agent verifies.

Dev-stack-driven items require actual live proof under an owned lease when
available. An unexecuted smoke procedure is an explicit remaining item. Reconcile
all acceptance evidence using `docs/reference/contributing/agent-workflow.md`.

Don't silently mark the gate "passed" because unit tests are green.

## Reference cases

- **Slice 430** (HealthEvent Resource): Phases 6-10 = 5 consecutive
  zero-finding outcomes via the bidirectional pattern.
- **CLAUDE.md tempdoc 403 Tier B/C**: wrong-gate mistake +
  audit-without-test failure mode.

The full doc covers the methodology arc (six-step process), tri-state
lookup pattern, phantom-ID probe, and concrete per-phase checklist.
