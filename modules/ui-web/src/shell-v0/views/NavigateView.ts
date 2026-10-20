// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 491 §9.D Phase E (C2) — typed view consuming `core.navigate-chat`.
 *
 * The first typed view to land on the FE substrate. Composes
 * `<jf-streaming-text-block>` + `<jf-navigation-receipt>` + a chat input;
 * posts the user's prompt to `/api/chat/url-emit`; routes SSE events to the
 * generated `CoreNavigateChatHandlers` interface via
 * `dispatchShapeEventToHandlers`.
 *
 * Mount path: programmatic via `<jf-chat-shape-mount shape-id="core.navigate-chat">`
 * resolves to this view through the `viewFactoryRegistry`. The shape is
 * registered at module-load time via the side-effect import below. No
 * user-clickable surface mount today; the standalone probe path remains
 * `POST /api/chat/url-emit`.
 *
 * Trust gate: the shape's `URLExtractor` runs the trust lattice server-side
 * before forwarding any Navigation envelope onto `/api/intent/stream`. This
 * view is a passive observer of those events; the navigation itself happens
 * via the chrome's `bootIntentStreamBridge` consuming the same SSE stream
 * (slice 492). The receipt renders inline so the user sees what just happened.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { friendlyStreamError } from '../utils/streamError.js';
import { ReasoningController } from '../controllers/ReasoningController.js';
import '../components/chat/ReasoningBlock.js';

import { dispatchShapeEventToHandlers } from '../../api/streams.js';
import type { CoreNavigateChatHandlers } from '../../api/generated/shape-handlers/core-navigate-chat.js';
import { registerViewFactory } from '../router/viewFactoryRegistry.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { streamViaHost } from '../plugin-api/pumpHostAiStream.js';

// Side-effect imports — these register the custom elements via their own
// module-level `customElements.define()` calls.
import '../components/chat/MarkdownBlock.js';
import '../components/chat/NavigationReceipt.js';
import { composerStyles } from '../components/Composer.js';
import '../components/Composer.js';

interface ReceiptEntry {
  addressKind: 'navigate' | 'invoke' | '';
  target: string;
  outcome: 'extracted' | 'dispatched' | 'forwarded' | 'rejected' | 'unresolved';
  reasonCode: string;
  message: string;
  key: string;
  suggestions?: Array<{ id: string; label: string }>;
}

export class NavigateView extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    host_: { attribute: false },
    inputDraft: { state: true },
    isStreaming: { state: true },
    streamingText: { state: true },
    receipts: { state: true },
    errorMessage: { state: true },
  };

  declare apiBase: string;
  declare host_: PluginHostApi | undefined;
  declare inputDraft: string;
  declare isStreaming: boolean;
  declare streamingText: string;
  declare receipts: ReceiptEntry[];
  declare errorMessage: string;

  readonly reasoning = new ReasoningController(() => this.requestUpdate());

  private abortController: AbortController | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.inputDraft = '';
    this.isStreaming = false;
    this.streamingText = '';
    this.receipts = [];
    this.errorMessage = '';
  }

  static styles = [composerStyles, css`
    :host {
      display: flex;
      flex-direction: column;
      width: 100%;
      height: 100%;
      padding: 1rem;
      gap: 0.75rem;
      box-sizing: border-box;
      color: var(--text-primary);
      font-family: system-ui, -apple-system, sans-serif;
    }
    .header {
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
      padding-bottom: 0.5rem;
      border-bottom: 1px solid var(--border-subtle);
    }
    .conversation {
      flex: 1;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }
    .user-message {
      font-style: italic;
      color: var(--text-secondary);
    }
    .error {
      padding: 0.5rem 0.75rem;
      background: var(--accent-danger-08);
      border: 1px solid var(--accent-danger-30);
      border-radius: 0.375rem;
      color: var(--text-danger);
      font-size: var(--font-size-sm);
    }
  `];

  override render(): TemplateResult {
    return html`
      <div class="header">
        <strong>Navigate Chat</strong> — emits <code>justsearch://</code> URLs that the
        chrome auto-dispatches.
      </div>
      <div class="conversation">
        ${this.reasoning.isThinking
          ? html`<jf-reasoning-block .controller=${this.reasoning}></jf-reasoning-block>`
          : nothing}
        ${this.reasoning.reasoningBlocks.length > 0 && !this.reasoning.isThinking
          ? this.reasoning.reasoningBlocks.map(
              (block) => html`<jf-reasoning-block
                .text=${block.text} .durationMs=${block.durationMs}
              ></jf-reasoning-block>`,
            )
          : nothing}
        ${this.streamingText || this.isStreaming
          ? html`<jf-markdown-block
              format="plain"
              .text=${this.streamingText}
              ?is-streaming=${this.isStreaming}
            ></jf-markdown-block>`
          : nothing}
        ${this.receipts.map(
          (r) => html`<jf-navigation-receipt
            outcome=${r.outcome}
            target=${r.target}
            address-kind=${r.addressKind}
            reason-code=${r.reasonCode}
            message=${r.message}
            .suggestions=${r.suggestions ?? []}
          ></jf-navigation-receipt>`,
        )}
        ${this.errorMessage
          ? html`<div class="error">${this.errorMessage}</div>`
          : nothing}
      </div>
      <jf-composer
        cancellable
        .value=${this.inputDraft}
        placeholder="Ask to navigate (e.g., 'take me to the Library view')…"
        ?streaming=${this.isStreaming}
        ?submit-disabled=${!this.inputDraft.trim()}
        submit-label="Send"
        streaming-label="Streaming…"
        cancel-label=${this.streamingText ? 'Stop' : 'Cancel'}
        @composer-input=${(e: CustomEvent<{ value: string }>) =>
          (this.inputDraft = e.detail.value)}
        @composer-submit=${() => void this.send()}
        @composer-cancel=${() => this.abortController?.abort()}
      ></jf-composer>
    `;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.addEventListener('suggestion-click', this.onSuggestionClick);
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.reasoning.destroy();
    this.removeEventListener('suggestion-click', this.onSuggestionClick);
    this.abortController?.abort();
    this.abortController = null;
  }

  private onSuggestionClick = (ev: Event): void => {
    const detail = (ev as CustomEvent<{ id: string; addressKind: string }>).detail;
    if (!detail?.id) return;
    this.dispatchEvent(new CustomEvent('navigate-with-context', {
      detail: { target: detail.id, state: {} },
      bubbles: true,
      composed: true,
    }));
  };

  /** Submit the current draft to `/api/chat/url-emit`. */
  private async send(): Promise<void> {
    const prompt = this.inputDraft.trim();
    if (!prompt || this.isStreaming) return;

    this.abortController = new AbortController();
    this.isStreaming = true;
    this.streamingText = '';
    this.reasoning.reset();
    this.receipts = [];
    this.errorMessage = '';
    this.inputDraft = '';

    // Tempdoc 521 §16.1 phase A.3 — host.ai consumer when wired; fallback URL otherwise.
    const fallbackUrl = (this.apiBase || '') + '/api/chat/url-emit';

    // Typed handler. Each method maps to one event in
    // `core.navigate-chat`'s eventSchema (per the codegen output at
    // modules/ui-web/src/api/generated/shape-handlers/core-navigate-chat.ts).
    const handlers: CoreNavigateChatHandlers = {
      onReasoningChunk: (payload: unknown) => { this.reasoning.handleReasoningChunk(payload); },
      onChunk: (payload: unknown) => {
        this.reasoning.endThinking();
        // Backend ConversationEngine emits `chunk` events with the text in
        // `payload.text` (string). Older substrate paths emit raw text as the
        // payload itself; accommodate both.
        const p = payload as Record<string, unknown> | string | null;
        const text =
          typeof p === 'string'
            ? p
            : typeof p?.text === 'string'
              ? (p.text as string)
              : '';
        if (text) this.streamingText = this.streamingText + text;
      },
      onDone: (_payload: unknown) => {
        this.reasoning.finalize();
        this.isStreaming = false;
      },
      onError: (payload: unknown) => {
        const p = payload as Record<string, unknown> | null;
        this.errorMessage =
          (p?.error as string) ??
          (p?.message as string) ??
          'shape stream error';
        this.isStreaming = false;
      },
      onNavigateUrlExtracted: (payload: unknown) => {
        const p = payload as Record<string, unknown>;
        const target = (p.target as string) ?? '';
        const idx = (p.index as number) ?? this.receipts.length;
        const addressKind =
          ((p.addressKind as string) === 'invoke' ? 'invoke' : 'navigate') as
            | 'invoke'
            | 'navigate';
        this.receipts = [
          ...this.receipts,
          {
            addressKind,
            target,
            outcome: 'extracted',
            reasonCode: '',
            message: '',
            key: `${idx}:${target}`,
          },
        ];
      },
      onNavigateUrlDispatched: (payload: unknown) => {
        const p = payload as Record<string, unknown>;
        const target = (p.target as string) ?? '';
        const outcome =
          ((p.outcome as string) === 'forwarded' ? 'forwarded' : 'dispatched') as
            | 'forwarded'
            | 'dispatched';
        const idx = (p.index as number) ?? -1;
        const key = `${idx}:${target}`;
        this.receipts = this.receipts.map((r) =>
          r.key === key ? { ...r, outcome } : r,
        );
      },
      onNavigateUrlRejected: (payload: unknown) => {
        const p = payload as Record<string, unknown>;
        const target = (p.target as string) ?? '';
        const idx = (p.index as number) ?? -1;
        const key = `${idx}:${target}`;
        const reasonCode = (p.reason as string) ?? '';
        const message = (p.message as string) ?? '';
        this.receipts = this.receipts.map((r) =>
          r.key === key ? { ...r, outcome: 'rejected', reasonCode, message } : r,
        );
      },
      onIntentResolution: (payload: unknown) => {
        const p = payload as Record<string, unknown>;
        const target = (p.target as string) ?? '';
        const idx = (p.index as number) ?? -1;
        const key = `${idx}:${target}`;
        const resolution = p.resolution as Record<string, unknown> | undefined;
        const status = (resolution?.status as string) ?? '';
        if (status === 'resolved' || status === 'redirected') return;
        const alternatives = (resolution?.alternatives as Array<Record<string, unknown>>) ?? [];
        const diagnosis = resolution?.diagnosis as Record<string, unknown> | undefined;
        const suggestions = alternatives.map(a => ({
          id: (a.id as string) ?? '',
          label: (a.label as string) ?? (a.id as string) ?? '',
        }));
        this.receipts = this.receipts.map((r) =>
          r.key === key
            ? { ...r, outcome: 'unresolved' as const, message: (diagnosis?.detail as string) ?? '', suggestions }
            : r,
        );
      },
    };

    try {
      await streamViaHost({
        host_: this.host_,
        shapeId: 'core.navigate-chat',
        fallbackUrl,
        body: { prompt },
        onEvent: (event, payload) => {
          dispatchShapeEventToHandlers(
            handlers as unknown as Record<string, unknown>,
            event,
            payload,
          );
        },
        signal: this.abortController.signal,
      });
    } catch (err) {
      if (!(err instanceof Error && err.name === 'AbortError')) {
        this.errorMessage = friendlyStreamError(err);
      }
    } finally {
      this.isStreaming = false;
      this.abortController = null;
    }
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-navigate-view')) {
  customElements.define('jf-navigate-view', NavigateView);
}

// Register the view factory at module-load time so `<jf-chat-shape-mount
// shape-id="core.navigate-chat">` resolves correctly. The factory mints a
// fresh `<jf-navigate-view>` element on each mount.
registerViewFactory('core.navigate-chat', 'jf-navigate-view');
