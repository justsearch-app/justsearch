// SPDX-License-Identifier: Apache-2.0
/**
 * host.selection capability module (548 §4.2 Increment A) — extracted from
 * HostApiImpl. Owns the selection boundary helpers (snapshot projection +
 * plugin-input → descriptor) and the trusted/untrusted selection sub-interface.
 * UNTRUSTED structurally omits setSelection/clearSelection/compose (the optional
 * PluginSelection fields are simply absent — a compile-time signal in plugin
 * code), selected by tier; no runtime if(tier) in any method body.
 */

import {
  clearSelection as clearSelectionInternal,
  getSelection,
  setSelection as setSelectionInternal,
  subscribeSelection,
  DEFAULT_CAPABILITIES_BY_KIND,
  type SelectionDescriptor,
  type SelectionItem,
  type SelectionCapability,
} from '../../state/selectionState.js';
import { listSelectionActions } from '../../commands/SelectionActionRegistry.js';
import { compose } from '../../utils/compose.js';
import { selectionItemToWirePayload } from '../../utils/selectionWire.js';
import type {
  PluginTrustTier,
  PluginSelection,
  SelectionDescriptorInput,
  SelectionItemSnapshot,
  SelectionSnapshot,
} from '../plugin-types.js';

function itemToSnapshot(item: SelectionItem): SelectionItemSnapshot {
  const capabilities = Array.from(item.capabilities);
  switch (item.kind) {
    case 'search-hit':
      return { kind: item.kind, capabilities, label: item.title, id: item.hitId, path: item.path };
    case 'browse-node':
      return { kind: item.kind, capabilities, label: item.path, id: item.path, path: item.path };
    case 'plugin-item':
      return {
        kind: item.kind,
        capabilities,
        label: item.label,
        id: item.itemId,
        ...(item.payload !== undefined ? { payload: item.payload } : {}),
      };
    case 'text-range':
      // Tempdoc 526 §12.4 — text-range projects its selected text + docId.
      return {
        kind: item.kind,
        capabilities,
        label: item.selectionText.length > 60 ? item.selectionText.slice(0, 60) + '…' : item.selectionText,
        id: item.hostEntity.id,
        path: item.address.docId,
      };
    case 'citation':
      // Tempdoc 526 §4.1 — citation kind: excerpt as label, parent doc as path.
      return {
        kind: item.kind,
        capabilities,
        label:
          item.citation.excerpt.length > 60
            ? item.citation.excerpt.slice(0, 60) + '…'
            : item.citation.excerpt || `Citation (${item.citation.parentDocId})`,
        id: item.citation.parentDocId,
        path: item.citation.parentDocId,
      };
    case 'result-set':
      return {
        kind: item.kind,
        capabilities,
        label: item.query
          ? `Result set: ${item.query}`
          : `${item.items.length} result${item.items.length === 1 ? '' : 's'}`,
        id: `result-set-${item.items.length}`,
      };
    case 'health-condition':
      return { kind: item.kind, capabilities, label: item.summary || item.conditionId, id: item.conditionId };
  }
}

function descriptorToSnapshot(d: SelectionDescriptor): SelectionSnapshot | null {
  if (d.items.length === 0) return null;
  return { items: d.items.map(itemToSnapshot), primaryIndex: d.primaryIndex, surfaceId: d.surfaceId };
}

function inputToDescriptor(pluginId: string, input: SelectionDescriptorInput): SelectionDescriptor {
  const items: SelectionItem[] = input.items.map((entry) => {
    const requested = new Set<SelectionCapability>(
      (entry.capabilities ?? []).filter(
        (c): c is SelectionCapability =>
          c === 'open' ||
          c === 'pin' ||
          c === 'export' ||
          c === 'ask-ai-about' ||
          c === 'reveal-in-explorer' ||
          c === 'copy-link',
      ),
    );
    const capabilities = requested.size > 0 ? requested : DEFAULT_CAPABILITIES_BY_KIND['plugin-item'];
    return {
      kind: 'plugin-item',
      pluginId,
      itemId: entry.itemId,
      label: entry.label,
      capabilities,
      ...(entry.payload !== undefined ? { payload: entry.payload } : {}),
    };
  });
  return { items, primaryIndex: input.primaryIndex ?? 0, surfaceId: input.surfaceId ?? null };
}

export function createSelectionApi(tier: PluginTrustTier, pluginId: string): PluginSelection {
  const isUntrusted = tier === 'UNTRUSTED_PLUGIN';
  // Tempdoc 526 §5 — `actions` is read-only at every tier; `compose` +
  // setSelection/clearSelection are trust-attenuated (UNTRUSTED can't dispatch).
  const actionsImpl = (): ReadonlyArray<{
    id: string;
    operation: string;
    label: string;
    trustGate?: 'AUTO' | 'INLINE_CONFIRM' | 'TYPED_CONFIRM' | 'DENY';
  }> => {
    return listSelectionActions().map((a) => ({
      id: a.id,
      operation: a.operation,
      label: a.presentation.floating?.label ?? a.presentation.contextMenu?.label ?? a.id,
      trustGate: a.trustGate,
    }));
  };
  const composeImpl = (intent: {
    operation: 'core.summarize' | 'core.ask';
    userPrompt?: string;
    source?: string;
  }): boolean => {
    const selected = getSelection();
    return compose({
      operation: intent.operation,
      source: intent.source ?? 'PLUGIN_EMITTED',
      userPrompt: intent.userPrompt,
      selection: selectionItemToWirePayload(selected.items[selected.primaryIndex]) ?? undefined,
    });
  };

  return isUntrusted
    ? {
        current: () => descriptorToSnapshot(getSelection()),
        subscribe: (handler) => subscribeSelection((d) => handler(descriptorToSnapshot(d))),
        actions: actionsImpl,
      }
    : {
        current: () => descriptorToSnapshot(getSelection()),
        subscribe: (handler) => subscribeSelection((d) => handler(descriptorToSnapshot(d))),
        actions: actionsImpl,
        compose: composeImpl,
        setSelection: (descriptor) => setSelectionInternal(inputToDescriptor(pluginId, descriptor)),
        clearSelection: () => clearSelectionInternal(),
      };
}
