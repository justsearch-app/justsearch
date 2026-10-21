// SPDX-License-Identifier: Apache-2.0
/**
 * searchResultActions — Tempdoc 577 Goal 1 Phase 8 (570 Move C, two-layer per §18.3 D2).
 *
 * The CORE verb-space of a search result, contributed through the one
 * `ContextActionRegistry` seam instead of a hard-coded menu array — so plugins,
 * the kernel `when`-evaluator, and every opener see the same action space.
 *
 * Two-layer cut (570 §18.3): these are SIMPLE, non-destructive actions riding
 * the existing platform capabilities (`openLocalFile` / `revealLocalPath`) and
 * shell seams (clipboard, inspector selection). Destructive or agent-tier
 * result actions would traverse the operation/`evaluateIntent`/Journal seam —
 * none exist yet; that layer is deliberately deferred (recorded in tempdoc 577).
 *
 * Registered once at shell boot (idempotent), context `'search-result-row'`
 * — the same context string `SearchSurface.handleRowContextMenu` passes, so
 * `openContextMenu` appends these automatically.
 */
import { registerContextAction } from './ContextActionRegistry.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { openLocalFile, revealLocalPath } from '../plugin-api/capabilities/platform.js';
import { copyToClipboard } from '../utils/clipboardCopy.js';

export const SEARCH_RESULT_CONTEXT = 'search-result-row';

interface HitLike {
  path?: string;
}

const hasPath = (payload: unknown): boolean =>
  typeof (payload as HitLike)?.path === 'string' && ((payload as HitLike).path ?? '') !== '';

let registered = false;

/** Idempotent boot-time registration of the core search-result verbs. */
export function registerSearchResultActions(): void {
  if (registered) return;
  registered = true;
  registerContextAction({
    id: 'search-result.open',
    context: SEARCH_RESULT_CONTEXT,
    label: 'Open file',
    icon: 'file-text',
    priority: 10,
    source: 'core',
    provenance: CORE_PROVENANCE,
    enabled: hasPath,
    handler: (payload) => openLocalFile((payload as HitLike).path ?? '').catch(() => { /* Already reported. */ }),
  });
  registerContextAction({
    id: 'search-result.reveal',
    context: SEARCH_RESULT_CONTEXT,
    label: 'Reveal in Explorer',
    icon: 'folder',
    priority: 20,
    source: 'core',
    provenance: CORE_PROVENANCE,
    enabled: hasPath,
    handler: (payload) => revealLocalPath((payload as HitLike).path ?? '').catch(() => { /* Already reported. */ }),
  });
  registerContextAction({
    id: 'search-result.copy-path',
    context: SEARCH_RESULT_CONTEXT,
    label: 'Copy path',
    icon: 'copy',
    priority: 30,
    source: 'core',
    provenance: CORE_PROVENANCE,
    enabled: hasPath,
    handler: (payload) => void copyToClipboard((payload as HitLike).path ?? ''),
  });
}

/** Test-only: allow re-registration after a registry reset. */
export function __resetSearchResultActionsForTest(): void {
  registered = false;
}
