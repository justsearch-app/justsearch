# C2-6: accepted settings revision implementation plan

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
