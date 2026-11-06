// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-details-plugin-tab> — one plugin inspector tab inside a window's details pane (design system
 * §10: "inspector tabs from plugins become details-pane tabs"). A plugin's `render` is outside code:
 * a string stays text, and a throw costs that tab alone and says so.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import {
  listInspectorTabs,
  type InspectorTabContribution,
} from '../../commands/InspectorTabRegistry.js';
import type { DetailsTab } from './DetailsPane.js';

const PLUGIN_TAB_PREFIX = 'plugin:';

/** The details-pane tabs the registry offers right now, in the registry's own order. */
export function pluginDetailsTabs(): DetailsTab[] {
  return listInspectorTabs().map((tab) => ({ id: `${PLUGIN_TAB_PREFIX}${tab.id}`, label: tab.label }));
}

export function pluginContributionFor(tabId: string): InspectorTabContribution | null {
  if (!tabId.startsWith(PLUGIN_TAB_PREFIX)) return null;
  const id = tabId.slice(PLUGIN_TAB_PREFIX.length);
  return listInspectorTabs().find((tab) => tab.id === id) ?? null;
}

export class DetailsPluginTab extends JfElement {
  static properties = {
    contribution: { attribute: false },
    selectedItem: { attribute: false },
    failed: { state: true },
  };

  declare contribution: InspectorTabContribution | null;
  declare selectedItem: unknown;
  declare private failed: boolean;

  constructor() {
    super();
    this.contribution = null;
    this.selectedItem = null;
    this.failed = false;
  }

  override willUpdate(changed: Map<string, unknown>): void {
    if (changed.has('contribution')) this.failed = false;
  }

  override updated(changed: Map<string, unknown>): void {
    if (!changed.has('contribution') && !changed.has('selectedItem')) return;
    const mount = this.renderRoot.querySelector<HTMLElement>('.mount');
    if (mount === null || this.contribution === null) return;
    try {
      const output = this.contribution.render({ selectedItem: this.selectedItem });
      mount.replaceChildren(output);
      this.failed = false;
    } catch {
      mount.replaceChildren();
      this.failed = true;
    }
  }

  override render(): TemplateResult {
    return html`
      ${this.failed
        ? html`<p class="failed" role="status" data-testid="details-plugin-failed">This tab could not be shown. The plugin stopped while drawing it.</p>`
        : nothing}
      <div class="mount"></div>
    `;
  }

  static styles = css`
    :host { display: block; block-size: 100%; overflow: auto; box-sizing: border-box; padding: var(--jf-space-4); }
    .failed { margin: 0; color: var(--text-secondary); font-size: var(--font-size-sm); }
  `;
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-details-plugin-tab')) {
  customElements.define('jf-details-plugin-tab', DetailsPluginTab);
}

declare global {
  interface HTMLElementTagNameMap {
    'jf-details-plugin-tab': DetailsPluginTab;
  }
}
