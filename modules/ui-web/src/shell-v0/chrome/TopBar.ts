// SPDX-License-Identifier: Apache-2.0
/**
 * `<jf-top-bar>` — the app frame's one bar (shell slice 2, design-system §2 and §7).
 *
 * Left: the brand. Centre: the switcher between the three areas (Search, Library, AI Brain).
 * Right: the status pill (slotted by the Shell, which owns it), Settings and the More (⋯) menu.
 *
 * The bar owns presentation and pointer/keyboard interaction only; every choice is reported to the
 * Shell as a `top-bar-select` event and the Shell navigates through the one intent router, so the
 * address, the journal and the stage stay one flow. Area buttons and menu destinations carry
 * `data-surface-id` (the harness and saved selectors address windows by it; one element per id).
 *
 * Sizes, read from the bar's own room (after the desktop window controls) in rem, so enlarged text
 * counts: below 62.5rem only the active area keeps its visible label (the others keep their name as
 * the accessible name and tooltip); below 45rem the minimal bar shows the brand dot, one button
 * naming the active area that opens the area menu, the pill as an icon, Settings and More; below
 * 30rem that button keeps only its icon and the switcher leaves the centre.
 *
 * Desktop: the bar's background moves the window (Tauri drag region) and its end keeps room for the
 * window layer's controls beside it; the switcher, the status slot and the menus never start a drag.
 */
import { html, css, nothing, type TemplateResult, type PropertyValues } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { isMac } from '../commands/KeybindingRegistry.js';
import { TransientController } from '../primitives/transientController.js';
import { icon, type IconName } from '../components/Icon.js';
import { hasWindowLayer } from '../../utils/windowLayer.js';
import { cssLength, shiftIntoWindow } from './menuPlacement.js';

/** One place the bar can open: an area in the switcher, or a window listed in the More menu. */
export interface TopBarDestination {
  readonly id: string;
  readonly label: string;
  readonly icon: IconName;
  /** Keyboard shortcut in the app's words ("Ctrl+1"); shown in the tooltip, named for assistive tech. */
  readonly shortcut?: string;
  /** Secondary words beside a menu row ("Health, Activity, Logs"). */
  readonly detail?: string;
}

/** What the reader chose in the bar. */
export type TopBarSelection =
  | { readonly kind: 'area'; readonly id: string }
  | { readonly kind: 'window'; readonly id: string }
  | { readonly kind: 'settings' }
  | { readonly kind: 'copy-link' }
  | { readonly kind: 'logs' };

type OpenMenu = 'more' | 'areas' | null;

/** "Ctrl+1" → "Control+1" (aria-keyshortcuts names modifiers by their key names). */
function ariaShortcut(shortcut: string): string {
  return shortcut.replace(/^Ctrl\+/, 'Control+');
}

export class TopBar extends JfElement {
  static override properties = {
    areas: { attribute: false },
    activeId: { attribute: 'active-id' },
    switcherVisible: { type: Boolean, attribute: false },
    system: { attribute: false },
    help: { attribute: false },
    about: { attribute: false },
    windows: { attribute: false },
    developerWindows: { attribute: false },
    developer: { type: Boolean, attribute: false },
    backendAddress: { attribute: false },
    openMenu: { state: true },
    showBackend: { state: true },
  };
  declare areas: readonly TopBarDestination[];
  declare activeId: string;
  /** A saved layout's `rail.visible:false` hides the switcher; the areas stay reachable by keyboard. */
  declare switcherVisible: boolean;
  declare system: TopBarDestination | null;
  declare help: TopBarDestination | null;
  declare about: TopBarDestination | null;
  /** Plugin windows (catalog contributions outside the three areas): ⋯ › More. */
  declare windows: readonly TopBarDestination[];
  /** Developer windows (API explorer): ⋯ › Developer, while Show developer tools is on. */
  declare developerWindows: readonly TopBarDestination[];
  declare developer: boolean;
  declare backendAddress: string;
  declare openMenu: OpenMenu;
  declare showBackend: boolean;

  private readonly transient = new TransientController(this, {
    layer: 'transient', id: 'top-bar-menu', managesDismiss: true, close: () => this.closeMenu(),
  });

  constructor() {
    super();
    this.areas = [];
    this.activeId = '';
    this.switcherVisible = true;
    this.system = null;
    this.help = null;
    this.about = null;
    this.windows = [];
    this.developerWindows = [];
    this.developer = false;
    this.backendAddress = '';
    this.openMenu = null;
    this.showBackend = false;
  }

  static override styles = css`
    /* The bar is the container its layout reads: the room left after the desktop window controls,
       in rem so enlarged text picks a smaller layout. In the browser that room is the window width. */
    :host {
      display: block;
      container-type: inline-size;
      box-sizing: border-box;
      min-width: 0;
      height: var(--frame-topbar-height);
      padding-inline-end: var(--window-controls-inline, 0rem);
      background: var(--surface-1);
      border-bottom: var(--frame-hairline) solid var(--border-subtle);
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    /* Equal side tracks keep the switcher centred; the status notice gives way inside its track. */
    .bar {
      --top-bar-padding: var(--jf-space-6);
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
      align-items: center;
      gap: var(--jf-space-3);
      box-sizing: border-box;
      height: 100%;
      padding-inline: var(--top-bar-padding);
    }
    .start, .end { display: flex; align-items: center; min-width: 0; }
    .start { gap: var(--jf-space-3); }
    .end { justify-content: flex-end; gap: var(--jf-space-2); }
    .brand { display: inline-flex; align-items: center; gap: var(--jf-space-3); flex: none;
      color: var(--text-primary); font-size: var(--font-size-md); font-weight: var(--jf-weight-semibold); white-space: nowrap; }
    .dot { inline-size: var(--frame-brand-dot); block-size: var(--frame-brand-dot); border-radius: var(--frame-pill-radius);
      background: var(--text-secondary); box-shadow: 0 0 0 var(--frame-brand-halo) var(--surface-active); flex: none; }
    nav { display: flex; align-items: center; justify-content: center; }
    nav[hidden] + .end { grid-column: 3; }
    .segments { display: flex; align-items: center; gap: var(--frame-hairline); padding: var(--jf-space-1);
      border: var(--frame-hairline) solid var(--border-subtle); border-radius: var(--frame-pill-radius); }
    button { font: inherit; color: inherit; background: transparent; border: 0; cursor: pointer; }
    button:focus-visible { outline: var(--frame-focus-width) solid var(--focus-ring-color); outline-offset: var(--frame-hairline); }
    .segment, .area-trigger { display: inline-flex; align-items: center; gap: var(--jf-space-2);
      padding: var(--jf-space-2) var(--jf-space-4); border-radius: var(--frame-pill-radius);
      font-weight: var(--jf-weight-medium); white-space: nowrap; }
    .segment:hover { color: var(--text-primary); }
    .segment[aria-current='page'], .area-trigger { background: var(--surface-2); color: var(--text-primary); }
    .segment[aria-current='page'] svg, .area-trigger svg { color: var(--text-secondary); }
    .area-trigger { display: none; }
    .icon-button { display: inline-grid; place-items: center; inline-size: var(--frame-control-size);
      block-size: var(--frame-control-size); border-radius: var(--frame-control-radius); flex: none; }
    .icon-button:hover, .icon-button[aria-expanded='true'] { background: var(--surface-2); color: var(--text-primary); }
    /* The pill may give way only through the notice beside it (which wraps); its own words never shrink. */
    ::slotted([slot='status']) { flex: 0 1 auto; min-width: 0; }
    .menu-anchor { position: relative; flex: none; }
    /* Under its button; placeMenu() moves it along the bar only as far as keeps it inside the window. */
    .menu { position: absolute; top: calc(100% + var(--jf-space-2)); z-index: var(--z-overlay-transient);
      translate: var(--top-bar-menu-shift, 0px) 0;
      min-inline-size: min(var(--frame-menu-width), 100vw - 2 * var(--jf-space-2)); max-inline-size: calc(100vw - 2 * var(--jf-space-2));
      max-block-size: calc(100dvh - var(--frame-topbar-height) - var(--jf-space-4)); overflow-y: auto;
      box-sizing: border-box; padding: var(--jf-space-2); background: var(--surface-1); color: var(--text-primary);
      border: var(--frame-hairline) solid var(--border-emphasized); border-radius: var(--frame-panel-radius);
      box-shadow: var(--shadow-float); text-align: start; }
    .menu.more { inset-inline-end: 0; }
    .menu.areas { inset-inline-start: 0; }
    .group + .group { margin-top: var(--jf-space-2); padding-top: var(--jf-space-2); border-top: var(--frame-hairline) solid var(--border-subtle); }
    .group-label { display: flex; justify-content: space-between; gap: var(--jf-space-3);
      padding: var(--jf-space-1) var(--jf-space-3); color: var(--text-secondary); font-size: var(--font-size-xs); }
    [role='menuitem'], [role='menuitemradio'] { display: flex; align-items: center; gap: var(--jf-space-3); inline-size: 100%;
      box-sizing: border-box; min-block-size: var(--frame-control-size); padding: var(--jf-space-1) var(--jf-space-3);
      border-radius: var(--frame-control-radius); text-align: start; color: var(--text-primary); }
    [role='menuitem']:hover, [role='menuitem']:focus-visible, [role='menuitemradio']:hover, [role='menuitemradio']:focus-visible { background: var(--surface-2); }
    [role='menuitemradio'][aria-checked='true'] svg { color: var(--text-secondary); }
    .item-label { flex: 1; min-width: 0; }
    .item-detail, kbd { color: var(--text-secondary); font-size: var(--font-size-xs); white-space: nowrap; }
    kbd { font: inherit; font-size: var(--font-size-xs); padding: 0 var(--jf-space-1);
      border: var(--frame-hairline) solid var(--border-subtle); border-radius: var(--frame-control-radius); }
    .backend { padding: var(--jf-space-1) var(--jf-space-3); color: var(--text-secondary); font-family: var(--font-mono, monospace);
      font-size: var(--font-size-xs); overflow-wrap: anywhere; }
    .visually-hidden { position: absolute; inline-size: var(--frame-hairline); block-size: var(--frame-hairline);
      overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
    [hidden] { display: none !important; }
    /* Below 1000 px of room (62.5rem) only the active area keeps its visible label. */
    @container (max-width: 62.4375rem) {
      .bar { --top-bar-padding: var(--jf-space-4); }
      .segment:not([aria-current='page']) .label { position: absolute; inline-size: var(--frame-hairline);
        block-size: var(--frame-hairline); overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
      .segment:not([aria-current='page']) { padding-inline: var(--jf-space-3); }
    }
    /* Minimal bar below 720 px of room (45rem; 600 × 400): brand dot, the active area as a menu, pill
       icon, Settings, More. */
    @container (max-width: 44.9375rem) {
      .bar { gap: var(--jf-space-2); --top-bar-padding: var(--jf-space-3); }
      .brand .name { position: absolute; inline-size: var(--frame-hairline); block-size: var(--frame-hairline);
        overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
      .segments { display: none; }
      .area-trigger { display: inline-flex; }
      .end { gap: var(--jf-space-1); }
    }
    /* Smallest bar below 30rem of room (600 px window with enlarged text): the switcher leaves the
       centre and its button keeps the icon; the area's name stays its accessible name. */
    @container (max-width: 29.9375rem) {
      .bar { grid-template-columns: auto auto minmax(0, 1fr); }
      .area-trigger { padding-inline: var(--jf-space-2); }
      .area-trigger .trigger-label { position: absolute; inline-size: var(--frame-hairline); block-size: var(--frame-hairline);
        overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
    }
    @media (forced-colors: active) {
      :host { border-bottom-color: CanvasText; }
      .dot { background: Highlight; box-shadow: none; forced-color-adjust: none; }
      .segments { border-color: ButtonText; }
      .segment[aria-current='page'], .area-trigger, [role='menuitemradio'][aria-checked='true'] {
        forced-color-adjust: none; background: Highlight; color: HighlightText; }
      .segment[aria-current='page'] svg, .area-trigger svg, [role='menuitemradio'][aria-checked='true'] svg { color: HighlightText; }
      .menu { border-color: CanvasText; }
      [role='menuitem']:focus-visible, [role='menuitemradio']:focus-visible { outline: var(--frame-focus-width) solid Highlight; }
    }
  `;

  /** The bar's own size and the area menu's button (only the minimal bar has one) as they come and go. */
  private sizes: ResizeObserver | null = null;
  /** Interactive parts opt out of the bar's drag region (only present with the window layer). */
  private readonly noDrag = hasWindowLayer() ? 'false' : nothing;

  override connectedCallback(): void {
    super.connectedCallback();
    if (hasWindowLayer()) this.setAttribute('data-tauri-drag-region', 'deep');
    this.sizes = typeof ResizeObserver === 'function' ? new ResizeObserver(this.resized) : null;
    this.sizes?.observe(this);
    if (this.hasUpdated) this.observeTrigger();
  }

  override disconnectedCallback(): void {
    this.sizes?.disconnect();
    this.sizes = null;
    super.disconnectedCallback();
  }

  protected override firstUpdated(): void {
    this.observeTrigger();
  }

  private observeTrigger(): void {
    const trigger = this.shadowRoot?.querySelector('.area-trigger');
    if (trigger) this.sizes?.observe(trigger);
  }

  /**
   * The window or the text size changed. An open menu is placed again. When the bar gained room past
   * the minimal layout, the area menu's button is gone, so its menu closes rather than staying open
   * apart from it; focus inside it moves to the active area in the switcher.
   */
  private readonly resized = (): void => {
    const trigger = this.shadowRoot?.querySelector('.area-trigger');
    if (this.openMenu === 'areas' && trigger && trigger.getClientRects().length === 0) {
      const focusInside = this.shadowRoot?.activeElement?.closest('[role="menu"]') != null;
      this.closeMenu(false);
      if (focusInside) this.shadowRoot?.querySelector<HTMLButtonElement>('.segment[aria-current="page"]')?.focus();
      return;
    }
    this.placeMenu();
  };

  /** The one placement of the bar's menus: see {@link shiftIntoWindow}. */
  private placeMenu(): void {
    const menu = this.shadowRoot?.querySelector<HTMLElement>('.menu');
    if (!menu) return;
    this.style.setProperty('--top-bar-menu-shift', '0px');
    const shift = shiftIntoWindow(menu.getBoundingClientRect(), cssLength(this, '--jf-space-2'));
    this.style.setProperty('--top-bar-menu-shift', `${shift}px`);
  }

  protected override updated(changed: PropertyValues): void {
    // Any change while a menu is open (rows added from another window, the backend address shown) can change its width.
    this.placeMenu();
    if (changed.has('openMenu')) {
      if (this.openMenu) {
        this.transient.open();
        this.menuItems()[0]?.focus();
      } else {
        this.transient.close();
      }
    }
  }

  private select(selection: TopBarSelection): void {
    this.closeMenu(false);
    this.dispatchEvent(new CustomEvent<TopBarSelection>('top-bar-select', {
      detail: selection, bubbles: true, composed: true,
    }));
  }

  private toggleMenu(menu: Exclude<OpenMenu, null>): void {
    if (this.openMenu === menu) this.closeMenu();
    else this.openMenu = menu;
  }

  /** Close the open menu; focus returns to its button when it was inside the menu. */
  closeMenu(restoreFocus = true): void {
    if (!this.openMenu) return;
    const menu = this.openMenu;
    const focusInside = this.shadowRoot?.activeElement?.closest('[role="menu"]') != null;
    this.openMenu = null;
    this.showBackend = false;
    if (restoreFocus && focusInside) {
      this.shadowRoot?.querySelector<HTMLButtonElement>(menu === 'more' ? '.more-trigger' : '.area-trigger')?.focus();
    }
  }

  private menuItems(): HTMLElement[] {
    return [...(this.shadowRoot?.querySelectorAll<HTMLElement>('[role="menu"] [role^="menuitem"]') ?? [])];
  }

  /** Roving arrow-key movement inside a menu; Escape closes and returns focus; Tab leaves and closes. */
  private onMenuKeydown(e: KeyboardEvent): void {
    const items = this.menuItems();
    const current = items.indexOf(this.shadowRoot?.activeElement as HTMLElement);
    let next: number | null = null;
    if (e.key === 'ArrowDown') next = (current + 1) % items.length;
    else if (e.key === 'ArrowUp') next = (current - 1 + items.length) % items.length;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = items.length - 1;
    else if (e.key === 'Escape') { e.preventDefault(); this.closeMenu(); return; }
    else if (e.key === 'Tab') { this.closeMenu(false); return; }
    if (next === null || items.length === 0) return;
    e.preventDefault();
    items[next]?.focus();
  }

  private segment(area: TopBarDestination): TemplateResult {
    const active = area.id === this.activeId;
    const tooltip = area.shortcut ? `${area.label} · ${area.shortcut}` : area.label;
    return html`<button type="button" class="segment" data-surface-id=${area.id} title=${tooltip}
      aria-label=${area.label} aria-current=${active ? 'page' : nothing}
      aria-keyshortcuts=${area.shortcut ? ariaShortcut(area.shortcut) : nothing}
      @click=${() => this.select({ kind: 'area', id: area.id })}>
      ${icon({ name: area.icon, size: 16 })}<span class="label">${area.label}</span></button>`;
  }

  private menuRow(destination: TopBarDestination, kind: 'window' | 'area'): TemplateResult {
    const checked = kind === 'area' && destination.id === this.activeId;
    // Area rows repeat the switcher's destinations, so they do not repeat its `data-surface-id`.
    return html`<button type="button" role=${kind === 'area' ? 'menuitemradio' : 'menuitem'} tabindex="-1"
      data-surface-id=${kind === 'window' && destination.id.includes('.') ? destination.id : nothing}
      data-menu-action=${kind === 'window' && !destination.id.includes('.') ? destination.id : nothing}
      data-area-id=${kind === 'area' ? destination.id : nothing}
      aria-checked=${kind === 'area' ? String(checked) : nothing}
      aria-keyshortcuts=${destination.shortcut ? ariaShortcut(destination.shortcut) : nothing}
      @click=${() => this.select({ kind, id: destination.id })}>
      ${icon({ name: destination.icon, size: 16 })}<span class="item-label">${destination.label}</span>
      ${destination.detail ? html`<span class="item-detail">${destination.detail}</span>` : nothing}
      ${destination.shortcut ? html`<kbd aria-hidden="true">${destination.shortcut}</kbd>` : nothing}</button>`;
  }

  private renderMoreMenu(): TemplateResult {
    const primary = [this.system, this.help, this.about].filter((d): d is TopBarDestination => d !== null);
    return html`<div class="menu more" role="menu" aria-label="More" data-tauri-drag-region=${this.noDrag} @keydown=${this.onMenuKeydown}>
      <div class="group" role="group" aria-label="App">${primary.map(d => this.menuRow(d, 'window'))}</div>
      ${this.windows.length ? html`<div class="group" role="group" aria-labelledby="more-windows-label">
        <div class="group-label" id="more-windows-label">More</div>
        ${this.windows.map(d => this.menuRow(d, 'window'))}</div>` : nothing}
      ${this.developer ? html`<div class="group" role="group" aria-labelledby="developer-label">
        <div class="group-label"><span id="developer-label">Developer</span><span>on in Settings › Advanced</span></div>
        ${this.developerWindows.map(d => this.menuRow(d, 'window'))}
        <button type="button" role="menuitem" tabindex="-1" data-menu-action="copy-link"
          @click=${() => this.select({ kind: 'copy-link' })}>${icon({ name: 'copy', size: 16 })}<span class="item-label">Copy link to this view</span></button>
        <button type="button" role="menuitem" tabindex="-1" data-menu-action="backend-address" aria-expanded=${String(this.showBackend)}
          @click=${() => { this.showBackend = !this.showBackend; }}>${icon({ name: 'database', size: 16 })}<span class="item-label">Show backend address</span></button>
        <button type="button" role="menuitem" tabindex="-1" data-menu-action="logs"
          @click=${() => this.select({ kind: 'logs' })}>${icon({ name: 'file-text', size: 16 })}<span class="item-label">Logs</span></button>
        ${this.showBackend ? html`<div class="backend" data-testid="backend-address">${this.backendAddress}</div>` : nothing}
      </div>` : nothing}
    </div>`;
  }

  override render(): TemplateResult {
    const active = this.areas.find(a => a.id === this.activeId);
    return html`<div class="bar">
      <div class="start">
        <span class="brand"><span class="dot" aria-hidden="true"></span><span class="name">JustSearch</span></span>
      </div>
      <nav aria-label="Areas" data-tauri-drag-region=${this.noDrag} ?hidden=${!this.switcherVisible || this.areas.length === 0}>
        <div class="segments">${this.areas.map(a => this.segment(a))}</div>
        <span class="menu-anchor">
          <button type="button" class="area-trigger" aria-haspopup="menu" aria-expanded=${String(this.openMenu === 'areas')}
            aria-label=${active ? `${active.label}, choose area` : 'Choose area'} @click=${() => this.toggleMenu('areas')}>
            ${active ? html`${icon({ name: active.icon, size: 16 })}<span class="trigger-label">${active.label}</span>` : html`<span class="trigger-label">Areas</span>`}
            ${icon({ name: 'chevron-down', size: 14 })}</button>
          ${this.openMenu === 'areas' ? html`<div class="menu areas" role="menu" aria-label="Areas" data-tauri-drag-region=${this.noDrag} @keydown=${this.onMenuKeydown}>
            ${this.areas.map(a => this.menuRow(a, 'area'))}</div>` : nothing}
        </span>
      </nav>
      <div class="end">
        <slot name="status" data-tauri-drag-region=${this.noDrag}></slot>
        <button type="button" class="icon-button settings-trigger" data-surface-id="core.settings-surface"
          aria-label="Settings" title=${isMac() ? 'Settings · ⌘+,' : 'Settings · Ctrl+,'} aria-keyshortcuts=${isMac() ? 'Meta+,' : 'Control+,'} aria-haspopup="dialog"
          @click=${() => this.select({ kind: 'settings' })}>${icon({ name: 'settings', size: 18 })}</button>
        <span class="menu-anchor">
          <button type="button" class="icon-button more-trigger" aria-label="More" title="More"
            aria-haspopup="menu" aria-expanded=${String(this.openMenu === 'more')}
            @click=${() => this.toggleMenu('more')}>${icon({ name: 'more-horizontal', size: 18 })}</button>
          ${this.openMenu === 'more' ? this.renderMoreMenu() : nothing}
        </span>
      </div>
    </div>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-top-bar')) {
  customElements.define('jf-top-bar', TopBar);
}
