// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 521 §16.7 deeper (Phase B consumer) — `<jf-pane-picker>` is
 * the right-pane affordance for `core.split`. A thin header strip
 * showing the currently mounted right-pane surface plus a dropdown
 * listing the catalog's rail surfaces (excluding the current
 * primary). Selecting a surface writes
 * `userConfig.secondaryActiveSurface`; Stage re-renders with the new
 * right pane.
 *
 * Design discipline:
 * - Lit element, mounted by Stage when `splitAxis !== null`. Stage
 *   passes the candidates + selection + onPick callback as
 *   properties so the picker is purely presentational.
 * - Catalog-driven candidate list: plugins contributing rail surfaces
 *   show up automatically (no special-casing of core surface ids).
 * - Disabled state when zero candidates exist; empty render is the
 *   single-surface fallback that Stage handles on its own.
 *
 * The element does NOT own state itself. Selection persistence flows
 * through `setSecondaryActiveSurface(...)` upstream so the choice
 * survives reloads via the existing per-profile UserStateDocument
 * channel.
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { selectStyles } from '../primitives/selectStyles.js';

export interface PanePickerCandidate {
  readonly id: string;
  readonly label: string;
}

export class PanePicker extends JfElement {
  static properties = {
    candidates: { attribute: false },
    selectedId: { attribute: 'selected-id', type: String },
    onPick: { attribute: false },
  };

  declare candidates: ReadonlyArray<PanePickerCandidate>;
  declare selectedId: string | null;
  declare onPick: ((surfaceId: string) => void) | null;

  constructor() {
    super();
    this.candidates = [];
    this.selectedId = null;
    this.onPick = null;
  }

  static styles = [selectStyles, css`
    :host {
      display: block;
      width: 100%;
      background: var(--surface-1);
      border-bottom: 1px solid var(--border-subtle);
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .row {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.25rem 0.75rem;
      height: 28px;
      box-sizing: border-box;
    }
    .label {
      letter-spacing: 0.04em;
      text-transform: uppercase;
      color: var(--text-muted);
    }
    select {
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      padding: 0.125rem 0.375rem;
      font-size: var(--font-size-xs);
      flex: 1;
      max-width: 240px;
    }
    select:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
  `];

  private handleChange(e: Event): void {
    const target = e.target as HTMLSelectElement;
    const value = target.value;
    if (!value) return;
    this.onPick?.(value);
  }

  override render(): TemplateResult {
    const disabled = this.candidates.length === 0;
    return html`
      <div class="row">
        <span class="label">Right pane</span>
        <select
          aria-label="Choose right-pane surface"
          ?disabled=${disabled}
          @change=${this.handleChange}
        >
          ${this.candidates.map(
            (c) => html`
              <option value=${c.id} ?selected=${c.id === this.selectedId}>
                ${c.label}
              </option>
            `,
          )}
        </select>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-pane-picker')) {
  customElements.define('jf-pane-picker', PanePicker);
}
