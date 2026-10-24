// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-sidebar — the Search v3 window's left panel (tempdoc 822 slice 1).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * Two-level inset: the panel insets its rows by `--sidebar-content-inset`, then each row insets its
 * own content by `--sidebar-row-content-inset`. The hover fill therefore starts at the panel inset
 * and reads as a pill, not as a full-bleed band — which is the whole point of the second level.
 *
 * One surface model for every row: surface encodes INTERACTION (hover, selection), content encodes
 * status — the row itself is <jf-sv3-session-row>; this panel owns only the grouping.
 *
 * Groups are the SHELVES the window projected (Active / Pinned / Recent — tempdoc 822 Phase F3) and
 * their order is fixed: activity never reorders rows inside a shelf, so a row does not move out from
 * under the pointer while you are reading it.
 *
 * The panel renders what it is GIVEN (`groups`) and decides nothing: the sessions are the window's
 * (`sv3-sessions.ts`), and a click is announced upward rather than acted on here — the window owns
 * the one search issuance, so the panel must not grow a second one.
 *
 * Side-effect registers <jf-sv3-sidebar>.
 */
import { html, css, nothing, type TemplateResult, type PropertyValues } from 'lit';
import { repeat } from 'lit/directives/repeat.js';
import { deepActiveElement } from '../../utils/keyboardHandler.js';
import { JfElement } from '../../primitives/JfElement.js';
import { icon } from '../../components/Icon.js';
import { sv3Shared } from './sv3-shared-styles.js';
import './Sv3SessionRow.js';
import type { Sv3SessionRow } from './Sv3SessionRow.js';
import './Sv3Empty.js';
import { SIDEBAR_EMPTY } from './fixtures.js';
import type { Sv3SessionGroup } from './sv3-sessions.js';

/** Asks the window to re-run a session; the window owns the search, so the panel only names one. */
export const SV3_SESSION_SELECT = 'sv3-session-select';

export interface Sv3SessionSelect {
  readonly id: string;
}

/** Asks the window to pin or unpin a session — the row raises it, the panel says which row. */
export const SV3_SESSION_PIN = 'sv3-session-pin';

export interface Sv3SessionPin {
  readonly id: string;
}

/**
 * Asks the window to discard a session (tempdoc 831) — the row raises it, the panel says which row,
 * and the window is the only thing that may act: existence is the conversation store's, so a delete
 * is a write-through the panel has no business making.
 */
export const SV3_SESSION_REMOVE = 'sv3-session-remove';

export interface Sv3SessionRemove {
  readonly id: string;
}

/** Asks the window to start a fresh session — back to the hero, previous sessions kept. */
export const SV3_SESSION_NEW = 'sv3-session-new';

/** Asks the window to collapse the panel to its icon rail, or to put it back (Phase F5). */
export const SV3_SIDEBAR_TOGGLE = 'sv3-sidebar-toggle';

/**
 * The rename triad, re-raised with the row's id attached (Phase F5). The row knows it is being
 * renamed; only the panel knows WHICH row that is.
 */
export const SV3_SESSION_RENAME = 'sv3-session-rename';

export interface Sv3SessionRename {
  readonly id: string;
  /** The committed title, or null for "start editing this row" / "cancel". */
  readonly title: string | null;
  readonly phase: 'start' | 'commit' | 'cancel';
}

const NEW_SESSION_LABEL = 'New search';

/** One name for both directions — the design spec's own, plus `aria-pressed`. */
const COLLAPSE_LABEL = 'Toggle sidebar';

/** The spec's menu-button glyph size (`size-4`), which is what the header control is. */
const NEW_SESSION_GLYPH_SIZE = 16;

export class Sv3Sidebar extends JfElement {
  static styles = [
    sv3Shared,
    css`
      :host {
        display: flex;
        flex-direction: column;
        min-height: 0;
        overflow: hidden;
        padding: var(--sidebar-content-inset);
        background: var(--sidebar);
        color: var(--sidebar-foreground);
        border-right: 1px solid var(--sidebar-border);
        font-family: var(--font-sans);
      }
      .group-label {
        display: flex;
        align-items: center;
        height: var(--space-8);
        padding-inline: var(--space-2);
        font-size: var(--font-size-sv3-xs);
        font-weight: 500;
        color: var(--sidebar-muted-foreground);
        /* The spec's icon-mode treatment of a group label, verbatim
           (-mt-8 opacity-0, over transition-[margin,opacity] duration-200 ease-linear): it fades AND
           pulls its own height back out of the column, so the rows close up without a reflow step. */
        transition:
          margin-top var(--duration-sv3-layout) var(--ease-sv3-linear),
          opacity var(--duration-sv3-layout) var(--ease-sv3-linear);
      }
      :host([collapsed]) .group-label {
        margin-top: calc(-1 * var(--space-8));
        opacity: 0;
        pointer-events: none;
      }
      /* The header is a row that becomes a stack: two 32px squares on the icon rail, which is the
         only shape the spec's size-8 icon-mode control fits into at 48px minus two 8px insets. */
      .header {
        display: flex;
        align-items: center;
        gap: var(--space-1);
        margin-bottom: var(--space-1);
      }
      :host([collapsed]) .header {
        flex-direction: column;
        gap: var(--space-1);
      }
      .groups {
        display: flex;
        flex-direction: column;
        min-height: 0;
      }
      .rows {
        display: flex;
        flex-direction: column;
        gap: var(--space-1);
        min-height: 0;
      }
      /* The spec's menu-button DEFAULT rung (h-8, --control-radius, the row content inset,
         text-sm) — a header control is a menu button, not a session row (36px). */
      button.new-session,
      button.collapse-toggle {
        display: flex;
        align-items: center;
        gap: var(--sidebar-control-gap);
        height: var(--space-8);
        padding-inline: var(--sidebar-row-content-inset);
        padding-block: 0;
        border: 0;
        border-radius: var(--control-radius);
        background: transparent;
        color: var(--sidebar-foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-sm);
        font-weight: 500;
        text-align: left;
        cursor: pointer;
        flex-shrink: 0;
        transition: background-color var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      button.new-session {
        flex: 1 1 auto;
        min-width: 0;
      }
      button.collapse-toggle {
        justify-content: center;
        inline-size: var(--space-8);
        padding-inline: 0;
        flex: 0 0 auto;
      }
      button.new-session:hover,
      button.collapse-toggle:hover {
        background: var(--sidebar-row-hover);
      }
      button.new-session:focus-visible,
      button.collapse-toggle:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: -2px;
      }
      /* Per the design spec: the glyph dims while the label stays full contrast. */
      button.new-session svg,
      button.collapse-toggle svg {
        flex-shrink: 0;
        color: var(--icon-muted);
      }
      /* The spec's icon-mode control: a 32px square with the label truncated away
         (size-8 plus [&>span:last-child]:truncate). */
      :host([collapsed]) button.new-session {
        flex: 0 0 auto;
        inline-size: var(--space-8);
        justify-content: center;
        padding-inline: 0;
      }
      :host([collapsed]) button.new-session span {
        display: none;
      }
      :host([collapsed]) jf-sv3-empty {
        display: none;
      }
      @media (prefers-reduced-motion: reduce) {
        button.new-session,
        button.collapse-toggle,
        .group-label {
          transition: none;
        }
      }
    `,
  ];

  static properties = {
    groups: { attribute: false },
    collapsed: { type: Boolean, reflect: true },
    renamingId: { attribute: false },
    announcement: { state: true },
  };

  /** The window's projected session groups; empty means nothing has been searched in this window. */
  declare groups: readonly Sv3SessionGroup[];
  /** The panel is on its 3rem icon rail (Phase F5). The WIDTH is the window's — this is the mode. */
  declare collapsed: boolean;
  /** Which row is being renamed, or null. At most one: an edit is where the reader's attention is. */
  declare renamingId: string | null;
  /**
   * What the polite live region is currently saying (tempdoc 831). A discard removes a row with no
   * visible confirmation and no sound; a reader who cannot see the list disappear is otherwise told
   * nothing at all that it happened.
   */
  declare announcement: string;

  /**
   * The discard this panel has asked for and not yet seen land. Keep its label for the announcement
   * after the authority removes it; focus is decided from who holds it when the removal lands.
   */
  private pendingDiscard: {
    readonly id: string;
    readonly label: string;
  } | null = null;

  constructor() {
    super();
    this.groups = [];
    this.collapsed = false;
    this.renamingId = null;
    this.announcement = '';
  }

  /** Every row the panel is showing, in render order — shelves flattened, which is the tab order. */
  private flatRows(): readonly Sv3SessionGroup['rows'][number][] {
    return this.groups.flatMap((group) => group.rows);
  }

  private startNew(): void {
    this.dispatchEvent(new CustomEvent(SV3_SESSION_NEW, { bubbles: true, composed: true }));
  }

  private select(id: string): void {
    this.dispatchEvent(
      new CustomEvent<Sv3SessionSelect>(SV3_SESSION_SELECT, {
        detail: { id },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /** The row's pin request, named. The panel decides nothing about it — the list is the window's. */
  private pin(id: string, event: Event): void {
    event.stopPropagation();
    this.dispatchEvent(
      new CustomEvent<Sv3SessionPin>(SV3_SESSION_PIN, {
        detail: { id },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * The row's discard request, named. Whether it is ALLOWED is the window's call, not the panel's.
   * `discard` rather than `remove`: the latter would shadow `Element.remove()` on this host.
   *
   * The panel announces a confirmed removal. Keyboard ownership is checked at render time, since
   * the reader may have returned to typing while the deletion was in flight.
   */
  private discard(id: string, event: Event): void {
    event.stopPropagation();
    const rows = this.flatRows();
    const at = rows.findIndex((row) => row.id === id);
    this.pendingDiscard = {
      id,
      label: rows[at]?.label ?? '',
    };
    // EMPTIED on the request, filled when the removal lands. A live region speaks on MUTATION, so
    // discarding two conversations that happen to share a title would set the same string twice and
    // announce the second one to nobody.
    this.announcement = '';
    this.dispatchEvent(
      new CustomEvent<Sv3SessionRemove>(SV3_SESSION_REMOVE, {
        detail: { id },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /** Keep focus outside removed rows untouched, including rows removed by another window. */
  protected override willUpdate(changed: PropertyValues<this>): void {
    const rows = this.flatRows();
    const previous = changed.get('groups');
    if (previous) {
      const oldRows = previous.flatMap(group => group.rows);
      const focused = deepActiveElement();
      const root = focused?.getRootNode();
      const host = root instanceof ShadowRoot ? root.host : null;
      const rendered = [...(this.shadowRoot?.querySelectorAll('jf-sv3-session-row') ?? [])];
      const at = rendered.indexOf(host as Sv3SessionRow);
      const removed = oldRows[at];
      if (focused && removed && !rows.some(row => row.id === removed.id)) {
        const survivor = [...oldRows.slice(at + 1), ...oldRows.slice(0, at).reverse()]
          .find(old => rows.some(row => row.id === old.id));
        this.focusAfterDiscard = { id: survivor?.id ?? null, from: focused };
        this.announcement = removed.label === '' ? 'Conversation deleted' : `${removed.label} deleted`;
      }
    }
    const pending = this.pendingDiscard;
    if (pending && !rows.some(row => row.id === pending.id)) {
      this.pendingDiscard = null;
      this.announcement = pending.label === '' ? 'Conversation deleted' : `${pending.label} deleted`;
    }
  }

  private focusAfterDiscard: { id: string | null; from: Element } | null = null;

  protected override updated(): void {
    const pending = this.focusAfterDiscard;
    this.focusAfterDiscard = null;
    if (pending) void this.placeFocusAfterDiscard(pending.id, pending.from);
  }

  private async placeFocusAfterDiscard(survivorId: string | null, from: Element): Promise<void> {
    const index = this.flatRows().findIndex(row => row.id === survivorId);
    const rows = [...(this.shadowRoot?.querySelectorAll<Sv3SessionRow>('jf-sv3-session-row') ?? [])];
    const row = index < 0 ? undefined : rows[index];
    if (row) await row.updateComplete;
    // An awaited row update must not take focus back from a reader who has already moved on.
    const focused = deepActiveElement();
    if (focused !== from && focused !== this.ownerDocument.body) return;
    if (row?.isConnected) row.focusRow();
    else this.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-sidebar-announcer"]')?.focus();
  }

  private toggleCollapsed(event: Event): void {
    // Preserve the actual opener across the shadow boundary for sheet focus restoration.
    (event.currentTarget as HTMLElement).dispatchEvent(
      new CustomEvent(SV3_SIDEBAR_TOGGLE, { bubbles: true, composed: true }),
    );
  }

  /** The row's rename intent, named. The panel supplies the id and decides nothing else. */
  private rename(id: string, phase: Sv3SessionRename['phase'], event: Event): void {
    event.stopPropagation();
    const title =
      phase === 'commit' ? ((event as CustomEvent<{ title: string }>).detail?.title ?? '') : null;
    this.dispatchEvent(
      new CustomEvent<Sv3SessionRename>(SV3_SESSION_RENAME, {
        detail: { id, phase, title },
        bubbles: true,
        composed: true,
      }),
    );
  }

  render(): TemplateResult {
    return html`
      <div class="header">
        <button
          type="button"
          class="new-session"
          title=${this.collapsed ? NEW_SESSION_LABEL : nothing}
          aria-label=${this.collapsed ? NEW_SESSION_LABEL : nothing}
          data-testid="sv3-sidebar-new"
          @click=${this.startNew}
        >
          ${icon({ name: 'search', size: NEW_SESSION_GLYPH_SIZE })}
          <span>${NEW_SESSION_LABEL}</span>
        </button>
        <button
          type="button"
          class="collapse-toggle"
          aria-pressed=${this.collapsed ? 'true' : 'false'}
          aria-label=${COLLAPSE_LABEL}
          title=${COLLAPSE_LABEL}
          data-testid="sv3-sidebar-collapse"
          @click=${this.toggleCollapsed}
        >
          ${icon({
            name: this.collapsed ? 'chevron-right' : 'chevron-left',
            size: NEW_SESSION_GLYPH_SIZE,
          })}
        </button>
      </div>
      ${this.groups.length === 0
        ? html`
            <jf-sv3-empty
              data-testid="sv3-sidebar-empty"
              glyph="&#9634;"
              heading=${SIDEBAR_EMPTY.title}
              description=${SIDEBAR_EMPTY.description}
            ></jf-sv3-empty>
          `
        : html`
            <div class="groups">
              ${repeat(this.groups, (group) => group.id,
                (group) => html`
                  <div class="group" data-testid="sv3-sidebar-group">
                    <div class="group-label" data-testid="sv3-sidebar-group-label">
                      ${group.label}
                    </div>
                    <div class="rows">
                      ${repeat(group.rows, (row) => row.id,
                        (row) => html`
                          <jf-sv3-session-row
                            data-testid="sv3-sidebar-row"
                            .label=${row.label}
                            .meta=${row.meta}
                            status=${row.status}
                            ?active=${row.active}
                            ?pinned=${row.pinned}
                            ?unread=${row.unread}
                            ?live=${row.live}
                            .storeBacked=${row.storeBacked}
                            ?compact=${this.collapsed}
                            ?renaming=${this.renamingId === row.id}
                            @click=${() => this.select(row.id)}
                            @sv3-session-pin-toggle=${(event: Event) => this.pin(row.id, event)}
                            @sv3-session-remove-request=${(event: Event) =>
                              this.discard(row.id, event)}
                            @sv3-session-rename-start=${(event: Event) =>
                              this.rename(row.id, 'start', event)}
                            @sv3-session-rename-commit=${(event: Event) =>
                              this.rename(row.id, 'commit', event)}
                            @sv3-session-rename-cancel=${(event: Event) =>
                              this.rename(row.id, 'cancel', event)}
                          ></jf-sv3-session-row>
                        `,
                      )}
                    </div>
                  </div>
                `,
              )}
            </div>
          `}
      ${/* The panel's one polite announcement channel (tempdoc 831). A LEAF at the end of the
           panel, holding text and nothing else: a live region wrapped around controls re-announces
           the whole subtree every time any of it re-renders, which turns a screen reader into
           noise. Only this node's text ever changes. */ ''}
      <div
        class="visually-hidden"
        role="status"
        aria-live="polite"
        aria-atomic="true"
        data-testid="sv3-sidebar-announcer"
        tabindex="-1"
      >
        ${this.announcement}
      </div>
    `;
  }
}

customElements.define('jf-sv3-sidebar', Sv3Sidebar);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-sidebar': Sv3Sidebar;
  }
}
