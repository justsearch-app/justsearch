// SPDX-License-Identifier: Apache-2.0
/**
 * pendingAuthorizationBridge — tempdoc 655.
 *
 * Surfaces a backend-created {@code PendingAuthorization} in the running app even when the
 * frontend never made the request that triggered the gate — concretely, an MCP tool call from
 * an external AI agent. Today's `authorizationBroker` ceremony is otherwise strictly
 * request-scoped: a gated `fetch` gets a 428 with a `pendingId` in THAT SAME response, and
 * `OperationClient.invokeWithConsent` hands it to the broker. An MCP-originated gate has no such
 * in-flight request for the frontend to observe.
 *
 * Pattern mirrors `api/intent/bootIntentStreamBridge.ts` (tempdoc 662's multiplexed-stream
 * subscriber shape) and `substrates/tasks/indexingJobsBridge.ts`'s `startXBridge(apiBase, opts)`
 * boot signature — subscribes the `system:pending-authorizations` streamId (event-only, no
 * snapshot lifecycle — each creation is its own self-contained announcement, same as the intent
 * stream) on the shared `MultiplexedStream`.
 *
 * On each announcement, this reuses the EXISTING ceremony (`authorizationBroker.requestAuthorization`
 * — the same `<jf-authorization-host>` dialog a live REST 428 already uses) rather than a new
 * presentation surface. Two genuinely new steps, both tempdoc 655 fix-pass additions:
 *
 * 1. The broadcast itself deliberately carries NO decision content (no args summary, no
 *    rationale) — only routing info (id, operation id, tiers). Putting that content on a channel
 *    every local subscriber receives would violate the existing privacy posture that scopes it
 *    to the point-to-point 428 response (tempdoc 444b / 550 F3 — see `PendingAuthorizationEvent`'s
 *    doc comment). So this bridge fetches the content itself, by id, via
 *    `OperationClient.peekPending` — the two-step shape mirrors what the REST 428 path gets in
 *    one response, just split into "learn it exists" (broadcast) then "learn what it is" (fetch).
 * 2. Because the frontend never held this pending's original arguments (it wasn't the caller),
 *    approval can't complete by the browser re-`invoke`-ing with a capsule the way
 *    `OperationClient.invokeWithConsent` does for its own gated calls. Instead it calls
 *    `OperationClient.approveAndExecutePending`, which asks the backend to complete the dispatch
 *    itself, server-side, using the pending's own stored args — the design tempdoc 655 settled on
 *    specifically so the MCP tool call never has to block or be retried by the calling agent.
 */

import type { MultiplexedStream } from '../streaming/MultiplexedStream.js';
import type { SseEnvelope } from '../streaming/envelope-types.js';
import { SHELL_EVENT_STREAM_IDS } from '../streaming/shellEventStreamIds.js';
import { requestAuthorization, withdrawAuthorization, type AuthorizationPrompt } from './authorizationBroker.js';
import { getOperationClient, isStaleAuthorization } from './OperationClient.js';
import { emitEphemeralToast } from '../components/advisory/ephemeralToast.js';
import { openActionLedgerStream } from './ActionLedgerClient.js';
import { subscribeStatus } from '../utils/statusPoll.js';

/** Wire shape of the SLIMMED `PendingAuthorizationEvent` — routing info only, no decision content. */
interface PendingAuthorizationPayload {
  readonly pendingId?: string;
  readonly operationId?: string;
  readonly sourceTier?: string;
  readonly riskTier?: string;
  readonly gateBehavior?: string;
}

/** Bounded so a long-running session's dedup set can't grow unbounded. */
const HANDLED_LRU_SIZE = 500;

/**
 * Boot the bridge. Idempotent per call site — unlike `bootIntentStreamBridge`, this has no
 * module-level singleton guard because (mirroring `startIndexingJobsBridge`) the caller (Shell)
 * owns a single boot call and the returned teardown handle.
 *
 * @returns Teardown: unsubscribes from the multiplexed stream. No-op (and a no-op start) when
 *     `multiplex` isn't supplied — this stream only exists on the multiplexed transport, unlike
 *     older single-purpose bridges that also supported a standalone `EventSource` fallback.
 */
export function startPendingAuthorizationBridge(
  apiBase: string,
  opts: { multiplex?: MultiplexedStream } = {},
): () => void {
  if (!opts.multiplex) {
    return () => {};
  }
  const multiplex = opts.multiplex;

  // Dedup by pendingId: a reconnect can replay the ring buffer, and a gate that ALSO reached a
  // live REST caller (unlikely for MCP, always true for the browser's own 428s, which are
  // broadcast here too for a uniform signal) would otherwise be presented twice.
  const handledIds = new Map<string, true>();
  const waiting = new Map<string, { checking: boolean }>();
  let stopped = false;

  // Reuse the existing operation stream for prompt completion, and the shared status cadence
  // for expiry / Engine restart. Only requests with an open ceremony are read; no new timer.
  const reconcile = (): void => {
    for (const [id, entry] of waiting) {
      if (entry.checking) continue;
      entry.checking = true;
      void getOperationClient(apiBase).peekPending(id).then(detail => {
        if (!stopped && waiting.get(id) === entry && !detail) {
          waiting.delete(id);
          withdrawAuthorization(id);
        }
      }).catch(() => {
        // A transport failure is not evidence that a request was answered or expired.
      }).finally(() => { entry.checking = false; });
    }
  };
  const stopActivity = openActionLedgerStream({ multiplex, onActivity: reconcile });
  const stopStatus = subscribeStatus(snapshot => { if (snapshot) reconcile(); });

  const stopSub = multiplex.subscribe<{ lastPayload: PendingAuthorizationPayload | null }>(
    SHELL_EVENT_STREAM_IDS.PENDING_AUTHORIZATIONS,
    () => ({
      initialState: { lastPayload: null },
      reducer: (state, env: SseEnvelope) => {
        // Event-only stream (no snapshot to consume) — LIFECYCLE frames (connected/heartbeat/
        // reset) carry no state this bridge needs; only UPDATE frames matter.
        if (env.frameKind === 'LIFECYCLE') {
          return state;
        }
        return { lastPayload: env.payload as PendingAuthorizationPayload | null };
      },
    }),
    (snapshot) => {
      const payload = snapshot.payload.lastPayload;
      if (!payload?.pendingId || !payload.operationId) {
        return;
      }
      // These user-only operations always have their own exact-target ceremony in the
      // originating window. A broadcast may precede its 428; it must never offer a second
      // route that can execute while that window still offers Cancel.
      if (payload.operationId === 'core.forget-indexed-path' || payload.operationId === 'core.retry-forget'
          || payload.operationId === 'core.withdraw-forget') return;
      if (handledIds.has(payload.pendingId)) {
        return;
      }
      handledIds.set(payload.pendingId, true);
      while (handledIds.size > HANDLED_LRU_SIZE) {
        const oldest = handledIds.keys().next().value;
        if (oldest === undefined) break;
        handledIds.delete(oldest);
      }
      const entry = { checking: false };
      waiting.set(payload.pendingId, entry);
      void presentAndExecute(apiBase, payload, () => !stopped && waiting.get(payload.pendingId!) === entry)
        .finally(() => waiting.delete(payload.pendingId!));
    },
  );

  return () => {
    stopped = true;
    stopActivity();
    stopStatus();
    stopSub();
    for (const id of waiting.keys()) withdrawAuthorization(id);
    waiting.clear();
  };
}

async function presentAndExecute(
  apiBase: string,
  payload: PendingAuthorizationPayload,
  isCurrent: () => boolean,
): Promise<void> {
  const pendingId = payload.pendingId as string;
  const client = getOperationClient(apiBase);

  // Tempdoc 655 fix pass: the broadcast carries no decision content — fetch it by id before
  // presenting. If it 404s, the pending already expired or was consumed elsewhere between the
  // broadcast and this fetch; skip presenting rather than show a broken/empty prompt.
  let detail;
  try {
    detail = await client.peekPending(pendingId);
  } catch (err) {
    console.warn('Tempdoc 655: failed to fetch pending authorization detail', err);
    return;
  }
  if (!isCurrent()) return;
  if (!detail) {
    withdrawAuthorization(pendingId);
    return;
  }

  const prompt: AuthorizationPrompt = {
    pendingId,
    operationId: detail.operationId,
    gateBehavior: payload.gateBehavior ?? detail.gateBehavior ?? 'TYPED_CONFIRM',
    ...(detail.riskTier ? { riskTier: detail.riskTier } : {}),
    ...(detail.argsSummary ? { argsSummary: detail.argsSummary } : {}),
    ...(detail.rationale ? { purpose: detail.rationale } : {}),
    ...(detail.requestedBy ? { requestedBy: detail.requestedBy } : {}),
  };
  const decision = await requestAuthorization(prompt);
  if (!isCurrent() || decision.superseded) return;
  if (decision.failedClosed) {
    // Tempdoc 807 item 4: an explicit human deny is a decision and needs no message — but a
    // FAIL-CLOSED outcome (no host mounted, or a mounted host torn down with this ceremony still
    // open) is a failure wearing a deny's clothes. Round 13 saw exactly that shape: the modal
    // dismissed, nothing dispatched, and nothing told the user why. Same channel as every other
    // outcome this function reports.
    reportExecutionFailure(prompt, 'the approval ceremony closed before the decision was sent');
    // The pending record itself expires server-side (5-minute TTL) if never approved; there is no
    // separate "reject" round trip to make for an MCP-originated pending.
    return;
  }

  // Fix pass: `requestAuthorization` can take an arbitrarily long time to resolve (the human has
  // to read the prompt and, for TYPED_CONFIRM, type the operation id) — long enough for the
  // pending to die (consumed by another caller, or past its server-side TTL) between the peek
  // above and the decision landing here. Re-peek right before POSTing so that case is caught with
  // the same "gone" signal already used before presenting, instead of only surfacing as a raw
  // approve-endpoint failure. A transient re-peek failure (network blip) doesn't block the
  // approval — the POST below has its own error handling and is the arbiter in that case.
  let stillPending: typeof detail | null = detail;
  try {
    stillPending = await client.peekPending(pendingId);
  } catch (err) {
    console.warn('Tempdoc 655 fix pass: re-peek before approve failed, proceeding anyway', err);
  }
  if (!isCurrent()) return;
  if (!stillPending) {
    reportNoLongerPending(prompt);
    return;
  }
  if (!decision.approved) return;

  try {
    const result = await client.approveAndExecutePending(pendingId, decision.allowAlways);
    // Tempdoc 655 fix pass: `execute:true` can come back 200 OK with the dispatch itself having
    // failed or been skipped server-side (`executed: false`, e.g. no dispatcher wired, the
    // operation wasn't found, or the dispatch threw — see AuthorizationController
    // .executeApprovedPending's doc comment, which already names `executeMessage` as the intended
    // user-facing explanation for exactly this case) or having run but reported failure
    // (`executed: true, executeSuccess: false`). Both outcomes were previously dropped on the
    // floor entirely (never even logged) — the same silent-failure shape as the swallowed throw
    // below, just on the 200-OK path instead of the 410 path.
    if (!result.executed || result.executeSuccess === false) {
      reportExecutionFailure(prompt, result.executeMessage ?? 'the action did not complete');
    }
  } catch (err) {
    if (isStaleAuthorization(err)) {
      reportNoLongerPending(prompt);
      return;
    }
    // Best-effort: the approval ceremony already resolved (the human said yes), so there's no
    // live caller awaiting this promise to report a failure to, unlike the REST-triggered path
    // (invokeWithConsent), which throws back into its own call stack. Unlike before the fix pass,
    // the failure is no longer console-only — it reaches the user via the same ephemeral-toast
    // channel Shell.ts's sibling gated-dispatch path (`invoke-operation` effect) already uses for
    // exactly this "nobody is synchronously awaiting this promise" shape.
    reportExecutionFailure(prompt, err instanceof Error ? err.message : String(err));
  }
}

function reportNoLongerPending(prompt: AuthorizationPrompt): void {
  emitEphemeralToast({
    message: `Request for '${prompt.operationId}' is no longer pending (answered or expired).`,
    severity: 'info',
  });
}

/**
 * Fix pass: surface an approved-but-failed pending authorization to the user. Reuses the single
 * client-originated message channel ({@link emitEphemeralToast}, tempdoc 559 Authority III) rather
 * than inventing a new presentation surface — the same channel Shell.ts's `invoke-operation` effect
 * handler already uses for the structurally identical "gated dispatch failed with no live caller to
 * throw back to" case. `severity: 'error'` makes the toast sticky (no auto-dismiss) per the
 * severity→presentation projection in `messageClasses.ts` — an error must not silently auto-vanish,
 * which is exactly the failure mode this closes (the modal had already advanced past this pending by
 * the time the outcome is known).
 */
function reportExecutionFailure(prompt: AuthorizationPrompt, reason: string): void {
  console.warn('Tempdoc 655 fix pass: pending authorization approved but execution failed', reason);
  emitEphemeralToast({
    message: `Approval for '${prompt.operationId}' failed: ${reason}`,
    severity: 'error',
  });
}
