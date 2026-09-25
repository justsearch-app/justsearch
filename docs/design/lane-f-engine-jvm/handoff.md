# Lane F handoff: implementation orchestrator

Start with the [continuation brief](continuation-brief.md): the next two batches,
owner boundaries, acceptance endpoints and execution protocol. This handoff owns
current evidence; the brief does not narrow the remaining lane scope.

**Owner decision, 2026-09-24:** D1/D2 feature scope is unchanged. Finish the
D1-9 streaming correction and its proofs before further implementation. Stage
E now pairs only seven groups against `main`: quality plus workflow fixture;
search/agent response times under bulk indexing; indexing speed; memory and
an owner-duration no-crash soak; crash recovery without orphaned children;
graceful and forced hang; dead-Engine upgrade. Every other §16 condition is
one-sided D1/D2 feature acceptance, with no waiver. Minimum-spec, other-OS,
representative-change and collector comparisons are conditional on their
claim or a response-time failure; use G1 by default. The old main-development
re-cut trigger is retired; merge `origin/main` at each checkpoint. See
[design §16](design.md#16-what-must-be-measured-and-the-gate-for-flipping-the-default),
[E](stages/E.md), and the D1/D2 §6 maps.

## Current D1-9/D1-11 gap batch (2026-09-25)

**Mid-replay process cut, 2026-09-25 (uncommitted follow-on to `bfebf9186`).**
Independent read-only review found that WP1's seven transition points are all
before or after full candidate replay. A narrow strict-promotion callback now
reuses `MigrationTransitionBarrier` after the first actual candidate projection
UPSERT, before the next row, commit, verification and pointer move. A held
barrier there would own final mutation admission, so the eighth point requires
supervised self-exit. Marker-write failure propagates outside the broad replay
gap catch and aborts promotion. This avoids extending every replay context or
adding a journal. The first UPSERT is uncommitted at the cut; the assertion is
interruption within the loop and complete re-drain, not a durable partial prefix.
Focused replay/barrier tests passed at `tmp/3702-replay-cut-focused.txt` after
the initial test-only searcher-refresh red at `tmp/3701`. Installed distribution
and fixture compilation passed at `tmp/3704` and `tmp/3709`.

The first installed attempt `tmp/3707` hit a stale migration reached marker
and release file inherited from its copied seed; it was a fixture precondition
failure, not execution of the new cut. The corrected copy `tmp/3708` removed
only those two stale files. `tmp/3710-installed-replay-halt-trace.txt` exited 1
through the selected harness point. Its reached marker names B
`g-01a0d895-5d5a-739f-9eec-ee0ce7e59ee0`; independent inspection at the
cut found A active in SWITCHING and three scoped rows (two `PROJECTION`, one
`PROJECTION_SOURCE`). The separate JVM `tmp/3711-installed-replay-resume-trace.txt`
exited 0 with `INSTALLED_PROJECTION_REPLAY_PASS` and B VECTOR 10. A further
independent SQLite/state read found B active in IDLE, zero `switch_buffer` rows
and its recorded bulk `COMPLETE/settled`; no fixture JVM remained. The full
serial `spotlessCheck pmdAll test -PincludeStress=true :modules:ui:installDist
--max-workers=1 --continue` gate passed at `tmp/3712-replay-cut-integrated.txt`
in 22m25s: 358 tasks, 22 executed, 336 up to date. The affected Engine and
Indexer Worker XMLs have 409 and 838 tests respectively, with zero failures or
errors. `regen-all --check` passed at `tmp/3713`, docs validation at `tmp/3714`,
and both instruction sync/budget checks passed. Explicit `build -x test`
passed in 1m8s at `tmp/3718-replay-cut-compile.txt` (333 tasks); a final
`origin/main` merge returned already up to date. The previous `bfebf9186` hosted run
[36134737632](https://github.com/justsearch-app/justsearch/actions/runs/36134737632)
finished success in all 13 jobs, including Windows-native and system integration.
This new source still needs its own hosted run. D1-9
other crash cuts, D2-5 covering commits and D1-13's manifest model map remain.

**WP1/WP2 2a/WP5 boundary, 2026-09-25.** The migration transition harness
now shares one atomic reached/release protocol with operation faults. Its seven
named points preserve the live monitor, and a resumed before-SWITCHING hold
checks MIGRATING plus the observed source/building generation identities under
final mutation admission. The test-only monitor kill switch and pause-flag
fixture barrier are removed. Focused Worker and UI barrier tests, Spotless and
PMD passed; the first identity bootstrap repeat had 20/20 fresh XMLs with zero
failures (`tmp/3480-wp1-identity-repeat/`). After the identity recheck was
added, focused `DocumentIdentityBootImportTest` and
`CutoverRestartEvidenceTest` plus Spotless passed. A second 20-run repeat
passed after the identity recheck: 20/20 fresh `cleanTest --no-build-cache`
runs, 20 XMLs, zero test failures or errors
(`tmp/3483-wp1-identity-repeat/`). After the one-line environment funnel
correction, the exact-source repeat also passed 20/20 fresh runs, 20 XMLs,
zero failures and errors (`tmp/3492-wp1-identity-repeat/`).

The rebuilt installed distribution seeded standard-model A through the
`installer-before-marker` pointer/settings crash cut at
`tmp/3481-wp1-installer-seed.txt`: terminal assertion passed, the supervisor
recovered a counted Engine exit, and STOP 0 closed owned ports. Reusing that
retained A, the first `model-live-a-b` watcher/accepted-write run with the
named `migration-before-switching` hold passed at
`tmp/3482-wp1-installed-live-ab.txt`. The marker identified the live PID and
exact source/building pair; deletion and accepted write were visible on A;
held B had 302 completed units, zero failures and the in-place encoder
`RELOADING`; promotion produced settings revision 2 and a real B vector result.
Both runs used the distribution built after the WP1 source change. The old
fixture pause responses and timing assumptions are no longer its evidence.
The same current distribution seeded another standard-model A through
`installer-before-marker` (`tmp/3485-gap-installer-seed.txt`), then passed the
installed gap wait and approval at `tmp/3486-gap-current-installed.txt`:
one missing captured unit, A real vector search, exact gap hash approval,
same B promotion, duplicate acceptance with no second effect, and STOP 0.
The final terminal row retained `PROMOTED_WITH_GAPS` as a diagnostic.
The first 358-task full serial stress gate (`tmp/3487-gap-wp1-integrated.txt`)
completed with one failure: `SystemAccessFunnelTest` found the new
`KnowledgeServer` constructor's direct `System::getenv`. Its original XML is
preserved at `tmp/3487-failure-xml/`. The harness selector now uses the
existing process-global `SystemAccess.rawEnvVar`; focused audit, barrier and
Spotless checks passed at `tmp/3488-sysaccess-focused.txt`. A full exact-source
rerun passed at `tmp/3489-gap-wp1-integrated-green.txt`: 358 tasks, 21
executed, 337 up-to-date, exit 0, including stress tests and the formerly red
audit gate. The first red run remains diagnostic, not integrated acceptance.
`build -x test --max-workers=1` passed at `tmp/3490-build-no-test.txt`;
UI typecheck and its 491-file, 6601-test unit suite passed, with the latter
captured at `tmp/3491-ui-unit.txt`. The installed rounds preceded only the
environment funnel correction: `SystemAccess.rawEnvVar` delegates to the same
`System.getenv` lookup for these fixed nonblank keys. The focused funnel test,
full exact-source gate and final 20-run identity proof cover that source
change; the installed proof is reused under that unchanged environment
semantics, not represented as a post-correction installed rerun.

WP2 2a's `versionSource` gate first failed against the real `ui-settings`
1-versus-4 drift, then passed with `currentVersion: 4`, readable legacy 0–3,
and literal code-version checks on every eligible SQLite/JSON row. The
historical reconciliation string stays byte-for-byte stable because released
updaters compare it before readable versions; independent review found this
compatibility constraint. `check-store-recoverability` passed 47 rows and its
self-test passed 78 assertions; Rust updater tests passed 33 including the
legacy v1→v4 positive and changed-token negative. The release generator's
production wrapper still omits the optional inherited-register baseline and
belongs to WP2 before E. WP5 corrected the complexity labels and rollback
scope, added five descriptive paired development measures and the final PR
subsystem-map requirement. The two owner-requested agent-instruction checks,
runtime manifest closure and module-dependency check pass at this boundary.
The pushed checkpoint is `b971982c0`. Its hosted
[CI run 36087239338](https://github.com/justsearch-app/justsearch/actions/runs/36087239338)
completed with two red jobs. Windows-native, search-worker, app-ui and the
other fact lanes passed. Public claims failed only its
`check-ui-step-coverage` step because the new Library strict operation-history
read had no deterministic fixture route. The local correction maps a
schema-valid route and records the `library-gap-decision` screenshot step;
`check-ui-step-coverage`, 23 focused fixture tests and the measured capture
pass, with zero console errors, zero axe violations and no overflow at
`tmp/ui-shot-gap/library-gap-decision.measure.json`.

The hosted integration tier failed nine attempts of three bulk crash
scenarios: `bulk-partial-capture`, `bulk-state-before-binding` and
`bulk-promotion-before-terminal` each retried three times. Its saved XML at
`tmp/3499-hosted-integration/` shows every failure at one harness assertion:
the operation's `processing_history_counts_json` carried version 3 and an
empty `capturedGaps`, while `assertFinal` still required version 2. The
D1-9/D1-11 gap witness deliberately widened new operation evidence to v3;
the queue receipt remains v2. Local `tmp/3500-bulk-before.txt` reproduced the
same assertion against the installed distribution. The corrected assertion
requires v3 plus an exact empty gap set and retains the target, manifest,
revision and settlement digest checks. All three fresh installed scenarios
passed at `tmp/3501-bulk-after.txt` and `tmp/3502-bulk-*.txt`, each with
`BULK_FAULT_PASS`, STOP 0 and no remaining shared stack. This test-intent
correction follows the selected D1-9 gap-witness version, rather than
weakening the settlement proof. The UI and harness corrections require a
new hosted checkpoint; run 36087239338 is not acceptance. The next pushed
checkpoint `deffb59be` passed its [hosted system integration job](https://github.com/justsearch-app/justsearch/actions/runs/36089686525/job/107929222016),
build, Windows-native and unit jobs. Public claims failed only on the D1-16
disabled placeholders described below; that run is not a whole-CI pass.

The D1-9/D1-11 gap decision, WP1 transition barrier and WP2 register gate
share `KnowledgeServer` and the recoverability register. One integrated
checkpoint commit keeps every recorded tree buildable; splitting these
interdependent edits into item commits would leave unverified intermediate
source/schema combinations. This is the in-scope exception to D1 §4's
item-by-item commit plan. The accepted owner policy makes technical test
intent and this commit boundary the lane owner's judgment.

The gap runtime checkpoint is `b971982c0` after merging the owner-authorized escalation
policy `57fd2e1aa` and docs-only improvement package `59da2bc4d`; the gap
implementation and WP1/WP2 2a/WP5 boundary are committed and pushed. The
package changed no tested runtime source after the local/installed gap runs.
`origin/main` was fetched at this checkpoint and was already an ancestor
(`HEAD...origin/main` 198/0), so there was no new main merge commit. Both
`agent-instructions-sync --check` and `check-always-loaded-budget` passed.
The previous pushed `7f469d044` hosted run
[`36059941295`](https://github.com/justsearch-app/justsearch/actions/runs/36059941295)
passed system integration, Windows native and its other jobs, but search-worker
failed `DocumentIdentityBootImportTest.blueUidIsImportedBeforePausedMigrationReindexesIntoGreen`:
the manual pointer crash cut raced automatic cutover. The test-only monitor
hold preserves that cut. The new rename guard then exposed a paused-candidate
rename in the fixture. Under the owner's test-intent decision, it now performs
the production rename before migration, retains the durable UID/chunk/pointer
proof, and refuses a second rename during migration. The focused classes passed
at `tmp/3467`; the correction is in `b971982c0`. The hosted search-worker
job passed it, while the separate integration failures above remain open.

**D1-16 skeleton underway:** a dedicated 30-minute
`lifecycleIntegrationTest` task drives the installed migration restart
baseline through the existing `exerciseMigrationRestart` module. Six named
pending scenario records print `LIFECYCLE_PENDING` with owner and missing
proof; three require AI. The hosted Public claims suppression ratchet rejected
the initial six `@Disabled` placeholders. Those placeholders had no behavior
assertions; the replacement keeps every unmet clause visible without adding
suppressed tests. The corrected task passed two tests, zero failures/skips at
`tmp/3503-d1-16-pending-registry.txt`, with Spotless and PMD green; the local
suppression ratchet now passes with no new disables. The pending records are
open D1-16 feature acceptance, not green scenario claims.

The next local UI pre-merge pass found one transient retention violation:
`LibrarySurface.gapDecisionError` survived navigation. The surface now invalidates
stale gap reads and approvals on detach, releases their busy state, and ignores
completion from the prior view. The focused navigation regression passed 2/2,
`run-ui-web-gates` passed 27/27, typecheck passed, and the full UI suite passed
491 files/6,602 tests on the final UI source at `tmp/3507-ui-unit-final.txt`.
The earlier full pass is retained at `tmp/3505-ui-unit-current.txt`. The recaptured
`library-gap-decision` fixture at `tmp/ui-shot-gap/` has zero console errors,
zero axe violations and no overflow. This correction is local until its next
hosted run. The owner-requested `git merge worktree-escalation-policy` reported
Already up to date (`57fd2e1aa` is an ancestor); instruction sync and the
always-loaded budget checks both pass. `origin/main` was fetched and remains
an ancestor of this branch (200/0 at this boundary).

Checkpoint `5e547f9e0` was pushed after `deffb59be`'s system integration
completed. Its [hosted run 36091276954](https://github.com/justsearch-app/justsearch/actions/runs/36091276954)
passed system integration, build, Windows-native, app-ui and the other unit
jobs. Public claims failed only `dead-code` in the built-input kernel step:
Knip counted the new generated `OperationOutcomeView` type and schema as unused
because Library imported its schema from the leaf file. A fresh local Knip
report reproduced `dead-code/silent-growth` in the generated barrel and leaf
(`tmp/3518`–`tmp/3519`). Library now imports and explicitly types the strict
parse through the generated public barrel; the dead-code gate, typecheck, all
27 UI gates and the 491-file/6,602-test UI suite pass on that source
(`tmp/3520-ui-unit-generated-import.txt`). No baseline changed. New hosted
proof is due after this correction is pushed. Checkpoint `e6ec2c0a5` then
completed [hosted run 36092470563](https://github.com/justsearch-app/justsearch/actions/runs/36092470563):
system integration, build, Windows-native, app-ui and the other unit jobs passed.
Public claims failed only `contract-projection/consumer-broken`, because its
declared Library consumer requires a direct generated leaf import while Knip
requires the public barrel's type export to be consumed. Library now imports
the schema from the leaf and the type from the barrel. Local `contract-projection`
and `dead-code` gates both pass; this one-line correction is unpushed WIP.

**Next D1-9 source seam.** Independent read-only refutation rejected seeding
non-file projections from Blue: the authoritative project-memory store may hold
accepted facts absent from Lucene, whose unstored vectors cannot reconstruct
the full projection. D1-9 now records registered source enumeration before
candidate replay, including restart re-enumeration and an unreadable-source
gap. The existing `switch_buffer` remains the only candidate journal. This is
a design decision and source review, not implementation or executed proof.

**Uncommitted no-file implementation, 2026-09-25.** A versioned
`AcceptedProjection` carries stable source/document identity, monotonic source
revision, UPSERT/DELETE and canonical fields in the existing generation-scoped
`switch_buffer`. The Worker reserves index identity and exact revision/digest
fields; the in-process KnowledgeClient port applies an NRT projection on A and
conditionally journals it for B. `DURABLE` currently refuses pending D2-5's
covering-commit owner. Source owners may register before Engine start and stream
their complete live/tombstone set through a bounded sink; a candidate-scoped
source marker remains in that same journal until source enumeration completes
and promotion replay certifies it. On resumed BUILDING, source enumeration
reruns without repeating the file walk. Best-effort candidate replay now retains
its journal revisions until pointer commitment; its former cleanup could erase
a DELETE ordering witness before promotion. Focused queue/replay/Worker-port
tests, `build -x test`, PMD/Spotless, SSOT sync, recoverability and UI contract
gates pass at `tmp/3510`, `tmp/3522`–`tmp/3524`, `tmp/3526`–`tmp/3534`.
The affected Worker/Engine suites are still running at `tmp/3535`.
The affected Indexer Worker and Engine suites then passed at `tmp/3539` after
the enumerator fixture was corrected. Real A/B candidate replay and a
SQLite/candidate-writer reopen passed at `tmp/3537`–`tmp/3538`; concurrent
newer revisions and deletes survived the seed and replay. This remains local
implementation proof, not installed D1-9 acceptance.

**Pre-marker crash cut and source gap slice, 2026-09-25, uncommitted.**
An independent refutation found that marker-only recovery could lose a
registered source if the process died after the generation pointer but before
marker admission. New v2 bulk and v4 installer preparations freeze sorted,
bounded source IDs while preserving legacy absent payloads and plan hashes.
The in-process client projects the registered set through the additive
MigrationStartRequest, and the Worker writes it to a generation manifest
strictly before `state.json` names B. Recorded boot compares the accepted plan
with that manifest; legacy absence cannot replay over a new explicit set.
The final candidate journal witness names missing/incomplete source markers,
exact UPSERT revision/digest mismatches and unapplied DELETEs. Registered
source enumeration on restart may be incomplete for a recorded candidate and
then enters the existing hash-bound gap wait; native candidates fail closed.
Focused plan/handler/resolver, Worker-port, real replay and manifest tests
passed at `tmp/3545`, `tmp/3547`–`tmp/3550`; `tmp/3548` found and corrected
one PMD qualifier, while the other static checks passed. The first
`tmp/3544` plan run exposed an old v3 test assuming the automatic decoder
could not accept legacy v2 shape; the explicit v3-schema refusal assertion
now preserves the original security intent. Source-set implementation was
read-only reviewed for the pre-marker cut. The second refute-first review found
five coupled defects: frozen IDs dropped at recorded promotion and installer
pointer/settings recovery; v4 refused by the store marker and installer harness;
source completion reused by a later B; and a pre-replay gap hash which could
ask approval for healthy journaled projections. The correction carries IDs
through strict promotion and recovery, keys completion by generation and source,
allows v4 in the existing marker, and replays before computing a refreshed
candidate witness. The judgment is replay-first because journal presence is
not evidence of loss; any accepted incomplete marker remains bound to the
recomputed full hash. The first broad diagnostic run at `tmp/3553` passed
app-api, app-services, worker-core and worker-services, then failed two
indexer-worker mock fixtures lacking candidate manifests; it predated these
corrections and is not integrated proof. The fixtures now declare legacy
manifests. Focused promotion, v4 marker, source-lifetime, partial replay,
Indexer Worker and Engine owner tests pass at `tmp/3554`–`tmp/3559`; the first
`tmp/3554` run exposed a fixture trying to allocate B while an unretired
predecessor still occupied capacity, and the first `tmp/3558` run exposed old
five-argument migration mocks. Both fixtures were corrected to exercise the
new protocol. Repository Spotless and PMD passed at `tmp/3557` before the
latest Engine test changes. The requested escalation policy commit
`57fd2e1aa` was already an ancestor; instruction sync and always-loaded
budget checks both passed on this branch. This is still not D1-9 acceptance:
exact-source integrated, installed no-file restart,
source-gap approval and final-fence races, positive cancel/abandon, and D2-5
covering durability remain open.

**Exact approved-row correction, 2026-09-25.** The first full serial gate at
`tmp/3560` stopped at an obsolete unreferenced-method audit; both legacy
overloads were retired. Focused exact replay and gap-reason regressions passed
at `tmp/3561`–`tmp/3564`. The first partial-source fixture at `tmp/3565`
failed on a null mocked Green field reader; its corrected real partial-source
scenario and the replay classes passed at `tmp/3566`. A full serial gate
started at `tmp/3567` but was deliberately stopped after independent review
proved a changed journal row could inherit approval when its visible unit and
reason stayed the same. The selected correction adds optional immutable row
evidence to each candidate gap, changes the hash domain only for evidence-
bearing lists, and retains legacy hashes and safe reason codes. The reviewer
refuted synthetic unit IDs and reason suffixes because they disturb unit
reconciliation or the durable reason contract. The canonical gap schema and
UI projection were regenerated at `tmp/3568`. The first focused run at
`tmp/3569` exposed released two-field gaps failing the store's strict
missing-creator-property reader; a local Gap deserializer now preserves strict
field/type checks while accepting that released shape. Focused API, Engine and
Worker tests passed at `tmp/3570`; repository Spotless/PMD passed at
`tmp/3571`, and generated-file/document checks at `tmp/3573` plus the named
instruction checks passed. The first exact-source full serial gate at
`tmp/3572` ran 16m16s and failed one of 830 Indexer Worker tests:
`CutoverRestartEvidenceTest.recordedGapWaitRetainsBlueAndGreenBeyondSwitchingDeadline`.
Its original XML is retained at `tmp/3572-failure-xml/`. The fixture required
approval before any call to the live cutover, contradicting D1-9's selected
replay-first gap discovery. The revised test now observes one or more
pre-approval replay attempts with no pointer commitment, then retains its
two approved cutover attempts, late-gap restoration and eventual promotion.
The focused class passed at `tmp/3577`. The exact-source full serial
stress, repository Spotless and PMD rerun passed at `tmp/3578` (355 tasks,
exit 0). UI typecheck passed at `tmp/3575`; its 491 files and 6,602 tests
passed at `tmp/3576`. Store recoverability (47 rows), UI coverage, module
dependency, runtime config, schema generation and canonical-doc checks also
passed. `tmp/3560`, `tmp/3567` and `tmp/3572` remain diagnostic red/stopped
runs. `build -x test` and `:modules:ui:installDist` passed at `tmp/3579`;
the installed behavioral results follow, and hosted proof remains due.
All six fresh installed standard-model cuts, `installer-before-marker`,
`installer-before-arm`, `installer-before-pointer`,
`installer-pointer-before-settings`, `installer-settings-before-publication`
and `installer-before-receipt`, passed at `tmp/3580`–`tmp/3585`: each had one
counted transient restart, the same operation completed, pointer and settings
named B, real search found both indexed files, and STOP 0 closed owned ports.
The no-file source and gap rounds, and hosted proof remain.
This exact source-set checkpoint is `331a860bc` on `codex/lane-f-pr1`.
The installed runs above used its source before commit; no source changed
between those runs and the commit. Hosted CI 36104445880 completed for the
`ee8516cf7` evidence-note tip: system integration, build, Windows-native,
search-worker, app-ui and all other jobs passed except Public claims. That
job failed its dead-code gate because
the preceding direct generated-module import left the same schema's generated
barrel export unused; its contract-projection gate had rejected the earlier
barrel import. The follow-on local correction makes that gate resolve only
named barrel imports actually re-exported from the record, restores the
existing barrel import and checks both declared and undeclared consumers.
The import parser's 24 tests, both governance gates, UI typecheck, generated
file check and diff check pass locally. This correction is not in that hosted
run and will be pushed after the follow-on integrated local gate.
The next local D1-9 proof exercises an actual SQLite candidate journal and
Lucene source writer: a refused B transfers a newer projection and delete to
A, empties only B's journal, and abandons/prunes B. The existing pre-pointer
approved-row test and two new strict replay crash-cut tests cover retention,
exact post-pointer retirement without reapplying, and retry of interrupted
post-pointer removal. Both focused Worker classes and Worker Spotless/PMD
passed. These are local component proofs; installed no-file source and gap
approval remain due.
The production `KnowledgeServer.candidateJournalWitness()` test now replaces
one missing projection with a different accepted revision and payload under
the same unit/reason, then proves its `evidenceId` and full gap hash change;
the focused Worker class passed. This joins the Engine's approval-revocation
test to the actual Worker witness, while the installed cross-process decision
still needs proof.
Independent D1-9 review found that production refusal marked B for deletion
but did not prune it before restart. The first same-process correction and
tests passed at `tmp/3595`–`tmp/3597`; review then refuted it as reboot proof
because the cleanup flag is volatile and best-effort prune gives no deletion
witness. The follow-on uses the durable recorded operation key and source to
retire only exact B under generation state control. A fenced refused boot now
opens A writable solely for accepted-mutation replay while new claims remain
denied. Focused Worker recovery, exact partial-delete and real refused-boot
tests pass at `tmp/3599`–`tmp/3602`. The injected deletion-failure retry does
not restart until B is absent. `tmp/3598` was a full passing serial stress gate
before this latest boot correction; its exact follow-on `tmp/3603` passed all
196 Gradle tasks under serial stress in 20m 59s. The first static pass at
`tmp/3604` exposed PMD's now-unused volatile cleanup flag. Removing that
field and its test-fixture writes changes no branch or runtime effect. The
replacement `spotlessCheck pmdAll --continue` gate passed at `tmp/3605`, and
`build -x test` passed at `tmp/3606`; the affected Worker class passed at
`tmp/3607` after that source cleanup. `origin/main` was fetched at this
checkpoint and has zero commits ahead of the branch, so no merge was needed.
Terminal refusal can currently precede physical retirement in the coordinator;
if that happens before a process crash, `openRecords()` loses the operation
key and the next boot may classify an orphan as Native/FENCED. This is the
next D1-9 crash-ordering correction, not a completed recovery claim. That
checkpoint was committed and pushed as `f2503e7ce` to PR727; hosted
[CI 36110298622](https://github.com/justsearch-app/justsearch/actions/runs/36110298622)
is still running. Do not push the following local correction until it finishes.

The local follow-on keeps a durable refused bulk row open until Worker's
existing attachment reports exact B absent and the B-scoped journal empty.
The caller still observes immediate cancellation. The first Engine fixture
run (`tmp/3608`, XML preserved in `tmp/3608-failure-xml/`) reproduced five
old tests that assumed cleanup had already happened; its physical witness
fixture and a new crash/reopen negative passed all 16 focused tests at
`tmp/3609`. Worker/Core proof initially failed because the exact manager
witness threw while B was still the building pointer (`tmp/3610`, XML
preserved); it now returns incomplete for that state. Focused real Worker
boot and exact-retirement tests pass at `tmp/3611`, and Spotless/PMD pass at
`tmp/3612`. The full serial stress gate passed at `tmp/3613`: 196 tasks,
eight executed, 188 up-to-date, in 21m 7s. A subsequent exact manager test
reproduced the stale `previous_generation=A` alias after B deletion, which
blocked the next recorded candidate (`tmp/3617`, XML preserved). Exact
retirement now clears that alias only after B's original and marked
representations are absent, and certifies ambiguous post-move pointer writes.
The focused capacity regression and both pointer crash cuts pass at
`tmp/3619`–`tmp/3620`; `tmp/3618` was a transient duplicate local variable
compile error before this correction. The full `tmp/3613` result predates
only this exact retirement pointer change and the register-only correction.
The pushed `f2503e7ce` [hosted run](https://github.com/justsearch-app/justsearch/actions/runs/36110298622)
completed: system integration, Windows native, build, UI and search Worker
passed; Public claims alone was red at `operation-surface/undeclared-surface`.
The completed-job log
is `tmp/3614-public-claims-job.txt`, and its uploaded SARIF is in
`tmp/3615-governance-health/`. The three source facts are the accepted gap
plan resolver's operation row read, Worker's gap witness contract, and
`KnowledgeServer`'s candidate gap producer. They are projections/consumers
of the existing operation outcome and row, not new authorities. The exact
register entries are locally corrected in
`governance/operation-surfaces.v1.json`; all three register-family gates pass
at `tmp/3616`. This correction still needs hosted proof.
The real Engine registered-source fixture now holds an old no-file snapshot
while B builds, accepts a newer update, delete and addition through the
public port, checks A and promoted B, and reopens B on a third boot. The first
attempt (`tmp/3621`) correctly refused NRT's missing durable covering commit;
an initial NRT seed then exposed a restart fingerprint error (`tmp/3622`), so
the live mutations run in the second epoch. Shared search markers made an
initial assertion ambiguous (`tmp/3623`); distinct markers fixed it. The
two-epoch replay passed at `tmp/3624`–`tmp/3625`. Reopening B first failed
with Windows `InvalidPathException` (`tmp/3627`, XML retained) because file
identity boot import normalized the reserved `projection:` ID as a path.
The focused importer regression reproduced that exception at `tmp/3628`.
The importer now excludes source-owned projection IDs from file identity
rows and counts; focused importer and real Engine third-boot tests pass at
`tmp/3629`. The current no-file test uses NRT because the D2-5 durable
covering-commit contract is still outstanding. This is local source proof,
not the installed standard-model source-owner round.
The exact corrected tree passed the full single-worker Gradle gate at
`tmp/3631-d1-nofile-integrated.txt`: 358 tasks, exit 0 in 25m 27s, with
stress-enabled `test`, Spotless, PMD and `:modules:ui:installDist`.
`tmp/3630` was a command-selection error (`stressTest` is not a task); the
repository's stress switch is `-PincludeStress=true`. This gate covers the
refusal terminal witness, stale alias retirement, register correction,
no-file source replay and third-boot importer fix together. Hosted and
installed registered-source proof remain open.

The `3e39e3c9f` checkpoint was pushed to PR727 after the full `tmp/3631`
gate and register-family gates at `tmp/3632`; instruction sync/budget checks
passed at `tmp/3633`–`tmp/3634`. `origin/main` had zero commits ahead.
[Hosted CI 36116815327](https://github.com/justsearch-app/justsearch/actions/runs/36116815327)
completed: Public claims, system integration, build, UI, search Worker and
other fact lanes passed. Windows-native alone failed two initial modes of
`WindowsParserContainmentTest`. The completed job log is `tmp/3646` and its
XML is under `tmp/3649-windows-native-artifact/`: both `recycle` and `timeout`
spent the fixture's 20-second response clock before a native PID was written;
later modes passed on the same runner. Local focused Windows execution passed
without a cold load (`tmp/3648`). Raising the slow native fixture from six to
25 seconds reproduced the old-budget failure at `tmp/3651` (XML preserved).
The new test-only 60-second sandbox response budget and 120-second JUnit
limit retain the observed-live-child, timeout, recycle and child-exit checks;
the five focused Windows cases pass at `tmp/3652`. The test intent remains
containment after a live native descendant, with room for hosted cold startup.
This is the owner policy's technical judgment, not a waived Windows gate.
The exact follow-on source also passed `spotlessCheck pmdAll build -x test`
at `tmp/3656` (333 tasks, exit 0); the installed fixture itself compiled
against distribution jars at `tmp/3654` and passed at `tmp/3655`.
The subsequent `e046fc340` checkpoint completed
[hosted CI 36119660449](https://github.com/justsearch-app/justsearch/actions/runs/36119660449)
with every job green, including system integration, Windows-native and Public
claims. This establishes the hosted gate for that exact checkpoint; the newer
registered-source gap test and installed fixture are a local follow-on batch.

The installed registered-source round compiled
`scripts/supervisor-conformance/InstalledProjectionRound.java` against the
`3e39e3c9f` `:modules:ui:installDist` jars. It copied only data, UI settings
and index from retained standard-model A under `tmp/3580` into a fresh
`tmp/3653-installed-projection/`, while the real model assets remained at
their retained paths. The fixture registers a source through
`EngineRoot.registerProjectionSeedSource`, freezes its identity in accepted
bulk preparation, holds old enumeration during B, accepts newer update,
delete and addition, promotes B and reopens it. `tmp/3655` exits 0 with
`INSTALLED_PROJECTION_A_VECTOR 10`, `B_VECTOR 10` and final PASS; both query
responses are checked for `SearchTrace.effectiveMode=VECTOR`. The exact B
pointer is IDLE, the operation row is COMPLETE, and no fixture JVM remains.
The launcher used `javac -cp 'modules/ui/build/install/ui/lib/*' -d
tmp/3653-installed-projection/classes
scripts/supervisor-conformance/InstalledProjectionRound.java`, then `java`
with `-cp 'tmp/3653-installed-projection/classes;modules/ui/build/install/ui/lib/*'`,
data/index/models-root/watched-root/query-file arguments, and JVM data/index
properties. The citation scorer model path came from copied A's generation
manifest as `-Djustsearch.citation.scorer.model_path=<parent of model ID>`;
this preserves the active generation's exact model identity outside the
worktree. The retained seed directory is `tmp/3580-installer-before-marker`;
only its data, settings and index were copied, never mutated. An independent
read of `tmp/3653` found state `IDLE`, active B equal to the operation key,
the B manifest source set `installed-fixture`, and its SQLite operation row
`COMPLETE/settled`. The B manifest carries no `models` map despite the traced
real vector search on third boot; D1-13's per-generation model-identity
acceptance must decide whether same-model bulk should persist that map.
Earlier `tmp/3638`, `tmp/3640` and `tmp/3642` were fixture configuration
negatives: the standalone root initially omitted the models root and the
active generation's citation model path. The selected generation manifest
named that citation path outside this worktree; providing the same exact
path as a JVM config input resolved the mismatch. These negatives are not
runtime acceptance claims. NRT no-file writes still need D2-5's durable
covering-commit path; source-gap crash cuts remain open. The new real Engine
`incompleteRegisteredSourceWaitsOnAForExactRecordedApproval` test passed at
`tmp/3661` after a first assertion expected the wrong terminal state at
`tmp/3659` (XML retained). The selected protocol ends the approved bulk row
as `FAILED/PROMOTED_WITH_GAPS`, an explicit diagnostic; the distinct HIGH-risk
webview `core.accept-gaps` operation is `COMPLETE`. The test verifies an
incomplete source marker and exact gap hash, A pointer retention, the recorded
decision and exact B promotion. The installed extension compiled at
`tmp/3662-installed-projection-gap-compile.txt` against the same distribution
jars. Its fresh copied standard-model A at `tmp/3662-installed-projection-gap/`
passed `tmp/3663-installed-source-gap-trace.txt`: A VECTOR 10 initially,
A VECTOR 1 during `awaiting_acceptance`, B VECTOR 10 after the third boot,
then `INSTALLED_PROJECTION_GAP_PASS`. The source emits one row and throws;
the candidate remains on A until the distinct approved decision, whose row
and the terminal bulk diagnostic survive the third boot. Disk state is IDLE
with exact B active and `projection_source_ids:[installed-fixture]`. An
independent SQLite read after fixture exit found `core.bulk-reindex` as
`FAILED/settled/PROMOTED_WITH_GAPS` and `core.accept-gaps` as `COMPLETE`. The
test-intent correction follows the D1-9 gap decision, with no production
relaxation. This proof does not close source-gap crash cuts, positive
cancel/abandon, D2-5 durable covering commits or D1-13 model identity: the
B manifest still has no `models` map.
The exact follow-on tree passed the full serial `spotlessCheck pmdAll test
-PincludeStress=true :modules:ui:installDist --max-workers=1 --continue`
gate at `tmp/3665-source-gap-integrated.txt`: 358 tasks, two executed, 356
up-to-date, exit 0 in 15m43s. The app-engine XML reports all three
`RecordedBulkEngineRestartTest` cases passed, including the new source-gap
round. The focused test/static gate passed earlier at `tmp/3664`. `origin/main`
had zero commits ahead at the checkpoint fetch.
The `2f4053cbb` source-gap proof checkpoint was pushed to PR727; its hosted
CI run [36122603064](https://github.com/justsearch-app/justsearch/actions/runs/36122603064)
completed with every job green, including system integration, Windows-native
and Public claims. The enumerator fence below is a later local source change.

**Current source-gap handoff, 2026-09-25.** A second Engine
handoff while the bulk row was `awaiting_acceptance` initially failed because
the fixture closed a live index owner (`tmp/3666`, XML retained). Reusing the
production handoff/quiescence contract reached the approval path but the old
gap hash returned `GAP_LIST_STALE` (`tmp/3667`–`tmp/3668`, XML retained).
The independent reviewer confirmed this is required: source re-enumeration
replaces the marker with a new revision, and approval binds exact row versions.
The same review found a real race: the prior wait remained visible while the
new enumerator could still write that journal outside mutation admission.
`withCandidateGapAcceptanceFence` now refuses approval until enumeration is
done and no longer running, under its existing runtime lock before the final
mutation fence. The real Engine test deterministically holds the third-boot
source, sees `GAP_ACCEPTANCE_UNAVAILABLE` with A retained, releases it, then
requires old-hash `GAP_LIST_STALE` and a new decision. Its old and new source
gaps have the same unit and reason but distinct evidence IDs. Focused test
`tmp/3671` and Spotless/PMD plus distribution `tmp/3672` pass. This is the
one-line owner judgment: preserving the old hash would let an approval cover
a newly written physical marker, so the fresh decision is required.

The rebuilt installed `ui` jars and a fresh copy of retained standard-model
A at `tmp/3673-installed-gap-restart/` passed
`tmp/3674-installed-gap-restart-trace.txt`: A VECTOR 10 initially, A VECTOR 1
after the gap-wait Engine handoff, B VECTOR 10 after a fourth boot, then
`INSTALLED_PROJECTION_GAP_RESTART_PASS` with exit 0. Independent disk and
SQLite reads found IDLE, exact B active, `installed-fixture` source identity,
bulk `FAILED/settled/PROMOTED_WITH_GAPS`, old decision
`FAILED/GAP_LIST_STALE` and new decision `COMPLETE`. The B manifest still
lacks a `models` map. This is graceful Engine handoff evidence, not a forced
process crash cut. The broad gate first exposed a Worker mock that omitted the
settled-enumerator precondition (`tmp/3675`, XML in `tmp/3675-failure`); the
focused correction passed at `tmp/3676`. A second broad run exposed a
document-identity crash-cut fixture closing its serving view before a cancelled
cutover thread exited (`tmp/3677`, XML in `tmp/3677-failure`). It now waits for
that exact thread to leave before closing, preserving the UID and pointer-cut
assertions; focused proof passed at `tmp/3678`. The corrected 358-task serial
stress, Spotless, PMD and installed-distribution gate passed at `tmp/3679`
(15m26s, exit 0). A new copy of the retained standard-model A at
`tmp/3680-installed-gap-restart/` passed `tmp/3681` with A VECTOR 10, A VECTOR
1 after handoff, fourth-boot B VECTOR 10, and exact B IDLE on disk. Its SQLite
rows show bulk `FAILED/settled/PROMOTED_WITH_GAPS`, stale decision
`FAILED/GAP_LIST_STALE` and fresh decision `COMPLETE`. This second run waits for
the restarted enumerator to settle before submitting the old hash, avoiding an
observation race in the fixture. The owner policy branch `57fd2e1aa` was
already an ancestor; its merge command returned up to date. Both
`agent-instructions-sync --check` and `check-always-loaded-budget` passed.
Hosted proof for this new guard, forced source-gap cuts, positive
cancel/abandon and D2-5 durable writes remain open.

**Forced source-gap process cut, 2026-09-25, local follow-on.** The installed
fixture now has `--gap-halt` and `--gap-resume` modes. The first process writes
only a fixture key/hash/source marker, forces that file, then calls
`Runtime.halt(73)` at `AWAITING_ACCEPTANCE` while A still answers VECTOR and the
pointer still names A. A separate JVM registers the same source identity,
re-enumerates it, refuses the pre-crash hash as `GAP_LIST_STALE`, records a fresh
HIGH/DURABLE approval, promotes exact B, and reopens B on a fourth boot. The
first dry run (`tmp/3683`–`tmp/3684`) proved the pointer and decision path but
the fixture closed Engine with live recovered admission; its refusal was
retained. The corrected fixture uses the existing quiescent handoff after
promotion. A new copy of retained standard-model A at
`tmp/3685-installed-gap-halt/` passed: `tmp/3685-installed-gap-halt-trace.txt`
exited intentionally with code 73 after A VECTOR 10, and
`tmp/3686-installed-gap-resume-trace.txt` exited 0 with A VECTOR 1, reopened B
VECTOR 10 and `INSTALLED_PROJECTION_GAP_RESUME_PASS`. Independent disk/SQLite
inspection found IDLE, active `g-<bulk key>`, the bulk
`FAILED/settled/PROMOTED_WITH_GAPS`, old decision `FAILED/GAP_LIST_STALE` and
new decision `COMPLETE`. This is an abrupt fixture JVM cut; other D1-9 crash
cuts and D2-5 durable writes remain open. The
`724fb7ae7` hosted [run 36127904750](https://github.com/justsearch-app/justsearch/actions/runs/36127904750)
completed with every job green, including system integration, Windows-native,
Public claims and both unit lanes. The next source batch remains local until
its own coherent gate.

**Positive pre-pointer cancel, 2026-09-25, local follow-on.** The real Engine
test `RecordedBulkEngineRestartTest.cancellingAcceptedBulkRetiresItsCandidateAndReopensOnlyA`
now admits an accepted bulk under its cancellable owner, seeds a searchable A,
creates B, cancels before pointer commitment and reopens twice. It passed at
`tmp/3690-positive-cancel-seeded.txt`: the recovered row is `CANCELLED`, A is
still searchable, B is physically absent and the previous alias is clear. The
installed standard-model `--cancel` round passed at
`tmp/3692-installed-cancel-trace.txt` with A VECTOR 10 before cancellation,
A VECTOR 10 after recovered B retirement, and
`INSTALLED_PROJECTION_CANCEL_PASS`; its runner checked the same row, pointer,
alias and B absence. The earlier local red fixtures in `tmp/3687`–`tmp/3689`
incorrectly awaited successor-owned cleanup in the old Engine, read before
asynchronous serving publication, then omitted the A seed; each was corrected
before the passing run. The broad 358-task stress/static/distribution gate passed
at `tmp/3693` (14m44s, exit 0). A subsequent proposed held-enumerator assertion
failed in `tmp/3694-failure` because the first Engine intentionally hands off
after creating B and before the successor begins source enumeration.
`RecordedIngestionCoordinator.finishBulkStart` checkpoints BUILDING, and
`driveBulk` will not advance that started bulk on the same physical attachment.
The corrected fixture asserts exact B and A's still-committed pointer at the
restart request instead; this is the actual stable precommit boundary. This is
the one-line owner judgment for the failed assertion, with its XML retained.
The corrected focused Engine test passed at
`tmp/3696-positive-cancel-precommit-pointer.txt`. The matching installed
standard-model round compiled against the distribution at `tmp/3697`, then
passed from a fresh retained-A seed at
`tmp/3699-installed-cancel-precommit-pointer.txt` (A VECTOR 10 before and after,
exact B retired). An independent disk/SQLite read found IDLE with only source A
under `index/indices`, no previous alias and the exact bulk key
`CANCELLED/settled` with reason `cancelled`. The first Java launch used the fixture's unqualified class
name and exited before any Engine started (`tmp/3698`); the corrected launch
used its package-qualified name. Recovered cleanup currently waits for the existing
120-second maintenance tick, so the focused run took 2m19s. D1-9 sets no
cancellation deadline. The independent review refuted an immediate startup
tick because it can close the producer before serving publication; owner
judgment is to retain the proven ordering for this checkpoint and account for
the observed latency in any timed recovery claim. Other D1-9 crash cuts and
D2-5 durable writes remain open. The full gate at `tmp/3693` ran on unchanged
production source before the extra pointer assertion; the focused test and
installed round cover that assertion. Hosted proof for this local follow-on
remains due.

The review also confirmed no production caller yet registers a no-file source.
Its suggestion to wire project-memory into the shipped composition now conflicts
with [the C2 consumer record](evidence/C2/project-memory-consumer.md#6-d2-durable-deletion-is-lane-f-work):
project-memory (955) lands after lane F and consumes this port then. The D1-9
registered-source contract still needs an installed fixture running against
the built distribution with a real source owner; these component tests do not
stand in for that proof. This is the one-line ownership judgment for the
independent review, not a waiver of the no-file acceptance item.
Reusing `tmp/3580`'s retained standard-model A, the fresh installed live
`model-live-a-b` round passed at `tmp/3586`: A answered a real vector query;
the named before-SWITCHING hold captured the exact source/building IDs; a
watcher deletion and accepted write became visible on A; B promoted with
settings revision 2, ten real vector hits and both accepted effects; STOP 0
closed owned ports. This proves the installed file-mutation and model path on
this source, not the still-missing installed non-file source proof.

The recorded gap decision keeps the bulk row nonterminal in
`COMPLETE_WITH_GAPS`, exposes the current candidate gap-list hash on the outcome
wire, requires a separate HIGH/DURABLE webview `core.accept-gaps` preparation,
and resumes the same Green after exact approval. Immutable captured settlement
and the effective candidate gap witness have separate store projections;
terminal recovery reads the latter. An in-place wait parks B and recomposes A,
and the Worker fences approval after Green's accepted queue drains and its
producer pauses. Recovery retains the visible gap wait. The initial failing
owner regression is at `tmp/3399`; focused Engine, Worker, store and schema
checks passed at `tmp/3450`, `tmp/3453`–`tmp/3455`.

The serial runs then exposed catalog/validator fixtures and the old terminal
recovery test (`tmp/3456`), the paused rename (`tmp/3460`), and the UI catalog
count (`tmp/3468`). All were corrected while retaining their intended
guarantees. The complete serial `test --max-workers=1` gate passed at
`tmp/3471-gap-full-test.txt` (10m54s). Failure XML is preserved under
`tmp/3456-app-services-test-results/`, `tmp/3460-indexer-worker-test-results/`
and `tmp/3468-ui-test-results/`. `build -x test` and UI `installDist` passed at
`tmp/3473`; Spotless, PMD and the selected stress test passed at `tmp/3478`.
`regen-all --check --except notices` passed at `tmp/3461`; UI typecheck/unit
passed at `tmp/3462`–`tmp/3463`. Earlier live Head capture had 244 routes;
five affected Library captures reported no axe violations or console errors.

A fresh installed standard-model A passed installer marker recovery at
`tmp/3476-gap-installer-baseline.txt`. On that retained A,
`tmp/3477-gap-installed.txt` passed: one captured file disappeared after
closure; the row waited as `COMPLETE_WITH_GAPS`, the wire answered
`running/awaiting_acceptance` with one hash-bound gap, real vector search
answered on A, separately approved accept-gaps promoted the same B, duplicate
acceptance had no second effect, and STOP 0 closed owned ports. The committed
pointer's terminal row is `FAILED` with `PROMOTED_WITH_GAPS`, preserving the
diagnostic. The first fixture run `tmp/3475` timed out because it expected the
durable bulk phase to change from `settled`; the retained DB/pointer held the
correct wait. The wire projector owns `awaiting_acceptance`, and the corrected
fixture passed. Dev preflight's standalone Worker executable check is stale
against Lane F's library Worker; the UI dist and installed harness are the
applicable executable proof. Installed in-place A recovery, no-file projection,
positive cancel/abandon, gap crash cuts and hosted proof remain open. Do not
count this WIP as D1-9 completion.

PR727's last fully hosted runtime checkpoint is `fc5b444d6`; the following
chronology retains earlier failures and corrections. The 358-task local integrated gate passed at
`tmp/3358-d1-file-mutation-integrated.txt`. Its hosted run
[`36039474659`](https://github.com/justsearch-app/justsearch/actions/runs/36039474659)
finished: system integration, Windows native and every other job passed except
Public claims. That job found `jobs.db` schema v21 in code but v20 in the
recoverability register. The local register correction passes
`check-store-recoverability.mjs` and its 77-assertion self-test; it is not yet
pushed or hosted.

The installed standard CPU seed passed at `tmp/3359` and `tmp/3361`. Its first
watcher A/B run (`tmp/3360`) raced migration promotion; the next fixture paused
MIGRATING before watcher changes. That run (`tmp/3362`) proved the watcher
addition and deletion in serving A, then exposed a real failure in the
recorded ingest child: `INGEST_ENUMERATION_FAILED` because streaming recorded
members had no source hash for candidate-scoped journal admission. The source
failure is in the private run's `operations.db` and Engine log. A focused
regression failed at `tmp/3363`; the queue now hashes source bytes before its
atomic recorded admission, and the regression, related recorded walk tests,
PMD, Spotless and installed distribution pass at `tmp/3364` and `tmp/3365`.
A fresh seed `tmp/3366` passed, but the next watcher attempt (`tmp/3367`)
reached SWITCHING before the pause was acquired. A bounded diagnostic probe
then acquired the pause at `tmp/3369` and confirmed the watcher deletion in A.
The recorded ingest admitted a hash-witnessed scoped queue/journal pair but
stayed PENDING: the queue's old claim guard denied any source hash on a
streaming walk. Its focused regression failed at `tmp/3370`; the guard now
validates that hash and retains the recorded owner decision. Focused queue and
walk tests, PMD, Spotless and rebuilt distribution pass at `tmp/3371` and
`tmp/3372`. A fresh installed combined run remains required. Do not count
`tmp/3362` or `tmp/3369` as passing combined acceptance. D1-9's non-file,
cancel, gap and crash-cut acceptance remains open.

The refute-first review of that streaming correction found a further H1→H2
failure: a changed source kept H1 in the recorded member and candidate UPSERT,
so repeated claims could only defer. The current uncommitted correction uses
one claim-owned SQLite transaction to record H1's stale result, replace the
streaming member with a fresh revision/H2, replace the exact candidate UPSERT,
and advance walk revision. Captured plans remain immutable. Focused queue and
extractor tests, a post-ledger journal-failure rollback control, captured-plan
control, PMD, Spotless and rebuilt distribution pass at `tmp/3378`; the prior
focused passes are `tmp/3376` and `tmp/3377`. The installed A/B fixture now
creates its migration load only after A serves a real vector result: `tmp/3375`
timed out on that initial query because 1200 load files were created earlier.
Fresh installed and integrated proof of this correction remains open.

`tmp/3379` passed a fresh installed pointer/settings seed. The first A/B run
`tmp/3380` held MIGRATING and admitted the watched addition with an exact
candidate UPSERT, but timed out on A visibility: the file remained PENDING
behind 995 of the fixture's 1200 build-load jobs. This is queue ordering under
fixture load, not a demonstrated production refusal. The next fixture uses
300 freshly added load files after A's vector check and a 240-second watcher
visibility bound; another clean installed seed and A/B run are in progress.

That `tmp/3381` seed passed. `tmp/3382` then proved the watched addition in
A, its scoped UPSERT, watched deletion absent from A, and a recorded ingest
accepted and text-searchable in A. Its held cut failed a fixture assertion:
the physical free-device-memory decision selected `IN_PLACE`, but the fixture
assumed `BESIDE` whenever its forced-floor flag was false. The status
projection exposes the actual `encoders.mode`; the fixture now asserts that
mode and applies the corresponding A vector/text expectation. B promotion and
settlement remain to be rerun; `tmp/3382` is not full installed acceptance.

The clean standard-model seed `tmp/3383` passed. The corrected A/B run
`tmp/3384` passed end to end: A real vector before B, MIGRATING pause,
watched addition's scoped UPSERT and A text visibility, watched deletion
absent from A, recorded ingest accepted and text-searchable in A, a held
IN_PLACE cut with 302 completed units and zero failures, then B pointer and
settings revision 2 with a real B vector result. STOP 0 closed the owned run.
This proves the combined installed file-mutation path on the local distribution;
D1-9's non-file, gap, cancel and crash-cut acceptance remains open, as do the
full integrated and hosted gates for this correction.

The first complete 358-task gate `tmp/3385` failed six tests in three modules:
five 30-second JUnit timeouts and one parser timeout under concurrent module
load. Its original XML is retained in `tmp/3385-test-results/`. The same six
subjects passed at `tmp/3386` with one Gradle worker and unchanged compiled
source. A complete single-worker gate then passed at
`tmp/3387-d1-streaming-integrated-serial.txt`: `spotlessCheck pmdAll test
-PincludeStress=true :modules:ui:installDist --continue --max-workers=1`
finished with 358 actionable tasks and exit 0. This is local integrated proof
for the D1-9 correction committed as `fc5b444d6`. The concurrent-suite failure and
its original XML remain recorded above. Hosted proof requires a coherent
checkpoint; D1-9's non-file, gap, cancel and pointer/settings crash-cut
acceptance remains open.

After checkpoint `fc5b444d6` was pushed, hosted run
[`36055201547`](https://github.com/justsearch-app/justsearch/actions/runs/36055201547)
passed every job, including system integration, Windows native, Public claims,
build and module tests. `origin/main` was already an ancestor at this checkpoint.
The next local D1-9 regression, in `SwitchBufferStrictReplayTest`, proves
that refused-candidate cleanup removes a scoped UPSERT only after its exact
accepted source projection is present on A, while retaining a foreign candidate.
The focused class passed locally. A second local regression checks scoped
prefix and collection deletes transfer to A and commit before their exact
journal versions retire. These tests and the owner-recut wording correction were
not part of that hosted run. Gap acceptance,
positive installed cancel/abandon, no-file projection and D1-9 crash-cut proof
remain open.

The current-revision installed `installer-before-marker` crash cut at `tmp/3388`
timed out waiting for its marker at 170 seconds. The correct marker appeared at
20:44:00 UTC, concurrent with timeout, after cold standard CPU model composition;
the fixture stopped its owned run with ports closed. This is a fixture observation
timeout, not passing recovery proof. The fixture now allows 240 seconds to find
the marker and 480 seconds for the whole fault/recovery path; Stage E retains its
separate response-time requirements. The fresh installed rerun passed at
`tmp/3389-installer-before-marker-current.txt` on the unchanged installed runtime
`fc5b444d6`: the marker bound to the accepted operation, an identity-checked
Engine kill consumed one counted restart, the successor completed the same row
with matching pointer and settings witness, both watched files were searchable,
same-key replay remained idempotent, and STOP 0 closed the owned ports. The
remaining five composite crash cuts are still required.
The next `installer-before-arm` cut also passed at
`tmp/3392-installer-before-arm-current.txt`: the exact accepted row completed
after one counted restart, matching pointer/settings and two searchable files;
STOP 0 closed the owned ports. `installer-before-pointer` also passed at
`tmp/3393-installer-before-pointer-current.txt` with the same exact recovery
checks. The critical pointer-B/settings-A cut passed at
`tmp/3394-installer-pointer-before-settings-current.txt`: the successor rolled
settings forward to the exact operation key, completed the same row, served both
files and closed owned ports. The pointer/settings-before-runtime-publication
cut also passed at `tmp/3395-installer-settings-before-publication-current.txt`:
the successor served B, completed the same row and closed owned ports. The
before-receipt cut passed at `tmp/3396-installer-before-receipt-current.txt`.
All six current-revision installed cuts now pass. An independent read of each
`installer-cut.json` and `installer-final.json` confirmed the intended cut
pointer/settings combination, the same final operation key and generation,
`COMPLETE` row and acknowledged walk revision. Every run had one counted restart,
two searchable files, same-key idempotence and STOP 0; quick_health afterward
reported ABSENT with no foreign run or inference orphan. This is proof of the
installer composite cut series, not of D1-9's missing no-file, gap or cancellation
feature acceptance. The refusal transfer
test class passed at `tmp/3390-switch-refusal-transfer-focused.txt`, with
Spotless and PMD at `tmp/3391-switch-refusal-transfer-static.txt`; these are
local-only WIP proof.

The earlier PR727 checkpoint `daeb5704d` had hosted run `36003707287` SUCCESS across all
jobs, including Windows native and system integration. The 358-task local gate
on its runtime checkpoint `4971fbb9f` passed at
`tmp/3251-lifecycle-checkpoint-integrated.txt`. The standard installed CPU
activation and forced in-place distinct-model A/B checks passed at `tmp/3253`
and `tmp/3256` respectively. This establishes the D1-14 candidate lifetime
checkpoint, while D1-9 accepted-write visibility and refusal/cancel coverage
remain open.

Local uncommitted D1-9 work opens recorded A as a distinct writable runtime,
projects accepted Green file batches lexically into A, and commits A before
Green acknowledges a batch or the final cutover. Focused tests and static
checks passed at `tmp/3259-lexical-projection-focused.txt`. The first installed
write probe (`tmp/3262`) reproduced the old prepared-ingest refusal during a
recorded build. The serving-generation capture and recorded owner check now
admit a writable A with Green as producer; focused checks passed at `tmp/3264`.
The next probe (`tmp/3266`) held the already-settled activation marker, where
the producer is paused for cutover, so its A-text timeout is not evidence about
the MIGRATING write path. Its abort exposed an invalid bulk refusal code;
the code and a durable-progress regression passed at `tmp/3267`. The revised
private installed probe passed at `tmp/3273-lexical-build-ab.txt`: the
MIGRATING-accepted file became text-searchable in serving A before pointer
promotion, remained searchable in B afterward, and the distinct FP16 B model
answered a real vector query. The first two attempts at `tmp/3269` and
`tmp/3271` corrected fixture timing and retry of `UPGRADE_PREPARING`; neither
was counted as passing proof. Direct deletes, watcher deletes, non-file projections, durable
journal replay, beside semantic indexing, and refusal backfill still need D1-9
implementation and proof. The private fixtures close their owned ports.

The next local D1-9 slice widens the existing SQLite `switch_buffer` to carry
candidate generation with exact `(generation, key, revision)` removal. Ordinary
file batches and finite recorded-walk members now admit their jobs and scoped
UPSERT obligations in one `jobs.db` transaction; the Worker routes watcher,
batch and scan producers through that seam during migration. Direct watcher,
ID, prefix and collection deletes journal first, then commit their lexical A
effect before the B effect. Promotion replay selects the building generation
plus historical unscoped cutover entries, leaving a foreign candidate untouched.
Focused queue, service, replay and real-Lucene deletion tests passed at
`tmp/3292-scoped-journal-replay-focused.txt` and
`tmp/3294-file-journal-producer-regressions.txt`; `:modules:ui:installDist`
passed at `tmp/3295-d1-file-projection-installDist.txt` before the final scan
phase guard edit. Later focused UI and Worker tests plus installed distribution
passed at `tmp/3306-switch-scoped-watcher-dist.txt`. These are uncommitted
proofs, not hosted acceptance. The first watcher-deletion probes exposed a
fixture issue: the fault harness suppressed automatic root producers. A
narrowly enabled probe registered both roots, and `tmp/3308-watcher-delete-ab.txt`
proved the deletion absent from serving A during MIGRATING with a
candidate-scoped DELETE row. That run could not promote: it deleted a file in
the recorded captured plan, leaving one legitimate missing-projection gap.
The second probe (`tmp/3310-watcher-delete-ab.txt`) added a file outside the
captured plan: it acquired a candidate-scoped UPSERT and its queue job completed,
but only after the migration entered SWITCHING. The probe's MIGRATING-only A
visibility check timed out; it is not a passing A/B deletion check. A second
candidate request now refuses under the generation manager's state lock while
preserving the retained candidate; focused proof is `tmp/3311`. Maintenance
sync, prune and profiling reset now refuse during MIGRATING until
their accepted projections can be represented; focused refusal proof is
`tmp/3316`. Rename retains its existing paused-migration identity contract;
its A/B replay remains open. The scoped SWITCHING batch and sync tests pass
at `tmp/3312`.
Exact accepted projection
payloads, stale-source and non-file mutations, refusal/cancel reconciliation,
and all D1/D2/E/F integrated acceptance remain open.

The 358-task integrated gate `tmp/3317-d1-scoped-integrated.txt` reached all
selected tasks but failed three test modules. The context replay test used the
legacy unscoped drain context, the serving-view lifetime test observed its
close count before lease cleanup, and one 60-second Lucene RMW test timed out
under full-suite load. The RMW test passed alone at `tmp/3320`; the two test
corrections and restored paused-migration rename behavior passed focused
checks at `tmp/3321`. The corrected full gate passed all 358 tasks at
`tmp/3323-d1-scoped-integrated-rerun.txt` in 11m32s, before the next source
edit. A refute-first review then found that idle and shutdown batches committed
only B before queue ACK, while the time/buffer path committed A first. Both
paths now commit A before B; failure and ordering regressions, PMD and
Spotless passed at `tmp/3325-idle-shutdown-projection-order-fixed.txt`.
This later source edit has not had an integrated rerun.

The review's source-witness and claim/delete findings now have focused fixes.
Scoped file admissions carry the exact queue revision and source hash; strict
replay verifies the settled row and indexed hash without rereading changed bytes.
SWITCHING submissions and watcher upserts use atomic queue+journal admission.
The writer holds a file-mutation fence through A/B publication and checks its
issued claim before writing; direct deletes hold the same fence. Distinct-A
SWITCHING deletes also project A immediately. The affected module suites passed
at `tmp/3331` before the later queue-revision and delete-fence edits; focused
proof for those later edits is `tmp/3332`, `tmp/3333`, `tmp/3337`, and `tmp/3338`.
These edits remain uncommitted after local WIP checkpoint `1fa67ae92`; the
current tree has no integrated or hosted proof.

Recorded pre-pointer refusal now tries exact candidate-scoped replay on surviving
writable A under the mutation fence, after Green settles and pauses. It retains
Green when A projection cannot be proved, and removes exact journal versions
only after source replay commits. Focused replay and owner regressions, PMD and
Spotless passed at `tmp/3339` and `tmp/3340`. Ordinary failure/cancel transfer,
no-file accepted mutations, gap acceptance and crash-cut proof remain open.
Keep `tmp/3323` bound to its earlier working-tree snapshot. Let a coherent
hosted checkpoint complete before another push.

The first post-review full gate `tmp/3341-d1-source-refusal-integrated.txt`
completed all 358 tasks but failed two modules. Its full Worker, Indexer and
Engine XML was preserved under `tmp/3341-test-results/` before reruns. A Worker
shutdown fixture's mocked queue did not grant the new exact-claim check; its
writer never reached the controlled seam. The agent-context test still assumed
SWITCHING was journal-only, though candidate admission now persists the queue
row atomically. Both fixtures retain their ownership/provenance intent at the
new boundary and pass with the real enumeration regression at `tmp/3342`.
The longer real Engine VDU migration test exposed a production omission: native
root enumeration replaced a scoped queue row with plain `enqueueEntries`,
leaving an older source witness in the journal and blocking Green promotion.
Native enumeration now uses atomic candidate admission; a changed-source
regression and the real VDU migration pass at `tmp/3342` and `tmp/3343`.
The current tree still needs a full integrated rerun and installed/hosted proof.

The second full 358-task gate `tmp/3344-d1-source-refusal-integrated-rerun.txt`
failed two real migration cases; the full Indexer and Engine XML is retained at
`tmp/3344-test-results/`. Force-switching let the native enumerator scan after
an accepted DELETE and overwrite that journal key with a baseline UPSERT,
resurrecting the deleted file. Enumeration now distinguishes its own prior
admissions from foreground mutations within the same SQLite transaction and
counts superseded scan entries as covered. The atomic changed-file/delete
precedence regression and the real Engine SWITCHING case passed at `tmp/3345`
and `tmp/3346`. A manual native pointer-before-publication cut left a verified
scoped file witness at boot, preventing old-generation retirement. Native boot
now clears only exact file witnesses after checking the completed queue revision
and committed Green source hash; other operation kinds remain fenced. The real
document-identity boot cut and strict replay tests passed at `tmp/3348` (the
first `tmp/3347` run only failed on a new test's invalid Windows fixture path).
Another full integrated gate and installed/hosted proof remain required.

The next 358-task gate `tmp/3349-d1-file-mutation-integrated.txt` reached
400 Engine tests but failed the live VDU migration's replacement-search assertion;
the isolated rerun `tmp/3352-vdu-live-rerun.txt` failed identically after Green
promotion. Native enumeration had been taught to preserve every prior foreground
UPSERT, including one whose accepted source hash differed from the rewritten
file now being enumerated. A real SQLite regression for that changed-source cut
failed at `tmp/3353-changed-foreground-red.txt`. The queue rule now preserves a
foreground UPSERT only while its exact source hash still matches, and separately
preserves accepted exact, prefix, and collection deletes. The prefix regression
failed on the old rule at `tmp/3350-prefix-regression-red.txt`; prefix and
collection coverage passed locally at `tmp/3351-bulk-enumeration-focused.txt`.
An unprovable collection identity refuses candidate batch admission and retains
the delete. The live VDU case passed at `tmp/3354`; its paired synthetic
foreground fixture lacked the provenance that real RPC admission supplies, so
that focused assertion failed. After specifying the actual producer provenance,
the full enumeration suite passed at `tmp/3355`. Full integration and
installed/hosted proof remain open.

The next 358-task `tmp/3356-d1-file-mutation-integrated.txt` ran every test
task without a reported failure, but PMD rejected one unused variable in the
new prefix regression. Its Indexer and Engine XML is retained at
`tmp/3356-test-results/`. Source review then confirmed that real foreground
admission can also have null provenance: the source-hash comparison must cover
that case, rather than treating null as an immutable foreground winner. The
real-shaped SQLite test, Indexer PMD and live VDU case all pass at
`tmp/3357-enumeration-null-provenance.txt`. A fresh full gate is still required.

The final coherent `tmp/3358-d1-file-mutation-integrated.txt` gate passed all
358 Gradle tasks in 9m18s: Spotless, PMD, stress-enabled tests and installed
distribution. This proves local integration of the current file-mutation slice;
installed A/B mutation probes, hosted completion and the remaining D1-9
non-file/cancel/gap acceptance are still required.

## Prior checkpoints (2026-09-24)

The current unpushed D1-14 continuation corrected a refute-first lifecycle review of
the first in-place implementation. A separate lexical A service now preserves the
bindings of issued A calls while new captures answer text; a held-call test and a
five-second drain-refusal test cover the distinction. The candidate bundle adopts
its full wrappers on promotion or closes them after native retirement on refusal;
promotion updates the serving owner, selected model identity, configuration and
component state. A durable recorded refusal signal now starts pre-pointer
cleanup: Worker joins Green's producer, releases B, recomposes A when in place,
abandons the still-building generation and requests an ordered restart. The
same cleanup covers a beside candidate. Focused coordinator, producer and
held-view tests passed at `tmp/3238-refusal-focused.txt`; the drain deadline
regression passed at `tmp/3236-inplace-drain-deadline.txt`. `tmp/3239` reached
342 tasks but failed only test PMD after a new static import made ten older
qualified assertions redundant; the correction passed `tmp/3242`. The full
gate passed at `tmp/3243`, before the final lexical-health isolation edit;
focused tests, PMD, Spotless and installed distribution then passed at
`tmp/3245-lexical-status-dist.txt`. The first installed mixed chat/CUDA
pointer-before-settings cut at `tmp/3246` reached the Engine crash but its
observer hit a short SQLite writer lock. The observer now waits for that lock;
the rerun passed at `tmp/3247-installed-mixed-inplace.txt`, with one COMPLETE
row, matched pointer/settings witness, two search hits and closed private
ports. No hosted proof applies to this unpushed revision yet. The accepted-write
lexical A projection during Green build, installed refusal cuts and D1-14
acceptance remain open.

A second read-only lifecycle review found that an unpublished successor after
pointer commitment was not retained for ordered shutdown, a configured but
unavailable A was mistaken for a READY source, lexical view retirement did not
notify streams, and B promotion omitted compatibility/disambiguation bindings.
The corrections also close partially constructed candidate wrappers. Focused
tests and static checks passed at `tmp/3250-lifecycle-final-focused.txt` after
`tmp/3248` identified a PMD initializer issue. The complete 358-task
stress-enabled integrated gate passed on checkpoint `4971fbb9f` at
`tmp/3251-lifecycle-checkpoint-integrated.txt`. A standard installed CPU
pointer-before-settings crash recovered with one COMPLETE row, matching
pointer/settings and two text hits at `tmp/3253-installed-cpu-seed.txt`.
The first no-crash A/B probe at `tmp/3254` found that its beside-only vector
assertion did not fit the actual in-place mode; it had already proven A text
visibility. The corrected fixture requires A vector service before B, a
one-megabyte device-memory ceiling, A text visibility plus `RELOADING` and
unavailable vectors at the held cut, then B's real vector service after live
promotion. It passed at `tmp/3256-model-live-forced-inplace.txt`, with
different A/B model SHA-256 values, one accepted operation, settings revision
2 and closed private ports. Hosted proof of this checkpoint is pending.

### Current hosted and D1-14 boundary

`ec1cd14b7` is pushed to PR727 with the distinct-model commit/parity
regression, real standard CPU lease through ordered shutdown, mocked held GPU
lease, and installed different-byte A/B activation evidence. Its hosted run
`35966546167` passed system integration and all jobs except Windows-native:
the first attempt of `DrainAndCloseTest.drainAndCloseWaitsForInFlightWriter`
timed out after its fixed sleep failed to establish a held writer; automatic
retry passed. `da60deb28` replaced that timing assumption with an explicit
write-barrier lease and latches. Its focused test, PMD and Spotless passed at
`tmp/3167-drain-native-deterministic-focused.txt`. Hosted run `35968550589`
completed SUCCESS, including Windows-native and system integration. This is
terminal hosted proof for `da60deb28`; later D1-14 edits are local WIP.

D1-14 is locally in progress after `da60deb28`. The `core` device-memory line,
restart-required ceiling key, resolved CUDA arena footprint with ten-percent
headroom, `EngineRoot` GPU supplier binding, and component/wire projection of
mode, reason, free bytes and footprint are implemented. Focused configuration,
footprint, projection, PMD/Spotless, module-dependency, config-surface and
canonical-doc checks pass at `tmp/3170`, `tmp/3173`–`tmp/3176` and the current
worktree. The present `KnowledgeServer` candidate path records the decision
but **refuses IN_PLACE** before native composition. This is a fail-closed
implementation checkpoint, not D1-14 acceptance; it must not be pushed as a
finished device-line flow. Required work is a text-only A serving interval,
lexical Green ingestion with semantic deferral, bounded A-set retirement,
B composition/promotion, immediate A recompose on pre-pointer refusal or
abandonment, and the held-native and floor installed proofs. Preserve A's
issued view leases and its query-side binding coherence while implementing it.
The first worker-service seam removes A's query native references while leaving
Green's detached producer set intact; its producer-transfer regression, PMD and
Spotless passed at `tmp/3177-d1-14-text-only-service-focused.txt`; a refute-first
review found an incomplete restoration helper, which was removed. The seam now
also clears A's status-side GPU suppliers; its focused regression and static
checks passed at `tmp/3183-d1-14-text-only-review-repair.txt`. The server does
not yet invoke this seam.

A second D1-14/Flow B acceptance gap is source-confirmed: an ordinary accepted
ingest during Green build currently waits for Worker authority, so it is not
immediately text-searchable in active A, contrary to design §7.4:1481–1495.
The active runtime opens read-only and Green owns the sole writer. The selected
minimal path is to open A writable from migration boot (two runtime sessions,
not a same-directory upgrade), project each accepted mutation lexically to A,
and retain its durable Green replay through the existing mutation admission and
switch journal. This requires D1/D2 ownership and installed ordering proof;
the current text-only service seam alone does not provide it.

The first full D1-14 pre-in-place gate at `tmp/3181-d1-14-pre-in-place-integrated.txt`
failed one app-engine foreground-pacing test during teardown. Its preserved XML
at `tmp/3181-EngineForegroundPacingTest.xml` proves cancellation interrupted a
Lucene searcher release, invalidated the Windows writer lock, then refused
ordered close. `EngineKnowledgeClient` now signals cancellation without
interrupting the Worker; the caller still receives a terminal outcome and its
issued view remains held until actual exit. Executor/deadline and foreground
pacing focused tests plus PMD/Spotless pass at
`tmp/3182-cancel-lucene-lock-focused.txt`. The second full WIP gate
`tmp/3184-d1-14-pre-in-place-integrated-repair.txt` found two remaining
ownership assertions. Serving-view release now precedes terminal completion,
and cancellation closes fanout cooperatively without interrupting Lucene or
releasing child ownership early. The user approved changing the stale
interruption test contract on 2026-09-24. Focused proof passed at `tmp/3193`
and `tmp/3195`; the cancellation-signal test and static checks passed in the
current focused run. Full gates `tmp/3202`, `tmp/3203` and `tmp/3207` exposed
test PMD and stale interrupt expectations, plus a remaining production
`cancel(true)` in per-source search fanout. That callback now cancels
cooperatively, and the affected focused model, fanout and per-source tests
passed at `tmp/3205` and `tmp/3208` with PMD/Spotless. An independent review
found that child fanout work also needed its own serving-view lease; that
ownership and an independent worker-interrupt regression passed focused tests
at `tmp/3210` and `tmp/3211`. The full 358-task Java gate passed at `tmp/3212`
on those corrections. Frontend typecheck and all 6,600 unit tests passed.

The two bounded audit corrections are local WIP. Mixed installer activation
records explicit selected chat GGUF and companion identities in a v3 accepted
plan, validates durable contract/registry/bytes before candidate settings
change, preserves operator precedence, and refuses changed bytes at preparation
or replay. V2 no-chat rows replay; v2 rows with an unproven chat path refuse
before pointer or fence recovery after pointer B. Producer, precedence,
verifier and boot focused suites passed at `tmp/3188`, `tmp/3198`, `tmp/3191`
and `tmp/3197`; the installed mixed activation later passed at `tmp/3223`.
The drain regression now
measures the production writer's read hold inside its existing supplier seam
and observes a queued drainer. Its focused test passed at `tmp/3199`; the
paired acquire/release bypass negative control failed for missing writer
ownership at `tmp/3206`, after the final cleanup edit, with source restored.
The installed standard CPU pointer-before-settings activation passed at
`tmp/3214`: one terminal row, B's pointer, the exact settings witness and two
text hits; the private stack stopped with ports closed. An opt-in mixed
chat/CUDA installed fixture now stages registry-verified GGUF and companion
assets and checks v3 identities. Its first run had CUDA disabled by the fixture;
the corrected run reached the device-memory decision and refused before
candidate composition because B needs about 14.2 GB while 11.4 GB is free.
This was the deliberate D1-14 checkpoint `IN_PLACE` refusal. The next local
revision publishes text-only A, drains its held native view and exact set,
then composes B. Installed mixed-chat pointer-before-settings recovery passed
at `tmp/3223`: v3 selected chat identities, one COMPLETE row, B's pointer,
accepted settings witness and two text hits; private ports closed. Focused
producer and held-view regressions passed at `tmp/3224` and `tmp/3226`.
Pre-pointer A recomposition is implemented locally; refusal, gap/cancel and
accepted-write visibility proofs remain open. A full gate at `tmp/3227`
found an Error Prone self-assignment in the fresh lexical-only branch; the
one-line fix is local and the integrated rerun is owed.

PR727 checkpoint `70c518144` has terminal hosted run `35982489785`:
Windows-native, system integration and all other jobs passed except Public
claims. That job found generated `status-response.ts` drift from the changed
wire schema. Regeneration is local; the focused contract-projection gate and
UI typecheck pass. No later push has replaced the terminal system-integration
evidence. Next finish the in-place refusal/held-native proof and accepted-write
routing, rerun the full local gate, then push a coherent correction checkpoint.

### Latest checkpoint and D1-12 installed boot evidence

Pushed PR727 checkpoint `61958becc` passed terminal hosted run `35954572417`,
including system integration. The next D1-12 checkpoint extends
accepted installer preparation with the complete selected ONNX model map,
including retained roles, exact hashes, sparse mode and vector dimension.
Worker boot composes the active manifest's X instead of desired settings Y;
compatibility and schema status use X's identity. Focused Java checks and
`:modules:ui:installDist` passed at `tmp/3127-d1-12-status-build.txt`.
The full 358-task `spotlessCheck pmdAll test -PincludeStress=true
:modules:ui:installDist` gate passed at `tmp/3132-d1-12-integrated.txt`.
The installed standard-model pointer-before-settings recovery passed at
`tmp/3119-d1-12-installed.txt`. A private installed X/Y boot passed at
`tmp/3128-d1-12-model-x-y-installed.txt`: X encoders READY, exact X embedding
and schema fingerprints matched the stored values, Y remained pending, and
text search returned a hit. The private missing-X boot passed at
`tmp/3129-d1-12-model-missing-x-installed.txt`:
`UNAVAILABLE/INDEX_MODEL_NOT_INSTALLED` with text search still serving. Both
stopped with ports closed. The initial X/Y `tmp/3122` run exposed a real
process-wide fingerprint leak; the earlier `tmp/3120`/`3121` attempts were
fixture request timeouts. The next boundary is retained-asset audit and
terminal hosted proof at the coherent checkpoint. D1 side-by-side publication and
lifetime, then D2/E/F remain open; merge is conditional on stage F acceptance.

After the `ccbe9df4a` push, the retained-asset follow-up freezes
supporting metadata files for retained selected models in the accepted plan
and refuses replacement of differing supporting bytes in candidate directories.
Repair of the plan's selected ONNX variant remains allowed. Focused tests, PMD and
Spotless passed at `tmp/3139-d1-12-retained-assets-final-focused.txt`; the
358-task full gate passed at `tmp/3140-d1-12-retained-assets-integrated.txt`.
Installed standard-model pointer-before-settings recovery passed at the exact
follow-up source in `tmp/3141-d1-12-retained-assets-exact-installed.txt`,
including exact settings witness, one terminal operation, two text hits and
closed ports. Hosted run `35960460879` for `ccbe9df4a` and run `35962108023`
for `60176ac6e` completed SUCCESS, including system integration.

The next local correction after `60176ac6e` fixes the placement repair
predicate: the candidate directory key is the whole package identity, so it
cannot be compared with the selected ONNX SHA. `PlannedDownload.isModelVariant`
is the existing exact producer fact; it permits only that ONNX variant's
candidate-owned repair. A negative control refuses an auxiliary ONNX file
marked as supporting. Focused placement, PMD and Spotless checks passed at
`tmp/3142-placement-repair-correction-focused.txt`. Full integrated proof is
`tmp/3143-placement-repair-correction-integrated.txt` (358 tasks passed);
hosted proof awaits this correction's push. The installed X/Y boot also answered a
real vector query through serving X (HTTP 200, two results) with Y still
pending at `tmp/3144-model-x-y-vector-installed.txt`; owned ports closed.
The installed live A/B probe held B at the settled pre-pointer marker: A
answered text and vector searches, B had two completed build units and zero
failures, and B then promoted with matching settings and a working vector
query. The strengthened pass is `tmp/3148-model-live-ab-installed.txt`; owned
ports closed. A and B used distinct private paths but the same standard-model
SHA, so differing-model fingerprint isolation and held A lease retirement
remain open acceptance.
The exact `4a6dc7e47` installed distribution then passed a different-byte A/B
activation at `tmp/3154-model-live-distinct-ab-installed.txt`: A's FP32 SHA
remained searchable at the settled pre-pointer cut while B's FP16 SHA had two
completed build units, then status converged to B's exact fingerprint and B
answered a vector query; owned ports closed. A's query finished before
promotion, so a held A lease spanning publication and retirement remains open.
An adapters-lucene regression at the next local revision writes two open
generations with different model SHA inputs, verifies each commit's own
fingerprint, accepts own parity and rejects cross-parity. Focused test and
static checks passed at `tmp/3149-two-runtime-model-identity-focused.txt`
and `tmp/3150-two-runtime-model-identity-static.txt`. The installed different-byte
probe above now complements that local commit/parity test.
The exact CPU native stress at the `4a6dc7e47` production revision executed
one unskipped test with zero failures under `-PincludeStress=true` at
`tmp/3151-d1-13-exact-native-stress.txt`. D1-13 still needs a real installed
lease crossing recompose/ordered shutdown; this
stress result alone does not close native lifetime acceptance. A deterministic
mocked GPU lease held across retirement now passes in the NativeSessionHandle suite
at `tmp/3155-d1-13-gpu-held-lease-focused.txt`, with PMD and Spotless at
`tmp/3156-d1-13-gpu-held-lease-static.txt`; installed native ownership is
still open.
A real standard-model CPU session is now held through ordered KnowledgeServer
shutdown in an in-process test: its issued session remains usable, post-retire
acquisition refuses, and close finishes after release. The focused class ran
13 tests without skips/failures at
`tmp/3162-d1-13-real-native-shutdown-final-focused.txt`; static checks passed
at `tmp/3161-d1-13-real-native-shutdown-static.txt`. Held native recompose and
installed GPU lifetime proof remain open.

### D1-4 accepted inference refresh slice and model-identity follow-up

The local `bc4527f36` checkpoint adds two D1-4 component-boundary regressions
on top of pushed `d64339ef8`. The current uncommitted slice routes Brain's
Reload control through a fresh witnessed `core.reconfigure` with explicit
refresh intent. Accepted preparation binds that intent, and the settings owner
forces generative candidate preparation even for unchanged settings. The old
catalog operation, both direct reload routes and their live producers are
retired. An installed Head captured 243 routes, down from 245, with exactly
those two routes removed (`tmp/3047-reconfigure-route-capture.txt`).

The corrected 358-task `spotlessCheck pmdAll test -PincludeStress=true
:modules:ui:installDist` gate passed at
`tmp/3046-reconfigure-integrated-repair.txt`. The first run's PMD and catalog
count failures remain preserved at `tmp/3043-reconfigure-integrated.txt` and
`tmp/3043-RegistryControllerTest.xml`. Frontend typecheck and all 6,600 unit
tests passed. An owned installed standard-model run activated the 9B profile,
completed real model queries before and after the accepted refresh, observed
inference generation 1 → 2 and the exact successor settings witness, and
stopped with ports closed (`tmp/3051-reconfigure-refresh-standard.txt`).
Earlier fixture attempts at `tmp/3048`–`3050` failed on a required-token
assumption, an operator model-path override, and an incorrect response-shape
assertion; none established a product failure.

The refute-first review found two runtime gaps. Committed refresh recovery now
decodes its accepted `core.reconfigure` preparation and waits to recompose the
generative component before terminalizing; a real SQLite restart cut passes at
`tmp/3058-refresh-review-focused.txt`. `RESTART_IF_ONLINE` now resolves under
the inference manager lifecycle lock: an Offline refresh retains configuration
without starting a server, with focused manager and owner regressions at
`tmp/3066-refresh-lock-policy-focused.txt`. A separate precommit reconfigure
regression asserts `ENGINE_RESTARTED_DURING_APPLY` while settings-apply keeps its
C2 reason. The retired-handler residue in the canonical composition slot map
was removed. Generated/document/UI checks passed, including frontend typecheck,
6,600 unit tests, eight measured Brain captures, route capture, docs and client
regeneration checks. The final 358-task integrated gate passed at
`tmp/3067-refresh-lock-policy-integrated.txt`; installed standard-model queries
before and after refresh passed at `tmp/3069-reconfigure-refresh-standard-lock-policy.txt`
with generation 1 → 2, exact successor witness and identity-checked teardown.
The first rerun at `tmp/3064` exposed a fixture race: the Head proxy answered a
query before logical Online publication; waiting for the Online generation
resolved it at `tmp/3065` and remained green after the lock fix.

Checkpoint `821d196b4` is pushed to PR727. Hosted CI run `35943801825` passed
Windows-native, platform, app-ui, search-worker, jseval, build and system
integration jobs. Public claims failed the dead-code
gate because `OPEN_BRAIN` was newly exported but used only inside
`readinessNotice.ts`. Its export was removed locally; the real Knip report and
`--gate dead-code` now pass without a baseline change. The system integration
job reached terminal success before the next push.

Checkpoint `f5d81ac1f` is pushed to PR727. It adds a harness-only `settings-mid-compose`
boundary after the generative owner prepares and before the settings file
commit. The owner-order regression and affected PMD/Spotless/installDist passed
at `tmp/3070-mid-compose-focused.txt`; the full 358-task integrated gate passed
at `tmp/3073-mid-compose-integrated.txt`. The installed standard-model cut at
`tmp/3078-mid-compose-physical-standard.txt` proves the candidate's server
became healthy and reported the Qwen 9B model before the marker, then an
identity-verified kill left the accepted row RUNNING and the settings witness
at A. The successor failed the same row with
`ENGINE_RESTARTED_DURING_APPLY`, served settings A in Offline mode, and
same-key replay preserved the failure. Its private stack stopped with ports
closed. Earlier `tmp/3071`–`3072` exercised only nonstarting Offline refresh;
`tmp/3076`–`3077` exposed a fixture log-path timing error; none is claimed as
the physical mid-compose proof. Frontend typecheck, 45 focused readiness tests
and the local dead-code gate pass after the export correction.

Retained differing-model assets and the remaining D1/D2/E/F acceptance stay
open. The installed mid-compose proof used the private supervisor harness with
identity-checked teardown; the later browser run used the owned shared dev
stack from this worktree.

An owned dev-stack run `8c98e2d4-ef06-4db8-a161-07fb510f2a5e` at
`f5d81ac1f` exercised the actual Brain Reload button. The Head inference
generation advanced 2 → 3; official stop reported `portsClosed: true`.
The same run exposed a model-identity projection defect during a compact to
standard profile switch: `/v1/models` and a real completion reported the
physical Qwen 9B server while `/api/inference/status` retained Qwen 4B as
`activeModelId`. The committed runtime-status projection read the configured
9B identity, but the inference view carried A's stale `/props` model ID.
A local D1-4 fix prepares B's managed model identity in the logical publication
snapshot without exposing it before commitment. The A/B regression passes at
`tmp/3080-model-projection-focused.txt`; integrated and installed proof for
this follow-up is now recorded below. Do not count the first browser run as a
clean standard-model status proof.

The next installed check exposed a second D1-4 defect: Reload of a transient
standard profile selected the persisted compact profile again. The physical
9B-to-4B change is preserved at `tmp/3087-reconfigure-refresh-model-identity-clean.txt`.
Accepted `core.reconfigure` preparation now freezes the serving profile beside
refresh intent (replay schema v2, with v1 recovery compatibility). The settings
owner composes the frozen profile and refresh together; committed recovery
replays both affected keys before the runner terminalizes. The inference owner
publishes the candidate's managed model ID with B, never during private prep.
The first integrated run `tmp/3090-refresh-profile-integrated.txt` caught an
executor test still expecting the retired boolean-only settings call. Its
accepted-context assertion was corrected and passed at
`tmp/3091-reconfigure-executor-focused.txt`. The repeated 358-task integrated
gate passed at `tmp/3092-refresh-profile-integrated.txt`. Installed standard
proof `tmp/3093-reconfigure-refresh-physical-standard.txt` passed with real
completions, generation 1 → 2, exact successor settings witness and the same
physical Qwen 9B ID in `/v1/models` and `/api/inference/status` on both sides.
The private stack stopped with ports closed; official quick health then reported
ABSENT and inference REFUSED. This source is checkpoint `386cd5575`, pushed to
PR727. Hosted run `35949287953` finished SUCCESS at that SHA, including
system integration, Windows-native, public claims and all other jobs. It
reached terminal system integration before the next checkpoint push.

The next D1-12 implementation boundary remains open. `EncoderSet.ModelIdentity`
and `CandidateIndexTargetCapture` hold Green's captured hashes, and the accepted
installer plan retains exact model paths and SHA-256 values. Local work after
`ebdb96abe` adds a manifest `models` map and binds those accepted installer
paths and hashes to recorded Green before its runtime opens. The binding is
idempotent, refuses a conflicting replay, and survives promotion in a focused
Worker-core test (`tmp/3100-generation-model-binding-focused.txt`); affected
compile, Spotless and PMD passed. The full 358-task integrated gate passed at
`tmp/3101-generation-model-binding-integrated.txt`. The installed standard-model
`installer-pointer-before-settings` cut passed at
`tmp/3102-generation-binding-installed.txt`: the exact Green manifest retained
the embedding, NER and SPLADE file paths and SHA-256 values through pointer
commit, recovery and terminal success; settings witness and search converged,
and the private stack stopped with ports closed. Official quick health afterward
reported ABSENT, inference REFUSED and no foreign runs. Checkpoint `8a6b22332`
is pushed to PR727. Its hosted run `35953229613` had build, Worker, platform,
Windows-native, public-claims and other completed jobs green at the 2026-09-24
04:04 UTC check; system integration was still running. Do not push the next
checkpoint until that job reaches a terminal result. Local commit `e4dea2fd5`
then bounded the manifest's accepted role map and serialized size before
replacement, preserving the old manifest on refusal. Focused Worker-core
tests and static checks passed at `tmp/3105-generation-manifest-limit-focused.txt`;
this local commit has no hosted proof yet. Native generation creation and boot
still compose A from desired process configuration and global model fingerprint
providers. A boot with
manifest X and desired settings Y still lacks the specified implementation and
proof, as does the missing-X text-search fallback. Continue from stage D1-12
and the accepted installer identity rather than claiming the fresh-install
six-cut fixture proves
retained differing-model A/B.

### D1-10 opened-runtime status checkpoint `ebdb96abe`

The production Worker status path now binds the search and ingest runtimes'
opened directories and reports each generation from its validated manifest.
The pointer remains the separately reported active generation. A real A/Green
runtime test keeps the old service alive after pointer promotion and observes
pointer Green, search Blue, ingest Green; a newly composed service reports
Green/Green. Focused tests passed at `tmp/3095-served-generation-focused.txt`;
affected PMD and Spotless passed at `tmp/3096-served-generation-static.txt`.
The 358-task integrated gate passed at `tmp/3097-served-generation-integrated.txt`.
Review then removed the helper's test-only pointer fallback so no served ID
comes from the pointer, and added a negative manifest-identity check. Final
focused tests, PMD and Spotless passed at
`tmp/3098-served-generation-final-focused.txt`. The full `3097` run predates
that final focused correction. The installed recorded migration with real
retained ONNX assets passed at `tmp/3099-served-generation-installed-migration.txt`:
the status endpoint named the open Blue/Blue runtimes before rebuild and
Green/Green after live promotion, alongside the existing search, queue,
retirement and rollback checks. Its private stack stopped with ports closed;
official quick health then reported ABSENT and inference REFUSED. Hosted proof
for this slice is now complete: checkpoint `ebdb96abe` is pushed to PR727 and
hosted run `35951296305` finished SUCCESS, including system integration,
Windows-native and Public claims, before another push. This status update
follows the checkpoint and is not yet committed.

### Active checkpoint `d64339ef8` and D1-4 continuation

Checkpoint `d64339ef8` is pushed to PR727. Its 358-task local integrated suite
passed at `tmp/3024-installer-acceptance-integrated.txt`; all six installed
standard-model installer activation crash cuts passed with a fresh no-READY
start. The final before-receipt cut uses a narrowly scoped harness-only Engine
hard halt and passed at `tmp/3025-installer-before-receipt-final.txt`; the
updated external-kill pointer-before-settings cut passed at
`tmp/3026-installer-external-cut-final.txt`. Hosted run
[`35934855473`](https://github.com/justsearch-app/justsearch/actions/runs/35934855473)
finished SUCCESS at the exact checkpoint, including system integration and
Windows-native tests. This closes hosted proof for the six-cut installer slice;
retained differing-model A/B assets remain open.

The next local D1-4 tests in `SettingsCommitCoordinatorTest` exercise a real
SQLite reconfigure row with two affected components. Refusal by the second
aborts the first, closes its apply lease, fails the row with the component
code, and preserves settings bytes, witness and ConfigStore A. An unrelated
settings change completes without invoking the component composer. The two
focused tests and Spotless passed at `tmp/3028-d1-component-boundary-focused.txt`.
These tests do not close D1-4: the installed mid-compose crash, retirement of
`core.reload-inference`, and the remaining D1/D2/E/F acceptance stay open.

### Installer activation acceptance slice after `07179edeafed` (committed in `d64339ef8`)

The producer now has runnable regressions for tampered staged model bytes after
preview, accepted activation after download cancellation, and frozen watched
scope/source-generation drift. Focused app-services checks passed at
`tmp/3008-installer-boundary-focused.txt` and
`tmp/3009-installer-boundary-focused.txt`. The installed standard-model fixture
proved an in-flight same-key call retains one accepted row at five fault cuts:
pointer-before-settings at `tmp/3012-installer-inflight-duplicate-standard.txt`,
and before-marker, before-arm, before-pointer, and settings-before-publication
in their respective `tmp/3013-installer-*-standard.txt` runs. The before-receipt cut can be terminalized by legitimate
concurrent reconciliation before an external Windows kill; the harness now
self-halts only the exact selected Engine at that marker. A process-handle
forced destroy proved insufficient on Windows: it took the requested-restart
path at `tmp/3022-installer-before-receipt-self-destroy.txt`. The narrow
harness-only hard halt and its focused UI/PMD/installDist check passed at
`tmp/3023-receipt-hard-halt-focused.txt`; the 358-task integrated
`spotlessCheck pmdAll test -PincludeStress=true :modules:ui:installDist`
gate passed at `tmp/3024-installer-acceptance-integrated.txt`. The final
deterministic installed cut passed at `tmp/3025-installer-before-receipt-final.txt`: pointer/settings B,
sealed queue receipt with no terminal operation result at crash, one counted
Engine restart, same accepted preparation and row at COMPLETE, two Green search
documents and same-key replay. Its private stack stopped with ports closed.
The updated external-kill fixture passed pointer-before-settings at
`tmp/3026-installer-external-cut-final.txt` with clean teardown.
The six installed runs begin without a READY model and with no committed ONNX
settings. Retained differing-model A/B assets remain D1-12 proof, not established
by this fresh-install fixture. Hosted system integration passed at the exact
checkpoint above.

### Active checkpoint `07179edeafed` (2026-09-24)

The installed recorded migration exposed two issued Blue serving-view holders
after live Green publication: the indexing-jobs subscription and its delivery
task. Blue could retire only at shutdown; the two-holder reaper log is in the
`tmp/3002` installed run. Long-lived streams now register a per-view retirement
callback, release the old flow and reconnect the Head bridge to Green after
publication locks release. Finite issued calls still drain normally. The
focused lifetime test and install distribution passed at
`tmp/3005-serving-stream-retirement-focused.txt`. The installed recorded
`migration` scenario passed at `tmp/3006-migration-installed-stream-retirement.txt`:
Green search and exact queue/operation settlement, rollback 409, Blue pointer
clear and directory deletion while incarnation 2 remained running, and clean
private-stack teardown. The native pointer-before-publication boot assertion
and the fresh no-READY installer fixture are included. The coherent 358-task
`spotlessCheck pmdAll test -PincludeStress=true :modules:ui:installDist`
sweep passed at `tmp/3007-stream-retirement-integrated.txt`. Checkpoint
`07179edeafed` is pushed to PR727; hosted CI run `35929769206` finished
SUCCESS at that exact SHA, including system integration and Windows-native
jobs. This closes the live Blue retirement defect and its installed proof;
D1/D2/E/F acceptance remains open.

### Active checkpoint `449653f63bb3` (23:48 Berlin)

Checkpoint `449653f63bb3` is pushed to PR727. Native predecessor retirement
waits for settled replay and the last issued Blue view, and new builds refuse
retained predecessor capacity. The corrected 358-task integrated suite passed
at `tmp/2992-native-retirement-integrated.txt`; the retained standard-model
installed pointer-before-settings cut passed at
`tmp/2994-installer-standard-current-retirement.txt` with exact operation
receipt, settings and pointer B, both search documents and private-stack stop.
Hosted CI run `35924698949` finished SUCCESS at this SHA, including system
integration and Windows-native jobs. An uncommitted native
pointer-before-publication boot assertion now proves Green serves and Blue's
pointer/directory retire after reboot; its focused Worker/static check passed
at `tmp/2993-native-pointer-boot-focused.txt`. The installed fixture now
explicitly requires no committed model settings and a non-READY initial AI
manifest; it passed the same standard-model cut at
`tmp/2995-installer-fresh-no-ready-standard.txt` and the private stack stopped.
The retained initial manifest reports `OFFLINE` in that run. The installed
recorded migration at `tmp/2996-migration-installed-native-current.txt` passed
live Green publication, exact queue acknowledgement and rollback refusal. Its
new live-retirement assertion exposed the two-holder lifetime gap at
`tmp/2998-migration-installed-retirement-reaper.txt`; the correction and passing
installed proof are above.

### Earlier checkpoint `5eac4851603e` (23:14 Berlin)

Checkpoint `5eac4851603e` is pushed to PR727. Ordinary native migration now
uses the in-process Flow A serving-view publication: an exact A/B pointer guard,
final-fence `SYNC_ROOT` replay into Green, live publication and forward
reconciliation. The live Engine sees Green before any restart and retains it
after restart. The corrected 358-task integrated suite passed at
`tmp/2984-native-flow-a-integrated-corrected.txt`; the operation-surface gate
passed. Hosted run `35921193076` finished SUCCESS, including system
integration, Windows-native, build, public claims and all other jobs. Earlier
checkpoint `983ad90c0507` also has green hosted run `35916819396`, which
proves the corrected installed migration fixture and frozen candidate
revalidation at that revision.

The current uncommitted D1 slice adds native predecessor retirement after
settled replay and actual Blue serving-lease release. It refuses a new build
while a distinct predecessor or physical abandoned candidate retains the
second generation slot; the next build first reclaims an already marked
abandoned candidate. Diagnostic corruption backups are distinguished from
live generation slots so source rebuild remains reachable. The first full
sweep `tmp/2988-native-retirement-integrated.txt` found three Worker boot/
recovery regressions and an Engine corruption-rebuild regression; the run was
stopped after preserving failure XML in `tmp/2988-native-retirement-results/`.
Focused manager/static, Worker recovery, and Engine corruption checks pass at
`tmp/2989-native-capacity-recovery-focused.txt`,
`tmp/2990-native-recovery-worker-focused.txt`, and
`tmp/2991-native-recovery-engine-focused.txt`. The corrected 358-task full
integrated sweep passed at `tmp/2992-native-retirement-integrated.txt`. This
remains implementation and local proof, not D1 acceptance. Next prove native
installed cutover and pointer-before-publication recovery, then the remaining
D1/D2/E/F register; merge still requires stage F acceptance.

### Active D1 state after `bab9a3ff0` (21:54 Berlin)

Checkpoint `bab9a3ff0` (21:54 Berlin) is pushed. Its 358-task local integrated
suite passed at `tmp/2963-lane-f-capture-integrated-green.txt`; an installed
standard-model pointer-before-settings crash cut passed at
`tmp/2964-installer-current-checkpoint.txt` with pointer/settings B, exact
operation receipt, both search documents and clean private-stack teardown.
Hosted run `35912460674` finished all jobs: build, app-ui, search-worker,
platform, Windows-native and other jobs passed. Public claims failed solely
because `RecordedInstallerGenerationPlanResolver` lacked an operation-surface
consumer row; the added row passes `node scripts/governance/run.mjs --gate
operation-surface --mode gate` locally. System integration finished its full
suite (113 tests, 48 skipped) and identified three retries of one stale
`migration` fixture. The hosted artifacts show the accepted rebuild row
`COMPLETE`, pointer Green and same Engine incarnation after the legitimate
start restart. The fixture still waited for a promotion restart and then a
pointer-only rollback restart. It now asserts live Green publication and the
existing rollback 409 refusal; its direct installed run passed at
`tmp/2976-migration-live-no-restart-rollback-refusal.txt` with exact queue
acknowledgement and clean teardown. The installer standard-model cases were
skipped only on the hosted runner without retained bytes; local installed proof
remains above and in the prior six-cut evidence.

The next local D1 change revalidates the frozen installer candidate at the
approved execution boundary and refuses a changed settings witness, scope or
Worker target before the recorded ingestion owner starts. Witness and target
regressions passed at `tmp/2969-activation-fresh-preview-focused.txt`; PMD,
formatting and the architecture gate passed at `tmp/2970-activation-static.txt`.
The full 358-task suite passed at `tmp/2971-lane-f-preview-integrated.txt`.
These fixes and the migration fixture correction are not yet pushed. After
the next checkpoint, let hosted integration reach its terminal result before
another push.

Worktree `F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify` is on
`codex/lane-f-pr1`; PR727 has pushed checkpoint `756567096`. The approved
installer activation and Flow A implementation, six local installed
standard-model pointer/settings crash cuts, full local Gradle/static/stress
suite, and frontend checks are in that commit. Do not stage the untracked
`modules/app-inference/logs/` directory. Checkpoint pushes remain authorized;
merge still requires stage F acceptance.

Hosted run `35904361821` completed at that SHA. Public claims found the
jobs-db v20 recoverability catalog omission and an unclassified placement
write; both are corrected locally and the recoverability test/gate passed.
The app-ui shard found a Windows-only absolute-path fixture in
`RecordedInstallerGenerationPlanResolverTest`; the portable-root correction
passed its focused test. System integration ran for the full 35-minute job
budget and was cancelled: its bulk partial-capture and pre-binding fixtures
waited for obsolete promotion restarts while operation rows actually settled,
and six installer cases lacked retained model bytes on the hosted runner.
The bulk fixture now distinguishes a permitted Green-start restart from
promotion, and asserts the serving incarnation is unchanged after terminal
settlement, search, and replay. A private installed rerun passed partial
capture at `tmp/2953-bulk-partial-live-no-promotion-restart.txt` and
pre-binding at `tmp/2954-bulk-state-live-no-promotion-restart.txt`. Installer
cases are explicitly skipped only on hosts
without the three retained model assets; the six direct installed standard-model
cuts above remain their proof. The next pushed checkpoint needs a coherent
hosted system run that finishes.

Uncommitted D1 request-capture changes retain MCP search's exact serving
client/config through delivery and pair encoder runtime policies/probes on one
lease. Focused search-session, MCP, and cutover tests passed at
`tmp/2938-diagnostic-capture-focused.txt`, `tmp/2941-mcp-capture-tests.txt`,
`tmp/2942-mcp-session-projection.txt`, and `tmp/2943-mcp-session-lease.txt`.
The first full integrated rerun (`tmp/2945-lane-f-capture-integrated.txt`)
completed with only PMD's scope annotation and two superseded global MCP
settings readers red; these were corrected, with focused static tests green at
`tmp/2949-static-corrections.txt`. The complete 358-task
`spotlessCheck pmdAll test -PincludeStress=true :modules:ui:installDist`
sweep passed at `tmp/2955-lane-f-capture-integrated-green-candidate.txt`.
Independent review then found MCP search's enrichment hint still reacquired
live status during governor rendering. The pending correction reads one status
fact snapshot through the retained search lease before rendering, so the count
and hint both belong to A after B is selected. The MCP cutover and
close-on-render-error tests, focused app-services/MCP suite
(`tmp/2957-mcp-retained-status-focused.txt`), and dead-code architecture plus
MCP suite (`tmp/2960-mcp-arch-recheck.txt`) pass. An intervening full suite
(`tmp/2958-lane-f-capture-integrated-final.txt`) found only the now-removed
test-only wrapper's dead-code violation. After removing it and formatting the
tests, the exact-source 358-task rerun passed at
`tmp/2963-lane-f-capture-integrated-green.txt`. Hosted proof is pending. The
recoverability test/gate, script syntax, and diff check also pass.
The current installed standard-model pointer-before-settings cut passed at
`tmp/2956-installer-current-session-capture.txt`: the exact killed attempt
recovered the same operation, settings witness and pointer B; both documents
were returned in search and the private stack stopped cleanly. Hosted rerun
is due after review and checkpoint push.
Remaining D1 includes unrecorded live Flow A, held-query/A-B lifetime proof,
fresh/no-READY activation, retained assets, cancellation/duplicate/conflict
cases, and item-by-item D1-4 through D1-17 reconciliation. D2/E/F remain open.

### Historical D1 checkpoint after `a3039517c` (20:45 Berlin)

Worktree `F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify` remains on
`codex/lane-f-pr1`. `a3039517c` is the current HEAD; the Flow A and installer
activation edits below are uncommitted. Do not push another checkpoint until one
coherent hosted verification can finish. Prior pushes cancelled hosted system
integration twice. PR727 checkpoint pushes are authorized; merge is conditional
on stage F acceptance. Keep main and other sessions' files untouched, stage
explicit paths, and exclude `modules/app-inference/logs/`.

The corrected candidate-context integrated, installed standard-model and hosted
verification finished before this WIP. The last completed hosted run
`35846791699` found the known ordinary-settings installer refusal and an old
system-fault fixture selector; other completed jobs passed. Preserve that proof
at its tested revision. The [installer generation design](evidence/D1/generation-native-cursor-design-2026-09-23.md#7-installer-produced-generation-candidate-2026-09-23-amendment)
is selected: acquisition says “Downloaded — activation required”; a distinct
approved HIGH/DURABLE/REINDEX activation freezes candidate settings and model
identity. Ordinary settings still refuses generation-bound changes. The
publication decision records the settings projection and pointer-first
roll-forward rule. Do not route activation through ordinary `BulkReindexHandler`
or complete a row from the settings witness alone.

Current WIP has an isolated B model/producer surface and candidate-specific
embedding compatibility metadata, strict switch-buffer replay and mutation
fence, live Green/Head publication, typed v2 activation preparation and runner
settings marker, boot pointer/settings roll-forward, and installer candidate
placement/status UI. Its focused owner tests and the app-services full module
passed (`tmp/2877-app-services-full.txt`); the Worker full module passed before
the latest recovery edits (`tmp/2878-worker-full.txt`). The full static build
passed at `tmp/2886-full-build-static.txt`. The full test run at
`tmp/2870-full-test-live-head.txt` found failures since fixed locally; a new
full suite is due. Frontend typecheck and focused activation UI tests passed at
`tmp/2844-ui-typecheck.txt` and `tmp/2845-ui-activation-test.txt`. No hosted
checkpoint has been started for this WIP.

Direct installed standard-model verification reached and passed the first
composite crash cut, `installer-before-arm`, at
`tmp/2910-installer-before-arm14.txt`: the real approved v2 activation retained
one accepted row and frozen preparation across an exact Engine kill, promoted B,
committed its settings witness, returned both indexed documents in search,
completed the row and preserved it on same-key replay. Earlier direct runs
found and fixed a genuine candidate fingerprint comparison against global A
(`tmp/2900-green-target-install.txt`), boot's already-held installer settings
reservation, and the already-armed exact revision marker. Focused recovery and
store tests passed at `tmp/2906-resume-marker-tests.txt`. The separate
before-marker crash boundary and focused gate tests passed locally at
`tmp/2911-premarker-tests.txt`; its installed run passed at
`tmp/2913-installer-before-marker.txt` with the durable marker absent at the
cut and one terminal row after recovery. The before-pointer cut also passed at
`tmp/2915-installer-before-pointer2.txt`. The committed pointer-before-settings
cut passed at `tmp/2917-installer-pointer-before-settings2.txt`: boot projected
settings B and completed the same attempt-2 row without retrying the committed
effect. The settings-before-publication cut passed at
`tmp/2918-installer-settings-before-publication.txt`. The final pre-result cut
passed at `tmp/2923-installer-before-receipt.txt`. The first final-cut run found
a real publication-order bug: a reconciliation notification completed the row
before the recorded projection callback. `KnowledgeServer` now invokes that
callback first; `tmp/2921-post-order-producer-dist.txt` built the installed
distribution used for the successful rerun. All six cuts use a real installed
Engine, exact process kill, the same accepted row and preparation on recovery,
pointer/settings B, and both indexed documents in search. The cut fixture now
distinguishes the already sealed queue receipt from the later operation result.
Each direct fixture stops its owned private stack.

The chat-turn capture now handles Worker-independent free chat and explicit
document overrides, binds a per-work immutable config, uses stable nested work
identity and routes hierarchical retrieval through the captured facade. The
full local/static/stress/installDist sweep passed at `tmp/2930-lane-f-coherent-full.txt`
(358 tasks, 12m05s). The previously red EngineRoot test used a Mockito service
that swallowed its producer encoder lease; its ownership-aware fixture and
focused rerun passed at `tmp/2929-engine-close-lease-fixture.txt`. Frontend
typecheck and unit tests passed at `tmp/2931-ui-typecheck.txt` and
`tmp/2932-ui-unit.txt`. The current installed standard-model
`installer-pointer-before-settings` cut passed at
`tmp/2933-current-installer-pointer-settings.txt`: exact Engine kill, same
accepted row and frozen preparation, pointer/settings B, two search hits and
owned-stack stop. This refreshes one of the six earlier successful cuts at the
current dirty source; it does not replace their individual evidence.

Open D1 acceptance includes fresh activation without a READY encoder, retained
old-model assets, held A query, settings conflict/model drift, cancellation and
duplicate RUNNING activation, then full Head request capture, A/B encoder
retirement and installed live no-restart Flow A proof. D1-4 through D1-17 must
be reconciled against their stage clauses; D2/E/F remain open. One coherent
hosted checkpoint is next. Do not push another commit while its system job runs.
Root owns Gradle, shared stack and integration; bounded agents do not run
builds. The older record below is historical context and does not supersede
these live facts.

### Continuation in progress after `b90005e2e` (09:00 UTC)

Hosted run `35840851483` finished red at `b90005e2e`: app-ui confirmed the
installer generation-bound settings regression, and system integration exceeded
its 35-minute job limit. No push occurred during that run. Retained log and
artifact: `tmp/2748-hosted-system-job.txt` and
`tmp/2749-hosted-integration-results`. The system log also recorded the old
revision's migration live-work-drain failure. Six operation fault cases failed;
the settings cases waited 170 seconds each for a legacy `settings-apply` hook,
while the current settings route records `reconfigure`. A direct installed run
proved the request returned HTTP 200 without a hook (`tmp/2751-settings-fault-direct.txt`).
The fixture now selects `reconfigure` exactly and reports a premature HTTP
outcome immediately. Installed crash cuts before acceptance, after acceptance,
and after settings commitment passed at the current uncommitted source in
`tmp/2754-settings-fault-direct.txt`, `tmp/2756-settings-fault-direct.txt`,
and `tmp/2757-settings-fault-direct.txt`. The uncommitted durable shutdown handoff
passed installed migration promotion, rollback, and requested restarts in
`tmp/2755-migration-direct.txt` and bulk post-promotion crash recovery in
`tmp/2758-bulk-direct.txt`. Each fixture used the real installed frontend and
Engine, checked the exact durable row/manifest state, and reported owned stop
with closed ports. Other bulk cuts and hosted replay remain due.

`b90005e2e` is the latest pushed checkpoint. The preceding `d010e63cf` unreadable failed-job-count
regression passed seven focused cutover tests (`tmp/2719-unreadable-cutover-count-fixed.txt`)
and affected static checks (`tmp/2720-unreadable-count-static.txt`). Hosted run
`35836534083` at that SHA finished red: build, Windows-native, search-worker,
platform-contracts and other completed fact lanes passed; app-ui failed the
generation-bound ONNX installer path, and system integration recorded migration
and installed settings/bulk recovery failures before cancellation. Logs and the
downloaded integration artifact are `tmp/2735-hosted-system-job.txt` and
`tmp/2736-hosted-integration-results`. These failures are open; the earlier green
candidate-context hosted run remains evidence for its cited revision only.
Read-only triage found the migration/bulk failure is a durable pending-owner
shutdown leak: producer exit did not release the accepted runner body and its
retained admission references, so live-work-drain refused the requested restart.
The settings fixture did not reach its fault marker in that earlier run. The
later direct run above identifies the stale operation-kind selector as the
cause; compact-model warnings were incidental.

Partial D1-4 checkpoint `b90005e2e` prepares a paired Head serving capture with a
physical client lease, installs its graph/orchestration under the shared publication
lock, restores them on precommit failure, and visibly degrades agent-tool registration
after a committed connect failure. The complete focused Head/UI and static
checkpoint check passed in `tmp/2737-head-publication-checkpoint.txt`; throwing
registration and rollback proof are also in `tmp/2731-head-registration-throw.txt`
and `tmp/2734-head-rollback.txt`. The uncommitted worker-services edit prepares a Green service view
borrowing the incumbent producer with an exact transfer lease and transferable
provider/watcher callbacks. It compiled and passed existing focused service tests
in `tmp/2725-publication-substrate-focused.txt`; static checks passed in
`tmp/2726-publication-substrate-static.txt` except one Head test-fixture PMD issue,
subsequently fixed. `KnowledgeServer.prepareServingSuccessor` now wires the
candidate through the canonical post-construction owner; it is not production
cutover wiring or composed activation proof. The untracked
`modules/app-inference/logs/` directory is not Lane F source and must not be staged.

Independent review found the earlier Head prepublication bridge/orchestration
side effects and hidden postpublication registration failure; the edits above
address those bounded defects. The selected D1-4 reader migration is still open:
production HTTP search/RAG/chat, MCP and async document paths do not yet use the
capture. The worker final mutation fence is also open: the existing SWITCHING
state check is best effort and watcher events bypass it. A correct fence needs
the `KnowledgeServer` cutover owner to close admission, drain accepted effects,
replay exact switch-buffer revisions, promote and publish Green, replay later
revisions, and reopen routing; abort must preserve/replay accepted mutations to
Blue. No Flow A live activation, stage D1, D2, E or F acceptance is claimed.

The uncommitted shutdown correction separates durable attempt handoff from
terminal row completion. After recorded producer exit, the runner detaches its
live body while retaining RUNNING; the coordinator completes only its private
stage so the executor releases its exact admission reference. The real three-
epoch bulk restart test now calls this drain at both requested cuts and passes
(`tmp/2741-shutdown-stage-release.txt`). Runner and Worker producer-transfer
focused tests/static checks passed in `tmp/2740-shutdown-handoff-and-producer-focused.txt`;
that run predates the final stage-release correction. Refute-first review and
wider integrated/hosted proof remain due. The separate Worker pause and Green
producer substrate are also uncommitted. Bounded pause tests/static passed at
`tmp/2738-worker-cutover-pause-focused.txt` and transfer ownership tests at
`tmp/2740-shutdown-handoff-and-producer-focused.txt`; neither connects the
final mutation fence or activates Green.

The final shutdown-handoff staged slice received a clear independent refute-first
review on 2026-09-23. The reviewer checked runner handle/callback serialization,
child-before-parent drain, admission release and root close ownership; no
actionable defect was found. The frozen affected rerun at `tmp/2759-shutdown-handoff-broad-rerun.txt`
passed app-engine and app-observability tests (991 cases, zero failures/errors/skips,
157 suites), affected PMD and repository Spotless. This is local proof of the
staged source; hosted evidence for its eventual commit remains due.

The [installer-generation amendment](evidence/D1/generation-native-cursor-design-2026-09-23.md#7-installer-produced-generation-candidate-2026-09-23-amendment)
is the selected D1 contract. Acquisition reports “Downloaded — activation
required”; a distinct approved HIGH/DURABLE/REINDEX row freezes the candidate,
full SettingsWitness, model identities, source and scope. The recorded coordinator
owns pointer-committed settings roll-forward through the existing runner. Ordinary
generation-bound settings refusal remains. This changes the activation flow and
supersedes the direct ONNX settings-write expectation; implementation, real-front,
fresh-index, retained-asset and pointer/settings crash-cut proof are open.

Continue autonomously in `.claude/worktrees/lane-f-pr1-verify`, branch
`codex/lane-f-pr1`. Main contains unrelated work; never edit or clean it.
Existing checkpoint commits/pushes to [PR727](https://github.com/justsearch-app/justsearch/pull/727)
are authorized; merge remains at stage F. No routine owner approval is pending.
Continue after status answers, commits and reviews. C2 is accepted; D1/D2/E/F
remain open. Root owns all Gradle runs, stack lifecycle and integration.

The user resumed this lane after Astra resolved the remaining design. This is not
scope completion. No user decision or permission is pending. Preserve the worktree;
a lifecycle hold is recorded through2026-09-29. The owned installed run was
officially stopped: quick_health reports ABSENT, no foreign runs and no inference
orphan. The latest corrected hosted run passed; the next unused verification label
must be checked against retained `tmp` artifacts before allocation.

Candidate-context checkpoint78fca2b67, audit correction22800c842, ordering
corrections79c63e9b0/358af2cb4, Windows test-clock correction22b76b850,
and strict state-read gap correctione3b7b6d3c are pushed to PR727.
Root owns runtime integration. Main remains untouched.

Implemented: captured inference/resolved configuration and strict adoption policy;
logical versus physical server ownership; publication after health/hash proof; rollback
to actual serving A; manager-serialized recovery; stale callback suppression; monitoring
only after health; preserved crash budgets and dead-row cleanup; strict terminal cleanup;
serving-based vision capability during APPLY_ONLY. No executor was added. Runtime register
F-019 and both inference-runtime skills carry the current ownership rules. D1-5 stage text
clarifies desired CONFIGURED versus serving APPLIED; this does not waive D1-4 composition.

Evidence:
-2459 full inference/static passes359 cases, zero failures/errors/skips,33 suites.
 Independent source/proof review was clear. Tests include actual distinct OS child retries,
 B launch paths/reasoning fallback under global C, detach cleanup refusal, both vision
 directions, and manager A preservation followed by manager B strict same-child adoption.
-2451–2455 negative controls each failed as intended (owner guard, post-close mode,
 transition lock, crash budget, clean exit); exact source bytes restored.2457 independently
 demonstrated both vision directions fail before the serving-context correction.
-2460 full Java/static/stress/installDist at78fca2b67:11559 cases,2 failures,31 skips,
 1805 suites,34 test tasks (27 reused),9m21s. Both failures were migration residue:
 UnreferencedCodeTest found3 no-arg test-only wrappers; SystemAccessFunnelTest found2 stale
 allowlist entries. Full log/XML/counts/source inventory are tmp/2460-candidate-integrated*.
-Hosted CI35678039813 at78fca2b67 failed those same audit test classes in app-ui and
 platform-contracts. All other jobs, including Windows-native and system integration,
 passed. Failed hosted logs retained in tmp/2465-hosted-failed.txt.
-The correction retires the3 wrappers, null-owner crash path and2 stale allowlist entries.
 Tests use narrow src/test LlamaServerTestAccess to capture the current real owner and
 invoke guarded production methods. Telemetry fixtures install logical ownership, close
 their schedulers and no longer carry obsolete Windows-only race exclusions.
-2462 passed94 audit cases but inference tests did not compile due to one missed method
 reference. Fixed;2464 passes453 cases/zero failures/errors/skips,58 suites:359 fresh
 inference cases plus94 audit cases reused from2462. Spotless/PMD pass. Complete evidence
 is tmp/2464-candidate-residue-focused*.
-2466 full static/stress/installDist at docs HEAD2e7f1e1f6 with runtime code22800c842:
 11559 cases,zero failures/errors,31 skips,1805 suites; complete log/XML/counts and
 source inventory are `tmp/2466-candidate-corrected-integrated*`.
-Hosted CI35793788189 at2e7f1e1f6 failed only the app-ui lane's app-services
 `RuntimeActivationServiceTest` at line226 (3043 cases, one failure). The terminal
 status was visible before component failure publication. Focused2468 passes28 cases;
 deterministic negative2469 fails on pre-fix production bytes exactly because it
 observes `failed` while publication is blocked.
-2470 at79c63e9b0 passes the full static/stress/installDist command:11560 cases,
 zero failures/errors,31 skips,1805 suites; logs/XML/counts/source inventory retained.
-Installed owned run191bb413 at79c63e9b0 used retained data and standard Qwen
 model. All four health components READY; runtime-client0.4.0 smoke passed;
 jseval one-query answer was exactly Captain Mortimer Flux with zero errors and
 zero anchor errors. Official stop reports portsClosed:true; post-stop health
 ABSENT with no foreign runs/orphan. `tmp/2472-runtime-smoke.txt` and
 `tmp/2473-model-query/tier2-eval.json` retain query evidence. The full account
 is [candidate-context proof](evidence/D1/candidate-context-plan-2026-09-22.md).
-2475 at358af2cb4 passes the full static/stress/installDist command:11561 cases,
 zero failures/errors,31 skips,1805 suites. Owned installed standard-model run
 25140142 at that commit passes four-component READY, runtime-client0.4.0 smoke
 and a real Qwen9B query with exact Captain Mortimer Flux, zero errors/anchors;
 official stop closed ports and left no foreign/orphan process. Logs and source
 inventory are `tmp/2475-candidate-publication-integrated*`,
 `tmp/2477-runtime-smoke.txt`, and `tmp/2478-model-query/tier2-eval.json`.
-Hosted CI35797331099 failed only a cold Windows parser-test deadline. Test-only
 commit22b76b850 made that bound accommodate cold bootstrap; focused2483 passed.
 CI35799156920 then passed Windows-native, app-ui and system integration, but
 search-worker failed once on the strict state reader reopening during the
 generation writer's temporary `state.json` rename gap. Narrow retry commit
 e3b7b6d3c preserves immediate missing-file failure and passed local focused
 2491/2492 plus static2499. Hosted CI35800678462 passed all13 jobs. Downloaded
 search-worker XML under `tmp/2506-hosted-search-worker-results` confirms the
 formerly failing strict reader and Linux replacement-gap cases both passed.

Immediate continuation:
1. Candidate-context integrated, installed standard-model, and hosted proof is
   complete at the cited revisions. Preserve the evidence and avoid replaying
   completed verification without a changed assumption.
2. Continue D1-4 prepared component composition/publication and all remaining
   stage acceptance. The coordinator ownership decisions and typed API-port design
   are in evidence/D1. The [2026-09-23 design resolution](evidence/design-resolution-2026-09-23.md)
   settles publication, teardown, generations/native/cursors and D2 ownership.
   All production acceptance is still required.

The refuted shared-freeze/interactive-only drain is replaced by the selected monotonic
closing and lifetime protocol. Monotonic admission closing is implemented locally;
teardown quiescence and native disposition remain open. D1/D2/E/F remain open. No scope was waived.
Closeout sweep reaped nothing: it retained two stale ui-shot records whose PIDs no longer
exist and reported the intentionally persistent OTLP sink. Do not force-kill these.

## Selected design and remaining implementation/proof

D1-4 ownership decisions remain in
[evidence/D1/reconfigure-owner-decisions-2026-09-22.md](evidence/D1/reconfigure-owner-decisions-2026-09-22.md).
Full-witness C2 authority, the typed patch domain, dependency/value projections,
D1 dispatch and all D1/D2/E/F acceptance remain binding, including the14 identity
gaps. Historical checkpoints are in a [separate evidence record](handoff-checkpoint-history-2026-09-22.md);
their old next-run and next-action statements are not the continuation queue.

D1 Flow A must reconcile streaming core.reindex versus captured core.bulk-reindex:
abandonment cannot delete C2 evidence before terminal ownership and exact queue
ACK. The generation decision now fixes COMPLETE_WITH_GAPS/accept-gaps/live activation
ordering, approval binding and forward recovery; implementation/proof remain open.

## Active ownership

Root owns Gradle, stack, integration, and the retained worktree. Native exact-instance
leases and the generative precommit seam were committed and pushed as partial D1
checkpoints `4db845fdd` and `933f903bb`; neither is connected D1 acceptance. The
read-only composition trace located the physical index/encoder owners in
`KnowledgeServer` and identified their current mutable rebuild paths. A separate
generative owner trace is pending. Check live ownership before changing a delegated path.

The partial D1 review found five defects. Root fixed exact runner commit admission,
retained the current serving API-port resolution until restart, translated closing at
accepted-work attachment, and mapped malformed envelopes to HTTP 400. Component keys
currently fail closed with `COMPONENT_PREPARATION_REQUIRED` until the fixed production
composition collaborator is connected; this is a safe interim state, not D1-4 acceptance.
Focused `tmp/2509-serving-port-config-tests.txt` (3 cases) and
`tmp/2510-runner-admission-tests.txt` (37 runner, 7 app-api cases) pass. Native focused
`tmp/2511-native-lease-focused.txt` passed 25 cases; full ort-common/stress
`tmp/2512-native-lease-suite.txt` ran 175 and failed one stress oracle because retirement
started while CPU creation was in progress before the test's post-close flag. Its full
suite XML is retained under `tmp/2512-native-lease-results`. The native
owner corrected the oracle, retained exact-instance native leases and added a typed
retirement disposition. Root's `tmp/2515-d1-focused-integration.txt` passed the full
ort-common suite plus opt-in stress: 176 cases, zero failures/errors. The same combined
run passed targeted app-inference preparation, app-engine admission and UI compilation,
but app-services had four failures. One new test asserted the wrong exception type and
was corrected; three existing admission tests needed an explicit test-only component
composer. `tmp/2517-settings-composer-tests.txt` then passed 26 coordinator and five
public-admission cases, zero failures/errors. The other D1 checks from 2515 were not
rerun at the new dirty revision. `tmp/2518-fixed-composer-compile.txt` compiled the
new fixed composer; it is not yet wired to the physical production owners.

Native lifetime follow-up at dirty revision after `933f903bb`: the connected
shutdown suite in `tmp/2525-native-shutdown-connected.txt` passed 53 cases
covering surface retirement, KnowledgeServer close/retry, bootstrap lock
retention, exit selection and HeadlessApp binding. The exit selection includes
an isolated child JVM: its shutdown hook runs only for confirmed native
quiescence. The first child invocation (`tmp/2522-native-exit-child-tests.txt`)
hit the Windows command-line length limit; the corrected argfile invocation
passed in `tmp/2523-native-exit-child-tests.txt`. Relevant module Spotless checks
passed in `tmp/2526-native-shutdown-spotless.txt`. This is local focused proof,
not installed native held-call or D1-13 acceptance. `NativeSessionHandle`'s
full 176-case suite including opt-in stress passed at the 2515 revision;
later surface/shutdown changes did not alter that committed handle.

The local D1-13 continuation added monotonic runner body/receipt tracking and a
bounded drain. `HeadlessApp` now closes admission, stops recorded ingestion
producers through `EngineRoot`, waits admitted work and runner bodies, then
guards Head, checkpoint, index, operations, process resources and instance-lock
closure with observed completion. Fatal-startup `finally` applies the same
work/owner dependency guards. A refused drain retains dependencies and produces
an unclean result. `tmp/2529-live-drain-focused.txt` passed 21 runner and 14
shutdown wiring cases; `tmp/2531-producer-drain-focused.txt` passed the same
focused runner/shutdown coverage plus recorded bulk ingestion coverage. A
partial native initializer with no published surface is now conservatively
UNQUIESCED; `tmp/2532-native-null-surface.txt` passed the KnowledgeServer
close-completion cases. Relevant Spotless passed at `tmp/2530-d1-lifetime-spotless.txt`
before the latest producer/null-surface additions and must be rerun. These
dirty changes are not yet a checkpoint or full D1-13 proof; real installed
native held-call, broader store-user/reader inventory, integrated and hosted
checks remain outstanding.

Independent lifetime review found that externally initiated JVM shutdown starts
hooks concurrently; HeadlessApp's late hook cannot order ahead of ORT's hook.
The selected design now limits native race exclusion to controlled exit selection
before JVM shutdown. The hook's conditional hard stop remains mitigation only.
`tmp/2537-jvm-competing-hook.txt` passed an isolated adversarial two-hook case
which proves the competing hook can run during cleanup. The product-owned
uncaught handler now writes its crash report and hard-stops directly; boot
contract validation moved before asynchronous native-capable startup. Focused
exit authority tests passed at `tmp/2538-exit-authority-focused.txt`.
The UI-web typecheck passed at `tmp/2539-ui-web-typecheck.txt` and its unit
suite passed 6,598 tests across 490 files at `tmp/2540-ui-web-unit.txt`; the
Happy DOM teardown emitted AbortError diagnostics despite exit code zero.
The same review found D1-13 still lacks an explicit deadline/cancellation input
for CPU recreation and typed retired/unavailable outcomes; zero-argument
`acquireCpu()` can wait indefinitely behind a held failed instance. Treat this
as an open defect, not covered by the 176-case native stress pass.

Generative candidate preparation now has manager-local managed enable,
disable and reconfigure values plus a lifecycle guard callback. Root wired the
fixed composer/coordinator so owner guards nest before publication write;
focused manager tests passed at `tmp/2534-generative-prepared.txt` and
coordinator/composer tests at `tmp/2535-owner-lock-coordinator.txt` and
`tmp/2536-owner-lock-order.txt`. Production HeadlessApp now uses the fixed
composer with a registered generative owner. Physical index and encoder
owners, paired captures and owner-level installed checks remain open.

The coordinator now invokes opaque owner preparation before file replacement, validates
under publication write, installs after the proven witness and delivers callbacks/retirement
outside physical locks. A fixed composer can serialize owner preparation and prebuild one
registry observation batch. Production still uses the unavailable default, so affected
component settings safely refuse. Index and encoder candidate construction, generative
desired-state handling, paired reader capture/leases and shutdown dependency closure
remain open. Do not describe the current focused test pass as D1-4 acceptance.

At the next dirty D1 slice, `SessionHandle` requires an explicit monotonic acquisition
request and returns typed retired, unavailable and deadline outcomes; CPU replacement
waits are bounded. Root's `tmp/2543-ort-acquisition-focused.txt` passed all 180
ort-common tests after correcting a stale assertion to the new unavailable type.
`tmp/2542-ort-inference-prepared.txt` also ran 367 app-inference tests with zero
failures; its aggregate Gradle exit was red solely because that earlier native
assertion still expected `IllegalStateException`. These are local module proofs
at the dirty revision, not D1-13 installed native held-call or D2 fairness proof.
Worker encoder, reranker, citation and benchmark call sites now pass explicit
finite requests. `tmp/2544-native-callers-compile.txt` compiled the affected
callers. The broader `tmp/2545-native-caller-focused.txt` run found one stale
indexer-worker mock close/status fixture; the corrected fixture passed its
focused class at `tmp/2546-indexer-fixture-focused.txt`. Broader native-caller
unit coverage remains to be rerun at the next integrated test boundary.

The fixed settings composer now builds the registry observation batch under the
final publication writer, before file replacement, so unrelated precommit
component transitions cannot stale a batch prepared earlier. A failed owner abort
or retirement retains the apply permit; the coordinator marks failed abort as
uncertain for ordered recovery and requests a successor after committed retirement
failure. A postcommit prepared-install exception also retains the committed file
witness for ordered boot reconstruction. Focused coordinator and composer tests
passed at `tmp/2547-component-owner-coordinator.txt`.

The first dirty D1 compiling checkpoint passed `./gradlew.bat build -x test`
at `tmp/2552-d1-checkpoint-build.txt` (333 tasks, successful). It includes UI
and app-services integration tests and static checks, but skips ordinary unit
tests. Earlier attempts `tmp/2548-d1-checkpoint-compile.txt` and
`tmp/2551-d1-checkpoint-build.txt` exposed PMD findings, corrected without
relaxing the gate. `tmp/2549-d1-checkpoint-compile.txt` found a UI integration
fixture that still used the unavailable composer. The fixture now uses the
production manager-absent generative owner, and the focused policy class passed
at `tmp/2550-ai-pack-policy-integration.txt`. Full unit, installed native,
physical owner and hosted proof still remain. This compiling checkpoint does
not satisfy D1-4, D1-13, D2, E or F acceptance.

Checkpoint `0b68b4e29` (109 files) was committed and pushed to PR727 after the
first compiling build. The subsequent `./gradlew.bat test` at
`tmp/2553-d1-checkpoint-full-test.txt` stopped after two audit failures:
HeadlessApp retained obsolete private shutdown helpers, and the fatal startup
`System.exit` call did not name its EngineExit constant at the call site. Full
failed XML is retained under `tmp/2553-failed-results`. The obsolete helpers
were removed, tests call the canonical binding, and the exit site names the
constant. Focused audit, shutdown, compile and formatting checks passed at
`tmp/2554-shutdown-audit-compile.txt` through
`tmp/2557-shutdown-wiring-focused.txt`.

The next full suite at `tmp/2558-d1-checkpoint-full-test-rerun.txt` passed those
two audits and then reported 24 app-services failures. The full failed XML is
retained under `tmp/2558-app-services-results`. Recovery-reset code had tried
to inspect quarantined settings during D1 change classification; that path now
uses its established successor-boot behavior and avoids the unreadable file.
The normal-reset test fixture now constructs the same serving config as boot.
`tmp/2561-reset-focused.txt` passes the reset class. The new `core.reconfigure`
catalog entry now has its validator fixture binding, i18n strings and updated
wire golden, preserving all 32 prior entries. Reset, catalog and coordinator
focused checks passed together at `tmp/2562-settings-witness-catalog.txt`.

Independent read-only review of `0b68b4e29` found four concrete D1 publication
defects: generative requests could use B before commit; a dead prepared B could
still validate READY; the reserved settings witness was not revalidated just
before file replacement; and prepared READY→READY registry publication reset
`stateSince`. Checkpoint `ea7f6e1c9` adds a generative admission/lease
gate for chat, vision, stream and token endpoints, bounded drain before A stops,
staging observation, candidate liveness/invalidation checks, final witness
reinspection under publication write, and registry state-epoch normalization.
Focused gate/manager tests passed at `tmp/2565-generative-candidate-death.txt`,
settings/registry checks at `tmp/2566-publication-review-focused.txt`, and the
full compile/static/integration `build -x test` passed at
`tmp/2571-d1-review-checkpoint-build.txt`. The earlier `tmp/2568` and `tmp/2570`
build attempts exposed PMD findings corrected without suppressing the rules.
The checkpoint was pushed to PR727. `tmp/2572-generative-full-unit.txt` passes
the complete app-inference unit suite (367 tests). The complete app-services
run at `tmp/2573-app-services-full-unit.txt` ran 3,070 tests with eight failures
and three skips; its XML is retained under `tmp/2573-app-services-results`.
It is not full D1 acceptance.

The remaining app-services red classes in the 2573 suite are
`AiInstallOnnxSettingsProducerTest` and seven
`RuntimeActivationServiceChatProfileTest` cases. Their install-time settings
paths still expect generation-bound model paths to become serving config or
perform a second physical inference apply after settings persistence. D1-4/D1-5
require those paths to join the prepared owner route; do not turn the tests green
by allowing a generation-bound reconfigure or restoring apply-then-compensate.
The physical index/encoder owners, paired reader captures, installed held-call
and hosted proof remain open. No stage acceptance is claimed.

The current dirty activation slice threads a typed transient `ChatModelProfile`
and forced same-path model refresh through the existing accepted settings row,
runner, coordinator and fixed generative composer. `RuntimeActivationService`
and Install AI's chat-model stage no longer perform a separate inference apply
after settings commitment. The profile is not a stored model path or JVM
property. Internal physical intent is now frozen in accepted private preparation;
the runner checks the executing context against it before arming SQL. A committed
profile/refresh row remains RUNNING after a crash until the sealed fixed owner
reconstructs and installs the target before API bind. Missing preparation blocks
serving. Focused regression `tmp/2593-candidate-binding-and-repair.txt` passes
same-path repair, accepted-context binding and committed-profile boot recovery;
the broad `tmp/2594-d1-integrated-tests.txt` is running. The earlier full
`tmp/2584-activation-integrated-tests.txt` reported one intentional ONNX
generation-bound gap plus stale app-launcher/UI test fixtures; their XML is
retained at `tmp/2584-integrated-results` and fixtures have been recut.
The next full run `tmp/2594-d1-integrated-tests.txt` had 3,078 app-services
tests with two ONNX installer failures; the incomplete-attempt mock used the
old three-argument seam and has been corrected in production routing. Its XML
is retained at `tmp/2594-app-services-results`. All other executed modules
passed. Independent review found that failed boot composition would cause a
fatal restart loop, profile activation could override an operator model path,
and Install AI refreshed an unrelated operator model. The current dirty source
returns an unresolved boot verdict, fences generative admission and publishes
UNAVAILABLE while API/text can start; it checks operator precedence before
self-test and owner prepare; Install AI forces refresh only for the effective
model. Focused proof is `tmp/2597-recovery-guard-focused.txt` and
`tmp/2598-boot-degrade-focused.txt`; full compile/static/integration proof is
`tmp/2599-recovery-guard-build.txt`. The full unit run
`tmp/2600-d1-integrated-after-review.txt` finished in 9m25s with 11,648 cases,
one failure, zero errors and 32 skips across 1,810 suites and 34 test tasks.
Its sole failure is `AiInstallOnnxSettingsProducerTest.stageCommitsEligiblePathsTogetherWithoutPropertyPromotionAndPreservesEarlierStage`:
the coordinator correctly refuses generation-bound ONNX paths until the recorded
bulk owner can carry the staged target. All other executed tasks passed. XML and
task counts are preserved under `tmp/2600-d1-integrated-after-review-xml` and
`tmp/2600-d1-integrated-after-review-counts.json`; 19 test tasks were reused.
The ONNX stage still requires the recorded generation owner route; no D1-4
acceptance is claimed.

Checkpoint `c876a4e85` with that recovery slice and the honest red result was
pushed to PR727. Installed validation then found two boot-source defects. The
dev runner publishes `JUSTSEARCH_SERVER_EXE` for its selected CUDA executable;
activation wrongly refused even when the requested variant named that same
file. A guarded same-path correction permits that identity and still refuses a
different operator executable; focused app-services test/static proof is
`tmp/2605-operator-exe-focused.txt`. Next, policy discovery in `setupInfra`
wrote `policy.gpu_acceleration_enabled` after the initial resolved config, so
the first settings candidate appeared to change the `encoders` component.
Policy discovery now precedes the boot refresh and runtime selection;
`HeadlessAppConfigRebuildOrderingTest` and UI static proof pass in
`tmp/2609-policy-boot-focused.txt`. Diagnostic proof of the two original
refusals is in installed runs `tmp/2604-d1-dev-runner.txt`,
`tmp/2606-d1-dev-runner.txt` and `tmp/2608-d1-dev-runner.txt`.

Installed run `a44315b6` after both corrections activated standard Qwen9B in
10.9s, reported API/index/encoders/generative READY and passed runtime-client
smoke (`tmp/2611-d1-runtime-smoke.txt`). A jseval query against the dev
runner's default `.dev-data` had no cinnamon source and correctly returned
insufficient information (`tmp/2613-model-query/tier2-eval.json`); this was
data provenance, not model activation proof of that answer. Owned run
`6948e84e` then used the exact retained corpus directory from prior run
`25140142`: standard model already active, runtime-client0.4.0 smoke passed
(`tmp/2615-retained-runtime-smoke.txt`), and the real-model jseval query
answered Captain Mortimer Flux exactly with zero errors/anchor errors
(`tmp/2616-retained-model-query/tier2-eval.json`). Official stop reported
`portsClosed:true`; quick_health returned ABSENT, no foreign run/orphan. The
local dev MCP preflight still checks the retired Worker distribution, so the
same shared-lease `dev-runner.cjs` started/stopped these runs; MCP health and
activation operated on their recorded run IDs. This installed proof belongs to
the boot correction atop `c876a4e85`; full D1 remains open.

Install AI's embedding, NER and SPLADE paths are generation-bound by the governed
register. The remaining ONNX test failure is therefore an ownership gap, not a
reason to permit ordinary reconfigure to bypass `core.bulk-reindex`. The chosen
next connection is one explicit installer-owned recorded generation target that
carries the staged candidate and full witness to the bulk owner; its Green
composition and promotion must use D1-8/D1-12. The current bulk plan captures
only live config, and the installer has no generation-owner port, so that path
is not yet implemented or verified. It must preserve staged install merging,
whole-stage conflict refusal, and no global property promotion.

The first D1-4 paired HTTP search slice is in progress after the installed proof.
`KnowledgeSearchEngine` now captures one ConfigStore snapshot and the exact
`KnowledgeClient` under their shared publication lock; its query classification,
QU/filter normalization, retrieval, rerank and capability projection retain that
view through the synchronous search. `KnowledgeServerBootstrap` owns a counted
client lease and refuses close while requests hold it; close stops new captures
under publication write, waits outside that lock, and retains the owner on
timeout/close failure. Suggest, folder browse and status probes now use client
leases. Focused pipeline/controller tests passed at `tmp/2621-search-capture-focused.txt`;
the physical close interleaving passed at `tmp/2622-bootstrap-lease-focused.txt`;
the concurrent config-change search passed at `tmp/2628-search-config-interleaving.txt`.
Static/compile/integration `build -x test` passed at `tmp/2629-search-lease-build.txt`.
The first app-services suite exposed four stale mocks plus the already known ONNX
failure (`tmp/2624-app-services-lease-tests.txt`, XML at
`tmp/2624-app-services-results`); after correcting those mocks, the suite ran
3,087 tests, one failure, three skips (`tmp/2630-app-services-lease-rerun.txt`,
XML at `tmp/2630-app-services-results`). The sole failure remains the ONNX
generation-owner gap. This slice is still under independent review and not D1-4
acceptance: HeadAssembly graph publication, other raw client paths, async readers,
physical generation owners and installed concurrency proof remain.

Independent refute-first review then found four blocking lifetime defects in this
first slice. (1) A unary Engine call can return at its deadline while its worker
task continues, so the caller's lease ends before actual work exit. (2) The
`EngineKnowledgeClient` wrapper re-reads mutable `WorkerAppServices` on each call;
retaining the wrapper does not retain the serving generation or prevent
`KnowledgeServer` from closing A during a multi-leg request. (3) The current
bootstrap close stops lease admission without publishing index unavailability,
so a timed-out drain can leave READY advertised while all new leases refuse.
(4) Startup publishes the client before physical health/initialization verifies
it, allowing a direct capture of an unready candidate. These are design-contract
failures, not test waivers. The next correction must bind the owner-local serving
view and actual asynchronous work lifetime under the selected generation protocol,
then add the paused-worker/deadline, base-to-rerank swap, retirement-readiness and
paused-startup interleavings before claiming this path.
The first correction removes the search-time global ConfigStore fallback, keeps
startup retry on the original cause when teardown refuses, and gates new client
leases until the first healthy initialization. Focused startup and retry tests
pass at `tmp/2631-lease-owner-corrections.txt` and
`tmp/2633-bootstrap-private-start.txt`; `build -x test` passes at
`tmp/2634-owner-capture-build.txt`. These corrections do not resolve the
reviewer's asynchronous, physical serving-view, readiness or raw-reader defects.
Resume reconciliation, debug state, pending inference counts and the index-drift
probe now use captured client leases. The first UI suite found the existing boot
policy refresh bypassed the settings-writer architecture guard and one suggest
test still mocked the raw getter (`tmp/2635-ui-reader-lease-tests.txt`, XML at
`tmp/2635-ui-results`). Policy discovery now runs before the exact authorized
`rebuildAfterPostBuildWrites` call in `resolveConfig`; the suggest fixture uses
the captured lease. The focused architectural and behavioral checks pass at
`tmp/2636-ui-reader-corrections.txt`; full UI tests pass at
`tmp/2637-ui-reader-lease-rerun.txt`: 1,348 tests, zero failures, one skip,
206 suites. Remaining raw clients and physical serving-view replacement still
block D1-4.
The resumed health-monitor fixture was recut to retain a client lease
(`tmp/2639-resume-lease-focused.txt`). Latest `build -x test` is green at
`tmp/2640-d1-reader-wip-build.txt`. Latest full app-services run is
`tmp/2641-app-services-wip-tests.txt`: 3,089 tests, one failure, three skips;
XML at `tmp/2641-app-services-results`. Its only failure is the unchanged ONNX
recorded-generation route. This is a WIP checkpoint, not D1-4 acceptance;
the independent review's physical view and async lifetime blockers remain.

The next uncommitted D1 correction captures a `KnowledgeServer.ServingView` in the
Head client lease, pins each unary Engine task's exact `WorkerAppServices` through
actual worker exit, and routes synchronous search plus short status/debug probes
through `ClientLease.withClient`. A deadline regression passes at
`tmp/2648-serving-deadline-test.txt`; an owner retirement pause test passes at
`tmp/2650-serving-retirement-tests.txt`. Focused app-services tests pass at
`tmp/2647-serving-view-focused-tests.txt`; the UI suite passes all 1,348 tests
(one skip) at `tmp/2656-ui-lease-full.txt`. Dev reload now shares the runtime
replacement lock and retains/cleans an unpublished candidate on startup failure;
its cleanup/retry regression passes at `tmp/2659-dev-reload-cleanup-test.txt`.
The physical view implementation remains WIP: independent review found that
deferred model wiring mutates a published service, in-place replacement can leave
all captures fenced while the component still advertises READY, streams and
HeadAssembly cached clients bypass lifetime capture, and side-by-side A/B
publication while A is held is not yet supported. No D1-4 or generation-lifetime
acceptance is claimed. The existing `EncoderBindings`/`SearchOrchestrator` seam
was identified for a single immutable model snapshot; text search currently
waits on `modelReadyLatch`, so an EMPTY-model view needs a deliberate text-path
cut rather than only wrapping current mutable fields.

Hosted run 35821663891 for pushed checkpoint `15535fea8` passed build,
platform-contracts, search-worker, Windows-native, jseval Python, license,
secret scan and measured axe. Public claims failed `runtime-state/unregistered-
referencer` for the new `GenerativeSettingsComponentOwner`; the consumer was
registered locally and `node scripts/governance/run.mjs --gate runtime-state
--mode gate` now passes. App-ui failed the unreferenced-method audit on two
superseded overloads; they were retired locally and the focused ArchUnit plus
behavior rerun passes at `tmp/2665-dead-overload-cleanup-rerun.txt`. The hosted
integration job was still pending at the last check. These are local corrections
only until a later pushed revision completes hosted checks. The installed
standard-model proof remains at the prior `b9dd6ace4` checkpoint; no new stack
was started for this WIP. The shared stack remains stopped.
The first integrated `build -x test` exposed only PMD findings introduced in the
new view/reload tests and one newly imported type (`tmp/2667-serving-wip-build.txt`).
Their source-level corrections passed affected PMD at
`tmp/2668-serving-pmd-corrections.txt`, and the full `build -x test` now passes at
`tmp/2669-serving-wip-build-green.txt`. `spotlessCheck` passed at
`tmp/2666-serving-wip-spotless.txt`. Neither build is a full test suite or D1
acceptance proof.

Hosted run 35824174175 for `efa50aa21` passed its build but failed Public claims
on `operation-surface/undeclared-surface` for the private settings candidate
preparation codec. It is an accepted-row consumer, not a lifecycle fork; the
register now declares it and the local gate passes. The hosted search-worker
job failed three retries of one reflection test: it invoked private service
reconstruction without production's preceding retirement. The test now supplies
that owner precondition; the focused rerun passes at
`tmp/2674-hosted-reconstruction-regression.txt`. The new `EncoderBindings` uses
one immutable publication snapshot; `SearchOrchestrator` reads the encoder pair
from one snapshot. Focused test and static checks pass at
`tmp/2670-encoder-snapshot-test.txt` and `tmp/2671-encoder-snapshot-static.txt`.
Interrupted pre-destruction retirement now restores capture admission to live A;
the owner-lock regression and static checks pass at
`tmp/2673-serving-retire-owner-test.txt`. These corrections are not yet hosted
at a new revision, and the published model set, async streams, and side-by-side
A/B publication remain open D1 blockers.

## 2026-09-23 deferred A/B lifetime checkpoint (WIP)

PR727's `3f4f6e07a` hosted run 35824997755 passed Public claims, build,
Windows-native and platform contracts. App-ui failed four distinct cases across
retries: three stale `ClientLease.withClient` mocks (locally corrected at
`tmp/2682-hosted-ui-fixtures.txt`) and the real installer ONNX setting route.
The latter correctly refuses ordinary generation-bound settings apply. The
search-worker and system-integration hosted jobs were cancelled after the
app-ui failure; neither is hosted acceptance proof for this checkpoint.

The uncommitted lifetime slice now opens a deferred B writer beside A's live
reader, composes B services, starts B's indexing thread behind an activation
gate, publishes the complete B serving view, then releases the gate. A's issued
leases continue on A until actual task/stream exit; cleanup runs outside the
swap and publication locks. Candidate services and prepared runtime retain a
joint retry owner on refusal. Retired cleanup is retried on the existing
maintenance tick and at shutdown. Model initialization pins B plus any
still-issued A under swap/publication ordering and wires both before readiness
release. `EngineKnowledgeClient` now binds queued unary, fanout and stream work
to the exact physical view until actual exit. The old direct
`DeferredRuntime.upgradeWriter` API was removed; adapter tests use the
two-step prepared owner. Canonical adapter/schema docs were updated, and docs
regeneration plus all five docs checks passed.

Evidence: the prior broad run found eight app-engine failures and a Worker
shutdown deadlock (`tmp/2689-serving-affected-suites.txt`, thread dump
`tmp/2690-indexer-worker-threaddump.txt`). The corrected focused behavior
passed at `tmp/2693-focused-owners-static.txt` apart from a PMD qualifier fixed
immediately afterward. Gated loop and abandonment tests passed with PMD and
Spotless at `tmp/2699-gated-loop-focused.txt`; model capture and A/B startup
tests passed at `tmp/2700-model-capture-owner.txt`; adapter migration passed at
`tmp/2701-retire-legacy-upgrade.txt`. The latest full affected-module run,
`tmp/2702-lifetime-integrated-affected.txt`, passed adapter, worker-services,
indexer-worker and app-engine tests (including stress), PMD and Spotless in
11m15s. Independent refute-first review found no remaining material race in
this slice; its final report named missing acceptance proof below.

Still required before D1 lifetime acceptance: hold a production A service
request at model readiness through B publication and prove both views receive
the selected model; inject retired A cleanup refusal and prove a later retry;
run a deterministic shutdown-versus-deferred-initializer lock-order regression.
The existing raw A-reader test does not prove model/service coherence. D1-4
also still needs complete Head graph publication and installed concurrency
proof. D1-8/D1-12 still need the recorded installer generation target, complete
generation-owned `EncoderSet`, live promotion/replay/gap protocol and model
identity. D2/E/F remain open. Shared stack has stayed stopped; no new installed
or hosted proof has been claimed for this WIP slice.

The three named lifetime regressions are now implemented in the Worker tests.
`KnowledgeServerStartupConfigurationTest` holds a real A search call at model
readiness through B publication and checks both service instances bind the
selected provider before release. `KnowledgeServerDeferredRetirementTest`
injects an A close refusal, observes retained cleanup ownership and retry,
and deterministically overlaps shutdown with an initializer waiting for
`runtimeSwapLock`. The first focused run exposed a test-only Java checked
exception and then thread-local Mockito static mocks on the wrong thread;
both were corrected without weakening production checks. The final focused
Worker tests, PMD and Spotless passed at `tmp/2708-lifetime-regressions.txt`
(seven tests, 2026-09-23). A broader post-test integrated run and hosted run
for this test revision remain due; these tests do not close D1-4 Head graph or
installed proof. The installer ONNX path still enters ordinary settings apply,
which correctly refuses generation-bound model paths; it needs a recorded
generation target rather than a relaxation of that refusal.

The Engine applied-generation capture also had a stale supplier check after
acquiring its exact A serving lease: publication of B caused an otherwise valid
A capture to abort. The check now applies only to supplier-only calls with no
retained lease; those still refuse a stale rebound. A held A/B regression first
passed at `tmp/2709-applied-generation-held-view.txt`; restoring the old
unconditional check made that exact test fail with ABORTED at
`tmp/2710-applied-generation-negative.txt`. The existing supplier-only
A→B→A refusal test exposed an overbroad first correction, so the final
lease-conditional version ran both test classes plus PMD and Spotless green at
`tmp/2712-applied-generation-two-paths.txt`. This does not yet prove the
complete Head config/component/graph paired-reader capture.
The subsequent affected-module integrated run at `38cce11ae` passed adapter,
worker-services, indexer-worker and app-engine tests with stress enabled, plus
affected PMD and repository Spotless, in 9m25s at
`tmp/2713-post-lifetime-affected-integrated.txt`. This run does not include the
remaining app-services installer failure or installed model proof for this WIP.

The `e709bd33d` hosted run 35830605012 passed Public claims, search-worker,
platform-contracts, build, jseval, secret scan and notices. App-ui failed only
`AiInstallOnnxSettingsProducerTest.stageCommitsEligiblePathsTogetherWithoutPropertyPromotionAndPreservesEarlierStage`
across its three retries: the intentional generation-bound refusal. Windows-native
and system integration were then cancelled, so neither is hosted proof for this
revision. The focused lifetime tests were committed and pushed as `9519b0b1e`;
its own hosted run must still be checked.

The first Flow A owner seam is WIP: `IndexGenerationManager` now uses one
reentrant process state guard in place of its 14 synchronized entries, and a
strict, one-use `RecordedPromotion` lease can hold that guard across a future
publication section. Focused recorded boot/lock-order tests, PMD and Spotless
passed at `tmp/2714-recorded-promotion-owner.txt`; the full worker-core suite
passed at `tmp/2715-worker-core-state-lock-suite.txt`. Independent review found
that a post-move IOException needed an exact committed-pointer reread and the
test needed to show a competitor stayed blocked after nested promotion. Both
were added with a package-private post-move fault probe; focused tests/static
passed at `tmp/2716-promotion-ambiguity.txt`. A second refute-first pass found
the blocking test could pass on mere thread scheduling delay. It now observes
the actual queued `STATE_CONTROL` waiter both before and after nested promotion;
focused tests, PMD and Spotless pass at `tmp/2717-promotion-contention.txt`.
This API is not yet connected to
`KnowledgeServerMigrationOps` or `RecordedIngestionCoordinator`, and Flow A's
mutation intake fence, prepared Green publication, no-restart cutover and
installed evidence remain open. Do not claim D1-8 from this seam.

D1-9's failed-job-count guard no longer treats an unreadable failure summary
as zero. It now leaves Blue active, marks the migration FAILED and drains the
switch buffer. The new refusal regression and the existing cutover evidence
tests passed at `tmp/2719-unreadable-cutover-count-fixed.txt` (7 tests), with
Indexer Worker Spotless and PMD at `tmp/2720-unreadable-count-static.txt`. The
first focused run at `tmp/2718-unreadable-cutover-count.txt` exposed two old
tests whose mock queue returned a null summary; their fixture now supplies an
explicit zero-failure summary. This is a focused local proof only. The exact
final mutation fence and replay certification are still open.

## Evidence and owner map

| Concern | Governing record |
| --- | --- |
| Predecessor teachings and honest self-audit | [Resumption contract](evidence/C2/resume-2026-09-21.md) |
| In-depth workflow findings, measurements and proposed trial | [Workflow retrospective](evidence/workflow-retrospective-2026-09-22.md) |
| C2 accepted proof, including installed/stress/hosted/real-model | [C2 acceptance](evidence/C2/verification-2026-09-21.md) |
| D1 actual owners and captured configuration | [Owner map](evidence/D1/regrounding-2026-09-21.md), [component plan](evidence/D1/component-plan-2026-09-21.md), [wiring proof](evidence/D1/owner-wiring-2026-09-21.md) |
| Broad affected-module proof2298 at6c95d7989 | [Integrated verification](evidence/D1/owner-integrated-verification-2026-09-21.md) |
| Full-snapshot CAS and stateless reason retention | [Publication seam](evidence/D1/publication-seam-verification-2026-09-21.md) |
| Pure schema2 projection, trigger feedback and tool composition | [Schema/trigger proof](evidence/D1/schema-trigger-verification-2026-09-21.md) |
| Physical-health initialization and close/retry ownership | [Bootstrap proof](evidence/D1/bootstrap-initialization-verification-2026-09-21.md) |
| Runtime readiness and six-state decisions | [Readiness plan](evidence/D1/readiness-plan-2026-09-21.md) |
| Schema and host migration | [Schema consumers](evidence/D1/schema2-consumer-plan-2026-09-21.md), [host plan](evidence/D1/host-readiness-plan-2026-09-21.md) |
| Apply classification and audited missing readers | [Apply-register plan](evidence/D1/apply-register-plan-2026-09-21.md) |

## Working rules to retain

Start with the acceptance path, then reuse actual owners. Delegate bounded files
and deliverables; consolidate review and reassess after two substantive rounds.
Root owns shared state, stack and Gradle. Freeze compiled sources before a build.
Run focused checks while correcting and integrated checks at coherent boundaries;
collect independent static failures using `spotlessCheck pmdAll --continue`.
Workflow correction `b4d01c1cc` already records this in canonical guidance and both
CI-triage skills; no extra always-loaded policy copy is needed.

Keep compiling checkpoint commits per item, push each checkpoint and preserve WIP
at least hourly. Stage explicit paths; check native exit codes before mutations.
Preserve XML before reruns. Name tested revision, reuse, failures, skips and proof
tier; BUILD SUCCESSFUL or hosted job success alone is not test-level acceptance.
Refute expected-looking passes and reviewer mechanisms against the actual owner.
Do not replace a concurrency defect with an unnecessary marker, store or timer.

Discover paths with `rg --files`; use `-g` for filename globs and bounded excerpts.
Use UTF-8 editing. Root has repeatedly failed to apply the path/output discipline;
more wording is not evidence of improvement. The concrete context correction here
is to keep this handoff current, with history in a separate archive.

Raw logs/XML under this worktree's `tmp/` remain accessible through lane acceptance
plus30days; export before deleting the worktree. Use `--no-ignore` to discover
ignored evidence. Hashes supplement accessible artifacts rather than replace them.

[Historical handoff archive](handoff-history-through-2026-09-21.md) preserves the
prior1850-line record and old decisions. It is background, not a resumption queue;
read only the historical section needed to resolve a specific uncertainty.
