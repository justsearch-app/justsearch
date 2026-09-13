# C2-4 continuation plan: durable history and keyed outcomes

Status: active design/implementation cut, 2026-09-13, grounded at13b6bd680.
No C2-4 acceptance is claimed. C2-3 consumer placement is recorded in section0
and the owning C2-6/8/10 checklists. Merge placement remains F/PR1.

## Acceptance order and commit cuts

1. **C2-4 wire correction.** R6 explicitly requires UNDONE in the retained proto
   and an enum-values conformance assertion. First run that assertion against the
   existing proto, then add the additive value and its wire changeset. Preserve
   existing field numbers/values. Run the focused services wire test, PMD, UI
   integration compilation and wire gate. Register the history lineage with the
   durable swap, when its producer/consumer guards describe the new authority.
2. **C2-4 keyed query and durable swap.** Start with the named six-state acceptance
   test, including row/store cross-checks and failed units against jobs. One
   committed-row-plus-history-fence read serves HTTP and MCP. Add the existing
   controller's missing handleGet coverage, restart durability, row-first lookup,
   CANCELLED/COMPLETE_WITH_GAPS projection and executionId refusal. Preserve the
   existing recent-history route/resource and bound recent() to terminal SQL rows.
   The receipt exposes bounded metadata only; prepared payloads never enter it.
3. **C2-4 completion projection.** Add post-commit subscription plus bounded
   terminal catch-up on the same store owner; subscribe before catch-up and use
   row identity for deduplication, never a completion high-water mark. Prove a
   non-dispatched memory completion and a crash before ledger append recover one
   event. Wire memory/note through the existing fan-in without duplicating agent
   run events.955 owns its product-specific ActionEvent.Memory variant.
4. **C2-4 reconnect and integration.** Capture the stream boundary before querying
   the durable snapshot, atomically subscribe/replay, and reset/repeat on overflow.
   No SQLite snapshot executes under the channel lock. Prove completion during
   reconnect and restart; update the resource retention declaration, schemas,
   operation-surface/register guards and canonical docs with the implementation.
   Run the item gates, live HTTP/MCP capture and one independent batch review.

Every cut compiles independently, has a substantive commit body and retained
proof inventory, and is pushed immediately. Full suite at the coherent batch
boundary; focused checks during the cuts. No extra installed/model tier is added.

## Source findings that the durable swap must resolve

Verified at13b6bd680; these are required design inputs, not accepted mechanisms:

- OperationHistoryStore.java:28-123 is a 200-entry deque; append listeners run
  outside its lock. OperationHistoryEntry.java:58-66 has an operation reference,
  but no invocation row identity. Keep the listener fan-in, replace deque truth.
- OperationExecutorImpl's completion callback emits STORAGE_FAILED even when the
  terminal SQLite write failed. R1 requires this failure observation; it must not
  make the durable query claim a terminal row that was never committed. Preserve
  the sticky health signal and distinguish the observation from committed history.
- AuditPolicy.NONE suppresses history, not acceptance. Historical visibility
  cannot safely be reconstructed from today's catalog, especially for removed
  operations and non-dispatched producers. Persist the acceptance-time policy
  separately from public identity; its exact migration cut precedes that code.
- SqliteOperationStore.java:102 prunes during construction. Acceptance also prunes
  under its transaction; HeadAssembly's timer prunes hourly.955 requires retained
  completion replay before startup pruning. Settle ordering for both production
  assembly paths before changing retention tests; retaining every row forever is
  not an alternative to the existing 30-day/100000-row bound.
- ActionLedgerChangeRegistry.java:143-153 puts the event in its ring before the
  journal append; ActionEventJournal.java:166-184 logs an append failure without
  reporting it and remembers the tail before writing. Ring deduplication therefore
  blocks retry after an append failure, and does not survive restart. The existing
  journal's 8x4MB retention is a separate window from the operations row window.
  The completion cut must resolve retry/deduplication across both windows before
  choosing additional persistent state; do not silently use row id as a completion
  watermark or invent another intent journal.
- SseEnvelopeWriter.java:224 onward snapshots before subscribing on a fresh attach.
  SseStreamChannel's existing subscribeAndReplay covers only the resume branch.
  Reuse its bounded replay and outside-lock delivery; handle an empty frame window
  and expiry during snapshot explicitly rather than looping forever.

## Reach and retirement

The useful principle is that a projection cannot acknowledge an effect before its
own durable sink accepted it. The present scope is the existing operation history
and action-ledger fan-in. Crash/retry and reconnect tests demonstrate its value;
remove any added projection-only mechanism if the retained source directly serves
that view and the second durable append is retired. Do not generalize this work
into another event bus, completion-sequence ledger or product-specific memory store.

## Keyed query cut

The shared port returns OperationOutcomeView from one SELECT joining the selected
operations row and singleton operations_meta. A present row wins below the fence;
a missing valid key compares its UUIDv7 time with the fence, with no lookup margin.
Malformed/non-v7 and missing far-future keys are typed invalid-key failures.
Timestamps use UTC epoch milliseconds. The typed result contains only code,
executionId and/or a list of Gap(unitId, reason), with bounded identifier/code
components; gaps are read from the reserved gaps_json column, not a handler map.
D1 produces this gap projection with its generation activation mechanism. No
arbitrary content or sealed preparation enters the SELECT's projection columns.

HeadAssembly forwards this narrow read to both the existing history controller
and justsearch_operation_outcome, a seventh curated read-only MCP tool. Querying
never dispatches or accepts an operation. Existing recent-history and SSE remain
until their durable swap cut; this keyed query is not that swap's acceptance.

Use app-engine's existing test access to both halves for the named store cross-check:
real SqliteOperationStore plus real SqliteJobQueue, no new module edge. Cover six
states, units matched to completed/failed jobs, restart, missing-key fence, older
open row below the fence, cancellation, awaiting-acceptance gaps and raw-content
exclusion. HTTP/MCP must serialize the same narrow answer and preserve typed errors.
The new route requires the existing local trust boundary and live capture before
item acceptance; it never requires a mutation token for the read itself.

## Store completion subscription prerequisite

After the keyed-read cut, implement the generic live completion hook before
connecting projection consumers. OperationStore owns subscriptions because direct
non-dispatched producers, pre-start rejection and reconciler completion all commit
through that same owner. Observe only successful state transitions, using the row
snapshot captured with the transition; publish after the SQLite lock is released.
Repeated/refused transitions publish nothing. A throwing observer is logged and
cannot rewrite completion or stop another observer. Unsubscribe/close retire the
listener reference; a notification already iterating its listener snapshot may
still deliver that callback.

This reuses synchronous post-commit notification and bounded retained source rows;
no notification executor, second outbox, acknowledgement marker or completion
sequence is added in this prerequisite. It is live delivery only. The next
projection cut supplies bounded catch-up, row-identity deduplication and startup
replay before pruning. Do not claim the hook alone closes the crash interval.
Test the commit from an independent SQLite reader and the unlocked callback from
another thread reading the same store. Cover non-dispatched MEMORY/NOTE rows,
repeated finish, before-start rejection, listener failure, removal and store close.
The launcher architecture fixture must implement every new port method and stay
in the affected compile/test set after the query-cut omission.
