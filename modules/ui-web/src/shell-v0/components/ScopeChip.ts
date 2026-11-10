// SPDX-License-Identifier: Apache-2.0
/**
 * ScopeChip (`jf-scope-chip`) — Search-Thread S3. Displays one active scope constraint
 * ({@link SearchScopeChip} from `searchState.ts`): "just this file" or "just this result set",
 * narrowing both instant search and AI retrieval to the chip's pinned docIds.
 *
 * Presentational: the host owns removal — this element only dispatches `scope-remove` (bubbling,
 * composed) when the remove affordance activates; the host removes the chip from state (and
 * decides whether to re-issue the active search — see `removeScopeChip` in searchState.ts).
 *
 * A `file` chip's label is the file's path, middle-ellipsized via the shared
 * `resultRowPresentation.formatDisplayPath` (the same truncation the result rows use — no second
 * path-formatting fork). A `result-set` chip's label is a short description the caller supplies
 * (e.g. "12 results"), rendered verbatim.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { icon } from './Icon.js';
import { formatDisplayPath } from './searchResults/resultRowPresentation.js';
import type { SearchScopeChip } from '../state/searchState.js';

export class ScopeChip extends JfElement {
  static properties = {
    chip: { attribute: false },
  };

  declare chip: SearchScopeChip | undefined;

  static styles = css`
    :host {
      display: inline-flex;
    }
    .scope-chip {
      display: inline-flex;
      align-items: center;
      gap: 0.3rem;
      padding: 0.125rem 0.25rem 0.125rem 0.55rem;
      border-radius: 9999px;
      border: 1px solid var(--border-subtle);
      background: var(--surface-2);
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      max-width: 100%;
    }
    .scope-chip-icon {
      display: inline-flex;
      flex: none;
      color: var(--text-tertiary);
    }
    .scope-chip-label {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      max-width: 16rem;
    }
    .scope-chip-remove {
      display: inline-flex;
      flex: none;
      align-items: center;
      justify-content: center;
      padding: 0.15rem;
      border: none;
      border-radius: 9999px;
      background: transparent;
      color: inherit;
      cursor: pointer;
      transition: background var(--duration-fast), color var(--duration-fast);
    }
    .scope-chip-remove:hover {
      background: var(--surface-hover);
      color: var(--text-primary);
    }
    .scope-chip-remove:focus-visible {
      outline: 2px solid var(--focus-ring-color);
      outline-offset: 1px;
    }
  `;

  private handleRemove(): void {
    this.dispatchEvent(new CustomEvent('scope-remove', { bubbles: true, composed: true }));
  }

  override render(): TemplateResult | typeof nothing {
    const chip = this.chip;
    if (!chip) return nothing;
    const displayLabel = chip.kind === 'file' ? formatDisplayPath(chip.label) : chip.label;
    const iconName = chip.kind === 'result-set' ? 'list' : 'file-text';
    return html`<span class="scope-chip" title=${chip.label}>
      <span class="scope-chip-icon">${icon({ name: iconName, size: 13 })}</span>
      <span class="scope-chip-label">${displayLabel}</span>
      <button
        type="button"
        class="scope-chip-remove"
        aria-label=${`Remove scope ${chip.label}`}
        @click=${() => this.handleRemove()}
      >
        ${icon({ name: 'x', size: 11 })}
      </button>
    </span>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-scope-chip')) {
  customElements.define('jf-scope-chip', ScopeChip);
}
