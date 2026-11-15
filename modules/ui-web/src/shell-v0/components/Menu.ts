// SPDX-License-Identifier: Apache-2.0
import { css, html, nothing, type PropertyValues, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { TransientController } from '../primitives/transientController.js';
import { cssLength, shiftIntoWindow } from '../chrome/menuPlacement.js';

/** Shared by the bar, Library and context menus, inside their existing shadow boundary. */
export const menuStyles = css`
  jf-menu { display: contents; }
  .menu { position: absolute; top: calc(100% + var(--jf-space-2)); z-index: var(--z-overlay-transient);
    min-inline-size: min(var(--frame-menu-width), 100vw - 2 * var(--jf-space-2)); max-inline-size: calc(100vw - 2 * var(--jf-space-2));
    max-block-size: calc(100dvh - var(--frame-topbar-height) - var(--jf-space-4)); overflow-y: auto;
    box-sizing: border-box; padding: var(--jf-space-2); background: var(--surface-1); color: var(--text-primary);
    border: var(--frame-hairline) solid var(--border-emphasized); border-radius: var(--frame-panel-radius);
    box-shadow: var(--shadow-float); text-align: start; font-size: var(--font-size-sm); }
  .menu.align-end { inset-inline-end: 0; }
  .menu.align-start { inset-inline-start: 0; }
  .menu.fixed { position: fixed; }
  .menu .group + .group, .menu .sep { margin-top: var(--jf-space-2); padding-top: var(--jf-space-2); border-top: var(--frame-hairline) solid var(--border-subtle); }
  .menu .group-label { display: flex; justify-content: space-between; gap: var(--jf-space-3);
    padding: var(--jf-space-1) var(--jf-space-3); color: var(--text-secondary); font-size: var(--font-size-xs); }
  .menu [role^='menuitem'] { display: flex; align-items: center; gap: var(--jf-space-3); inline-size: 100%;
    box-sizing: border-box; min-block-size: var(--frame-control-size); padding: var(--jf-space-1) var(--jf-space-3);
    border: 0; background: transparent; font: inherit; font-weight: 500; cursor: pointer;
    border-radius: var(--frame-control-radius); text-align: start; color: var(--text-primary); }
  .menu [role^='menuitem']:hover:not(:disabled), .menu [role^='menuitem']:focus-visible { background: var(--surface-2); }
  .menu [role^='menuitem']:focus-visible { outline: var(--frame-focus-width) solid var(--focus-ring-color); outline-offset: -2px; }
  .menu [role^='menuitem']:disabled { color: var(--text-secondary); cursor: default; }
  .menu svg { color: var(--text-secondary); flex: none; }
  .menu .item-label, .menu .label { flex: 1; min-width: 0; }
  .menu .item-detail, .menu kbd { color: var(--text-secondary); font-size: var(--font-size-xs); font-weight: 400; }
  .menu .item-secondary { display: block; margin-top: var(--jf-space-1); }
  .menu kbd { white-space: nowrap; font-family: inherit; padding: 0 var(--jf-space-1);
    border: var(--frame-hairline) solid var(--border-subtle); border-radius: var(--frame-control-radius); }
  @media (forced-colors: active) {
    .menu { border-color: CanvasText; }
    .menu [role^='menuitem']:focus-visible { outline-color: Highlight; }
  }
`;
let sequence = 0;
const PRESS_EVENTS = ['pointerdown', 'pointerup', 'click', 'pointercancel'] as const;

/** Light DOM keeps caller-owned rows, selectors and focus in the same shadow boundary. */
export class Menu extends JfElement {
  static override properties = {
    content: { attribute: false }, label: {}, align: {}, anchor: { attribute: false }, trigger: { attribute: false },
  };
  declare content: TemplateResult | typeof nothing;
  declare label: string;
  declare align: 'start' | 'end';
  declare anchor: { x: number; y: number } | null;
  declare trigger: HTMLElement | null;
  private readonly menuId = `shared-menu-${++sequence}`;
  private sheet: CSSStyleSheet | null = null;
  private sheetRoot: ShadowRoot | Document | null = null;
  private readonly transient = new TransientController(this, {
    layer: 'transient', id: this.menuId, managesDismiss: true,
    dismissExclude: path => !!this.trigger && path.includes(this.trigger), close: () => this.close(),
  });
  constructor() {
    super(); this.content = nothing; this.label = 'Menu'; this.align = 'end'; this.anchor = null; this.trigger = null;
  }
  protected override createRenderRoot(): HTMLElement { return this; }
  /** A pointer press in progress; its own click decides (outside dismiss, or the trigger's toggle). */
  private pressing = false;
  private readonly onPress = (event: Event): void => {
    if (event.type === 'pointerdown') this.pressing = true;
    else setTimeout(() => { this.pressing = false; });
  };
  override connectedCallback(): void {
    super.connectedCallback();
    for (const type of PRESS_EVENTS) document.addEventListener(type, this.onPress, true);
  }
  /** Focus moved out of the menu by keyboard or by code (Tab, Shift+Tab, a shortcut): the menu closes. */
  private onFocusOut(event: FocusEvent): void {
    const next = event.relatedTarget;
    if (this.pressing || next instanceof Node && this.contains(next)) return;
    this.close(false);
  }
  protected override firstUpdated(): void {
    this.closed = false; this.transient.open(); this.items()[0]?.focus();
    window.addEventListener('resize', this.place);
  }
  protected override updated(_changed: PropertyValues): void { this.place(); }
  protected override onReveal(): void {
    window.addEventListener('resize', this.place);
    void this.updateComplete.then(() => {
      if (this.isConnected) { this.closed = false; this.transient.open(); this.place(); this.items()[0]?.focus(); }
    });
  }
  override disconnectedCallback(): void {
    window.removeEventListener('resize', this.place);
    for (const type of PRESS_EVENTS) document.removeEventListener(type, this.onPress, true);
    this.pressing = false;
    if (this.sheetRoot && this.sheet) this.sheetRoot.adoptedStyleSheets = this.sheetRoot.adoptedStyleSheets.filter(sheet => sheet !== this.sheet);
    this.sheet = null; this.sheetRoot = null;
    super.disconnectedCallback();
  }
  private items(): HTMLElement[] { return [...this.querySelectorAll<HTMLElement>('[role^="menuitem"]:not(:disabled):not([aria-disabled="true"])')]; }
  /** CSSOM placement survives the desktop CSP without creating a style attribute. */
  readonly place = (): void => {
    const surface = this.querySelector<HTMLElement>('.menu');
    const root = this.getRootNode();
    if (!surface || !(root instanceof ShadowRoot || root instanceof Document)) return;
    if (!this.sheet) {
      this.sheet = new CSSStyleSheet(); this.sheetRoot = root;
      root.adoptedStyleSheets = [...root.adoptedStyleSheets, this.sheet];
    }
    this.sheet.replaceSync('');
    const box = surface.getBoundingClientRect(), margin = cssLength(this, '--jf-space-2') || 8;
    const placement = this.anchor
      ? `left:${Math.max(margin, Math.min(this.anchor.x, window.innerWidth - box.width - margin))}px;top:${Math.max(margin, Math.min(this.anchor.y, window.innerHeight - box.height - margin))}px;`
      : `translate:${shiftIntoWindow(box, margin)}px 0;`;
    this.sheet.replaceSync(`[data-menu-id="${this.menuId}"]{${placement}}`);
  };
  private closed = false;
  close(restoreFocus = true): void {
    if (this.closed) return;
    this.closed = true;
    this.transient.close();
    const root = this.getRootNode() as Document | ShadowRoot;
    if (restoreFocus && this.contains(root.activeElement)) this.trigger?.focus();
    this.dispatchEvent(new CustomEvent('menu-close', { bubbles: true, composed: true }));
  }
  private onKey(event: KeyboardEvent): void {
    const items = this.items(), current = items.indexOf((this.getRootNode() as ShadowRoot).activeElement as HTMLElement);
    let next: number;
    switch (event.key) {
      case 'ArrowDown': next = (current + 1) % items.length; break;
      case 'ArrowUp': next = (current - 1 + items.length) % items.length; break;
      case 'Home': next = 0; break;
      case 'End': next = items.length - 1; break;
      case 'Enter': case ' ': event.preventDefault(); items[current]?.click(); return;
      case 'Escape': event.preventDefault(); this.close(); return;
      case 'Tab': this.close(false); return;
      default: return;
    }
    event.preventDefault(); items[next]?.focus();
  }
  override render() {
    return html`<div class="menu ${this.anchor ? 'fixed' : `align-${this.align}`}" data-menu-id=${this.menuId}
      role="menu" aria-label=${this.label} @keydown=${this.onKey} @focusout=${this.onFocusOut}>${this.content}</div>`;
  }
}
if (!customElements.get('jf-menu')) customElements.define('jf-menu', Menu);
