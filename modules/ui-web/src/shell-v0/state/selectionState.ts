// SPDX-License-Identifier: Apache-2.0
/**
 * selectionState — Tempdoc 508 §11.2 / §13.2 — first-class
 * selection state with discriminated kinds and capability vocabulary.
 *
 * Replaces ad-hoc `payload: unknown` patterns that left every
 * downstream consumer guessing the shape. A SelectionItem carries
 * its `kind` discriminator and the set of capabilities it supports;
 * predicates (`when` expressions, context menu filters) and command
 * handlers read the typed shape directly.
 *
 * Design notes:
 *   - Kind union is demand-driven (§13.2): only the kinds we ship
 *     consumers for today (`search-hit`, `browse-node`,
 *     `plugin-item`). Adding a kind later is an additive union
 *     widening with no migration.
 *   - Capability set is the primary discriminator (§13.2 verdict):
 *     `'export' in selectionCapabilities` evaluates correctly
 *     regardless of kind. Predicates that depend on what an item
 *     can DO (rather than its underlying type) read capabilities.
 *   - Multi-select is structurally supported from day one
 *     (`items: ReadonlyArray<SelectionItem>`). Single-select today
 *     is `items.length === 1`. No retrofit cost when surfaces grow
 *     multi-select handlers.
 *   - Persistence: ephemeral (not in UserStateDocument). Cleared on
 *     surface change (working assumption per slice plan §C2).
 *
 * The ShellContext (§13.1) reads from this store and projects
 * selectionKind / selectionCount / selectionCapabilities for the
 * when-evaluator. inspectorState (single-item, pre-existing) is
 * kept as a derived view via a thin shim — callers that already
 * use `setSelected(SelectedItem)` continue to work.
 */

import type {
  DocumentAddress,
  HostEntity,
  ResultRef,
  SourceCitation,
} from '../../api/types/selection.js';
import { updateShellContext } from './shellContextState.js';

/** Capability vocabulary — what selected item(s) support. */
export type SelectionCapability =
  | 'open'
  | 'pin'
  | 'export'
  | 'ask-ai-about'
  | 'reveal-in-explorer'
  | 'copy-link';

/**
 * Discriminated selection item. Add a kind here when a new surface
 * starts selecting a new shape (§13.2 demand-driven discipline).
 *
 * Tempdoc 526 §12.4: the `text-range` variant carries the typed wire shape
 * (DocumentAddress + selectionText + hostEntity) that compose() forwards to
 * the backend as body.selection. The other variants remain FE-only state —
 * they have no v1 wire consumer per substrate-without-consumer-flavors.
 */
export type SelectionItem =
  | {
      readonly kind: 'search-hit';
      readonly hitId: string;
      readonly title: string;
      readonly path: string;
      readonly capabilities: ReadonlySet<SelectionCapability>;
    }
  | {
      readonly kind: 'browse-node';
      readonly path: string;
      readonly nodeKind: 'file' | 'directory';
      readonly capabilities: ReadonlySet<SelectionCapability>;
    }
  | {
      readonly kind: 'plugin-item';
      readonly pluginId: string;
      readonly itemId: string;
      readonly label: string;
      readonly capabilities: ReadonlySet<SelectionCapability>;
      /** Plugin-defined payload — passed back to the plugin's handler verbatim. */
      readonly payload?: unknown;
    }
  | {
      readonly kind: 'text-range';
      readonly address: DocumentAddress;
      readonly selectionText: string;
      readonly hostEntity: HostEntity;
      readonly capabilities: ReadonlySet<SelectionCapability>;
    }
  // Tempdoc 526 §4.1 + §16 retraction — citation and result-set ship with v1
  // producers (citation kind-flip in Shell.onCitationSelect; result-set via
  // summarizeChatState pre-fill mirror and the URL adapter bridge).
  // ConversationTurn and HealthCondition retracted (no v1 FE producer).
  | {
      readonly kind: 'citation';
      readonly citation: SourceCitation;
      readonly promotedFrom: 'rag-stream' | 'manual';
      readonly capabilities: ReadonlySet<SelectionCapability>;
    }
  | {
      readonly kind: 'result-set';
      readonly items: ReadonlyArray<ResultRef>;
      readonly query?: string;
      readonly capabilities: ReadonlySet<SelectionCapability>;
    }
  | {
      // Tempdoc 526 §17 T1C — health-condition pickup gesture (F6 / F21).
      readonly kind: 'health-condition';
      readonly conditionId: string;
      readonly severity?: string;
      readonly summary?: string;
      readonly capabilities: ReadonlySet<SelectionCapability>;
    };

export interface SelectionDescriptor {
  readonly items: ReadonlyArray<SelectionItem>;
  readonly primaryIndex: number;
  readonly surfaceId: string | null;
}

const EMPTY: SelectionDescriptor = {
  items: [],
  primaryIndex: 0,
  surfaceId: null,
};

let current: SelectionDescriptor = EMPTY;
const listeners = new Set<(d: SelectionDescriptor) => void>();

function notify(): void {
  for (const l of listeners) {
    try {
      l(current);
    } catch {
      /* swallow */
    }
  }
}

/**
 * Project the union of capabilities across all selected items into
 * a comma-separated string. The §13.1 when-evaluator uses
 * `in selectionCapabilities` against this representation.
 */
function projectCapabilitiesString(items: ReadonlyArray<SelectionItem>): string {
  if (items.length === 0) return '';
  const out = new Set<string>();
  for (const item of items) {
    for (const cap of item.capabilities) out.add(cap);
  }
  return Array.from(out).sort().join(',');
}

function projectKind(
  items: ReadonlyArray<SelectionItem>,
):
  | 'none'
  | 'search-hit'
  | 'browse-node'
  | 'plugin-item'
  | 'text-range'
  | 'citation'
  | 'result-set'
  | 'health-condition' {
  if (items.length === 0) return 'none';
  // Mixed-kind selections (rare for now) report the primary item's
  // kind. Capability filtering covers cross-kind UX cases.
  return items[0]!.kind;
}

/**
 * Project per-kind selection details into the flat-key fields the
 * when-evaluator (slice 521 §11.1) can match against. Tempdoc 526 §12.4 —
 * the `text-range` variant exposes `selectionEntityKind`,
 * `selectionAddressCoords`, and `selectionTextLength` so commands and menu
 * items can gate on the typed shape.
 */
function projectSelectionContextDetails(items: ReadonlyArray<SelectionItem>): {
  selectionEntityKind: string | null;
  selectionAddressCoords: string | null;
  selectionTextLength: number;
} {
  if (items.length === 0) {
    return { selectionEntityKind: null, selectionAddressCoords: null, selectionTextLength: 0 };
  }
  const primary = items[0]!;
  if (primary.kind === 'text-range') {
    return {
      selectionEntityKind: primary.hostEntity.kind,
      selectionAddressCoords: primary.address.coords,
      selectionTextLength: primary.selectionText.length,
    };
  }
  return { selectionEntityKind: null, selectionAddressCoords: null, selectionTextLength: 0 };
}

export function getSelection(): SelectionDescriptor {
  return current;
}

export function subscribeSelection(listener: (d: SelectionDescriptor) => void): () => void {
  listeners.add(listener);
  try {
    listener(current);
  } catch {
    /* swallow */
  }
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Set the current selection. Drives:
 *   - listeners
 *   - ShellContext.selectionKind/Count/Capabilities (so when-eval
 *     sees the new state immediately)
 */
export function setSelection(descriptor: SelectionDescriptor): void {
  current = descriptor;
  const details = projectSelectionContextDetails(descriptor.items);
  // Tempdoc 526 §4.3 property 4 — push the new kind/coords/length keys to
  // ShellContext FIRST so the registry-derived capability projection (run
  // immediately after) sees the post-update context. Two-pass write avoids a
  // chicken-and-egg where appliesTo expressions reference the stale kind.
  updateShellContext({
    selectionKind: projectKind(descriptor.items),
    selectionCount: descriptor.items.length,
    selectionCapabilities: projectCapabilitiesString(descriptor.items),
    selectionEntityKind: details.selectionEntityKind,
    selectionAddressCoords: details.selectionAddressCoords,
    selectionTextLength: details.selectionTextLength,
  });
  // Tempdoc 526 §4.3 property 4 — replace the authored comma-string with the
  // registry-derived projection ("the string is computed, not stored"). The
  // legacy DEFAULT_CAPABILITIES_BY_KIND fallback remains for backward-compat
  // with consumers that read the capability set off a SelectionItem directly,
  // but the ShellContext flat key now reflects applicable actions, not
  // authored capabilities. Drift becomes impossible because both are the same
  // computation. Capabilities lookup is gated behind a try/catch so a failure
  // in the registry doesn't block selection updates.
  try {
    // Lazy import via require-style dynamic to keep this file's import graph
    // free of registry-cycle hazards.
    void import('../commands/SelectionActionRegistry.js').then((m) => {
      updateShellContext({ selectionCapabilities: m.projectDerivedCapabilities() });
    });
  } catch {
    /* keep authored capabilities if the registry isn't loaded yet */
  }
  notify();
}

/**
 * Set a single-item selection. Helper for the common case where a
 * surface picks one item at a time (today's behavior). Multi-select
 * callers use setSelection directly.
 */
export function setSingleSelection(item: SelectionItem, surfaceId: string | null): void {
  setSelection({ items: [item], primaryIndex: 0, surfaceId });
}

/** Clear the selection — fires listeners, resets ShellContext flat keys. */
export function clearSelection(): void {
  if (current.items.length === 0) return;
  setSelection(EMPTY);
}

/**
 * Default capability sets per kind. Surfaces can override at
 * construction time; this provides a sensible default for callers
 * that don't curate capabilities per item.
 */
export const DEFAULT_CAPABILITIES_BY_KIND: Record<SelectionItem['kind'], ReadonlySet<SelectionCapability>> = {
  'search-hit': new Set<SelectionCapability>(['open', 'pin', 'ask-ai-about', 'copy-link']),
  'browse-node': new Set<SelectionCapability>(['open', 'reveal-in-explorer', 'copy-link']),
  'plugin-item': new Set<SelectionCapability>(),
  // tempdoc 526 §12.4 — text-range selections are LLM-targeted by design.
  'text-range': new Set<SelectionCapability>(['ask-ai-about', 'copy-link']),
  citation: new Set<SelectionCapability>(['ask-ai-about', 'copy-link']),
  'result-set': new Set<SelectionCapability>(['ask-ai-about', 'export']),
  'health-condition': new Set<SelectionCapability>(['ask-ai-about']),
};

export function __resetSelectionForTest(): void {
  current = EMPTY;
  listeners.clear();
}
