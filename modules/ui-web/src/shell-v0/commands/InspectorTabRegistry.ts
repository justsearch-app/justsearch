// SPDX-License-Identifier: Apache-2.0
/**
 * InspectorTabRegistry — Tempdoc 508 §4.2 — inspector tab contribution.
 *
 * Each window's details pane reads this registry for the tabs it adds after its own;
 * plugins contribute them via PluginContribution.
 *
 * Tempdoc 543 §28.W7 — uses the shared `createRegistry` primitive.
 */

import { getShellContext } from '../state/shellContextState.js';
import { evaluateWhen } from './whenExpression.js';
import type { Provenance } from '../primitives/provenance.js';
import { createRegistry } from '../primitives/registry.js';

export interface InspectorTabContribution {
  readonly id: string;
  readonly label: string;
  readonly icon?: string;
  readonly priority: number;
  readonly source: 'core' | 'plugin';
  /** Tempdoc 543 §3.A / §21.A2 — required typed provenance. */
  readonly provenance: Provenance;
  /**
   * Tempdoc 508 §11.1 / §13.1 — visibility predicate against the
   * live ShellContext. Tabs with `when` only appear when the
   * expression evaluates true. Absent = always visible.
   */
  readonly when?: string;
  /** Renderer called when the tab is active. Returns content to mount. */
  readonly render: (context: { selectedItem: unknown }) => HTMLElement | string;
}

const _registry = createRegistry<InspectorTabContribution>();

export const registerInspectorTab = _registry.register;
export const unregisterInspectorTab = _registry.unregister;
export const onInspectorTabChange = _registry.subscribe;
export const __resetForTest = _registry.__resetForTest;

export function listInspectorTabs(): InspectorTabContribution[] {
  const ctx = getShellContext() as unknown as Record<string, unknown>;
  return Array.from(_registry.list())
    .filter((t) => evaluateWhen(t.when, ctx))
    .sort((a, b) => a.priority - b.priority);
}
