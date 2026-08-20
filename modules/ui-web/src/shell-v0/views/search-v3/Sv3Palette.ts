// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-palette — the Search v3 window's command palette (tempdoc 822 slice 4).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * WINDOW-SCOPED, not document-fixed. The design spec's palette is a real `<dialog>` in the browser's top
 * layer because it belongs to the whole app; this one belongs to a dev surface living INSIDE the
 * shipped shell, so a top-layer dialog would cover the shipped app's rail, topbar and status bar —
 * chrome this arc has no licence to touch. It is therefore absolutely positioned over the window host
 * and cannot escape it. (Slice 3's lesson points the same way: `backdrop-filter` creates a containing
 * block, so an ancestor with glass would already have trapped a `position: fixed` layer.)
 *
 * Two details from the design spec are the point of the whole component:
 *
 *  1. **Two different insets.** The input row sits at `--command-shell-inset` (8px) while the footer
 *     sits at `--command-content-inset` (16px). The field is tighter than the chrome around it, which
 *     is what makes it read as edge-to-edge rather than as another boxed control.
 *  2. **Selection and highlight are distinct, and only ONE fill ever shows.** Selection (6%) is the
 *     current choice; highlight (9%) is where the keyboard is. A row that is both takes the 9% — the
 *     precedence lives in the SELECTOR (`:not([data-highlighted])`), not in declaration order, so the
 *     palette cannot grow a second competing fill by an edit that merely reorders rules.
 *
 * Side-effect registers <jf-sv3-palette>.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { sv3Shared } from './sv3-shared-styles.js';
import {
  COMMAND_GROUPS,
  PALETTE_EMPTY,
  PALETTE_HINTS,
  PALETTE_PLACEHOLDER,
  type Sv3Command,
  type Sv3CommandGroup,
} from './fixtures.js';

/** Raised when a command is run; the window decides what a command means. */
export const SV3_PALETTE_RUN = 'sv3-palette-run';

export interface Sv3PaletteRun {
  readonly id: string;
}

const matches = (command: Sv3Command, query: string): boolean =>
  command.label.toLowerCase().includes(query);

export class Sv3Palette extends JfElement {
  static styles = [
    sv3Shared,
    css`
      /* Inert until opened: the layer renders nothing at all rather than hiding, so it cannot take a
         click, cannot hold a focusable node, and cannot become a second scroller in the window. */
      :host {
        display: none;
      }
      /* Sized by the window host it covers, which makes the region ITSELF the palette's viewport —
         cqh below then means "a share of the window", the honest re-expression of the spec's vh
         (a window-scoped overlay may not measure the browser viewport). */
      :host([open]) {
        display: block;
        position: absolute;
        inset: 0;
        z-index: var(--z-overlay);
        container-type: size;
        font-family: var(--font-sans);
      }

      /* Tempdoc 859 §B (D2/D3) — the scrim honours the shipped app's one blur multiplier like every
         other glass site in this window. Its FILL is deliberately left alone: a dialog scrim is a
         dimming layer, not a readable surface, and the "opaque or it is unreadable" rule is about
         content sitting ON glass. Zeroing the blur is the whole of what the preference asks for
         here; making the scrim opaque would hide the window behind it. */
      .backdrop {
        position: absolute;
        inset: 0;
        background: var(--dialog-backdrop);
        -webkit-backdrop-filter: blur(calc(var(--dialog-backdrop-blur) * var(--glass-blur-scale)));
        backdrop-filter: blur(calc(var(--dialog-backdrop-blur) * var(--glass-blur-scale)));
      }

      /* Top-anchored, not centred: a palette that grows downward keeps its first row under the eye. */
      .viewport {
        position: absolute;
        inset: 0;
        display: flex;
        flex-direction: column;
        align-items: center;
        padding-inline: var(--space-4);
        padding-block: max(var(--space-4), 10cqh);
        pointer-events: none;
      }

      .popup {
        pointer-events: auto;
        position: relative;
        display: flex;
        flex-direction: column;
        width: 100%;
        min-width: 0;
        min-height: 0;
        max-inline-size: var(--command-popup-max-width);
        max-block-size: var(--command-popup-max-height);
        border: 1px solid var(--dialog-border);
        border-radius: var(--radius-2xl);
        /* The dialog recipe, on ONE node (slice 3's lesson): a split silhouette reports no glass on
           whichever node carries the radius. */
        /* 859 §B (D2) — blur and translucency off ONE multiplier; see Sv3Composer's '.glass'. */
        background: color-mix(
          in srgb,
          var(--background) calc(100% - (100% - var(--glass-opacity)) * var(--glass-blur-scale)),
          transparent
        );
        -webkit-backdrop-filter: blur(calc(var(--glass-blur) * var(--glass-blur-scale)))
          saturate(var(--glass-saturation));
        backdrop-filter: blur(calc(var(--glass-blur) * var(--glass-blur-scale)))
          saturate(var(--glass-saturation));
        box-shadow: var(--dialog-shadow);
        color: var(--foreground);
        outline: none;
      }
      @supports not ((-webkit-backdrop-filter: blur(1px)) or (backdrop-filter: blur(1px))) {
        .popup {
          background: var(--background);
        }
      }

      /* ── The input row: the tighter of the two insets ─────────────────────── */
      .shell {
        flex-shrink: 0;
        padding-inline: var(--command-shell-inset);
        padding-block: var(--space-1-5);
      }
      .field {
        position: relative;
        display: flex;
        align-items: center;
      }
      /* The glyph is inset by the shell inset again and nudged half a step, so it optically centres
         over the field's own edge rather than sitting on the popup's. */
      .field-glyph {
        pointer-events: none;
        position: absolute;
        inset-block: 0;
        inset-inline-start: calc(var(--command-shell-inset) + 1px);
        display: flex;
        align-items: center;
        translate: 2px 0;
        color: var(--icon-muted);
        font-size: var(--font-size-sv3-base);
      }
      input {
        width: 100%;
        min-width: 0;
        height: var(--space-10);
        margin: 0;
        padding: 0;
        padding-inline-start: calc(var(--command-shell-inset) + var(--space-6));
        padding-inline-end: var(--space-2);
        border: 0;
        outline: none;
        background: transparent;
        color: var(--foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-base);
      }
      /* The spec strips the field's own ring inside the palette, and live measurement showed why the
         rule has to be explicit: the ambient :focus-visible sheet every shell component adopts is
         MORE specific than a bare element rule, so it painted a boxed control across the whole input
         row — undoing the edge-to-edge reading the tight shell inset exists to produce. The popup is
         the focused surface here; the field inside it is not a separate control. */
      input:focus-visible {
        outline: none;
      }

      /* ── The panel: the list's own clip, and the conditional bottom corners ──
         The spec makes the bottom corners follow the FOOTER's presence rather than a flag, so a
         palette assembled without a footer rounds off by itself. :has(+ .footer) is that rule
         re-expressed: structure decides, no state to keep in sync. */
      .panel {
        position: relative;
        display: flex;
        flex-direction: column;
        min-height: 0;
        overflow: hidden;
        border-start-start-radius: var(--radius-xl);
        border-start-end-radius: var(--radius-xl);
        background: transparent;
      }
      .panel:not(:has(+ .footer)) {
        border-end-start-radius: var(--radius-2xl);
        border-end-end-radius: var(--radius-2xl);
      }

      /* The scroll fade is a MASK, never an overlay gradient: an overlay only works while the chrome
         and the content share a background, and this list sits on glass. The seven-stop ramp is the
         spec's eased curve; the third layer keeps the scrollbar column at full opacity. */
      .list {
        flex: 1 1 auto;
        min-height: 0;
        padding: var(--space-2);
        scroll-padding-block: var(--space-2);
        mask-image:
          linear-gradient(
            to bottom,
            transparent 0%,
            rgb(0 0 0 / 10%) 10%,
            rgb(0 0 0 / 30%) 24%,
            rgb(0 0 0 / 58%) 42%,
            rgb(0 0 0 / 82%) 62%,
            rgb(0 0 0 / 96%) 82%,
            black 100%
          ),
          linear-gradient(black, black),
          linear-gradient(black, black);
        mask-position:
          top,
          bottom,
          right;
        mask-repeat: no-repeat;
        mask-size:
          100% var(--command-scroll-fade-height),
          100% calc(100% - var(--command-scroll-fade-height)),
          var(--app-scrollbar-width) 100%;
      }

      .group + .group {
        margin-top: var(--space-1-5);
      }
      .group-label {
        padding-inline: var(--space-2);
        padding-block: var(--space-1-5);
        color: var(--muted-foreground);
        font-size: var(--font-size-sv3-xs);
        font-weight: 500;
      }

      .separator {
        height: 1px;
        margin-inline: var(--space-2);
        margin-block: var(--space-2);
        background: var(--border);
      }
      /* A trailing rule would draw a line under the last group against nothing. */
      .separator:last-child {
        display: none;
      }

      .item {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        min-height: var(--space-7);
        padding-inline: var(--space-2);
        padding-block: var(--space-1-5);
        border-radius: var(--radius-sm);
        color: color-mix(in srgb, var(--foreground) 90%, transparent);
        font-size: var(--font-size-sv3-sm);
        cursor: default;
        user-select: none;
      }
      /* Selection is the current CHOICE and highlight is where the keyboard IS. A row that is both
         shows the highlight alone — the guard, not the source order, is what forbids two fills. */
      .item[data-selected]:not([data-highlighted]) {
        background: color-mix(in srgb, var(--foreground) 6%, transparent);
      }
      .item[data-highlighted] {
        background: color-mix(in srgb, var(--foreground) 9%, transparent);
        color: var(--foreground);
      }

      .item-label {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .shortcut {
        margin-inline-start: auto;
        color: var(--secondary-label);
        font-family: inherit;
        font-size: var(--font-size-sv3-xs);
        font-weight: 500;
        letter-spacing: 0.1em;
      }

      .empty {
        padding-inline: var(--space-2);
        padding-block: var(--space-6);
        color: var(--muted-foreground);
        font-size: var(--font-size-sv3-sm);
        text-align: center;
      }

      /* ── The footer: the wider of the two insets ──────────────────────────── */
      .footer {
        position: relative;
        flex-shrink: 0;
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--space-3);
        padding-inline: var(--command-content-inset);
        padding-block: var(--space-2-5);
        /* Inner radius: the popup's corner minus its own 1px border, or the fill would peek past it. */
        border-end-start-radius: calc(var(--radius-2xl) - 1px);
        border-end-end-radius: calc(var(--radius-2xl) - 1px);
        background: color-mix(in srgb, var(--foreground) 2.5%, transparent);
        color: var(--muted-foreground);
        font-size: var(--font-size-sv3-sm);
        font-weight: 500;
      }
      .hints {
        display: flex;
        align-items: center;
        gap: var(--space-3);
      }
      .hint {
        display: inline-flex;
        align-items: center;
        gap: var(--space-1-5);
        font-family: inherit;
      }
      .key {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        gap: var(--space-1);
        min-inline-size: var(--space-5);
        block-size: var(--space-5);
        padding-inline: var(--space-1);
        border-radius: var(--radius-sm);
        background: color-mix(in srgb, var(--foreground) 8%, transparent);
        color: var(--foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-xs);
        font-weight: 500;
        letter-spacing: 0.1em;
      }
    `,
  ];

  static properties = {
    open: { type: Boolean, reflect: true },
    query: { state: true },
    highlight: { state: true },
    chosen: { state: true },
  };

  declare open: boolean;
  declare query: string;
  /** Index into the FILTERED list — the keyboard's position, reset to the top on every query change. */
  declare highlight: number;
  /** The current choice. Seeded from the fixtures, then owned by whatever the user last ran. */
  declare chosen: string;

  /** Restored on close. Tracked here rather than via `document.activeElement`, which retargets to the
      window host across the shadow boundary and would hand focus to a non-focusable element. */
  private invoker: HTMLElement | null = null;

  constructor() {
    super();
    this.open = false;
    this.query = '';
    this.highlight = 0;
    this.chosen = COMMAND_GROUPS.flatMap((g) => g.commands).find((c) => c.selected)?.id ?? '';
  }

  /** The groups that survive the query, with empty groups dropped so no label stands over nothing. */
  private get groups(): readonly Sv3CommandGroup[] {
    const q = this.query.trim().toLowerCase();
    if (q === '') return COMMAND_GROUPS;
    return COMMAND_GROUPS.map((group) => ({
      ...group,
      commands: group.commands.filter((command) => matches(command, q)),
    })).filter((group) => group.commands.length > 0);
  }

  /** Render order, flattened — the axis the arrow keys move along. */
  private get visible(): readonly Sv3Command[] {
    return this.groups.flatMap((group) => group.commands);
  }

  async show(invoker: HTMLElement | null = null): Promise<void> {
    this.invoker = invoker;
    this.query = '';
    this.highlight = 0;
    this.open = true;
    await this.updateComplete;
    this.shadowRoot?.querySelector('input')?.focus();
  }

  hide(): void {
    if (!this.open) return;
    this.open = false;
    const invoker = this.invoker;
    this.invoker = null;
    // Focus must land somewhere deliberate, or it falls to <body> and the next Tab restarts the page.
    if (invoker !== null && invoker.isConnected) invoker.focus();
  }

  /**
   * Close because focus has already LEFT the window (`SearchV3View.onHostFocusOut`). The difference
   * from {@link hide} is the whole point: the reader did not dismiss this — something else took the
   * keyboard — so restoring the invoker would take it back off whatever now holds it, which is the
   * fight the shipped palette's Ctrl+K already starts.
   */
  dismiss(): void {
    if (!this.open) return;
    this.open = false;
    this.invoker = null;
  }

  private moveHighlight(delta: number): void {
    const count = this.visible.length;
    if (count === 0) return;
    this.highlight = (this.highlight + delta + count) % count;
  }

  private run(command: Sv3Command): void {
    this.chosen = command.id;
    this.dispatchEvent(
      new CustomEvent<Sv3PaletteRun>(SV3_PALETTE_RUN, {
        detail: { id: command.id },
        bubbles: true,
        composed: true,
      }),
    );
    this.hide();
  }

  private onInput(event: Event): void {
    this.query = (event.target as HTMLInputElement).value;
    this.highlight = 0;
  }

  private onKeydown(event: KeyboardEvent): void {
    switch (event.key) {
      case 'Escape':
        event.preventDefault();
        event.stopPropagation();
        this.hide();
        return;
      case 'ArrowDown':
        event.preventDefault();
        this.moveHighlight(1);
        return;
      case 'ArrowUp':
        event.preventDefault();
        this.moveHighlight(-1);
        return;
      case 'Home':
        event.preventDefault();
        this.highlight = 0;
        return;
      case 'End':
        event.preventDefault();
        this.highlight = Math.max(0, this.visible.length - 1);
        return;
      case 'Enter': {
        const command = this.visible[this.highlight];
        if (command === undefined) return;
        event.preventDefault();
        this.run(command);
        return;
      }
      case 'Tab':
        this.trapTab(event);
        return;
      default:
        return;
    }
  }

  /**
   * Focus may not leave an open palette. The spec's dialog gets this from the platform (`showModal`
   * makes the background `inert`); a window-scoped layer has to close the cycle itself, or Tab walks
   * out into the shipped shell's chrome behind the backdrop.
   */
  private trapTab(event: KeyboardEvent): void {
    const stops = [
      ...(this.shadowRoot?.querySelectorAll<HTMLElement>(
        '.popup input, .popup button, .popup [tabindex]:not([tabindex="-1"])',
      ) ?? []),
    ].filter((el) => !el.hasAttribute('disabled'));
    if (stops.length === 0) {
      event.preventDefault();
      return;
    }
    const edge = event.shiftKey ? stops[0] : stops[stops.length - 1];
    if (this.shadowRoot?.activeElement !== edge) return;
    event.preventDefault();
    (event.shiftKey ? stops[stops.length - 1] : stops[0])?.focus();
  }

  private renderItem(command: Sv3Command, index: number): TemplateResult {
    const highlighted = index === this.highlight;
    const selected = command.id === this.chosen;
    return html`
      <div
        class="item"
        role="option"
        id="sv3-palette-item-${command.id}"
        aria-selected=${selected ? 'true' : 'false'}
        ?data-selected=${selected}
        ?data-highlighted=${highlighted}
        data-testid="sv3-palette-item"
        @click=${() => this.run(command)}
        @pointermove=${() => {
          // The pointer MOVES the highlight rather than painting a third fill of its own — one fill,
          // one keyboard position, whichever device is driving.
          if (this.highlight !== index) this.highlight = index;
        }}
      >
        <span class="item-label">${command.label}</span>
        ${command.shortcut === undefined
          ? nothing
          : html`<kbd class="shortcut">${command.shortcut}</kbd>`}
      </div>
    `;
  }

  render(): TemplateResult | typeof nothing {
    if (!this.open) return nothing;
    const groups = this.groups;
    const visible = this.visible;
    const active = visible[this.highlight];
    let index = -1;
    return html`
      <div
        class="backdrop"
        data-testid="sv3-palette-backdrop"
        @click=${(): void => this.hide()}
      ></div>
      <div class="viewport">
        <div
          class="popup"
          role="dialog"
          aria-modal="true"
          aria-label="Command palette"
          data-testid="sv3-palette-popup"
          @keydown=${this.onKeydown}
        >
          <div class="shell">
            <div class="field">
              <span class="field-glyph" aria-hidden="true">&#9906;</span>
              <input
                type="text"
                role="combobox"
                aria-expanded="true"
                aria-controls="sv3-palette-list"
                aria-activedescendant=${active === undefined
                  ? ''
                  : `sv3-palette-item-${active.id}`}
                aria-label=${PALETTE_PLACEHOLDER}
                placeholder=${PALETTE_PLACEHOLDER}
                .value=${this.query}
                data-testid="sv3-palette-input"
                @input=${this.onInput}
              />
            </div>
          </div>
          <div class="panel" data-testid="sv3-palette-panel">
            <div
              class="list sv3-scroller"
              id="sv3-palette-list"
              role="listbox"
              aria-label="Commands"
              data-testid="sv3-palette-list"
            >
              ${visible.length === 0
                ? html`<div class="empty" data-testid="sv3-palette-empty">${PALETTE_EMPTY}</div>`
                : groups.map(
                    (group, groupIndex) => html`
                      <div class="group" role="group" aria-label=${group.label}>
                        <div class="group-label" data-testid="sv3-palette-group-label">
                          ${group.label}
                        </div>
                        ${group.commands.map((command) => {
                          index += 1;
                          return this.renderItem(command, index);
                        })}
                      </div>
                      ${groupIndex === groups.length - 1
                        ? nothing
                        : html`<div class="separator" data-testid="sv3-palette-separator"></div>`}
                    `,
                  )}
            </div>
          </div>
          <div class="footer" data-testid="sv3-palette-footer">
            <div class="hints">
              ${PALETTE_HINTS.map(
                (hint) => html`
                  <kbd class="hint" data-testid="sv3-palette-hint">
                    ${hint.keys.map((key) => html`<kbd class="key">${key}</kbd>`)}
                    <span>${hint.label}</span>
                  </kbd>
                `,
              )}
            </div>
          </div>
        </div>
      </div>
    `;
  }
}

customElements.define('jf-sv3-palette', Sv3Palette);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-palette': Sv3Palette;
  }
}
