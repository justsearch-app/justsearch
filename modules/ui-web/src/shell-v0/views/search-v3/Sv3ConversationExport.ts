// SPDX-License-Identifier: Apache-2.0
import { css, html, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { ModalController } from '../../primitives/modalController.js';
import { copyToClipboard } from '../../utils/clipboardCopy.js';
import { emitEphemeralToast } from '../../components/advisory/ephemeralToast.js';
import '../../components/Button.js';
import { sv3Shared } from './sv3-shared-styles.js';
import type { Sv3Session } from './sv3-sessions.js';
import {
  EXPORT_LOCKED, loadConversationExport, serializeConversationJson, serializeConversationMarkdown,
  type ConversationExport,
} from './sv3-export.js';

/** One export ceremony. Preparing before the copy/save gesture preserves clipboard activation. */
export class Sv3ConversationExport extends JfElement {
  static properties = {
    locked: { type: Boolean },
    receipt: { state: true },
    busy: { state: true },
    visible: { state: true },
    title: { type: String },
  };
  static styles = [sv3Shared, css`
    :host { display: contents; }
    dialog {
      box-sizing: border-box;
      inline-size: min(28rem, calc(100% - var(--space-8)));
      max-block-size: calc(100% - var(--space-8));
      overflow: auto;
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      padding: var(--space-6);
      background: var(--background);
      color: var(--foreground);
    }
    dialog::backdrop { background: var(--dialog-backdrop); }
    h2 { font-size: var(--font-size-sv3-base); overflow-wrap: anywhere; }
    .formats { display: flex; flex-wrap: wrap; gap: var(--space-4); }
    fieldset { min-inline-size: 0; flex: 1; border: 1px solid var(--border); }
    jf-button { margin-block: var(--space-1); }
    p { overflow-wrap: anywhere; }
    @media (forced-colors: active) {
      dialog, fieldset { border-color: CanvasText; }
    }
  `];
  declare locked: boolean;
  declare receipt: string;
  declare busy: boolean;
  declare visible: boolean;
  private prepared: ConversationExport | null = null;
  private read: AbortController | null = null;
  private readonly modal = new ModalController(this, {
    dialog: () => this.shadowRoot?.querySelector('dialog'),
  });

  constructor() {
    super();
    this.locked = false;
    this.receipt = '';
    this.busy = false;
    this.visible = false;
    this.title = '';
  }

  async open(session: Sv3Session, apiBase: string): Promise<void> {
    this.read?.abort();
    const read = new AbortController();
    this.read = read;
    this.visible = true;
    this.title = session.title || 'Untitled conversation';
    this.prepared = null;
    this.busy = true;
    this.receipt = this.locked ? EXPORT_LOCKED : 'Loading the complete conversation…';
    await this.updateComplete;
    this.modal.open();
    if (this.locked) { this.busy = false; return; }
    try {
      const prepared = await loadConversationExport(apiBase, session, read.signal);
      if (read.signal.aborted || this.locked) return;
      this.prepared = prepared;
      this.receipt = prepared.turns.length ? 'Ready to export. Turns still streaming are left out.' : 'There is nothing to export. Turns still streaming are left out.';
    } catch (error) {
      if (!read.signal.aborted) this.receipt = error instanceof Error
        && (error.message === EXPORT_LOCKED || error.message.startsWith('Export failed:'))
        ? error.message : 'Export failed: the complete conversation could not be loaded. Try again.';
    } finally {
      if (this.read === read) this.busy = false;
    }
  }

  protected override updated(changed: Map<string, unknown>): void {
    if (changed.has('locked') && this.locked) {
      this.read?.abort();
      this.prepared = null;
      this.busy = false;
      this.receipt = EXPORT_LOCKED;
    }
  }

  private cancel(): void {
    const cancelled = this.busy && this.prepared === null;
    this.read?.abort();
    this.read = null;
    this.prepared = null;
    this.busy = false;
    this.modal.close();
    this.visible = false;
    if (cancelled) emitEphemeralToast({ message: 'Export cancelled.' });
  }

  protected override settleTransients(): void {
    this.cancel();
  }

  private async deliver(format: 'Markdown' | 'JSON', action: 'copy' | 'save'): Promise<void> {
    const prepared = this.prepared;
    if (this.locked) { this.receipt = EXPORT_LOCKED; return; }
    if (this.busy || !prepared?.turns.length) return;
    const text = format === 'JSON' ? serializeConversationJson(prepared) : serializeConversationMarkdown(prepared);
    this.busy = true;
    const read = this.read;
    try {
      if (action === 'copy') {
        const copied = await copyToClipboard(text);
        if (this.read !== read || read?.signal.aborted || this.locked) return;
        this.receipt = copied ? `${format} copied.` : `Copy failed. ${format} was not copied. Try saving a file.`;
      } else {
        const url = URL.createObjectURL(new Blob([text], { type: format === 'JSON' ? 'application/json' : 'text/markdown;charset=utf-8' }));
        const anchor = document.createElement('a');
        try {
          anchor.href = url;
          anchor.download = `justsearch-conversation-${prepared.conversationId.replace(/[^a-zA-Z0-9_-]/g, '_')}.${format === 'JSON' ? 'json' : 'md'}`;
          document.body.appendChild(anchor);
          anchor.click();
          this.receipt = `${format} download started. Check your downloads; saving or cancellation is handled by your browser.`;
        } finally {
          anchor.remove();
          URL.revokeObjectURL(url);
        }
      }
    } catch {
      this.receipt = `${action === 'copy' ? 'Copy' : 'Download'} failed. Try again.`;
    } finally {
      if (this.read === read) this.busy = false;
    }
  }

  render(): TemplateResult {
    if (!this.visible) return html``;
    const unavailable = this.locked || this.busy || !this.prepared?.turns.length;
    return html`<dialog aria-labelledby="export-heading" aria-describedby="export-receipt"
      @cancel=${(event: Event) => { event.preventDefault(); this.cancel(); }}>
      <h2 id="export-heading">Export ${this.title}</h2>
      <p id="export-receipt" role="status">${this.receipt}</p>
      <div class="formats">
        ${(['Markdown', 'JSON'] as const).map((format) => html`<fieldset>
          <legend>${format}</legend>
          <jf-button variant="secondary" ?disabled=${unavailable}
            .onActivate=${() => this.deliver(format, 'copy')}>Copy ${format}</jf-button>
          <jf-button variant="secondary" ?disabled=${unavailable}
            .onActivate=${() => this.deliver(format, 'save')}>Save ${format}</jf-button>
        </fieldset>`)}
      </div>
      <jf-button variant="secondary" .onActivate=${() => this.cancel()}>${this.busy && this.prepared === null ? 'Cancel export' : 'Close export'}</jf-button>
    </dialog>`;
  }
}
customElements.define('jf-sv3-conversation-export', Sv3ConversationExport);
declare global {
  interface HTMLElementTagNameMap { 'jf-sv3-conversation-export': Sv3ConversationExport; }
}
