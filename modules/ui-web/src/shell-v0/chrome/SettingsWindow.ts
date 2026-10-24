// SPDX-License-Identifier: Apache-2.0
/**
 * SettingsWindow — tempdoc 855 Phase 0: the centered window that hosts Settings.
 *
 * Settings is the first `Placement.MODAL` surface: navigating to it (rail affordance, command
 * palette, pasted URL) opens this window OVER the current stage surface instead of replacing it
 * (`NavigationHandler`'s MODAL branch, 855 §11.1). The window itself is assembled from primitives
 * that already exist — zero new overlay machinery:
 *
 *  - **Modality** — `ModalController` in the `ConfirmDialog` idiom: a reflected boolean `open`
 *    property is the single source of truth, the native `<dialog>` supplies focus-trap / `inert` /
 *    Top Layer, and the controller adds scroll-lock + focus-restore. `::backdrop` is styled
 *    directly, so there is no hand-picked z-index.
 *  - **Content** — the catalog's `mountSurface` (the `Peek` / `SurfaceTabs` precedent), so the
 *    window hosts the DECLARED settings surface rather than hard-coding its tag.
 *
 * The hosted element is mounted **persistently** (855 §11.2): it stays connected while the window
 * is closed, so member-tab intents (`memberTabIntent`, e.g. a deep link to Security) always reach a
 * live subscriber instead of racing a mount. Since the Stage never mounts `jf-settings-surface`
 * again (placement filter + the skipped `setActiveSurface`), this is the surface's one mount site.
 *
 * Close (X / Escape / backdrop) flips `open` and emits `settings-window-close`; the window itself
 * makes NO history assumption (855 §11.1 D4). Only the Shell knows whether opening this window
 * actually pushed an entry (`OpenModalInfo.pushed`): it did for an in-app navigation, and it did NOT
 * for a boot/deep-link entry where the settings address was already the location. So the Shell owns
 * the unwind — `history.back()` in the first case, a forward navigation to the stage surface in the
 * second. The complement is `dismiss()`, which closes with NO event and no history effect — for when
 * the address has ALREADY moved (a real browser Back, or any other realized stage navigation; the
 * Shell calls it from `setActiveSurface`).
 */
import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { ModalController } from '../primitives/modalController.js';
import { icon } from '../components/Icon.js';
import { present } from '../display/present.js';
import { getSurface, mountSurface, onSurfaceCatalogChange } from '../../api/registry/SurfaceCatalogClient.js';
import { ensureSurfaceLoaded, isLazySurface } from '../views/lazySurfaceRegistry.js';
import type { SettingsSurface } from '../views/SettingsSurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

/** The one MODAL surface this window hosts (855 Phase 0). */
const SETTINGS_SURFACE_ID = 'core.settings-surface';

export class SettingsWindow extends JfElement {
  static properties = {
    open: { type: Boolean, reflect: true },
    apiBase: { type: String, attribute: 'api-base' },
    host_: { attribute: false },
  };

  declare open: boolean;
  declare apiBase: string;
  declare host_: PluginHostApi | undefined;

  /** The persistently-mounted settings element (855 §11.2) — minted once, never torn down. */
  private mounted: HTMLElement | null = null;
  private unsubscribeCatalog: (() => void) | null = null;
  // 855 §11.1 P1 look-item — the mount is deferred to browser idle time so SettingsSurface's full
  // connectedCallback cost (9 loads + 8 subs + a chunk fetch, §11.1) doesn't land on shell boot; the
  // window opens the moment `open` flips regardless (see `shouldMount` read in `renderContent`), so
  // the connected-before-first-open property member intents rely on (§11.2) is never given up —
  // idle mounting only shifts WHEN the (still boot-synchronous, non-open) mount happens.
  private shouldMount = false;
  private recoveryFocusRequested = false;
  private readonly cancelRecoveryOnUserActivity = (): void => {
    this.recoveryFocusRequested = false;
    (this.mounted as SettingsSurface | null)?.cancelSettingsRecoveryFocus?.();
  };
  private idleHandle: number | null = null;
  private cancelIdle: (() => void) | null = null;
  // 855 §11.2 merge-blocker: set by `dismiss()` right before `open` flips false, consumed by the
  // `updated()` open→false path, then reset — so a navigation-initiated close (the address already
  // moved to a different stage surface) adds no restore-to-invoker of ITS OWN, unlike `requestClose()`
  // (ESC / X / backdrop). This only suppresses `ModalityController`'s restore (see `modality.ts`
  // ModalityController.exit); the native `<dialog>.close()` called just before it may still return
  // focus to the invoker in real browsers regardless (audit-measured on the Back-dismissal path) —
  // judged benign for the dismissal cases this flag covers.
  private pendingCloseSkipsFocusRestore = false;

  constructor() {
    super();
    this.open = false;
    this.apiBase = '';
    this.host_ = undefined;
  }

  /** The FULL modal contract (574 §22.G) fired atomically — showModal + scroll-lock + focus-restore. */
  private readonly modal = new ModalController(this, {
    dialog: () => this.shadowRoot?.querySelector('dialog'),
  });

  static styles = css`
    :host {
      display: contents;
    }
    /* Native <dialog> (showModal): browser inert + focus-trap + Top Layer, so no backdrop div and
       no hand-picked z-index. */
    dialog {
      width: min(1200px, 92vw);
      height: min(85vh, 900px);
      max-width: 92vw;
      max-height: 92vh;
      padding: 0;
      border: 1px solid var(--border-subtle);
      border-radius: 0.75rem;
      background: var(--surface-1);
      color: var(--text-primary);
      box-shadow: 0 24px 48px rgba(0, 0, 0, 0.5);
      overflow: hidden;
    }
    dialog::backdrop {
      background: rgba(0, 0, 0, 0.55);
    }
    .frame {
      position: relative;
      display: flex;
      flex-direction: column;
      height: 100%;
      overflow: hidden;
    }
    /* The hosted surface owns its own scrolling (SettingsSurface's .settings-scroll), exactly as it
       does in the Stage pane — so the frame clips and does not add a second scroll container. */
    .body {
      flex: 1;
      min-height: 0;
      overflow: hidden;
    }
    .close {
      position: absolute;
      top: 0.5rem;
      right: 0.5rem;
      z-index: 1;
      width: 2rem;
      height: 2rem;
      display: flex;
      align-items: center;
      justify-content: center;
      border: 1px solid transparent;
      border-radius: 0.4rem;
      background: var(--surface-2);
      color: var(--text-secondary);
      cursor: pointer;
    }
    .close:hover {
      color: var(--text-primary);
      border-color: var(--border-subtle);
    }
    .empty {
      padding: 2rem;
      text-align: center;
      color: var(--text-secondary);
    }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    // The catalog boots asynchronously; re-render when it lands so the content mounts on arrival.
    this.unsubscribeCatalog = onSurfaceCatalogChange(() => this.requestUpdate());
    this.scheduleIdleMount();
    for (const type of ['pointerdown', 'keydown']) {
      window.addEventListener(type, this.cancelRecoveryOnUserActivity, true);
    }
    window.addEventListener('blur', this.cancelRecoveryOnUserActivity);
  }

  override disconnectedCallback(): void {
    for (const type of ['pointerdown', 'keydown']) {
      window.removeEventListener(type, this.cancelRecoveryOnUserActivity, true);
    }
    window.removeEventListener('blur', this.cancelRecoveryOnUserActivity);
    this.recoveryFocusRequested = false;
    this.unsubscribeCatalog?.();
    this.unsubscribeCatalog = null;
    this.cancelIdle?.();
    this.cancelIdle = null;
    super.disconnectedCallback();
  }

  /** 855 §11.1 P1 — mount on browser idle (setTimeout fallback), not at connectedCallback. */
  private scheduleIdleMount(): void {
    const activate = (): void => {
      this.shouldMount = true;
      this.idleHandle = null;
      this.requestUpdate();
    };
    const ric = (globalThis as { requestIdleCallback?: (cb: () => void) => number }).requestIdleCallback;
    if (typeof ric === 'function') {
      this.idleHandle = ric(activate);
      const cic = (globalThis as { cancelIdleCallback?: (h: number) => void }).cancelIdleCallback;
      this.cancelIdle = () => {
        if (this.idleHandle !== null) cic?.(this.idleHandle);
      };
    } else {
      this.idleHandle = window.setTimeout(activate, 0) as unknown as number;
      this.cancelIdle = () => {
        if (this.idleHandle !== null) window.clearTimeout(this.idleHandle);
      };
    }
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('open') && this.open) {
      this.modal.open();
    } else if (changed.has('open')) {
      this.recoveryFocusRequested = false;
      (this.mounted as SettingsSurface | null)?.cancelSettingsRecoveryFocus?.();
      const skipFocusRestore = this.pendingCloseSkipsFocusRestore;
      this.pendingCloseSkipsFocusRestore = false;
      this.modal.close({ skipFocusRestore });
    }
    if (this.open && this.recoveryFocusRequested && this.mounted) {
      this.recoveryFocusRequested = false;
      const surface = this.mounted as SettingsSurface;
      void surface.focusSettingsRecovery();
    }
  }

  /** Move focus to the existing recovery destination after the Settings window has rendered. */
  focusSettingsRecovery(): void {
    if (this.open && this.mounted && this.shadowRoot?.querySelector('dialog')?.open) {
      void (this.mounted as SettingsSurface).focusSettingsRecovery();
      return;
    }
    this.recoveryFocusRequested = true;
    this.requestUpdate();
  }

  /**
   * The one close routine. `open` flips immediately so the window never depends on a navigation
   * round-trip, then `settings-window-close` asks the Shell to move the address off this window.
   * The window deliberately carries no history assumption of its own (855 §11.1 D4).
   */
  private requestClose(): void {
    if (!this.open) return;
    this.open = false;
    this.dispatchEvent(
      new CustomEvent('settings-window-close', { bubbles: true, composed: true }),
    );
  }

  /**
   * Close WITHOUT emitting the close event — for a navigation that has ALREADY moved the address (a
   * real browser Back, or any other realized stage navigation). `requestClose()` would ask the Shell
   * to unwind a second time on top of the navigation that just happened. It also adds no
   * restore-to-invoker of its own (855 §11.2 merge-blocker): the navigation has already moved focus's
   * rightful destination, so `ModalityController`'s OWN restore-to-invoker would be wrong here. This
   * only suppresses that controller's restore, though — the native `<dialog>.close()` this still calls
   * (via `ModalController.close`) may still return focus to the invoker in real browsers, which is
   * acceptable for the dismissal cases this covers.
   */
  dismiss(): void {
    this.pendingCloseSkipsFocusRestore = true;
    this.open = false;
  }

  private onBackdrop(e: Event): void {
    if (e.target === e.currentTarget) {
      this.requestClose();
    }
  }

  /**
   * The window's one display authority (S4): the surface's label comes from `present`, which
   * resolves the catalog's i18n `labelKey` and only humanizes the id as a fallback — so the window
   * chrome never hand-stamps the product name "Settings".
   */
  private get surfaceLabel(): string {
    return present({ kind: 'surface', id: SETTINGS_SURFACE_ID }).label;
  }

  private renderContent(): TemplateResult {
    const label = this.surfaceLabel;
    const surface = getSurface(SETTINGS_SURFACE_ID);
    if (!surface) {
      return html`<div class="empty">${label} is not available.</div>`;
    }
    // 855 §11.1 P1 — defer the FIRST mount to idle unless the window is already being opened (an
    // explicit open must never wait on idle scheduling).
    if (!this.shouldMount && !this.open && this.mounted === null) {
      return html`<div class="empty">Loading ${label}…</div>`;
    }
    const tag = surface.mountTag;
    // Lazy-load the surface module (registers the custom element), mirroring Shell.renderOneSurface.
    if (isLazySurface(tag) && !customElements.get(tag)) {
      void ensureSurfaceLoaded(tag);
      void customElements.whenDefined(tag).then(() => this.requestUpdate());
      return html`<div class="empty">Loading ${label}…</div>`;
    }
    if (this.mounted === null) {
      try {
        this.mounted = mountSurface(surface, { apiBase: this.apiBase, host_: this.host_ });
      } catch {
        // The factory throws when the mountTag is not a registered element (Shell/Peek guard the
        // same way): report it in place rather than failing the whole shell's render.
        this.mounted = null;
      }
    }
    if (this.mounted === null) {
      return html`<div class="empty">Cannot mount ${label}.</div>`;
    }
    // Set every render so live shell dependencies propagate to the persistent element.
    if (this.apiBase) {
      this.mounted.setAttribute('api-base', this.apiBase);
    } else {
      this.mounted.removeAttribute('api-base');
    }
    (this.mounted as unknown as { host_?: PluginHostApi }).host_ = this.host_ ?? undefined;
    return html`${this.mounted}`;
  }

  override render(): TemplateResult {
    const label = this.surfaceLabel;
    // Chrome close affordances in this codebase carry a hand-written English "Close"
    // (ConfirmDialog, ContextInspectorPane, FailedJobsDrawer, …) — there is no close-label resource
    // key to resolve. The one part that IS catalog-backed, the surface name, goes through `present`.
    const closeLabel = `Close ${label}`;
    return html`
      <dialog
        aria-label=${label}
        @cancel=${(e: Event) => {
          e.preventDefault();
          this.requestClose();
        }}
        @click=${(e: Event) => this.onBackdrop(e)}
      >
        <div class="frame">
          <button
            class="close"
            type="button"
            title=${closeLabel}
            aria-label=${closeLabel}
            data-testid="settings-window-close"
            @click=${() => this.requestClose()}
          >
            ${icon({ name: 'x', size: 16 })}
          </button>
          <div class="body">${this.renderContent()}</div>
        </div>
      </dialog>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-settings-window')) {
  customElements.define('jf-settings-window', SettingsWindow);
}
