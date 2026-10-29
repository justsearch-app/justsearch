// SPDX-License-Identifier: Apache-2.0
/** Prepare Search through the shared navigation and selection transport; never send on arrival. */
import { getAiState } from '../state/aiStateStore.js';
export type Affordance = 'none' | 'retrieve' | 'documents' | 'extract' | 'agent';
import type { SelectionPayload } from '../../api/types/selection.js';

/**
 * Operations the FE compose() helper recognizes. Mirrors the Java
 * {@code ComposeIntent.operation} field; new operations require an entry in
 * {@link resolveShape}.
 */
/**
 * Operations the FE compose() helper recognizes. Tempdoc 526 §16 retraction:
 * narrowed from {summarize, ask, explain, compare} to {summarize, ask} because
 * the explain/compare shapes don't exist — those rows routed to {@code core.rag-ask}
 * under misleading labels. They land with their first concrete shape.
 */
export type ComposeOperation = 'core.summarize' | 'core.ask';

export interface ComposeArgs {
  readonly operation: ComposeOperation;
  /** Source/provenance tag (e.g., 'BUTTON', 'PALETTE'). */
  readonly source: string;
  /** Free-text user input (question, instruction). */
  readonly userPrompt?: string;
  /** Typed selection — when present, forwarded as body.selection on next dispatch. */
  readonly selection?: SelectionPayload;
  /** Doc context for migration-compatibility with the askAi shape (e.g., context-menu Summarize). */
  readonly docIds?: ReadonlyArray<string>;
  /** Display name for templated query (e.g., "Summarize X.pdf"). */
  readonly docName?: string;
  /**
   * Optional affordance hint. Defaults from operation + docIds. The resolved
   * shape (see {@link resolveShape}) is always preferred; affordance is
   * carried only so Search's UI bits that still read it (chip,
   * input placeholder) keep behaving as before.
   */
  readonly affordance?: Affordance;
  /** Optional explicit shape override; bypasses {@link resolveShape}. */
  readonly targetShape?: string;
}

/**
 * Pending-selection register — Search's next /api/chat/dispatch
 * picks this up and forwards it as body.selection. One-shot.
 */
let pendingSelection: SelectionPayload | null = null;

export function setPendingSelection(s: SelectionPayload | null): void {
  pendingSelection = s;
}

export function takePendingSelection(): SelectionPayload | null {
  const s = pendingSelection;
  pendingSelection = null;
  return s;
}

/**
 * Data-driven (operation, selection.kind, affordance?) → ShapeId table per
 * tempdoc 526 §4.5 property 1. Source of truth for shape routing on
 * compose() paths.
 *
 * Adding a new row requires no callsite change. The optional {@code
 * affordance} hint disambiguates `core.ask + none` between RAG-ask and
 * FreeChat (which one the user wants without a typed selection).
 */
export function resolveShape(
  operation: ComposeOperation,
  selectionKind: SelectionPayload['kind'] | 'none',
  // Tempdoc 561 P-B3: accepts the full Affordance. 'agent' is the action-plane MODE (not an
  // answer-plane shape), so it falls through to 'core.free-chat' here and is never used to resolve
  // a dispatch shape — the agent path hosts <jf-agent-view> instead of dispatching a shape.
  affordance?: Affordance,
): string {
  switch (operation) {
    case 'core.summarize':
      return 'core.summarize';
    case 'core.ask':
      // An EXPLICITLY chosen operation is never overridden by ambient selection state; an
      // unspecified one may be resolved by it. The affordance check used to sit inside the
      // `kind === 'none'` arm, so picking Extraction and then having anything selected
      // silently downgraded the send to `core.rag-ask` — dropping both the chosen mode and
      // the schema, while the mode chip still read "Extraction".
      if (affordance === 'extract') return 'core.extract';
      if (affordance === 'documents') return 'core.rag-ask';
      switch (selectionKind) {
        case 'none':
          return 'core.free-chat';
        case 'text-range':
        case 'item':
        case 'citation':
        case 'result-set':
        case 'health-condition':
        default:
          return 'core.rag-ask';
      }
  }
}

/** Mirror the legacy askAi return contract: false when AI is offline. */
export function compose(args: ComposeArgs): boolean {
  const ai = getAiState();
  if (!ai.capabilities.chat) {
    return false;
  }
  const docIds = args.docIds ? Array.from(args.docIds) : [];
  const affordance: Affordance =
    args.affordance ?? (docIds.length > 0 ? 'documents' : 'none');
  const query = resolveQuery(args);
  setPendingSelection(args.selection ?? (docIds.length > 0
    ? { kind: 'result-set', items: docIds.map(id => ({ id, kind: 'doc' as const })) }
    : null));
  const kind: SelectionPayload['kind'] | 'none' = args.selection?.kind ?? 'none';
  const forceShapeId =
    args.targetShape ?? resolveShape(args.operation, kind, affordance);
  const detail = {
    target: 'core.search-v3-surface',
    state: { query, affordance, ...(args.docIds !== undefined ? { docIds } : {}), forceShapeId },
  };
  const event = new CustomEvent('navigate-with-context', {
    bubbles: true,
    composed: true,
    detail,
  });
  const shell = document.querySelector('jf-shell');
  (shell ?? document).dispatchEvent(event);
  return true;
}

function resolveQuery(args: ComposeArgs): string {
  if (args.userPrompt && args.userPrompt.trim().length > 0) return args.userPrompt;
  if (args.operation === 'core.summarize') {
    if (args.docName) return `Summarize ${args.docName}`;
    if (args.selection?.kind === 'text-range') return 'Summarize the selection';
    if (args.selection?.kind === 'result-set') return 'Summarize these documents';
    if (args.selection?.kind === 'citation') return 'Summarize this citation';
    return 'Summarize this document';
  }
  return '';
}

/** Navigation delivers transient state before mounting; the receiving window owns it thereafter. */
let searchV3Handoff: { state: Record<string, unknown>; selection: SelectionPayload | null; origin: 'fresh' | 'restore'; refused?: boolean } | null = null;

export function parkSearchV3Handoff(state: Record<string, unknown>, origin: 'fresh' | 'restore' = 'fresh'): void {
  const selection = takePendingSelection();
  searchV3Handoff = { state, selection: typeof state.forceShapeId === 'string' ? selection : null, origin };
  window.dispatchEvent(new Event('sv3-handoff'));
}

export function takeSearchV3Handoff(): typeof searchV3Handoff {
  const value = searchV3Handoff;
  searchV3Handoff = null;
  // Navigation can retire a refusal without changing any store or remounting the view.
  if (value?.refused) window.dispatchEvent(new Event('sv3-handoff'));
  return value;
}

/** Keep fresh address inputs available until the receiving view has retained them safely. */
export function peekSearchV3Handoff(): typeof searchV3Handoff {
  return searchV3Handoff;
}
