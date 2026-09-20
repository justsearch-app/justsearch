# C2 declared survival and real admission

Selected 2026-09-20 after live1929: the registered ingestion parent and child complete
but retain INTERACTIVE survival from the HTTP request. This closes the already owed
declared-kind prerequisite in recorded-root-preparation.md and C2-3. C2 stays open;
merge placement stays F. The current operation-kind fix remains necessary and is
not itself evidence of crash survival.

## Contract and ownership

Add an optional declared survival to OperationPolicy, using the existing
EngineContext.Survival enum through app-agent-api's existing core dependency.
Absence inherits the caller's survival, preserving generic operation behavior.
Ingest and reindex explicitly declare DURABLE. Memory and ordinary notes can declare
INTERACTIVE, and note un-designation can declare DURABLE without inventing a new
record kind. Preserve the override in every policy copy method and compatibility
constructor, and regenerate the governed schema projections. Survival is separate
from kind, urgency, trust and audit; no new vocabulary or path-based permission is
introduced.

Composition must reject an explicitly DURABLE declaration whose record kind has no
registered boot recovery owner. Use the existing root-owned kind set; do not invent
another registry. Inherited survival keeps its existing behavior. Pin INGEST/REINDEX
and a registered NOTE owner, plus fail-fast rejection of an unowned explicit kind.

One policy projection returns the existing context for inherited/equal survival.
When survival differs it preserves attribution, grant, transport and urgency but
clears work identity: a differently classified effect must be admitted as new work,
not attached under another work's id. Only the real admission result supplies its
work id. Freeze the declared axes into prepared invocation context before storing
the preparation. Matching recorded keys still answer before preparation or new
effect admission; stored pending preparation remains the original caller's scope.
Accepted rows keep their recorded survival across an upgrade. A still-unaccepted
pending envelope that conflicts with a current explicit declaration refuses as
preparation unavailable; silently relabeling its frozen axes is not compatibility.

### Direct HTTP and native MCP

For the REST alias and generic invoke/undo, classify before the HTTP filter admits
work. Inject the existing OperationsController/catalog resolver into the filter;
use the same alias/operation-id resolution as actual dispatch. The classifier
returns only the declared survival and grants no mutation authority. Select Javalin
`beforeMatched` for Engine admission: the pinned6.7 implementation updates the actual
matched endpoint before invoking that filter (tmp/1937-before-matched.txt). Verify
with real Javalin routes, including `/api/undo/{id}`. Share route constants with the
controller registration and its catalog resolver, without a second route table.
Keep the existing security/capability before-filters ahead of matched admission. Preserve all
loopback, Host, Origin, token, capability and freeze checks.

For POST /mcp, let the existing protocol parser resolve the message first, then admit
through the same Engine admission service before invoking a tool/resource/prompt.
The generic HTTP filter continues all security/lease checks but does not also admit
this message. The protocol uses its parsed id for capacity/freeze errors and retains
notification/no-response semantics. Direct methods stay interactive. Operation tool
aliases obtain survival from the same catalog binding used to dispatch them; factor
the existing browse/ingest alias mapping once rather than duplicating it. No second
JSON-RPC parser or session permission authority is introduced. The HTTP after-hook
closes the exact work handle registered by the protocol, including error paths.
The pinned framework already bounds request bodies to1,000,000 bytes; classification
does not add an unbounded parser. This bounded protocol parsing precedes effect
admission, just as route matching precedes HTTP classification.

This gives one work slot to one direct operation request, including when the
per-client limit is1. Classifying only inside the dispatcher would temporarily
require two slots for every direct request and cannot satisfy that contract.

### Nested effects and acceptance

An interactive agent/workflow and its accepted durable mutation have different
lifetimes. The dispatcher admits a separate durable effect when its incoming work
has different declared survival. Both use the same quota bucket and count toward
the actual limits; this is a real child, not a quota exemption. Equal axes retain
the existing exact work. Approval execution keeps its existing reserve-before-
consume rule and frozen-origin attribution.

Move valid effect admission before attempt acceptance. Capacity/freeze refusal must
leave an unknown key and no effect, with saved pure preparation allowed. Keyed
recorded outcomes need no new admission. Retain the admitted effect until its actual
asynchronous completion, using the existing handle retention. Preserve recording
of preparation/input refusals without manufacturing an effect.

Reserve the effect before consuming a single-use capsule as well as before row
acceptance. An admission refusal must leave that capsule usable for a later retry.
The runner must arbitrate final key lookup, effect reservation and acceptance as one
ownership decision: a concurrent loser observes the winner without reserving a second
effect or consuming consent. This applies after ordinary request-envelope admission;
it does not bypass C1's ingress bound. Reuse the runner's key ownership and preserve
its prohibition on publishing arbitrary callbacks under preparation locks. Selected
runner API `admitAndAccept` uses the existing stripe for raw final lookup, a scoped
nonpublishing reservation callback, and raw store acceptance. Its callback receives
one thread-bound acceptance capability, must accept once, and cannot retain that
capability beyond the scope. Existing rows bypass the callback. Prepared/control
futures are constructed after the stripe is released. There is no new pending-key
registry. The callback's resources remain the caller's responsibility on failure.

Capsule validation/removal must return deferred publication, since the current
consume method synchronously emits the CONSUMED grant event. Existing public verify
wrappers preserve synchronous publication; the dispatcher publishes deferred grant
and gate outcomes after leaving the stripe. Register parent listeners beforehand.
Lock order is runner stripe, admission lock (released), local handoff lock, capsule
atomic state, SQLite lock. Hold the handoff lock across the final cancellation check,
consumption, store acceptance and accepted flag. Callback delivery only changes local
state while holding that lock; child detach/cancel happens after releasing it.
On store failure after capsule removal, consent stays spent as today; no rollback
authority is introduced. Capacity/freeze refusal occurs before removal.

For distinct child work, establish the caller cancellation boundary explicitly:
before acceptance, an already cancelled caller cannot launch a new effect; after
acceptance, caller cancellation/completion detaches durable foreground work to
background instead of cancelling it. Serialize acceptance and cancellation delivery
at the local handoff if a simple cancellation check leaves a race. The handoff is
process-local and owns no durable state or second operation registry. Remove its
listener registrations when the effect finishes. Hold a temporary parent attachment
only through the handoff: attach/check parent, register callbacks, reserve child,
serialize acceptance, then close that temporary parent reference. Retaining it until
child completion would prevent observing the real caller's last close. Keep only the
child's effect reference until actual completion, and remove both registrations then.
Direct HTTP operation response completion
likewise detaches a still-running durable foreground operation; an accepted response
is not continued foreground demand. The existing operation/queue owner remains the
only source of checkpoint and terminal publication.
Scope this after-hook to operation HTTP/MCP responses. RunStreamController owns the
live SSE client's demand; a blanket detach in every HTTP after-hook would demote an
open stream prematurely.

The reverse override (an explicitly interactive effect called from durable work)
also acquires its own real interactive owner; it remains cancellable by
cancelInteractive. Parent cancellation cancels that interactive child; normal parent
completion ends caller demand without pretending it cancels a durable child. Do not
generalize the durable child's no-cancellation rule to
every different-axis child. Pin both directions and inherited/equal axes in tests.

Rejected alternatives: mutating Work.initial or relabeling only the row lies about
cancelInteractive; inferring survival from kind cannot express both note lifetimes;
new permission/queue state duplicates existing owners; a blanket second HTTP work
breaks cap1. Explicit declaration, front classification and a genuine nested child
are the smallest ownership split that preserves those contracts.

## Bounded implementation and proof

1. Declaration and projection: policy field/copy constructors, INGEST/REINDEX
   declarations, schema copies/projections, inheritance/equal-axis/new-work tests.
   The declaration is not locally complete until its actual admission wiring lands.
2. Direct fronts: real API filters plus actual catalog/dispatcher under cap1; native
   MCP with parsed request-id errors and notification behavior. Verify server-selected
   DURABLE, matching row/owner/work id, one slot, preserved urgency/trust, and token/
   Origin/Host refusal. Unknown/invalid tools and operations retain their errors.
3. Dispatcher and nested lifetime: real admission owner/SQLite, cap/freeze refusal
   before acceptance and capsule consumption, barrier-controlled same-key one-effect
   admission/identical result, different-origin frozen preparation,
   interactive parent cancellation before/after acceptance, durable child detach,
   actual slot release and no leaked listeners. Exercise agent and workflow context
   forwarding rather than claiming nested proof from a standalone dispatcher only.
4. Final live ingestion/reindex: observe DURABLE rows and real owner cancellation,
   receipt/ack completion, retained matching outcomes and changed-input conflicts.
   Repeat installed supervisor/restart proofs after this correction. Earlier live
   search/model success remains useful but cannot stand in for crash survival.

Root owns cross-module admission integration, builds and publication. Delegate only
bounded declaration/front tests after this mechanism is reviewed; unresolved
ownership/cancellation behavior returns to root before implementation grows. Keep
the .3c.2a refusal matrix and .3c.2b store-reopen proof distinct from this front fix.
