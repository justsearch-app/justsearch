# D1 closure review (2026-10-01)

Refute-first closure review by Sol (gpt-6.1-sol, no sub-agents) at 56df784ad. Verbatim.

**D1 is not closable at `56df784ad`.**

**U** = current unit XML plus [batch5 suite evidence](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/lane-f-batch5-full-suite.log): independently counted **12,488 tests, 0 failures/errors, 33 skips**. Runtime code is unchanged between `a38dc2883` and HEAD. **I** = [installed receipts and revisions](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/docs/design/lane-f-engine-jvm/evidence/D1/installed-round-2026-10-01.md); I checked the underlying XML.

| Item | Status | Evidence or missing piece | Smallest action |
|---|---|---|---|
| D1-1 | CLOSED | U: production four-owner coverage 1/1; applied-value tests 3/3; registry 26/26; resource policy 6/6. | — |
| D1-2 | OPEN | Exhaustive 1,296-combination projection and both hosts’ 18/18 conformance pass. Missing §16 proof joining actual death, component readiness clocks, and successful TEXT/semantic queries. | Capture one installed death/recovery trace with those witnesses. |
| D1-3 | CLOSED | U: register 4/4, generation observation 5/5; accessible missing/unknown mutation failures `tmp/2406-register-missing.txt`, `2407-register-unknown.txt`; config-surface pass `2410`. Relevant owners unchanged. | — |
| D1-4 | OPEN | I: BESIDE/IN_PLACE reconfigure passes at `30f5ca540`; actual second-owner refusal passes at `a38dc2883`. Earlier crash receipts do not prove the newly implemented IN_PLACE query transaction. | Installed precommit kill → A/FAILED and postcommit kill → B/COMPLETE. |
| D1-5 | CLOSED | U: apply/preflight/rollback 46/46, candidate context 9/9, strict adoption 9/9. `tmp/5582-standard-recovery.log` proves real standard-model chat after recovery; inference owners unchanged since `ac22fff8e`. | — |
| D1-6 | OPEN | Retirement and replacement routing pass. **18 → 18 does not satisfy “shrinking baseline”**; the checklist explicitly says the receipt remains unsatisfied. Signed proof is separately deferred below. | Produce genuine automatic shrink, or explicitly amend the binding requirement with rationale; rerunning green is insufficient. |
| D1-7 | CLOSED | I: standard-model lock release/exhaustion both pass at `30f5ca540`; current monitor tests 35/35, recovery-route tests 5/5, exit tests 4/4; both adapters 18/18. Relevant recovery paths unchanged. | — |
| D1-8 | CLOSED | U: migration 4/4, nine-point lifetime matrix within 25/25 close tests, retirement 14/14, pointer recovery 2/2. I: current `migration` passes at `a38dc2883`. [D1-8f disposition](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/docs/design/lane-f-engine-jvm/evidence/D1/d1-8f-disposition-2026-10-01.md) is supported by captured-view ownership and retained leases. | — |
| D1-9 | OPEN | Fresh-start refusal and settlement regressions now pass. Missing refusal-to-restored-A timing against the reconfigure budget. Also, `representation-generations` still has no connected retained-state producer. | Identify the governing budget, record the elapsed interval, and connect existing generation accounting. |
| D1-10 | CLOSED | U: `MidMigrationCompatSurfaceTest` 3/3 and schema tests 21/21; I: current live migration. Served-generation projection is unchanged; schema regeneration receipt passes. | — |
| D1-11 | CLOSED | U: acceptance handler 2/2 and coordinator hash/refusal cases; I: exact gap approval at `eee08b92e`, unchanged decision owners. Current UI measurement at `a38dc2883`: zero axe/console/overflow findings; frontend gates 27/27. | — |
| D1-12 | OPEN | Identity/latch/lease tests and installed differing-model A/B proof pass. **`co-resident-encoders` remains `awaitingProducer: D1`; no owner activates/accounts it.** | Connect encoder ownership to live retained accounting and replace the marker with its proven producer. |
| D1-13 | CLOSED | Held CPU/GPU/recreation/timeout tests 9/9; preserved native stress 1/1; current shutdown/recompose tests pass. I: real held-native controlled hard stop passes at `ec653a777`; relevant native/lifetime paths unchanged. | — |
| D1-14 | CLOSED | Device-line tests plus I’s CUDA BESIDE/forced-IN_PLACE, restored A, failed-A recovery and query-reconfigure rounds. Structural reload/fallback and connected UI notice proof remain valid; device cap is correctly described as simulated pressure. | — |
| D1-15 | CLOSED | Readiness gate: 61 producer-backed codes, 57 worded rows; notice/unit receipts pass; U error-message contract 4/4. Current retirement grep is clean for the specified notice vocabulary. | — |
| D1-16 | OPEN | Installed functional scenarios have passing receipts. Latest retained pending-registry receipt still reports the old pending reconfigure row; no current default-task receipt proves **every untagged test**, including the zero-pending assertion. | Run and retain the current default lifecycle task. |
| D1-17 | OPEN | Full suite and executable retirement are proved. Baseline remains 18 → 18; both D1 retained-state markers remain in [the register](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/governance/retained-state.v1.json:31). | Resolve shrink requirement; connect both producers and retire their markers. |
| D1-18 | OPEN | Live-start/crash/fallback installed cases pass; current suites pass. Required exact-HEAD successful hosted CI receipt is absent. | Obtain and reconcile successful required CI results for final HEAD. |
| §16 recovery workflow | OPEN | D1-2’s death/readiness witness is missing. Local recovery and successor VECTOR success alone do not provide the complete component trace. | Same installed trace as D1-2. |
| §16 stuck component | CLOSED | I: local recovery, counted exit-5 escalation and standard-model successor; U: optional non-escalation and busy/manual refusal. | — |
| §16 generation transition | OPEN | Mutation, supersession, gaps, approval and A/B serving are proved; restoration-budget timing and generation accounting remain open. | Finish D1-9. |
| §16 semantic availability | CLOSED | I at `9bb93fb38`: IN_PLACE window **16,629/39,585 ms, 42.01%**, hybrid 146/146, zero outage; two-document/two-unit fixture. Connected notice proof uses unchanged UI ownership. | — |
| §16 combined low-memory interruption | CLOSED | I: both pointer cuts at `30f5ca540`; post-pointer/root reconciliation refreshed at `a38dc2883`. Exact models/settings, latest text/vector and supersession asserted; pointer/replay paths unchanged. | — |
| §16 combined delayed retry | OPEN | Existing delayed-retry regression changes **theme only**. It proves settings/outcome replay, not applied component versions and generation behavior after a later change. | Extend the existing reconfigure fixture: A→B, B→C, delayed old-key replay, stale new-key refusal; assert C/version/generation stay unchanged. |
| §16 resume conditions | CLOSED | Current captured-edit installed proof at `a38dc2883`; U exact writable-B, sealed-queue, replay/checkpoint settlement and fencing regressions. | — |
| §16 reconfigure | OPEN | Atomic refusal/restoration and native-child recovery have evidence; new IN_PLACE query crash boundaries remain unproved physically. | Finish D1-4’s installed cuts. |
| Signed restart-required proof | DEFERRED-AUTHORIZED | [Owner re-plan](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/docs/design/lane-f-engine-jvm/handoff.md:155) moves D1-6’s signed successor proof to **E7**. | Execute in E7; retain that destination in closure record. |
| D2-owned feature portions | DEFERRED-AUTHORIZED | [Owner re-plan](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/docs/design/lane-f-engine-jvm/handoff.md:135): D2-1..10, including client re-entry, component map and old cursors, move post-merge. | Preserve follow-on ownership; do not defer D1 portions with them. |

Before marking D1 closed:

- Connect both retained-state producers and remove their D1 markers.
- Resolve the binding baseline-shrink clause; method deletion and 18 → 18 are insufficient.
- Prove death-to-component readiness and real TEXT/semantic recovery.
- Measure refusal-to-restored-A against an identified reconfigure budget.
- Run the new IN_PLACE query transaction’s installed crash cuts and the component/generation delayed-retry case.
- Retain the current default lifecycle receipt and successful final-SHA hosted CI.
- Reconcile the stage/handoff with these receipts and the two authorized deferrals.