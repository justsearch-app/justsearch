// SPDX-License-Identifier: Apache-2.0
/**
 * Token Editor — the bundled first-party plugin (tempdoc 560 §25/§26).
 *
 * The ONE token editor: a live design-token editor delivered through the plugin contribution model,
 * not a bespoke core surface. It reads the live tokens via the `theme` capability
 * (`host.theme.getTokens`), live-previews edits via `host.theme.previewTokens` (the HOST generates the
 * scoped, value-sanitized `@layer user-theme` <style> — the plugin never supplies raw CSS), and
 * exports the overrides via `host.ui.copyToClipboard`. It writes NO core tokens (preview-only).
 *
 * Graduated from the dev-examples URL-loaded version (560 §22-24): this is a bundled TS module
 * installed at boot via `registry.install(createTokenEditorPluginManifest(), 'TRUSTED_PLUGIN')` in
 * main.jsx — trusted by construction, no SES compartment, no dev server, no signing. It mirrors
 * `CorePlugin` (the compiled-in first-party plugin), but ships as its own `token-editor` plugin so the
 * "a plugin can do what core does" dogfood stays a real, distinct plugin rather than core code.
 */
import {
  PLUGIN_CONTRACT_VERSION,
  type PluginContribution,
  type PluginHostApi,
  type PluginManifest,
  type PluginSurfaceContribution,
} from '../../plugin-api/plugin-types.js';
import { ROLE_CATALOG } from '../../themes/themeRoles.js';
import { deriveForeground, formatRatio, WCAG_AAA, apcaLc, APCA_SOFT } from '../../themes/contrast.js';

/**
 * The host API, captured in `register()` and read by the element instances (the browser constructs
 * <token-editor-panel> later, on deep-link/settings-link navigation (855 P3a moved this off the rail —
 * placement RAIL→DEEPLINK, launched from the Appearance sub-anchor's link), with no constructor args —
 * this module-level binding is how the panel reaches the capability). `register()` runs at boot, before
 * any surface mounts.
 */
let host: PluginHostApi | null = null;

// Tempdoc 567 — the authored surface is SEEDS only (hue angles `h-*`, primitive channels `p-*`);
// every other token DERIVES from them in CSS, so the editor never exposes a derived value to hand-edit.
const SEED_PREFIXES = ['h-', 'p-'] as const;
const isSeedName = (name: string): boolean => SEED_PREFIXES.some((p) => name.startsWith(p));
const slugify = (s: string): string =>
  s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'theme';

// Tempdoc 567 §8 / A2 — resolve any CSS colour (incl. `oklch()`, which getComputedStyle returns
// verbatim) to an sRGB [r,g,b] triple by rasterising one pixel on a canvas. Used to derive the readable
// role foreground over each accent background.
let _resolveCanvas: HTMLCanvasElement | null = null;
function resolveColorToRgb(cssColor: string): [number, number, number] | null {
  if (typeof document === 'undefined' || cssColor.trim() === '') return null;
  if (!_resolveCanvas) {
    _resolveCanvas = document.createElement('canvas');
    _resolveCanvas.width = _resolveCanvas.height = 1;
  }
  const ctx = _resolveCanvas.getContext('2d');
  if (!ctx) return null;
  ctx.clearRect(0, 0, 1, 1);
  ctx.fillStyle = '#000000';
  ctx.fillStyle = cssColor; // an invalid colour leaves the prior #000
  ctx.fillRect(0, 0, 1, 1);
  const d = ctx.getImageData(0, 0, 1, 1).data;
  return [d[0] ?? 0, d[1] ?? 0, d[2] ?? 0];
}

const PANEL_CSS = `
        :host { display: block; height: 100%; overflow: auto; padding: 1rem;
                color: var(--text-primary);
                font-size: var(--font-size-sm); font-family: var(--font-native); }
        h1 { margin: 0 0 0.25rem; font-size: var(--font-size-md); }
        h2 { margin: 1.1rem 0 0.4rem; font-size: var(--font-size-xs); text-transform: uppercase;
             letter-spacing: 0.06em; opacity: 0.65; }
        p.hint { margin: 0 0 0.5rem; opacity: 0.6; font-size: var(--font-size-sm); }
        button { padding: 0.35rem 0.625rem; cursor: pointer; }
        .row { display: grid; grid-template-columns: 1fr auto auto; align-items: center;
               gap: 0.6rem; padding: 0.25rem 0; border-bottom: 1px solid var(--border-subtle); }
        .name { font-family: var(--font-mono); font-size: var(--font-size-sm); }
        .val { opacity: 0.6; font-size: var(--font-size-xs); font-variant-numeric: tabular-nums;
               min-width: 4.5rem; text-align: right; }
        input[type=range] { width: 9rem; }
        input[type=text] { width: 9rem; padding: 0.2rem 0.35rem; font-size: var(--font-size-sm); }
        .save-row { display: flex; gap: 0.5rem; align-items: center; margin: 0.4rem 0; }
        .save-row input { flex: 1; padding: 0.35rem 0.5rem; }
        .saved { display: flex; flex-direction: column; gap: 0.35rem; }
        .saved-item { display: flex; gap: 0.5rem; align-items: center; }
        .saved-item .lbl { flex: 1; }
        .empty { opacity: 0.5; font-size: var(--font-size-sm); margin: 0; }
        .role-row { display: grid; grid-template-columns: 5rem auto 1fr; align-items: center;
                    gap: 0.6rem; padding: 0.25rem 0; border-bottom: 1px solid var(--border-subtle); }
        .role-label { font-size: var(--font-size-sm); }
        .role-swatch { display: inline-flex; align-items: center; justify-content: center;
                       min-width: 2.6rem; padding: 0.1rem 0.45rem; border-radius: 0.25rem;
                       font-size: var(--font-size-xs); font-weight: 600; }
        .role-ratio { font-size: var(--font-size-xs); opacity: 0.7; font-variant-numeric: tabular-nums; }
        .role-ratio.warn { color: var(--text-danger); opacity: 1; }
        /* tempdoc 576 §6 B6 — per-role nudge-to-compliant: how far below the floor + which way to push. */
        .role-nudge { font-size: var(--font-size-xs); color: var(--text-warning); grid-column: 1 / -1; opacity: 0.95; }
`;
let panelSheet: CSSStyleSheet | null = null;

class TokenEditorPanel extends HTMLElement {
  // Tempdoc 567 §8 / A3 — authored values are bucketed: mode-invariant hue seeds (`h-*`) are SHARED,
  // mode-variant primitive channels (`p-*`) are authored PER light/dark mode. Save maps the shared
  // bucket to `tokens` and the per-mode buckets to `tokensByMode`.
  private hEdits = new Map<string, string>();
  private pEdits: { light: Map<string, string>; dark: Map<string, string> } = {
    light: new Map(),
    dark: new Map(),
  };
  private root!: ShadowRoot;
  private modeObserver: MutationObserver | null = null;
  /** True while save-time role baking toggles `data-theme` synchronously — suppresses the observer. */
  private baking = false;

  /** The app's active light/dark mode (the `data-theme` attribute; absent ⇒ dark base). */
  private currentMode(): 'light' | 'dark' {
    return document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
  }

  connectedCallback(): void {
    this.hEdits = new Map();
    this.pEdits = { light: new Map(), dark: new Map() };
    this.root = this.attachShadow({ mode: 'open' });
    this.renderShell();
    this.updateModeLabel();
    this.renderSeeds();
    this.renderRoles();
    this.renderSavedThemes();
    // Re-render the p-* rows + roles + re-preview when the app light/dark mode changes, so the editor
    // always edits/derives the currently-visible mode. Guarded during save-time role baking (which
    // toggles data-theme synchronously).
    this.modeObserver = new MutationObserver(() => {
      if (this.baking) return;
      this.updateModeLabel();
      this.renderSeeds();
      this.renderRoles();
      this.applyPreview();
    });
    this.modeObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme'],
    });
  }

  disconnectedCallback(): void {
    this.modeObserver?.disconnect();
    this.modeObserver = null;
    // Clear this plugin's live preview when the surface unmounts (navigate-away reverts the app).
    host?.theme.previewTokens?.(new Map());
  }

  private renderShell(): void {
    // The desktop window refuses a <style> parsed from markup; the panel's CSS is an adopted sheet.
    panelSheet ??= new CSSStyleSheet();
    if (panelSheet.cssRules.length === 0) panelSheet.replaceSync(PANEL_CSS);
    if (!this.root.adoptedStyleSheets.includes(panelSheet)) {
      this.root.adoptedStyleSheets = [...this.root.adoptedStyleSheets, panelSheet];
    }
    this.root.innerHTML = `
      <h1>Theme Editor</h1>
      <p class="hint">Edit the design seeds — colours and surfaces derive automatically.</p>
      <p class="hint mode-hint"></p>
      <div class="seeds"></div>
      <h2>Roles — text contrast</h2>
      <p class="hint">Foreground auto-derived to stay readable (WCAG) over each accent background.</p>
      <div class="roles"></div>
      <h2>Save as theme</h2>
      <div class="save-row">
        <input id="name" type="text" placeholder="Theme name…" />
        <button id="save">Save &amp; apply</button>
        <button id="export">Export</button>
        <button id="reset">Reset</button>
      </div>
      <h2>My themes</h2>
      <div class="saved"></div>
    `;
    this.root.getElementById('save')!.addEventListener('click', () => this.saveTheme());
    this.root.getElementById('export')!.addEventListener('click', () => this.exportCurrent());
    this.root.getElementById('reset')!.addEventListener('click', () => this.resetEdits());
  }

  private updateModeLabel(): void {
    const el = this.root.querySelector('.mode-hint');
    if (!el) return;
    const mode = this.currentMode() === 'light' ? 'Light' : 'Dark';
    el.textContent =
      `Editing ${mode} mode — surface tints (p-*) save per mode; switch light/dark in Settings to ` +
      `author the other. Hues (h-*) are shared across both.`;
  }

  private renderSeeds(): void {
    const el = this.root.querySelector('.seeds')!;
    if (!host) {
      el.innerHTML = '<p class="empty">Theme capability unavailable (host not ready).</p>';
      return;
    }
    const seeds = host.theme.getTokens().filter((t) => isSeedName(t.name));
    el.textContent = '';
    const doc = this.ownerDocument;
    for (const t of seeds) {
      const row = doc.createElement('div');
      row.className = 'row';
      const name = doc.createElement('div');
      name.className = 'name';
      name.textContent = `--${t.name}`;
      const bucket = t.name.startsWith('h-') ? this.hEdits : this.pEdits[this.currentMode()];
      const current = bucket.get(t.name) ?? (t.currentValue === '(unset)' ? '' : t.currentValue);
      const valEl = doc.createElement('span');
      valEl.className = 'val';
      valEl.textContent = current;
      const input = doc.createElement('input');
      if (t.widgetType === 'angle') {
        // Hue seeds: a 0–360 slider — dragging it live-restyles every colour derived from the hue.
        input.type = 'range';
        input.min = '0';
        input.max = '360';
        input.step = '1';
        input.value = String(parseInt(current, 10) || 0);
      } else {
        input.type = 'text';
        input.value = current;
      }
      input.addEventListener('input', () => {
        valEl.textContent = input.value;
        this.onEdit(t.name, input.value);
      });
      row.append(name, valEl, input);
      el.append(row);
    }
  }

  /**
   * Tempdoc 567 §8 / A2 — render the semantic roles: for each accent, the auto-derived readable
   * foreground + the achieved WCAG ratio over the CURRENT mode's accent. Read-only (the background is
   * authored via the hue seeds); a sub-floor ratio is flagged.
   */
  private renderRoles(): void {
    const el = this.root.querySelector('.roles');
    if (!el || !host) return;
    el.textContent = '';
    const doc = this.ownerDocument;
    const cs = getComputedStyle(document.documentElement);
    for (const role of ROLE_CATALOG) {
      const bgVal = cs.getPropertyValue(`--${role.bgToken}`).trim();
      const rgb = resolveColorToRgb(bgVal);
      const derived = rgb ? deriveForeground(rgb, role.floor) : null;
      const row = doc.createElement('div');
      row.className = 'role-row';
      const label = doc.createElement('span');
      label.className = 'role-label';
      label.textContent = role.label;
      const swatch = doc.createElement('span');
      swatch.className = 'role-swatch';
      swatch.style.background = bgVal;
      swatch.textContent = 'Ag';
      if (derived) swatch.style.color = derived.fg;
      const ratio = doc.createElement('span');
      ratio.className = 'role-ratio';
      if (derived) {
        const badge = derived.ratio >= WCAG_AAA ? 'AAA' : derived.meets ? 'AA' : 'fail';
        ratio.textContent = `${formatRatio(derived.ratio)}:1 ${badge}`;
        if (!derived.meets) ratio.classList.add('warn');
      } else {
        ratio.textContent = '—';
      }
      row.append(label, swatch, ratio);
      // tempdoc 576 §6 B6 — nudge-to-compliant. Push the fill toward its own extreme (away from the
      // contrasting text) to raise contrast. Two triggers: (1) the rare WCAG-AA failure (even the
      // optimal black/white fg can't clear the floor — a genuinely mid-tone fill); (2) the COMMON,
      // perceptually-meaningful case — the fill clears AA but is APCA-weak (the mid-tone saturated hues
      // WCAG-2 under-penalises — exactly the #3 amber-as-text bug this work exists to surface). Without
      // the APCA trigger the nudge is near-dead, because black/white always clears AA 4.5 (min ~4.58).
      if (derived && rgb) {
        const fgRgb: [number, number, number] = derived.fg === '#ffffff' ? [255, 255, 255] : [0, 0, 0];
        const dir = derived.fg === '#ffffff' ? 'darken' : 'lighten';
        const lc = Math.abs(apcaLc(fgRgb, rgb));
        let text: string | null = null;
        if (!derived.meets) {
          const gap = Math.max(0, role.floor - derived.ratio);
          text = `↳ fails AA — ${dir} the fill (+${formatRatio(gap)}:1 to ${formatRatio(role.floor)}:1)`;
        } else if (lc < APCA_SOFT) {
          text = `↳ APCA Lc ${Math.round(lc)} (< ${APCA_SOFT}) — clears AA but perceptually weak; ${dir} the fill for stronger contrast`;
        }
        if (text) {
          const nudge = doc.createElement('div');
          nudge.className = 'role-nudge';
          nudge.textContent = text;
          row.append(nudge);
        }
      }
      el.append(row);
    }
  }

  private onEdit(name: string, value: string): void {
    if (!host) return;
    const bucket = name.startsWith('h-') ? this.hEdits : this.pEdits[this.currentMode()];
    bucket.set(name, value);
    this.applyPreview();
  }

  /**
   * Live-preview the combined SHARED hues + CURRENT-mode primitives against the active mode's selector
   * (`:root` for dark, `[data-theme="light"]` for light) so the preview is WYSIWYG for the visible mode.
   */
  private applyPreview(): void {
    if (!host) return;
    const mode = this.currentMode();
    const combined = new Map<string, string>([...this.hEdits, ...this.pEdits[mode]]);
    try {
      host.theme.previewTokens?.(combined, mode === 'light' ? 'light' : undefined);
    } catch (e) {
      console.log('[token-editor] previewTokens rejected:', (e as Error)?.message);
    }
  }

  /** Bucket the authored edits into the save shape: shared hues → tokens, per-mode primitives → tokensByMode. */
  private buildTokens(): {
    tokens: Record<string, string>;
    tokensByMode?: { light?: Record<string, string>; dark?: Record<string, string> };
  } {
    const mapToObj = (m: Map<string, string>): Record<string, string> => {
      const o: Record<string, string> = {};
      for (const [n, v] of m) if (v.trim() !== '') o[n] = v;
      return o;
    };
    const tokens = mapToObj(this.hEdits);
    const light = mapToObj(this.pEdits.light);
    const dark = mapToObj(this.pEdits.dark);
    // Tempdoc 567 §8 / A2 — bake the auto-derived role foregrounds for BOTH modes (accent-on-* differ by
    // mode), so a saved theme keeps readable accent text everywhere.
    const roleFgs = this.deriveRoleFgs();
    Object.assign(dark, roleFgs.dark);
    Object.assign(light, roleFgs.light);
    const byMode: { light?: Record<string, string>; dark?: Record<string, string> } = {};
    if (Object.keys(light).length) byMode.light = light;
    if (Object.keys(dark).length) byMode.dark = dark;
    return Object.keys(byMode).length ? { tokens, tokensByMode: byMode } : { tokens };
  }

  /**
   * Resolve + derive the readable role foreground (`accent-on-*`) for EACH mode. The accent backgrounds
   * derive from the hue seeds, so we apply the authored hues inline (hues are mode-shared) and toggle
   * `data-theme` synchronously to read each mode's resolved accents — all in one JS task (no paint ⇒ no
   * flicker), restoring the original attribute + temp props afterward.
   */
  private deriveRoleFgs(): { dark: Record<string, string>; light: Record<string, string> } {
    const result = { dark: {} as Record<string, string>, light: {} as Record<string, string> };
    if (typeof document === 'undefined') return result;
    const root = document.documentElement;
    const originalTheme = root.getAttribute('data-theme');
    this.baking = true;
    const tempProps: string[] = [];
    for (const [n, v] of this.hEdits) {
      if (n.startsWith('h-') && v.trim() !== '') {
        root.style.setProperty(`--${n}`, v);
        tempProps.push(n);
      }
    }
    try {
      for (const mode of ['dark', 'light'] as const) {
        if (mode === 'light') root.setAttribute('data-theme', 'light');
        else root.removeAttribute('data-theme');
        const cs = getComputedStyle(root);
        for (const role of ROLE_CATALOG) {
          const rgb = resolveColorToRgb(cs.getPropertyValue(`--${role.bgToken}`).trim());
          if (rgb) result[mode][role.fgToken] = deriveForeground(rgb, role.floor).fg;
        }
      }
    } finally {
      if (originalTheme === null) root.removeAttribute('data-theme');
      else root.setAttribute('data-theme', originalTheme);
      for (const n of tempProps) root.style.removeProperty(`--${n}`);
      this.baking = false;
    }
    return result;
  }

  private saveTheme(): void {
    if (!host) return;
    const nameInput = this.root.getElementById('name') as HTMLInputElement;
    const name = nameInput.value.trim();
    if (!name) {
      host.ui.showNotification?.('Enter a theme name first');
      return;
    }
    const built = this.buildTokens();
    if (Object.keys(built.tokens).length === 0 && built.tokensByMode === undefined) {
      host.ui.showNotification?.('Edit a seed before saving');
      return;
    }
    const id = `custom.${slugify(name)}`;
    try {
      host.theme.saveTheme?.({ id, displayName: name, ...built });
      void host.theme.selectTheme?.(id);
      host.ui.showNotification?.(`Saved & applied "${name}"`);
      nameInput.value = '';
      this.renderSavedThemes();
    } catch (e) {
      host.ui.showNotification?.(`Save failed: ${(e as Error)?.message}`);
    }
  }

  /**
   * Tempdoc 567 §8 #2 — export the current authored seeds as a DesignTokenTree JSON to the clipboard
   * (the share affordance the pre-A1 editor had, restored). Built locally — no capability needed; uses
   * the name field for the id/displayName so the exported JSON is a valid, importable theme.
   */
  private exportCurrent(): void {
    if (!host) return;
    const built = this.buildTokens();
    if (Object.keys(built.tokens).length === 0 && built.tokensByMode === undefined) {
      host.ui.showNotification?.('Edit a seed before exporting');
      return;
    }
    const name = (this.root.getElementById('name') as HTMLInputElement).value.trim() || 'Untitled theme';
    const tree = { schemaVersion: 1, id: `custom.${slugify(name)}`, displayName: name, ...built };
    void host.ui.copyToClipboard(JSON.stringify(tree, null, 2));
    host.ui.showNotification?.('Theme JSON copied to clipboard');
  }

  private renderSavedThemes(): void {
    const el = this.root.querySelector('.saved')!;
    if (!host) return;
    const custom = host.theme.listThemes().filter((t) => t.isCustom);
    el.textContent = '';
    if (custom.length === 0) {
      el.innerHTML = '<p class="empty">No saved themes yet.</p>';
      return;
    }
    const doc = this.ownerDocument;
    for (const t of custom) {
      const item = doc.createElement('div');
      item.className = 'saved-item';
      const lbl = doc.createElement('span');
      lbl.className = 'lbl';
      lbl.textContent = t.displayName;
      const applyBtn = doc.createElement('button');
      applyBtn.textContent = 'Apply';
      applyBtn.addEventListener('click', () => void host?.theme.selectTheme?.(t.id));
      const exportBtn = doc.createElement('button');
      exportBtn.textContent = 'Export';
      exportBtn.addEventListener('click', () => {
        const json = host?.theme.exportTheme?.(t.id);
        if (json) {
          void host?.ui.copyToClipboard(json);
          host?.ui.showNotification?.(`Copied "${t.displayName}" JSON`);
        }
      });
      const delBtn = doc.createElement('button');
      delBtn.textContent = 'Delete';
      delBtn.addEventListener('click', () => {
        host?.theme.deleteTheme?.(t.id);
        this.renderSavedThemes();
      });
      item.append(lbl, applyBtn, exportBtn, delBtn);
      el.append(item);
    }
  }

  private resetEdits(): void {
    if (!host) return;
    this.hEdits.clear();
    this.pEdits.light.clear();
    this.pEdits.dark.clear();
    host.theme.previewTokens?.(new Map()); // empty map clears the host preview <style>
    this.renderSeeds();
  }
}

/** The plugin's single surface — DEEPLINK, not RAIL (tempdoc 855 §5 item 2: a design tool, not a
 *  daily destination; reachable via the *General → Appearance* settings link + command palette).
 *  Reused by `capabilities.surfaces` + the register contribution. */
const SURFACE: PluginSurfaceContribution = {
  // Surface ids must be `vendor.<x>.<y>` (or `core.<y>`) to satisfy the router's SurfaceRef id regex
  // (`^(core|vendor\.[a-z][a-z0-9-]*)\.[a-z][a-z0-9-]*$`) — otherwise the surface is admitted but not
  // navigable. (The element tag is separate: `token-editor-panel`.)
  id: 'vendor.token-editor.editor-surface',
  mountTag: 'token-editor-panel',
  labelKey: 'surface.token-editor.label',
  descriptionKey: 'surface.token-editor.description',
  audience: 'USER',
  placement: 'DEEPLINK',
};

export const TOKEN_EDITOR_PLUGIN_ID = 'token-editor';

export function createTokenEditorPluginManifest(): PluginManifest {
  return {
    id: TOKEN_EDITOR_PLUGIN_ID,
    version: '0.1.0',
    displayName: 'Token Editor',
    contractVersion: PLUGIN_CONTRACT_VERSION,
    tagNamespace: TOKEN_EDITOR_PLUGIN_ID,
    capabilities: {
      surfaces: [SURFACE],
    },
    register: (h: PluginHostApi): PluginContribution => {
      host = h;
      // Declare the custom element so the HOST registers <token-editor-panel> (token-editor +
      // tagSuffix 'panel') via the validated contribution path, rather than self-defining.
      return {
        customElements: [{ tagSuffix: 'panel', klass: TokenEditorPanel }],
        translations: {
          en: {
            'surface.token-editor.label': 'Theme Editor',
            'surface.token-editor.description': 'Edit design seeds live and save custom themes',
          },
        },
        surfaceContributions: [{ contribution: SURFACE }],
      };
    },
    // Host-guaranteed cleanup on uninstall (560 §24): clear this plugin's scoped preview <style> so an
    // uninstall fully reverts the app's tokens (the registry calls manifest.unregister on uninstall).
    unregister: (h: PluginHostApi): void => {
      h.theme.previewTokens?.(new Map());
    },
  };
}
