# C2-3: key identity, prepared invocation and approval

Status: active implementation plan, 2026-09-13, grounded at11254a1e0.
The validated lookup prerequisite passes final909; keyed dispatcher SPI and receipt
access pass final913. HTTP invoke/undo, approval key/mode and approved-result projection pass923.
MCP ingress and persisted preparation remain open. R1–R10 are accepted; this record owns the next C2 item. No prepared
producer is active and no C2-3 completion is claimed.

## Current seams and intended behavior

The HTTP invocation parser already reads idempotencyKey, but OperationsController
still dispatches without it. OperationDispatcher's invoke and undo overloads lack
a key. OperationExecutorImpl prepares before accepting a runner request with a null
key. SqliteOperationStore already compares kind, operation reference and canonical
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
