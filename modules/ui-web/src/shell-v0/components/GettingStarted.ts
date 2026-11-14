// SPDX-License-Identifier: Apache-2.0
import { html, css, nothing, type PropertyValues } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { getFirstUse, subscribeFirstUse, firstUseWatchedPaths, hideGettingStarted } from '../state/firstUse.js';
import { getSuggestedRoots, type SuggestedRoot } from '../../api/domains/indexing.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { FolderAddFlow, emptyFolderAddState, focusFolderAdd } from '../controllers/FolderAddFlow.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';
import { icon } from './Icon.js';
import './Button.js';
import './WalkthroughCard.js';

const mounted = new Set<GettingStarted>();
let addState = emptyFolderAddState();
let addFlow: FolderAddFlow | null = null;
let api: PluginHostApi | undefined;
let opener: GettingStarted | null = null;
const accepted = new Set<string>();
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
  });
}
/** Test isolation only; production preserves pending work when Search is left. */
export function resetFirstUseAddForTest(): void {
  addFlow?.dispose(); addFlow = null; addState = emptyFolderAddState(); accepted.clear(); api = undefined; opener = null;
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
    .intro { text-align: center; padding-block: var(--jf-space-6); }
    h1 { font: inherit; font-size: var(--font-size-sv3-display, var(--font-size-headline)); font-variation-settings: "opsz" 32; margin: 0 0 var(--jf-space-3); }
    p { margin: var(--jf-space-2) 0; color: var(--text-secondary); }
    .intro > jf-button { margin-block: var(--jf-space-4); }
    .suggestions { display: flex; flex-wrap: wrap; justify-content: center; gap: var(--jf-space-2); }
    .suggestions jf-button { min-inline-size: 0; max-inline-size: 100%; }
    .suggestions span { display: block; white-space: normal; overflow-wrap: anywhere; text-align: start; }
    .path { color: var(--text-secondary); font-size: var(--font-size-xs); }
    .card { border: var(--frame-hairline) solid var(--border-subtle); border-radius: var(--frame-control-radius);
      background: var(--surface-1); margin-block-start: var(--jf-space-6); margin-inline: auto;
      max-inline-size: var(--measure-notice); text-align: start; overflow-wrap: anywhere; }
    header { display: flex; flex-wrap: wrap; align-items: center; gap: var(--jf-space-3); padding: var(--jf-space-4); }
    header h2 { font-size: var(--font-size-md); font-weight: 500; margin: 0; }
    header p { flex: 1; font-size: var(--font-size-xs); }
    progress { flex-basis: 100%; inline-size: 100%; block-size: var(--jf-space-2); accent-color: var(--text-tint); }
    li[aria-current='step'] { border-inline-start: var(--frame-focus-width) solid var(--text-secondary); }
    ol { list-style: none; margin: 0; padding: 0; }
    li { display: flex; flex-wrap: wrap; align-items: center; gap: var(--jf-space-3);
      padding: var(--jf-space-4); border-block-start: var(--frame-hairline) solid var(--border-subtle); }
    .step { flex: 1; min-inline-size: 0; }
    .step p { margin-block-end: 0; }
    .mark { font-weight: 600; color: var(--text-secondary); }
    .progress { text-align: center; margin-block: var(--jf-space-4); }
    .add-row { display: flex; flex-wrap: wrap; gap: var(--jf-space-2); margin-block: var(--jf-space-3); }
    input { flex: 1; min-inline-size: 0; max-inline-size: 100%; font: inherit; color: var(--text-primary);
      background: var(--surface-1); border: var(--frame-hairline) solid var(--border-emphasized);
      border-radius: var(--frame-control-radius); padding: var(--jf-space-2); }
    input:focus-visible { outline: var(--frame-focus-width) solid var(--focus-ring-color); }
    .message, .error, .add-preview { overflow-wrap: anywhere; margin-block: var(--jf-space-2); }
    .error { color: var(--text-danger); }
    @media (forced-colors: active) { .mark, .error, .path, p { color: CanvasText; } .card, input { border-color: CanvasText; } }
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
      const key = normalizePath(path);
      if (accepted.has(key) || addState.addBusy) return;
      await flow().submit(path);
      if (!addState.error && !addState.showManualInput) { accepted.add(key); notify(); }
    }
  }
  override render() {
    const view = getFirstUse();
    if (!view.reading && !(view.waiting ?? 0)) addState.actionMessage = null;
    // An in-flight add/error is kept accessible even while a new observation is settling.
    const pending = addState.showManualInput || addState.addBusy || !!addState.error;
    if (!view.visible && !pending && !(this.intro && view.empty)) return nothing;
    const paths = firstUseWatchedPaths();
    const watched = paths === null ? null : new Set(paths.map(normalizePath));
    const suggestions = watched === null ? [] : this.suggestions.filter((s, i, all) =>
      !watched.has(normalizePath(s.path)) && !accepted.has(normalizePath(s.path))
      && all.findIndex(other => normalizePath(other.path) === normalizePath(s.path)) === i);
    const empty = this.intro && view.empty;
    return html`
      ${empty ? html`<section class="intro" aria-label="Start searching your files">
        <h1 data-testid="first-use-headline">Nothing to search yet</h1>
        <p>Add the folders you want JustSearch to read. Your files stay where they are,<br />and nothing leaves this computer.</p>
        <jf-button variant="primary" label="Add a folder" data-add-folder .disabled=${addState.addBusy}
          .onActivate=${() => this.add()}>Add a folder</jf-button>
        ${suggestions.length ? html`<p>Or start with a folder you already have.</p><div class="suggestions">
          ${suggestions.map(s => html`<jf-button label=${`Add ${s.label}`} .disabled=${addState.addBusy}
            .onActivate=${() => this.add(s.path)}><span>${s.label}<span class="path">${s.path}</span></span></jf-button>`)}
        </div>` : nothing}
      </section>` : nothing}
      ${view.coreVisible && view.progress && !empty ? html`<p class="progress" data-testid="first-use-progress" role="status" aria-live="polite">${view.progress}</p>` : nothing}
      <div class="message" role="status" aria-live="polite">${view.reading || (view.waiting ?? 0) > 0 ? addState.actionMessage ?? '' : ''}</div>
      ${addState.error ? html`<p class="error" role="alert">${addState.error}</p>` : nothing}
      ${flow().renderForm()}
      ${view.visible ? html`<section class="card" data-testid="getting-started-card" aria-label="Getting started">
        <header><h2>Getting started</h2>${view.coreVisible ? html`<p role="status" aria-live="polite">${view.currentStep} of 3 · the last step is optional</p>` : nothing}
          <jf-button variant="ghost" label="Hide Getting started" .onActivate=${hideGettingStarted}>Hide</jf-button>
          ${view.coreVisible ? html`<progress role="progressbar" aria-label="Getting started step" aria-valuemin="1" aria-valuemax="3"
            aria-valuenow=${view.currentStep} max="3" value=${view.currentStep}></progress>` : nothing}</header>
        ${view.coreVisible ? html`<ol>${[
          { title: 'Add folders to search', body: view.folderUnderWay ? 'Under way. Your folder is being read; search works as soon as the first files are searchable.' : view.folderNeedsAttention
            ? 'No files are searchable yet. Open Library to check what is being read or skipped.'
            : view.done[0] ? 'Your files stay where they are.' : 'Pick what JustSearch should read. Files stay where they are.',
            action: view.folderNeedsAttention ? 'Open Library' : view.done[0] ? 'Add another' : 'Add a folder',
            run: () => view.folderNeedsAttention ? requestSurfaceNavigation('core.library-surface') : this.add() },
          { title: 'Try a search', body: (view.searchable ?? 0) > 0 ? 'Works now for the files read so far.' : 'Works as soon as the first files are read.',
            action: '', run: () => {} },
          { title: 'Optional: answers and search by meaning', body: 'Adds AI models that run on this computer.',
            action: 'Open AI Brain', run: () => requestSurfaceNavigation('core.brain-surface') },
        ].map((step, i) => html`<li aria-current=${i + 1 === view.currentStep ? 'step' : nothing}><span class="mark" role="img" aria-label=${view.done[i] ? 'Done' : i === 0 && view.folderUnderWay ? 'Under way' : `Step ${i + 1}`}>${view.done[i] ? icon({ name: 'check-circle-2' }) : i + 1}</span>
          <div class="step">${step.title}<p>${step.body}</p></div>
          ${step.action ? html`<jf-button variant="ghost" label=${step.action} ?data-add-folder=${i === 0 && !empty}
            .disabled=${i === 0 && addState.addBusy} .onActivate=${step.run}>${step.action}</jf-button>` : nothing}</li>`)}
        </ol>` : nothing}
        <jf-walkthrough-card embedded></jf-walkthrough-card>
      </section>` : nothing}`;
  }
}
if (!customElements.get('jf-getting-started')) customElements.define('jf-getting-started', GettingStarted);
