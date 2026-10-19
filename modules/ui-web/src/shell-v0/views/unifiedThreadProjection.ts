// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 561 P-A/P-B (Slice 2) — the unified thread as a PURE projection of the canonical
 * interaction record (GET /api/thread/{id}).
 *
 * <p>This is a stateless function of the record's event list: it owns NO state of its own, so it can
 * never become a second authority that drifts from the backend record (the projection-not-fork
 * discipline, 561/553). The render contract is {@link UnifiedTurnItem}; when a backend LifecycleEvent
 * later subsumes the record, only this function's INPUT rebinds — the render stays unchanged.
 *
 * <p>Ordering is by the record's authoritative {@code occurredAt} (never an FE-invented clock).
 * Tool-call lifecycle events are collapsed by {@code callId} to their latest status (the record
 * stores pending/completed/rejected as separate rows; the thread shows one card per call).
 */

/**
 * One wire row from {@code GET /api/thread/{id}} (the backend serialization).
 *
 * <p>Tempdoc S4a (risk-review finding #1) — the `kind` union carries an extra `'UNKNOWN'` member
 * alongside the backend-declared kinds, so a forward-deployed backend event this build doesn't
 * recognize yet still type-checks as a plain `ThreadEvent` everywhere one is already pinned to a fixed
 * shape (`UnifiedChatView.unifiedEvents: ThreadEvent[]`) — extending the UNION, not forking a parallel
 * array-element type, is the least-invasive shape once that consumer is read. `rawKind` is set ONLY for
 * the `'UNKNOWN'` member (see {@link UnknownThreadEvent}); it carries the backend's original,
 * unrecognized kind string so the projection can still label the item meaningfully. `'SEARCH'` (tempdoc
 * S4b) is now a real, backend-declared kind — a manually-triggered search action persisted outside the
 * agent loop — carried as the existing `'progress'` {@link UnifiedTurnKind} (not a new render kind) so
 * `UnifiedChatView.renderUnifiedItem`'s exhaustive switch needs no change; see the `KIND_MAP` comment.
 */
export interface ThreadEvent {
  readonly id: string;
  /** ISO-8601 authoritative order key. */
  readonly occurredAt: string;
  readonly kind:
    | 'USER_MESSAGE'
    | 'ASSISTANT_MESSAGE'
    | 'TOOL_ACTIVITY'
    | 'PROGRESS'
    | 'ERROR'
    | 'HANDOFF'
    | 'SEARCH'
    | 'UNKNOWN';
  readonly originator: string;
  readonly content: string;
  readonly attributes: Readonly<Record<string, unknown>>;
  /** Set only when {@link kind} is `'UNKNOWN'` — the backend's original, unrecognized kind string. */
  readonly rawKind?: string;
}

/**
 * A {@link ThreadEvent} narrowed to the forward-tolerant `'UNKNOWN'` member (kind + rawKind both
 * required). A type ALIAS over the same `ThreadEvent` shape, not a separate interface, so every
 * existing `ThreadEvent`/`ThreadEvent[]` consumer accepts it without change (see the interface doc above).
 */
export type UnknownThreadEvent = ThreadEvent & { readonly kind: 'UNKNOWN'; readonly rawKind: string };

export type UnifiedTurnKind =
  | 'user'
  | 'assistant'
  | 'tool-activity'
  | 'progress'
  | 'error'
  | 'handoff';

/**
 * Tempdoc 565 §12.3.C — the composition/prominence facet (the facet 559 named but never projected).
 * Each timeline item DECLARES how prominently it composes, so the surface renders answer-first: the
 * answer/turn lead (`primary`); the tool steps recede to a dense audit row (`secondary`); the progress
 * chatter is faint (`ambient`). Declared here (read by the renderer), not hand-authored padding.
 */
export type TurnProminence = 'primary' | 'secondary' | 'ambient';

const PROMINENCE_MAP: Readonly<Record<UnifiedTurnKind, TurnProminence>> = {
  user: 'primary',
  assistant: 'primary',
  'tool-activity': 'secondary',
  error: 'secondary',
  progress: 'ambient',
  handoff: 'ambient',
};

/** The declared prominence of a timeline item (565 §12.3.C). */
export function prominenceFor(kind: UnifiedTurnKind): TurnProminence {
  return PROMINENCE_MAP[kind];
}

/**
 * Tempdoc 565 §19.3 — the prominence grading as a DECLARED scale (projected, not hand-CSS'd). Before
 * §19 the spine hand-authored the size + opacity per `.prominence-*` CSS class (the "presentation
 * hand-authored, not projected" disease — why the ambient nodes read too faint). The visual weight of a
 * prominence now lives ONCE here, so the spine / any future grading surface projects it instead of
 * re-deriving the literals. Composes with the §19.2 density ladder: a node's box is the declared
 * prominence size; its own `DensityController` then picks the legible representation for that size.
 */
export interface ProminenceWeight {
  /** The node's box edge (rem). */
  readonly sizeRem: number;
  /** The node's opacity (the answer-first recede: primary bright → ambient faint). */
  readonly opacity: number;
}

export const PROMINENCE_SCALE: Readonly<Record<TurnProminence, ProminenceWeight>> = {
  primary: { sizeRem: 0.62, opacity: 1 },
  secondary: { sizeRem: 0.36, opacity: 0.65 },
  // §19.I-R4 — ambient opacity raised 0.45 → 0.55 after a LIVE contrast measurement: at 0.45 the neutral
  // ambient dot sat right ON the WCAG 1.4.11 non-text bar (~2.86–3.05:1 vs the dark surface, depending on
  // the exact composite) — borderline, and §19.3 flagged it as reading too faint. 0.55 lifts it to a
  // comfortable ~4:1 while staying below `secondary` (0.65) so the prominence grading order holds.
  ambient: { sizeRem: 0.3, opacity: 0.55 },
};

/**
 * The terminal landmark (the answer node) — biggest + full-strength, the run's destination. A declared
 * peak above `primary`, not a fourth prominence (it is an `assistant` turn, prominence `primary`, that
 * the spine renders as the terminal map-pin). 565 §13/§19.3.
 */
export const TERMINAL_NODE_WEIGHT: ProminenceWeight = { sizeRem: 0.8, opacity: 1 };

/**
 * Tempdoc 565 §26.A — the RUN-STRUCTURE authority: which group/origin a timeline item belongs to. The
 * flat {@link UnifiedTurnItem} stream carries this as ONE typed facet (not the untyped `attributes`
 * escape hatch §15.C's flatten dropped it into), computed by the one {@link assignRunSegments} pass and
 * read by BOTH the body (segment → collapsible group) and the spine (segment start → boundary landmark).
 * A workflow run is the >1-segment case (one per node); an agent run is the degenerate one-segment case.
 */
export type RunSegmentRef = {
  /** Where this item's activity came from. `workflow` = inside a workflow node; `background` = a presence run. */
  readonly originKind: 'interactive' | 'background' | 'workflow';
  /** The workflow node id (originKind `workflow`); absent for interactive/background. */
  readonly nodeId?: string;
  /** The workflow node kind (`llm` | `tool` | `gate`). */
  readonly nodeKind?: string;
  /** A human label for the segment header (the node label, or a background-run label). */
  readonly label?: string;
  // Tempdoc 565 §26.B / §15.D.1 — BRANDED: a RunSegmentRef is constructible ONLY by `assignRunSegments`
  // (the one authority), so a second renderer cannot fabricate a segment to stamp items with — the
  // segmentation leaf is unforkable by construction (the typed seam preferred over the grep gate).
} & { readonly __runSegment: unique symbol };

/** One rendered item in the unified thread. */
export interface UnifiedTurnItem {
  readonly id: string;
  /** Epoch milliseconds parsed from {@link ThreadEvent.occurredAt} — the sort key. */
  readonly ts: number;
  readonly kind: UnifiedTurnKind;
  /** Tempdoc 565 §12.3.C — the declared composition prominence (answer-first hierarchy). */
  readonly prominence: TurnProminence;
  readonly originator: string;
  readonly content: string;
  readonly attributes: Readonly<Record<string, unknown>>;
  /** Tempdoc 565 §26.A — the run-structure segment this item belongs to (undefined = ungrouped). */
  readonly segment?: RunSegmentRef;
}

/**
 * Tempdoc 565 §26.A/§26.B — the ONE run-segmentation pass. Both projectors call this on their ordered
 * {@link UnifiedTurnItem}[]: it consumes the node-boundary markers (a `progress` item carrying
 * `attributes.nodeBoundary` — emitted record-side by `AgentInteractionMapper` and live-side by the
 * controller's `run-node` entry), stamps every item BETWEEN a start/end with that node's
 * {@link RunSegmentRef}, and DROPS the boundary markers (the segment header renders from the ref, not a
 * progress row). Pure: same input, same output. Items outside any node keep their existing `segment`
 * (undefined unless a caller pre-stamped a run-level origin — §26.D background provenance).
 */
export function assignRunSegments(items: ReadonlyArray<UnifiedTurnItem>): UnifiedTurnItem[] {
  const out: UnifiedTurnItem[] = [];
  // §26.C — a STACK of open segments (not a single ref) so nested/overlapping brackets are correct by
  // construction (a `start` before its `end` no longer overwrites the outer segment). Today the backend
  // never overlaps (sequential workflow nodes + a single-thread background scheduler), but the authority
  // is robust regardless. An item gets the INNERMOST open segment (the top of the stack).
  const stack: RunSegmentRef[] = [];
  for (const it of items) {
    const boundary = it.kind === 'progress' ? it.attributes.nodeBoundary : undefined;
    if (boundary === 'start') {
      // §26.A — the origin defaults to `workflow` (a node boundary); a background-run wrapper
      // (§26.D) sets originKind `background` so the same machinery brackets the whole run.
      const origin = it.attributes.originKind;
      // §26.B/§15.D.1 — the BRANDED RunSegmentRef is minted ONLY here (the one authority). The `as`
      // cast is the sole construction site; nothing else can fabricate a segment.
      const seg = {
        originKind: origin === 'background' || origin === 'interactive' ? origin : 'workflow',
        nodeId: typeof it.attributes.nodeId === 'string' ? it.attributes.nodeId : undefined,
        nodeKind: typeof it.attributes.nodeKind === 'string' ? it.attributes.nodeKind : undefined,
        label: typeof it.attributes.label === 'string' ? it.attributes.label : undefined,
      } as RunSegmentRef;
      stack.push(seg);
      continue; // the boundary marker is consumed, not rendered
    }
    if (boundary === 'end') {
      stack.pop(); // a stray `end` (empty stack) is a harmless no-op
      continue;
    }
    const top = stack.length > 0 ? stack[stack.length - 1] : undefined;
    out.push(top ? { ...it, segment: top } : it);
  }
  return out;
}

/**
 * The id of the LAST `assistant` item in each turn — a `user → … → (next user | end)` segment. The
 * agent loop emits MULTIPLE assistant messages per turn (intermediate tool-call preambles + the final
 * answer); only the final one is the turn's terminal "Answer" landmark. The spine gives only these the
 * terminal peak weight + the "Answer" label; earlier intermediate assistants recede to texture and are
 * relabelled (they are NOT answers). 565 §13/§19.3. Pure — same input, same output.
 */
export function terminalAssistantIds(items: ReadonlyArray<UnifiedTurnItem>): Set<string> {
  const terminal = new Set<string>();
  let lastAssistantId: string | null = null;
  for (const it of items) {
    if (it.kind === 'user') {
      if (lastAssistantId !== null) terminal.add(lastAssistantId);
      lastAssistantId = null;
    } else if (it.kind === 'assistant') {
      lastAssistantId = it.id;
    }
  }
  if (lastAssistantId !== null) terminal.add(lastAssistantId);
  return terminal;
}

const KIND_MAP: Readonly<Record<ThreadEvent['kind'], UnifiedTurnKind>> = {
  USER_MESSAGE: 'user',
  ASSISTANT_MESSAGE: 'assistant',
  TOOL_ACTIVITY: 'tool-activity',
  PROGRESS: 'progress',
  ERROR: 'error',
  HANDOFF: 'handoff',
  // Tempdoc S4a — carried as the existing `progress` UnifiedTurnKind (not a new kind literal) so
  // UnifiedChatView.renderUnifiedItem's exhaustive `switch (it.kind)` (no `default`) stays exhaustive
  // and needs no change here; `projectUnifiedThread` below overrides this item's prominence + content
  // so it doesn't just read as ordinary ambient progress chatter.
  UNKNOWN: 'progress',
  // Tempdoc S4b (Search Thread) — same reasoning as UNKNOWN above: SEARCH rides the existing `progress`
  // UnifiedTurnKind (not a new render kind, so renderUnifiedItem's exhaustive switch stays untouched).
  // `projectUnifiedThread` below stamps `attributes.searchEvent = true` (plus the typed attributes
  // passed through verbatim) so the view's owner can later render the real search card from the marker
  // instead of ordinary ambient progress chatter.
  SEARCH: 'progress',
};

/**
 * Tempdoc S4a — humanize a raw unrecognized-kind wire string for the generic UNKNOWN item's label
 * (`'SEARCH'` → `'Search'`, `'SEARCH_RESULT'` → `'Search result'`).
 */
function humanizeRawKind(rawKind: string): string {
  const words = rawKind
    .toLowerCase()
    .split('_')
    .filter((w) => w.length > 0);
  if (words.length === 0) return rawKind;
  return [words[0]!.charAt(0).toUpperCase() + words[0]!.slice(1), ...words.slice(1)].join(' ');
}

/**
 * Project the record's events into the rendered unified thread: map kinds, collapse each tool call
 * to its latest status, and order by the authoritative timestamp. Pure — same input, same output.
 */
export function projectUnifiedThread(events: ReadonlyArray<ThreadEvent>): UnifiedTurnItem[] {
  // Tempdoc 565 §12.3.B — MERGE tool-activity by callId (one row per call). A call emits several
  // lifecycle events — proposed (toolName + arguments + risk, the identity), pending (gateBehavior),
  // completed (output + structuredData, the outcome), rejected (reason). Keeping only the latest drops
  // the identity (completed/rejected carry no toolName/args), so the record row reads "tool · status".
  // Instead union all events' attributes (record order is chronological, so later events win on
  // conflicts like `status`) and keep the latest event's id/occurredAt/content — mirroring how the
  // live side accumulates `agentCtrl.toolCalls[callId]`.
  const mergedToolByCall = new Map<string, ThreadEvent>();
  const passthrough: ThreadEvent[] = [];
  for (const e of events) {
    if (e.kind === 'TOOL_ACTIVITY') {
      const callId = typeof e.attributes.callId === 'string' ? e.attributes.callId : e.id;
      const prior = mergedToolByCall.get(callId);
      if (!prior) {
        mergedToolByCall.set(callId, { ...e, attributes: { ...e.attributes } });
      } else {
        const base = toMillis(e.occurredAt) >= toMillis(prior.occurredAt) ? e : prior;
        // Tempdoc 859 §A §1.3 — `reasoning` is UNIONED, not overwritten. The attribute merge is
        // later-wins, which is right for `status` and wrong for this: the record fold can attach a
        // block to two different lifecycle events of the SAME callId, and later-wins would silently
        // drop the earlier one.
        //
        // LOAD-BEARING, not defensive, and reachable through exactly one shape (859 §A F4): a run cut
        // off mid-thought. Reasoning never falls BETWEEN a call's lifecycle events on a healthy run —
        // it is emitted inside the LLM stream and the tool calls parsed out of that stream are
        // dispatched only after it closes — but the fold's trailing rule attaches a still-open region
        // to the run's LAST event, which can be a later lifecycle event of the call an earlier block
        // already rode. Both blocks then land on this one row. The known cost is ordering: the
        // trailing block draws above the row rather than after it, which is the one place the live
        // and record timelines disagree; it is pinned in `sv3-timeline-parity.test.ts`.
        const reasoning = [
          ...(Array.isArray(prior.attributes.reasoning) ? prior.attributes.reasoning : []),
          ...(Array.isArray(e.attributes.reasoning) ? e.attributes.reasoning : []),
        ];
        mergedToolByCall.set(callId, {
          ...base,
          attributes: {
            ...prior.attributes,
            ...e.attributes,
            ...(reasoning.length > 0 ? { reasoning } : {}),
          },
        });
      }
    } else {
      passthrough.push(e);
    }
  }

  const items: UnifiedTurnItem[] = [...passthrough, ...mergedToolByCall.values()].map((e) => {
    const kind = KIND_MAP[e.kind];
    if (e.kind === 'UNKNOWN') {
      // Tempdoc S4a — a forward-deployed backend event kind this build doesn't recognize yet degrades
      // to a generic SECONDARY item labelled from its raw kind, instead of the whole thread failing to
      // parse (the risk-review finding this closes). `secondary` is an explicit override — `progress`
      // otherwise defaults to `ambient` (PROMINENCE_MAP) — so it reads as a distinct generic item, not
      // ordinary progress chatter.
      const rawKind = e.rawKind ?? 'UNKNOWN';
      return {
        id: e.id,
        ts: toMillis(e.occurredAt),
        kind,
        prominence: 'secondary',
        originator: e.originator,
        content: humanizeRawKind(rawKind),
        attributes: { ...e.attributes, rawKind },
      };
    }
    if (e.kind === 'SEARCH') {
      // Tempdoc S4b (Search Thread) — a committed search card. Rides the existing `progress`
      // UnifiedTurnKind (see the KIND_MAP comment) but is marked with a dedicated `searchEvent` flag
      // (distinct from the UNKNOWN degrade's `rawKind` marker) plus the typed attributes passed through
      // verbatim, so the view's owner can render the real search card from this item later without a
      // new UnifiedTurnKind / a UnifiedChatView.renderUnifiedItem change.
      return {
        id: e.id,
        ts: toMillis(e.occurredAt),
        kind,
        prominence: 'secondary',
        originator: e.originator,
        content: e.content,
        attributes: { ...e.attributes, searchEvent: true },
      };
    }
    return {
      id: e.id,
      ts: toMillis(e.occurredAt),
      kind,
      prominence: prominenceFor(kind),
      originator: e.originator,
      content: e.content,
      attributes: e.attributes,
    };
  });

  // Authoritative-timestamp order; stable id tiebreaker so equal timestamps are deterministic.
  items.sort((a, b) => a.ts - b.ts || a.id.localeCompare(b.id));
  // Tempdoc 565 §26.A — stamp run-structure segments (workflow node grouping) over the ordered stream.
  return assignRunSegments(items);
}

function toMillis(iso: string): number {
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? 0 : ms;
}

/**
 * Tempdoc 565 §12 Phase 2 — the structural input the LIVE projection consumes: one entry of the
 * agent controller's `conversation`. Declared here (not imported) so this module stays a pure,
 * dependency-free projection (it owns no state and depends on no controller). The controller's
 * `ConversationEntry` is structurally assignable to this.
 */
export interface LiveConversationEntry {
  readonly id: string;
  readonly type:
    | 'user'
    | 'assistant-text'
    | 'tool-call-group'
    | 'error'
    | 'progress'
    | 'handoff'
    | 'run-node'
    // Tempdoc 565 §30 — a human STEERING directive (the DIRECTION authority's `interject`), pushed by
    // the controller on a `directive_acknowledged` event. A POINT human-origin run event (not a span,
    // so NOT a RunSegmentRef origin): it projects to a `progress`/`originator:'user'` item the spine
    // shows as a human landmark and the body renders as a "Your direction: …" chip.
    | 'steer-directive'
    // Tempdoc 859 §A §1.1 — a closed reasoning region. Declared here because `ConversationEntry` must
    // stay structurally assignable to this type (see the doc above) and the switch below has no
    // exhaustiveness guard, so omitting it would break `typecheck` at every call site rather than
    // failing legibly here — and the deliberate skip below would read as an oversight.
    | 'reasoning';
  readonly content: string;
  readonly callIds?: readonly string[];
  readonly errorCode?: string;
  readonly severity?: string;
  /** Tempdoc 577 Ext II: typed progress phase token (see ConversationEntry.phase). */
  readonly phase?: string;
  /** WP6.2 — a `steer-directive` entry's outcome and reason (see ConversationEntry). */
  readonly directiveOutcome?: string;
  readonly directiveReason?: string;
  readonly timestamp: number;
  // Tempdoc 565 §26.B — a `run-node` entry is a workflow node boundary the controller pushes on
  // node_started/node_completed; the projector emits it as a boundary `progress` item that
  // {@link assignRunSegments} consumes (and drops) to bracket the node's items into a segment.
  readonly nodeBoundary?: 'start' | 'end';
  readonly nodeId?: string;
  readonly nodeKind?: string;
  readonly nodeLabel?: string;
  /** Tempdoc 859 §A — a `reasoning` entry's measured duration (see `ConversationEntry.durationMs`). */
  readonly durationMs?: number;
  // Tempdoc 585 §D Phase 2 (D2) — multi-agent handoff identity, projected into the handoff item's
  // attributes so <jf-handoff-card> renders the structured "from → to" row (was flat text).
  readonly fromAgentId?: string;
  readonly toAgentId?: string;
  readonly reason?: string;
}

/** The structural input for one live tool call (the controller's `ToolCall`, render-relevant fields). */
export interface LiveToolCall {
  readonly callId: string;
  readonly toolName: string;
  readonly arguments?: string;
  readonly risk: string;
  readonly status: string;
  readonly output?: string;
  readonly success?: boolean;
  readonly rejectReason?: string;
  readonly structuredData?: Record<string, unknown>;
  readonly gateBehavior?: string;
}

/** The latest answer's grounding (sources + per-sentence cites), rides on the live assistant item. */
export interface LiveAnswerGrounding {
  readonly sources?: readonly unknown[];
  readonly citations?: readonly unknown[];
}

/** WP6.2 — why a direction was not applied, as the reader is told (the backend's closed reasons). */
const DIRECTION_NOT_APPLIED_BECAUSE: Readonly<Record<string, string>> = {
  run_finished: "the run finished before the agent's next step",
  run_stopped: "the run was stopped before the agent's next step",
  run_failed: "the run failed before the agent's next step",
  step_limit: "the run reached its step limit before the agent's next step",
  budget_limit: "the run reached its token budget before the agent's next step",
  run_interrupted: 'the run was interrupted when JustSearch restarted',
};

export const DIRECTION_QUEUED_NOTE = "applies at the agent's next step";

export function directionNotAppliedBecause(reason: unknown): string {
  return (
    (typeof reason === 'string' ? DIRECTION_NOT_APPLIED_BECAUSE[reason] : undefined) ??
    "the run ended before the agent's next step"
  );
}

/** WP6.2 — a direction as a labelled note, live or reloaded: "Steered" only once it reached the model. */
export function directionNote(text: string, outcome: unknown, reason: unknown): { label: string; text: string } {
  if (outcome === 'queued') return { label: 'Direction queued', text: `${text} (${DIRECTION_QUEUED_NOTE})` };
  if (outcome === 'not_applied') {
    return { label: 'Not applied', text: `${text} (${directionNotAppliedBecause(reason)})` };
  }
  return { label: 'Steered', text };
}

/**
 * Tempdoc 565 §12 Phase 2 — project the LIVE agent run into the SAME {@link UnifiedTurnItem} contract
 * the record projection emits, so one ordered timeline renders through one render function (killing the
 * live-vs-record render fork: previously `renderUnifiedThread` + a parallel `renderLiveAgentActivity`).
 * Pure: a function of the controller's conversation + toolCalls (+ the latest answer grounding).
 *
 * <p>A `tool-call-group` entry (one per agent turn, holding several callIds) EXPANDS to one
 * `tool-activity` item per call — mirroring the record, where each call is its own row — pulling the
 * per-call {@link LiveToolCall} into the row's attributes (the inverse of how the unified render
 * reconstructs a `ToolCall`). The LATEST answer's grounding rides on the last assistant item's
 * attributes (fix B) so the unified render resolves the same inline [n] marks the record path does.
 */
export function projectLiveAgentActivity(
  conversation: ReadonlyArray<LiveConversationEntry>,
  toolCalls: Readonly<Record<string, LiveToolCall>>,
  grounding: LiveAnswerGrounding = {},
): UnifiedTurnItem[] {
  // Fix B — the grounding (ctrl.answerSources) describes only the LATEST answer, so attach it to the
  // LAST assistant-text alone; earlier turns' answers in a multi-turn session would otherwise show the
  // current answer's [n] marks. Earlier live answers render mark-less and gain their own marks from the
  // record (which persists per-answer sources) on reconcile.
  let lastAssistantId: string | null = null;
  for (const e of conversation) if (e.type === 'assistant-text') lastAssistantId = e.id;

  const items: Array<Omit<UnifiedTurnItem, 'prominence'>> = [];
  for (const e of conversation) {
    const originator = e.type === 'user' ? 'user' : 'agent';
    switch (e.type) {
      case 'user':
        items.push({ id: e.id, ts: e.timestamp, kind: 'user', originator, content: e.content, attributes: {} });
        break;
      case 'assistant-text':
        items.push({
          id: e.id,
          ts: e.timestamp,
          kind: 'assistant',
          originator,
          content: e.content,
          // Only the latest answer carries the grounding (fix B); the unified render resolves the same
          // [n] marks the record path does (565 §3.A).
          attributes:
            e.id === lastAssistantId
              ? { sources: grounding.sources ?? [], citations: grounding.citations ?? [] }
              : {},
        });
        break;
      case 'tool-call-group': {
        const ids = e.callIds ?? [];
        ids.forEach((cid, i) => {
          const tc = toolCalls[cid];
          if (!tc) return;
          items.push({
            id: `${e.id}:${cid}`,
            // Stable intra-group order: the group shares one timestamp, so offset by index.
            ts: e.timestamp + i,
            kind: 'tool-activity',
            originator: 'agent',
            content: '',
            // Inverse of UnifiedChatView.renderToolActivity's reconstruction (carry every render field).
            attributes: {
              callId: tc.callId,
              toolName: tc.toolName,
              arguments: tc.arguments ?? '',
              risk: tc.risk,
              status: tc.status,
              output: tc.output,
              success: tc.success,
              reason: tc.rejectReason,
              structuredData: tc.structuredData,
              gateBehavior: tc.gateBehavior,
            },
          });
        });
        break;
      }
      case 'error':
        items.push({
          id: e.id, ts: e.timestamp, kind: 'error', originator, content: e.content,
          attributes: { errorCode: e.errorCode },
        });
        break;
      case 'progress':
        items.push({
          id: e.id, ts: e.timestamp, kind: 'progress', originator, content: e.content,
          attributes: { severity: e.severity, phase: e.phase },
        });
        break;
      case 'handoff':
        // Tempdoc 585 §D Phase 2 (D2) — carry the handoff identity so <jf-handoff-card> renders the
        // structured from → to row (the ids already persist on the event; the projection used to drop
        // them, leaving only the pre-formatted `content` string).
        items.push({
          id: e.id, ts: e.timestamp, kind: 'handoff', originator, content: e.content,
          attributes: { fromAgentId: e.fromAgentId, toAgentId: e.toAgentId, reason: e.reason },
        });
        break;
      case 'steer-directive':
        // Tempdoc 565 §30 — a human steering directive: a POINT item, human-origin (originator
        // 'user'), flagged `steer` so the spine renders it as a human landmark and the body as a
        // "Your direction: …" chip. Not a boundary (no nodeBoundary) so assignRunSegments keeps it.
        // WP6.2 — with its outcome, under the same keys a reloaded direction carries.
        items.push({
          id: e.id, ts: e.timestamp, kind: 'progress', originator: 'user', content: e.content,
          attributes: {
            steer: true,
            ...(e.directiveOutcome ? { directiveOutcome: e.directiveOutcome } : {}),
            ...(e.directiveReason ? { directiveReason: e.directiveReason } : {}),
          },
        });
        break;
      case 'run-node':
        // Tempdoc 565 §26.B — emit a boundary `progress` item carrying the node identity; the shared
        // assignRunSegments pass consumes it to bracket the node's items, then drops it from the render.
        items.push({
          id: e.id,
          ts: e.timestamp,
          kind: 'progress',
          originator: 'agent',
          content: '',
          attributes: {
            nodeBoundary: e.nodeBoundary,
            nodeId: e.nodeId,
            nodeKind: e.nodeKind,
            label: e.nodeLabel,
          },
        });
        break;
      case 'reasoning':
        // Tempdoc 859 §A §1.9 — DELIBERATELY SKIPPED, and named as a limit rather than left implicit.
        // This window's LIVE reasoning render is controller-bound (`UnifiedChatView`'s streaming
        // arm), so emitting a second, item-shaped copy here would draw the same thinking twice while
        // a run streams. Widening the legacy window's live path is out of slice A's scope; its
        // RECORD path does carry these blocks (they ride `attributes.reasoning` on the projected
        // items, which `renderUnifiedItem` now reads above its kind switch).
        break;
    }
  }
  const withProminence: UnifiedTurnItem[] = items.map((it) => ({
    ...it,
    prominence: prominenceFor(it.kind),
  }));
  withProminence.sort((a, b) => a.ts - b.ts || a.id.localeCompare(b.id));
  // Tempdoc 565 §26.A — stamp run-structure segments (workflow node grouping) over the ordered stream.
  return assignRunSegments(withProminence);
}
