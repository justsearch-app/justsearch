# Lane F September 29 checkpoint history

This is retained checkpoint history, not the current queue. See [handoff](handoff.md)
for current state and [D1](stages/D1.md) for governing acceptance. Pending statements
below describe their original checkpoint and may be superseded.

## Current state (2026-09-29)

**Autonomous continuation:** the owner resumed remaining Lane F work in this
chat, retaining the D1/D2/E/F acceptance and checkpoint publication authority.
The startup-lifetime correction is pushed as `54b92132d243287af94c6a9358199520a3ec08db`.
Exact-SHA hosted CI passed at `36490652907`. Prior pushed head `de6cedf80` failed hosted run `36480997611` because
recording a recovery count replaced retained fatal-remedy evidence; that defect
and the independent lifetime findings are corrected with runnable regressions.
Initial open now owns the bootstrap lock before publication and through retry
backoff; close refuses a live opening within five seconds. Timer observation
stays nonblocking while the separate recovery worker performs physical work.
Late initial success, manual admission, owner-local close/open, and conditional
startup progress preserve the actual owner's lifetime.

Final stress-enabled suite plus Spotless/PMD passed in 9m11s at
`tmp/5407-d1-7-final-integrated.txt`, with 1,860 XML reports preserved in
`tmp/5407-suite-xml/` and source manifest `tmp/5407-d1-7-source-sha256.txt`.
`build -x test` passed in 21s at `tmp/5410-d1-7-final-build.txt`.
Regeneration coherence and canonical links passed at `tmp/5408` and `tmp/5409`.
Independent review found no remaining correction-slice defect. Negative controls
at `tmp/5385`, `tmp/5401`, and `tmp/5406` fail the intended assertions against
old close, retry-backoff, remedy-overwrite, and pre-lock publication behavior.
The earlier full-suite fixture failures are preserved at `tmp/5393-suite-xml/`;
the version and capacity assertions remain intact with corrected physical fixtures.

This is a prerequisite checkpoint, not D1-7 acceptance. General two-attempt
policy, all-component deadlines, optional physical owner actions, counted code-5
escalation, catalog/live snapshots, and installed recovery/model proof remain.
Continue D1-7 before D1-6 or D1-15. Do not merge before Stage F acceptance.

Current uncommitted implementation connects all physical owners to the generic
monitor: exact counted admission/terminal witnesses, two-attempt episodes,
nonblocking deadlines, fair dispatch, optional non-escalation and code-5 dispatch.
Generative recovery retains exact A, drains requests and replaces one native
process without an independent retry scheduler; 67 inference tests plus PMD pass
at `tmp/5447` (unchanged source). Encoder recovery captures exact pre-native plans
and identity, retains issued-view/partial-retirement ownership, supports failed
initial composition, and atomically publishes physical versions. Known missing
roles restore as explicit DEGRADED/UNAVAILABLE; new missing roles remain FAILED.

The connected focus at `tmp/5465` passed 34 Worker, 32 Engine and 32 services tests
plus PMD. Later encoder DEGRADED/version tests and services tests pass at
`tmp/5472`; that run remained red only for the new combined Bootstrap fixture's
missing recorded-ingestion attachment. The corrected real Bootstrap/Root suite
and distribution pass at `tmp/5473-bootstrap-health-focus-dist.log`. Direct Root
lock negative `tmp/5466` fails the intended concurrent-close assertion; restored
positive `tmp/5467` passes. Per-run manifests and original XML are retained.

Installed index proof is still pending. `tmp/5468` exposed startup-health/public
admission coupling; `tmp/5476` then exposed READY publication while native work
kept public serving fenced, causing repeated ten-second recovery episodes.
The corrected owner retains native/mutation fences while permitting exact READY
serving, including synchronous observers. Superseded replacements retire instead
of entering accepted-release cleanup. Independent review found no remaining
correction defect. Focused Root/Worker tests, PMD and distribution pass at
`tmp/5485-restored-positive-dist.log` (16s), with XML and source hashes retained.
Negative `tmp/5484` fails the intended READY-listener assertion against the old
fence; `tmp/5482` fails the physical-close assertion against old lost-CAS cleanup.
Both production files were restored byte-exactly before the positive run.

Standard generative recovery passes at `tmp/5483-generative-standard.log` using
the frozen `tmp/5479` distribution (the subsequent Root-only cleanup fix does not
change that owner). It records ONLINE intent, verifies and faults its private
9B native child, observes the deliberate executable-missing attempt 1, restores
and manually admits attempt 2, verifies a distinct child and receives real chat
`quokka`. The same Engine incarnation has restartCount 0; owned cleanup passes.
JAR and harness hashes are retained. Earlier `tmp/5477` lacked activation intent;
`tmp/5480` had an impossible exception-type predicate. Those are failed fixture
runs, not native acceptance. The corrected predicate was independently reread
against the actual typed exception contract and preserves failure observations.

`tmp/5486-installed-recovery.log` is red overall (1/3, 5m54s), but index
exhaustion passes counted exit5 plus successor standard VECTOR, and optional
generative exhaustion passes two failures/terminal503 without Engine restart.
Original XML/JAR/harness hashes and owned STOP0 reports are retained. Release
still fails: cached old status can overwrite new READY, and the accepted physical
view remains coupled to mutable narration-row equality. Both bounded ownership
corrections are being implemented. The ORT fixture audit also found missing
shared backoff for externally counted A restoration; the monitor correction
observes every admission count instead of adding a fixture barrier.
Connected `tmp/5487` passes Root/Worker/monitor and all affected PMD/compile;
it is red only for two UI fixtures still asking cached reads to publish health.
Those fixtures now explicitly invoke fresh sampling for fresh-health assertions.
Negative `tmp/5488` fails exactly on old cached READY clobber and omitted 20s
external-attempt backoff; source restoration is byte-exact and XML retained.
Restored positive `tmp/5489-restored-readiness-backoff-dist.log` passes UI status,
monitor, affected PMD, integration compilation and distribution in 26s; original
XML and source hashes are retained. Installed rerun `tmp/5490-installed-index.log`
is red (1/2, 5m37s): exhaustion again passes; release still takes an extra attempt.
The initial-failure branch leaves status bound to null until after the recovery
action returns. Its first READY listener therefore observes no Bootstrap and
demotes the accepted row before physical handover confirms. The bounded fix binds
the retained Bootstrap to status before starting the monitor, preserving normal
client fences and delaying full route handover until recovery succeeds. Do not
weaken terminal confirmation or fresh-health failure checks to mask this defect.
The failing XML is retained at `tmp/lane-f-takeover/5490-installed-xml/`.
The correction passes focused UI tests/PMD at `tmp/5492`; old-binding negative
`tmp/5493` fails the intended actual startup wiring assertion, then production
is restored byte-exactly. `tmp/5494-restored-pending-owner-dist.log` passes the
restored tests, PMD (including the packaged fixture), integration compilation and
distribution. Initial `tmp/5491` had an incomplete test snapshot and qualifier
lint failures; original XML is preserved separately. Installed `tmp/5495-installed-index.log`
passes both arcs in 3m51s: release has READY/count1/restartCount0; exhaustion has
counted TRANSIENT/code5, successor restartCount1 and READY. Both return one real
standard VECTOR hit with unchanged settings. XML is at `tmp/5495-installed-xml/`;
raw receipts and STOP0 reports are retained in fixtures
`writer-junit-d5e34464-0b83-4903-ba13-59d27b201142` and
`writer-junit-16045fb9-5847-4521-ba4a-c9b1ec4d32f3` under `tmp/lane-f-takeover/`.
Distribution stamp is `b0c25c940b8ad2b0`, with JAR/harness hashes retained.
The run also reveals duplicate main-finally cleanup after the ordered shutdown
already closed the component registry. A local post-latch completion fact now
guards fallback cleanup; initial-error cleanup keeps its original order. A new
installed log assertion must refute the old run and pass against the rebuilt fix.
Focused Headless tests/static/distribution pass at `tmp/5496` (35s); the retained
5495 trace fails the duplicate-cleanup oracle. `tmp/5497` is invalid for acceptance:
Gradle started lifecycle and integration test tasks concurrently, violating the
single-stack constraint. The owned encoder runs were stopped through dev-runner
with STOP0 receipts in `tmp/5497-overlap-owned*-stop.json`; rerun serially.
The installed task declarations now order integration, lifecycle, and packaged
port checks with `mustRunAfter`, without creating dependencies or skipping tests.
Isolated `tmp/5498-ort-source-a.log` passes real CUDA source-A recovery in 4m21s:
UNAVAILABLE/count1 after deliberate missing A, lexical service preserved,
manual202, READY/count2 with A's applied version, CUDA before/restored, two VECTOR
hits during gap and after cancellation. XML is retained at `tmp/5498-ort-xml/`;
raw receipts/STOP0/cache-prune reports are in
`lifecycle-recompose-failure-b7e26b4c-b665-41c1-a40d-ab066547929f`.
Isolated index cleanup rerun `tmp/5499-installed-cleanup.log` is red: release
reaches READY/count1 but immediate vector-document ingestion becomes a durable
FAILED/UNCAUGHT_EXCEPTION row before procedure start (attempts0, three milliseconds
from acceptance to failure). Fixture `writer-junit-2b7633f3-b45a-4509-a3d8-1ed7606747b2`
retains the operation DB and logs. Root cause: READY preceded required handler
registration. XML is at `tmp/5499-installed-xml/`; do not retry the accepted failed
operation in the fixture. Current correction performs strict Head/API binding before
the recovery READY CAS and activates background consumption afterward. Status binds
last for initial handover; an unfinished initial owner is not exposed to sampling.
HandlerRegistry late reads/writes are synchronized and ID views are stable snapshots.
The legacy monitor branch is retired; current encoder evidence no longer uses a
historical missing-model flag. Focused proof: 5503 passes 111 tests, 5504-5506 cover
the composed publication changes and affected PMD after correcting unused resource
names. Root's 5505 accidental rename of a used mock caused one compile failure,
fixed in 5506. The 5507 negative control omitting structural preparation fails
FAILED-versus-RECOVERED; exact-byte restoration and 5508 Root tests/dist pass.
Source hashes are in `tmp/5508-source-sha256.txt`, matching installed Engine JAR hashes
in `tmp/5508-engine-jar-sha256.txt`. Fresh index run
`tmp/5509-installed-publication.log` passes both cases in 3m48s; its launch stamp is
`69397080e3e61369` (the preceding local build stamp was `2fa21e523bc0d3e7`). Release
fixture `writer-junit-c112643d-2916-4ffc-8758-31fba8610853` reaches READY/count1,
restart0, successful immediate ingest and one VECTOR hit, with the new clean-shutdown
oracle and STOP0. Exhaustion fixture `writer-junit-3e5c13b2-0cf5-4f05-b920-1b09aa97e869`
passes counted code5 escalation/reopening and STOP0. XML is in `tmp/5509-installed-xml/`.
Real CUDA publication must rerun with the new null-evidence assertion. Supervisor
self-checks pass 35/35 and both adapters pass 18/18 (5500/5502); Rust build retains
seven advisory dead-code warnings. All installed tasks run separately, with task
ordering as a backstop. Independent review found two remaining bounded corrections:
delayed-initial strict-binding failure must release its pending handover into generic
bounded recovery instead of wedging; FileOperationsTool's index-update callback must
capture the current Bootstrap client under its serving lease after physical replacement.
Its roots projections already use the stable root authority and remain unchanged.
Both corrections pass 63 focused tests and services PMD in `tmp/5510-final-recovery-bindings.log`;
the independent reread found no further defect. The file MOVE regression proves client selection
and lease closure with mocks, not a real Engine retirement.
The next CUDA run `tmp/5511-ort-current-publication.log` completed physical recovery/cancellation,
CUDA before/restored, READY/count2, and two VECTOR hits during the gap and after cancellation,
but JUnit failed because its new assertion expected JSON null instead of the omitted field
required by `EngineComponentView`'s NON_NULL contract. Fixture
`lifecycle-recompose-failure-f82e03a7-46c1-458d-b175-5c603e89da6f` has both empty reason/evidence
omitted, STOP0, and 9.49GB cache pruning; XML is retained in `tmp/5511-ort-xml/`.
The corrected assertion passes in `tmp/5519-ort-lifecycle.log` (1/1, no skips, 4m23s), with
XML in `tmp/5519-ort-xml/` and source/JAR manifests alongside it. Fixture
`lifecycle-recompose-failure-eb76d23f-561d-499a-9e5a-ff14ad817e31` proves CUDA before/restored,
READY/count2 with absent error fields, two VECTOR hits during the gap and after cancellation,
STOP0, and pruning of 30 private model files (9,490,054,214 bytes).
5512 llms and 5513 store/manifest checks pass. The 5513 reason-code gate correctly found
the now-unproduced `worker.recovering`. Its enum/retention/test consumers and
frontend projection are retired together in favor of existing `component.recovering`;
the frontend uses exact index component state to avoid confusing optional AI recovery with
an index restart. D1-15 records this one-row sequencing delta; the other renames remain pending.
5514 reason-code and all regeneration checks pass. `tmp/5515-reason-focus-dist.log` passes
122 focused tests in 14 suites, PMD, fixture compilation and distribution (50s), with original
XML in `tmp/5515-focused-xml/`. UI typecheck and all 6,612 unit tests pass at 5516. The old
fixture server refused the 5517 capture; the harness-owned replacement passes at 5518 with
no console errors or axe violations. This capture covers semantic-pause rendering; exact
index/optional-component recovery discrimination is unit-tested. UI coverage passes at 5520.
The current full stress suite plus Spotless/PMD at `tmp/5521-integrated-stress-static.log`
ended red in 14m25s. It identifies four obsolete methods, one unused test
resource and formatting violations. The Engine suite has one failure in 448 tests: enriching
a failed ordinary start context hard-codes `recoveryAttempt=true`, so same-Root retry opens
successfully but remains fenced as supervised recovery. Preserve the original classification;
do not force READY. Worker startup fixtures also mock a retired five-argument encoder assembly
overload instead of the current exact-file overload; a scan fixture's in-place state write races
Windows shared readers and must use the existing atomic publication contract. These corrections
are implemented without weakening their assertions. The suite additionally exposed a real
close/reaper lock inversion: close held `runtimeSwapLock` while joining the reaper, whose
maintenance tail needed that lock. Both platform and virtual-thread dumps are retained at
`tmp/5521-test-executor-thread-dump.txt` and `tmp/5521-all-threads.txt`. The exact verified
Gradle test executor was stopped after diagnosis; its forced exit is not a passing suite.
Original reports, including all four behavioral failures, are in `tmp/5521-suite-xml/`.
Close now joins the reaper before taking the runtime lock, preserving producer-before-queue
closure. The obsolete permissive Head binding path is removed; its publication-lock tests
exercise strict preparation. Dead encoder helpers/tracking are removed with maintained-output
assertions. Formatting passes at 5522; focused correction tests, PMD and distribution run at
`tmp/5523-integrated-correction-focus.log`. No corrected integrated pass is claimed yet.
Standard generative recovery (`tmp/5483`) and
optional exhaustion (`tmp/5486`) are proved; integrated current stress/build,
static/docs checks and a coherent pushed checkpoint remain required.

D1-4's actual packaged Tauri fixed/ephemeral-port fixture is implemented and
compiled but still needs current-source NSIS build/extraction and execution.
D1-6/D1-15 retirement follows D1-7 acceptance; D1 closure, D2, E and F remain.
The local harness commit is `f19e93a3d`; latest pushed/hosted remains `54b92132d`.
No current dirty-tree full integrated or hosted acceptance is claimed.
The dated checkpoint chronology below is evidence history.
