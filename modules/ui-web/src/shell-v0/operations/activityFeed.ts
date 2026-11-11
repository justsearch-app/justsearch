// SPDX-License-Identifier: Apache-2.0
/**
 * Activity's one feed: a pure projection joining the three records of what happened — the
 * backend action ledger, the committed operation history and this window's Effect Journal — into
 * one chronological list, by exact identities only.
 *
 * Joins (each a proof that two records describe one happening):
 *   - ledger operation ↔ history: `ledger.id === "operation:" + operationKey`, or, for an unkeyed
 *     legacy history row, the backend's legacy projection identity rebuilt from the row's raw
 *     fields (`operation:<endTime>:<operationId>:<outcome>:<executionId>`), never an approximation;
 *   - ledger effect ↔ journal entry: the entry's persisted unique `ledgerId`;
 *   - journal invocation ↔ operation: the entry's persisted `operationKey`, else its `executionId`
 *     when exactly one loaded operation carries it.
 * Nothing is joined by name or nearby time. A legacy journal entry (numeric `fe-effect:<n>` id) is
 * never proof: another window's journal can hold the same number, so both rows stay and say so.
 *
 * Every loaded observation is accounted for once: proven copies share a row, everything else keeps
 * its own. A local effect with no backend copy reads "Recorded locally".
 */
import { journalEventId, type JournalEntry, type EffectOriginator } from '../substrates/effects/index.js';
import { compareInstants } from '../primitives/instant.js';
import { OPERATION_KEY_PATTERN } from './operationReceipt.js';
import {
  indexResultOf,
  projectBackend,
  projectEffect,
  type BackendLedgerEntry,
  type UnifiedActionEntry,
} from './ActionLedgerClient.js';

/** A `core.operation-history` wire row (OperationHistoryEntry), as the stream delivers it. */
export interface OperationHistoryRow {
  readonly operationId: string;
  readonly operationKey?: string | null;
  readonly outcome: string;
  readonly startTime?: string;
  readonly endTime: string;
  readonly executionId?: string | null;
  readonly message?: string | null;
  readonly diagnosticsLink?: string | null;
  readonly provenance?: { readonly transport?: string | null; readonly correlationId?: string | null } | null;
}

type ProposalDecision = 'normal' | 'accepted' | 'rejected';

export interface FeedRow {
  /** Render/selection identity: the ledger id, else the history row's ledger identity, else the journal's. */
  readonly key: string;
  /** Every identity this row answers to, so a row that gains a partner can still be found by the old one. */
  readonly aliases: readonly string[];
  /** Presentation (the shared ledger-row shape), with the committed history outcome when present. */
  readonly entry: UnifiedActionEntry;
  readonly ledger?: BackendLedgerEntry;
  /** Usually 0 or 1; more only when unkeyed legacy history rows share one identity (kept, disclosed). */
  readonly history: readonly OperationHistoryRow[];
  readonly journal?: JournalEntry;
  /** A journal row's decision; absent unless a local journal entry contributes (never invented). */
  readonly proposal?: ProposalDecision;
  readonly indexing: boolean;
  readonly problem: boolean;
  /** Disclosures shown with the row. */
  readonly notes: readonly FeedNote[];
}

export type FeedNote = 'recorded-locally' | 'ambiguous-legacy' | 'ambiguous-history' | 'outcome-unavailable';

/**
 * Operations that are indexing work. Explicit, because several of them declare no resource lineage.
 * Unknown and plugin operations stay in All and their actor category only.
 */
const INDEXING_OPERATIONS = new Set([
  'core.ingest-files', 'core.reindex', 'core.bulk-reindex', 'core.rebuild-index', 'core.scan-root',
  'core.reconcile-root', 'core.add-watched-root', 'core.remove-watched-root', 'core.forget-indexed-path',
  'core.retry-forget', 'core.withdraw-forget', 'core.clear-failed-jobs', 'core.index-gc', 'core.settle-index',
  'core.cancel-indexing-job', 'core.retry-indexing-job', 'core.preview-excludes', 'core.apply-excludes',
  'core.indexing-pause', 'core.indexing-resume', 'core.set-indexing-rules', 'core.accept-gaps',
  'core.cancel-reindex',
].map(canonicalOperation));

/** Operation ids reach the feed as declarations (`core.reindex`) and as agent wire names (`core_reindex`). */
function canonicalOperation(id: string): string {
  return id.toLowerCase().replace(/[._]/g, '-');
}

function isIndexingOperation(id: string | undefined): boolean {
  return !!id && INDEXING_OPERATIONS.has(canonicalOperation(id));
}

/** The backend's attribution of a transport (ActionLedgerProjection.originatorOf). */
function originatorOfTransport(transport: string | null | undefined): EffectOriginator {
  switch (transport) {
    case 'LLM_EMISSION': case 'AGENT_LOOP': case 'WORKFLOW': case 'MCP': case 'HTTP_API': return 'agent';
    case 'SYSTEM_INTERNAL': case 'SCHEDULED': case 'RULE_ENGINE': return 'system';
    default: return 'user';
  }
}

/** The ledger identity the backend gives this history row's operation event (raw fields, raw precision). */
export function historyLedgerId(row: OperationHistoryRow): string {
  return row.operationKey ? `operation:${row.operationKey}`
    : `operation:${row.endTime}:${row.operationId}:${row.outcome}:${row.executionId ?? ''}`;
}

/** A history row as the ledger operation row the backend would project for it. */
function historyAsLedger(row: OperationHistoryRow): BackendLedgerEntry {
  const transport = row.provenance?.transport ?? undefined;
  return {
    id: historyLedgerId(row),
    kind: 'operation',
    occurredAt: row.endTime,
    originator: originatorOfTransport(transport),
    ...(transport ? { transport } : {}),
    operationId: row.operationId,
    outcome: row.outcome,
    ...(row.executionId ? { executionId: row.executionId } : {}),
    ...(row.provenance?.correlationId ? { correlationId: row.provenance.correlationId } : {}),
  };
}

function ledgerIsProblem(e: BackendLedgerEntry): boolean {
  switch (e.kind) {
    case 'operation':
      return e.outcome === 'FAILURE' || e.outcome === 'PARTIAL' || (e.docsFailed ?? 0) > 0;
    case 'index':
      return indexResultOf(e.state) === 'failed';
    case 'gate':
      return e.disposition === 'DENIED' || e.disposition === 'EXPIRED';
    case 'effect':
      return e.effectKind === 'data-error';
    default:
      return false;
  }
}

function ledgerIsIndexing(e: BackendLedgerEntry): boolean {
  switch (e.kind) {
    case 'index': return true;
    case 'operation': case 'gate': return isIndexingOperation(e.operationId);
    case 'grant': return isIndexingOperation(e.subject);
    default: return false;
  }
}

function journalIsProblem(j: JournalEntry): boolean {
  return j.pendingOutcome === 'rejected' || j.effect.kind === 'data-error'
    || (j.effect.kind === 'toast' && (j.effect.severity === 'warning' || j.effect.severity === 'error'));
}

interface Draft {
  key: string;
  aliases: string[];
  ledger?: BackendLedgerEntry;
  history: OperationHistoryRow[];
  journal?: JournalEntry;
  notes: FeedNote[];
}

/**
 * Join the loaded observations of all three sources. Rows are returned newest first by parsed
 * event time, ties broken by identity, so the order never depends on which source arrived first.
 */
export function projectActivityFeed(
  ledger: readonly BackendLedgerEntry[],
  history: readonly OperationHistoryRow[],
  journal: readonly JournalEntry[],
): FeedRow[] {
  const drafts: Draft[] = [];
  const byId = new Map<string, Draft>();
  const add = (draft: Draft): void => {
    drafts.push(draft);
    for (const alias of draft.aliases) if (!byId.has(alias)) byId.set(alias, draft);
  };
  for (const e of ledger) {
    const id = typeof e.id === 'string' && e.id ? e.id : '';
    if (id && byId.has(id)) continue; // the one log is id-keyed: a re-delivered row is the same row
    add({ key: id || `ledger:${drafts.length}`, aliases: id ? [id] : [], ledger: e, history: [], notes: [] });
  }
  for (const row of history) {
    const id = historyLedgerId(row);
    const existing = byId.get(id);
    if (existing && (existing.ledger?.kind === 'operation' || (!existing.ledger && existing.history.length))) {
      existing.history.push(row);
      if (!row.operationKey && existing.history.length > 1 && !existing.notes.includes('ambiguous-history')) {
        existing.notes.push('ambiguous-history');
      }
      continue;
    }
    add({ key: id, aliases: [id], history: [row], notes: [] });
  }
  const executionOwners = new Map<string, Draft[]>();
  for (const d of drafts) {
    const exec = d.ledger?.kind === 'operation' ? d.ledger.executionId : d.history[0]?.executionId ?? undefined;
    if (exec) executionOwners.set(exec, [...(executionOwners.get(exec) ?? []), d]);
  }
  for (const j of journal) {
    const eventId = journalEventId(j);
    let partner: Draft | undefined;
    if (j.ledgerId) partner = byId.get(j.ledgerId);
    if (!partner && j.operationKey) partner = byId.get(`operation:${j.operationKey}`);
    if (!partner && j.executionId) {
      const owners = executionOwners.get(j.executionId);
      if (owners?.length === 1) partner = owners[0];
    }
    if (partner && !partner.journal) {
      partner.journal = j;
      partner.aliases.push(eventId);
      byId.set(eventId, partner);
      continue;
    }
    const notes: FeedNote[] = ['recorded-locally'];
    if (!j.ledgerId) {
      // A legacy numeric identity may be another window's: keep both and say the link is unproven.
      const namesake = byId.get(eventId);
      if (namesake?.ledger) {
        notes.push('ambiguous-legacy');
        if (!namesake.notes.includes('ambiguous-legacy')) namesake.notes.push('ambiguous-legacy');
      }
    }
    const key = j.ledgerId ? eventId : `local:${eventId}`;
    const aliases = j.operationKey ? [key, `operation:${j.operationKey}`] : [key];
    add({ key, aliases, journal: j, history: [], notes });
  }
  const rows = drafts.map(finish);
  rows.sort((a, b) => compareInstants(b.entry.occurredAt, a.entry.occurredAt) || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
  return rows;
}

function finish(d: Draft): FeedRow {
  const committed = d.history[0];
  const base = d.ledger ? projectBackend(d.ledger)
    : committed ? projectBackend(historyAsLedger(committed))
      : projectEffect(d.journal!);
  // The committed history is the authority for an operation's result.
  const entry: UnifiedActionEntry = committed && committed.outcome ? { ...base, outcome: committed.outcome } : base;
  const contributions = [d.ledger, ...d.history.map(historyAsLedger)].filter((e): e is BackendLedgerEntry => !!e);
  const notes = [...d.notes];
  if (entry.kind === 'operation' && !entry.outcome && !notes.includes('outcome-unavailable')) notes.push('outcome-unavailable');
  const j = d.journal;
  return {
    key: d.key,
    aliases: d.aliases,
    entry,
    ...(d.ledger ? { ledger: d.ledger } : {}),
    history: d.history,
    ...(j ? { journal: j, proposal: j.pendingOutcome ?? 'normal' } : {}),
    indexing: contributions.some(ledgerIsIndexing)
      || (j?.effect.kind === 'invoke-operation' && isIndexingOperation(j.effect.operationId)),
    problem: contributions.some(ledgerIsProblem) || (!!j && journalIsProblem(j)),
    notes,
  };
}

// ============================================================
// Filters
// ============================================================

export type QuickFilter = 'all' | 'agents' | 'mine' | 'indexing' | 'problems';
/**
 * Which records the feed is scoped to: everything loaded, the records this window's journal holds
 * (where the retired journal list's arrivals now land), or one agent run's records by its session id.
 */
export type FeedScope = 'all' | 'local' | 'run';
/** `auto` follows the quick filter; an explicit choice overrides it in every quick filter. */
export type RoutineChoice = 'auto' | 'show' | 'hide';
type DateScope = 'all' | 'today';

export interface FeedFilter {
  readonly quick: QuickFilter;
  /** Advanced; each dimension ORs its values, dimensions AND together. Empty = no constraint. */
  readonly originators: readonly string[];
  readonly outcomes: readonly string[];
  readonly proposals: readonly ProposalDecision[];
  readonly routine: RoutineChoice;
  readonly date: DateScope;
  readonly scope: FeedScope;
  /** The run a `run` scope shows, by its session id. */
  readonly runId?: string;
}

export const DEFAULT_FEED_FILTER: FeedFilter = {
  quick: 'all', originators: [], outcomes: [], proposals: [], routine: 'auto', date: 'all', scope: 'all',
};

/** The agent run (session id) a row's backend records name, if any. */
export function runOf(row: FeedRow): string | undefined {
  return row.ledger?.correlationId ?? row.history.find((h) => h.provenance?.correlationId)?.provenance?.correlationId ?? undefined;
}

function matchesScope(row: FeedRow, filter: FeedFilter): boolean {
  switch (filter.scope) {
    case 'local': return !!row.journal;
    case 'run': return !!filter.runId && runOf(row) === filter.runId;
    default: return true;
  }
}

/**
 * Whether routine rows show. In `auto`, All and Agents keep the curated default (routine hidden);
 * Your actions, Indexing and Problems reveal their routine members (per-file indexing events,
 * witnessed navigation). An explicit Show or Hide wins over every quick filter.
 */
export function routineShown(filter: FeedFilter): boolean {
  if (filter.routine !== 'auto') return filter.routine === 'show';
  return filter.quick === 'mine' || filter.quick === 'indexing' || filter.quick === 'problems';
}

/**
 * A no-op records that nothing was done: palette and kernel actions that do their own work journal
 * one. All shows it with routine rows, as before; it is never one of Your actions.
 */
function isNoop(row: FeedRow): boolean {
  return row.journal?.effect.kind === 'noop' || (row.ledger?.kind === 'effect' && row.ledger.effectKind === 'noop');
}

function matchesQuick(row: FeedRow, quick: QuickFilter): boolean {
  switch (quick) {
    case 'agents': return row.entry.originator === 'agent';
    case 'mine': return row.entry.originator === 'user' && !isNoop(row);
    case 'indexing': return row.indexing;
    case 'problems': return row.problem;
    default: return true;
  }
}

/** Local midnight at the start of `now`'s calendar day. */
export function startOfLocalDay(now: Date): Date {
  return new Date(now.getFullYear(), now.getMonth(), now.getDate());
}

/** Every constraint except routine; the caller counts what routine alone hides. */
export function matchesFilter(row: FeedRow, filter: FeedFilter, now: Date): boolean {
  if (!matchesScope(row, filter)) return false;
  if (!matchesQuick(row, filter.quick)) return false;
  if (filter.originators.length && !filter.originators.includes(row.entry.originator)) return false;
  if (filter.outcomes.length && !(row.entry.outcome && filter.outcomes.includes(row.entry.outcome))) return false;
  if (filter.proposals.length && !(row.proposal && filter.proposals.includes(row.proposal))) return false;
  if (filter.date === 'today'
      && compareInstants(row.entry.occurredAt, startOfLocalDay(now).toISOString()) < 0) return false;
  return true;
}

// ============================================================
// What a selected record's details say
// ============================================================

/** The invocation key a row answers to (history, ledger identity or the journal's association). */
export function operationKeyOf(row: FeedRow): string | undefined {
  const keyed = row.history.find((h) => h.operationKey)?.operationKey;
  if (keyed) return keyed;
  if (row.ledger?.kind === 'operation' && row.ledger.id.startsWith('operation:')) {
    const key = row.ledger.id.slice('operation:'.length);
    if (OPERATION_KEY_PATTERN.test(key)) return key;
  }
  return row.journal?.operationKey;
}

/** How long the recorded work took: the committed start and end, else a recorded duration. */
export function durationOf(row: FeedRow): number | undefined {
  const committed = row.history.find((h) => h.startTime && h.endTime);
  if (committed) {
    const ms = Date.parse(committed.endTime) - Date.parse(committed.startTime!);
    if (Number.isFinite(ms) && ms >= 0) return ms;
  }
  const recorded = row.ledger?.durationMs;
  return typeof recorded === 'number' && Number.isFinite(recorded) && recorded >= 0 ? recorded : undefined;
}

/**
 * How long a group of records (an indexing burst) took: from its earliest member's start to its
 * latest member's end, where a member ends when it was recorded and starts its duration before that.
 * Known only when every member's duration is (per-file index records carry none).
 */
export function groupDurationOf(members: readonly FeedRow[]): number | undefined {
  let start = Number.POSITIVE_INFINITY;
  let end = Number.NEGATIVE_INFINITY;
  for (const member of members) {
    const ms = durationOf(member);
    const at = Date.parse(member.entry.occurredAt);
    if (ms === undefined || !Number.isFinite(at)) return undefined;
    start = Math.min(start, at - ms);
    end = Math.max(end, at);
  }
  return members.length ? end - start : undefined;
}

/** The transport a record names as its source, if any (history provenance, else the ledger's). */
export function transportOf(row: FeedRow): string | undefined {
  return row.history.find((h) => h.provenance?.transport)?.provenance?.transport ?? row.ledger?.transport ?? undefined;
}

/** The records a row's contributions came from, in the order the details name them. */
export function contributionsOf(row: FeedRow): Array<'ledger' | 'history' | 'journal'> {
  return [...(row.ledger ? ['ledger' as const] : []), ...(row.history.length ? ['history' as const] : []),
    ...(row.journal ? ['journal' as const] : [])];
}

/** What a selected key stands for: one record, a collapsed indexing burst, or a legacy scan rollup. */
export interface SelectedRecord {
  readonly key: string;
  readonly kind: 'record' | 'burst' | 'scan';
  /** The record itself (absent for a burst, which is a summary of its members). */
  readonly row?: FeedRow;
  /** As the feed words it, with resolved file names. */
  readonly label: string;
  readonly occurredAt: string;
  /** A burst's members, or the per-document records a scan still has retained. */
  readonly members: readonly FeedRow[];
  /** Other records of the same approval request (gate decisions, grants, the execution). */
  readonly related: readonly FeedRow[];
  /** Index paths resolved so far, by path hash. */
  readonly paths: Readonly<Record<string, string>>;
}
