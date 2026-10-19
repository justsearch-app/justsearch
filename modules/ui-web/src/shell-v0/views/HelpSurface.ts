// SPDX-License-Identifier: Apache-2.0
/**
 * HelpSurface — Lit-side Help surface (slice 451 phase 9).
 *
 * Self-mounting Surface. Mostly static content (FAQ, shortcuts,
 * privacy, network info). Diagnostics interactions are projected through
 * the aggregate substrate's `<jf-operation>` component. Export-path feedback
 * remains visible locally; the redacted diagnostic-summary payload is copied
 * directly to the clipboard and is never stored or rendered.
 *
 * Side-effect registers `<jf-help-surface>` for the chrome dispatcher.
 * The shell-v0 barrel (loaded eagerly via main.jsx per slice 450 §2.5)
 * pulls this file into the boot graph automatically.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { surfaceScrollLayoutStyles } from '../primitives/surfaceLayout.js';
import { ReceiptController } from '../primitives/receiptController.js';
import { copyToClipboard } from '../utils/clipboardCopy.js';
import { icon } from '../components/Icon.js';
import '../aggregate-substrate/components/JfOperation.js';
// 569 §15 — render the Help reference content through the projection engine (the 3rd real surface).
import { activeBodyFor, subscribePresentation } from '../state/presentationRuntime.js';
import { HELP_REFERENCE_REGION } from '../themes/builtinPresentations.js';
import { summarizeWireDrift } from '../../api/wireDriftTelemetry.js';
import '../components/DeclaredSurface.js';
import type {
  OpSuccessEventDetail,
  OpErrorEventDetail,
} from '../components/OpButton.js';

interface FaqItem {
  q: string;
  a: string;
}

interface ShortcutRow {
  keys: string;
  desc: string;
}

const FAQ: FaqItem[] = [
  {
    q: 'How do I add files to search?',
    a: 'Go to Library and click "Add Folder" to index a directory. JustSearch will automatically scan and index all supported files.',
  },
  {
    q: 'What file types are supported?',
    a: 'JustSearch supports PDFs, Word documents, HTML, Markdown, plain text, code files (JS, TS, Python, etc.), and images, whose text is read with OCR.',
  },
  {
    q: 'How does AI summarization work?',
    a: 'Select text in a document and choose "Summarize" from the menu that appears, or type a request such as "summarize the budget notes" in the search bar. Summaries need the local chat model, which is installed only on a machine with an NVIDIA GPU with at least 7.5 GB of video memory. It runs on your machine; no data is sent externally.',
  },
  {
    q: 'Why is indexing slow?',
    a: 'Initial indexing can take time for large folders. Once complete, incremental updates are fast. Check the Health tab of the System view for indexing status.',
  },
  {
    q: 'How do I ask the AI a question?',
    a: 'Type your question in the search bar. A query that reads like a question (for example one with "?" or starting with "how" or "what") is answered with citations from your files; press Ctrl+Enter to search it instead. This needs the local chat model (see above).',
  },
];

// Tempdoc 586 P-3 — only shortcuts that ACTUALLY fire are listed. The previous table advertised
// `/` (focus search) and `/` (command mode) — unimplemented V1 deferrals at the time (the registered
// bindings are in Shell.ts; `/` now focuses the Search view's bar there, and handleGlobalKey owns the
// Alt+arrow, Ctrl+D and Ctrl+Shift+A chords; Composer.ts owns the search-bar keys) — plus
// `??` (a documented search-box query *prefix* for the AI, not a keycap) and `Alt+1–4` agent tabs
// (the standalone agent surface was retired into the unified chat window). Listing shortcuts that
// don't work is worse than a short list, so the table now mirrors the real bindings.
const SHORTCUTS: ShortcutRow[] = [
  { keys: 'Ctrl / ⌘ + K', desc: 'Open the command palette' },
  { keys: 'Ctrl / ⌘ + Z', desc: 'Undo' },
  { keys: 'Ctrl / ⌘ + Shift + Z', desc: 'Redo' },
  { keys: 'Ctrl / ⌘ + L or /', desc: 'Put the cursor in the search bar (in the Search view; / only when not typing)' },
  { keys: 'Enter', desc: 'Run the query (in the search bar)' },
  { keys: 'Shift + Enter', desc: 'New line in the search bar' },
  { keys: 'Ctrl + Enter', desc: 'Run the query the other way: ask instead of search, or search instead of ask' },
  { keys: 'Alt + ← / →', desc: 'Go back / forward between views (when not typing)' },
  { keys: 'Ctrl + D', desc: 'Bookmark the current view, or remove its bookmark (when not typing)' },
  { keys: 'Ctrl + Shift + A', desc: 'Open the Search view to ask the AI (when not typing; needs the chat model)' },
  { keys: 'Esc', desc: 'Close an open menu, popover or dialog' },
  // Tempdoc 857 PR-A — J/K is the only keyboard navigation the product has for a run's steps, and it
  // was the least discoverable thing in it: a raw window listener, unregistered in the palette and
  // absent from this table. P-3's rule cuts both ways — a table that omits a binding that DOES fire
  // is as partial a mirror as one that lists bindings that don't. The scope note is part of the row
  // because this one is a window listener that acts only on a Delegate run.
  { keys: 'J / K', desc: 'Step to the next / previous step of a run (a Delegate run in a wide window, when not typing)' },
];

// Labels are the operation catalog's (registry-operation.en.properties) as rendered by the Library
// header and the Health quick actions (LibrarySurface.ts, HealthSurface.ts renderActions).
const TROUBLESHOOTING: string[] = [
  'If search results look stale, use "Reindex" in Library or in the Health tab of the System view.',
  'If indexing seems stuck, use "Recover component" in the Health tab of the System view (shown when Settings → Developer → View tier is Operator), or "Force Rebuild" there to rebuild the whole index from scratch, which takes a long time.',
  'Use "Copy Diagnostic Summary" above before reporting a bug; review it before posting.',
];

const NETWORK: string[] = [
  'Local app traffic uses loopback (not a network service).',
  'AI model downloads occur only when you explicitly install models.',
  'Future "Online" features will be clearly labeled and opt-in.',
];

export class HelpSurface extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    expanded: { state: true },
    exportPath: { state: true },
    exportError: { state: true },
    copyText: { attribute: false },
  };

  declare apiBase: string;
  declare expanded: number | null;
  declare exportPath: string | null;
  declare exportError: string | null;
  /** Injectable clipboard seam; production uses the shared failure-safe utility. */
  declare copyText: (text: string) => Promise<boolean>;

  private readonly summaryReceipt = new ReceiptController(this);

  constructor() {
    super();
    this.apiBase = '';
    this.expanded = null;
    this.exportPath = null;
    this.exportError = null;
    this.copyText = (text: string) => copyToClipboard(text);
  }

  // 569 §15 — re-render when the active presentation changes (the declared reference region appears
  // when CORE_DECLARED is applied, reverts when cleared/quarantined — degrade-never-fail).
  private presentationUnsub: (() => void) | null = null;

  override connectedCallback(): void {
    super.connectedCallback();
    this.presentationUnsub = subscribePresentation(() => this.requestUpdate());
  }

  /** Tempdoc 609 §R (S1) — declarative transient reset (JfElement default applies it on hide): the
   *  diagnostics-export result path + error. The `expanded` FAQ choice is recoverable, KEPT. */
  static override transientState = { exportPath: null, exportError: null };

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.presentationUnsub?.();
    this.presentationUnsub = null;
  }

  /**
   * 569 §15 — the Help reference content, rendered through the engine when CORE_DECLARED declares it
   * (the 3rd real surface inverted); otherwise the built-in Lit render (degrade-never-fail). The
   * shortcuts/troubleshooting/network data flows in as the declared body's `data`.
   */
  private renderReferenceRegion(): TemplateResult {
    const body = activeBodyFor(HELP_REFERENCE_REGION);
    if (!body) {
      return html`
        ${this.renderShortcuts()}
        <section style="margin-top: 1rem">
          <div class="panel">
            <div class="panel-title">Quick troubleshooting</div>
            <ul>
              ${TROUBLESHOOTING.map((t) => html`<li>${t}</li>`)}
            </ul>
          </div>
        </section>
        <section style="margin-top: 1rem">
          <div class="panel">
            <div class="panel-title">${icon({ name: 'wifi', size: 16 })} Network activity</div>
            <ul>
              ${NETWORK.map((n) => html`<li>${n}</li>`)}
            </ul>
          </div>
        </section>
      `;
    }
    return html`<jf-declared-surface
      .declaration=${body}
      .data=${{ shortcuts: SHORTCUTS, troubleshooting: TROUBLESHOOTING, network: NETWORK }}
      .enabled=${true}
    ></jf-declared-surface>`;
  }

  static styles = [
    surfaceScrollLayoutStyles,
    css`
    .header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;
      padding-bottom: 1rem;
      border-bottom: 1px solid var(--border-subtle);
      margin-bottom: 1rem;
    }
    .header h2 {
      margin: 0;
      font-size: var(--font-size-lg);
      font-weight: 600;
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    .header .subtitle {
      margin: 0.25rem 0 0 0;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .diagnostic-actions {
      display: flex;
      align-items: flex-start;
      justify-content: flex-end;
      flex-wrap: wrap;
      gap: 0.5rem;
    }
    .diagnostic-action {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
    }
    @media (max-width: 48rem) {
      .header {
        flex-direction: column;
      }
      .diagnostic-actions {
        justify-content: flex-start;
      }
      .diagnostic-action {
        align-items: flex-start;
      }
    }
    .export-status {
      margin-top: 0.5rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .export-error {
      color: var(--text-danger);
    }
    .export-status .path {
      font-family: monospace;
    }
    .summary-receipt {
      min-height: 1rem;
      margin-top: 0.5rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .summary-receipt.error {
      color: var(--text-danger);
    }
    .grid {
      display: grid;
      grid-template-columns: 1fr;
      gap: 1.5rem;
    }
    @media (min-width: 64rem) {
      .grid {
        grid-template-columns: 1fr 1fr;
      }
    }
    section {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    h3 {
      margin: 0 0 0.25rem 0;
      font-size: var(--font-size-xs);
      font-weight: 600;
      letter-spacing: 0.05em;
      text-transform: uppercase;
      color: var(--text-secondary);
      display: flex;
      align-items: center;
      gap: 0.4rem;
    }
    .faq-item {
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      overflow: hidden;
    }
    .faq-q {
      width: 100%;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      padding: 0.75rem 1rem;
      background: transparent;
      border: none;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
      cursor: pointer;
      text-align: left;
    }
    .faq-q:hover {
      background: var(--surface-hover);
    }
    .faq-a {
      padding: 0 1rem 0.75rem 1rem;
      font-size: var(--font-size-xs);
      line-height: 1.5;
      color: var(--text-secondary);
    }
    .chev {
      transition: transform var(--duration-fast) var(--ease-standard);
    }
    .chev.open {
      transform: rotate(180deg);
    }
    .panel {
      padding: 1rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
    }
    .panel-title {
      font-size: var(--font-size-sm);
      font-weight: 500;
      color: var(--text-secondary);
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 0.5rem;
    }
    .panel ul {
      margin: 0;
      padding-left: 1.25rem;
      font-size: var(--font-size-xs);
      line-height: 1.6;
      color: var(--text-secondary);
    }
    .panel ul li + li {
      margin-top: 0.25rem;
    }
    .shortcuts {
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      overflow: hidden;
    }
    .shortcut-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 0.5rem 1rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .shortcut-row + .shortcut-row {
      border-top: 1px solid var(--border-subtle);
    }
    kbd {
      padding: 0.125rem 0.4rem;
      background: var(--surface-tertiary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      font-family: monospace;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .jf-icon-spin {
      animation: jf-spin 1s linear infinite;
    }
  `,
  ];

  private handleOpSuccess(e: CustomEvent<OpSuccessEventDetail>): void {
    this.exportError = null;
    const path = e.detail?.structuredData?.['path'];
    this.exportPath = typeof path === 'string' ? path : '';
  }

  private handleOpError(e: CustomEvent<OpErrorEventDetail>): void {
    this.exportPath = null;
    const msg = e.detail?.message;
    this.exportError = typeof msg === 'string' ? msg : 'Diagnostics export failed.';
  }

  private async handleSummarySuccess(e: CustomEvent<OpSuccessEventDetail>): Promise<void> {
    const summary = e.detail?.structuredData?.['summary'];
    const copied = typeof summary === 'string' && await this.copyText(summary);
    this.summaryReceipt.flash(
      copied ? 'Diagnostic summary copied.' : 'Could not copy diagnostic summary.',
      { key: copied ? 'summary-success' : 'summary-error' },
    );
  }

  private handleSummaryError(_e: CustomEvent<OpErrorEventDetail>): void {
    this.summaryReceipt.flash('Could not copy diagnostic summary.', { key: 'summary-error' });
  }

  private renderFaq(): TemplateResult {
    return html`
      <section>
        <h3>Frequently asked questions</h3>
        <div>
          ${FAQ.map(
            (item, idx) => html`
              <div class="faq-item">
                <button
                  class="faq-q"
                  @click=${() => (this.expanded = this.expanded === idx ? null : idx)}
                >
                  <span>${item.q}</span>
                  <span class="chev ${this.expanded === idx ? 'open' : ''}">
                    ${icon({ name: 'chevron-down', size: 16 })}
                  </span>
                </button>
                ${this.expanded === idx
                  ? html`<div class="faq-a">${item.a}</div>`
                  : nothing}
              </div>
            `,
          )}
        </div>
      </section>
    `;
  }

  private renderShortcuts(): TemplateResult {
    return html`
      <section>
        <h3>${icon({ name: 'keyboard', size: 14 })} Keyboard shortcuts</h3>
        <div class="shortcuts">
          ${SHORTCUTS.map(
            (s) => html`
              <div class="shortcut-row">
                <span>${s.desc}</span>
                <kbd>${s.keys}</kbd>
              </div>
            `,
          )}
        </div>
      </section>
    `;
  }

  override render(): TemplateResult {
    return html`
      <div class="header">
        <div>
          <h2>${icon({ name: 'help-circle', size: 18 })} Help &amp; Support</h2>
          <p class="subtitle">Copy a redacted summary or export the full local diagnostics bundle.</p>
        </div>
        <div class="diagnostic-actions">
          <div class="diagnostic-action">
            <jf-operation
              operation-id="core.copy-diagnostic-summary"
              context="button"
              api-base=${this.apiBase}
              @op-success=${(e: CustomEvent<OpSuccessEventDetail>) =>
                void this.handleSummarySuccess(e)}
              @op-error=${(e: CustomEvent<OpErrorEventDetail>) =>
                this.handleSummaryError(e)}
            ></jf-operation>
            <div
              class="summary-receipt ${this.summaryReceipt.isFlashing('summary-error') ? 'error' : ''}"
              role="status"
              aria-live="polite"
            >${this.summaryReceipt.active?.message ?? nothing}</div>
          </div>
          <div class="diagnostic-action">
            <jf-operation
              operation-id="core.export-diagnostics"
              context="button"
              api-base=${this.apiBase}
              .args=${{ feTelemetry: { wireDrift: summarizeWireDrift() } }}
              @op-success=${(e: CustomEvent<OpSuccessEventDetail>) =>
                this.handleOpSuccess(e)}
              @op-error=${(e: CustomEvent<OpErrorEventDetail>) =>
                this.handleOpError(e)}
            ></jf-operation>
            ${this.exportPath !== null
              ? html`<div class="export-status">
                  Saved to: <span class="path">${this.exportPath}</span>
                </div>`
              : nothing}
            ${this.exportError
              ? html`<div class="export-status export-error">${this.exportError}</div>`
              : nothing}
          </div>
        </div>
      </div>

      <div class="grid">
        <div>
          ${this.renderFaq()}

          <section style="margin-top: 1.5rem">
            <div class="panel">
              <div class="panel-title">
                ${icon({ name: 'shield', size: 16 })} Local-first
              </div>
              <p style="margin: 0; font-size: var(--font-size-xs); line-height: 1.6; color: var(--text-secondary)">
                Your files stay on this machine. Search and indexing run locally,
                and the UI only talks to the local backend over loopback.
              </p>
            </div>
          </section>
        </div>

        <div>
          <!-- 569 §15 — the reference content (shortcuts + troubleshooting + network) renders
               THROUGH the projection engine; the FAQ + local-first + export op stay surface-owned. -->
          ${this.renderReferenceRegion()}
        </div>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-help-surface')) {
  customElements.define('jf-help-surface', HelpSurface);
}
