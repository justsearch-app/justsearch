# Lane F handoff: implementation orchestrator

Start with the [continuation brief](continuation-brief.md): the next two batches,
owner boundaries, acceptance endpoints and execution protocol. This handoff owns
current evidence; the brief does not narrow the remaining lane scope.

## Current state (2026-09-23)

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

Install AI's embedding, NER and SPLADE paths are generation-bound by the governed
register. The remaining ONNX test failure is therefore an ownership gap, not a
reason to permit ordinary reconfigure to bypass `core.bulk-reindex`. The chosen
next connection is one explicit installer-owned recorded generation target that
carries the staged candidate and full witness to the bulk owner; its Green
composition and promotion must use D1-8/D1-12. The current bulk plan captures
only live config, and the installer has no generation-owner port, so that path
is not yet implemented or verified. It must preserve staged install merging,
whole-stage conflict refusal, and no global property promotion.

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
