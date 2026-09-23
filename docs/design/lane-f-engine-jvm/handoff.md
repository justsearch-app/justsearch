# Lane F handoff: implementation orchestrator

Start with the [continuation brief](continuation-brief.md): the next two batches,
owner boundaries, acceptance endpoints and execution protocol. This handoff owns
current evidence; the brief does not narrow the remaining lane scope.

## Current state (2026-09-23)

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
