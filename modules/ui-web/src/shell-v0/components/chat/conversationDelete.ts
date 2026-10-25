// SPDX-License-Identifier: Apache-2.0
import { html, type TemplateResult } from 'lit';
import { confirmAsync } from '../ConfirmDialog.js';
import type { DeleteCascadeResult, DeleteTreeEntry } from '../../state/conversationListStore.js';

function name(row: DeleteTreeEntry): string {
  return row.title || row.firstUserMessage || 'Untitled conversation';
}

/** Native nested lists expose the same parentage visually and to assistive technology. */
export function confirmConversationTree(_children: string[], tree: DeleteTreeEntry[]): Promise<boolean> {
  const branches = new Map<number, TemplateResult[]>();
  for (const row of [...tree].reverse()) {
    // The validated walk already supplies preorder/depth, even when a saved parent uses an alias.
    const children = branches.get(row.depth + 1) ?? [];
    branches.delete(row.depth + 1);
    const item = html`<li>
      <strong>${name(row)}</strong>
      <div>${row.id} · ${row.createdAt > 0 && Number.isFinite(row.createdAt) ? `Created ${new Date(row.createdAt).toLocaleString()}` : 'Creation time unknown'}${row.messageCount === undefined ? '' : ` · ${row.messageCount} own messages`}</div>
      ${row.parentSessionId ? html`<div>Branched from ${row.parentSessionId} at ${row.branchPointMessageId ?? 'unknown message'}</div>` : ''}
      ${children.length > 0 ? html`<ol>${children}</ol>` : ''}
    </li>`;
    const siblings = branches.get(row.depth) ?? [];
    siblings.unshift(item);
    branches.set(row.depth, siblings);
  }
  return confirmAsync({
    title: 'Delete conversation and branches?',
    message: html`<div>Permanently delete ${tree.length} conversation${tree.length === 1 ? '' : 's'} and their agent runs? This cannot be undone.</div>
      <ol>${branches.get(0) ?? []}</ol>`,
    variant: 'danger',
    confirmLabel: `Delete all (${tree.length})`,
  });
}

/** Only categories established by the authoritative boundary appear as deleted or remaining. */
export function conversationDeleteOutcome(result: DeleteCascadeResult, tree: readonly DeleteTreeEntry[]): string {
  const names = new Map(tree.map(row => [row.id, `${name(row)} (${row.id})`]));
  const describe = (ids: readonly string[]): string => ids.map(id => names.get(id) ?? id).join('; ');
  const parts = [result.error ?? 'That conversation could not be deleted'];
  if (result.deletedIds?.length) parts.push(`Deleted: ${describe(result.deletedIds)}.`);
  if (result.remainingIds?.length) parts.push(`Remain: ${describe(result.remainingIds)}.`);
  if (result.unconfirmedIds?.length) parts.push(`Unconfirmed: ${describe(result.unconfirmedIds)}. Reconnect and refresh before trying again.`);
  return parts.join(' ');
}
