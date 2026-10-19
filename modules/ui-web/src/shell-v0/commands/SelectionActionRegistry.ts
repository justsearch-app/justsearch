// SPDX-License-Identifier: Apache-2.0
/**
 * SelectionActionRegistry — Tempdoc 526 §4.3 — fifth bespoke contribution
 * registry mirroring Command / StatusBar / ContextAction / Keybinding.
 *
 * The registry answers: *"given the current {@link SelectionPayload}, which
 * operations are applicable, and where do they render?"* (§4.3 framing).
 *
 * Each entry declares:
 *   - `id` — stable action id (e.g., `core.selection.summarize-text-range`).
 *   - `appliesTo` — a WhenExpression evaluated against the live
 *     {@code ShellContext} (slice 521 §11.1). Selection-specific flat keys
 *     (`selectionKind`, `selectionEntityKind`, `selectionAddressCoords`,
 *     `selectionTextLength`, `selectionCount`) feed this predicate.
 *   - `operation` — the ComposeIntent operation id this action invokes.
 *   - `presentation` — per-presentation slots (floating, contextMenu, palette,
 *     keyboard, askAiButton). Each context UI projects whichever fields it
 *     consumes; the registry is the single source of truth (§4.3 property 1).
 *
 * Capability derivation (§4.3 property 4): the registry exposes
 * {@link applicableActions} which evaluates `appliesTo` against the live
 * ShellContext + current selection. The {@code selectionState} module reads
 * this to *derive* the {@code selectionCapabilities} string instead of the
 * authored {@code DEFAULT_CAPABILITIES_BY_KIND} fallback — drift between
 * "capability declared" and "action actually applicable" becomes impossible.
 */

import { getShellContext } from '../state/shellContextState.js';
import { evaluateWhen } from './whenExpression.js';

import type { ComposeOperation } from '../../api/types/selection.js';
import type { Provenance } from '../primitives/provenance.js';

/** Presentation slots — each is the data one consumer surface needs. */
export interface SelectionActionPresentation {
  /** Floating-near-selection menu (the F9 surface). */
  readonly floating?: {
    readonly label: string;
    readonly icon?: string;
    readonly priority: number;
  };
  /** Right-click context menu. */
  readonly contextMenu?: {
    readonly label: string;
    readonly icon?: string;
    readonly category: string;
  };
  /** Command palette. */
  readonly palette?: {
    readonly label: string;
    readonly keywords: ReadonlyArray<string>;
    readonly description: string;
  };
  /** Keyboard shortcut. */
  readonly keyboard?: {
    readonly binding: string;
    readonly when?: string;
  };
  /** Inline "Ask AI" affordance inside InspectorPane / chat surfaces. */
  readonly askAiButton?: {
    readonly label: string;
    readonly visible?: string;
  };
}

export interface SelectionActionContribution {
  readonly id: string;
  /** WhenExpression — true means the action applies to the current selection. */
  readonly appliesTo: string;
  /** ComposeIntent operation invoked when the user picks this action. */
  readonly operation: ComposeOperation;
  /** Presentation slots — the registry doesn't render; each surface projects. */
  readonly presentation: SelectionActionPresentation;
  readonly source: 'core' | 'plugin';
  /** Tempdoc 543 §3.A / §21.A2 — required typed provenance. */
  readonly provenance: Provenance;
  /** Optional trust gate override; defaults to the operation's gate. */
  readonly trustGate?: 'AUTO' | 'INLINE_CONFIRM' | 'TYPED_CONFIRM' | 'DENY';
}

// Tempdoc 543 §28.W7 — shared registry primitive.
import { createRegistry } from '../primitives/registry.js';

const _registry = createRegistry<SelectionActionContribution>();

export const registerSelectionAction = _registry.register;
export const unregisterSelectionAction = _registry.unregister;

/**
 * Return every registered action whose {@code appliesTo} expression evaluates
 * true against the live ShellContext. Result is sorted by floating-presentation
 * priority where available, then by id for stability.
 */
export function listSelectionActions(): SelectionActionContribution[] {
  const ctx = getShellContext() as unknown as Record<string, unknown>;
  const out: SelectionActionContribution[] = [];
  for (const a of _registry.list()) {
    if (evaluateWhen(a.appliesTo, ctx)) out.push(a);
  }
  out.sort((a, b) => {
    const pa = a.presentation.floating?.priority ?? 1000;
    const pb = b.presentation.floating?.priority ?? 1000;
    return pa !== pb ? pa - pb : a.id.localeCompare(b.id);
  });
  return out;
}

/**
 * Project the action list into the {@code selectionCapabilities} comma-string
 * the WhenExpression evaluator's {@code in selectionCapabilities} membership
 * checks read. Per §4.3 property 4: derived from action ids, not authored.
 *
 * Tempdoc 526 §16 F12 — precise operation→capability mapping. Earlier
 * versions blanket-added `'ask-ai-about'` whenever ANY action applied,
 * which lost precision for predicates that specifically want to gate on
 * ask vs summarize. The string now contains the action id, plus exactly
 * one operation-shorthand per applicable action.
 */
export function projectDerivedCapabilities(): string {
  const matched = listSelectionActions();
  if (matched.length === 0) return '';
  const out = new Set<string>();
  for (const a of matched) {
    out.add(a.id);
    const shorthand = operationCapability(a.operation);
    if (shorthand) out.add(shorthand);
  }
  return Array.from(out).sort().join(',');
}

function operationCapability(op: ComposeOperation): string {
  switch (op) {
    case 'core.summarize':
      return 'summarize';
    case 'core.ask':
      // Tempdoc 526 §16 F12 — `ask-ai-about` is the legacy capability name
      // used by existing when-clauses (see DEFAULT_CAPABILITIES_BY_KIND);
      // it maps 1:1 to the ask operation, not to "any action."
      return 'ask-ai-about';
  }
}

export const onSelectionActionChange = _registry.subscribe;
export const __resetSelectionActionsForTest = _registry.__resetForTest;
