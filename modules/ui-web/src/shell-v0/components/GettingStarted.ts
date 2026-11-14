// SPDX-License-Identifier: Apache-2.0
import { html, css, nothing, type PropertyValues } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { getFirstUse, subscribeFirstUse, firstUseWatchedPaths } from '../state/firstUse.js';
import { getSuggestedRoots, type SuggestedRoot } from '../../api/domains/indexing.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { FolderAddFlow, emptyFolderAddState, focusFolderAdd } from '../controllers/FolderAddFlow.js';
import './Button.js';

const mounted = new Set<GettingStarted>();
let addState = emptyFolderAddState();
let addFlow: FolderAddFlow | null = null;
let api: PluginHostApi | undefined;
let opener: GettingStarted | null = null;
const normalizePath = (path: string) => {
  const normalized = path.replaceAll('\\', '/').replace(/\/+$/, '') || '/';
  return /^[a-z]:/i.test(normalized) ? normalized.toLowerCase() : normalized;
};
function notify(): void { for (const card of mounted) card.requestUpdate(); }
function flow(): FolderAddFlow {
  return addFlow ??= new FolderAddFlow({ state: addState,
    api: () => { if (!api) throw new Error('Folder controls are unavailable. Retry or open Library.'); return api; },
    changed: notify, current: () => () => true,
    open: async () => { await opener?.updateComplete; },
    focus: async target => { const card = opener; if (!card?.isConnected) return; await card.updateComplete;
      if (card.isConnected && card.shadowRoot) await focusFolderAdd(card.shadowRoot, target); },
    added: async () => { document.dispatchEvent(new Event('jf-first-use-folder-added')); },
  }, true);
}
/** Test isolation only; production preserves pending work when Search is left. */
export function resetFirstUseAddForTest(): void {
  addFlow?.dispose(); addFlow = null; addState = emptyFolderAddState(); api = undefined; opener = null;
}
export class GettingStarted extends JfElement {
  static properties = { host_: { attribute: false }, apiBase: { type: String }, intro: { type: Boolean }, suggestions: { state: true } };
  declare host_: PluginHostApi | undefined;
  declare apiBase: string;
  declare intro: boolean;
  declare private suggestions: SuggestedRoot[];
  private stop: (() => void) | undefined;
  private suggestionAbort: AbortController | null = null;
  constructor() { super(); this.apiBase = ''; this.intro = true; this.suggestions = []; }
  static styles = css`
    :host { display: block; min-inline-size: 0; color: var(--text-primary); font-size: var(--font-size-sm); }
    .intro { text-align: center; padding-block-end: 30px; }
    .intro p { max-inline-size: 520px; margin-inline: auto; margin-block: 6px 0; line-height: 21px; }
    .suggestions { margin-block-start: var(--jf-space-3); align-items: baseline; gap: var(--jf-space-1); color: var(--text-secondary); }
    .suggestions button { padding: 0; border: 0; background: transparent; color: var(--text-primary); font: inherit; cursor: pointer; }
    .suggestions { --focus-ring-color: var(--text-primary); }
    .suggestions button:focus-visible { outline: var(--frame-focus-width) solid var(--focus-ring-color); }
    .intro > jf-button::part(control) { min-block-size: 38px; padding-inline: 20px; }
    .intro > jf-button { color: var(--accent-on-tint); }
    .add-label { color: var(--accent-on-tint); }
    .intro > jf-button svg { inline-size: 18px; block-size: 18px; fill: none; stroke: currentColor; stroke-width: 1.8; }
    h1 { font: inherit; font-size: var(--font-size-sv3-display, var(--font-size-headline)); line-height: 40px; font-variation-settings: "opsz" 32; margin: 0; }
    p { margin: var(--jf-space-2) 0; color: var(--text-secondary); }
    .intro > jf-button { margin-block-start: 22px; }
    .suggestions { display: block; margin-block-start: 18px; }
    .suggestions jf-button { min-inline-size: 0; max-inline-size: 100%; }
    .suggestions span { white-space: normal; overflow-wrap: anywhere; }
    .path { color: var(--text-secondary); font-size: var(--font-size-xs); }
    .add-row { display: flex; flex-wrap: wrap; gap: var(--jf-space-2); margin-block: var(--jf-space-3); }
    input { flex: 1; min-inline-size: 0; max-inline-size: 100%; font: inherit; color: var(--text-primary);
      background: var(--surface-1); border: var(--frame-hairline) solid var(--border-emphasized);
      border-radius: var(--frame-control-radius); padding: var(--jf-space-2); }
    input:focus-visible { outline: var(--frame-focus-width) solid var(--focus-ring-color); }
    .message, .error, .add-preview { overflow-wrap: anywhere; margin-block: var(--jf-space-2); }
    .error { color: var(--text-danger); }
    @media (forced-colors: active) { .intro > jf-button, .add-label { color: ButtonText; } .mark, .error, .path, p { color: CanvasText; } .card, input { border-color: CanvasText; } }
  `;
  override connectedCallback(): void {
    super.connectedCallback(); mounted.add(this);
    if (!opener?.isConnected && (addState.showManualInput || addState.addBusy || addState.error)) opener = this;
    this.stop = subscribeFirstUse(() => this.requestUpdate());
    this.loadSuggestions();
  }
  override disconnectedCallback(): void {
    mounted.delete(this); this.stop?.(); this.stop = undefined;
    this.suggestionAbort?.abort(); this.suggestionAbort = null;
    super.disconnectedCallback();
  }
  override updated(changed: PropertyValues): void {
    if (changed.has('apiBase') && changed.get('apiBase') !== undefined) this.loadSuggestions();
    if (this.host_) api = this.host_;
  }
  private loadSuggestions(): void {
    this.suggestionAbort?.abort();
    const abort = new AbortController(); this.suggestionAbort = abort;
    void getSuggestedRoots(this.apiBase, abort.signal).then(suggestions => {
      if (!abort.signal.aborted) this.suggestions = suggestions;
    }).catch(() => { if (!abort.signal.aborted) this.suggestions = []; });
  }
  private async add(path?: string): Promise<void> {
    opener = this; if (this.host_) api = this.host_;
    if (path === undefined) await flow().open();
    else {
      if (addState.addBusy) return;
      await flow().submit(path);
    }
  }
  override render() {
    const view = getFirstUse();
    if (!view.reading && !(view.waiting ?? 0)) addState.actionMessage = null;
    // An in-flight add/error is kept accessible even while a new observation is settling.
    const pending = addState.showManualInput || addState.addBusy || !!addState.error;
    if (!pending && !(this.intro && view.empty)) return nothing;
    const paths = firstUseWatchedPaths();
    const watched = paths === null ? null : new Set(paths.map(normalizePath));
    const suggestions = watched === null ? [] : this.suggestions.filter((s, i, all) =>
      !watched.has(normalizePath(s.path))
      && all.findIndex(other => normalizePath(other.path) === normalizePath(s.path)) === i);
    const empty = this.intro && view.empty;
    return html`
      ${empty ? html`<section class="intro" aria-label="Start searching your files">
        <h1 data-testid="first-use-headline">Nothing to search yet</h1>
        <p>Add the folders you want JustSearch to read. Your files stay where they are, and nothing leaves this computer.</p>
        <jf-button variant="primary" label="Add a folder" data-add-folder .disabled=${addState.addBusy}
          .onActivate=${() => this.add()}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg><span class="add-label">Add a folder</span></jf-button>
        ${suggestions.length ? html`<div class="suggestions"><span>Or start with</span>
          ${suggestions.map((s, i) => html`${i > 0 ? i === suggestions.length - 1 ? ' or ' : ', ' : ''}<button type="button" aria-label=${`Add ${s.label}`} ?disabled=${addState.addBusy}
            @click=${() => this.add(s.path)}>${s.label}</button>`)}
        </div>` : nothing}
      </section>` : nothing}
      ${addState.error ? html`<p class="error" role="alert">${addState.error}</p>` : nothing}
      ${flow().renderForm()}`;
  }
}
if (!customElements.get('jf-getting-started')) customElements.define('jf-getting-started', GettingStarted);
