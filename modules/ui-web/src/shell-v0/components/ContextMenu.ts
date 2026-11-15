// SPDX-License-Identifier: Apache-2.0
/**
 * ContextMenu — Lit primitive for right-click action menus (slice 458).
 *
 * Replaces the React `<ContextMenu>` for Lit-side surfaces (Browse,
 * Library, future search results). Works with `confirmAsync()` (slice
 * 457) for HIGH-risk actions via `requiresConfirmation`.
 *
 * Two parts:
 *  - `<jf-context-menu>`: presentational menu with positioning,
 *    grouping, keyboard nav.
 *  - `openContextMenu(actions, anchor)`: helper that creates the
 *    element on body, returns a Promise that resolves with the
 *    invoked action id (or null on cancel).
 */

import { html, css, type TemplateResult, nothing } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { icon, type IconName } from './Icon.js';
import { deepActiveElement } from '../utils/keyboardHandler.js';
import { menuStyles } from './Menu.js';
import { confirmAsync } from './ConfirmDialog.js';
import { listContextActions } from '../commands/ContextActionRegistry.js';

export type ContextActionCategory = 'file' | 'ai' | 'index' | 'system' | 'plugin';

export interface ContextMenuAction {
  id: string;
  label: string;
  icon?: IconName;
  shortcut?: string;
  category: ContextActionCategory;
  enabled: boolean;
  requiresConfirmation?: {
    title: string;
    message: string;
    variant?: 'danger' | 'warning' | 'info';
    confirmLabel?: string;
  };
}

interface MenuItem {
  kind: 'action' | 'separator';
  action?: ContextMenuAction;
}

const CATEGORY_ORDER: ContextActionCategory[] = [
  'file',
  'ai',
  'index',
  'system',
  'plugin',
];

function buildItems(actions: ContextMenuAction[]): MenuItem[] {
  const items: MenuItem[] = [];
  for (const cat of CATEGORY_ORDER) {
    const grouped = actions.filter((a) => a.category === cat);
    if (grouped.length === 0) continue;
    if (items.length > 0) items.push({ kind: 'separator' });
    for (const a of grouped) items.push({ kind: 'action', action: a });
  }
  return items;
}

export class ContextMenu extends JfElement {
  static properties = {
    actions: { type: Array },
    anchor: { type: Object },
  };

  declare actions: ContextMenuAction[];
  declare anchor: { x: number; y: number } | null;


  constructor() {
    super();
    this.actions = [];
    this.anchor = null;
  }

  static styles = [menuStyles, css`:host { display: contents; }`];

  private async invoke(action: ContextMenuAction): Promise<void> {
    if (action.requiresConfirmation) {
      const ok = await confirmAsync({
        title: action.requiresConfirmation.title,
        message: action.requiresConfirmation.message,
        variant: action.requiresConfirmation.variant ?? 'danger',
        confirmLabel: action.requiresConfirmation.confirmLabel,
      });
      if (!ok) {
        this.cancel();
        return;
      }
    }
    this.dispatchEvent(
      new CustomEvent('context-action', {
        detail: { id: action.id },
        bubbles: true,
        composed: true,
      }),
    );
  }

  private cancel(): void {
    this.dispatchEvent(new CustomEvent('cancel', { bubbles: true, composed: true }));
  }

  override render(): TemplateResult | typeof nothing {
    if (!this.anchor || this.actions.length === 0) return nothing;
    const items = buildItems(this.actions);
    return html`<jf-menu label="File actions" .anchor=${this.anchor}
      @menu-close=${() => this.cancel()} .content=${html`${items.map(item => {
        if (item.kind === 'separator') return html`<div class="sep" role="separator"></div>`;
        const a = item.action!;
        return html`<button class="item" type="button" role="menuitem" tabindex="-1" ?disabled=${!a.enabled}
          @click=${() => void this.invoke(a)}>${a.icon ? icon({ name: a.icon, size: 16 }) : nothing}
          <span class="item-label">${a.label}</span>${a.shortcut ? html`<kbd>${a.shortcut}</kbd>` : nothing}</button>`;
      })}`}></jf-menu>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-context-menu')) {
  customElements.define('jf-context-menu', ContextMenu);
}

// ---------- Promise-based opener ----------

export interface OpenContextMenuOptions {
  actions: ContextMenuAction[];
  anchor: { x: number; y: number };
  /**
   * Tempdoc 508 §4 — context name for plugin-contributed actions.
   * When set, ContextActionRegistry entries matching this context are
   * appended to the action list and invoked directly when chosen.
   */
  context?: string;
  /** Payload passed to plugin action handlers and `enabled` filters. */
  payload?: unknown;
  /**
   * §28.W2 — when provided, the Addressable is threaded through
   * `listContextActions(..., addressable)` so the EvaluationContext
   * layer (Scope ∪ TargetFacts) applies the registered projector for
   * `addressable.kind`. The when-clauses on contributed ContextActions
   * see the flat-key facts the projector produces.
   */
  addressable?: import('../substrates/addressable.js').Addressable | null;
}

/**
 * Open a context menu at the given coordinate. Returns the invoked
 * action id, or null if cancelled. When `context` is provided, plugin
 * actions registered for that context are appended; if the user picks
 * one, the plugin handler is invoked here (the returned id is still
 * the action id for callers that branch on it).
 */

export function openContextMenu(opts: OpenContextMenuOptions): Promise<string | null> {
  // Tempdoc 508 §4 — merge plugin-contributed actions for this context.
  type PluginAction = {
    id: string;
    label: string;
    icon?: string;
    handler: (payload: unknown) => void | Promise<void>;
  };
  let pluginActions: PluginAction[] = [];
  if (opts.context) {
    // §28.W2 — pass addressable through so registered projectors fire
    // and `when`-clauses see flat-key facts.
    pluginActions = listContextActions(opts.context, opts.payload, opts.addressable ?? null)
      .filter((a) => !a.enabled || a.enabled(opts.payload))
      .map((a) => ({
        id: a.id,
        label: a.label,
        icon: a.icon,
        handler: a.handler,
      }));
  }

  const mergedActions: ContextMenuAction[] = [
    ...opts.actions,
    ...pluginActions.map((a) => ({
      id: a.id,
      label: a.label,
      icon: a.icon as IconName | undefined,
      category: 'plugin' as ContextActionCategory,
      enabled: true,
    })),
  ];

  const previousFocus = deepActiveElement();
  return new Promise<string | null>((resolve) => {
    const el = document.createElement('jf-context-menu') as ContextMenu;
    el.actions = mergedActions;
    el.anchor = opts.anchor;
    const cleanup = (id: string | null) => {
      const hadFocus = el.shadowRoot?.contains(el.shadowRoot.activeElement) ?? false;
      el.remove();
      if (hadFocus && previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus();
      // Invoke plugin handler if a plugin action was chosen.
      if (id) {
        const pluginAction = pluginActions.find((a) => a.id === id);
        if (pluginAction) {
          void Promise.resolve(pluginAction.handler(opts.payload));
        }
      }
      resolve(id);
    };
    el.addEventListener('context-action', (e) => {
      const detail = (e as CustomEvent<{ id: string }>).detail;
      cleanup(detail.id);
    }, { once: true });
    el.addEventListener('cancel', () => cleanup(null), { once: true });
    document.body.appendChild(el);
  });
}
