# C2-6: accepted settings revision implementation plan

## 2026-09-14 activation and compensation writer cut

The runtime-intent checkpoint `8e4706cf0` is pushed. Activation/deactivation/rollback
are next: their raw saves now refuse a recorded revision. Keep the existing
SettingsService as the producer coordinator. Add an internal-candidate entry taking
UiSettings, the full SettingsWitness captured with its base, and explicit internal
EngineContext. It accepts a fresh SETTINGS_APPLY row before calling the existing
runner's settings port. Canonical invocation identity is a digest, not private
settings content. Return the runner result including row identity on failure;
only COMPLETE establishes the witness against which compensation may proceed.
No second physical writer, terminal writer, durable counter or callback registry.

Activation decisions must use the snapshot captured before model/self-test selection;
do not read fresh metadata after choosing a candidate. Compensation compares the
specific full witness activation committed, rather than refreshing from the current
file. A competing settings mutation must survive. Runtime property/config/apply
ordering is being traced against actual inference consumers before changing it;
delete redundant ConfigStore publication only when the owner publication covers
the same sources. Preserve the existing procedure bracket, operator locks, selected
model/projector pair and failure outcomes. This mechanism pass is active, not an
owner-gated deferral or a claim that the consumer is implemented.

Resolved ordering: freeze the full candidate before selection/self-test; activation
adds chatEnabled=true to that same candidate. Begin the existing procedure before
commitment and keep compensation inside it. Commit through the settings owner,
which publishes ConfigStore before inference apply. On inference failure the
existing lifecycle manager restores its previous runtime configuration; compensate
settings only against the completed activation row's exact witness. Do not perform
a second inference apply during compensation. A stale compensation leaves newer
settings/config untouched and reports rollback failure. No compensation is inferred
from a persistence exception or an incomplete row.

Retire all app-owned server.exe JVM writes in activation, boot and installer;
the old ordinal-500 bridge masks candidate settings both before and after commit,
so reordering it cannot fix the stale executable. Boot CUDA discovery contributes
to the existing remembered autoDetected map at150; retain the post-boot rebuild
for ORT native-path writes. Runtime activation refuses an environment/JVM winner
(400/500) using existing resolver provenance. CPU deactivation persists a nonblank
baseline executable at300, so remembered CUDA150 cannot undo it. Installer CUDA
selection uses the accepted candidate entry and respects settings-or-higher winners.
Retire the server-executable source marker and its dead schema/readers together.

Dynamic profile selection is passed directly to inference; retire its temporary
JVM write/restore, keeping the bootstrap CHAT_PROFILE operator key and realized
profile observation. A narrow combined profile/runtime apply builds one config
and calls the existing manager once. Staging APPLY_ONLY first would destroy the
true rollback base. Profile selection still does not persist a model path.

The internal producer requires registered SYSTEM_INTERNAL attribution, not a
client-kind label, and freezes a private candidate before digesting/accepting it.
Typed refusals return row identity; persistence uncertainty propagates from the
runner with the row unresolved. Tests must refute untrusted INTERNAL labels and
caller mutation at acceptance as well as stale activation/compensation, operator
precedence, one combined profile apply, and CPU baseline survival across rebuild.

Review correction: CPU baseline persistence alone does not remove a remembered
GPU99 override. Store envelope4 therefore retains one nullable gpuLayers field:
null=auto, zero=explicit CPU, positive=offload. Migrate older zero to null because
the previous resolver omitted zero and used auto-detection. Preserve positive
values and the full revision witness. The numeric convenience getter remains0
for auto; serialization preserves null and ConfigStore contributes any explicit
value, including zero, at300. Reset clears to null. This avoids a second GPU
intent bit and an executable-path heuristic. Verify actual schema round-trip,
older migration, and deactivation with remembered CUDA/GPU99 before accepting.

Consumers preserve that distinction: v2 reads project null versus zero, while
partial-update null remains no-change and reset restores auto. Bare inference
apply honors explicit zero; settings-based callers pass the nullable override.
Boot context derivation accepts nullable settings, and the settings assembler
recomputes an existing derived context150 when it has a resolved GPU selection.
Unknown GPU state preserves the remembered window; no CPU fact is invented.
Activation/deactivation apply one published effective context/GPU snapshot so
environment/JVM overrides win at the actual effect, including zero. Boot executable
selection likewise uses resolved GPU zero rather than treating it as absent.

## 2026-09-14 runtime-intent writer cut

The runtime spec remains a settings projection. Writable composition receives the
existing attempt runner; a read-only constructor never fabricates an owner.
Both catalog handlers declare SETTINGS_APPLY and consume their dispatcher-issued
record. Server preparation freezes the full witness in strict
`settings-runtime-intent-v1` metadata, preserving public retry arguments.
Execution checks that pair before changing chatEnabled on its captured snapshot;
the existing settings owner performs the final compare and atomic commitment.

Direct mode REST passes request EngineContext and optional idempotencyKey.
Its normalized enabled bit is the public identity of `core.set-chat-enabled`.
Look up before settings inspection: changed target with an existing key is refused;
known outcomes never prepare or nudge again. Unknown keys capture one snapshot,
accept a row, then mutate inside that runner body. Per-call observation runs after
commitment inside the normal body error guard, not inside the physical writer.
The response returns operationKey and, on COMPLETE, acceptedRevision. Incomplete
retry answers `accepted` without joining its unresolved completion future.
Completed replay answers `recorded` without a live mode observation; only the
first execution can say `converged`.

An accepted failure still returns its server-issued operationKey and operationRecordId
for outcome lookup. Direct REST projects bounded operation/settings errors through
the sanitized REST error shape (`error`, `errorCode`, `errorClass`, `retryable`),
not the generic invocation DTO. Keep existing public tokens OPERATION_KEY_INVALID
and OPERATION_KEY_EXPIRED. Read-only/version conflicts are409; malformed input400;
recovery503 without automatic retry; capacity503 is retryable; storage failure500.

Boot autostart and activation's final intent use fresh internal settings attempts.
Their no-op predicate and candidate share one captured witness. Typed recovery or
read-only refusal leaves autostart bootstrap alive for Health/reset. Activation
receives the same composed spec; missing ownership fails closed. Its whole-document
apply, deactivation and compensation remain the next required writer cut.
The runtime-intent checkpoint cannot ship independently: its recorded witness makes
those remaining legacy saves refuse. Continue straight into that cut and the other
writers before C2-6 acceptance; final merge remains F.

Rejected alternatives: raw save bypasses the witness; refreshing only the witness
permits a stale candidate to erase a concurrent edit; another runtime-intent store
forks the settings authority. Reuse existing runner, preparation and settings owner.

Required checks: existing intent/convergence tests through real runner/owner;
acceptance before effect, stale frozen refusal, complete retry without preparation
or observation, changed-target key reuse, incomplete retry without blocking,
boot refusal preserving startup, request context/key and response witness wire
coverage. Run affected service/UI suites, PMD/format and independent batch review.
The reviewed checkpoint passes final1405:4,235 represented cases, four existing
skips, zero failures/errors, with applicable PMD/format/UI integration compilation.
[Evidence and precise limits](runtime-intent-producer.md). C2-6 remains open.

2026-09-13 implementation checkpoint: [typed witness foundation](settings-witness.md) is
locally verified; confirmed recovery reset, producer composition, Health and public wire
projection remain the next required cuts. This does not close C2-6.

2026-09-13 investigation at e7edaf503 (unchanged production at028be4ac8).
The governing protocol remains [operations-store-design §1.8](operations-store-design.md#18-the-accepted-settings-revision-port-amended-2026-09-12).
This record is implementation planning, not implementation or verification proof.
Root owns shared lifecycle, schema and runner changes. No owner decision is pending.

## Grounded constraints

- UiSettingsStore loads before operations setup in HeadlessApp:689-736; no settings
  save occurs there. HeadlessApp:1068-1091 has the store, runner and ordered restart
  callback together. Construct the settings owner at this existing composition root.
- ServicePhase:211,242-244 constructs RuntimeSpecStore and seeds autostart before the
  operation substrate, but after the runner exists. The owner must work before catalogs
  and HTTP, using the same runner with server-issued keys.
- HeadAssembly:329-339 already carries settings/store/runner; ServicePhase.Input:76-115
  and LocalApiServer:174-183 currently receive raw settings storage. Propagate the one
  owner through these paths and the CoreApiAssembly fallback constructors. Do not add
  a static registry or discover an owner from a global service locator.
- UiSettingsStore:222-245 serializes mutable input, atomically replaces the file and
  calls recovery-cleared notification afterwards. ConfigStoreRebuilder:72-85 builds
  and publishes together under a swallowed RuntimeException. ConfigStore:124-127
  swaps then invokes arbitrary synchronous listeners. Split preparation, atomic
  snapshot publication and outside-lock notification explicitly.
- AtomicFileWrites:34-49 falls back to ordinary replacement if atomic move is unsupported;
  its NioFileAccess.write:73-78 does not force the temporary file. C2 cannot infer atomicity
  or power-loss durability from this helper's name. The settings cut needs a strict atomic
  path with a forced temporary file and explicit platform evidence; process-restart proof
  is not physical power-loss proof. Preserve unrelated callers' existing contract.
- RuntimeSpecStore:34-36; RuntimeActivationService:979,1059,1084;
  AiInstallService:898,1855,1948,2005; AiPackImportService:634 are direct writers.
  Activation rollback currently restores a whole old document; it must compare the
  revision it is compensating so it cannot erase an intervening settings write.
- ResetSettingsHandler still calls a late UI callback through SettingsServiceImpl;
  the claimed DTO-layer obstacle is stale: SettingsV2 already lives in app-api.
  Move merge/reset mapping into the settings service and retire the late callback.
  Reset must reuse its dispatcher-issued attempt and declare SETTINGS_APPLY.
- OperationSchema:36 already has accepted_settings_revision, explicitly defined by
  the design as the expected revision. It is currently unused. Populate this existing
  column through a runner-owned conditional RUNNING transition after owner reservation
  and durable revision comparison, before settings preparation;
  no completion listener is emitted. Do not widen generic OperationPreparation or invent
  another durable expected-revision authority. COMPLETE for a committed settings apply determines next revision as
  expected+1. OperationReceipt currently persists only safe code/executionId.
- OperationAttemptRunnerImpl:167-222 currently fails a row on a handler exception;
  :261-278 performs terminal persistence then notifies completion observers. Its
  sticky persistenceFailure signal currently publishes Health only (HeadAssembly:791-800,
  OperationRecoveryNotice:39-55); settings post-commit failure must also request the
  existing EngineRoot ordered restart, with scope restricted to a committed settings
  witness. Preserve the single terminal writer.
- The UI has several real POST producers, not only api/domains/settings.ts: Shell,
  SettingsSurface, BrainSurface, LibrarySurface and adaptationProfile. Existing
  uiModeState intent order is separate from acceptedRevision and remains intact.

## Ownership decisions

Use a settings-specific app-api owner and the existing attempt runner. Construct the
owner from UiSettingsStore, ConfigStore and ordered restart dependencies before the
runner; inject the owner into the runner and the paired settings service afterwards.
The runner validates its own live OperationRecordHandle before invoking the fixed
settings owner. Only that call supplies runner-owned AttemptControl to the owner;
handlers cannot fabricate a committed receipt or write a terminal state themselves.
This avoids a circular service lookup, a second writer and a registry of attempts.
The settings call is synchronous inside the exact runner capability's executing body
thread, which is cleared before completion callbacks are attached. Retained or foreign-thread
handles cannot race terminalization. Background install/import owners start a fresh internal
settings attempt at their mutation point; the parent may remain asynchronous. Commit authority
is a separate private runner object, not an interface implemented by the handler-visible handle.
An independent caller/D1 check confirmed this restriction does not exclude a required producer.


Before SQL arming or any copy, builder or preparation callback, reserve under the apply
mutex: refuse active/unresolved fences, inspect the durable witness, compare expected
revision and install an attempt-bound PREPARING token. Fail fast on occupied reservation.
Release the mutex before the runner arms SQL; the locks never overlap. Then validate the
same token, copy and prepare serialized bytes and ResolvedConfig, and perform strict atomic replacement.
Promote the same guard to COMMITTED and mark its prebuilt immutable receipt on AttemptControl
immediately after success. Only matching-attempt cleanup after durable terminal persistence may clear the guard. Swap the prepared config snapshot under the
mutex; release the physical mutex before notifications. Keep the logical fence through
durable row completion and release it through the fixed owner outside locks. Nested
apply is refused, including an apply triggered by a notification. A post-commit ordinary
exception completes from the receipt. Any Error after SQL marker/PREPARING guard installation explicitly requests ordered
restart before rethrowing and retains the guard: an Error on a request thread does not
itself terminate the process. Boot classifies the valid unchanged or exact new witness.

Controller-only serialization and holding a mutex through handler return were rejected
by the governing design. Reusing the actual settings-file witness keeps recovery bounded;
no extra journal or unbounded committed-key history is introduced. ConfigStore's existing
update API can delegate to its split swap/notify primitives for unrelated callers.

Internal writers submit a fresh server-keyed settings attempt. Their candidate is a
copy of a revisioned snapshot; stale whole-document candidates cannot bypass comparison.
Runtime activation compensation is another revision-checked settings mutation, not a
rollback of a completed operations row. No unchecked old snapshot restore survives.
Existing inference-specific system-property updates need explicit composition during
preparation: the installer still requires five ONNX globals for actual EnvRegistry
readers (AiInstallService:1927-1944). Their removal is not licensed by the config swap.
The final writer cut must prove those readers still receive the accepted values.

## Bounded cuts and acceptance

1. **Preparation and storage boundary.** Split ConfigStoreRebuilder preparation from
   publication; delete swallowed preparation failure, including exclude-pattern
   serialization fallback. Prepare settings copies/bytes before replacement. Add
   acceptedRevision and lastCommittedOperationKey to a schema-v3 settings envelope, adding
   v2 to readable legacy versions so an older writer cannot silently strip the witness.
   Accept revision zero/no key or positive revision/canonical UUIDv7 key; reject malformed
   pairs. Use strict atomic replacement with no ordinary-move fallback and force temporary
   bytes. A move error is resolved against the actual file witness: exact new means committed,
   exact prior means precommit, any third/corrupt state remains unresolved. Preserve read-only IN_MEMORY behavior and
   future-version refusal. Keep public raw save only until the following migration cut;
   it must be deleted before C2-6 can pass.
2. **Runner and fence.** Carry expected revision into the existing SQL column, add typed
   in-process committed receipt, and project committed revision in the safe HTTP/MCP
   outcome. Register settings reconciliation before autostart writers. Validate runner
   ownership and one-commit-per-attempt. Complete from the receipt on post-commit
   RuntimeException; terminal persistence failure keeps the fence and requests ordered
   restart. No outcome observer runs under apply or SQL locks.
3. **All producers.** Move settings merge/reset ownership out of the controller; require
   expected revision and keyed identity at HTTP ingress; use the same attempt on catalog
   reset and later reconfigure. Migrate runtime-spec, activation/compensation, installer,
   import and fallback composition. Retire raw save/rebuild bypasses and stale comments.
   Preserve mode-intent ordering and native/sysprop consumers. A search sweep and an
   executable guard prove no production bypass remains.
4. **Consumers/contracts.** Update the Java record source, both generated schema copies,
   frontend generated types and every POST path to carry revision and one key per
   logical attempt. Same-key retries keep the original public input and key; they do
   not silently retry against a newer revision. Retain explicit version-conflict UX.
5. **Fault/live/integrated proof.** Run exact negative regressions, affected module/PMD/
   format checks, full/stress, UI typecheck/unit and instrumented browser proof, then
   installed/hosted checks. Commit and push each coherent cut; preserve failed attempts
   and distinguish local/hosted proof. D1 component-applied revisions remain owned by D1.

Required fault matrix: preparation throws without file/config change; file replacement
fails; notification throws after commitment; nested/reentrant apply; concurrent controller
and internal writer; stale compensation; forged/foreign attempt; invalid witness;
post-file/pre-row termination; failed row completion; boot witness reconciliation;
corrupt settings quarantine while an unresolved settings row exists; legacy/future file;
same key changed input; A-to-B lost response then B-to-C completion then retry of A-to-B
returns B's receipt while current settings stay C; a new stale key returns VERSION_CONFLICT.
No settings mutation may proceed by guessing commitment after quarantine.

## Independent design refutation and resolved metadata path

Read-only review found four concrete gaps: late reentrancy protection, atomic-move fallback,
old binaries stripping a same-version witness, and corruption misclassified as precommit.
The decisions above incorporate the corrections; no execution proof is claimed.

Reset's executeRecorded override receives its existing dispatcher-issued handle and calls
SettingsService with expectedRevision. The service calls runner.applySettings; the runner
validates live ownership/kind, obtains a revision-validated reservation from the fixed
owner, conditionally sets the existing SQL expected-revision column, then invokes preparation
using the same opaque token. Null marker at boot proves no settings preparation/effect;
armed marker plus exact key/expected+1 witness completes; unchanged valid prior witness fails
precommit. Armed rows with quarantine or contradictory metadata stay unresolved and block
settings mutation with a recovery Health condition. Do not loop restarts against missing
evidence or report a false failed outcome. Receipt revision is Math.addExact(storedExpected,1),
never caller-supplied. Internal writers enter exactly the same path with a server key.

The fixed owner alone supplies settings boot verdicts; generic public reconciliation rejects
both settings kinds. With an owner composed, a settings body that skips applySettings cannot
report success. Missing owner callbacks are uncertain, not proof of precommit failure. Owner
Errors request restart inside applySettings even if a handler catches them, and callback failure
cannot replace the primary fault or strand the observation. The in-memory prepared receipt
preserves the successful result; typed precommit refusals preserve their code/details. Durable
rows retain bounded metadata only. [Runner implementation and verification](settings-runner.md).

The native-property preparation seam remains root implementation investigation within cut3.
No operator question or external approval is required to settle it; no direct writer is
waived from C2-6 acceptance. The existing D1 component-installation boundary is unchanged.

## 2026-09-13 reservation-order correction

Independent read-only review at9534c3330 refuted marker-before-reservation. While A holds
revision R, B could arm R, be refused at the owner, then survive a crash after A writes
R+1/keyA. B would falsely present an ambiguous armed witness. Merely reserving first is
insufficient: stale B can reserve after A releases unless reservation compares the durable
revision before SQL arming. The order above fixes both traces without another durable store.
Do not reinterpret advanced different-key witnesses as precommit; corruption or bypass can
violate the very invariant that inference assumes. Boot examines the complete open settings
set before per-row reconciliation: more than one armed row blocks settings with recovery
Health; one uses the existing witness rules; null-marker rows safely fail precommit.
Required regressions add occupied/stale reservation leaves a null marker, reservation-to-arm
failure retains the guard until durable failure, and multiple armed boot rows fail closed.
This is a reviewed mechanism decision, not executable proof; no stage/merge cut changes.

## 2026-09-13 settings history recovery

Independent design review found that permanent refusal after quarantine conflicts with explicit
re-authoring, while treating defaults as revisionzero accepts stale numeric revisions. The
[owning protocol](operations-store-design.md#settings-history-recovery) now requires typed witness
comparison for every new-key producer and a confirmed recovery reset with frozen quarantine
evidence. This is a C2-6 contract correction under delegated authority; implementation remains
in cuts2–4 and no external decision is pending.

Rejected alternatives: defaults-as-zero fabricates history; permanent refusal loses the reset
path; retained SQL rows cannot prove a maximum after retention; a second revision allocator
adds another authority and changes the two-store protocol. Reusing both existing witness fields
and the existing accepted preparation is the smaller ownership change. A Boolean recovery flag
alone was refuted: reset may commit, lose SQL completion, then be quarantined at boot. A digest
of the original complete quarantine set prevents falsely failing that committed reset.

Extend the owner/runner API to typed expected witness and immutable accepted recovery preparation;
use the fixed versioned settings codec, never public recovery flags. Return both witness fields
on GET and migrate HTTP, reset/reconfigure, internal writers, compensation and all UI producers.
The numerical part still occupies the existing SQL marker and determines successful receipt
revision as expected+1. Fingerprint the sorted regular sibling set with unambiguous encoding;
revalidate before arming, and pass unknown evidence through as unresolved. Exact live commitment
wins even when the recovery payload cannot be decoded. Wire the recovery notice lifecycle so
only committed explicit re-authoring clears its condition.

Required proof in addition to the existing matrix: stale same-number/different-key conflict;
normal/internal refusal while recovery is required; armed row blocks reset before marking;
reset termination before/after file commitment; terminal SQL failure; same-key reset idempotency
and changed-input refusal; changed/added/removed/unreadable/non-regular quarantine evidence WAIT;
postcommit corruption/quarantine cannot become precommit FAILED; exact live witness completes
with invalid/missing preparation; future/inaccessible file refusal; ordinary fresh absencezero.
No runtime or verification claim is made by this design amendment. Update ADR0008 and canonical
configuration behavior with the producer implementation, not ahead of its shipped behavior.

## 2026-09-13 owner foundation checkpoint

The concrete coordinator and recovery classification now have
[local proof](settings-owner.md): Owner1286 passes100 represented cases, independent source
review is clear, negative1287 fails at four intended assertions and1288 restores identical
sources. Failed1282 PMD and1285 actual concurrent cleanup failure remain recorded. The latter
required an unrelated-id fast return before locking during terminal cleanup; prompt reserve
alone was insufficient to make the caller's refusal prompt. Callback lock probes now use a
side-effect-free reconciliation read, since unrelated cleanup intentionally no longer locks.

Continue directly with the typed witness and confirmed recovery-reset amendment above, then
all-producer/Health composition and wire consumers. The current scalar-only owner foundation
is not production-composed and does not discharge the amended recovery or all-producer contract.


## 2026-09-13 recovery evidence reader cut

The next bounded cut adds a read-only UiSettingsStore fingerprint of the complete preserved
quarantine sibling set. It is evidence, not reset authority. Reuse SHA-256 and streaming file
reads: sorted names are UTF-8 length-prefixed, file contents contribute fixed-width digests,
and the versioned domain/count prevents ambiguous concatenation. Require proven live-file
absence, writable persistence, a nonempty regular non-symlink set, successful reads, unchanged
attributes during each read and unchanged membership/live absence afterwards. The owner must
recompute against the frozen accepted value before arming. No extra persistent file, marker,
allocator or writer is introduced. Hostile concurrent filesystem replacement is not made an
atomic transaction by this reader; unknown or detected changes refuse proof. The application's
single physical owner supplies the mutation serialization.

The following cut reuses PreparedInvocationCodec's existing envelope validation for a narrow
metadata-only decode, without an arbitrary caller decoder, SQL access in the owner, a second
JSON envelope or an early cipher dependency. The runner will supply its actual accepted
preparation beside each settings recovery row outside SQL locks. The fixed settings reset
schema is validated before authorizing absent-history recovery; exact live commitment is
classified first and does not depend on decoding. Reset producer classification, confirmation,
Health and full public writer migration remain required in C2-6.


## 2026-09-13 accepted recovery input cut

The runner supplies a private RecoveryInput of row plus optional accepted preparation only
when exactly one settings row is armed. Count lightweight rows first (stop counting at two);
multiple armed rows must reach blocked Health without loading their potentially large payloads. Null-marker rows require no private-payload read because they cannot
have crossed file commitment. Read the payload before calling inspectRecovery; no SQL lock
is held during owner inspection. This leaves OperationRecord, history and public wire payloads
unchanged. A malformed stored nonce/payload validation exception becomes unavailable preparation
with a bounded row-id diagnostic, so it cannot prevent exact live-witness classification.
It never authorizes absent-history recovery. SQL storage failures still propagate; this is not
a catch-and-default around database availability.

PreparedInvocationCodec exposes only a public static metadata decode entrypoint; its constructor,
full envelope and content methods remain package-scoped. It rejects sealed input and reuses the
existing disabled-cipher metadata validation, including version, key, nonce, descriptor/public
identity and content classification. No duplicate envelope, arbitrary decoder callback or
startup cipher dependency is introduced. The next fixed settings-reset schema consumes this
validated metadata and the quarantine fingerprint; this foundation alone grants no reset.


## 2026-09-13 fixed reset reservation and recovery-clear order

The fixed reset schema and private owner-issued expected marker reuse the existing reservation,
SQL arming and receipt path. An accepted recovery token carries its frozen quarantine identity
and may bypass only the quarantine/UNREADABLE block that made explicit re-authoring necessary.
Do not clear that block during reservation. Normal/precommit failures release the attempt fence
only after durable failure while retaining recovery-required state, so a new confirmed reset
can retry. A COMMITTED recovery token clears the block only after its row terminalizes durably.
Multiple armed, contradictory and persistence-disabled blocks cannot use this bypass.

Independent review refuted reusing current requireFence/release unchanged: both reject/retain
all blocked tokens, and clearing blocked early would lose recovery refusal after SQL-arm failure.
The recovery distinction belongs on the existing opaque fence, not in another persistent flag.
Likewise move recovery-clear notification out of apply and into matching committed-fence release
(after SQL finish), outside the physical mutex. Terminal SQL failure keeps the condition/fence
and invokes the existing ordered restart; it cannot publish premature recovery success.

The sole successful absent-history reset also requests the composition root's existing ordered
restart after durable completion. This rare explicit recovery re-enters normal bootstrap after
ordinary startup writers were refused and gives the existing per-process sticky recovery future
its correct lifetime. It avoids adding a second Health state writer, resettable future registry
or polling authority. Successful readable-history resets need no such recovery restart. A lost
HTTP response is answered by the recorded key outcome, as already required by C2. Callback
RuntimeExceptions are diagnostic after durable completion, never rewritten as SQL failure;
restart is requested even when the recovery-clear callback fails.

Required regressions: arm/precommit failure preserves quarantine condition while allowing a new
confirmed reset; committed reset plus SQL terminal failure retains condition/block/fence and
requests restart; successful durable reset clears and requests one restart; callbacks run outside
the mutex; exact live witness takes precedence over invalid/missing preparation at boot; changed
quarantine cannot become precommit failure. No owner input or stage/merge placement change.


Final independent lifetime review is clear atd9ac1c202. Grounded restart owner is
HeadlessApp.localRestartAction:1629–1673, composed before the runner at1068–1075.
Capture the successful recovery restart decision under the owner mutex, dispatch it outside
from committed-fence release (never retainForRestart, which marks uncertainty). Ensure a clear
callback Error cannot suppress restart; preserve the primary Error if restart also throws.
Boot reconciliation of a prior committed recovery does not request another restart because
that process already re-entered bootstrap. Installed proof must show successor bootstrap and
previously refused startup writers, not just a callback counter or exit request.


The [fixed metadata helper](settings-reset-schema.md) now implements settings-reset-v1 and
strict normal/recovery intent validation. It consumes the existing accepted envelope and row;
root continues directly with owner reserve/apply/reconciliation, then production/wire/Health.


The [fixed reset owner cut](settings-reset-owner.md) now implements reservation, post-arm
candidate creation, ambiguity classification, full-pair normal reset recovery and durable
clear/restart ordering. Independent review and negative1335 are clear;1336 restores196 passing
cases. The next cut is production owner/producer/Health composition and public witness/key
flows, followed by actual successor-bootstrap proof. The new main identity policy requires
PR1 publication-lineage repair; it changes neither stage order nor final F merge readiness.

## 2026-09-14 next producer cuts after reset and Health

Grounding at `5198da953` confirms that runtime intent must migrate as one family,
not as a store-only replacement. Read-only independent discovery and the root's
source inspection agree on the following per-item commits. All remain required;
this plan does not claim implementation or authorize a second commitment owner.

1. **Runtime intent.** Migrate RuntimeSpecStore:29-70, SetChatEnabledHandler:73-85,
   SwitchInferenceModeHandler, BrainRuntimeServiceImpl:114-130, the ServicePhase:211
   composition and InferenceWiring:72-103 autostart seed together. Recorded handlers
   consume their existing runner-issued capability (OperationExecutorImpl:501-504,
   593-603); direct boot/REST producers accept before effects. Include activation's
   RuntimeSpecStore construction at RuntimeActivationService:1015 in this injection
   cut. Preserve provenance and the one captured full witness through the no-op
   predicate and mutation: recordUserEnabled and seedAutostartIfUnset must not read
   again to refresh metadata for an older decision. A typed recovery refusal from
   boot seeding must leave startup able to expose Health and the confirmed reset UI.
   It must not falsely report that the seed persisted. Verify real SQLite acceptance,
   stale refusal, operation-key replay, seed refusal followed by reachable startup,
   and existing runtime intent/reconciler/procedure behavior.
2. **Activation and compensation.** Migrate RuntimeActivationService:960-979,
   1055-1059 and 1081-1085; delete the separate ConfigStore rebuilds at 1008,1066,1108.
   Capture the committed full witness for compensation. Refuse an intervening write
   instead of restoring the previous whole document. Preserve the procedure bracket,
   profile selection and native system-property order; assert those effects separately.
3. **Installer and import.** Migrate AiInstallService:876-898,1853-1868,1893-1949,
   1998-2006 and AiPackImportService:631-650. The five ONNX properties at1928-1943
   still have real readers. Pack recording at617-626 remains after settings durability.
   Carry candidate/witness together and propagate refused or uncertain commitment;
   no successful install/import claim may hide a failed settings apply.
4. **Public settings ingress and consumers.** Replace SettingsController:109-145's
   direct save, carry one key and original witness through every frontend producer,
   and expose an atomic snapshot/witness read. Preserve the existing per-client mode
   sequence/LRU behavior. Update Java DTOs and generated schema/type projections
   together, with public A/B/C retry and changed-input proof.
5. **Retirement and integrated acceptance.** Remove public raw-save/config-rebuild
   bypasses after the caller sweep, run an executable no-bypass guard, then the
   required full/stress/frontend/live/installed/hosted checks. Installed successor
   bootstrap and all C2-11 scenarios remain owed; callback counters do not substitute.

The launcher fallback remains IN_MEMORY and has no restart owner. No constructor
fallback may create an uncomposed writable runner or silently write raw settings.
Root retains shared composition and Gradle ownership; each implementation batch gets
one independent review, explicit-path commit with proof body, and immediate push.

## 2026-09-14 runtime-intent response preparation

The runtime-intent migration exposes a response ownership mismatch:
SetChatEnabledHandler returns chatEnabled plus the observed engineState, while
OperationAttemptRunnerImpl:240-257 deliberately replaces a settings body's result
with the fixed owner's prepared committed receipt. That receipt currently projects
SettingsV2 from HeadlessApp:1082-1089. Returning only that projection would silently
remove the operation's existing observation fields; letting the handler replace the
receipt would let a postcommit failure contradict a proven commitment.

The selected mechanism enriches only the first successful synchronous response
with non-colliding handler observation fields. The fixed receipt wins every collision
and retains success, message, execution identity, revision/key and all prepared
settings fields. Build the merged response before terminal completion callbacks.
A thrown body or returned failure uses the prepared receipt unchanged. Persist only
the existing bounded receipt; keyed retries report that recorded outcome, never new
engine observations. No handler gains terminal-write or committed-witness authority.

Alternatives: a per-operation response callback/registry on the physical settings
owner adds lifecycle dependencies to commitment; changing chat intent to SettingsV2
alone drops an existing contract. Reusing the normal successful handler's already
computed observation requires neither. The scope is response projection after a
proven commitment, not a new effect/transaction abstraction. It earns its keep if
runtime intent retains its observed-state response while collisions and postcommit
failures cannot alter commitment. Retire it if settings-producing operations no
longer have supplemental response contracts.

Required focused proofs: successful observation retained; conflicting witness,
settings and receipt fields ignored; failed/thrown response cannot publish its
observations; same-key retry cannot invoke the observation producer again. The
existing reservation, callback ordering and fault matrix must remain green.

Independent review found the new projection allocation initially outside the fatal guard.
The regression1394 reproduced missing restart retention; the correction moves projection
inside the existing body guard. Final1395 executes560 cases/83 suites with zero skips,
failures or errors; PMD/format/UI integration compilation pass. No new fault callback
was needed: a mocked response supplies the synthetic allocation-path Error.
