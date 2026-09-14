---
status: active
---

# Composition-substrate retraction protocol

**Owning tempdoc**: 541 (`composition-substrate-completion`).
**Slot enumeration**: `docs/reference/contracts/composition-substrate-slots.v1.md`.
**Status**: active manual quarterly cadence until tempdoc 531 (`consumer-drift` gate kind on tempdoc 530's discipline-gate kernel) ships an automated CI-tier check.

## What this protocol is

Every Phase Output field exposed by the composition substrate is a named slot under C-018 ("substrate-without-consumer fails review"). When a slot's consumer disappears — or the slot never earns a consumer at landing — the slot must either earn a new consumer or be retracted on a documented timeline. This document is the protocol: who runs the audit, what evidence proves a slot is or isn't earning out, and how a retraction lands.

## Three retraction trajectories

Each trajectory has a named trigger, an evidence threshold, and a documented action. The thresholds carry over from tempdoc 541 §4.4.

### Trajectory 1 — Phase Output field with 0 consumers for 90 days

| Aspect | Specification |
|---|---|
| **Trigger** | C-018-unnamed-pending verdict per tempdoc 527's vocabulary (≥ 1 quarterly audit pass logs the field as zero-consumer). |
| **Evidence** | Grep of the slot's typed accessor (e.g., `serviceOut().X()` or `services.worker().X()`) across `modules/ui/`, `modules/app-services/`, `modules/app-agent/`, `modules/app-launcher/` returns zero non-test callsites. Direct callsites only — supplier-deferred indirect consumers DO count as named consumers (§4.1 reckons them with 🟡). |
| **Action** | (1) field removed from the Output record (e.g., `ServicePhase.Output`); (2) any downstream phase Inputs that reference the field are updated; (3) a row is recorded in this document's "audit history" (below) naming the retracted slot + the audit pass that triggered it; (4) when the 530-kernel `consumer-drift` gate ships, the row migrates to a changeset entry. |
| **Cadence** | First audit pass after a slot ships and remains zero-consumer for 90 days. Quarterly thereafter until removed or earned. |

### Trajectory 2 — Phase Output field with 1 fragility-signal consumer past grace expiry

| Aspect | Specification |
|---|---|
| **Trigger** | Tempdoc 531 `grace.until` expiry. Grace formats per 531's defined semantics: `until: "tempdoc N ships X"` (tempdoc-bound), `until: <commit-count>` (commit-bound), `until: <ISO-date>` (date-bound). |
| **Evidence** | Audit shows the slot still has exactly one named consumer at the grace expiry date. |
| **Action** | Either (a) the slot earns a second consumer (e.g., a new HTTP route is wired to read it; an operation handler is added); or (b) the slot retracts on schedule — same removal procedure as Trajectory 1. The choice is recorded in this document's audit history. |
| **Cadence** | At each `grace.until` checkpoint. Until 531 lands, the audit runs manually per slot's grace timestamp. |

### Trajectory 3 — Whole phase with 0 production effect (degraded → unreached)

| Aspect | Specification |
|---|---|
| **Trigger** | Either (a) `composition.phase.<name>` OTel span consistently emits `outcome=Failed(notImplemented)` (no live consumer ever exercises the phase); OR (b) the phase is `Eagerness.LAZY` and its `Supplier<O>` is never invoked for 90 days. |
| **Evidence** | OTel traces queried over the 90-day window show no successful phase invocation. The phase's typed output is never read by any downstream consumer (confirmed by Trajectory 1's grep across all of its Output fields). |
| **Cadence** | Quarterly audit reviewing OTel + slot accessor evidence. |
| **Action** | Phase removed from the composition root sequencer call chain. The phase's Output Java file is deleted. Audit history records the removal. |

## Inherent slots (not eligible for retraction)

Some slots have justified single-consumer cardinality by design. They are documented as `inherent` in the slot contract and are explicitly **out of scope** for the retraction protocol. As of the v1 slot contract:

- `BootstrapLateBindings.debugStateProvider` — diagnostic controller SPI source.
- `BootstrapLateBindings.statusSnapshotProvider` — diagnostic controller SPI source.
- `SubstratePhase.Output.healthOut.lifecycleSnapshotTap` — inherent to the status-deck contract.
- `SubstratePhase.Output.operationOut.capabilitiesChangeRegistry` — inherent to the `/infra/capabilities` endpoint contract.

When a slot is reclassified as inherent (via a tempdoc citation), it's added to this list. Retraction of an inherent slot requires retracting its design citation first.

## Manual quarterly audit checklist

Run this checklist quarterly (rotating ownership; recorded in audit history):

1. **Read** the current slot contract (`docs/reference/contracts/composition-substrate-slots.v1.md`).
2. **For each non-inherent slot**, grep its named consumers in current main. Confirm verdict still matches the contract (healthy / fragility signal / C-018-unnamed-pending).
3. **Apply Trajectory 1** to any slot dropped to zero consumers. Document the 90-day clock start.
4. **Apply Trajectory 2** to any slot whose `grace.until` (if recorded) has expired.
5. **Apply Trajectory 3** by reviewing OTel `composition.phase.*` spans over the prior 90-day window. (Until tracing is enabled in production, this trajectory is dormant.)
6. **Update slot contract**: increment the document's `## Audit history` table with date / outcome / changes.
7. **Update this document** with the same audit-history row.

## Audit history

| Date | Auditor | Outcome | Retractions / earnings |
|---|---|---|---|
| 2026-05-21 | tempdoc 541 P5 (initial protocol pass) | first cut — 13 slots enumerated in `composition-substrate-slots.v1.md`. The `/api/boot/phases` endpoint is in C-018-unnamed-pending state but co-shipped its FE consumer (`BootPhasesPanel.ts`) in the same slice (tempdoc 541 P3f), so this is not a 90-day clock start. 4 helper slots flagged as fragility signal eligible-for-grace; no `grace.until` recorded since they ship the same slice as their consumers. No retractions. | None. Baseline pass. |

## Migration target

When tempdoc 531's `consumer-drift` gate kind ships on tempdoc 530's discipline-gate kernel, this document migrates to:

- Slot enumeration: a slot declaration consumed by the kernel.
- Retraction protocol: gate-kind invocations record retractions as changeset rows in `gates/consumer-drift/.changesets/`.
- Audit cadence: CI-triggered, not manual.

Until then, the manual quarterly cadence IS the protocol.

## Why not 540 observations-inbox?

Tempdoc 540 (observations-inbox processing) is design-only as of 2026-05-21. When 540 ships its recurring cadence, the manual quarterly audit can be subsumed under 540's inbox-disposition pass — the substrate-slots contract becomes one of the inputs 540 reviews. Until then, the audit owner is the agent/human running 541's slot contract through the checklist above.

## Philosophical alignment (not direct precedent)

Tempdoc 511's `normalizeOperationFromWire` deletion demonstrates the disposability discipline this protocol formalizes: bridging code lands as a stop-gap, then is deleted once the real substrate consumer proves out. 541's retraction protocol applies the same disposability to the *substrate slot itself* when consumers fail to prove. 511 is the philosophy; 541's slot contract + this retraction protocol + 531's eventual gate kind are the machinery.

Settings reset no longer uses an inherent controller slot. ServicePhase directly composes
SettingsServiceImpl with the settings store and operation runner; accepted reset execution
is owned by SettingsCommitCoordinator. The controller-as-reset-source design is superseded.
