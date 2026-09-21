# Lane F handoff: implementation orchestrator

## Current state (2026-09-21)

Continue autonomously in `.claude/worktrees/lane-f-pr1-verify`, branch
`codex/lane-f-pr1`; main contains unrelated work. The user resumed this lane and
retained checkpoint publication/merge authorization. No routine owner approval is
pending. Merge remains at stage F; C2 and D1/D2/E/F are open.

[Working contract and transcript lessons](evidence/C2/resume-2026-09-21.md) is the
context-reset entry point. Do not reread historical amendments to reconstruct the
current task. Root owns production lifecycle, shared Gradle and the dev stack;
subagents may own bounded tests or read-only investigation/review.

The prior recovery/lock correction is pushed as85154fed1. It passes full operations
storage, all13 local installed scenarios and fresh hosted CI35561341238 at job and
XML level, including4 Windows storage cases and13 installed recovery cases.
[Exact proof and historical failure limits](evidence/C2/hostile-lock-acceptance.md).

Pushed3caf90cbc: C2-10 queue capture and immutable settlement, schema19.
Final2133 represents1,036 cases, zero failures/errors,21 existing model/filesystem
skips;690 indexer cases execute,346 unchanged worker-core results reuse2129.
PMD/format, canonical checks and store gate pass. One consolidated review is fixed;
negative2131 fails all3 intended predicates and restores production bytes. Hosted
CI35563199072 passes all jobs. [Design, current implementation and exact evidence](evidence/C2/bulk-reindex-connection.md).

Pushed29071724c: exact generation start and response witness, strict v2 recorded
manifest with physical IndexFingerprint digest, and process-local serialization of
generation control. Focused2137 has42 cases/zero failures/one Windows symlink privilege
skip. Negative2139 fails all3 intended predicates. Review's equal-active/building
finding is fixed with two regressions. Full2141 executes5,280 cases/881 suites,
zero failures/errors and12 qualified skips; PMD/format pass. Final fixture2144 has19
passing cases without skips. Hosted CI35565311927 passes;2145 preserves Linux XML with all18 exact-generation
cases passing without skips, including the locally skipped symlink regression.

Pushed 65afa81fc, sequence3 preparation batch: physical target snapshot, immutable runner-owned
bulk progress in existing operation columns, accepted bulk plan resolver, and shared
prepared bulk/rebuild handler/catalog profiles. Focused2149 passes96 cases; negative2152 fails all three intended guards.
Full2153 executes5,538 cases with only the deliberate wire-golden mismatch;
PMD/format pass. Regenerated golden2155 passes31 focused cases. Review identified
read-only rebuild-brake preparation and missing policy assertions; corrections and
real braked Engine tests pass in 2158: 97 cases across 14 suites, no failures,
errors or skips. The Engine suite executes; three unchanged suites reuse 2157.
The seven affected PMD and six format checks pass in 2159; engine-port,
store-recoverability and generated documentation checks also pass. No build or
dev stack is active. Application bulk consumer remains unimplemented;
these preparation changes must not be called complete until connected.
Independent security review approved the narrowly scoped prepared-operation
continuation design in the bulk owner document. Existing HIGH-risk capsule approval
cannot survive the two required restarts; preserve ordinary capsule refusal and
mint the new exact key/nonce basis only after successful prepared consent. The
implementation and adversarial proof are still outstanding.
Pushed d69763186 implements the continuation locator, minting after prepared
capsule consumption, exact accepted key/nonce resolution, and current bulk policy.
Focused 2165 passes 92 cases, including the dead-code gate. Independent review's
alternate-handler finding is fixed; negative 2166 fails all three intended guards
and production bytes are restored. Hosted 35569918097 exposed missing resolver
surface registration and its then-missing caller; both are corrected locally,
with operation-surface 2167 passing. Full 2168 has one Windows resource-exhaustion
cleanup failure and four test qualifier PMD violations. Root identified and
stopped the lane's orphaned 47 GB PowerShell evidence collector, restoring 26 GiB
free physical memory, and fixed qualifiers. Full 2170 passes 3,240 tests with
zero failures/errors and three existing skips; app-services executes and unchanged
app-api results reuse 2168. All four PMD and both format checks pass. No build or stack is active.
Current captured traversal checkpoint adds Worker CAPTURED mode and sequential
multi-root capture under one retained Engine admission owner. Focused 2174 has
76 passing cases (22 Engine fresh, 54 unchanged worker/indexer reused); full 2175
executes 2,092 cases, zero failures/errors, 17 existing skips. Five PMD and three
format checks pass. Independent review is clean. Negative 2177 fails exactly both
intended guards; the two production files are restored byte-for-byte. No build or
stack is active. Hosted continuation CI 35572035546 passes every job except
platform-contracts, which encounters Maven HTTP 429 before any test execution;
that job is being retried, not counted as a test pass.
Boot design review selected one early immutable request before generation
initialization. Pending/fenced recorded startup must precede any .prev restoration;
completed IDLE active generations remain usable after operation-row expiry.
Next: connect the shared REINDEX consumer to captured traversal, strict boot,
manual REST dispatch and both restart boundaries, then prove installed bulk faults.
Then finish C2-12/full stress/hosted reconciliation and D1/D2/E/F. Full2064's known
configuration-funnel failure is corrected, but no final full-stress green is claimed.
Artifacts stay accessible in this worktree's tmp through lane acceptance plus30 days
and must be exported before releasing the worktree.

The following entries preserve earlier implementation history and its evidence.

Prepared-handler checkpoint132b25ec1 is pushed;
origin/main was already included at resumption. [Pause state and ordered remaining work](evidence/C2/session-2026-09-15-closeout.md).
Prepared-handler review corrections and retired agent scan wiring pass full1854:
5,128 cases/725 suites, zero failures/errors, six skips; all eight PMD tasks pass.
1856 architecture executes37 passing cases and reuses restored app-agent results.
Negative controls distinguish snapshot coherence, current-client lookup, collection refusal
and file/directory shape. Canonical docs are updated. Hosted CI35533098123 and
CLA35533095933 pass at132b25ec1; all11 Linux boundary cases execute without skips.
[Resumption evidence](evidence/C2/resume-2026-09-20.md).

Pushed0431bbe74 completes the bounded Engine producer item: full1826 executes3,240 cases/490
suites with three existing skips and zero failures/errors; independent source/evidence review,
PMD/format and docs/governance pass. Focused1831 clears the unreferenced-method check. All13
hosted CI34900811163 jobs and CLA34900809246 pass. [Producer proof](evidence/C2/recorded-engine-producer.md).

REST backend checkpoint093ef4166 and nullable-schema correction7143d6dfc are committed;
the caller item follows as its own commit. Full1878 passes app-agent692/UI1251 cases
(three existing skips), PMD and format. Three negative controls fail as intended;
restored1884 passes17 HTTP/context cases and1885 passes10 Node caller tests. Python's
eight ui_perf cases and shell syntax pass. Independent review finds no remaining
implementation defect in this cut. Fixture correctionce1e813cd passes full1893 build
and all nine UI integration cases without skips. Final1900 UI1,251 and1901 frontend6,589
cases pass; live1902-1904 prove the server/bridge confirmation and outcome contracts.
Live1896 regenerates245 routes and removes scan progress. Final hosted proof remains
pending; the complete Java producer proof stays .3c.

Current item [.3c production composition](evidence/C2/recorded-production-composition.md)
has exposed and corrected missing INGEST/REINDEX kinds in the production catalogs.
Live1909 and all four new registered-handler cases in1912 fail at the strict resolver;
1914 passes all30 coordinator cases after the catalog fix. Full1917 Engine299 cases
pass; restored1922 tests/lint and1923 architecture37 cases pass after review fixes.
Actual live success/recovery and .3c.2 remain in progress. Hosted CI35536949678
at30792f547 fails app-ui catalog projections and system integration; CLA35536948211
passes. Original failures are retained and are not treated as completed hosted proof.

Pushed3898195ab passes live1924 ingestion/search (five documents),1925 one real
standard-model query, and1931 retained outcome/retry/conflict checks. Live1929 also
shows INTERACTIVE survival in those rows. Root is closing the already owed
[declared survival admission](evidence/C2/declared-survival-admission.md) before
crash-survival proof. The owned live stack is stopped with data retained. Component
refusal matrix1935 passes34 cases and PMD; full1936 passes304 cases, no skips/failures.
Negative1939 removes both generation guards and fails both stale cases; restored1940
passes34 coordinator cases and PMD. None of that substitutes
for the front admission/cancellation fix or .3c.2b both-store recovery proof.

The [selected connection plan](evidence/C2/recorded-handler-connection.md) splits .3a
handlers, .3b REST dispatcher/consumer retirement, then .3c real vertical integration/proof.
Root/policy snapshots and root-owned wiring are installed and verified. The current
[REST bridge item](evidence/C2/rest-ingestion-connection.md) implements the alias, consumer
migration and scan-observer retirement; its prepared HTTP/store proof and live route
projections are recorded. Final hosted reconciliation follows .3c. Root owns shared lifecycle,
source integration, builds and publication. C2 remains open; merge stays at F.

The active root/child contract is [C2-8d-vertical-plan.md](evidence/C2/C2-8d-vertical-plan.md)
and [recorded-ingest-binding.md](evidence/C2/recorded-ingest-binding.md). Historical
recorded-root-preparation.md is superseded where it conflicts. Prerequisite recovery routing
is pushed1a75eb891 with final6,589 frontend cases, typecheck/focused checks and62 represented
Java cases (17 new,45 cached); source/evidence review and fixture UI checks pass.
[Recovery proof](evidence/C2/recovery-routing.md). Its earlier hosted guard failure is resolved
by0431bbe74. Earlier40a9a14b9/d23f1136e force/legacy prerequisites retain
[their evidence](evidence/C2/recorded-force-compatibility.md).

Recorded scan admission is pushed17d56daf0 with52 final1769 cases and independent negative proofs.
Its hosted CI34887604012 passes12/13 jobs; app-ui fails only the still-unbound producer method on three attempts, while
CLA34887601017 passes. [Scan evidence](evidence/C2/recorded-scan-admission.md).

Prior pushed checkpoint064adc779 fixes coordinator startup cancellation and
synchronous producer rejection. Hosted CI34878848566 passes12/13 jobs; its sole app-ui finding
is the still-unbound producer. CLA34878843824 passes. The obsolete maintenance helper is removed.
[Coordinator proof](evidence/C2/ingestion-coordinator.md) preserves full1735 and corrective
focused/negative-control evidence without treating the partial hosted run as green.

Force-at-claim checkpoint0aa0575b4 is pushed and locally verified: full1757 executes2,626
cases/493 suites,23 existing skips and no failures/errors, plus eight PMD tasks and format.
Independent review and all seven negative controls pass. Hosted CI34882097481 passes12/13,
failing only the unbound producer method; CLA34882092528 passes.
[Owning implementation, evidence and limits](evidence/C2/recorded-force-claims.md).
Next is actual Java-only recorded admission/adapter and prepared-handler wiring. indexing.proto
stays unchanged under C2-2. Frozen subtree pruning, cancellation after backpressure and force
compatibility ordering must be proven before producer activation.
C2 remains open; merge remains at F.

C2-9b.3b.3 receipt-only settlement now passes integrated1718:1,069 cases/186 suites,
zero skips/failures/errors, six PMD tasks and format. Focused15 cases, seven discriminating
negative controls and independent review are preserved in the
[settlement proof](evidence/C2/ingestion-receipt-settlement.md). Next is the stable
parent/child coordinator and actual bounded producer binding. Foundation13dd53189 hosted
CI34868482004 has12 passing jobs and one app-ui failure: the unreferenced-code guard
correctly detects the still-unconnected projection. Complete production wiring; do not
exempt the code. C2 remains open and merge remains at F.

C2-9b.3b.3 observation foundations now pass integrated1709:1,218 cases/234 suites,
21 existing skips and no failures, plus PMD/format. API/store1703 separately passed814
cases. Exact child lookup, compact receipt matching and locked issued-owner observation
are implemented; independent review and lock/acceptance negative controls pass.
[Proof and limits](evidence/C2/ingestion-coordinator-foundations.md). Next is receipt-only
runner settlement, then the stable parent/child coordinator and actual producer binding.
No live recorded producer is bound yet. C2 remains open; merge at F.

C2-9b.3b.2 startup/drain attachment is committed1ced178d9 and locally/hosted
verified through ae486f3e4 (separate pacing observation correction). Integrated1688
passes1,190 cases/231 suites,21 existing skips, zero failures/errors, plus PMD/format.
All13 hosted CI34859997847 jobs and CLA34859994378 pass at ae486f3e4.
Focused1686 also proves actual SQLite corruption/restore fencing. Earlier1685
failures and their corrections remain preserved in the owning evidence.
[Current proof and limits](evidence/C2/ingestion-authorization-recovery.md#c2-9b3b2-startup-and-close-attachment-2026-09-14).

C2-8d.3a strict sealed receipt projection is implemented and locally verified:
final indexer1698 passes625 cases/15 existing skips; unchanged Worker-core1692 passes
342/six skips. Independent review and corruption/retention/rollback negative controls
pass. All13 CI34863470535 jobs and CLA34863468188 pass at759775f6f.
[Receipt proof](evidence/C2/sealed-receipt-validation.md).
Next: stable activation/admission coordinator .3b.3, then actual bounded producer
adapters d.3b. No live recorded producer is bound yet; constructor defaults to denial.
Root owns lifecycle/SQL/builds; C2 remains open.
Pure fresh policy2bfd30b08 also passed all13 CI34853428167 jobs and CLA34853425540.

C2-9b.3a queue fencing now passes the full indexer suite:598 cases,15 existing skips,
no failures, plus focused and discriminating negative controls. Constructor default
denies recorded execution; stopped-orphan administrative closure remains possible.
[Queue proof and remaining attachment](evidence/C2/recorded-queue-authority.md).
A separate inherited real-model test-budget failure was reproduced and corrected;
focused1667 and full Worker-core1668 pass (342 cases, six existing skips, no failures). The source
and failed proof are preserved in [the correction record](evidence/C2/long-doc-test-budget.md).
Next is C2-9b.3b startup/reconciliation/lifecycle, then .3c actual producer. The
selected fresh/restart/admission design is pushed9dfaf9ee4; pure decision449f56a06
passed all13 hosted CI34846902769 jobs plus CLA. C2 remains open.

**Resumed after the user's reboot.** Checkpoint13207a960 passed all13 hosted CI34830819900 jobs; exact Linux filesystem XML
confirms all three Windows-skipped cases executed successfully. Evidence commit a822dff0f is pushed. Resource
preflight1611 reports no stack/foreign run/inference orphan. The preserved sealing draft is
implemented and C2-8d.2b.2 closure/seal is locally verified:1613 full affected suites report
915 cases/21 skips/no failures;1614 corrected focused tests and1615 guard negative controls
establish the reviewed claims. [Evidence](evidence/C2/walk-closure-sealing.md). Next is
C2-8d.3 producer activation and outer receipt/cadence integration. The queue-only C2-8d.2c
notification/retention attachment now passes integrated1624 (929 cases/21 skips/no failures),
with failure-path and discriminating negative proofs. [Evidence](evidence/C2/walk-notifications-retention.md).
The overall .2c acceptance still requires the actual outer owner and cadence/shutdown wiring.
Root owns SQL/lifecycle changes; independent test and read-only review work are bounded. The prior [pause summary](evidence/C2/session-2026-09-14-closeout.md)
remains historical. Existing autonomous authorization and merge-at-F placement remain in force.

C2-9a.1 authorization basis/selection/binding is now locally verified: full1631
app-api/services has3,122 cases, three existing skips and no failures; negative1632
and restored1633 pin the new guards. [Owning evidence](evidence/C2/ingestion-authorization-recovery.md).
C2-9a.1 is pushed as43fa7b3ac. The C2-9a.2 grant persistence-before-publication
correction now passes full1636 (2,901 services cases, three existing skips,
zero failures), negative1635 and independent review; pushed27246acd6.
C2-9b.1 now preloads one shared OperationAuthority and the existing WatchedRootsState
before the async fork, with identical state used by the client and Head. Full1642
services/Engine:3,145 cases, three existing skips, zero failures;143 focused cases,
three discriminating negative controls, UI compilation/PMD/format and independent
call-path review also pass. C2-9b.2 scope/decision is verified below; next is C2-9b.3 fencing and C2-8d.3 actual producer. Recovery activation remains open.
Hosted27246acd6 exposed the old UI assertion that omitted the server-selected basis;
[the correction record](evidence/C2/hosted-authorization-context.md) preserves the
exact failure. This is separate from the shared-authority implementation.
Notification checkpoint7777be3fd passed all13 hosted jobs in CI34835832804.

C2-9b.1 is pushed as9a3aa8bb0 and passed all13 CI34842534126 jobs plus CLA,
including the separately committed288113a2b UI context assertion correction.
C2-9b.2a strict frozen-root scope is pushed48b38b4ea and passed all13 hosted
CI34844688686 jobs plus CLA. C2-9b.2b pure typed decision now passes full1657
services:2,934 cases, three existing skips, no failures; focused Engine/UI and
negative controls pass. [Owning decision and proof](evidence/C2/ingestion-authorization-recovery.md).
Next is C2-9b.3 startup/per-claim fencing and C2-8d.3 actual producer, including
immediate stuck-job recovery after later Wait-to-Authorized reconciliation.
No queue permission or real recovery activation is claimed by the pure decision.

A/B/C1 are complete. C2 remains open; do not merge before stage F.
The public settings producer/controller is implemented and locally verified:
final1452 represents4,859 cases, four existing skips and zero failures/errors,
with PMD/format and UI integration compilation passing.
[Owning mechanism, review corrections and proof](evidence/C2/public-settings-producer.md).

All eight frontend callers now use the shared witnessed attempt helper. Final1485
passes6,573 tests across489 suites; typecheck/lint and independent reviews pass.
[Retained-base proof and negative controls](evidence/C2/frontend-settings-derived.md).
Hosted frontend CI34800723187 and CLA34800721640 pass at e135acb0b after the
consumer-registration correction ([evidence](evidence/C2/hosted-frontend-contract-correction.md)).
The raw save API is retired and the physical/config caller guard passes integrated1500:
4,187 represented cases, four existing skips, zero failures/errors, PMD/format and
UI integration compilation ([evidence](evidence/C2/settings-writer-retirement.md)).
The repaired harness now proves20 mapped Settings/Library/Brain captures with zero
axe/console/overflow findings; Python UI/CLI1508 passes218 tests. [Browser proof](evidence/C2/frontend-settings-browser.md)
records earlier failures and fixture limitations. Both owned Vite helpers5185/5186
were identity-verified and closed by sweep1509. No Gradle/dev stack is running.
C2-7 checkpoint cadence/shutdown is implemented and locally verified: final1514 represents
4,964 cases/769 suites, four existing skips, no failures/errors; PMD/format/UI integration
compilation pass. Negative1512 proves both callbacks matter. [Checkpoint evidence](evidence/C2/checkpoint-cadence.md).
Hosted CI34801903519/CLA34801900544 pass at writer-retirement dc06b4be4;
CI34802770723(all13 jobs)/CLA34802769444 pass at browser347187073. PR727's managed
review record is verified through those checkpoints. C2-7 CI34804384959 and CLA34804383862
pass at5a4d1dde3. C2-8a scheduled key delivery is locally verified: 1,936 Java cases
(one existing skip), 6,577 frontend cases, PMD/format/typecheck/lint and negative controls.
[Scope and evidence](evidence/C2/scheduled-key.md); CI34805930681(all13 jobs) and
CLA34805928429 pass at2ab49abc4. PR727 review record is verified through the scheduled
key checkpoint (its CI was still running when the record was updated).
C2-8b committed content hash and idle/shutdown outcome retry are locally verified:
integrated1525 executes2,179 cases/423 suites, 20 existing skips, zero failures/errors;
PMD/format pass. Combined and isolated shutdown negatives are preserved; final1527
restores the exact production source and passes65 cases. [Proof and limits](evidence/C2/committed-content-hash.md).
Hosted CI34807238861 (all13 jobs) and CLA34807236808 pass at07813a14a.
C2-8c admission revisions and reenqueue ownership preservation are locally verified:
final1533 represents2,186 cases/424 suites,20 existing skips, zero failures/errors;
PMD/format pass. Focused1530, discriminating negative1531 and the corrected version-pin
failure1532 are preserved. [Proof and limitations](evidence/C2/admission-revision.md).
Hosted CI34809851127 (all13 jobs) and CLA34809849392 pass at128a0c945.
C2-8d.1 frozen roots and child acceptance are implemented as a WIP primitive: integrated1537
executes5,013 cases/774 suites, four existing skips, zero failures/errors; ten PMD and
whole Spotless pass. Negative1536 discriminates stale preparation and retention defects;
independent review's COMPLETE_WITH_GAPS correction is included. [Evidence](evidence/C2/recorded-ingest-binding.md).
C2-8d.2a adds WIP schema/epoch and actual batch-exit ownership primitives.
[Proof, failures/corrections and remaining cuts](evidence/C2/walk-claims.md).
Hosted0123adb1f fixes the missing resolver declaration but failed a Windows parser recycle
fixture before its native-child handshake. The test-only startup correction passes the
exact Windows CI module selection locally (113 cases, no skips/failures), with deterministic
budget and containment negative controls. [Proof and pending hosted run](evidence/C2/parser-containment-startup.md).
Actual producer/queue/recovery activation remains open in the [per-item vertical plan](evidence/C2/C2-8d-vertical-plan.md).
C2-9's [authorization recovery contract](evidence/C2/ingestion-authorization-recovery.md)
requires a server-built basis, exact grant re-resolution and sole preloaded roots/evaluator.
PR727 managed review record is verified through112d2e05d. Hosted d892321c6 passes
Windows-native tests and twelve jobs, but its advisory integration job fails; recovery
failures are being diagnosed and remain required lane work despite overall green CI.
C2-8d.2b.1 admission/terminal accounting passes final1567:2,225 represented cases,
20 existing skips, no failures/errors, PMD/format pass. [Proof and review corrections](evidence/C2/walk-terminal-accounting.md).
Next cut is administrative closure/seal, then notification/ack retention and real producer/recovery.
Installed1568 fixes a stale positive-count recovery log assertion and passes writer, lock-boot,
processing and operation-resume. Migration (Green fingerprint missing) and lock-ingest
(SQLITE_BUSY admission) fail and are under active repair. Hosted initial-discovery failure
is still unproven locally. [Owning evidence and next repairs](evidence/C2/hosted-recovery-correction.md).
The queue upgrade defect is locally corrected:mutation transactions reserve write access before
preservation reads. Full1572 passes524 queue cases,12 existing skips, PMD/Spotless, with a
deterministic competing-writer negative and no-phantom-delta proof. [Details](evidence/C2/queue-write-reservation.md).
The migration barrier now reconciles existing backfill success and requires a current-model
stamp before the final commit, including fresh COMPATIBLE Green. Its old-code negative
fails in all three intended cases; final1576 represents870 cases/174 suites with18 existing
skips and no test failures/errors, PMD/Spotless pass. Final review1579 adds exact-model binding and successful REBUILDING coverage (40 executed
cases, no failures/skips). Installed1578 passes five of six, including real-model migration
and lock-ingest; lock-boot reproduces initial Engine exit before discovery. The inherited resumed-empty corruption-recovery waiver is now corrected with exact
persisted provenance, opened-generation binding and a trustworthy zero count; full1583
indexer/core and PMD/Spotless pass. [Proof](evidence/C2/corruption-resume-attestation.md).
Initial-discovery diagnostics now preserve the actual exit and causal stderr. Installed1589
passes five of six; targeted1591 proves WAL setup returns SQLITE_BUSY despite its busy timeout.
The BUSY-only initializer retry passes integrated1596 (3,453 cases, three existing skips)
and all six installed recovery cases1598, plus independent review, PMD/format and governance.
[Startup retry proof](evidence/C2/operations-startup-busy.md). Quick1599 confirms clean fixture
shutdown. Startup checkpoint518f13d62 CI34827632310 passes all13 jobs and CLA34827630282
passes. The preceding Maven HTTP403 rerun was cancelled by this newer push, not passed.
Zero-root and partial migration enumeration are now corrected: only complete declared coverage
sets done, failures block promotion and the existing monitor retries FAILED persistence, with
one shared watched-roots format authority. Integrated1604 executes4,050 cases/638 suites,
24 skips (21 existing plus three Windows filesystem limitations), zero failures/errors;
PMD/format and all six installed recovery cases1605 pass. [Mechanism and proof](evidence/C2/migration-enumeration-completeness.md).
Final review also closes a transient-null-state early exit: failure consumption now precedes
state classification. Negative1607 fails exactly that branch; final1608 executes561 indexer
cases with15 skips/no failures, PMD/format pass. Combined with unchanged1604 modules,4,051
cases/638 suites are represented with24 skips; installed1605 covers the unchanged normal
success path before this final failure-order correction. Linux permission/symlink tests
remain required hosted proof. Quick1606 confirms fixture cleanup. Next implementation is the settled queue-owned administrative closure/seal cut. The [recovery record](evidence/C2/hosted-recovery-correction.md) retains exact evidence. [Attestation proof](evidence/C2/migration-cutover-attestation.md).
The [ingestion ownership plan](evidence/C2/C2-8-ingestion-plan.md) selects a queue-owned
walk receipt, existing ledger and current membership after independent review. Next is
C2-8d recorded walks/C2-9 scoped recovery, C2-10 reindex, then
required integrated/live/model/installed/final-head proofs and D1/D2/E/F. Root makes
remaining mechanism/merge decisions; no owner reply is pending.

Pushed prerequisites: installer/import09f91917e, atomic read/wire1e49d18a5,
system-access residue3d842f496, durable replay witness7f665a33d.
[Installer proof](evidence/C2/installer-model-producers.md),
[wire/replay proof](evidence/C2/public-settings-wire.md),
[activation proof](evidence/C2/activation-settings-producer.md).
Hosted CI34797375495 and CLA34797373680 pass at public producer c8aa61591; this
does not prove subsequent frontend work. Prior stale-allowlist failure and exact nine-line
retirement are [recorded](evidence/C2/hosted-system-access-correction.md).

Active worktree: F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify,
branch codex/lane-f-pr1, draft [PR727](https://github.com/justsearch-app/justsearch/pull/727).
Merge stays at F. Original lane-F-A is held through2026-09-20 for PR718/evidence/
foreign helpers; do not build/edit/kill there. Main remains foreign dirty work.

Root alone runs Gradle; no compiled-source edits during builds. Push every per-item
commit, with scope and exact proof in its body; WIP at least hourly. Reassess workers
after two correction rounds and take their diff by three. Continue after checkpoints.
Use revision-local MCP configuration for live work; attached tooling still references
main's retired stack. Keep the client alive through start/capture/owned stop.
Set JUSTSEARCH_AGENT_SESSION_ID from CODEX_SESSION_ID. Raw evidence lives in active
tmp (older bundles in lane-F-A/tmp); retain through lane acceptance plus30 days and
export before worktree release. Final acceptance must distinguish local, hosted,
live/model and installed proof; none substitutes for another.

## Historical handoff record (preserved)

**Current authority (owner, 2026-09-08): merges are delegated to the orchestrator.**
The earlier per-PR approval requirements below are historical and superseded.
The orchestrator verifies, reviews and merges autonomously through the repository
queue; no owner reply is needed for #708 or #717.

Latest state: C1 complete including the later MCP quota correction, verified at
the R10 boundary; C2 is open. R7's installed operations-row foundation witness
and R9's later schema reproof are accepted with the correction batch. C2-3 is
in progress; the six keyed C2-11 recovery scenarios and later producers remain required.

C2-6's [reviewed corruption-recovery amendment](evidence/C2/C2-6-plan.md#2026-09-13-settings-history-recovery)
requires typed witness comparison and confirmed reset with exact frozen quarantine evidence.
It is design authority, not implemented recovery proof. Root owns the remaining migration;
no owner input is pending and no armed ambiguous operation may be guessed FAILED.

Hosted availability recovered; ADR/register Public claims now passes. CI34776942178 at2accba28b
passed12/13 jobs and failed the raw-attach test's initial-replay/live-delivery race. The test now
waits for actual observe return, and a separate regression pins initial failure. Final1317 runs
allthree cases green with PMD/format; negative1316 still catches lost detach notification.
[Hosted diagnosis/local proof](evidence/C2/raw-detach-barrier.md). Fresh hosted success remains
required. Windows-native success is not installed-v5 recovery proof.

Latest hosted checkpoint ae46f6919: CI34778431996 all13 jobs and CLA34778430639 pass.
The queue delay has cleared and public-claims/app-ui corrections are hosted green. This
predates the fixed reset schema diff and does not satisfy installed-v5 recovery proof.

Full1340 exposed a settings owner public-entry self-call. The [correction](evidence/C2/settings-owner-entry.md)
now uses private shared reservation checks and guards both reset methods; focused1344
and the intended negative1343 pass their respective contracts. Corrected full-suite and
successor publication proof are next, before continuing production migration.

Fresh candidate codex/lane-f-pr1 is based on mainb4d972b6b with product sources
identical to pushed8bf81ea2d. Full1346 passes10,496 cases (25 skips), build1351 passes,
stress1350 includes both stress classes, and frontend/agent/governance checks pass.
[Candidate evidence and remaining publication steps](evidence/C2/publication-lineage.md#fresh-candidate-proof-2026-09-13).
Next: clean-candidate preflight, successor draft/review record/hosted checks, then C2-6.
The next implementation item extracts the pure settings response projection, preserving
controller-only default index injection and snapshotting excludePatterns for prebuilt receipts.
Then share fixed-reset pure metadata validation for handler prepare/preview/replay; retire the
late reset callback in the production cut. Launcher also constructs a real runner with durable
operations but IN_MEMORY settings and lacks a restart owner; preserve that explicit limitation
while composing fail-closed settings ownership. No owner input is pending.

**Immediate next items (2026-09-13): integrate main privacy safeguards and repair PR1 publication lineage, then C2-6 producer/Health migration.**
The [fixed reset owner](evidence/C2/settings-reset-owner.md) implements reserve/apply/recovery
and durable clear/restart ordering.1334 executes155 settings cases plus41 unchanged runner/UI
cases; negative1335 has five intended failures, restored1336 passes196. Independent review is
clear. Production composition, every settings producer/public wire, Health and installed
successor-bootstrap proof remain required; this is not C2-6 completion.

Origin main b4d972b6b adds the identity gate that now rejects365 historical lane commits.
Current effective identity and latest commits already comply. The original per-item checkpoint
history/evidence is retained; root is creating a successor PR1 lineage without force-push or gate exceptions.
[Decision and exact remaining candidate protocol](evidence/C2/publication-lineage.md).

The fixed reset reservation/clear/restart mechanism passed independent review: preserve
quarantine block through precommit failure, clear only after durable COMPLETE, then use the
existing process-owned ordered restart. [Mechanism and required fault matrix](evidence/C2/C2-6-plan.md#2026-09-13-fixed-reset-reservation-and-recovery-clear-order).
This is governing design; reset implementation and successor-bootstrap proof are next.
The [accepted recovery input/metadata decoder](evidence/C2/settings-recovery-input.md) passes
121 executed cases plus PMD/format; register gates also pass. Negative1309 catches lost accepted
payload and missing key binding; negative1312 catches eager multiple-row loading and swallowed
SQL failure. Restored1313 reuses identical final sources. The fixed reset schema
and owner must now consume that input and revalidate the quarantine fingerprint before arming.
The [quarantine identity reader](evidence/C2/settings-quarantine.md) now passes71 executed
cases plus PMD/format, with a content-digest negative control and byte-exact restoration.
It supplies read-only evidence; fixed accepted-preparation decoding and reset owner integration
are next. It does not authorize reset or close C2-6.
The shared witness-pair foundation now passes152 cases (83 executed/69 reused in1293),
plus format/PMD; negative1297 rejects revision-only comparison. Restored1298 reuses the same
152 passing cases with identical source hashes. [Proof](evidence/C2/settings-witness.md).
The physical owner is still uncomposed; confirmed recovery reset and public producers are next.
The [physical file/config owner foundation](evidence/C2/settings-owner.md) passes Owner1286:
100 cases/23 suites (71 executed,29 reused), no failures/errors/skips, PMD/format and independent
source review clear. A real concurrent regression exposed blocking cleanup of an unrelated
refused attempt in1285; the owner now returns promptly for mismatched fence ids. Negative1287
fails at all four intended assertions; restored1288 passes via unchanged/cache reuse with the
same source hashes. This owner remains uncomposed; the subsequent witness-pair cut above supersedes its scalar
expected-revision API before producers migrate. No full/live/
installed/hosted or recovery-genesis proof is claimed by this local foundation slice.
Atomic snapshot/reconnect and retirement production is at e7edaf503. Live1238 passes17
protocol checks; full UI1248 passes6520/typecheck. Full1249 now passes10403 represented
cases (1310 in executed tasks,9093 reused), zero failures/errors and35 inherited skips,
including PMD and UI integration tests. Full1242 remains FAILED on three stale keepAlive
assertions; their replacements verify request-future lifetime, and real Javalin now proves
SseConnection retains admission until close. Negative1250 catches premature completion;
1245's wrong-reason fixture failure remains recorded. [Integrated proof](evidence/C2/integrated1249.md).
Browser1252 passes eight functional checks: actual checkpoint reconnect, durable reload
snapshot after selecting Activity, and same-key retry without duplicate rows. Its measured
capture has three existing but unbaselined accessibility rules and inaccurate theme metadata;
[the browser record](evidence/C2/activity-browser.md) assigns the next corrections. Failed1244
and1247 remain retained. All owned stacks are stopped. Integrated tests are pushed at478a41c41.
Installed-v5 and hosted proof remain owed; no C2-4/stage closure is claimed.
C2-6 cut1 now implements the preparation/storage foundation with Final1259 green (85
represented cases, 76 executed/9 cached) and six intended negative1258 failures. Independent
review is clear. [Storage proof](evidence/C2/settings-preparation.md). The [SQL marker](evidence/C2/settings-marker.md) now passes Marker1268 (45 represented,
all executed, no failures/errors/skips), with PMD and format checks. The runner protocol now
passes Final1281 (91 cases/19 suites, all execute, no failures/errors/skips), with PMD/format
and independent source/evidence review. Negative1280 fails at three intended assertions;
failed1277 and earlier passing snapshots remain retained. [Runner proof](evidence/C2/settings-runner.md).
The subsequent concrete owner is summarized above; composition and public wire-outcome proof
remain absent from the runner slice. Activity corrections now pass
Browser1266/light and1267/dark (nine checks each, zero axe/console findings and no page overflow).
Typecheck1273 and full unit1276 pass6521 cases/485 files; the full unit log retains inherited
connection/abort warnings. Focused Shell fixtures pass36 cases without AggregateError.
Independent source/evidence review is clear. [Correction proof](evidence/C2/activity-accessibility.md)
retains failed intermediates and binds browser binaries to868ca9f85 plus the listed UI source;
the later SQL-marker commit is outside that browser proof.
The runner now reserves and validates through its fixed owner before arming SQL;
[the reviewed correction](evidence/C2/C2-6-plan.md#2026-09-13-reservation-order-correction)
closes refused/stale-contender crash ambiguity and requires whole-set boot classification.
C2-6's independently refuted [implementation plan](evidence/C2/C2-6-plan.md) and owning
protocol are committed at6db0fd2b1, including the SQL marker, preparation guard, strict atomic
replace, schema-v3 witness and explicit fatal/corrupt recovery. No owner input is pending.

The completion
consumer now attaches at the end of both Head constructors, publishes memory/note
completions through the source hook, and owns bounded forced-append/ack retry and
startup catch-up. Independent review corrected failed-bootstrap ownership and
unbounded startup replay (including clock regression). Focused1191 executes98 passing
cases; final1194 reuses those successful inputs with PMD/format/integration checks green.
Negative1192/1193 catch unbounded replay and acknowledgement after rejected append.
Live1198 passes15 checks with a blocked sink, repair/restart, HTTP/MCP parity and a
durable pending1-to0 transition. Both owned stacks are stopped. [Consumer proof](evidence/C2/history-consumer.md).
Live1195 was stale because only app-launcher:installDist was refreshed: the dev runner
uses **:modules:ui:installDist**. UI install1197 and matched installed jar hashes correct
the witness. Full1202 now passes at4c6389be4 with stress enabled:10362 represented
cases (10195 execute,167 cached), zero failures/errors and35 inherited skips.
PMD and UI integration checks pass. [Integrated proof](evidence/C2/integrated1202.md).
Installed-v5/hosted remain owed; C2-4 and the lane stay open. The subsequent SSE
implementation and proof are summarized above and in the [SSE plan](evidence/C2/C2-4-sse-plan.md).

The first SSE sub-item now serializes publication and gives each listener one bounded
drainer outside channel locks. Negative1204 exposes the prior ordering/fan-out races;
final1210 and compatibility1213 pass, including actual-eviction numeric run-zero proof.
Independent channel review is clear. [Ordering proof](evidence/C2/sse-ordering.md).
The later snapshot/replay attachment and incarnation/checkpoint cut is implemented
and verified above; the ordering cut alone was not its proof.

Frontend keyed convergence is implemented separately: operation history declares
operationKey through Resource.primaryKey, and EVENT_STREAM/HISTORY merge overlap
without collapsing distinct invocations or unkeyed rows. Negative1211 catches disabled
key routing; restored1212 passes19 and full UI1209 passes6516. Independent review
verifies the catalog-to-ResourceView path and finds no substantive defect.
[Keyed-merge proof](evidence/C2/history-keyed-merge.md). The later1238/1241 campaign proves
live reconnect/restart and simulated-source DOM overlap; browser/installed/hosted tiers
remain as stated in the current status.

Frontend checkpoint recovery now clears the failed logical token and reconnects through
the existing physical owner. Independent review's reentrant-start defect is reproduced
innegative1220 and fixed; final1221 passes46, full1222 passes6519 and typecheck is green.
[Checkpoint recovery](evidence/C2/checkpoint-recovery.md). Backend snapshot boundaries,
incarnation validation and lifecycle checkpoint tokens now have the focused proof above;
live1238 and rendered1241 now pass; browser/installed/hosted proof remains as stated above.

**Prior effect identity cut (2026-09-13):** ingress rejects server
namespaces, and new frontend effects persist UUID identities while old entries keep
their prior wire identity. Final1172 passes33 represented cases (21 execute), live1173
passes14 exchanges across restart, full UI1175 passes6511,27 UI gates pass and
independent reader review is clear. [Correction proof](evidence/C2/effect-identity.md).
The first full UI1171 watchdog timing failure is retained; its assertion now uses a
controlled clock. Negative1178 catches disabled expiry; full1179 passes6511.
[Separate test correction](evidence/C2/watchdog-test.md). Full JVM1180 finishes with
one unused journal factory failure among10342 represented tests; all other checks
pass. The consolidated factory passes64 focused cases in1182. [Integrated finding
and correction](evidence/C2/integrated1180.md). Full1202 above supplies the new green boundary.
The pending-source review found no defects. Journal force/retry is now verified:
negative1155/1157, final1158 passes47 represented cases (32 execute), independent
review clear after adding rotated-generation proof. [Sink proof](evidence/C2/journal-force.md).
The completion consumer above supersedes this cut's former next item. Atomic SSE
and full/installed-v5/hosted remain owed.

**Latest C2-4 pending-source cut (2026-09-13):** schema v5 atomically marks visible
completions and pre-start refusals as pending projection. Bounded reads and keyed
acknowledgement share the operations owner; pending rows survive startup/age/cap
pruning. Final1150 executes104 passing cases, with PMD/integration compilation;
negative1148 catches both pruning regressions. Register77, release12, Rust85 and
Java upgrade13 checks pass. [Source proof](evidence/C2/history-pending.md).
Continue completion consumer attachment, durable sink/ack ordering, idle retry,
memory/note fan-in, shutdown and atomic SSE. The source bit alone is not delivery.
Installed-v5/coherent full/hosted proof remain required; no C2-4 or lane closure.

**Latest C2-4 reader cut (2026-09-13):** recent history now reads bounded committed
operations metadata; SQL/live entries share optional operationKey and the ledger
uses it as invocation identity. Schema remains v4. Final1139 passes213 represented
cases (95 execute), PMD/format/integration checks; negative1138 catches ordering and
identity regressions. Live1141 proves the served schema, accepted failure and keyed
retry across a controlled restart; the owned stack is stopped. [Reader proof](evidence/C2/history-reader.md).
Continue source acknowledgement/catch-up before pruning, memory/note fan-in and
atomic SSE reconnect from the [C2-4 plan](evidence/C2/C2-4-plan.md). No projection
acknowledgement is active yet. Installed-v4, coherent full, independent batch review
and hosted proof remain owed; C2-4 and the lane remain open.

**Prior local boundary (2026-09-13; superseded by full1202 above):** full1059 passes at13b6bd680 in10m36s,
10301 represented cases, zero failures/errors and35 inherited skips; eight test
tasks execute and30 reuse unchanged successful inputs. PMD and integration-test
compilation pass. [Integrated proof](evidence/C2/integrated1059.md) and the latest
full-run summary supersede full1009 as the current successful local boundary.
Latest observed CI34753477130 at7b5248442 remains pending; CLA34753476241
is queued awaiting external runner allocation. Superseded CI34748767868 was cancelled. No hosted success is inferred. Continue [C2-4](evidence/C2/C2-4-plan.md),
after the UNDONE wire correction (negative1061, focused1062/schema1063 pass;
wire gate1067 passes at eac25abb3). C2-3's remaining settings/ingestion/reindex
consumers stay open in C2-6/8/10; no batch2 or stage closure is claimed.

**C2-4 keyed query (2026-09-13):** the row/fence read, six-state DTO, HTTP route,
seventh read-only MCP tool and published schema are implemented. Final1079 passes
78 represented cases (71 execute, seven reuse successful unchanged inputs), PMD,
integration compilation and installDist. Negative1077 detects the row-first defect.
Live1089 proves discovery, HTTP/MCP parity, invalid-key errors, served schema and
Host/Origin rejection; the owned stack is stopped. Route captures now reflect the
live Engine. [Query proof](evidence/C2/keyed-outcome-query.md) records intermediate
failures and evidence. Continue the C2-4 durable recent-history swap, completion
projection/replay and SSE boundary; neither C2-4 nor batch2 is closed. Hosted proof
remains pending external runner allocation. A post-cut compile check caught the
launcher architecture fixture missing outcome(String);1096 passes after its
correction. Include launcher tests for every subsequent OperationStore port edit.

**C2-4 completion hook prerequisite:** final1100 executes65 passing cases;
negative1099 proves the callback lock test detects the regression. The operations
store exposes a live subscription for successful terminal transitions, including
non-dispatched memory/note producers and pre-start rejection. No replay or ledger
consumer is activated yet. [Proof](evidence/C2/completion-subscription.md). Continue
with acceptance-time history visibility, bounded terminal reads, retention/replay
ordering and the ledger deduplication contract in the C2-4 plan.

**C2-4 journal retry prerequisite:** final1106 executes38 passing cases after
negative1104/1105. The existing journal now deduplicates retained ids beyond its
read tail, retries persistence before live-ring dedup and preserves a torn-line
boundary. [Proof](evidence/C2/journal-retry.md). No source-row replay consumer is
activated. The next design choice must reconcile the operations/journal retention
windows before the durable-history swap; the alternatives and numeric-id recovery
constraint are recorded in the C2-4 plan. Current hosted jobs remain queued.

Final independent correction verification is clear at **b713307ea**. Full905 passes
in9m30s with10158 represented cases, zero failures/errors and35 inherited skips;
seven test tasks execute2040 cases and31 reuse unchanged successful inputs. PMD,
formatting and UI integration-test compilation pass. The full905 XML is preserved,
and the latest full-run summary records it; full900's formatting failure remains
historical failed evidence. Source inventories identify53 raw Git blobs atb713307ea.
Hosted CI34734430973 passes all13 jobs with fresh installed recovery and orphan
witnesses. The R1–R10 correction batch is accepted.
See [the final review record](evidence/C2/final-independent-review.md). No new
producer is activated yet. Continue C2-3's keyed
lookup before preparation, separated prepared payload and approval carry-through.

C2-3 is now in progress: validated lookup is committed at382b19a5c with294 passing
represented cases at909. The keyed dispatcher SPI/receipt branch passes333 cases
at913 after negative910/912. HTTP invoke/undo key delivery, typed failures and approval key/mode carry-through
and approved-result key/error projection now pass265 represented cases at923.
MCP key delivery, pending approval and failure receipt projection pass278 represented cases at936;
numeric row-id correction passes74 cases at942 after negative941.
See [MCP key proof](evidence/C2/mcp-key.md). The bounded prepared-envelope codec
prerequisite passes548 represented cases at950; [codec proof](evidence/C2/prepared-codec.md).
Operations.db v3 prepared storage passes363 represented cases at958, with atomic
transfer, stale nonce, retention and migration proof; [store proof](evidence/C2/prepared-store.md).
Runner serialization and completion publication outside the key lock pass336 represented
cases at966 (reuse explicitly recorded); [runner proof](evidence/C2/prepared-runner.md).
Frozen approval reference delivery and domain-separated consent pass422 represented
cases at975; [approval prerequisite proof](evidence/C2/prepared-approval.md).
Connected dispatcher/cipher wiring now passes424 cases at984, including real admission ownership and two negative controls; [dispatcher proof](evidence/C2/prepared-dispatch.md).
HTTP exact-reference delivery and typed locked ingress pass284 cases at988; [ingress proof](evidence/C2/prepared-ingress.md).
Frontend exact-reference retries and immutable approval input pass6480 tests at992, with typecheck/lint and UI coverage green; [client proof](evidence/C2/prepared-client.md).
Frozen approval preview backend passes463 cases at997, including actual MCP pending/SSE privacy and full HTTP target delivery; [preview proof](evidence/C2/prepared-preview.md).
Long-target wrapping and control reachability pass four measured UI cases at1007 (zero axe/console errors), with6480 frontend tests at1008; [presentation proof](evidence/C2/prepared-preview-ui.md).
Full1009 at852916d16 passes10252 represented cases (8178 executed), zero failures/errors
and35 inherited skips, with PMD/integration compilation. Preparation-only dispatcher/router
entry points pass358 cases at1011 after the route-first LOW-effect negative; [planning proof](evidence/C2/prepared-planning.md).
Workflow gate registration-before-announcement and nested live approval delivery pass32
represented cases at1014 after four intended negative1012 failures;
[delivery proof](evidence/C2/workflow-approval-delivery.md).
Private live approval lookup now passes427 represented cases at1021, including
reply/announcement/cancellation lifetime and private full-target display;
[lookup proof](evidence/C2/private-approval.md).
Agent preparation-reference carry-through now passes978 represented cases at1024,
including the full684-case agent suite and the scope-order negative1022;
[agent proof](evidence/C2/agent-continuation.md).
Workflow preparation-reference carry-through, declared confirmation floors and
nested approval snapshots now pass2155 cases at1027 (one inherited UI skip);
[workflow proof](evidence/C2/workflow-continuation.md).
Nested background posture through LlmStep/engine/agent shape delegation now passes747
represented cases at1029 after negative1028; [posture proof](evidence/C2/background-shape.md).
Frontend private lookup now passes6490 tests at1036 plus typecheck/lint, with
stale-response and raw-display negative controls; [client lookup proof](evidence/C2/private-approval-client.md).
Full1038 reports one architecture failure among10292 cases: three obsolete agent
overloads. Their removal passes772 executed agent/launcher cases at1039;
[cleanup proof](evidence/C2/agent-cleanup.md). Workflow metadata is corrected with76 passing tests
at1043 and real-store direct/nested proof; [metadata evidence](evidence/C2/workflow-meta.md).
Stream ownership is corrected with6508 passing frontend tests at1050 and162
focused tests at1052 after the cleanup negative; [stream proof](evidence/C2/stream-owner.md).
Projected workflow delegation and exception metadata are corrected with81 executed
passing cases at1058, including a primary-exception/cleanup-write failure regression;
[delegation proof](evidence/C2/workflow-delegation.md). The actual nested LLM fixture
now passes while direct AGENT-to-USER denial remains. Next repeat integrated proof,
then finish C2-3 consumer placement and the remaining C2 stages.
Next: remaining ingress and integrated C2-3 acceptance. Hosted v3 proof remains
required; the earlier hosted b713 correction acceptance certified v2.
See [C2-3 plan](evidence/C2/C2-3-plan.md). No prepared producer is active.

The shared GatedOperationExecutor preparation/route kernel passes525 represented cases
at1017 after the public-only capsule negative for both agent/workflow transports;
[shared route proof](evidence/C2/gated-preparation.md). The loops must now retain that
same plan through their approval/retry paths; private display and nested snapshot
delivery remain required before activating prepared producers.

The following paragraphs retain the pre-review correction checkpoints; current
acceptance is governed by the summary above and the correction batch checklist.

Current priority is the [September13 review correction batch](evidence/C2/review-correction-september13.md),
adopted at429115fec. New ingestion work is paused. R1 async persistence observability passes its named
regressions and focused790 (92 cases), UI integration-test compilation and PMD.
R2 launcher exclusion passes45 cases and its item gate at795. R3 checked
transition results pass797 and three negative controls. R4 mandatory acceptance and
typed admission reasons pass88 cases and five negative controls. R5 bounded history,
schema v2 and the registered hourly timer pass228 cases and the item gate at810.
The terminal write now returns its committed snapshot so retention cannot race
completion publication. R6's named acceptance-before-effect child-JVM kill passes
at814, with two wrong-side controls. R7's installed operations-row proof passes816,
and restoring the former acceptance bypass fails817. The six keyed C2-11 scenarios
remain open. R8's failure boundaries and coalescing pass136 cases at821, with
named negative controls819/820/822 and exact-source restoration823. R9 reconciliation
and held-source gate pass835 (281 cases), native85 and compatibility/port checks.
R10 is in progress: c94494525 corrects app/index lock exclusion after a Linux hosted
failure reopened R2; gate848 passes63 cases. CI now runs release-assets tests.
Full853 exposed a fixture completion race;33e54dfcd gives all five port calls one
owner and focused857 passes12 cases. Full858 then found the unused snapshot helper;
d9c80a646 holds it with its fixtures, and45 active cases pass862. Full863 exposed a
false-positive lifecycle marker search: c56e1a838 requires modified indexed content
before deletion; negative867 rejects a missing submission and focused868 passes8 cases.
Full869 now passes10133 represented cases/1647 suites,35 inherited skips, with the
Engine suite executed and other unchanged results reused. CI34730328727 atd9c80a646
passes all13 jobs, including first-attempt Linux exclusion, MCP quota, installed
operations recovery and parser orphan witnesses. It supplies the fresh installed tier;
no duplicate local installed rerun is needed. Current CI34731185342 atc56e1a838 also passes all13 jobs.
One final independent R1-R10 review remains pending. Both source snapshots match53
committed blobs; held patches reconstruct23 originals. The R10 artifact inventory
and full-run summary record the final available proof with explicit reuse. Root owns corrections.
The prior next-step ingestion notes below are superseded by that ordered batch.

September13 R9 holds the unactivated root-plan and ingest-child APIs outside compiled
sources. Their [source packet](evidence/C2/held/README.md) is preserved for C2-3/C2-8/C2-10;
it must be adapted to separate public key identity from persisted preparation before
activation. Current R9 proof is [the reconciliation record](evidence/C2/review-r9-reconciliation.md).

## Historical checkpoints (current next work is the correction batch above)

The [recorded ingest child primitive](evidence/C2/recorded-ingest-child.md) has complete
local proof at fc679f92a plus this item: focused786 passes96 cases; broader787 executes
741 cases/134 suites across app-api, app-observability and app-launcher, with zero
failures/errors/skips and selected PMD green. Independent review fixes cover direct
store-call architecture bypass and typed storage failure receipts; negative785 catches
both lost-code paths. Parent owners compose child durable completion explicitly.
The [operations port catalog](evidence/C2/operations-port-catalog.md) now names both
implemented ports and their actual outer bindings; its gate and negative omissions
are verified. Next: connect recorded ingest/reindex owners, survival admission,
accepted scan keys, generation checks and committed-unit completion. C2-2 remains open.
Declared-kind hosted CI34722656207 failed only its stale policy-component fixture;
[correction fc679f92a](evidence/C2/record-kind-policy-fixture.md) is pushed and both
policy-axis checks pass locally. Successful successor hosted proof remains required.

Current work is [recorded ingestion preparation](evidence/C2/recorded-root-preparation.md).
The common preparation/acceptance seam has focused761 proof and broad763 proof:
3491 cases/557 suites, zero failures/errors,3 skips, all four affected test tasks
executed and PMD passed. Negative762 causes six intended unsafe-payload failures;
independent reread clears the corrected source. Root producer, child identity and
committed-unit implementation follow. The immutable root plan passes focused771
(22 cases and PMD), and negative772 catches wrong-ancestor policy collapse; independent
review clears the DTO. Atomic root-state preparation now passes775 (40 cases and PMD);
negative774 exposes missing label, duplicate walk and removed-root resurrection. The
recorded root producer, child acceptance and committed-unit completion are next.
Broader snapshot776 passes5189 cases/906 suites with zero failures/errors,11 inherited
skips and affected PMD. All six selected test tasks execute. Hosted CI34721364763 passes
the same c6556fa02 snapshot. The declared-kind prerequisite passes focused781 (120 cases,
PMD, documented unchanged-input reuse) and negative780 catches allfour intended defects:
one app-agent-api enum serves OperationPolicy and the durable row, including memory/note;
production catalog activation and survival admission remain coupled to recorded owners.
The subsequent [generation capture](evidence/C2/generation-capture.md)
passes focused768 (55 cases, including the separately committed root-plan scope) and
negative769 catches three intended service-absence failures. Independent review clears
the strict observation and replacement-availability fix. Hosted preparation run34719066293
is red on parser startup before the live-child witness. The [diagnostic correction](evidence/C2/parser-hosted-766.md)
preserves the future failure and hosted XML without relaxing assertions; focused770 passes4 cases.
Hosted CI34720523685 passes04716d41e, including Windows-native tests; it precedes the
root-state snapshot. Snapshot776/CI34721364763 now provide the broader snapshot check;
recorded producer validation remains required. No Engine stack is running.

Prior item is [the shared offline owner](evidence/C2/offline-owner-proof.md):
manual and automatic procedures share bounded admission/lifetime ownership;
acknowledged per-pass progress feeds the recorded handler through actual cleanup.
Final focused742 passes129 cases and affected PMD after mode-failure metadata and
opt-in test-registry corrections. Full737 was red on the broad test-fixture close
change, now corrected. Full744 passes10,047 cases/1,636 suites with zero failures/errors,
35 skips and passing PMD at82e0e185d (18 test tasks execute,20 reuse unchanged inputs).
Hosted CI34715474339 passes that same revision. Independent final reread found no
remaining source defect. Named stress745 passes2 cases. [Live754](evidence/C2/offline-owner-live.md)
passes actual-model/API and installed Gradle distribution proof in production-token
mode: one selected/acknowledged unit, two successful model calls, and terminal row
after mode cleanup. Instrument752 passes106 cases; negative753 fails six intended
output-collision cases and independent reread clears the fixes. The owned stack is
stopped. This is one C2-2 item, not stage completion or packaged installer acceptance.

Prior integrated verification is [checkpoint724](evidence/C2/vdu-checkpoint-724.md):
55b8aeb15 code passes full build/PMD with10,034 cases/1,643 suites, zero failures/errors
and35 skips. Eight test tasks execute; the rest reuse unchanged inputs. Hosted
CI34712074865 passes the same code and new real Engine fixture. The subsequent offline-owner code is governed by
[the result/shutdown cut](evidence/C2/offline-owner-cut.md) and its proof record above.

Prior integrated verification is [checkpoint694](evidence/C2/vdu-checkpoint-694.md)
at41a74500b: full build/PMD9,993 cases/1,637 suites, zero failures/errors,35 skips;
11 test tasks execute and27 reuse unchanged results. Hosted CI34708670410 is green
at e872a36d4 (subsequent skill projection corrections only). Named stress696 passes
two cases with documented unchanged-input reuse after broad695's empty-module
selection failure. Conditional switch-buffer removal is now implemented with
[schema16 and replay-race proof](evidence/C2/switch-buffer-version.md):75 focused cases
pass, the original whole-table clear fails3 race cases, and key-only removal fails both
replacement cases plus reinsertion. Restored691 reuses the identical689 test cache.
[VDU client control errors](evidence/C2/vdu-control-errors.md) now propagate unchanged;
693 executes70 focused cases, with8 old-source negative failures. Batch/coordinator
propagation remains open. The plan settles shared offline procedure ownership; implementation follows
the remaining VDU generation verification. No dev stack or Gradle remains running.

The VDU generation correction now has [focused and negative proof](evidence/C2/vdu-generation-proof.md):
713 executes133 cases across four modules with zero failures/errors/skips and passing PMD.
It includes strict read-only existing-target checks, typed absent-runtime refusal and
incomplete selected-recovery retention found by independent review. New VDU buffering
is retired; legacy rows wait for an eligible serving runtime. Independent correction
review finds no remaining production blocker. The subsequent
[real Engine proof](evidence/C2/engine-vdu-migration-replay.md) passes719 after
independent review strengthened source replacement assertions; the force-eligible
production mutation720 fails exact row retention, and restored721 reuses719.
Hosted CI34711067677 passes b532a56ee and stress714 reuses unchanged passing inputs.
Full724 and hosted CI34712074865 now cover the new Engine fixture and generation
correction; proceed with the shared offline owner and its separate producer proofs.

Prior coherent code checkpoint: 758aeb2eb, pushed. Full656 passes9,917 cases/1,626
suites, zero failures/errors and35 skips; stress659 passes allthree selected cases
with documented unchanged-result reuse. Hosted CI34700660160 has clean first-attempt
orphan containment (deliberate parser reuse, native descendant, real Engine kill/reap),
plus unskipped queue/snapshot and producer tests. Its only red job is a malformed byte
in a design link;0bdf584bc repairs it and successor Public claims passes.
See [checkpoint656](evidence/C2/projection-checkpoint-656.md) for exact revisions,
commands, task reuse, artifacts and remaining limits.

Offline prerequisites now include guard cleanup (3b425cebb) and strict backlog
reads. [Backlog proof](evidence/C2/backlog-reads.md) records 46 focused cases, four
additional actual Engine error-translation cases, negative regressions and independent
review. These establish control-read failures, not durable write acknowledgements.

Direct VDU covering commits now have [focused proof](evidence/C2/vdu-commits.md):
162 cases with explicit task reuse, negative regressions and recovery corrections.
The prior guard checkpoint3b425cebb is fully green in hosted CI34702144423;
this does not stand in for hosted verification of these newer changes.

Failed buffered VDU replay now has [retention proof](evidence/C2/vdu-replay-retention.md):
29 cases, five intended negative failures and a fresh restored-source execution.

Shared live/replay VDU mutation rules now have [writer proof](evidence/C2/vdu-writer.md):
78 focused cases, 13 intended negative failures, committed real-Lucene parity and
invalid payload retention. Hosted CI34704917379 (e225cbf4b) and34704211058 (e784a97f3)
are green; these prior runs do not establish hosted proof for the newer writer.

Next: implement [recorded ingestion preparation](evidence/C2/recorded-root-preparation.md),
committed-unit completion, direct HTTP/keyed work and every C2/D1/D2/E/F acceptance item.
The plan records the bounded offline effect
contract; do not replace it with an invented unbounded global semantic-drain owner.

C2-2 now has the queue claim prerequisite, shared durable attempt runner,
process-root/dispatch/undo wiring, synchronous completion-store failure propagation,
and scheduled agent acceptance through actual durable run outcome. The non-dispatched
sealed-mutation fixture is proved (aad9d1845). Runtime activation/deactivation records
follow actual owner cleanup (5f2285f63); install/repair completion follows in this
checkpoint, including live-owner reaper correction3be553d89.615 passes123 install/
runtime tests, combined618 passes163 producer/admission tests, and621 adds actual
pre-write cancellation, cleanup failure and named stress coverage. The plan preserves
exact evidence and unchanged-input reuse; these are not installed/live-download proofs.

C1 asynchronous dispatch quota correction now retains exact Engine work through actual
handler completion, including cancellation/error and synchronous throws.625 passes116
tests/13 suites with documented unchanged-input reuse; negative623 reproduces the
early release over real HTTP. Independent source review and surface/guard gates pass.
Hosted inclusion remains. Install/repair checkpointd86d5d40c is pushed.

Pack import completion is implemented with the same actual owner/cleanup lifetime;
631 passes84 tests/8 suites plus PMD, with negative630 and independent review.
The hosted schema integration fixture missed the new runner dependency;3e6569a0d
fixes it and633 passes integration compilation/PMD. Fresh hosted green is owed;
[hosted evidence](evidence/C2/hosted-ci.md) records the red runs and exact cause.

C2-2 remains OPEN for recorded ingestion/committed-unit completion, offline and
other async owners, direct HTTP acceptance (the existing install/runtime controllers
still bypass catalog dispatch), and installed acceptance-before-effect fault proof.
C2-3 owns explicit client keys on those paths. Continue [the plan](evidence/C2/C2-2-plan.md)
and every remaining C2/D1/D2/E/F item. Fresh fetchSeptember12 at13:00UTC found no
origin/main work outside this branch. Merge placement remains F/PR1.

Downstream project-memory v4 requirements955-1..955-6 are adopted in section0 and
C1/C2/D1/D2 checklists (8d5c424f7). The first external row consumer is recorded in
[its contract](evidence/C2/project-memory-consumer.md), including prepared payloads,
completion projection, non-file reindex journal entries and durable delete receipts.
The C1 MCP quota correction (fc67f6ed9) has focused negative599/positive601 proof (66 tests,
PMD); [evidence](evidence/C1/mcp-session-quota.md) distinguishes this new correction
from the older C1 hosted/live pass. Included in combined618; hosted inclusion remains.

C2-1's independent store, lifetime/recovery fence and jobs15/updater compatibility
are implemented. C2-11's day-one installed PROCESSING replay plus fresh retry
passes with one document and clean owned shutdown. Full integrated565 passes;
[batch1 reconciliation](evidence/C2/batch1.md) records exact commands, retained XML,
unchanged-input reuse and the remaining acceptance/checkpoint producer boundary.
Commits396d16e22 and6a4059352 are pushed. Continue C2-2's shared attempt runner,
then all remaining C2/D1/D2/E/F items; merge placement stays F/PR1. No owner input
is pending. Preserve hourly WIP commits and push immediately after every commit.

## Autonomous work resumed (2026-09-12)

The user authorized integrating main and continuing the entire lane. Merge9762cf593
incorporates #721/#722; the hook import correction35d03f7c4 is fully green in hosted
CI34683617524. Full478 passes with documented unchanged-input reuse. Fresh offline483
completes full enrichment of469 files under9720 successful continuous searches, zero
errors. The stack is stopped clean:none, ports closed and evidence retained. C1 is
complete under [acceptance reconciliation](evidence/C1/acceptance-reconciliation.md).

C2 design1501ff0c4 settles canonical key conflicts, keyed undo, expiry fencing, SSE
snapshot/replay ordering, and one attempt runner with committed settings receipts.
D1 designa480ac6f6 binds each query to one leased runtime/encoder/service view, fixes
previous-generation retirement and puts late model-manifest work in its actual batch.
C2 entry grounding additionally corrects the pre-fork construction citation and requires
interrupted-quarantine preservation to retain the expiry fence. The acceptance runner is the current C2-2 implementation; keyed outcomes and D1
behavior remain ahead. Batch1 store and day-one replay proofs are complete as
recorded above. Continue every remaining item in C2/D1/D2/E/F. No owner decisions are pending or deferred.

The lane worktree is held through review date2026-10-12 for active work and mandatory
raw evidence. Retain artifacts through lane acceptance plus30 days; export before any
worktree release because the lifecycle tool treats evaluation directories as disposable.
Main's worktree stays untouched. Fetch at this boundary finds origin/main already
contained. Push after each commit; commit WIP at least hourly; at most three worker
follow-ups. Stage closure does not end authorized work. Merge placement remains F/PR1.
See [resumption evidence](evidence/C1/resume-2026-09-12.md) for commands and proof limits.

## Historical user-requested pause (2026-09-09)

Current implementation candidate8f8c7d775: full455 and Python456 pass, hosted34392044686
is fully green; fresh fairness459, standard primary460 and aggregate467/468 pass.
C1 remains OPEN. The user requested stopping before usage exhaustion. Offline enrichment470
was interrupted at1768s: dense/SPLADE/chunk100%, NER404/469. It is not a full-pipeline pass.
The owned evaluation and registered stack are stopped, ports closed, data retained; all
reviewers are complete. No C2 implementation has started and no owner input is pending.
The session-closeout sweep reaped nothing and reported only the shared ownerless otlp-sink
daemon (PID14468), which is intentionally retained across sessions.
See [acceptance reconciliation](evidence/C1/acceptance-reconciliation.md) and
[final live evidence](evidence/C1/final-live.md) for exact commands, revision and receipts.

Resume with a fresh chat-offline full pipeline under continuous search, then close C1 only
after reconciling every acceptance item. The completed full455 reuses unchanged Java inputs
(2 executed/367 up-to-date); do not claim every test ran anew. Preserve earlier failed runs.
Next re-ground C2 citations and resolve the bounded idempotency-history design before code;
[entry investigation](evidence/C1/c2-entry-investigation.md) records the source findings and
open design decisions. C2/D1/D2/E/F remain mandatory; merge placement stays F/PR1 (#718).
Existing autonomous decision and merge authorization persists when resumed. Push every
commit, commit WIP at least hourly, and do not exceed three worker follow-ups.

The older checkpoint narrative below is historical; this pause summary and current acceptance
table supersede its already-completed next-action instructions.


## Current C1 verification checkpoint (2026-09-09 root-scan race)

**Latest steering: the owner's independent review is mandatory and ordered.** Read
[the complete review](evidence/C1/independent-review-2026-09-09.md) and
[the correction ledger](evidence/C1/independent-review-fixes.md) before continuing below.
The production observer and interrupted-handoff blockers reproduce and pass restored251.
RuntimeSession close and its enclosing reload/close ownership are implemented and independently
reviewed: bounded waits retain live resources; reload and close share a lock; failed server close
retains its index lock and completion latch for retry. OCR now has one reusable component pool,
bounded cleanup, retained task/child/temp ownership and structured-text preservation; focused296
passes61 cases. The final review exposed uncontained Tesseract descendants when the parser JVM
is forcibly recycled. Windows parser Job containment now corrects that boundary; forced recycling
and Engine-crash tests witness live native descendants and prove their exit. Engine client
registration close is now aggregated; adverse310, restored311 and build312 establish cleanup of
all seven base/transport names despite failures. Late failures are now ERROR logged after cleanup,
with Error reaching the actual worker uncaught-handler path;14 client/context cases and build317
pass, with separate logging/rethrow refutations. Sandbox reader refusal, schema2 correlation and
retained retiring slots now pass41 selected cases323 and build324, with three adverse guards322.
See [sandbox response ownership](evidence/C1/sandbox-response-ownership.md).
Health-monitor capacity recovery now passes53 selected cases337 and full build338, with
both timer-contention/manual-refusal regressions failing against the old code330. Startup
rolls back before authority publication; manual capacity reaches HTTP429 with Retry-After.
See [health capacity](evidence/C1/health-capacity.md). CORS preflight exemption and Retry-After
visibility pass39 selected cases340/342 and full build343; both adverse341 checks fail as expected.
See [CORS admission](evidence/C1/cors-admission.md). The ordered governance/test debts and Windows rename correction are complete locally;
final C1 acceptance remains open. The admission oracle now
requires captured boolean retrySafe true:63 self-tests347, three adverse346 guards and build348
pass. The earlier raw aggregate capture fails this stronger rule349; fresh final live proof is
required. See [refusal proof](evidence/C1/admission-refusal-proof.md). Governance sweep now passes
the complete17-seam efficacy gate366 and reviewed build370: routing registration, executor consult,
65/80 census, source-backed schema version, two property gaps and retired Worker baseline residue.
See [governance sweep](evidence/C1/governance-sweep.md). The cancellation/shutdown and queued-root
test debts now pass24 selected cases374 and full build375, with four adverse373 controls.
The obsolete B cancellation limitation is removed. See [cancellation sweep](evidence/C1/cancellation-sweep.md).
Windows rename now passes actual held-reader release/exhaustion, permanent-error and adverse
deadline/filter tests381, supervisor conformance34/34 and build382. See
[Windows publication](evidence/C1/windows-state-rename.md). Full385 at clean pushed0b4b13ad0
passes; final review then found MCP retrySafe omitted from both refusal paths. The root is
closing that two-call defect with real transport regressions. Hosted34383091150 passes
Windows-native/system but axe browser installation fails an upstream APT hash mismatch on
both attempts. Current evidence and exact remaining checks are in
[final candidate](evidence/C1/final-candidate.md). Broader400 then exposes the parser fixture's
one-second cold-start assumption; [the test-only correction](evidence/C1/parser-fixture-startup.md)
passes28 sandbox cases and retains a failing lost-slot counterfactual. Final stress is still required.
CI34386342721 repeats the Chrome APT failure; [the bounded source preparation](evidence/C1/playwright-apt.md)
passes nine local filesystem cases, preserving all integrity and measurement checks. Push and
verify the current hosted run, then complete fresh stress and live acceptance before C2.
Full418 now passes at clean5c2f0ef3b, including all five recovery cases. Hosted34387563666
passes12/13 jobs including axe; Public claims finds a newly published smol-toml advisory,
[corrected in the frontend development lockfile](evidence/C1/dependency-advisory.md).
Hosted34390502944 is fully green at7ff787cf3; it precedes the load-client correction.
Primary423 proves the initial scan and all469 files, but has four unclassified load failures.
Slow receipts confirm HTTP200 responses beyond its30s client timeout, within the existing
60s CPU rerank allowance; the mismatch is proven, but the four exact error types were not logged.
The load/probe clients now share the normal jseval retriever's existing90s allowance. Retain failures,
and obtain fresh live/hosted proof. See final-candidate.md for exact artifacts and remaining arms.
Hosted34377820922 passes all13 jobs at4136d57e1, including both advisories and Windows-native;
inspected system XML has88 cases, zero failures/errors,42 skips and no failed-retry entries.
That checkpoint precedes the governance sweep. See [OCR correction](evidence/C1/ocr-component-close.md) and
[parser containment](evidence/C1/parser-containment.md).
C2 cannot start until that review is closed. One implementer per worktree; read-only reviewers.
Each subsequent item commit must be build-green and pushed immediately; earlier red WIP
authorization is historical. Raw evidence hashes and the last full-run summary are now committed
artifacts. Full299 at clean pushed `bad1a7622` passes in8m52s with zero represented XML
failures/errors, including all five supervised recovery cases. Its reports are preserved in
`tmp/c1-ocr-integrated-results-299/`; the last-full-run summary records the clean tested revision.
This supersedes271's fanout failure, fixed by the parent/child ownership regression. Full
stress/current hosted green and final live standard-model proof remain required after the ordered fixes.

Hosted34356123502 at6bf931408 fails Windows supervisor-state rename and docs headings. Its system
job passes only after writer recovery's third attempt; earlier failures include EPERM and a close
deadline hang. Final-guard fatal boot/ingest witnesses are now captured, with exact100 acceptance
and incarnation2 recovery. The docs headings are corrected; bounded production rename retry remains
required. These findings are in [hosted-ci.md](evidence/C1/hosted-ci.md).
Later hosted34363379524 at44039df47 passes every job; inspected system XML has88 cases, zero
failures,42 skips and no failed retry entries. That checkpoint includes runtime close and the
publisher regression, before OCR. The reproduced Windows rename defect still requires its fix.

Continue autonomously in `F:/justsearch-public/.claude/worktrees/lane-F-A`, branch
`worktree-lane-F-A`. A/B are complete at their recorded tiers; C1 is open. C2/D1/D2/E/F remain
required. F/PR1 remains the merge placement (draft718). All lane decisions and merges are
authorized. Push every commit, commit WIP at least hourly, and cap worker follow-ups at three.
Root owns current corrections. This checkpoint is not a pause or stage pass.

Pushed through f4ab8ceba: native GPU waiter cancellation, broken-child Engine workflow, pacing
observer corrections, synchronous indexing-stream opening retry, GPU broadcast on late index
connection, jseval watcher readiness floor, and owned data directory at JVM logging startup.
Integrated203 passed its configured tiers. Full integrated221 passed 9725 represented unit
cases (25 skips), but its isolated system tier failed lock-boot. All XML remains in
`tmp/c1-integrated-results-221/manifest.json`. Full jseval222 passed 3638 tests (16 skips,
82 existing warnings). The integration failure remains recorded, not hidden by targeted reruns.

Standard-active primary run229 at f4ab8ceba indexed 469 documents while serving 13500 continuous
searches with zero errors. However, it exposed the initial root scan failing WORK_FINISHED before
the periodic rescan masked the loss. Hosted34350223349 proves this exact cause in all three
IndexingLedgerCoherenceTest attempts. Its preserved logs are in the uploaded
integration-test-results artifact and locally `tmp/c1-hosted-artifacts-233/`. Root fixed ownership
before queue submission using the existing owned asynchronous task; all four root-walk queues
pass the retained context. Adverse232 fails on original code. Focused234 and expanded235 pass,
including queued cancellation/rejection, shutdown/actual-exit ownership, isolated ledger and all
five installed recovery cases. See [root scan evidence](evidence/C1/queued-root-ownership.md).

Integrated221's lock-boot failure came from indefinite fault reinjection exhausting successor
attempts after tragic Lucene writes. The harness now stops injection only after a counted exit
at or after the binding that accepted all 100 documents, waits for JUnit's release acknowledgement,
then proves recovery within the unchanged 180-second search bound. Healthy runs stay attacked.
Review found and root fixed stale-lastExit and partial-acceptance holes; four Node tests and
counterfactual237 prove those guards. Installed235 exercised fatal recovery in both lock arms.
Final guarded installed239 is running; its result and XML must be preserved. See
[lock phase evidence](evidence/C1/hostile-lock-phase.md) and design §16's explicit decision.

Hosted34350223349 also failed one migration recovery attempt with a supervisor-state EPERM rename
and timeout waiting for rollback restart. This is independently under diagnosis; local235's
migration pass does not explain it. Overall hosted CI success is not a pass of its advisory
system-tests job. The source failure logs and artifact are retained under233.

Owned standard run d59d7ea8-5d5a-430b-93d7-70f69f4dd53c is stopped with closed ports. Its owned
application logs were copied to `tmp/c1-standard-primary-evidence-231/logs/`; this confirms the
dev log-directory fix. No ordinary dev stack is active;239 owns the only Gradle/isolated run.
Next: finish reviews and per-item commits/pushes, resolve the supervisor-state publication defect,
repeat standard-active primary indexing with a successful initial scan, then chat-offline full
enrichment under continuous search, plus standard admission on the final candidate. Reconcile
integrated/hosted evidence before opening C2. The separate native session close/quiescence defect
remains mandatory D1 work; C1 waiter cancellation cannot establish safe session close.

## Current C1 batch 4 checkpoint (2026-09-09 11:04 UTC)

Worktree `F:/justsearch-public/.claude/worktrees/lane-F-A`, branch `worktree-lane-F-A`.
A/B are complete at their recorded proof tier. C1 remains in progress; C2/D/E/F remain required.
Lane decisions and autonomous merges remain authorized. Merge placement remains F/PR1 (draft718).
The original dirty tree was split into ten per-item WIP commits and pushed; later slices follow
that cadence. Push every commit immediately, checkpoint WIP at least hourly, and cap worker
follow-ups at three before taking the diff at root. This is continuing work, not a pause.

Latest pushed revision db368e353 follows65f65d36c and239ac1a6c (launcher configuration funnel),
c57525718 (frontend lint correction),4a57950ae (OCR fixture), SSE bound a46b4544c, direct/three-way fanout proof 6c89796f2,
async guard 66d85feb1, raw/census guards 749ae81dc, and Lucene generation/NRT correction 3f6fdeae5.
Earlier per-source search e9ed79694 and OCR d0d09bc72 are also complete at focused proof tier.
All these slices have mutation evidence under evidence/C1. Fully executed run138 passes all five
focused runtime lifetime/generation tests (93/93 tasks executed); independent shutdown review found
no code defects. Restored run133 passes all 388 observability tests and whole-program dead-code;
guards135 and their source/method-reference/arity/telemetry/unused-class mutations pass as recorded.
WholeProgramDeadCodeTest's committed store did not grow or change.

Integrated build140 found one redundant qualifier in the OCR chaos fixture. Fix 4a57950ae is pushed;
restored build143 passes332 tasks, including configured integration and PMD checks. Full stress147 and integrated162 finished: only SystemAccessFunnelTest (fixed/pushed239ac1a6c)
and the API thin-composer field ceiling failed in sequence. Root moved borrowed search state into
CoreApiAssembly.Result and removed a constructor-only executor field; the30field ceiling stays.
Restored integrated164 passes build/test/installDist with stress enabled:9718unit tests,
zero failures/errors,25existing skips; configured integration36tests/zero failures/10skips.
Both prior full XML snapshots are preserved; see the current ledger for cache/execution binding. Frontend run142
passes483files/6474tests and typecheck141 passes; console teardown diagnostics match the pre-C1 log exactly and are documented as existing fixture noise.
Lint148 found a dead initial assignment in admissionFetch; fix c57525718 is pushed and lint154,
typecheck155 and seven focused tests156 pass. SchemaMismatchStatusContractTest executed in
build140: one test passed (XML09:59:58UTC), now preserved in its own evidence directory.
UI gates149 pass27/27, regen150 passes, engine-port144 and register-guard-resolution145 pass,
store-recoverability146 passes, docs152/153 pass, logic-seams136 and JVM-option pin137 pass.

Remaining C1:
full public preflight/local checks, final canonical/residue sweep, and installed standard-model
aggregate admission/fairness plus continuous search/indexing proof on this bounded-executor candidate.
Batch3 live proof used compact and predates bounded executors; it cannot close C1. The parent SPLADE
RMW correction still needs the fresh live run. No final C1 acceptance is claimed yet. Required hosted/platform checks are running via draft718;
CI wiring or intermediate successes are not final hosted proof. C2 owns its explicit operation-row
urgency record and the design's awaitingProducer clauses remain labelled; nothing is owner-gated.

The active session identity is01a082dc-dfd6-7d60-be2e-a8d088229a67. No dev stack is active.
Aggregate166 and fairness169 pass with the standard model; continuous hybrid search/indexing170
FAILED after native GPU calls stalled and15 foreground callers waited uninterruptibly behind one
native session. Admission correctly retained actual work; cancelled waiters could not leave.
Interruptible waiter acquisition3b6a9914e passes focused and adverse proof; active native leases remain unchanged. Integrated190 is still required.
Two unregistered compact-model llama processes abandoned on September7 were also found and retired
only after process identity, absent parents and no connected clients were verified. The standard
stack was stopped through its owner. Fresh pacing must run without those co-resident processes;
the passed admission captures prove wire/count ordering, not uncontaminated performance.
Hosted34339800148's three failures were traced and corrected: strict test-source compiler errors
(09c329b46/9629f240b), the generator's vulnerable js-yaml dependency (2ea41b2d4), and the declared
aggregate-cap configuration growth (1c7fcfff9). Strict focused181 and public-claims subjects180 pass
locally; final hosted proof is still required. Latest pushed revision3ccf3749b (after native waiter fix3b6a9914e and Engine confinement proof d20ebf450).
C1-14 exact Tika-import mutation185 fails for the intended forbidden dependency; restored186
passes, including the same-session real Engine index/search/ledger proof for five routed families
and the three decoder formats. These are committed in d20ebf450. ADR0048 reconciliation3ccf3749b
fixes the next hosted Public claims failure. Hosted system integration still fails the live
indexing-to-action-ledger projection; root-directed triage is active. Integrated190 also reported
a format-matrix initialization timeout under investigation. Native shutdown quiescence is recorded
as required D1 implementation, not established by the waiter fix or permissive old CPU stress test. C2 starts only after remaining C1 acceptance is reconciled.
See evidence/C1/integrated-candidate.md for the advancing command/result ledger. Older notes below
are historical when they disagree with this current state.


Two handoffs live in this file. The first (2026-09-07) is from the design orchestrator to the
first implementation orchestrator and is kept as written. The second, **"Implementation
orchestrator handoff (2026-09-08)"** at the end, is from that orchestrator to its successor and
is the one to read first if you are taking the lane over mid-stage-B: it records the state of
every branch and PR, the in-flight work, the review findings that are decided but not yet fixed,
and the working practices that were never written down anywhere else.

Written 2026-09-07 by the agent that orchestrated the design through seven reviews and the
lock. The design is the contract; this file is what that agent knows and the contract does
not say. Read `design.md` sections 0, 15, 16 and 17 first, then this file, then
`verified-facts.md` when a stage touches the code it cites.

## What exists, and where

- `design.md`: the locked contract (status line, section 0 lock paragraph, 15 last bullet).
- `verified-facts.md`: code facts with `file:line` citations, verified at `b96cd999`.
- `docs/tempdocs/936-lane-f-engine-jvm-design.md`: a pointer, kept so cross-references resolve.
- All three are **untracked and uncommitted** in the `lane-F` worktree on branch
  `worktree-lane-F`, which has zero commits of its own and can fast-forward to `main`.
- Nothing has been implemented. No PR exists. No baseline exists.

## Rules the owner set, still binding

1. **Nothing merges without an explicit, per-PR go-ahead** from the owner. PR 0 and PR 1 each
   need their own. Between stages, the owner's "continue" authorises the next stage and nothing
   else (17.6).
2. **The design is locked.** A change inside a decided line (a mechanism detail, a citation)
   proceeds with a dated line in section 0 naming the stage that found the error. A change to a
   decision (15), a gate row (16) or the stage table (17.3) waits for the owner's word before
   the stage continues (17.6). Do not re-litigate 15 or 17.7; every value there is the owner's.
3. **All other development is halted** while the branch is open. 17.8 names what re-cuts the
   sequencing if that stops being true.
4. **Starting values are not design facts.** 17.7's numeric starting values (p95 plus ten
   percent, two hours' soak, thirty days' retention) are instantiated from the PR 0 baseline
   and confirmed by the gate run. Do not invent a number where 17.7 says the baseline supplies
   it, and add no allowed-difference class after a diff is seen.

## The order of work

1. **Bring the design files onto a branch.** From inside the `lane-F` worktree,
   `git merge --ff-only main` (the branch has no commits; the untracked files survive), then
   `git add` the three files explicitly and commit. Add one line for `docs/design/` to
   `docs/llms.txt`; no gate covers the directory today and nothing indexes it.
2. **PR 0 (17.2)** on that branch: launch flags at both spawn sites, the workflow fixture and
   its mode-agnostic runner, the design files riding along. Measure the flag change before and
   after on the 917 Derisk 1 procedure. Ordinary review, ordinary go-ahead.
3. **Baseline capture on `main` after PR 0 merges**, into
   `docs/design/lane-f-engine-jvm/evidence/baseline/`: jseval search-quality baselines, the
   fixture's output, the brief v2 performance list, request-time encoder latencies, the Worker
   restart rate from `worker.log`. Stage A does not begin until this exists.
4. **PR 1** in a **fresh worktree cut from post-PR 0 `main`**, not from `lane-F`. Stages A to F
   per 17.3, one agent at a time in that worktree (never-share-worktree), sessions handing off
   through `stages/<letter>.md` and `evidence/<letter>/`. Commits per checklist item.
   Independent review of each stage's commit range by an agent other than the implementer,
   recorded in the managed review record, findings fixed before the go-ahead is requested.
5. **Stage E runs, it does not build.** `/jseval` and `/dev-stack` under a campaign lease.
6. **Stage F** ends with the report-back (19) per `00-program-overview.md`.

## Things the contract does not say, or says quietly

- **`main` has moved 12 commits past the verified base** (`b96cd999` to `59ae966d` at the
  time of writing; 316 files, mostly 941 sandbox rounds and the 0.3.0 release). Seven files the
  design cites changed: `IndexingService.java`, `DocumentService.java`,
  `GplJobCoordinator.java`, `IndexingLoop.java`, `BackfillScheduler.java`, `indexing.proto`,
  `SqliteJobQueue.java`. Spot-checked 2026-09-07: the cited facts hold with shifted line
  numbers (the `supplyAsync` site in `DocumentService` moved from 74 to 85; the
  `commit` then `drainPending()` precedent in `IndexingLoop` sits at 723 rather than 705 to
  722). 17.6 already requires re-verification at each stage start; these seven are where to
  look first. `contracts/wire/knowledge.proto` and `status.proto` gained one line each.
- **The design is not a tempdoc.** No size cap, no append-only convention, and no tempdoc
  gate runs on it. `docs-validate` does cover `docs/design/` (it walks `docs/**` and exempts
  only tempdocs; corrected at PR 0), but only for front matter, headings and links. The prose
  sweep at stage F is what keeps the content honest; nothing automated does.
- **Two registers must be loaded before the work and updated before the lane closes**:
  `/search-quality` and `/inference-runtime` (CLAUDE.md, Skills). Stage D2's request-time
  paths and stage E's measurements touch both.
- **Runtime files.** `supervisor.v1.json` next to the port manifest is a new `<dataDir>/runtime/`
  file; `check-runtime-manifest-closure` must know it, and the design asks that the check's
  `SKIP_PATHS` be narrowed so the supervisor file's writers are checked (stage B).
  `dev-runner.cjs` and `lib.rs` are skipped today (`:73`, `:80`).
- **Stage A is the risk.** The unplug deletes the wire, the MMF bus, `WorkerSpawner`,
  `SupervisionPolicy`, `WorkerProcessManager` and its 20 tests, and supersedes ADR-0001 and
  ADR-0002 in one change. 17.8's stop rule applies: if stage A's checklist outgrows the 917
  consumer audit, stop and re-read 3 and 6 before more code. Time-to-complete is an
  architecture signal (`agent-lessons.md`).
- **The 17.4 code inventory is the starting point for D1**: `IndexGenerationManager` has
  generational directories and an atomic `state.json` swap; the cutover restarts the Worker
  today (`KnowledgeServerMigrationOps.java:258-266` at the base); `max_failed_jobs` defaults
  to -1 and the design flips it to 0; there is no journal, no live activation, no reader
  pinning. Do not rebuild what exists.
- **The supervisor budget is re-cut, not ported** (7.1, lock). The conformance harness's fake
  engine must exit under each of the three classes, and the hang poll interval and count are
  placeholders until stage E sets them with the collector.
- **Same-dir double open is a documented handle leak** (`KnowledgeServer.java:645-651`,
  `swapRuntime` at 1236 at the base). The beside mode composes a second generation directory,
  never a second reader over the same one.
- **The generic MCP-client recovery harness** (7.6, lock) is new work with no code today; it
  belongs to C2 or D2, whichever the implementer's checklist places it in, and the 16
  recovery row exercises it.
- **Windows discipline.** Edit/Write or node UTF-8 scripts for multi-file edits, never
  PowerShell `Get-/Set-Content`; check the diff for stray non-ASCII and NUL bytes
  (`agent-lessons.md`, `utf8-bulk-edits`). `git merge` to catch up a pushed branch, never
  rebase. A piped command reports the pipe's exit code.
- **Delegation.** The orchestrator writes briefs and judges evidence; stage implementation can
  be delegated in bounded, self-verifying chunks with an explicit `model` on every spawn
  (sonnet floor, opus where quality is in doubt). Reviewer is never the implementer.
  Fire-and-forget dev-stack delegation is not allowed.
- **The main checkout holds other agents' untracked work** and there are roughly forty
  registered worktrees, many from Codex sessions. Leave them alone; the halt condition (17.8)
  is about new merges to `main`, not about cleaning those up.

## What the design orchestrator would watch for

- A stage that passes its checkpoint proof but whose "branch state after" is redder than the
  table allows. That is a defect of the stage, not a note for the next one.
- A green that depends on an environment precondition (`green-masked-destructive`): the
  in-place reconfigure path is forced by capping free device memory, the stuck-component row
  by a held lock. Test the adverse precondition, not only the happy one.
- Line-number citations trusted across a moved base. The seven files above are the known
  cases; grep the symbol, not the line.
- Any sentence that starts "the owner probably meant". 17.7 records what the owner meant.

---

## Implementation orchestrator handoff (2026-09-08)

Written by the agent that orchestrated PR 0, PR 0b and stages A and B1 to B7 (session
`9b397ac4-f652-4d85-ab86-880c86ef4c16`, branch `worktree-lane-F-A`), for the agent taking the
orchestration over mid-stage-B. Everything below is either not written anywhere else or is
scattered across commit bodies; `design.md` section 0 (dated paragraphs), `stages/A.md`,
`stages/B.md` and the `evidence/` directories are the durable record and win on conflict.

## 1. Where every branch and PR stands

| worktree (`.claude/worktrees/`) | branch | contents | state |
|---|---|---|---|
| `lane-F` | `worktree-lane-F` | PR 0: Head launch flags, workflow fixture, baseline, design files | **PR #708**, base `main`, CLEAN, green; review record refreshed; awaiting the owner's merge go-ahead |
| `lane-F-0b` | `worktree-lane-F-0b` | PR 0b: determinism pins (chunk tie-break, exhaustive kNN switch, `sampling` request override, fixture noise-pair gate, retaken split-side baseline) | **PR #717**, base `worktree-lane-F`, CLEAN, green; **retarget the base to `main` after #708 merges** (`gh pr edit 717 --base main`), then refresh the review record; awaiting go-ahead |
| `lane-F-A` | `worktree-lane-F-A` | PR 1: stages A to F, one branch | no PR yet (opened at stage F per 17.6); stage A checkpoint complete; stage B in progress; `origin/main` last merged at the A/B boundary |

Both PR bodies live at `tmp/pr0-body.md` (lane-F) and `tmp/pr0b-body.md` (lane-F-0b), the
managed review records at `tmp/pr0-review-record.md` and `tmp/pr0b-review-record.md`; `tmp/` is
untracked, so regenerate from those files if they are gone. The squash-message gate
(`node scripts/ci/preview-squash-message.mjs <N>`) rejects a body over 2000 characters or 32
lines and any banner or session URL; the accepted form ends with the single line
`Session-Id: <session uuid>`. `node scripts/ci/pr-review-record.mjs check <N>` must pass on the
PR head before enqueue; every push to a PR branch invalidates it, so refresh last.

**Merges are the owner's per-PR call and were never given.** Everything else in the lane was
delegated to the orchestrator on 2026-09-07 (design section 0, "Decision authority"): no
"owner item" category exists any more; decide, record the decision dated in section 0 with its
reasoning, and continue. The one other standing restriction is the owner's: **no benchmark,
eval, soak or capture that takes more than an hour**; stage E's design must be cut to fit that
(three captures per side under the pins already fits).

## 2. Stage B: what is landed, in flight, and decided-but-unfixed

**Landed and pushed** (`e692b86ef` = B1 to B6 plus the sweep; `c7e8e0302` = B7). Full suite at
B6: 9331 tests, 0 failures (XML counts, `cleanTest --no-build-cache`); kernel 30 gates, 0 fail.

**B8 to B10 landed (same day, one implementer, 111 minutes), pushed with this handoff:**
`f97511113` B8 (the dev-runner supervisor, `scripts/dev/lib/engine-supervisor.cjs`),
`664da18d4` B9 (`scripts/dev/run-dev-runner-tests.mjs`, nine files discovered, CI step in
`windows-native-tests` because `dev-runner.cjs` is Windows-first; `public-claims` runs the
harness `--self-test`), `63826830e` B10 (`modules/shell/src-tauri/src/supervisor.rs`, the
`[[bin]] supervisor-conformance` target, the `shell-rust-tests` step, the `supervisor-state`
FE consumer), `c05216f6a` and `1242fd288` (critical-analysis fixes: `force_kill` reaped the
handle so `poll_exit` never fired again; a 50 ms resurrection window in the shutdown check;
the same start/death race in the dev-runner), `b5e3a5ce2` (B.md section 0.1 corrections,
`status: B1-B10 landed`). Implementer's verification: harness self-test 31/31, dev-runner
adapter 10/10, Tauri adapter 10/10 with real children; `cargo test --lib --locked` 56 passed
(Smart App Control did **not** block cargo here; `modules/shell/src-tauri/resources/headless/.ci-placeholder`
must exist locally, gitignored); `run-dev-runner-tests` 9/9; every kernel gate green;
`check-runtime-manifest-closure` with **both writers unskipped**; ui-web typecheck, 6451 unit
tests, `run-ui-web-gates` 27/27.

**Two things the successor must resolve before trusting that suite run.** (1) The full
`cleanTest test --no-build-cache` reported **1433 XML files, 9001 tests, 1 failure** against
9331 tests and 1514 files at B6: 81 result files and 330 tests are missing, which means at
least one module's test task did not complete (the failing task likely aborted the rest of its
module). Re-run the full suite and reconcile the count before the stage-end claim; a total that
is smaller than the previous one is not "one failure". (2) The one failure,
`OnnxEmbeddingEncoderLongDocForensicTest.longDocEmbedWithSpansMatchesBaseEmbed`, ran68s.
The contemporary CPU-fallback/explicit-method-timeout interpretation was wrong:
source verification on2026-09-14 confirms intentional FP32 CPU and the inherited30s
JUnit default. Reproductions1665/1666 and the scoped numerical-test budget correction
are preserved in [the correction record](evidence/C2/long-doc-test-budget.md).
The original full run remains failed; later proof is recorded separately.

**B7 to B10 have not been independently reviewed.** Run the same refute-first, read-only opus
review used for B1 to B6 (brief shape: findings ranked by severity with file:line, a concrete
failure scenario and the smallest fix; then a verified-sound list), and fold its fixes into the
B1 to B6 fix batch below. The implementer's own attack list, in its order: `ShellActuator` in
`supervisor.rs` is the half CI cannot reach (the loop is shared, the bindings are not, and both
critical-analysis findings lived there); `dev-runner.cjs` grew about 780 lines on the path every
agent's stack uses (the port-wait no longer short-circuits on an explicit `--api-port`; the
engine and frontend spawn commands are overridable through `JUSTSEARCH_DEV_RUNNER_{ENGINE,FRONTEND}_COMMAND`,
both gated behind `JUSTSEARCH_SUPERVISOR_HARNESS=1` — verify the gate, not the variable);
timing-shaped conformance cases (`hang-soft` at a 1.5 s graceful deadline was flaky before the
re-entrancy guard; margins are harness-tuned); the `watch_manifest` thread accumulates one per
Tauri restart (pre-existing shape, newly reachable; duplicate `backend-restart` emits are
idempotent, the threads are not reclaimed). Premises that did not survive, recorded in B.md
section 0.1: `SupervisionContractTest` cannot hold `enginePolicyMatchesCode()` (edge
`app-engine -> app-services`), so the engine row names its drift check in a `driftCheck`
field and guard resolution now accepts repo-relative paths; the closure check's Rust glob had
never matched `src/lib.rs` (fixed, and the planted-artifact falsification now reds at
`lib.rs:1080`); `maxCooldownMs: 5000` is inert at three attempts (ramp tops out at 3 s; stated
in the register, one case raises the budget to reach it); the dev-runner's first incarnation
cannot be supervised because `start` is synchronous for its caller, so `startDeadlineMs`
governs restarts there and first boot on Tauri; Q5's mirror is a sibling
`runtime/instances/supervisor-history.v1.jsonl` because the manifest mirror is keyed by an
instance id the exhausted case does not have; no conformance case covers "`exhausted` kills
registered children" until B11/B12 exist (a red placeholder was refused, correctly).

**The B1 to B6 independent review (opus, read-only, 2026-09-08) returned 14 findings; the four
highest were re-verified at the call sites by the orchestrator and all hold.** None is fixed
yet because the fix set overlaps the files B8 to B10 edit (`HeadlessApp.java` is safe;
`lib.rs`, the closure check and the recoverability register are not). Run the fix batch as one
implementer brief immediately after B10 lands, one commit per finding group, then a second
independent review of the fix range. Decisions per finding:

1. **Blocker: llama-server stop-by-reason has no caller.** `InferenceLifecycleManager.setStopServerOnClose`
   (`:1359`) and `ShutdownRequest.Reason.stopsGenerativeBackend()` (`:85`) are called only from
   tests; the default is `true`, so `restart` and `hang` still stop the server and the restarted
   Engine has nothing to adopt. Fix: the sequence sets `setStopServerOnClose(reason.stopsGenerativeBackend())`
   before the `head-assembly` step closes the manager; replace `GenerativeBackendByReasonTest` with
   one that drives a fake `serverOps` through `InferenceLifecycleManager.close()` for all four
   reasons and asserts `stopLlamaServer` called or not called.
2. **Must-fix: a stale request file shuts the next Engine down at boot.** `deadlineEpochMs` has
   no reader; nothing clears the file at start; the watcher's production predicate is
   `request -> true` (`HeadlessApp.java:1146`). Fix both halves: `pollOnce` drops and clears a
   request whose deadline is past, and `HeadlessApp` calls `ShutdownRequest.clear(runtimeDir)`
   once before `watcher.start()`. Test the adverse precondition (a pre-existing file at boot must
   not fire).
3. **Must-fix: 7.3 step 1 (admission freeze for every reason) is unimplemented** and
   `HeadlessApp.java:1236` says all eight steps are. Fix: a first step calling
   `leases.freezeAdmission(reason.wire())` (verify `OperationLeaseServiceImpl.freezeAdmission` is
   idempotent for the upgrade reason, where prepare already froze it); step 2 (cancel interactive
   turns with a reason code) has no admission front until C1, so record it as deferred in
   `stages/B.md` section 0.1 and section 10 and correct the javadoc.
4. **Medium: `worker_outcome` defaults to `UNKNOWN`** when `knowledgeServer == null`
   (`HeadlessApp.java:1283`, `EngineShutdownSequence.java:146`), which `updater.rs:1044` rejects,
   so an Engine that booted without its index half cannot be upgraded (the old code said
   `GRACEFUL`). Fix: the step returns `GRACEFUL` when there is nothing to close and `FAILED`
   when it throws; two new cases in `EngineShutdownSequenceTest`.
5. **Medium: the B6 end-to-end test asserts a hand-copied duplicate of the production wiring**
   (`UpgradeShutdownViaRequestFileTest.java:36-43` versus `HeadlessApp.java:1119-1156`). Fix:
   extract the writer and dispatcher lambdas into package-visible factories and test those; add
   the nonce-mismatch-writes-no-file case B6's acceptance asked for.
6. **Medium: the shutdown-request row's `futureVersionRefusalTest` names a test with no version
   refusal**, and the wire form has no `schemaVersion`. Fix: add `schemaVersion: 1`, a refusal
   branch for an unknown version, a test, and point the register at it.
7. **Medium: the nonce seam is declared and bypassed.** Fix: the predicate for `UPGRADE` with a
   `preparationId` checks it against the live lease snapshot's preparation; if that is not
   cheaply reachable from `HeadlessApp`, restate the javadoc as "unused until B8" and note it.
8. **Medium: rename residue.** `verified-facts.md:120,191` still cite `checkpointForUpgrade`;
   `KnowledgeServerHealthMonitor.java:120,366,699` and `KnowledgeServerBootRecoveryTest.java:294`
   still name `performOrderedShutdown`. Sweep them.
9. **Minor:** orphaned javadoc at `JobQueue.java:707-729`; `HeadShutdownCoordinator.shutdown(preparationId, nonce)`
   has no caller and its `implements` clause is decorative (delete or re-justify); the watcher is
   started and never closed (add it to the sequence's steps); the duplicated OOM comment at
   `lib.rs:804-808` mislabels the prod flag (delete); `check-runtime-manifest-closure.mjs:58`
   describes the Tauri supervisor and the dev-runner as writers in the present tense (say
   "will"); the sequence exits with bare `0`/`1` outside `EngineExit`'s pin, so a requested but
   unclean shutdown reads as a transient crash — add `EngineExit.REQUESTED_UNCLEAN` (a new
   code classified `REQUESTED`), route both exits through the constants, and extend the call-site
   pin to `EngineShutdownSequence`. Because B8's dev-runner classifier and B10's Rust classifier
   read `EngineExit`'s table, adding the code after them must trip their drift tests; if it does
   not, the drift test is the defect.
10. **Verified sound, do not re-litigate:** UTF-8 and NUL clean; flag pins exact-set on both
    spawn sites; no `System.exit` inside the JVM shutdown hook; idempotency, first-reason-wins,
    error accounting and single exit each pinned by a test that reds when its guard is removed;
    the B5 WAL justification is honest and the rename is complete in code; the governance rows
    are shaped like their neighbours. `AotTraining.java:113`'s `System.exit(0)` is a separate
    `main` and out of scope; the extraction child's exit codes are a different namespace.

**Remaining stage B items after the fix batch:** B11 (child registry, manifest schema v2,
`WorkerInfo.grpcPort` removed), B12 (reconciliation by PID plus start instant plus config
identity; extraction children registered, never adopted), B13 (dead-Engine updater path with
the `ENGINE_UNRECOVERABLE` witness phase; host-level proof on the branch, sandbox round deferred
to the first post-merge installer, recorded as a dated gap), B14 (`ENGINE_RESTART_EXHAUSTED`
producer and the rename, `readinessNotice.ts` rows), B15 (`restart_required` on cutover and the
requested-restart consumer), B16 (residue sweep), B17 (stress-suite policy). Then the stage-end
protocol of 17.6: full suite plus ui-web gates; the checkpoint proof run live and recorded under
`evidence/B/` (harness green on both adapters, **a forced kill on the live dev stack recovers
under the budget** — the orchestrator runs this under a dev-stack lease, it is not delegated
fire-and-forget; the death-observability test green in CI); an independent review of the whole
B range; `git merge origin/main` at the boundary; then the owner's "continue".

## 3. Stage C1 and C2 drafts exist; their questions are decided

`stages/C1.md` (863 lines) and `stages/C2.md` (788 lines) were drafted by two opus agents at
base `e692b86ef` while B7 was landing. Their section 0 lists the `verified-facts.md` corrections
found (ten for C1, twelve for C2); **apply those to `verified-facts.md` at the stage start**,
not before, since B may move them again. All eleven open questions are decided in design section
0 ("Stage C1 and C2 checklists drafted during B"). The three items each drafter judged most at
risk: C1 — admission as the `ForegroundLoad` producer (rule 6b bars `ui` from the type, the
gate forbids a second wrap, the front's filter skips GET: the producer must **move**, not be
added), parser confinement (no module boundary exists and VDU already parses PDF in the
Engine), context through the 33-method port (a `withContext` bound view is the decision);
C2 — no journal commit sequence number exists (the operations table's own autoincrement key is
the decision), `jobs.db` re-classification is refused by the installed updater's closed-set rule
(batch every durable-store identity change into one register change, and relax the rule's
successor in the same commit), and `version conflict` has no subject until the global
accepted-settings revision exists (C2 builds that revision only). Note the installed updater
compares against the installed build's **durable** stores, so stage B's `EPHEMERAL` rows do not
trip it; a `DERIVED`/`AUTHORED` identity change does.

## 4. Working practices that were never written down

- **Parallel lanes, one implementer per branch.** Throughput came from running, at once: one
  implementer committing on the branch (sequential items, one Gradle build at a time), one
  read-only reviewer on the previous batch (no Gradle, no cargo, no edits), and drafters for the
  next stage writing new files only. Never two writers on the same worktree. Do not commit your
  own files while an implementer may have paths staged: check `git diff --cached --stat` first,
  and stage by explicit path.
- **Isolated Gradle homes** avoid shared-cache corruption when two worktrees must build: the
  0b worktree used `GRADLE_USER_HOME=F:/gradle-home-0b`. Memory is the real limit: with the
  standard chat model loaded (about 11 GB) a Gradle build or a Rust compile beside it gets
  killed; use the compact profile for plumbing checks and unload before building.
- **Background tasks die at about 60 minutes**; briefs over that size are chunked (B1 to B6 ran
  64 minutes and survived by luck). Subagent reports go to the spawning session only; make
  implementers write their findings into `stages/<letter>.md` section 0.1 and commit bodies so a
  report is never the only copy.
- **Every implementer is told, in the brief:** Edit/Write or node UTF-8 scripts only; the
  NUL and non-ASCII diff checks before each commit; falsify every new assertion once and say how
  in the commit body; one commit per item, green on its own; never `git checkout --`, `stash` or
  `reset` on a dirty tree (the B1 to B6 implementer destroyed its own work twice); the two
  trailer lines; do not push (the orchestrator pushes after review). Bash-tool `node -e` and
  heredocs break on apostrophes and backslashes: the Edit tool for prose with either.
- **Always-loaded budget is at its ceiling.** `AGENTS.md` sits at exactly 8296 of 8296 bytes;
  any merge from `main` that adds a byte reds `check-always-loaded-budget`, and the invariants
  block in `CLAUDE.md` is a generated projection (`node scripts/docs/agent-instructions-sync.mjs`,
  `--check` to verify) that `scripts/ci/check-codex-agent-parity.mjs` runs in CI separately from
  `regen-all`. Trim, regenerate, run `check-always-loaded-budget`, `regen-all --check --except
  notices` and `check-codex-agent-parity`.
- **Live verification.** The dev MCP tools (`quick_health`, `start`, `ai_activate`, `reload`,
  `stop`) drive the stack from the worktree; ingest through `curl` with the worktree's absolute
  paths, not the MCP `ingest` tool, which resolves paths against the main checkout and produced
  a duplicated corpus once. The fixture capture pins live in `scripts/jseval/lane-f/fixture-pair.sh`
  (exhaustive kNN, one LLM slot, rerank deadlines 60000, rerank top_k 40 with 4096 MB, candidate
  limit 5000, collapse multiplier 50, leg arbitration and recall-complete off); `fixture-gate.sh`
  gives the verdict; three captures per side is the accepted noise floor (216 equal, 0
  regressions at PR 0b).
- **Evidence directories** are the checkpoint record: `evidence/pr0/`, `evidence/baseline/`
  (scifact, encoder-idle, fixture, fixture-pr0b), `evidence/A/` (red captures, accepted reds,
  live check, suite-and-gates, deletion counts). Create `evidence/B/` in the same shape.
- **Named reds and gaps carried at handoff**: `worker.restart_exhausted` has no producer until
  B14 (declared `awaitingProducer`, owner lane-F/B, in the readiness register);
  `WorkerBootRecoveryE2ETest` is red from stage A (A.md section 10 row 1c) and B owns it;
  `build-installer.yml` cannot run on a branch ref (`environment: release-signing`), so every
  packaging claim on the branch is "changed, locally reasoned, never executed" until the first
  post-merge run; migration cutover served-generation observability is D1's;
  `LambdaMartBenchmarkTest` is quarantined as load-sensitive on this branch; three other tests
  flake only under concurrent worktree builds and pass isolated (`BatchUpdateIntegrationTest`
  concurrent RMW, a `RuntimeReconcilerTest` temp-file move).
- **Design amendments** go in section 0 as dated paragraphs, newest last; the checklist's
  section 0.1 takes per-item corrections; `verified-facts.md` takes corrected citations at stage
  start. Three places, three purposes; do not collapse them.

### Codex orchestration checkpoint (2026-09-08)

The takeover verified the actual worktree at `.claude/worktrees/lane-F-A`, branch
`worktree-lane-F-A`, starting clean and pushed at `1ffd6cc2d`. The main checkout was
left on main with its unrelated changes intact. A separate, owned
`codex/lane-f-main-verification` worktree at `83b9e5fd5` supplied the main control;
it remains available for comparison, not implementation.

Both initial verification questions are resolved in
`evidence/B/takeover-verification.md`. The forensic test explicitly uses CPU FP32;
fresh isolated main and lane controls passed around 17s without timeout changes.
The old 1433/9001 count was a mixed post-rerun XML inventory, not the preceding
full-suite inventory. A fresh pre-edit full suite passed with 9334 tests, zero
failures/errors and 25 skips, and its XML was preserved before targeted runs.

The first sole-implementer shutdown batch is reviewed through `d23e94bdd` and
released with a clean tree and no active Gradle wrapper. See
`evidence/B/shutdown-review-checkpoint.md` for commits, primary-source evidence,
the 3965-test affected-module inventory, post-final-edit targeted verification,
falsification limits and all remaining findings. Strict boot cleanup's placement
has source/helper evidence, not a live boot-failure proof. Stage B is still open.

The independent B7–B10 findings are in
`evidence/B/b7-b10-independent-review.md`. Dated design section 0 now settles the
B6 acknowledgement/nonce transaction, first-claim-wins request protocol, focused
production host ownership, v2 child ownership handoff, and B13 process hold/evidence/UI
corrections. B section 0.1 records the corresponding per-item amendments. The next
implementation batches are B6 transaction and the coordinated request protocol,
then production supervisor ownership and the remaining lifecycle items. One
implementer owns the branch at a time; reviewers remain read-only.

Before B14/B15 implementation, resolve the source-backed questions in
`evidence/B/remaining-lifecycle-investigation.md`: stale supervisor state is not a
current recovery owner, a supervision veto needs an actual recovery owner, and
promotion response flags currently lose their restart consumer. These questions
are not silently treated as decided by the existing draft.

PR #708 (`62251e459`) and PR #717 (`f0d9e2481`) were rechecked OPEN/CLEAN during
this checkpoint and remain the owner's per-PR merge calls. No merge was performed.
No new benchmark, evaluation or capture longer than one hour was started.

### Reviewed transaction and host ownership checkpoint (2026-09-08)

This supersedes the preceding checkpoint's next-batch list. The primary worktree
remains `.claude/worktrees/lane-F-A` on `worktree-lane-F-A`; main and unrelated
worktrees were not edited. `0ecaa8496` was reviewed and pushed before the next work.

**B6 transaction:** `e18760596`, `975c4f33d`, `fb2b1e967` and evidence commit
`5c1df5992` have independent read-only sign-off. The production writer is installed
before API exposure and persists before success is returned. The controller alone
owns the nonce and OPEN/PERSISTING/ACKNOWLEDGED state. Dispatch follows successful
response flush. Pending requests survive deadline expiry during that flush;
controller-acknowledged requests retain their original deadline. A preparation
reservation protects both the lease-before-nonce and Worker-after-nonce boundaries
against concurrent prepare/cancel/commit, without holding the monitor over I/O.

The full app-engine/UI run passed in 7m7s: 1158 tests, zero failures/errors and one
skip. It predates the final deadline/preparation fixes. Final focused evidence is
33 passing tests with no skips: ten watcher tests executed in the preceding run,
then reused while the final 21s run executed 23 UI tests. Both inventories and the
raw falsification/green logs are under `tmp/lane-f-takeover/`; see
`evidence/B/b6-upgrade-transaction.md`. The servlet failure is injected, not a real
socket-disconnect proof. No post-change full repository suite is claimed.

**Production host ownership:** a separate sole implementer used
`.claude/worktrees/lane-F-host-ownership`, branch `codex/lane-f-host-ownership`,
based on `0ecaa8496`. Code checkpoint `f25dbadfa` has independent sign-off and was
integrated into the primary branch as `5c179b5a0`; `36fb25ed3` and `203d40f67`
integrate the evidence correction and complete raw Cargo log. The
production core serializes real-child spawn admission with monotonic host close,
retains predecessor discovery identity, rejects stale and child-absent manifests,
guards stdout EOF by spawn generation, owns one cancellable/joined watcher, and
publishes initial and replacement launch failures through the real state/event
paths. Only admitted manifests can update the tray, and repeated tooltip text does
not trigger repeated native updates. The library suite passed 59 tests; three
mutations of production event/failure paths failed before restoration. The evidence
in `evidence/B/b7-b10-independent-review.md` reconciles the inherited 56 tests and
states the test-only Tauri resource override. Tauri setup wiring was source-reviewed,
not executed as a packaged application.

The integrated `build -x test` exposed eight PMD violations in the shutdown
changes. `3721395ff` fixes the redundant qualifiers, preserves try-with-resources
cleanup using unnamed resources, and removes an unused test helper. Focused PMD
and shutdown tests passed in 20s; the repository build then passed in 26s (325
tasks). Raw failure, repair and green build logs are under `tmp/lane-f-takeover/`.

**Decisions and remaining work:** newest dated design section 0 paragraphs now
also settle B14/R7. Retire the obsolete Java whole-Worker supervision veto while
preserving local recovery and fatal index/schema refusals. Current host state must
reach the real recovery UI; stale state files are not a live recovery authority.
A bounded valid HTTP 503 response proves liveness and must not trigger a hang
restart; essential readiness separately controls budget reset. This is decided but
unimplemented. `evidence/B/remaining-lifecycle-investigation.md` carries the source
findings, including the fact that B15's actual promotion occurs after the cutover
request response.

The first-claim request protocol is stopped by the newest design section 0
decision. Read `evidence/B/scope-recut.md` before any request-protocol work. The
preferred replacement has one supervisor file writer and local Engine dispatch;
it first needs production proof of response ordering, requested-exit classification,
responsive shutdown stalls and updater timeout ownership. Existing manifest STOPPING
is a candidate signal, with overwrite and premature-deletion hazards still to fix.
Do not implement the suspended marker/retention/claim mechanics from older amendments.
R3 is still open; the B6 transaction does not close it. R6/R7/R8, B11/B12 ownership
handoff, B13 updater hold/recovery UI, B14 implementation, B15's real restart consumer,
B16 residue and B17 verification wiring remain open. Preserve the separate uncounted
intentional-restart and counted-failure paths. Then complete the stage-B compile,
unit, frontend, conformance, live recovery and real-model proof before claiming the
stage checkpoint. The main control worktree and pre-edit full-suite evidence remain
available; no relevant historical or named gap is silently converted to a pass.

PRs #708 and #717 remain the owner's per-PR merge decisions. No merge was performed.
No benchmark, evaluation or capture longer than one hour was started. All implementers
release their branch before orchestration edits or integration; reviewers remain
read-only and do not implement the work they review.

## Scope and integrated verification checkpoint (2026-09-08)

The scope stop is committed as `da62f5201`; the independently reviewed drain
fixture repair is `4349b28f5`. All B6 transaction and host ownership work is now
integrated in the primary branch. The host worktree is clean at `bb0409520`, with
no remaining implementation delta to integrate. No implementer retains branch or
Gradle ownership after this checkpoint.

The full stress-enabled run at `3721395ff` executed 9,364 tests and failed two.
All 1,522 XML files and per-file hashes were captured before targeted reruns.
The drain fixture now passes its full 699-test module; its deliberate wrong-reason
mutation failed. The final repository build with tests excluded also passes.
**The Engine file-locking boot test remains a blocking red.** A locked compound
segment closed the Lucene writer; subsequent work kept retrying that writer.
B14's no-client boot retry is not its recovery path. Read
`evidence/B/integrated-verification.md` for exact commands, counts, raw logs and
the next bounded detection/recovery/replay experiment. Do not turn an isolated
passing rerun into a claim that the writer failure is fixed.

The first-claim/accepted-marker request batch remains stopped. Before approving a
replacement, prove the preferred single-writer cut through production response
ordering, requested exits, responsive shutdown stalls and updater intent ownership
as listed in `evidence/B/scope-recut.md`. If either recovery investigation needs a
later-stage mechanism, apply 17.8 before moving it earlier. No stage-B completion,
live-model/installer proof, PR merge or new PR is implied by this checkpoint.

Closeout found no owned registered helper to reap; the ownerless OTLP sink
(PID 14468) was reported and retained. The extra host branch is intentionally
unpublished because all its reviewed content is integrated in the primary branch;
it is not an outstanding implementation lane. The clean main-control worktree
remains available for future comparisons. Unrelated worktrees and main were left
untouched.

## Publication and recovery checkpoint (2026-09-08)

Both split-baseline PRs have landed under the delegated merge authority:

- #708: `0824e365411960597d2af2f87ba55cf17381426f`, matching reviewed candidate
  `30e027c6105489d49cf5427f5a80188aee0902bb` over the entire tree.
- #717: `f938c4eb2e9b487bd0965859108d239c30e8601f`, matching reviewed candidate
  `a6bda3224274dd768a3ded741b28fca7b43c9de4`. That candidate is byte-identical to
  the fully tested `7c8cc01a50f10c684cfe26b47274bed778c855be`; the merge of the
  #708 squash changed ancestry only. Independent review verified the conflict
  resolutions, merge parents and unchanged 71-path diff before push.

Read [the publication record](evidence/publication.md) for candidate, queue and
main-push CI, all 33 module counts and the original log hashes. The fresh Java
runs passed 9,301 and 9,337 tests respectively; Python passed 3,536 and 3,637.
All original Java XML files were preserved before filtered reruns. These are
split-mode PR results and do not clear the Stage B stress failure.

The primary branch's recovery-documentation checkpoint is `508030d79`. Its only
test-source edit is a Javadoc correction: the old scheduler explanation is scoped
as a hypothesis, and the proven closed-writer failure remains a blocker. The
comment-only edit passed formatting; no full-suite rerun or recovery success is
claimed. The Codex workflow instructions match this branch's own canonical PR 0
reference. **PR 0b is not yet integrated into the primary branch.** The premature
PR 0b instruction copy was reverted before push; carry the published code and
matching instructions together at the lane's main-integration checkpoint.

The next recovery proof is the whole-Engine fatal-fault route, using the existing
transient-exit budget and startup queue replay. Read
[writer-recovery-investigation.md](evidence/B/writer-recovery-investigation.md)
before implementing: generic lock IO is not a fatal-writer diagnosis, failed
queue transitions can leave PROCESSING rows, and automatic production restart
must be exercised. The existing runtime swap is not a proven shortcut. D1 live
replacement has not moved into B.

The shared first-claim/accepted-marker batch stays stopped. The single-writer
shutdown direction still needs the response, exit, stalled-close and updater
proofs in [scope-recut.md](evidence/B/scope-recut.md). B11-B17 and their live,
real-model and installer proof obligations remain open in the stage checklist.

The owned main-control worktree is clean at the published `f938c4eb2` for future
comparisons. The host branch remains clean at `bb0409520`, wholly integrated into
the primary branch and intentionally unpublished separately. No lane implementer
or owned Gradle run remains active. The registered-process sweep found no owned helper
to reap and retained the ownerless telemetry sink (PID 14468). No development
stack or new capture campaign was started for publication. Unrelated worktrees
and the shared main checkout remain untouched.

## Ordered writer-fault repair checkpoint (2026-09-08)

The terminal-writer connection is implemented above `6f38df7e5`. Read the dated
implementation and verification sections of
[writer-recovery-investigation.md](evidence/B/writer-recovery-investigation.md)
before continuing. The production owner is HeadlessApp's complete ordered
shutdown sequence, dispatched by EngineRoot's dedicated thread. RuntimeSession
arbitrates one report against retirement; KnowledgeServer binds before publishing
each writable runtime. NRT failures during intentional close cannot fall back to
raw JVM exit after the runtime snapshot has been cleared. Deferred native model
initialization completes before model teardown. Fatal admission and the upgrade
receipt use the sequence's single exit-code selection.

The deterministic installed-Engine/dev-runner proof passed: one charged fatal
restart, preserved committed document and accepted document searchable. A separate
native-enabled arm exercised real GPU session initialization and reranker warm-up
overlap, followed by graceful stop. It did not execute AI-ranked search, both
durable queue outcomes or packaged Tauri startup. Earlier deadlock/native-crash
evidence is preserved rather than relabelled as unrelated. The integration tier
runs in advisory CI; default `test` excludes that tier and stress tags.

The orchestrator resumed direct implementation ownership after the worker's
handoff; the independent reviewer remains read-only. The final verification
inventory belongs in the linked evidence, not in an assumed green filename.
PRs #708/#717 remain merged. PR 0b still enters this primary branch at the recorded
main-integration checkpoint.

Next implementation batches are B11-B12, B13-B14, then B15-B17, with fixed briefs
and at most two worker review rounds. Within B17, explicitly relocate hostile-lock
survival to a real supervised process,
preserving intruder-before-boot ordering, lock intensity, corpus, acceptance and
180-second searchability assertions. Preserve the original red as the cause for
that move. Prove both durable queue outcomes. Then finish the single-writer
shutdown re-cut's production proofs and B11-B17; D1 live runtime replacement and
the rejected shared request-slot protocol remain outside this repair.

## Integrated B11–B14 and B16 checkpoint (2026-09-08)

Resume in `F:/justsearch-public/.claude/worktrees/lane-F-A`, branch `worktree-lane-F-A`.
The root is sole implementer; reviewers are read-only and have returned signoff.
B11/B12 is `531fa93f1`; the primary then integrated reviewed B14 Java retirement
`03c4e513b`, B16 `a7eb1abdf`, pre-API recovery UI `5c1e933d4`, and R7 `c7a0aae41`.
The following B13 commit completes the exclusive updater hold, real owned-child
stop/reconciliation, tagged no-receipt evidence and one-loop failed-launch resume.
Its evidence is [b13-updater-handoff.md](evidence/B/b13-updater-handoff.md).
The full integrated suite passed 9,405/0 with exact task XML preserved; Rust 76/0,
frontend 6,462/0, both supervisor adapters 11/11 and governance checks passed.
A stale Tauri conformance executable caused an initial failed invocation; rebuild
that binary explicitly before adapter runs. The two B11 stale UI assertions were
corrected to the new public schema and completion ordering and independently reviewed.

B11–B14 and B16 are complete at the permitted branch proof tier. The signed
installer/user-store exercise is assigned to final validation in stage E, registered under
`upgrade-dead-engine-recovery`; the 2026-09-08 final-validation amendment in design section 0
supersedes the earlier first-post-merge scheduling. Stage F carries the gap if main-only signing
requires the actual run to wait for the first eligible installer after the final merge. B15 and remaining B17 work are next:
actual promotion → requested whole-Engine restart → promoted generation served;
hostile-lock survival through real supervision; PROCESSING as well as PENDING replay;
and the single-writer shutdown re-cut's four production proofs. Read `scope-recut.md`
before changing shutdown transport. No shared request-slot/accepted-marker protocol
or D1 live component swap has been approved by this checkpoint.

The previous secondary recovery worktree/branch remains a preserved checkpoint at
`ad272bdd9`, including its earlier uncommitted B13 snapshot; do not resume editing it
or copy its older files over this integrated primary. Raw outputs remain in ignored
`tmp/`; tracked evidence contains summaries and hashes. Main and other worktrees
remain untouched. PRs #708 and #717 are merged; PR 0b still enters at the recorded
main-integration checkpoint. User authorization now permits autonomous merges.


B15 precursor after that push: the existing manifest handoff survives late readiness
writes and finally close, and is cleared by a fresh incarnation. A new publisher
regression proves this, including a failing negative control; 26 publisher tests
and UI test PMD pass. There is no production transport change yet. The independent
review found that a local `restart` handoff also accompanies fatal writer exits:
never copy it into the host-owned requested-reason slot, which makes restarts free.
See the new design section 0 constraint and `scope-recut.md` for the bounded next cut.


B15 transport checkpoint (2026-09-08): clean restart exit 4 is pushed in `86d369c25`.
The following reviewed cut removes Engine-written shutdown requests, dispatches upgrades
locally after response flush, and bounds current-incarnation manifest handoff with each
host's existing Stopping deadline. Both adapters 17/17, Rust 77/77, fresh Java full suite
9405/0; preserved XML and hashes in `evidence/B/b15-requested-restart.md`. Production
Tauri AppHandle binding remains source-reviewed, not claimed as installed proof.
B15 is still open for migration start/rollback/cutover consumers and actual promoted
search after restart, including publication-failure refusal. B17 remains open as above.
The scope-recut's controller and local-host proof obligations are now covered by this cut;
no shared request-slot/accepted-marker mechanism was introduced. Root is sole implementer.

B15 completion checkpoint (2026-09-08): migration start/rollback and verified promotion now
dispatch the existing process-owned ordered restart. Real installed Engine tests pass through
the integrationTest entry point: start, promotion and rollback each exit 4 without spending
the crash budget; differing Blue/Green search results prove reader reopen. The installed run
also exposed and fixed Windows batch-wrapper PID admission and the embedding-backfill drain
race. Both changes received independent review. Full units 9416/0; the last count-read
fail-closed correction passed its focused regression, final build and both installed cases.
See `evidence/B/b15-requested-restart.md` for exact limits and hashes. B15 is complete;
B17's hostile-lock relocation, PROCESSING-at-death replay and final stage checks remain.

B17 implementation checkpoint (2026-09-08): hostile-lock and PROCESSING-at-death proofs
now run in `EngineSupervisedRecoveryE2ETest` (five installed cases passed). The Tauri
adapter uses production EngineHost lifecycle and identity methods (17/17); both reviews
are clear. No production recovery protocol was added. The next action is merge current
origin/main into this lane, then run the full suite with stress enabled and rerun the
installed-process suite against that integrated candidate. B17 is not yet closed.
`evidence/B/b17-recovery-proofs.md` records the checkpoint; raw outputs stay ignored.

B completion (2026-09-08): B1–B17 are complete. B17 is `b8094f9cf`; main/PR 0b
`f938c4eb2` is integrated by `39f598efa`, with all conflict resolutions independently
reviewed. The following closeout corrects a fixture-only transient manifest read and
records final proof: full units plus stress 9,456/0 (25 skips), installed recovery 5/5,
Rust 77/77, both supervisor adapters 17/17, frontend 6,462/0, fixture tests 192/192,
build/governance/generated checks green, and a fresh jseval start/ingest/query/stop smoke.
Exact XML and raw logs remain ignored; hashes and limits are in the B17 evidence record.
No dev stack remains owned. The root is sole implementer and both final reviews are clear.

Next is C1: re-verify its draft citations and inherited facts under 17.6 before implementing
its bounded batches. C2, D1 and final E/F proofs retain their assigned scope. Signed
dead-Engine installer/user-store proof is deferred to E, carried through F only if main-only
signing requires the final merge. Keep the one-branch checkpoint sequence until F/PR 1;
autonomous merge authority does not require publishing the implementation at each stage.
The secondary recovery worktree remains preserved and must not overwrite this branch.

Hosted-proof correction (2026-09-08): the B-complete wording above omitted B9's hosted PR-run
acceptance. Draft PR 1 is now [718](https://github.com/justsearch-app/justsearch/pull/718), with
no change to final merge placement. Its first run exposed real CI/platform/test-wiring gaps;
`evidence/B/hosted-ci.md` records the causes, fixes and current verification. All 13 hosted jobs
pass at `84b8c0b6f` (run 34274192421), including B9 and advisory installed integration; the
generated dependency-document correction is also green. Stage B acceptance is complete and C1
can start under 17.6. The subsequent checkpoint commit changes only these evidence/status notes. Design section 0 is now a short Stage B decision index into owning sections;
the pre-consolidation chronology is preserved at `be47faa40`. Paired installer skills and their
canonical source now label the old config snapshot as inert history. E explicitly owns any
unproved supported-OS whole-Engine recovery coverage as well as signed installer/store proof.

C1 and C2 grounded (2026-09-09, design orchestrator, branch `worktree-lane-F-design`): both
checklists were re-grounded at `be47faa40` by a code-verified pass (investigation by two
read-only agents, the design writing by the orchestrator). `stages/C1.md` and `stages/C2.md`
now cite `design.md` by section and code by `file:line` at that commit; their §0 carries the
corrections, §11 the decisions, and **§12 the implementation batches, which are the briefs**:
one implementer per batch, sequential on the lane branch, read-only review after each, at most
two review rounds before the orchestrator takes the diff. Three design amendments landed with
them (17.3 rows C1 and C2, 17.9 row C2, 7.5's store sentence) and five dated rows in the section 0
index. The one reversal to know: the operations table lives in its own `operations.db` and **no
existing durable store changes owner, class or reconciliation in lane F**, because the installed
updater compares every register row by count and identity; stage B's new rows already cost 0.3.0
installs an in-place upgrade, and C2-1 lands the successor rule plus a release-descriptor
baseline so that boundary is paid once. `verified-facts.md` has a dated correction section for
everything the pass found. C1 starts with batch 1 of `stages/C1.md` §12 once PR 718's hosted
correction is green; C2 starts after C1's batch 2 has landed and been reviewed.

Later stages designed (2026-09-09, design orchestrator, `worktree-lane-F-design`): `stages/E.md`
is the gate-run runbook (values instantiated from the PR 0 baseline, instruments inventoried
against every row of 16 with the two stale ones named for the E1 fix, runs bounded to fifty-five
minutes, the split side measured on `origin/main` at E, the collector protocol, the three
representative changes, the signed dead-Engine round, the record layout). `stages/D1.md`,
`stages/D2.md` and `stages/F.md` are code-verified drafts at `be47faa40` with their decisions
recorded in design section 0; each is re-grounded at its stage start because C1, C2 and the
stage before it move the code. All five later-stage files follow the C1/C2 shape: §0
corrections, §1 ledger, §3 items with runnable acceptance, §7 harness, §9 allowed reds, §11
decisions, §12 batches (the briefs). The Codex agent implements from §12 of the current stage
and never from a draft two stages ahead.

C1 batch 1 complete (2026-09-08 continuation, checkpoint containing this note): integrated
main's workflow policy as `315afda5b` and the later design commits through `395078f04`, preserving
B's hosted correction. The original Claude transcript led to the newer design worktree; it and
the secondary recovery worktree remain preserved. `evidence/C1/batch-1.md` records implementation,
review corrections, local test counts, raw output paths and proof limits. Core 82, app-api 199,
Engine 133, focused provenance/gate 17, resource loader 3 and Rust 77 pass; repository compile,
exact launch flags, the admission oracle's 26 synthetic cases, docs and eight generated sets pass.
No live C1 admission/pacing proof exists yet; batches 3 and 4 own those runs, with batch 4 repeating
after bounded executors. All five retained kinds remain awaiting their actual D1/D2 owners.

Continue immediately with C1 batch 2. Q2 is corrected to explicit required context parameters:
the single KnowledgeClient implementor owns mutable stores, caches, callbacks and an executor,
so the proposed bound view was not the one-class change the draft claimed. C1-3 compares the
alternatives and records the new design. The app-api contract gains core as an API dependency;
regenerate dependency locks and the canonical module graph with that change. Provenance must
survive asynchronous enqueue to a terminal ledger row, including recovery; a ledger-only
constructor change cannot establish an agent-originated ingest. The root is tracing that queue
ownership seam before implementing V14. C2 remains separate operations.db, with jobs identity
unchanged. E observes three forty-minute runs to honor the original one-hour per-run ceiling.

C1 batch 2 in progress (2026-09-09 continuation, based on `dd11e372d`): required contexts now
propagate through ports, documents, agents/tools, conversation runners, request controllers,
MCP and pending approvals. `stages/C1.md §0.1` records the ingress/trust decisions and scope.
Jobs schema V14 persists admission originator/transport; maintenance preserves prior values,
atomic claims snapshot them for terminal writes, and versioned SWITCHING UPSERTs preserve collection
and attribution while reading legacy paths. The projection remains owned by the action ledger.
Focused persistence, real bridge propagation and originator tests pass; the migrated app-agent
suite passes 669/669 and app-agent-api 228/228. Store-recoverability and engine-port gates pass.
Raw logs are `tmp/c1-batch2-*`; initial XML copies are under `tmp/c1-batch2-xml` (retain through
lane F completion plus 30 days). These are working-tree results, not a committed/hosted proof.
UI and app-services fixture migrations plus new approval/run provenance regressions are delegated;
the root owns queue changes and all builds. Batch 2 remains incomplete until integrated build,
full suite, review and evidence reconciliation pass. Admission, gauge changes and bounded
executors remain batches 3/4; C2 begins only after batch 2 is reviewed and green.

Independent batch-2 review found and corrected a same-path re-admission attribution race: claimed
work now carries immutable provenance through extraction, stale outcomes and writing. A supplied
legacy-null snapshot stays unknown instead of borrowing a later caller. The regression passes and
fails with the previous current-row lookup restored (`tmp/c1-batch2-claim-mutation-red.txt`, XML
under `tmp/c1-batch2-xml/claim-mutation-red.xml`). The restored implementation and a real Engine
MCP submission reaching an agent/MCP SQLite outcome passed in `tmp/c1-batch2-claim-tests-2.txt`.
Explicit directory-sync attribution (normal and SWITCHING) and request-span export provenance
are now implemented and verified, including the final typed maintenance-coalescing correction.

C1 batch 2 complete (2026-09-09, checkpoint containing this note). Required context migration,
V14 durable provenance, real claim snapshots, versioned replay, maintenance coalescing and
request logging all pass independent review and integrated verification. Full-build-6 passes
including every Java source set and PMD; full-suite-3 reconciles 9503 tests with zero failures/errors
and 25 skipped. Final XML/counts and adverse mutation proof are linked from evidence/C1/batch-2.md.
The final owned live run a0dacbc2-580c-4d19-9e09-e9ae8fbca937 exercised hybrid dense/cross-encoder
search and real compact-model summarization with citation scoring; it was stopped after proof.
The compact output hit its token caps and is plumbing evidence only, not quality acceptance.

Continue with C1 batch 3 from its section 12 brief. Read-only preparation is recorded in section
0.1: one shared admission owner must be composed before asynchronous Engine startup; neutral
cancellation handles must cross the front/Engine boundary; the urgency gauge must cover all
unary/streaming work. The upgrade control routes must remain reachable after prepare freezes
admission. The current MCP process inherited an old Claude identity: supply this Codex sessionId
explicitly on start and subsequent calls, and use a worktree-relative dataDir. Its preflight's
workerDist requirement is stale; the target worktree's normal runner installs the single Engine.
No dev stack or Gradle task remains running. C2 can begin after this checkpoint, but admission and
durable-handle integration still belong to C1 batch 3 and C2 in their documented order.

2026-09-09 batch-3 implementation is now dirty above `986962ec0`; see
[batch-3 evidence and remaining work](evidence/C1/batch-3.md). Admission, port cancellation/gauge,
background/approval child lifetimes and frontend retry handling have focused passing proofs.
The current next seam is complete conversational/agent cancellation: OnlineModeOps still owns an
asynchronous HTTP stream whose lifetime is not controlled by interrupting the conversation waiter.
Do not claim shutdown step 2 or batch 3 complete until that producer is bound and the remaining
wire/live/integrated acceptance is executed. Continue autonomously; this note is not a pause.

2026-09-09 continuation: frontend retry checkpoint is `b0078e603210c2c1c64aa5e1c700a5086f125c6f`;
other batch-3 work remains dirty. Model producers now retain the admitted handle and cancellation
closes the actual HTTP stream. Conversation/agent/hierarchical wiring and post-model cancellation
checks are implemented; focused integration and real managed-SSE ownership proofs are running.
See the latest progress entry in [batch-3 evidence](evidence/C1/batch-3.md), including retained red
runs and fixes. Continue with this dirty work, not the earlier checkpoint alone. No stack is active.


2026-09-09 batch-3 verification continuation: the model, complete-turn and managed-SSE ownership
implementation is present above `b0078e603`. Real loopback tests prove durable creator disconnect
lowers urgency while work continues, normal retirement does not falsely detach it, early/replay
close releases resources, and MCP freeze preserves JSON-RPC identity. Focused correction tests
pass; full build/suite is being reconciled. Its long app-engine tail is the existing EngineSoakTest
(measured thread stack in tmp/c1-batch3-integrated-engine-threads.txt), not a stuck build.
The live jseval admission driver now rejects malformed/truncated/error SSE and validates real
holder/probe timing. Aggregate capture needs the startup aggregate reduction to 3 and baseline
projection; client fairness runs separately at packaged defaults across search/suggest/MCP/health.
Next: finish integrated verification, install the UI-owned single Engine distribution, run both
live admissions and the continuous-search indexing proof, reconcile batch-3 evidence and commit.
Then implement C1 batch 4 (executor registry/bounds and extraction pins), using the pending
read-only ownership review; C2 grounding is also delegated read-only. No lane item is owner-gated.
The active session identity is `01a082dc-dfd6-7d60-be2e-a8d088229a67`; use it explicitly for every
owned tool/helper because the inherited MCP/session-file fallback names the old Claude session.


2026-09-09 batch-4 continuation (04:50 UTC): active branch remains worktree-lane-F-A,
checkpoint `95ac489b2` commits the parent SPLADE preservation prerequisite and dedicated proof
[evidence/C1/parent-splade-rmw.md](evidence/C1/parent-splade-rmw.md). Its 52 focused tests
pass, including four real Lucene RMW cases; preservation/share/partial-write mutants fail for
the intended reasons. Batch-3 continuous-search proof completed: 7048/7048 successful queries
while 469 documents indexed to all enrichment stages complete. The pre-fix capture exposed the
SPLADE churn and remains adverse evidence. Repeat against the final batch-4 installed candidate.

Batch 3 and the broader batch-4 migration remain dirty above the checkpoint. The process registry,
root/telemetry/launcher composition, Head document urgency pools, EngineKnowledgeClient call/stream
pools, KnowledgeClient walk, SyncOps timer, managed-child HTTP and OpenAI HTTP owners are wired.
EngineFutures links a queued task's cancellation to its returned stage. Focused composition run
`tmp/c1-batch4-composition-tests-4.txt` passes 185 tests (Engine 25, services 42, launcher 65, UI 53),
XML/counts in `tmp/c1-batch4-composition-green-4`. The preceding integration run's four failures are
preserved in `tmp/c1-batch4-composition-red-3`; its correlation fixture, narrow telemetry layering
rule and two missing HTTP context fixtures were corrected. EngineKnowledgeClientExecutorTest's
new physical queue assertion still needs explicit execution (the focused pattern omitted it).

Continue remaining producer injection, urgency-separated inference callbacks/HTTP, parser pins,
rule mutations, stress/full checks and fresh live admission/pacing before closing C1. Runtime
activation HTTP wiring and parser confinement are delegated; root owns composition and all builds.
The current source also adds registered bootstrap/shutdown-watcher execution, not yet tested.
No dev stack or Gradle build remains active at this note. C2/D/E/F remain required; merge placement
stays F/PR1. No push/merge occurred in this continuation. This is recovery context, not a pause.

2026-09-09 batch-4 continuation (05:38 UTC): same HEAD95ac489b2 and worktree; no publication.
Worker registry bundle and parser confinement are implemented. Focused worker run11 passes all
120 tests, zero failures/errors/skips; XML at tmp/c1-batch4-worker-green-11. Build8/9 uncovered
minimal Head constructor omissions, telemetry PMD cleanup and two KnowledgeServer qualifiers,
now corrected. Full build remains pending; run10 also overlapped the next bridge constructor
migration and retained the old sandbox IOException expectation. Its worker expectation now pins
SandboxExtractionException with original IOException cause and preserves the real child-reaped
assertion; corrected worker run11 is the fresh proof.

The stream/metric/readiness/rule/rollup owners, Head/runtime HTTP, watcher self-close handling,
launcher ConfigStore/default-smoke path rollback are implemented but some recent corrections need
fresh integrated proof. Review found VDU timer's procedure still raw/untracked and the transition
log closed before the final inference SHUTDOWN event; root fixed both and added blocked-procedure
real-registry and Head final-log-drain regressions, not yet run. Four remaining Head timer owners
(ScanProgressRegistry, RemoteIndexingJobsBridge, KnowledgeServerHealthMonitor, EnergyStatePoller)
are being migrated; root has wired their production callers and all KnowledgeServerBootstrap
constructors/tests. Inference executor/actual-work migration is delegated with the decision added
to C1.md. Lucene/search fanout is under read-only design review. No dev stack is active; Gradle
worker run11 finished. Continue integrated checks and remaining producers, gates, full/stress,
final installed live proof, then C2/D/E/F. This is recovery context, not a pause.

2026-09-09 06:10 UTC reorientation and item checkpoints: the user requires pushing every
batch/item commit, committing WIP at least hourly, and at most three follow-ups per worker before
root takes the diff. All implementation workers have stopped; root now owns their unfinished
inference/Lucene integration. No new implementation proceeds until the current dirty tree is split.
The split and exact outstanding acceptance are recorded in [checkpoints](evidence/C1/checkpoints.md).
These are explicitly intermediate WIP commits, not individually green stage boundaries. Final
stage/merge acceptance remains unchanged. The first checkpoint is da6291c80 (C1-5), pushed with
all prior local checkpoints to the existing branch. Each remaining named item is pushed immediately.
No branch rewrite, main edit, PR creation or merge occurs during this operation.

New local proof since the previous note: core timeout tests13 passed and a task-cancellation mutant
failed both intended tests; inference production/test compilation14 passed before the worker's
last regression additions. All logs are named in checkpoints.md. Latest agent-history retry,
UnlockDeferredScan follow-up-drain, Head scan-close aggregation, GPL composite shutdown and
inference/Lucene additions need integrated tests. Reviewer confirmed the prior scan races fixed
but found running EngineFutures onActualExit cleanup can escape the worker after publication;
root must settle that failure policy and test it. C1 remains incomplete; C2/D/E/F remain required.
