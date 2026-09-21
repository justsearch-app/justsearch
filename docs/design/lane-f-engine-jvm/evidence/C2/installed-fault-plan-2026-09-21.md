# Remaining installed C2 fault points

Design selected 2026-09-21 after transcript-guided resumption. This refines C2-11,
it does not replace its six-case requirement. Use the existing installed
OperationResumeE2ETest -> EngineSupervisedRecoveryE2ETest.runScenario ->
real-writer-recovery.mjs driver, its identity-verified crash/cooldown observation
and owned stop. Do not add another polling driver or bypass local API protections.

| Case | Actual held boundary | Post-death and successor contract |
| --- | --- | --- |
| ingest-before-accept | before runner's real INGEST row acceptance | unknown key/no job or effect; first retry executes once, second retains identity and one document |
| settings-before-accept | before SETTINGS_APPLY row acceptance | unknown key/unchanged witness; original patch/revision retry commits once, replay preserves witness |
| ingest-after-accept-before-effect | after row acceptance, before start/body | ACCEPTED/no queue effect at death; successor resumes original prepared operation, retry creates no duplicate |
| settings-after-accept-before-effect | after acceptance, before start/body | ACCEPTED/unarmed revision at death; successor fails interrupted_before_settings_commit; retry preserves failure/no effect |
| ingest-after-effect-before-checkpoint | before positive-unit ingest-receipt checkpoint | queue sealed and real write committed, row checkpoint old; successor checkpoints receipt/completes original operation with one document |
| settings-after-effect-before-checkpoint | after settings owner apply returns, before operation terminal write | exact key and expectedRevision+1 witness committed, row open; successor completes from witness, replay does not increment revision |

Public settings is SETTINGS_APPLY/settings.apply-public via /api/settings/v2;
D1 RECONFIGURE is not substituted. The exact committed-witness rule in
operations-store-design.md supersedes the old blanket-failure expectation.
For successful ingest recovery, persist a narrow watched corpus root before
boot. StructuralAuto can continue a fresh out-of-root ingest but cannot authorize
its restart; baseline2020 exposed the stale fixture precondition, and rooted2022
passes the existing installed processing/retry scenario.

Use one immutable test callback selected at HeadlessApp composition only with
JUSTSEARCH_SUPERVISOR_HARNESS=1. Compare/reject selection without harness mode.
Candidate barriers belong around the runner's actual accept writes (including
admitAndAccept.Scope.accept and ordinary/prepared acceptance), before the
Control.checkpoint call for positive-unit ingest-receipt cursors, and after
settingsOwner.apply returns in applySettingsOwned. No global SQLite/worker hook
or alternate persistence owner is needed. Reached/release files live only under
the fixture runtime directory; a persisted reached marker prevents successor
retrigger. Match operation kind/key so unrelated boot operations cannot trigger
or accidentally satisfy the test. The harness must fail if the selected boundary
is not reached and verify its own marker before identity-checked Engine death.
Record the snapshot during counted cooldown, before successor can alter it.

Add each case as a separate JUnit parameter/supervised run. New names receive
Windows process-identity gating and restart cooldown; chaos extraction argfile
stays exclusive to the original processing/operation scenarios. Held HTTP requests
need an explicit cancellable lifetime so the helper's five-second timeout cannot
be mistaken for the intended kill. Reuse the after-accept barrier for one additional
real-producer client-disconnect case: abort caller, release barrier without killing
Engine, prove eventual durable completion and admission release.

Before implementation, resolve the independently reproduced closed-enumeration
refusal gap (2023). Then implement the narrow hook/driver batch, test disabled-hook
behavior and each reached point, run all six installed cases plus disconnect,
run required full stress suite and both supervisor adapters, and reconcile C2.
Current files/line numbers must be rechecked against the then-current source.
