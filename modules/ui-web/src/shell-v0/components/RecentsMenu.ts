// SPDX-License-Identifier: Apache-2.0
/**
 * RecentsMenu — tempdoc 609 §R (T1.2 / NEW-4): a navigation-trail menu.
 *
 * A read-only projection of the existing {@link NavigationJournal} (slice 501) — which already persists a
 * 50-entry ring of visited surfaces but had no UI beyond the back/forward buttons. This surfaces the trail
 * as a clickable "Recents" dropdown: pick a recent surface and it is restored via
 * {@link navigateToEntry} (the journal's jump-to-entry, which re-dispatches the entry's saved address +
 * state without re-journaling). No new store — the journal is the single source.
 *
 * The shell supplies the IntentRouter dispatch via the `dispatch` property (mirroring `<jf-pane-picker>`'s
 * `onPick`). Native `<button>`s keep every activation keyboard-operable (the 559 operability contract;
 * measured axe verifies it on the rendered page since the static gate was retired, 930 chunk H).
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import {
  subscribeJournal,
  navigateToEntry,
  type DispatchFn,
  type NavigationJournalEntry,
} from '../state/NavigationJournal.js';

const MAX_RECENTS = 8;

export class RecentsMenu extends JfElement {
  static properties = {
    dispatch: { attribute: false },
    open: { state: true },
    entries: { state: true },
    cursor: { state: true },
  };

  declare dispatch: DispatchFn | null;
  declare open: boolean;
  declare entries: readonly NavigationJournalEntry[];
  declare cursor: number;

  private unsub: (() => void) | null = null;
  private readonly onDocPointer = (e: Event): void => {
    if (this.open && !e.composedPath().includes(this)) this.open = false;
  };

  constructor() {
    super();
    this.dispatch = null;
    this.open = false;
    this.entries = [];
    this.cursor = -1;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.unsub = subscribeJournal((s) => {
      this.entries = s.entries;
      this.cursor = s.cursor;
    });
    document.addEventListener('pointerdown', this.onDocPointer);
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsub?.();
    this.unsub = null;
    document.removeEventListener('pointerdown', this.onDocPointer);
  }

  /** Most-recent-first, one row per surface (latest visit), excluding the current view. */
  private recents(): NavigationJournalEntry[] {
    const seen = new Set<string>();
    const out: NavigationJournalEntry[] = [];
    for (let i = this.entries.length - 1; i >= 0; i--) {
      const e = this.entries[i]!;
      if (i === this.cursor) continue; // skip where we already are
      if (seen.has(e.surfaceId)) continue;
      seen.add(e.surfaceId);
      out.push(e);
      if (out.length >= MAX_RECENTS) break;
    }
    return out;
  }

  private pick(entry: NavigationJournalEntry): void {
    this.open = false;
    if (this.dispatch) void navigateToEntry(entry.id, this.dispatch);
  }

  static styles = css`
    :host { position: relative; display: inline-flex; }
    .toggle {
      display: inline-flex; align-items: center; justify-content: center;
      background: transparent; border: none; color: inherit; cursor: pointer;
      padding: var(--space-1, 4px); border-radius: var(--radius-sm, 4px); font: inherit;
    }
    .toggle:hover { background: var(--surface-hover); }
    .menu {
      position: absolute; top: calc(100% + 4px); left: 0; z-index: var(--z-overlay-transient);
      min-width: 220px; max-width: 320px; padding: var(--space-1, 4px);
      background: var(--surface-raised, Canvas); color: var(--text-primary);
      border: 1px solid var(--border, rgba(127,127,127,0.3));
      border-radius: var(--radius-md, 8px); box-shadow: var(--shadow-menu);
    }
    .heading { font-size: var(--font-size-xs); opacity: 0.6; padding: 4px 8px; }
    .item {
      display: block; width: 100%; text-align: left; background: transparent; border: none;
      color: inherit; font: inherit; padding: 6px 8px; border-radius: var(--radius-sm, 4px); cursor: pointer;
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .item:hover, .item:focus-visible { background: var(--surface-hover); outline: 2px solid transparent; }
    .empty { padding: 6px 8px; opacity: 0.6; font-size: var(--font-size-sm); }
  `;

  override render(): TemplateResult {
    const recents = this.open ? this.recents() : [];
    return html`
      <button
        type="button"
        class="toggle"
        aria-haspopup="menu"
        aria-expanded=${this.open ? 'true' : 'false'}
        aria-label="Recent views"
        title="Recent views"
        @click=${() => (this.open = !this.open)}
        @keydown=${(e: KeyboardEvent) => {
          if (e.key === 'Escape') this.open = false;
        }}
      >
        ↺
      </button>
      ${this.open
        ? html`<div class="menu" role="menu">
            <div class="heading">Recent views</div>
            ${recents.length === 0
              ? html`<div class="empty">No recent views yet.</div>`
              : recents.map(
                  (e) => html`<button
                    type="button"
                    class="item"
                    role="menuitem"
                    @click=${() => this.pick(e)}
                  >
                    ${e.label || e.surfaceId}
                  </button>`,
                )}
          </div>`
        : nothing}
    `;
  }
}

if (!customElements.get('jf-recents-menu')) {
  customElements.define('jf-recents-menu', RecentsMenu);
}
