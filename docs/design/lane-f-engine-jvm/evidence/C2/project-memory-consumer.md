# Project memory as the first external operations-row consumer

Adopted2026-09-12 under design section0's amendment protocol, items955-1 through955-6.
Source: branch worktree-955-project-memory, commit b30d41fea,
docs/design/project-memory/design.md sections3.5,4,8. Inspected from the sibling
worktree955-project-memory; its newer d69b39dcb changes only sequencing prose in
this file. This record is self-contained; implementation does not require a private
transcript or a merge of955 into lane F. Lane F still publishes at F/PR1;955 lands
after lane F. No locked placement, trust boundary or product decision is reopened.

## 1. C1 quota correction

RequestEngineContext.java:29-30 at5f0c2c931 prefers a cooperative client-id header
over the MCP session. That contradicts C1's batch-3 quota rule. The MCP protocol owns
server-issued session identity; resolve it before admission and ignore cooperative
client-id/kind/source overrides. Missing/unrecognised sessions share the anonymous
client bucket until established, so arbitrary session strings cannot bypass the fix.
The bounded claim is per established client identity, with the aggregate ceiling
covering multiple clients; this is not authentication of a same-user native process.

Required correction proof: hold one admitted MCP call, rotate the client-id header
on another call in the same session and hit the same per-context ceiling. Cover
missing/unknown sessions and unchanged browser hints. This is a C1 correction during
C2, recorded separately from the already-completed C1 hosted/live proof.

## 2. Admission without catalog dispatch

MemoryAdmission owns one accepted row per remember/correct/forget/clear/import-batch
mutation before any sealed store effect. It consumes app-api's shared runner, not
OperationExecutorImpl. The port exposes accept and complete/fail semantics through
runner-issued capabilities and the accepted-body/actual-completion contract; a
producer must not require a catalog handler to use it. Any explicit terminal-call
adapter commands that runner, never writes OperationStore directly or terminalizes
an arbitrary row id. Existing accept/start plus OperationExecution completion is the
first adapter; C2-2 verifies this non-dispatched path with a sealed-mutation fixture.

The fixture must fail acceptance before touching the store, persist an effect only
after acceptance/start, terminalize failures truthfully and complete exactly that
row.955 implements MemoryAdmission/the memory store; lane F owns this usable port
and its generic regression. No memory-specific second intent journal is introduced.
Implemented local port proof602: four sealed-mutation consumer cases plus six runner
cases pass; see [C2-2 evidence](C2-2-plan.md#non-dispatched-sealed-mutation-consumer-september12).
The fixture exercises admission, not955 product mutation semantics.

The closed recordKind vocabulary gains memory and note in C2-3. Memory mutations
are interactive. Ordinary note creation is interactive; core.undesignate-notes-root
explicitly declares durable survival because it must await acknowledged deletions.
Kind alone therefore does not determine survival for every note operation. The
catalog declaration remains the authority; do not hard-code this id in the runner.

## 3. Prepared invocations and keyed retry

The public schema remains caller input. The dispatcher builds a versioned prepared
invocation containing that input plus frozen target path, designation revision,
original attribution and operation key. The same preparation is used by the trust
gate, pending authorization, approval and accepted operation. Callers cannot submit
server-owned fields as preparation.

Persist two related identities on the one row: the canonical public-input digest
and the prepared invocation. Compare operation reference, invoke/undo identity and
public digest before returning an existing outcome. Canonicalize public input by
the declared public schema, excluding only its separately-carried operationKey;
include all effect-bearing public fields. Do not compare caller JSON directly to
the larger server-built record. This yields one answer for changed input:
OPERATION_KEY_REUSED, whether the existing row is open or terminal. Server-added
path/revision/attribution are frozen values, not new caller requirements.

Lookup precedes preparation. After current caller authority is validated:

- Matching terminal row: return its non-content receipt without preparation or
  another target-exists check.
- Matching open row: attach to the live attempt, or let its recovery owner resume
  the persisted preparation under the existing eligibility/effect-witness rules.
  A retry never itself grants permission to rerun a committed effect.
- Unknown key: validate and prepare once. Coordinate concurrent same-key calls;
  if authorization is pending, reuse its persisted prepared invocation before
  preparing another target. The pending record owns unaccepted preparation; the
  operations row owns accepted preparation. Acceptance transfers that identity.
- Expired key: refuse; never prepare or reclassify it as a fresh mutation.

Generic operations keep digest-only identity. Replayable prepared invocations are
an intentional additional representation, not a second truth: persist their
content-bearing payload sealed by the existing data-key authority in the operations
row, with a format/version discriminator and bounds. Public digest/metadata and
outcome receipt stay separately readable. A locked key prevents preparation/replay;
metadata outcome queries do not expose the sealed content. Retention applies to the
payload with its row. Never put it in result_json, history or ledger. This resolves
the conflict between a replayable note body and C2's metadata-only receipts without
putting plaintext prompts in operations.db or adding another payload store.

Required C2-3 cases: terminal retry after the note file exists, accepted retry after
restart, changed public input with the same key, concurrent unknown callers, pending
approval retry, designation revision change, locked key, and no caller-controlled
prepared fields. The note's own front-matter operation key is an effect witness for
reconciliation; a file created by that operation is not mistaken for an unrelated
target collision and is never written a second time.

## 4. Completion as a durable projection source

C2-4's history authority exposes terminal-row catch-up plus completion subscription
after the SQLite commit returns. Capture the bounded stream token before the durable
snapshot, subscribe/replay atomically, and restart the snapshot if that token expires.
On restart replay retained terminal rows before pruning history. Row id is the
projection identity, not a completion high-water mark: an old accepted row can
complete after a newer one. Bounded catch-up/dedup uses the existing retention/cap;
a missing retained history window is explicit, never silently complete.

This reuses the operations row as the outbox source; a separate memory intent/event
store or a new completion-sequence ledger is unnecessary. The durable ActionEvent
journal deduplicates source row identity. Crash between row completion and append
therefore replays one event.955 defines ActionEvent.Memory and its product metadata;
lane F supplies the generic hook, replay and fan-in needed by that adapter, with a
non-dispatched producer regression. Projection metadata must be explicitly typed
and bounded, never an arbitrary handler result map.

Memory/note rows enter OperationSubstrateInit's fan-in regardless of AGENT_LOOP
transport; prevent duplicate projection through the agent-run branch. Event fields
may include opaque record/project/note identity, mutation kind, client attribution
and derived-from count. No content, content digest, title or filename is exposed.
955 wires its variant into the journal, API filter, frontend and register when it
lands. This contract does not require inventing a placeholder product variant now.

## 5. D1 non-file accepted writes

The widened generation journal covers accepted document upserts and identity deletes
from the port even without a file. Stable document identity and accepted projection
revision replace path/source hash as the unit identity for this arm. Preserve file
coalescing; use a versioned payload in the existing journal where needed, not a
fabricated path or a parallel memory journal. Replay all accepted revisions/deletes
before activation. Missing or stale required projection is a gap; a deleted identity
must stay absent.955's generation reset remains recovery, not post-activation refill.

D1-9 owns tests for non-file writes/updates/deletes during a rebuild, crash during
replay, missing-projection refusal and no resurrection after an acknowledged delete.

## 6. D2 durable deletion is lane F work

D2-5 owns an identity-based delete acknowledgement beside indexAndReturn, with
explicit EngineContext and the same bounded group-commit coordinator. Return durable
success only after the covering commit and required D1 journal acceptance; never
from enqueue, NRT refresh, timeout or cancellation. Already absent is idempotent
with an authoritative durable absence proof. The projection and note un-designation
consume this port after lane F;955 need not build a competing acknowledgement path.

Required proof: immediate forced restart preserves deletion; concurrent durable
deletes coalesce commits; failed commit yields no durable success; a concurrent
reindex cannot restore the identity. Register955's port consumer when its code lands.
