// SPDX-License-Identifier: Apache-2.0
/**
 * liveRuns — Tempdoc 834 §5.1/§15.3: the FE's run-DISCOVERY authority, read from the backend.
 *
 * This replaces the retired cross-tab `localStorage` run pointer. A pointer could only ever describe
 * runs THIS browser started, and it went stale whenever the writing tab died before its terminal
 * cleared it. `GET /api/chat/runs/live` is the registry's own answer, so it cannot go stale and it
 * sees runs this browser never started.
 *
 * Degradation is deliberate and total: a network failure, a non-2xx, or a body the generated Zod
 * validator rejects all resolve to "no live runs". Discovery runs on the mount path, and a mount
 * that throws is a blank window — a missed reattach is recoverable, a dead surface is not.
 */
import {
  liveRunsResponseSchema,
  type LiveRunsResponse,
} from '../../api/generated/schema-types/live-runs-response.js';
import { authorizedFetch } from '../api/authorizedFetch.js';

/** The shape id every steerable agent run carries (`RunChannelObservation.AGENT_SHAPE_ID`). */
export const AGENT_SHAPE_ID = 'core.agent-run';

/** One row of the enumeration. */
export type LiveRunRow = NonNullable<LiveRunsResponse['runs']>[number];

/**
 * Every run executing right now, newest-first (the registry sorts by `startedAtEpochMs` descending;
 * this preserves that order rather than re-deriving it from a field it would then have to assert).
 * Returns an empty list for every failure mode — see the module note.
 */
export async function fetchLiveRuns(
  apiBase: string,
  shapeId?: string,
): Promise<readonly LiveRunRow[]> {
  try {
    // `conversationId` is deliberately NOT sent as a filter: the caller's guard is asymmetric — a run
    // with no conversation of its own is adoptable by anyone, which a server-side equality filter
    // would exclude. Narrowing by shape has no such asymmetry.
    const query = shapeId ? `?shapeId=${encodeURIComponent(shapeId)}` : '';
    const res = await authorizedFetch(`${apiBase}/api/chat/runs/live${query}`);
    if (!res.ok) return [];
    const parsed = liveRunsResponseSchema.safeParse(await res.json());
    if (!parsed.success) return [];
    return parsed.data.runs ?? [];
  } catch {
    return [];
  }
}

/**
 * The runId of the newest live AGENT run a caller pinned to `conversationId` may adopt, or null.
 *
 * The conversation guard is the behaviour the retired pointer established and is preserved exactly:
 * a caller pinned to a SPECIFIC conversation must not adopt a run belonging to a DIFFERENT one,
 * while a caller with no conversation of its own (a fresh tab) still adopts the newest agent run —
 * that cross-tab adoption is the whole point of discovery. A run that carries no conversationId is
 * adoptable by either.
 */
export async function discoverLiveAgentRun(
  apiBase: string,
  conversationId?: string | null,
): Promise<string | null> {
  const runs = await fetchLiveRuns(apiBase, AGENT_SHAPE_ID);
  for (const run of runs) {
    // The server honours `?shapeId=`; this re-check is the guarantee, so a backend that ever stopped
    // honouring it degrades to "found nothing" instead of attaching to a workflow run.
    if (run.shapeId !== AGENT_SHAPE_ID) continue;
    if (!run.runId) continue;
    if (conversationId && run.conversationId && conversationId !== run.conversationId) continue;
    return run.runId;
  }
  return null;
}
