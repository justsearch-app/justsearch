// SPDX-License-Identifier: Apache-2.0
/**
 * ConversationHistory — dropdown for recent conversations (tempdoc 510 Design D).
 *
 * Follows the BookmarksPopover pattern: position:fixed, toggle on button click,
 * click-outside dismiss. Shows recent conversations by title + relative timestamp.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import {
  subscribeConversationList,
  loadConversations,
  deleteConversationWithCascade,
  type Conversation,
} from '../../state/conversationListStore.js';
import { confirmAsync } from '../ConfirmDialog.js';
import { emitEphemeralToast } from '../advisory/ephemeralToast.js';

function relativeTime(ms: number): string {
  const seconds = Math.round((Date.now() - ms) / 1000);
  if (seconds < 60) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  return `${days}d ago`;
}

export class ConversationHistory extends JfElement {
  static properties = {
    open: { type: Boolean, reflect: true },
    conversations: { state: true },
    loading: { state: true },
  };

  declare open: boolean;
  declare conversations: Conversation[];
  declare loading: boolean;

  private unsub: (() => void) | null = null;

  constructor() {
    super();
    this.open = false;
    this.conversations = [];
    this.loading = false;
  }

  static styles = css`
    :host {
      position: relative;
      display: inline-block;
    }
    .trigger {
      all: unset;
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      cursor: pointer;
      padding: 0.15rem 0.4rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
    }
    .trigger:hover {
      color: var(--text-primary);
      border-color: var(--accent-tint);
    }
    .dropdown {
      position: absolute;
      top: calc(100% + 4px);
      left: 0;
      z-index: var(--z-modal);
      min-width: 280px;
      max-width: 360px;
      max-height: 400px;
      overflow-y: auto;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
      padding: 0.25rem 0;
    }
    .dropdown-header {
      padding: 0.4rem 0.75rem;
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      text-transform: uppercase;
      letter-spacing: 0.04em;
      border-bottom: 1px solid var(--border-subtle);
    }
    .item-row {
      display: flex;
      align-items: stretch;
    }
    .item-row:hover {
      background: var(--surface-hover);
    }
    .item {
      flex: 1;
      display: block;
      padding: 0.5rem 0.75rem;
      background: none;
      border: none;
      color: var(--text-primary);
      font: inherit;
      font-size: var(--font-size-sm);
      text-align: left;
      cursor: pointer;
    }
    .item-delete {
      background: none;
      border: none;
      color: var(--text-muted);
      cursor: pointer;
      padding: 0 0.5rem;
      font-size: var(--font-size-md);
      opacity: 0;
      transition: opacity var(--duration-instant);
    }
    .item-row:hover .item-delete {
      opacity: 1;
    }
    .item-delete:hover {
      color: var(--text-danger);
    }
    .item-title {
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .branch-mark {
      color: var(--text-tint);
      margin-right: 0.3rem;
      font-weight: bold;
    }
    .item-meta {
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      margin-top: 0.1rem;
    }
    .empty {
      padding: 0.75rem;
      text-align: center;
      font-size: var(--font-size-xs);
      color: var(--text-muted);
    }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    this.unsub = subscribeConversationList((s) => {
      this.conversations = s.conversations;
      this.loading = s.loading;
    });
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsub?.();
  }

  toggle(): void {
    this.open = !this.open;
    if (this.open) void loadConversations();
  }

  private onSelect(conv: Conversation): void {
    this.open = false;
    this.dispatchEvent(
      new CustomEvent('conversation-select', {
        bubbles: true,
        composed: true,
        detail: { sessionId: conv.id, title: conv.title, shapeId: conv.shapeId },
      }),
    );
  }

  override render(): TemplateResult {
    return html`
      <button class="trigger" @click=${() => this.toggle()} title="Conversation history">
        History
      </button>
      ${this.open ? this.renderDropdown() : nothing}
    `;
  }

  private renderDropdown(): TemplateResult {
    return html`
      <div class="dropdown">
        <div class="dropdown-header">Recent conversations</div>
        ${this.loading
          ? html`<div class="empty">Loading…</div>`
          : this.conversations.length === 0
            ? html`<div class="empty">No conversations yet</div>`
            : this.conversations.map((c) => this.renderItem(c))}
      </div>
    `;
  }

  private async onDelete(c: Conversation, e: Event): Promise<void> {
    e.stopPropagation();
    const label = (c.title ?? c.firstUserMessage ?? 'this conversation').slice(0, 80);
    const ok = await confirmAsync({
      title: 'Delete conversation?',
      message: `Permanently delete "${label}"? This cannot be undone.`,
      variant: 'danger',
      confirmLabel: 'Delete',
    });
    if (!ok) return;
    // Slice 517 FIX-U1 — cascade-aware delete. If the backend reports the
    // conversation has child branches (HTTP 409), surface a second
    // confirm naming the branches (looked up from the loaded conversations
    // array) and recursively delete them on consent.
    let declined = false;
    const result = await deleteConversationWithCascade(c.id, async (childIds) => {
      const childLabels = childIds.map((id) => {
        const child = this.conversations.find((p) => p.id === id);
        const childLabel = child
          ? (child.title ?? child.firstUserMessage?.slice(0, 60) ?? 'Untitled')
          : id;
        return `• ${childLabel}`;
      });
      const message =
        `"${label}" has ${childIds.length} child branch${childIds.length === 1 ? '' : 'es'}:\n`
        + `${childLabels.join('\n')}\n\n`
        + `Delete the conversation and ${childIds.length === 1 ? 'its branch' : 'all branches'} together?`;
      const consented = await confirmAsync({
        title: 'Delete conversation and branches?',
        message,
        variant: 'danger',
        confirmLabel: `Delete all (${childIds.length + 1})`,
      });
      declined = !consented;
      return consented;
    });
    if (!result.ok && !declined) {
      const progress = result.deletedIds?.length
        ? `${result.deletedIds.length} branch${result.deletedIds.length === 1 ? '' : 'es'} deleted. ` : '';
      const failedRow = this.conversations.find(row => row.id === result.failedId);
      const failed = result.failedId && (result.error || result.deletedIds?.length) ? `Could not delete “${failedRow?.title ?? failedRow?.firstUserMessage ?? result.failedId}”. ` : '';
      emitEphemeralToast({ message: progress + failed + (result.error ?? 'Could not delete the conversation. Try again.'), severity: 'warning' });
      if (result.deletedIds?.length || !['STORE_LOCKED', 'CONVERSATION_BUSY'].includes(result.errorCode ?? '')) {
        await loadConversations();
      }
    }
  }

  private renderItem(c: Conversation): TemplateResult {
    const title = c.title ?? c.firstUserMessage?.slice(0, 50) ?? 'Untitled';
    // Slice 515 FIX-8 — branch indicator carries a hover-title naming the
    // parent (looked up from the same conversations array). Falls back to
    // a generic tooltip if the parent isn't in the loaded list.
    let branchMark: TemplateResult | typeof nothing = nothing;
    if (c.parentSessionId) {
      const parent = this.conversations.find((p) => p.id === c.parentSessionId);
      const parentLabel = parent
        ? (parent.title ?? parent.firstUserMessage?.slice(0, 60) ?? 'Untitled')
        : null;
      const tooltip = parentLabel
        ? `Branched from "${parentLabel}"`
        : 'Branched from another conversation';
      branchMark = html`<span class="branch-mark" title=${tooltip}>↪</span>`;
    }
    return html`
      <div class="item-row">
        <button class="item" @click=${() => this.onSelect(c)}>
          <div class="item-title">${branchMark}${title}</div>
          <div class="item-meta">
            ${c.messageCount === undefined ? nothing : `${c.messageCount} messages · `}${relativeTime(c.lastActiveAt)}
          </div>
        </button>
        <button class="item-delete" title="Delete conversation" @click=${(e: Event) => this.onDelete(c, e)}>×</button>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-conversation-history')) {
  customElements.define('jf-conversation-history', ConversationHistory);
}
