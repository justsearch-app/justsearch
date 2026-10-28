// SPDX-License-Identifier: Apache-2.0
/** Shared per-shape request body and explicit human demand marker. */
import type { SelectionPayload } from "../../api/types/selection.js";
import type { CoreInteractionShapeId } from "../plugin-api/coreInteractionShapes.js";

export type ShapeId = CoreInteractionShapeId;

/** Must match ConversationStore.EMPTY_PREFIX_SENTINEL. */
export const EMPTY_PREFIX_SENTINEL = "__empty_prefix__";

export function buildRequestBody(
  shapeId: ShapeId | 'core.summarize',
  text: string,
  sessionId: string,
  schemaDraft: string,
  docIds: string[],
  selection?: SelectionPayload | null,
): Record<string, unknown> {
  const body: Record<string, unknown> = { shapeId };
  switch (shapeId) {
    case 'core.rag-ask':
      body.question = text;
      // Slice 515 FIX-1: forward docIds from unifiedChatState so scoped
      // retrieval (askAi from BrowseSurface/SearchSurface) actually filters
      // to the referenced docs instead of silently degrading to open-retrieval.
      // Empty array preserves the open-retrieval fallback in RAGAskShape.
      body.docIds = docIds;
      break;
    case 'core.summarize':
      body.prompt = text;
      if (!selection && docIds.length > 0) {
        body.selection = { kind: 'result-set', items: [...new Set(docIds)].map(id => ({ id, kind: 'doc' })) };
      }
      break;
    case 'core.extract':
      body.prompt = text;
      body.schema = schemaDraft;
      break;
    case 'core.agent-run':
    case 'core.free-chat':
    default:
      body.prompt = text;
      body.sessionId = sessionId;
      break;
  }
  // Tempdoc 526 §12.4 — forward the typed selection as body.selection so the backend
  // SelectionContextInjector receives it verbatim. Applies to every shape today; shapes that
  // don't read body.selection simply ignore the field. A shape opts in by listing
  // `SelectionContextInjector.ID` in its `contextInjectorIds` — SummarizeShape and ExtractShape
  // do. (There is no `selectionPolicy` concept anywhere in the Java code; the comment that
  // claimed one described an opt-in mechanism that never existed.)
  if (selection) {
    body.selection = selection;
  }
  return body;
}

/**
 * Spec A step 9 (reconciliation F8/CH1) — marks a dispatch body as a person's chat: `allowLoad: true`
 * lets the Engine load an unloaded chat model for this turn (or offer "load anyway" under pressure)
 * instead of refusing it as an agent's request. Only the chat window's own send path calls this,
 * for `/api/chat/dispatch`; the Engine also requires the app's own client before it believes it.
 */
export function withHumanLoad(body: Record<string, unknown>): Record<string, unknown> {
  body.allowLoad = true;
  return body;
}
