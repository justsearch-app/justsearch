# C2-3: key identity, prepared invocation and approval

Status: active implementation plan, 2026-09-13, grounded at11254a1e0.
The validated lookup prerequisite passes final909; keyed dispatcher SPI and receipt
access pass final913. HTTP invoke/undo, approval key/mode and approved-result projection pass923.
MCP key delivery and failure receipts pass936; persisted preparation remains open. R1–R10 are accepted; this record owns the next C2 item. No prepared
producer is active and no C2-3 completion is claimed.

## Current seams and intended behavior

HTTP invoke/undo and MCP operation tools now carry optional keys through the
keyed dispatcher overloads. Existing keys are looked up before preparation; HTTP
and MCP pending approval preserve their key and invoke/undo mode. SqliteOperationStore already compares kind, operation reference and canonical
public identity before returning an existing row; that comparison must become the
same read used before preparation. OperationDescriptor.invocation already stores a
canonical public-input digest and separates invoke from undo. Undo arguments already
include the target execution id. These authorities are extended, not duplicated.

Only unknown keys prepare. Existing rows compare public identity before returning a
receipt or attaching to a live attempt. A changed public input always conflicts,
including for terminal rows. Expired and invalid keys refuse before preparation.
Absent keys are minted once at entry and returned; callers without their own key
retain no recovery promise. Current caller authority is checked before any receipt;
a keyed retry never independently authorizes replay of an accepted effect.

Receipt access uses the existing local metadata-read boundary, with provenance
integrity and the current IntentGateEvaluator DENY/hard-stop verdict enforced before
lookup. It is not a fresh mutation: AUTO/confirmation cells do not consume another
capsule merely to return metadata. Host/Origin/session-token ingress protections
remain required for the request. Client ids, session ids and grantReference remain
attribution, not new credentials. No second identity or receipt-policy registry is
introduced. Matching open rows may attach to their observation but cannot execute
from this branch; owner reconciliation still validates actual effect authority.

A read-only authority map confirms the reason for this choice: EngineContext
explicitly disclaims authority; DurableGrantStore checks operation/family, source
and scope rather than resolving grantReference; ConsentCapsuleService consumes its
capsule once. Requiring that consumed capsule again would make a successful keyed
retry fail. Treating an attribution field as authority would manufacture a trust
boundary. The current operation-history GET already exposes metadata without a
mutation grant. This mechanism keeps mutation authorization separate from receipt
access, as ADR-0046 requires. Required negatives include hard-stop denial, invalid
provenance, unchanged single-use capsule semantics and no effect from open-row lookup.

A prepared invocation remains separate from the public identity. Its immutable
server-built envelope binds the operation key, mode, public digest, original
attribution, handler schema/version and frozen payload. A retry cannot replace the
frozen target, generation or designation revision with newly prepared state. Receipt
and history projections contain no payload, prompt, title or document content.

## Ownership and storage decisions

1. Keep one operations store and runner. The SQLite owner atomically moves persisted
   preparation into acceptance. There is no second journal, outbox, key registry or
   durable operation lifecycle. A pending preparation is not an ACCEPTED operation
   and does not appear in outcome history as one.
2. Preparation before approval needs persistence because the955 note target is chosen
   before the gate. Reusing only PendingAuthorizationStore's current in-memory raw
   JSON cannot satisfy restart/retry or frozen-target approval. Use a bounded pending
   preparation table inside operations.db, with the same key/public identity and
   existing five-minute pending lifetime/512-entry bound. Acceptance copies the
   prepared record and retires the pending record in one transaction. Approval
   capsules stay process-local and single-use; persisted preparation is no authority.
   Expiry/cap pruning cannot silently re-prepare a still-referenced pending approval.
3. Serialize unknown same-key preparation within the runner, outside the SQLite
   transaction. A fixed bounded set of key locks is sufficient: no unbounded map and
   no handler callback while holding the store connection lock. Store uniqueness and
   comparison remain the final acceptance authority. The lock covers preparation and
   its persistence, never the asynchronous effect lifetime.
4. Metadata-only preparation may remain plaintext within the existing operations
   metadata contract. Content-bearing preparation must use the existing DataKeyManager
   and StoreCipher. StoreCipher.disabled() returns plaintext, so NOT_CONFIGURED and
   LOCKED both refuse content preparation/replay. Never silently configure encryption
   or create another key authority. A terminal metadata receipt requires no decrypt.
   The store opens before HeadAssembly creates its key manager: wire a cipher from
   that existing manager into the application preparation owner; the root store and
   boot sweep continue to read metadata without creating another key manager.
5. Bound schema and UTF-8 payload bytes at both the Java port and SQLite schema. Bind
   the sealed envelope to key/public identity and verify its version before replay.
   Refuse unsupported/malformed preparation; never fall back to raw caller JSON.
   Payload retention follows the operations row; pending expiry follows approval
   lifetime. No payload is projected to logs, receipts, outcome/history or ledger.

The metadata/content distinction is declared by the server preparation capability,
never a caller field. The existing public schema remains unchanged; callers cannot
supply a frozen path, server attribution or a prepared envelope. Existing passthrough
handlers retain transient public input and require no encryption setup. Restoring
held root-plan code belongs to its named later owner after this mechanism works.

## Per-item commits and verification

- **C2-3a — public key lookup and propagation.** Reuse canonical identity comparison
  for a read before preparation; carry optional keys through invoke and undo and
  map typed key failures at HTTP/MCP boundaries. Prove identical terminal retry
  executes once with no second preparation; changed arguments/operation/undo target
  conflict; invalid/expired keys never prepare; unkeyed responses carry a minted key.
- **C2-3b — persisted preparation.** Add the operations schema migration, one immutable
  payload and pending-to-accepted transaction, bounded unknown-key serialization and
  existing-cipher wiring. Prove real restart preserves the selected target, two
  concurrent unknown calls prepare once, terminal lookup stays available while
  locked, disabled/locked content cannot become plaintext, and rollback preserves
  the old schema. Keep the durable-store register and compatibility tests current.
- **C2-3c — approval carry-through.** Carry the same server preparation through the
  gate, pending record, preview and approval. Separate receipt authorization from
  new mutation authorization without making a used capsule reusable. Prove changed
  designation never causes a second target, replayed/expired approval cannot execute,
  current hard-stop/grant constraints remain enforced, and caller-supplied prepared
  fields cannot select scope. Use the receipt-read mechanism above; no grantReference resolver or caller-label
  credential is added. Actual resume authority remains in the owning recovery item.
- **C2-3d — remaining ingress and integrated acceptance.** Ingest/settings entry
  points carry the same key contract in the owning C2 sequence. Cover API, MCP,
  frontend response/retry and undo behavior; regenerate paired contracts, port and
  operation-surface catalogs as needed. Execute the stage's full batch2 gate and
  retain fresh hosted proof; keyed installed recovery expands at C2-11.

For every implementation item: first demonstrate the named adverse behavior, then
run the focused module tests, affected PMD and UI integration-test compilation.
Commit the item and its exact evidence; push immediately. Keep failed/crashed/reused
runs honest. Root owns all shared-state and migration implementation; read-only
exploration can resolve the remaining authorization mapping independently.

## Orchestrator preparation entry (September13)

The agent's WATCH and declared confirmation floors can require approval even when
the dispatcher's base lattice says AUTO. Calling dispatch to discover a frozen target
would therefore execute before the prompt. Keep issuance policy in its present owner
and expose preparation-only through OperationDispatcher and BackendIntentRouter.
The result is either the existing receipt or a ready reference (stable key, optional
nonce, optional bounded display). It contains no execution payload and is not authority.
Reuse InvocationPlan, the runner key scope and operations.db; a new ticket store or
persisted orchestrator lifecycle is unnecessary. ShellAddress stays the public intent
shape; backend continuation references use a separate router overload.

Preparation validates current provenance/hard-stop and checks identity before reading
or freezing payload. It never accepts work, performs an effect, or consumes/mints a
capsule. Display is requested only for a pending prompt; receipt and non-display paths
bypass the handler's projection. Dispatch revalidates current authority and exact
key/nonce. Passthrough handlers retain transient preparation; invalid preparation has
no effect and the eventual dispatch owns its ordinary recorded refusal.

First commit the dispatcher/router entry and focused proof: LOW preparation leaves
no accepted row/effect, restart preserves the target, changed keyed public input
conflicts, receipts bypass preparation/display, and the router preserves the reference
without forwarding an event. Then connect the agent/workflow consumers: scope before
preparation, unchanged autonomy/background policy, one continuation through retries,
registration before announcement, and answerable nested workflow gates. The existing
frozen-display privacy rule still applies; settle its per-run delivery explicitly
before changing event/history serialization. No consumer activation is claimed by
the prerequisite alone.

The shared GatedOperationExecutor now exposes prepare and routePrepared using this
same OperationDispatchPlan directly. No extra prepared-call/ticket representation is
needed: the caller already owns its immutable operation and public argument string.
Ready plans mint the exact prepared capsule when a nonce exists; the legacy sentinel
cannot satisfy that path. Recorded plans query through the router again without any
capsule, so a later hard-stop or receipt change is observed rather than returning a
cached result. Agent/workflow loop wiring and private display lookup follow.

## Connected consumers and private display (September13)

1. Extend each existing pending gate's value with a PendingToolApproval read view:
   its existing public PendingApproval detail plus optional OperationApprovalPreview.
   The value is a projection of that same gate, not another store or execution input.
   AgentRunQueries delegates lookup through AgentSessionRegistry/AgentSession;
   WorkflowGateRegistry owns the workflow equivalent. GET /api/chat/approval takes
   the same sessionId/callId used by the unified reply path and returns the live
   display projection, or404 after removal. The gate id remains routing, not a new
   credential; existing local API trust rules apply. Do not put the preview in the
   public pending detail, tracing, run-event persistence or global routing SSE.
2. The frontend always resolves the live pending gate before a human ceremony. Use
   the returned complete summary and gate/risk; failed, missing or stale lookup must
   not fall back to a raw prompt for a possibly prepared call. Check that the run and
   call are still pending after the asynchronous lookup, so a late response cannot
   open a ceremony after run conclusion. AUTO issuance still follows the server's
   existing verdict. Public event/snapshot fields remain projections of the open
   gate; only the point-to-point response contains the frozen summary.
3. In the agent step, merge document scope before preparation/gating and preserve the
   original call for loop guard/history. Compute the existing issuance verdict once,
   retain safe-by-default background refusal, and obtain one OperationDispatchPlan
   through GatedOperationExecutor. A recorded plan skips another prompt; a ready plan
   supplies private display and survives the entire retry loop. Actual dispatch uses
   routePrepared. The projected-workflow outer call has no operation handler, so its
   existing streaming branch stays explicit; preparation belongs to each inner tool.
4. Workflow tools use the shared IntentGateEvaluator at ASSIST, including the declared
   confirmation floor. Prepare without effect before registering/announcing a gate,
   preserve the plan and execute through routePrepared. Pass the server background
   flag from the agent bridge into the runner; any node requiring confirmation in
   that posture refuses immediately. WorkflowOperationProjection's LOW classification
   does not make its inner write or explicit GateStep safe to run unattended.
5. A workflow creates its own session id, so retain both that id and the incoming
   observer session on its existing gate entry. The WorkflowToolRunner query projects
   pending public details for the enclosing agent's snapshot; AgentLoopService's
   existing snapshot supplier combines those with its session gates. This is a
   projection of live owners, not a second cache or durable hierarchy. The unified
   reply endpoint already resolves an inner call carrying the outer agent session.

Implement as bounded backend lookup, agent continuation, workflow continuation and
frontend integration items, each compiled/verified/committed/pushed. Reconcile the
canonical API map and regenerate docs after the new endpoint. Named checks include
private display absent from event/row history, missing-preview refusal, delayed
lookup after run closure, scoped WATCH approval before effect, one key/nonce across
retries, background nested-write refusal, declared LOW confirmation, and nested
snapshot reattachment. The preceding kernel proofs do not close these consumer items.

## Reach and teardown

The useful principle is stable input identity with separately frozen execution
state. It also applies to ingestion generation/root plans and note targets. It earns
its keep when a retry after changed external state returns the original receipt or
uses the original preparation; retire the preparation capability for a handler
whose public input alone completely determines execution and needs no frozen state.
Do not introduce a generalized workflow engine. Remove the dispatcher's blanket
replay-schema rejection only when the persisted mechanism and its negative proofs
are connected. Replace raw-JSON approval redispatch for prepared calls in the same
change, and preserve the held source packet until its owning stage adapts it.

## Prepared-envelope implementation cut (September13)

The first C2-3b commit owns only the bounded value/codec prerequisite; the dispatcher
continues to refuse replay schemas until the store transaction and approval binding
are connected. The following store commit adds accepted payload columns and a pending
preparation table to operations.db v3, with rollback and retention proofs.

The envelope binds format version, operation key, server preparation nonce, existing
public descriptor, full server preparation, original unattached EngineContext, executor
and occurrence time. Do not persist a process-local workId or signed intent capsule;
neither is restart authority. The nonce binds a pending approval to this exact
preparation even after a pending entry expires or is evicted. Content classification
is a handler-produced enum. The existing three-argument preparation constructor means
metadata; content producers must explicitly select CONTENT. The full content envelope,
including public arguments and original attribution, is sealed with StoreCipher.
Plaintext metadata is allowed only for server-declared metadata preparation.

UTF-8 limits are128 bytes for handler schema and200000 bytes for replay payload,
524288 bytes for the full envelope and750000 bytes for stored ciphertext. The latter
covers base64/GCM overhead. Port values hide payloads in toString. A content seal must
produce a sealed result even if key configuration changes during the call; disabled
or locked state never permits a plaintext fallback. Decode checks envelope version,
key/nonce/public identity, classification, and the public-argument digest. Terminal
receipt lookup continues to bypass decode. Unknown data and malformed payloads refuse
without returning stored content in an exception.

## V3 store mechanism (September13)

Operations.db v3 appends preparation_nonce, preparation_sealed and preparation_payload
columns, with all-null or fully-paired bounds. A separate operation_preparations table
inside the same database holds the unaccepted key/public identity, nonce, opaque
payload and created/expiry timestamps. No new lifecycle state, database, writer or
numeric sequence is introduced. V1 rebuilds into v2, then both v1/v2 apply the append
migration inside the existing schema transaction. The existing sequence and fence
remain authoritative. Frozen v1/v2 fixtures survive independently of production DDL.

The store returns the first unexpired pending preparation for identical public input
and refuses different input. Save retries do not refresh its TTL. Pending state is
five-minute/512-entry bounded; pruning it does not move the operation-history fence.
Acceptance copies its exact payload and removes the pending row in one transaction.
A missing/expired/replaced nonce returns OPERATION_PREPARATION_UNAVAILABLE; it cannot
accept a new preparation under an old approval. Raw acceptance refuses while a live
pending preparation exists. An already-accepted matching row returns its receipt,
regardless of an obsolete nonce, without granting another effect. Retention removes
accepted payload bytes with their parent row. Existing key-history expiry still
applies before acceptance; a pending value is not a way around that fence.

The runner now uses a fixed bounded set of reentrant key locks in the existing runner,
with every runner acceptance sharing them. A preparation scope mints an absent key
once, repeats lookup under the key lock, and passes the validated existing-row metadata
snapshot to pure application preparation outside the SQLite lock. Pending save is
reentrant. The scope ends before acceptance, live observation, effect admission or
async execution. Acceptance shares the stripe for its transaction but publishes
completion listeners only after releasing it; nested same-stripe observation refuses.
This avoids the proven late-terminal callback deadlock without a publication queue. The dispatcher then consumes the frozen value or returns the
existing receipt; approval carries the exact preparation nonce. Generic passthrough
handlers remain transient and do not require content encryption. Reconciliation is
still the only owner that can authorize replay of accepted incomplete work.

## Frozen approval binding cut (September13)

The existing capsule's BoundAction digest uses a JSON binding of operationKey,
preparationNonce and the existing canonical publicDigest for prepared invocations.
The signed BoundAction argument digest is prefixed prepared-v1:, keeping it outside
ordinary hexadecimal public-input digests even when caller JSON copies the binding
representation. ConsentCapsuleAuthority has explicit prepared mint/verify methods;
unsupported implementations refuse, never downgrade. ConsentCapsuleService shares
one mint/verify implementation and its existing revocation/single-use registry across
both domains. No second digest algorithm, grant type or token registry is added. PendingAuthorization
keeps public args separate for the existing display and exact redispatch. Its new
nonce is server-built at preparation time and retained with the key. A capsule that
only covers public args cannot authorize a frozen preparation. Passthrough calls keep
their existing binding.

The first approval commit only delivers metadata, capsule binding and a fail-closed
SPI path: default dispatch implementations reject a non-null preparation nonce instead
of discarding it. Gate exceptions deliver the server key/nonce to HTTP/MCP pending
records. Approval returns the reference for caller redispatch or sends it through
the server-side overload. Dispatcher activation and all remaining gate consumers,
frontend invoke/undo carry-through, preview and nonce-before-preparation negatives
remain required in the connected follow-on item. This prerequisite cannot activate
a prepared producer by itself.

## Connected dispatcher decisions (September13)

Current request provenance and the current hard-stop/grant/capsule boundary authorize
the invocation. Frozen EngineContext and executor/time remain original attribution,
not a grant. Persist the original detached envelope and use it for accepted row and
handler attribution; never restore its old process workId or signed intent token.
If the current admitted context has exactly the same attribution and work axes, attach
that current work. Otherwise admit a separate child using the frozen detached context,
as the existing approval controller does, preserving its quota/survival without
relabeling another client's live work. Current caller authorization still runs first.

DurableGrantScope gains an explicit prepared-value scope check; its default refuses
the shortcut because public arguments cannot prove containment of a server-selected
target. A scope implementation that understands a prepared schema may prove coverage
against that frozen value. The955 composite notes scope can therefore see its frozen
path, while every current raw-input scope retains its existing behavior. Unproved
prepared scope uses the ordinary confirmation ceremony, never a silent grant.

Return the runner's metadata snapshot decision before attaching completion controls.
Pending decode/reuse and fresh preparation/persistence run in its pure same-key scope.
An explicitly supplied approval nonce must match pending state before any preparation;
an existing accepted row still returns metadata first. Acceptance consumes the saved
nonce outside the scope and before effects. Generic passthrough preparation remains
transient. This connected item retires the temporary replay-schema blocker only with
negative tests, exact cipher wiring and backend approval delivery. Frozen preview and
remaining frontend/agent/workflow callers are separate named follow-on commits before
C2-3 acceptance; no actual new producer is activated by the generic mechanism.

## Prepared transport reference and locked projection (September13)

HTTP invoke/undo take preparationNonce outside public args and require its operation
key. Approval already returns both. Preserve the existing STORE_LOCKED/HTTP423 answer
at operation ingress; MCP and approved-execution use the same common typed projection.
Unlock is required before a retry; do not silently re-prepare or log content as an
uncaught handler failure. Frontend consent retries next consume the full approval
reference, including server-minted keys, while keeping the original public input.

## Frozen approval preview projection (September13)

Use a pure handler projection of the already-frozen preparation, rather than adding
another copy of the target or a new envelope version. OperationApprovalPreview is a
small bounded display value: one nonblank summary, at most8192 UTF-8 bytes, redacted
from toString. It contains target/root/scope metadata needed for approval, never note
body, prompts or credentials. Overlong metadata refuses rather than truncating a target
into ambiguity. A replay handler must explicitly provide this projection when a gate
needs it; receipt, AUTO and successful-capsule paths do not compute it.

Carry that value in the existing gate exception and TTL-bounded pending authorization.
HTTP428 and pending-by-id GET use it as their existing argsSummary, instead of rendering
public JSON for a prepared call. SSE and ledger remain routing/metadata only. This is
a derived display projection, not a second execution authority or durable payload.
Backend transport proof owns this item; long-path visual presentation must be checked
and wrapped before C2-3 acceptance, alongside agent/workflow gate consumers.

### Nested LLM delegation posture (September13)

Direct workflow tool/gate background refusal does not cover an LlmStep delegating
into another agent shape. The existing ConversationEngine always calls the shape
runner with an engine-built body, already overwriting the server-owned
recordsToThread projection. Reuse that ownership pattern for a backgroundRun
projection, with an explicit boolean engine overload. Public engine ingress defaults
to interactive and overwrites any supplied backgroundRun value; an enclosing workflow
passes its server posture explicitly. ToolIteratingShapeRunner must use the background
AgentService overload, and a nested WorkflowShapeRunner must preserve the projected
posture. Do not infer it from urgency/survival: those axes are independent. This is
the next bounded implementation item, before frontend lookup and C2-3 acceptance.
