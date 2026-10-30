// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 561 P-A/P-B (Slice 2) — fetch + validate the canonical thread record from
 * {@code GET /api/thread/{id}}. Kept separate from {@link ./unifiedThreadProjection} so that
 * projector stays a pure, dependency-free function (the projection-not-fork discipline).
 */
import { z } from 'zod';
import { rememberServerConversation } from '../state/conversationListStore.js';
import type { ThreadEvent } from './unifiedThreadProjection.js';
import { authorizedFetch } from '../api/authorizedFetch.js';

/**
 * Tempdoc S4a (risk-review finding #1) — the closed vocabulary of backend event kinds THIS build
 * recognizes. Kept as its own named array (not inlined in the Zod enum call) so
 * `scripts/ci/check-thread-event-kinds.mjs` can regex-extract it and cross-check it against the
 * backend's `InteractionEventKind` Java enum (FORWARD: every Java member appears here; BACKWARD: every
 * member here is a real Java member) — the sync check the sibling gates (`check-message-classes.mjs`,
 * `check-readiness-reason-codes.mjs`) already establish the house pattern for.
 */
const KNOWN_EVENT_KINDS = [
  'USER_MESSAGE',
  'ASSISTANT_MESSAGE',
  'TOOL_ACTIVITY',
  'PROGRESS',
  'ERROR',
  'HANDOFF',
  'SEARCH',
] as const;

/** Tempdoc 561 P-A/P-A2 — the typed loop object summary (state + Turn/Iteration counts + budget). */
interface ThreadLifecycle {
  readonly sessionId: string;
  readonly state: string;
  readonly actor: string;
  readonly turns: number;
  readonly iterations: number;
  readonly toolCalls: number;
  readonly actors: readonly string[];
  readonly budget: {
    readonly initial: number;
    readonly consumed: number;
    readonly remaining: number;
    readonly overBudget: boolean;
  };
}

/** The /api/thread response: the unified thread events + the agent runs' typed lifecycles. */
export interface ThreadResponse {
  readonly events: ThreadEvent[];
  readonly lifecycles: ThreadLifecycle[];
  /** Export must refuse a record the display reader could only partially recover. */
  readonly incomplete?: boolean;
}

/**
 * The exact pre-S4a schema/strictness for a KNOWN-kind event — unchanged: `kind` must be one of the
 * seven {@link KNOWN_EVENT_KINDS} (minus `SEARCH`, which has its own strict attributes schema below),
 * and originator/content/attributes are all required. A known-kind event that fails this (e.g. a
 * missing `content`) is still treated as invalid — S4a only changes the BLAST RADIUS of that failure
 * (this one event is dropped, tempdoc S4a §4c), never the strictness itself.
 */
const genericKnownThreadEventSchema = z
  .object({
    id: z.string(),
    occurredAt: z.string(),
    kind: z.enum(KNOWN_EVENT_KINDS).exclude(['SEARCH']),
    originator: z.string(),
    content: z.string(),
    attributes: z.record(z.string(), z.unknown()),
  })
  .loose();

/**
 * Tempdoc S4b (Search Thread) — the typed attribute shape of a `SEARCH` event, matching what
 * `AgentInteractionMapper`'s `search_executed` case persists: the identity of a manually-triggered
 * search action (not a tool call), so the reloaded thread renders the same committed search card.
 */
const searchAttributesSchema = z.object({
  query: z.string(),
  mode: z.string(),
  matchCount: z.number().int(),
  resultCount: z.number().int(),
  docIds: z.array(z.string()),
  executedAt: z.string(),
});

/**
 * Tempdoc S4b — `SEARCH` is a KNOWN kind (unlike the generic per-kind schema above) with STRICTLY
 * typed attributes, since the FE needs `query`/`mode`/`matchCount`/`resultCount`/`docIds`/`executedAt`
 * to render the committed search card, not an opaque `Record<string, unknown>`.
 */
const searchThreadEventSchema = z
  .object({
    id: z.string(),
    occurredAt: z.string(),
    kind: z.literal('SEARCH'),
    originator: z.string(),
    content: z.string(),
    attributes: searchAttributesSchema,
  })
  .loose();

const knownThreadEventSchema = z.union([genericKnownThreadEventSchema, searchThreadEventSchema]);

/** The minimal structural shape ANY event (known or not) must have to be worth degrading, rather than
 * dropping outright: a stable id + an authoritative order key. */
const baseEventShapeSchema = z
  .object({
    id: z.string(),
    occurredAt: z.string(),
  })
  .loose();

const KNOWN_EVENT_KIND_SET: ReadonlySet<string> = new Set(KNOWN_EVENT_KINDS);

/**
 * Tempdoc S4a (risk-review finding #1) — parse ONE wire event, forward-tolerantly:
 *
 *  - A known-kind event parses under the exact pre-S4a strict schema (unchanged rigor).
 *  - An event whose `kind` is a STRING but not one of the known kinds degrades to a generic
 *    `UNKNOWN` event (the raw kind preserved as `rawKind`) as long as the base structural fields
 *    (id/occurredAt) parse — originator/content/attributes are loose passthrough, defaulted when
 *    absent/malformed, since a not-yet-shipped backend kind's shape isn't yet known to this build.
 *  - Anything else (missing id/occurredAt, or a KNOWN kind that fails its own strict schema) is
 *    structurally invalid and returns `null` — the caller drops it and warns, but the array survives.
 *
 * Returns `null` for a dropped event; never throws.
 */
export function parseThreadEvent(raw: unknown): ThreadEvent | null {
  const known = knownThreadEventSchema.safeParse(raw);
  if (known.success) return known.data;

  const base = baseEventShapeSchema.safeParse(raw);
  if (!base.success) return null; // missing required base fields — structurally invalid, drop it

  const rawObj = raw as Record<string, unknown>;
  const kind = typeof rawObj.kind === 'string' ? rawObj.kind : undefined;
  if (kind === undefined || KNOWN_EVENT_KIND_SET.has(kind)) {
    // Either no kind at all, or a KNOWN kind that still failed the strict schema above (e.g. a missing
    // required field) — preserve known-kind strictness rather than silently degrading it to UNKNOWN.
    return null;
  }

  return {
    id: base.data.id,
    occurredAt: base.data.occurredAt,
    kind: 'UNKNOWN',
    rawKind: kind,
    originator: typeof rawObj.originator === 'string' ? rawObj.originator : '',
    content: typeof rawObj.content === 'string' ? rawObj.content : '',
    attributes:
      rawObj.attributes !== null &&
      typeof rawObj.attributes === 'object' &&
      !Array.isArray(rawObj.attributes)
        ? (rawObj.attributes as Record<string, unknown>)
        : {},
  };
}

const lifecycleSchema = z
  .object({
    sessionId: z.string(),
    state: z.string(),
    actor: z.string(),
    turns: z.number(),
    iterations: z.number(),
    toolCalls: z.number(),
    actors: z.array(z.string()).default([]),
    budget: z
      .object({
        initial: z.number(),
        consumed: z.number(),
        remaining: z.number(),
        overBudget: z.boolean(),
      })
      .loose(),
  })
  .loose();

/**
 * Tempdoc S4a — the top-level envelope: `events` is validated only as AN ARRAY here (each element is
 * validated individually by {@link parseThreadEvent} below), so one malformed/unrecognized-kind event
 * can no longer sink the whole response the way an array-wide `z.array(threadEventSchema)` schema did
 * (a single non-enum `kind` used to fail the ENTIRE parse — the risk-review finding this closes).
 */
const threadResponseEnvelopeSchema = z
  .object({
    conversationId: z.string(),
    events: z.array(z.unknown()),
    lifecycles: z.array(lifecycleSchema).optional(),
  })
  .loose();

const EMPTY: ThreadResponse = { events: [], lifecycles: [] };

/** Why {@link fetchUnifiedThread} fell back to {@link EMPTY} — passed to the optional `onFailure`
 *  signal (tempdoc 727 F-8) so a caller CAN surface the failure without the return contract changing. */
export type ThreadFetchFailureReason = 'http-error' | 'malformed-envelope' | 'network';

/**
 * Fetch a conversation's canonical thread (events + the agent runs' typed lifecycles). Returns empty
 * on any failure (offline, non-200, malformed envelope) so the caller falls back to its live state.
 *
 * <p>Tempdoc S4a — event parsing is now PER-EVENT, not all-or-nothing: a structurally invalid event
 * (or a known kind that fails its own strict schema) is dropped with a single console.warn naming the
 * count; an event with an unrecognized `kind` string degrades to a generic `UNKNOWN` item rather than
 * blanking the whole thread (see {@link parseThreadEvent}).
 *
 * <p>Tempdoc 727 F-8 — the EMPTY-on-failure contract is deliberate (the projector must never become an
 * authority; a failed refresh must not wipe the caller's live render — pinned by
 * `unifiedThreadClient.test.ts`'s "returns EMPTY on a non-ok response" case). What was missing was any
 * signal that the fallback happened at all, so a caller silently rendered an empty thread with no hint
 * anything was wrong. `onFailure` is an OPTIONAL out-of-band channel, alongside the unchanged EMPTY
 * return — existing callers that omit it see no behavior change.
 */
export async function fetchUnifiedThread(
  apiBase: string,
  conversationId: string,
  signal?: AbortSignal,
  onFailure?: (reason: ThreadFetchFailureReason, detail?: string) => void,
): Promise<ThreadResponse> {
  try {
    const res = await authorizedFetch(`${apiBase}/api/thread/${encodeURIComponent(conversationId)}`, {
      signal,
    });
    if (!res.ok) {
      onFailure?.('http-error', String(res.status));
      return EMPTY;
    }
    const parsed = threadResponseEnvelopeSchema.safeParse(await res.json());
    if (!parsed.success) {
      onFailure?.('malformed-envelope');
      return EMPTY;
    }

    const events: ThreadEvent[] = [];
    let droppedCount = 0;
    for (const rawEvent of parsed.data.events) {
      const event = parseThreadEvent(rawEvent);
      if (event === null) {
        droppedCount++;
        continue;
      }
      events.push(event);
    }
    if (droppedCount > 0) {
      console.warn(
        `fetchUnifiedThread: dropped ${droppedCount} structurally invalid thread event(s) for conversation ${conversationId}`,
      );
    }

    if (events.length > 0 || (parsed.data.lifecycles?.length ?? 0) > 0) rememberServerConversation(conversationId);
    return {
      events,
      lifecycles: (parsed.data.lifecycles ?? []) as ThreadLifecycle[],
      ...(droppedCount > 0 || parsed.data.conversationId !== conversationId ? { incomplete: true } : {}),
    };
  } catch (e) {
    // AbortError is a deliberate cancellation (Slice 516 FIX-T1: a new loadConversation aborts the
    // in-flight fetch), not a failure worth surfacing to the user.
    if (!(e instanceof DOMException && e.name === 'AbortError')) {
      onFailure?.('network', e instanceof Error ? e.message : String(e));
    }
    return EMPTY;
  }
}
