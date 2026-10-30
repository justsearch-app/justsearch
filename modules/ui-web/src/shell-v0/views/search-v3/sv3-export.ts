// SPDX-License-Identifier: Apache-2.0
/** Public conversation export v1. The thread remains the authority for transcript content. */
import { authorizedFetch } from '../../api/authorizedFetch.js';
import { fetchUnifiedThread } from '../unifiedThreadClient.js';
import { projectSv3RecordTurns } from './sv3-record.js';
import type { Sv3Session, Sv3SessionHistory, Sv3Turn, Sv3TurnEvidence } from './sv3-sessions.js';

export interface ConversationExportSource {
  label: number;
  path: string;
  startLine: number;
  endLine: number;
  heading: string;
  excerpt: string;
}
export interface ConversationExportMessage {
  role: 'user' | 'assistant';
  content: string;
  excludedFromContext: boolean;
  citations: Array<{ sentence: string; labels: number[] }>;
  sources: ConversationExportSource[];
}
export interface ConversationExport {
  formatVersion: 1;
  conversationId: string;
  title: string;
  branch: { parentConversationId: string; branchPointMessageId: string | null } | null;
  turns: Array<{
    kind: Sv3Turn['kind'];
    status: Exclude<Sv3Turn['status'], 'streaming'>;
    detail: string;
    disposition: string | null;
    messages: ConversationExportMessage[];
  }>;
}

function message(role: ConversationExportMessage['role'], content: string, excludedFromContext: boolean,
  evidence: Sv3TurnEvidence | null = null, paths: readonly string[] = []): ConversationExportMessage {
  const citations = new Map<number, { sentence: string; labels: number[] }>();
  for (const mark of evidence?.marks ?? []) {
    const row = citations.get(mark.sentenceIndex) ?? { sentence: mark.sentenceText, labels: [] };
    if (!row.labels.includes(mark.label)) row.labels.push(mark.label);
    citations.set(mark.sentenceIndex, row);
  }
  return {
    role, content, excludedFromContext,
    citations: [...citations].sort(([a], [b]) => a - b).map(([, row]) => row),
    sources: (evidence?.sources ?? []).map((source, index) => ({
      label: index + 1, path: paths[index] || source.parentDocId,
      startLine: source.startLine, endLine: source.endLine,
      heading: source.headingText, excerpt: source.excerpt,
    })),
  };
}

export function projectConversationExport(session: Pick<Sv3Session, 'id' | 'title' | 'turns' | 'history'>,
  sourcePaths: ReadonlyMap<string, readonly string[]> = new Map()): ConversationExport {
  const excluded = new Set(session.history?.excludedMessageIds ?? []);
  return {
    formatVersion: 1,
    conversationId: session.id,
    title: session.title || 'Untitled conversation',
    branch: session.history?.parentSessionId ? {
      parentConversationId: session.history.parentSessionId,
      branchPointMessageId: session.history.branchPointMessageId ?? null,
    } : null,
    turns: session.turns.filter((turn) => turn.status !== 'streaming').map((turn) => ({
      kind: turn.kind, status: turn.status as Exclude<Sv3Turn['status'], 'streaming'>,
      detail: turn.detail, disposition: turn.disposition,
      messages: [
        message('user', turn.question, excluded.has(turn.recordId ?? '')),
        ...(turn.answerMessages?.length ? turn.answerMessages.map((answer) =>
          message('assistant', answer.content, excluded.has(answer.id), answer.evidence ?? null, sourcePaths.get(answer.id)))
          : [message('assistant', turn.answer, excluded.has(turn.assistantRecordId ?? ''), turn.evidence, sourcePaths.get(turn.assistantRecordId ?? ''))]),
      ],
    })),
  };
}

export function serializeConversationJson(conversation: ConversationExport): string {
  return `${JSON.stringify(conversation, null, 2)}\n`;
}

export function serializeConversationMarkdown(conversation: ConversationExport): string {
  const lines = [`# ${conversation.title}`, ''];
  if (conversation.branch) lines.push(
    `Branched from ${conversation.branch.parentConversationId} at ${conversation.branch.branchPointMessageId ?? 'an unknown message'}. Inherited turns returned by the conversation record are included.`, '');
  for (const [index, turn] of conversation.turns.entries()) {
    const status = turn.status === 'halted' ? 'stopped' : turn.status;
    lines.push(`## Turn ${index + 1} (${turn.kind}; ${status})`, '');
    if (turn.detail) lines.push(turn.detail, '');
    if (turn.disposition) lines.push(`Disposition: ${turn.disposition}`, '');
    for (const row of turn.messages) {
      lines.push(`### ${row.role === 'user' ? 'User' : 'Assistant'}${row.excludedFromContext ? ' (excluded from context)' : ''}`, '', row.content, '');
      // Keep the producer's exact sentence→mark association without guessing anchors in Markdown.
      // A separate citation list also preserves repeated sentences and marks crossing formatting.
      if (row.citations.length) lines.push('Citations:', ...row.citations.map((cite) =>
        `- ${cite.sentence} ${cite.labels.map((label) => `[${label}]`).join('')}`), '');
      if (row.sources.length) lines.push('Sources:', ...row.sources.map((source) =>
        `- [${source.label}] ${source.path} (${source.startLine > 0 && source.endLine >= source.startLine ? `lines ${source.startLine}–${source.endLine}` : 'line location not reported'})${source.heading ? ` — ${source.heading}` : ''}`), '');
    }
  }
  return lines.join('\n');
}

/** Only lineage and exclusions come from history; transcript text always comes from /api/thread. */
function exportHistory(raw: unknown): Sv3SessionHistory | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const data = raw as Record<string, unknown>;
  if (data.locked === true || Array.isArray(data.messages) && data.messages.some((message: unknown) =>
    message !== null && typeof message === 'object' && 'role' in message && message.role === 'locked')) {
    throw new Error(EXPORT_LOCKED);
  }
  if (!Array.isArray(data.messages) || data.messages.some((message: unknown) => {
    if (!message || typeof message !== 'object' || Array.isArray(message)) return true;
    const row = message as Record<string, unknown>;
    return typeof row.role !== 'string' || typeof row.content !== 'string'
      || (row.id !== undefined && typeof row.id !== 'string');
  })) return null;
  if (data.parentSessionId !== undefined && typeof data.parentSessionId !== 'string'
    || data.branchPointMessageId !== undefined && typeof data.branchPointMessageId !== 'string'
    || data.excludedMessageIds !== undefined && (!Array.isArray(data.excludedMessageIds)
      || data.excludedMessageIds.some((id: unknown) => typeof id !== 'string'))) return null;
  return {
    parentSessionId: data.parentSessionId as string | undefined,
    branchPointMessageId: data.branchPointMessageId as string | undefined,
    excludedMessageIds: data.excludedMessageIds as string[] | undefined,
  };
}

export const EXPORT_LOCKED = 'Export unavailable: conversation history is locked. Unlock it in Security & Privacy.';

/** Fresh, full reads, independent of navigation and of the view's pending record reads. */
export async function loadConversationExport(apiBase: string, session: Sv3Session, signal: AbortSignal): Promise<ConversationExport> {
  let failure: string | undefined;
  const [record, response] = await Promise.all([
    fetchUnifiedThread(apiBase, session.id, signal, (_reason, detail) => { failure = detail ?? 'read'; }),
    authorizedFetch(`${apiBase}/api/chat/conversations/${encodeURIComponent(session.id)}/history`, { signal }),
  ]);
  if (signal.aborted) throw new DOMException('Export cancelled.', 'AbortError');
  if (response.status === 423 || failure === '423') throw new Error(EXPORT_LOCKED);
  if (!response.ok || failure || record.incomplete || record.events.some((event) => event.kind === 'UNKNOWN')) throw new Error('Export failed: the complete conversation could not be loaded. Try again.');
  const history = exportHistory(await response.json());
  if (history === null) throw new Error('Export failed: conversation history could not be read completely. Try again.');
  const sourcePaths = new Map<string, string[]>();
  for (const event of record.events) {
    if (!Array.isArray(event.attributes.sources)) continue;
    sourcePaths.set(event.id, event.attributes.sources.map((source: unknown) =>
      source !== null && typeof source === 'object' && 'path' in source && typeof source.path === 'string' ? source.path : ''));
  }
  const turns = [...projectSv3RecordTurns(record.events)];
  // Reconcile by identity, never position: the local view might contain only a recent tail.
  // Unnumbered live turns are matched from the end, preserving repeated prompts in older history.
  const used = new Set<number>();
  const pending: Sv3Turn[] = [];
  for (const local of [...session.turns].reverse()) {
    let index = -1;
    for (let i = turns.length - 1; i >= 0; i--) {
      const saved = turns[i]!;
      if (!used.has(i) && (local.recordId !== null ? saved.recordId === local.recordId
        : local.status !== 'refused' && saved.question === local.question
          && (local.status === 'streaming' || saved.answer === local.answer))) { index = i; break; }
    }
    if (index < 0) { pending.unshift(local); continue; }
    used.add(index);
    const saved = turns[index]!;
    if (local.status !== 'complete') {
      turns[index] = { ...saved, status: local.status, detail: local.detail };
    }
    // The live terminal can precede its persistence; don't replace a received answer with silence.
    if (!saved.answer && local.answer) turns[index] = { ...local, recordId: saved.recordId };
  }
  // A cold Delegate record can contain interim assistant messages while the run is still live.
  // One run owns this conversation's current turn; its terminal lifecycle is separate from prose.
  if (record.lifecycles.some((run) => !['DONE', 'ERROR', 'CANCELLED'].includes(run.state))) {
    const last = turns.at(-1);
    if (last?.kind === 'agent') turns[turns.length - 1] = { ...last, status: 'streaming' };
  }
  // Restore record order for saved turns; append only outcomes that have not reached it yet.
  return projectConversationExport({ ...session, turns: [...turns, ...pending], history }, sourcePaths);
}
