// SPDX-License-Identifier: Apache-2.0
import { html, type TemplateResult } from 'lit';
import { confirmAsync } from '../ConfirmDialog.js';
import type { DeleteCascadeResult, DeleteTreeEntry } from '../../state/conversationListStore.js';
import { authorizedFetch } from '../../api/authorizedFetch.js';

function name(row: DeleteTreeEntry): string {
  return row.title || row.firstUserMessage || 'Untitled conversation';
}

/** Native nested lists expose the same parentage visually and to assistive technology. */
export async function confirmConversationTree(
  _children: string[], tree: DeleteTreeEntry[], apiBase = '', signal?: AbortSignal,
): Promise<boolean> {
  if (signal?.aborted) return false;
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
  const message = (count: string) => html`<div>Permanently delete ${tree.length} conversation${tree.length === 1 ? '' : 's'} and their agent runs? This cannot be undone.</div>
    <ol>${branches.get(0) ?? []}</ol>
    <p role="status">${count}</p>
    <p>All memories remain, including memories without a recorded conversation link. You can inspect and forget them in Memory.</p>
    <p>Exports and backups you made remain.</p>`;
  const read = new AbortController();
  const abort = () => read.abort();
  signal?.addEventListener('abort', abort, { once: true });
  try {
    return await confirmAsync({
      title: 'Delete conversation and branches?',
      message: message('Counting recorded memory links… You can cancel while this loads.'),
      readyMessage: countMemoryLinks(tree, apiBase, read.signal).then(message),
      signal,
      variant: 'danger',
      confirmLabel: `Delete all (${tree.length})`,
    });
  } finally {
    signal?.removeEventListener('abort', abort);
    read.abort();
  }
}

async function countMemoryLinks(tree: readonly DeleteTreeEntry[], apiBase: string, signal: AbortSignal): Promise<string> {
  try {
    const response = await authorizedFetch(`${apiBase}/api/memory`, { signal });
    if (!response.ok) return 'Recorded memory link count is unknown because Memory is unavailable.';
    const data = await response.json();
    if (data?.locked === true) return 'Recorded memory link count is unknown because Memory is locked.';
    if (data?.locked !== false || !Array.isArray(data.memories) || data.memories.some((row: unknown) => {
      if (!row || typeof row !== 'object') return true;
      const record = row as Record<string, unknown>;
      return typeof record.id !== 'string' || typeof record.content !== 'string'
        || typeof record.revision !== 'string' || !/^"[0-9a-f]{64}"$/.test(record.revision)
        || (record.sourceConversationId != null && typeof record.sourceConversationId !== 'string');
    })) return 'Recorded memory link count is unknown because the Memory response could not be read.';
    const ids = new Set(tree.map(row => row.id));
    // Empty snapshots and nonexact source IDs cannot establish a total from this read.
    if (data.memories.length === 0) return 'Recorded memory link count could not be confirmed.';
    let count = 0;
    for (const row of data.memories) {
      if (row.sourceConversationId == null) continue;
      if (!ids.has(row.sourceConversationId)) return 'Recorded memory link count could not be confirmed.';
      count++;
    }
    return `${count} recorded memory link${count === 1 ? '' : 's'} to this tree at the time of this read. Memories can change after this count.`;
  } catch {
    return 'Recorded memory link count is unknown because Memory could not be read.';
  }
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
