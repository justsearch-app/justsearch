// SPDX-License-Identifier: Apache-2.0
/**
 * Activity's words for recorded codes: the one place both the feed's rows and the details pane
 * (`<jf-activity-details>`) say who did something, what a record is, how it ended and where it came
 * from (FC9; decision 2026-11-11 extended to rows). A recorded code with no words of its own is said
 * as a code, labelled as one, never as if it were a word.
 */
import { present } from '../display/present.js';
import { describeEffectKind } from '../substrates/effects/describe.js';
import { indexResultOf } from './ActionLedgerClient.js';
import { transportOf, type FeedRow, type SelectedRecord } from './activityFeed.js';

const SOURCE_WORDS: Record<string, string> = {
  WORKER_INDEXER: 'Background indexing',
  URL_BAR: 'You, from the address bar',
  URL_DEEPLINK: 'A link from another app',
  PALETTE: 'You, from the command palette',
  RAIL: 'You, in the app',
  PLUGIN_EMITTED: 'A plugin',
  BUTTON: 'You, in the app',
  KEYBOARD: 'You, from the keyboard',
  COMMAND_PALETTE: 'You, from the command palette',
  LLM_EMISSION: 'The assistant',
  AGENT_LOOP: 'The assistant',
  WORKFLOW: 'A workflow',
  MCP: 'An outside assistant (MCP)',
  HTTP_API: 'The local API',
  SYSTEM_INTERNAL: 'The engine',
  SCHEDULED: 'A schedule',
  RULE_ENGINE: 'A rule',
};

export const RECORD_WORDS = { ledger: 'Activity ledger', history: 'Operation history', journal: "This window's journal" } as const;

const WHO_WORDS: Record<string, string> = { user: 'You', agent: 'The assistant', system: 'The engine' };

export const GATE_WORDS: Record<string, string> = {
  GATED: 'Asked for approval', APPROVED: 'Approved', DENIED: 'Denied',
  EXPIRED: 'Expired without an answer', WITHDRAWN: 'Withdrawn',
};

export const GRANT_WORDS: Record<string, string> = {
  ISSUED: 'Permission granted', CONSUMED: 'Permission used', REVOKED: 'Permission withdrawn',
};

/** A recorded outcome, in words. */
export const OUTCOME_WORDS: Record<string, string> = {
  SUCCESS: 'Succeeded', FAILURE: 'Failed', UNDONE: 'Undone', PARTIAL: 'Partly done',
};

/** How an approval was asked for, in words. */
export const ASKED_WORDS: Record<string, string> = {
  AUTO: 'Not asked: allowed to run directly', INLINE_CONFIRM: 'A confirmation',
  TYPED_CONFIRM: 'A typed confirmation', DENY: 'Not asked: refused outright',
};

export const RECEIPT_WORDS: Record<string, string> = {
  accepted: 'Accepted; waiting to run', running: 'Running', complete: 'Completed', failed: 'Failed',
};

/** A code with no words of its own is said as a code, never as if it were a word. */
export function words(map: Record<string, string>, code: string): string {
  return map[code] ?? `Not described (code ${code})`;
}

/** Who did it: you, the assistant or the engine. */
export function whoWords(originator: string): string {
  return words(WHO_WORDS, originator);
}

/** Where a record came from, in words: its recorded transport, this window's journal, or not recorded. */
export function sourceWords(row: FeedRow): string {
  const transport = transportOf(row);
  return transport ? (SOURCE_WORDS[transport] ?? 'Another source') : row.journal ? 'This window' : 'Not recorded';
}

/** A group's source: the one its members share, else each of theirs, in the order the members are listed. */
export function groupSourceWords(members: readonly FeedRow[]): string {
  const all = [...new Set(members.map(sourceWords))];
  if (all.length === 0) return 'Not recorded';
  return all.length === 1 ? all[0]! : `Several: ${all.join(' · ')}`;
}

/** What an index record's state establishes, in words; an unrecognised state is disclosed beside "Unknown". */
export function indexResultWords(state: string | undefined): string {
  const result = indexResultOf(state);
  return result === 'indexed' ? 'Indexed' : result === 'failed'
    ? (state === 'RETRY_EXHAUSTED' ? 'Gave up after retrying' : 'Failed') : `Unknown${state ? ` (${state})` : ''}`;
}

/**
 * How a record ended, in words, beside its title: an index record by its state, anything else by its
 * recorded outcome. An approval's or a permission's title says its decision already (the Engine
 * records that decision as the outcome too), so it has no second outcome: null (FC9a).
 */
export function rowOutcomeWords(kind: string, outcome: string): string | null {
  if (kind === 'gate' || kind === 'grant') return null;
  return kind === 'index' ? indexResultWords(outcome) : words(OUTCOME_WORDS, outcome);
}

function operationWords(id: string | undefined): string {
  return id ? present({ kind: 'operation', id }).label : 'an operation not recorded';
}

/**
 * What the feed and the details call a record. The feed's titles are kept where they are words
 * already; an approval, a permission and an action this window journaled are titled from their
 * recorded fields, and the backend's copy of another window's action from its recorded kind, so no
 * recorded code reads as a word (decision 2026-11-11).
 */
export function recordTitle(record: SelectedRecord): string {
  return record.row ? rowTitle(record.row) : record.label;
}

export function rowTitle(row: FeedRow): string {
  const ledger = row.ledger;
  if (ledger?.kind === 'gate') {
    return `${ledger.disposition ? words(GATE_WORDS, ledger.disposition) : 'Approval'}: ${operationWords(ledger.operationId)}`;
  }
  if (ledger?.kind === 'grant') {
    return `${ledger.action ? words(GRANT_WORDS, ledger.action) : 'Permission'}${ledger.subject ? `: ${operationWords(ledger.subject)}` : ''}`;
  }
  const effect = row.journal?.effect;
  if (!effect && ledger?.kind === 'effect' && ledger.effectKind && !['navigate', 'noop'].includes(ledger.effectKind)) {
    return describeEffectKind(ledger.effectKind);
  }
  // An invocation is titled by its operation already, as the ledger's own operation rows are.
  if (!effect || effect.kind === 'invoke-operation') return row.entry.label;
  const decision = row.journal?.pendingOutcome === 'rejected' ? 'Proposal rejected · '
    : row.journal?.pendingOutcome === 'accepted' ? 'Proposal accepted · ' : '';
  return `${decision}${present({ kind: 'effect', effect }).label}`;
}
