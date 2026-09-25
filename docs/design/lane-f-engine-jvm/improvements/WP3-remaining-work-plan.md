# WP3: re-plan of the remaining D1 and D2 work

Type: planning. It changes stage-internal ordering only. The §17.3 stage table, the §16 rows
and the acceptance items are unchanged. Write the adopted order into
`continuation-brief.md` and `stages/D1.md` §12 (implementation batches).

## Why re-plan

- **Four early, independent items have not been started** (D1-6, D1-7, D1-15, D1-16), while
  the large flows have been through many correction cycles. See `00` §2.
- **D1-16 (the lifecycle harness) now matters more.** The E re-cut moved the feature rows
  (reconfigure, stuck component, generation transition, combined rows, semantic
  availability) onto D1 items. The harness is what exercises them. Built last, every row gets
  proven twice: once ad hoc, once in the harness.
- **Installed proofs are the most expensive step,** and they race without WP1.
- **The flows' remaining work has recurring defect classes** (activation ordering,
  ownership and fencing, schema drift; see `00` §3). Cheap preflights catch the drift class
  before the integrated gate.

## Sizing convention

S is about 0.5, M about 1, L about 2 to 3 sessions (a session is one bounded context).
These are planning estimates to make overruns visible, not commitments.

## Order

| Batch | Items | Why here | Estimate |
|---|---|---|---|
| **0** (in progress) | Finish the D1-9 and D1-11 gap decision: serial integrated rerun, installed standard-model gap approval, live A during the wait | don't interrupt | as planned |
| **1** | WP1 barrier. WP2 2a (register truth, `versionSource` check). WP5 docs | cheap; removes the race and drift classes before more installed rounds | 1.5 |
| **2** | **D1-16 harness skeleton**, with the feature rows as named scenarios (initially failing or pending where the mechanism is unfinished), using WP1 points | each later flow is proven once, inside the harness | 1.5 |
| **3** | Finish D1-9 and D1-8 (no-file projection, cancel and abandon, crash cuts 3 to 6, live activation order) through harness scenarios | largest open risk; depends on WP1 and D1-16 | 4 to 5 |
| **4** | D1-14 and D1-13 (gap and cancel backfill, floor-cap installed, A-recompose regressions, held lease across recompose), then D1-12 Flow B | the native and device path; WP4 containment rule lands with it | 4 to 5 |
| **5** | D1-4 connected publication/lifetime path (continuation brief, Batch 2) | the design is settled (09-23); it touches EngineRoot composition, so it goes after the flows stabilize | 3 |
| **Interleave** | D1-15 reason codes (S), D1-6 restart-required plus retiring `core.restart-worker` (M), D1-7 deadlines, local recovery and escalation (M), D1-10 and D1-2 hosted proof (S) | independent and small. Implement them while long installed or hosted runs are in flight (edit and review only; **never start a second Gradle build**). Build and verify at the next boundary | 3 |
| **6** | D1-17 residue sweep; D1 closure: independent review and the D1 rows' feature acceptance through D1-16 | — | 1 |
| **7** | D2: batch 1 (D2-2 profiles, D2-3 library, D2-7 ephemeral stores, D2-1 component map), then batch 2 (D2-4 gate plus D2-8 timings), then batch 3 (D2-5 durable write, D2-6 cursors, D2-9 MCP harness), then D2-10 | **the `verification` profile (D2-2 and D2-7) cuts later test time**, so it comes first within D2 | 9 to 10 |
| **8** | WP2 2b and 2c code; E runbook values from the PR 0 baseline | before E | 1.5 |
| **E** | seven merge-gate rows plus the downgrade and dead-Engine rounds (WP2) | — | 3 to 4 |
| **F** | sweep, report and PR ready (with the WP5 subsystem map) | — | 2 |

**Total remaining:** about 35 to 40 sessions. The spread is in batches 3 and 4, where the
flows can still surface defects.

## Standing preflight before every integrated gate

To catch the drift class (`00` §3):
1. The generators for every touched schema (`WireRecordSchemaGenTest` and the other
   generator whose nullability rule differed).
2. `check-store-recoverability` (with the WP2 `versionSource` check).
3. `check-runtime-manifest-closure` when runtime files change.
4. `regen-all --check`.
5. `spotlessCheck pmdAll --continue`, so all static failures come in one pass.

Record the preflight result beside the gate id.

## Re-plan triggers

| Trigger | Action |
|---|---|
| A batch takes more than 2 times its estimate | write a plan delta (what changed, cost, options, recommendation) in the handoff and tell the owner; continue independent work meanwhile |
| A third substantive correction round on one item | reassess that item's design with an independent reviewer before a fourth |
| A flow needs a mechanism placed in a later batch | move it earlier with a note; don't build a workaround |
| An installed scenario fails for a timing reason after WP1 | the failure is WP1's bug; fix the barrier point, don't widen timeouts |
| D1 closes and the remaining D2 estimate still exceeds 10 sessions | write a plan delta on D2 ordering for the owner |
