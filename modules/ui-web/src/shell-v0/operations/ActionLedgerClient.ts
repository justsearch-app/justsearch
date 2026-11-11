// SPDX-License-Identifier: Apache-2.0
/**
 * ActionLedgerClient + unified-activity projection — tempdoc 550 Slice C1 (FE half).
 *
 * 550's Outcome face: the receipt, activity timeline, undo, and trust-audit are all
 * <em>read-views over one ledger</em>. The backend `GET /api/action-ledger`
 * ({@link ActionLedgerController}) is the authoritative cross-boundary projection over the
 * Operation + Navigation history stores. This client fetches it and {@link unifiedActivity}
 * folds the FE-local Effect Journal in as a client-side projection — yielding one
 * chronological, originator-attributed stream the FE can render ("show me everything the
 * agent did this session").
 *
 * Per the federated-ledger decision (D1): the FE does NOT mirror the backend records into
 * its own store; it projects over both at read time. The shared axis is `originator`
 * (user | agent | system), which both the backend ledger and the FE JournalEntry carry.
 */

import {
  listJournal,
  journalEventId,
  getUndoableOperation,
  subscribeJournal,
  type JournalEntry,
  type EffectOriginator,
} from '../substrates/effects/index.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { EnvelopeStream, type EnvelopeStreamSnapshot } from '../streaming/EnvelopeStream.js';
import type { MultiplexedStream } from '../streaming/MultiplexedStream.js';
import { SHELL_EVENT_STREAM_IDS } from '../streaming/shellEventStreamIds.js';
import { isLifecycleEnvelope } from '../streaming/envelope-types.js';
import { applySnapshot, observe } from '../streaming/snapshotArbitration.js';
import { present } from '../display/present.js';
// Tempdoc 613 §6/§10 + 612 §3 — the Activity-inclusion routing predicates: routine local-ack effects /
// navigation, and the operation-significance grade over the declared OperationCatalog facets.
import { isRoutineActivity, isRoutineOperation } from '../state/messageRouting.js';
// Tempdoc 885 item 21b — the ONE spelling of the exhausted terminal state (see its javadoc).
import { JOB_STATE_RETRY_EXHAUSTED } from '../state/indexingJobStates.js';
// 612 §3 — resolve an operation row's declared significance facets (sync singleton; the same registry
// `present()` already joins for labels in this module).
import { getOperation } from '../../api/registry/OperationCatalogClient.js';

/** L1 — the folder a successful removal row recorded; nothing for older rows without one. */
function removedFolderOf(e: BackendLedgerEntry): { removedFolder?: { path: string; collection?: string } } {
  const folder = e.removedFolder;
  if (e.kind !== 'operation' || e.operationId !== 'core.remove-watched-root' || e.outcome !== 'SUCCESS'
      || !folder || typeof folder.path !== 'string' || !folder.path) return {};
  return { removedFolder: { path: folder.path,
    ...(typeof folder.collection === 'string' && folder.collection ? { collection: folder.collection } : {}) } };
}

/**
 * What an index row's state establishes, and nothing more: DONE is indexed, FAILED and
 * RETRY_EXHAUSTED are failures, and any other or missing state is unknown — never a success.
 */
export type IndexResult = 'indexed' | 'failed' | 'unknown';

export function indexResultOf(state: string | undefined): IndexResult {
  if (state === 'DONE') return 'indexed';
  if (state === 'FAILED' || state === JOB_STATE_RETRY_EXHAUSTED) return 'failed';
  return 'unknown';
}

/** An index row's label for its state at `where` (a collection, or a resolved file name). */
export function indexLabel(state: string | undefined, where: string): string {
  // Tempdoc 885 item 21b: RETRY_EXHAUSTED is the other terminal failure — retried for a week
  // and never readable. Named distinctly because "Index failed" would suggest a bad file.
  if (state === 'FAILED') return `Index failed · ${where}`;
  if (state === JOB_STATE_RETRY_EXHAUSTED) return `Index gave up · ${where}`;
  if (state === 'DONE') return `Indexed · ${where}`;
  return `Index state unknown${state ? ` (${state})` : ''} · ${where}`;
}

/** A backend action-ledger entry (operation or navigation). Mirrors ActionLedgerController. */
export interface BackendLedgerEntry {
  // Tempdoc 550 thesis I: explicit, deterministic event id (kind:occurredAt:subject) — stable
  // across snapshot + stream, so the live reducer can dedup a row present in both frames.
  readonly id: string;
  readonly kind: 'operation' | 'navigation' | 'gate' | 'grant' | 'effect' | 'index';
  readonly occurredAt: string;
  readonly originator: EffectOriginator;
  readonly transport?: string;
  readonly operationId?: string;
  readonly outcome?: string;
  readonly targetSurface?: string;
  readonly sourceId?: string;
  // Tempdoc 550 Outcome face — present on kind='gate' rows (trust-gate firings). L16: also on the
  // grant ISSUED/CONSUMED and operation rows an Allowed request produced, when the Engine knew it.
  readonly pendingId?: string;
  readonly disposition?: string; // GATED | DENIED | APPROVED | EXPIRED | WITHDRAWN
  readonly gateBehavior?: string;
  // Tempdoc 550 G6 — backend execution id for undo-supported operations. The FE links it to
  // the dispatching Effect Journal entry (via markUndoableOperation), letting unifiedActivity
  // collapse the FE-effect row and this backend row into ONE logical record.
  readonly executionId?: string;
  // Tempdoc 550 thesis IV — present on kind='grant' rows (grant lifecycle in the one log).
  readonly action?: string; // ISSUED | CONSUMED | REVOKED
  readonly grantId?: string;
  readonly subject?: string;
  // Tempdoc 550 thesis I — present on kind='effect' rows (FE-local effects ingested into the log).
  readonly effectKind?: string;
  // Tempdoc 550 thesis I — present on kind='index' rows (system/indexing terminal outcomes). The
  // worker job lifecycle's Outcome face; pathHash is the cross-process correlation id (raw paths
  // never cross the wire), collection groups jobs, state ∈ {DONE | FAILED}.
  readonly pathHash?: string;
  readonly collection?: string;
  readonly state?: string;
  // Historical scan correlation key. On kind='index' rows: which directory scan
  // enqueued the document (absent for single-file ingests, the watcher, and pre-812 rows — those
  // fall back to the adjacency collapse). On the scan ROLLUP row (kind='operation',
  // operationId='core.scan-root'): the scan this row summarizes.
  readonly scanId?: string;
  // Persisted legacy scan-rollup fields; their live producer was retired by Lane F.
  // These counts preserve terminal job states observed by that producer.
  readonly root?: string;
  readonly docsDone?: number;
  readonly docsFailed?: number;
  readonly docsAdmitted?: number;
  readonly durationMs?: number;
  // Tempdoc 561 P-A1 — the cross-domain loop/session join key (the agent loop stamps its sessionId
  // here). Present on agent-originated operation rows; lets the agent History view project itself
  // from this one ledger filtered to a single session (P-B1).
  readonly correlationId?: string;
  // L1 — present on a successful core.remove-watched-root row: the folder it removed, so every
  // window (and a reload) can offer to add it back. Older records have none and offer no re-add.
  readonly removedFolder?: { readonly path?: unknown; readonly collection?: unknown };
}

/** A single row in the unified activity stream — backend record OR FE-local effect. */
export interface UnifiedActionEntry {
  /** Read details from the existing receipt rather than duplicating them in the audit journal. */
  readonly operationOutcomeKey?: string;
  readonly operationId?: string;
  /** Stable event id (the one ActionEvent schema's key) — a render key + dedup handle. */
  readonly id: string;
  /** Where the record came from: an authoritative backend record, or a FE-local effect. */
  readonly source: 'backend' | 'fe-effect';
  /** 'operation' | 'navigation' (backend) or the Effect kind (FE). */
  readonly kind: string;
  /** ISO-8601 timestamp; the chronological sort key. */
  readonly occurredAt: string;
  /** Shared attribution axis. */
  readonly originator: EffectOriginator;
  /** Human-readable one-line summary. */
  readonly label: string;
  /**
   * Terminal outcome for operation rows — `SUCCESS` / `FAILURE` / `UNDONE` (tempdoc 558 Deepening 3).
   * A STRUCTURED facet the row projects as a semantic glyph + legible tone (via the statusTone
   * authority), rather than concatenated into {@link label}. Absent on navigation/effect rows.
   */
  readonly outcome?: string;
  /**
   * Present on trust-gate rows: the structured disposition (GATED | DENIED | APPROVED) and the
   * humanized operation, so a presenter can word the row without parsing {@link label}. The optional
   * pending id ties a gate row to its exact request (L4b/L5); legacy rows have no request identity.
   */
  readonly gate?: { readonly disposition: string; readonly operation: string; readonly pendingId?: string;
    /** The raw operation id and the source that asked (L15: a reload rediscovers an outside request from its row). */
    readonly operationId?: string; readonly transport?: string };
  /**
   * Present on grant-lifecycle rows: the structured action (ISSUED | CONSUMED | REVOKED), the humanized
   * subject and the grant's source (`APPROVAL`: a one-time permission minted by an Allow; `ALLOW_ALWAYS`).
   */
  readonly grant?: { readonly action: string; readonly subject: string; readonly transport?: string };
  /**
   * L16 — on grant and operation rows: the request whose Allow minted this permission or admitted this
   * run, as the Engine recorded it. Absent on older rows and on paths that cannot know it; such rows
   * are never joined to a decision.
   */
  readonly allowedBy?: string;
  /** L1 — a successful folder removal's folder (path, and collection when recorded): the way back. */
  readonly removedFolder?: { readonly path: string; readonly collection?: string };
  /**
   * Optional grouping key for bounded-projection collapse (tempdoc 550 thesis III(b)). Present on
   * kind='index' rows (the job collection), so the Activity timeline can collapse an indexing burst
   * into one summary row rather than rendering N individual "Indexed · …" rows.
   */
  readonly groupKey?: string;
  /**
   * Tempdoc 613 §6/§10 — a ROUTINE direct-user action (navigation) the user already witnessed. The
   * default Activity feed excludes these (the navigation de-flood); the full view still shows them.
   * Derived via `isRoutineActivity`. Absent ⇒ not routine.
   */
  readonly isRoutine?: boolean;
  /**
   * Historical scan grouping. Set on retained scan ROLLUP rows and on each
   * per-document `index` row the same scan produced, so the Activity view expands a rollup to its
   * own documents by KEY. Absent on keyless rows (single-file ingest, watcher, pre-812), which
   * keep the render-time adjacency collapse as their only grouping.
   */
  readonly scanId?: string;
  /** Present on `index` rows — the hash `core.resolve-path-hash` turns into a friendly name. */
  readonly pathHash?: string;
  /** Present on `index` rows — the job's collection, so a collapsed burst can name it. */
  readonly collection?: string;
  /** Present on `index` rows that carry one — the backend's job state, read via `indexResultOf`. */
  readonly indexState?: string;
  /** Tempdoc 812 D4 — true on a scan-rollup row (an `operation` row summarizing a whole scan). */
  readonly isScanRollup?: boolean;
}

/** "6m 12s" / "45s" / "1h 03m" — a scan's duration at human altitude (millis are not). */
export function formatScanDuration(ms: number | undefined): string {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms < 0) return '';
  const totalSec = Math.round(ms / 1000);
  if (totalSec < 60) return `${totalSec}s`;
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  if (min < 60) return `${min}m ${String(sec).padStart(2, '0')}s`;
  return `${Math.floor(min / 60)}h ${String(min % 60).padStart(2, '0')}m`;
}

/**
 * Recognize persisted legacy scan rollups by their operation discriminator. The backend keeps
 * historical decoding/rendering support after retiring the live rollup producer.
 */
function isScanRollupRow(e: BackendLedgerEntry): boolean {
  return e.kind === 'operation' && e.operationId === SCAN_ROLLUP_OPERATION_ID;
}

/** Mirrors `ActionEvent.ScanRollup.OPERATION_ID` (backend). */
const SCAN_ROLLUP_OPERATION_ID = 'core.scan-root';

export interface ActionLedgerClientConfig {
  /** Absolute API base, or '' for same-origin (relative URLs). */
  apiBase?: string;
  fetchImpl?: typeof fetch;
}

export class ActionLedgerClient {
  private readonly apiBase: string;
  private readonly fetchImpl: typeof fetch;

  constructor(config: ActionLedgerClientConfig = {}) {
    this.apiBase = (config.apiBase ?? '').replace(/\/$/, '');
    this.fetchImpl = config.fetchImpl ?? authorizedFetch;
  }

  /** Fetch the backend unified ledger (operations + navigations), oldest-first. */
  async fetchBackendLedger(): Promise<BackendLedgerEntry[]> {
    const res = await this.fetchImpl(`${this.apiBase}/api/action-ledger`, {
      method: 'GET',
      headers: { Accept: 'application/json' },
    });
    if (!res.ok) {
      throw new Error(`action-ledger fetch failed (HTTP ${res.status})`);
    }
    const body = (await res.json()) as { entries?: BackendLedgerEntry[] };
    return body.entries ?? [];
  }

  /**
   * The unified read-view: fetch the backend ledger and fold the FE-local Effect Journal in,
   * returning one chronological (oldest-first) originator-attributed stream.
   */
  async unifiedActivity(): Promise<UnifiedActionEntry[]> {
    const backend = await this.fetchBackendLedger();
    return unifiedActivity(backend, listJournal());
  }
}

/**
/**
 * Tempdoc 612 §3/§L — is a backend row routine (excluded from the curated default Activity feed)? Only
 * direct-user rows can be routine. Effects/navigation route by the witnessed local-ack classification
 * ({@link isRoutineActivity}); OPERATION rows route by their DECLARED significance — a `FAILURE` is never
 * routine, and otherwise the operation must grade insignificant (LOW · no-confirm ·
 * mutates-no-Resource) via {@link isRoutineOperation} over facets resolved from the OperationCatalog. An
 * ungradable operation (registry not yet loaded / unknown id) FAILS TOWARD FOREGROUND — never hide a row
 * we cannot grade. Reads the same `getOperation` singleton `present()` already uses for labels here.
 */
function isRoutineBackendRow(e: BackendLedgerEntry): boolean {
  // Tempdoc 812 D4 — per-document index rows are routine regardless of originator (they are
  // system-originated by construction), so this grades BEFORE the direct-user guard. The scan
  // rollup below is an operation row and stays foreground: it is the durable audit record.
  if (e.kind === 'index') return isRoutineActivity(e.kind, e.originator, e.effectKind);
  if (e.originator !== 'user') return false;
  if (e.kind === 'operation') {
    if (e.outcome === 'FAILURE') return false;
    const op = e.operationId ? getOperation(e.operationId) : undefined;
    if (!op) return false; // fail toward foreground
    return isRoutineOperation({
      risk: op.policy.risk,
      confirmKind: op.policy.confirm?.kind ?? '',
      affectsCount: op.lineage.affects?.length ?? 0,
    });
  }
  return isRoutineActivity(e.kind, e.originator, e.effectKind);
}

/**
 * Map a backend ledger entry to a unified row. Exported (tempdoc 558 §S1) so the agent retrospective's
 * History tab projects the SAME record through this one projection (server-filtered to a session) rather
 * than re-deriving its own row shape — the row-level "two authorities for one concept" fix.
 */
export function projectBackend(e: BackendLedgerEntry): UnifiedActionEntry {
  // §2.A: operation/surface ids are routed through the display projector so the timeline
  // shows human labels ("Reindex", "Search") not raw catalog ids ("core.reindex") — Q7.
  const opLabel = (id: string | undefined): string =>
    id ? present({ kind: 'operation', id }).label : 'operation';
  let label: string;
  if (e.kind === 'gate') {
    // Trust-gate firing: "<disposition> <op> (<gateBehavior>)" — e.g. "GATED Reindex (TYPED_CONFIRM)".
    label = `${e.disposition ?? 'GATE'} ${opLabel(e.operationId)}${e.gateBehavior ? ' (' + e.gateBehavior + ')' : ''}`;
  } else if (e.kind === 'grant') {
    // Grant lifecycle: "grant <ACTION> <op>" — e.g. "grant ISSUED Search Index". Tempdoc 577
    // Move 4 — the subject is an operation id, so route it through the Display authority (humanized)
    // rather than leaking the raw `core.search-index` at user altitude (the §2.11 #5 principle).
    const subject = e.subject ? opLabel(e.subject) : (e.grantId ?? '');
    label = `grant ${e.action ?? ''} ${subject}`.trim();
  } else if (e.kind === 'effect') {
    // 557 Q7 — humanize the FE-local effect label. A navigate's subject is the raw
    // route, so project it through present({kind:'route'}) (the surface-label
    // authority → "Search"/"Chat", not the raw justsearch:// id); noop reads "No-op".
    if (e.effectKind === 'navigate') {
      label = `Navigate to ${present({ kind: 'route', target: e.subject ?? '' }).label}`;
    } else if (e.effectKind === 'noop') {
      label = 'No-op';
    } else {
      label = `${e.effectKind ?? 'effect'} ${e.subject ?? ''}`.trim();
    }
  } else if (e.kind === 'index') {
    // System/indexing terminal outcome: "Indexed · <collection>" / "Index failed · <collection>".
    // pathHash is a SHA-256 hex (not a raw path); a short prefix disambiguates without leaking.
    const where = `${e.collection ?? 'default'}${e.pathHash ? ' (' + e.pathHash.slice(0, 6) + ')' : ''}`;
    label = indexLabel(e.state, where);
  } else if (isScanRollupRow(e)) {
    // tempdoc 812 D2 — the scan rollup: ONE row for a whole directory scan, stating what it DID
    // (counts are the backend's observed terminal job states). This is the audit record the
    // per-document rows are the detail of.
    const where = e.collection ? ` · ${e.collection}` : '';
    if (e.outcome === 'STARTED') {
      label = `Indexing${where}…`;
    } else {
      const dur = formatScanDuration(e.durationMs);
      const failed = (e.docsFailed ?? 0) > 0 ? ` · ${e.docsFailed} failed` : '';
      const partial = e.outcome === 'PARTIAL' ? ' (incomplete)' : '';
      label =
        `Indexed ${e.docsDone ?? 0} documents${where}${dur ? ' · ' + dur : ''}${failed}${partial}`;
    }
  } else if (e.kind === 'operation') {
    // tempdoc 558 Deepening 3 — the outcome is a STRUCTURED facet (the row projects it as a glyph +
    // tone via statusTone), not concatenated here; the label is just the humanized operation.
    label = opLabel(e.operationId);
  } else {
    const to = e.targetSurface
      ? present({ kind: 'surface', id: e.targetSurface }).label
      : 'surface';
    label = `navigate → ${to}`;
  }
  const base = {
    id: e.id,
    source: 'backend' as const,
    kind: e.kind,
    occurredAt: e.occurredAt,
    originator: e.originator,
    label,
    // tempdoc 558 Deepening 3 — structured outcome (operation rows: SUCCESS/FAILURE/UNDONE; absent
    // elsewhere). Conditional-spread so an absent outcome stays absent (exactOptionalPropertyTypes-safe).
    ...(e.outcome ? { outcome: e.outcome } : {}),
    ...(e.kind === 'gate' && e.disposition ? { gate: { disposition: e.disposition, operation: opLabel(e.operationId),
      ...(e.pendingId ? { pendingId: e.pendingId } : {}), ...(e.operationId ? { operationId: e.operationId } : {}),
      ...(e.transport ? { transport: e.transport } : {}) } } : {}),
    ...(e.kind === 'grant' && e.action ? { grant: { action: e.action, subject: e.subject ? opLabel(e.subject) : '',
      ...(e.transport ? { transport: e.transport } : {}) } } : {}),
    ...((e.kind === 'grant' || e.kind === 'operation') && e.pendingId ? { allowedBy: e.pendingId } : {}),
    ...removedFolderOf(e),
    ...(e.operationId ? { operationId: e.operationId } : {}),
    ...(e.kind === 'operation'
      && ['core.bulk-reindex', 'core.rebuild-index', 'core.activate-installed-models'].includes(e.operationId ?? '')
      && /^operation:[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(e.id)
      ? { operationOutcomeKey: e.id.slice('operation:'.length) } : {}),
    // Tempdoc 613 §6/§10 + 612 §3/§L — flag routine direct-user rows so the default Activity feed can
    // exclude them (the de-flood): navigation + witnessed local-ack/preference effects, and insignificant
    // user operations (LOW/no-confirm/no-affects). Everything else stays foreground.
    ...(isRoutineBackendRow(e) ? { isRoutine: true } : {}),
  };
  // Tempdoc 812 D4 — the scan rollup carries its scan key so the view can expand it to the
  // per-document rows that scan produced (and suppress its own STARTED row once it finished).
  if (isScanRollupRow(e)) {
    return { ...base, isScanRollup: true, ...(e.scanId ? { scanId: e.scanId } : {}) };
  }
  // Index rows carry a bounded-projection group key: the SCAN when the capture-side key is present
  // (tempdoc 812 D2 — exact even when two scans interleave), else the collection, which keeps the
  // pre-812 adjacency collapse working for keyless legacy rows (tempdoc 550 III(b)).
  return e.kind === 'index'
    ? {
        ...base,
        groupKey: e.scanId ? e.scanId : (e.collection ?? 'default'),
        collection: e.collection ?? 'default',
        ...(e.scanId ? { scanId: e.scanId } : {}),
        ...(e.pathHash ? { pathHash: e.pathHash } : {}),
        ...(e.state ? { indexState: e.state } : {}),
      }
    : base;
}

/** Map a FE Effect Journal entry to a unified row. */
export function projectEffect(j: JournalEntry): UnifiedActionEntry {
  const e = j.effect;
  // §2.A: route the operation id / navigation target through the display projector (Q7).
  const detail =
    e.kind === 'invoke-operation'
      ? `: ${present({ kind: 'operation', id: e.operationId }).label}`
      : e.kind === 'navigate'
        ? `: ${present({ kind: 'route', target: e.to }).label}`
        : '';
  // A proposal marker records a DECISION, not a dispatch: a rejected one never ran.
  const decision = j.pendingOutcome === 'rejected' ? 'Proposal rejected · '
    : j.pendingOutcome === 'accepted' ? 'Proposal accepted · ' : '';
  return {
    // FE-local effects key off the journal entry id (the FE half of the one schema).
    id: journalEventId(j),
    source: 'fe-effect',
    kind: e.kind,
    occurredAt: j.invokedAt,
    originator: j.originator,
    label: `${decision}${e.kind}${detail}`,
    // Tempdoc 613 §6/§10 — a raw FE `navigate` journal row is routine direct-user navigation.
    ...(isRoutineActivity(e.kind, j.originator) ? { isRoutine: true } : {}),
  };
}

/**
 * Resolve the backend executionId a given FE journal entry correlates to, if any. The default
 * consults the Effect Journal's undoable side-map (`markUndoableOperation`, populated only for
 * undo-supported ops). Threaded as a parameter so {@link unifiedActivity} stays a pure function
 * of its arguments (the correlation source is explicit, not a hidden global read) and tests can
 * inject it.
 */
export type ExecutionIdResolver = (journalId: number) => string | undefined;

const defaultExecutionIdOf: ExecutionIdResolver = (id) => getUndoableOperation(id)?.executionId;

/**
 * Pure projection: merge backend ledger entries + FE Effect Journal entries into one
 * chronological (oldest-first) stream. Exposed for direct testing and for callers that
 * already hold both lists.
 *
 * <p>Tempdoc 550 G6 — Effect→Operation collapse: a backend operation row and the FE Effect
 * Journal entry that dispatched it are the same action across the boundary. They correlate on
 * executionId — the backend row carries it and `executionIdOf` resolves the FE entry's. When a
 * journal entry's execution id matches a backend operation row, the FE row is dropped (the
 * authoritative backend row stands for both) so the timeline shows ONE record.
 *
 * <p><b>Scope:</b> the correlation exists only for <b>undo-supported</b> operations (both the
 * backend `executionId` emit and the FE `markUndoableOperation` are gated on undo-support).
 * Non-undo invoke-operations have no shared key, so they still render as two rows (FE effect +
 * backend op). Broadening collapse to all ops (backend executionId for every op + FE marking
 * every op) is a deliberate follow-on, not done here.
 */
export function unifiedActivity(
  backend: ReadonlyArray<BackendLedgerEntry>,
  journal: ReadonlyArray<JournalEntry>,
  executionIdOf: ExecutionIdResolver = defaultExecutionIdOf,
): UnifiedActionEntry[] {
  const backendExecutionIds = new Set(
    backend
      .filter((e) => e.kind === 'operation' && typeof e.executionId === 'string')
      .map((e) => e.executionId as string),
  );
  // Tempdoc 577 §2.9 V6 root-cause — Effect→Effect collapse: `startEffectIngest` posts each FE
  // journal entry to the backend log under the SAME persisted event id (legacy entries keep their numeric id),
  // and it returns here as an authoritative kind='effect' row. The id was designed as the dedup
  // handle; without this filter the one act rendered twice ("Navigate to Chat" backend row +
  // "navigate: Chat" journal row, identical timestamps — the live-audit Timeline duplication).
  const backendIds = new Set(backend.map((e) => e.id));
  const collapsedJournal = journal.filter((j) => {
    if (backendIds.has(journalEventId(j))) return false; // ingested copy stands for both
    const exec = executionIdOf(j.id);
    return !(exec !== undefined && backendExecutionIds.has(exec));
  });
  const rows: UnifiedActionEntry[] = [
    ...backend.map(projectBackend),
    ...collapsedJournal.map(projectEffect),
  ];
  rows.sort((a, b) => a.occurredAt.localeCompare(b.occurredAt));
  return rows;
}

/**
 * Tempdoc 550 Outcome face: the 538 trust-firing audit as a READ-VIEW of the one ledger —
 * the gate-decision rows (`kind === 'gate'`). "Which actions were gated/denied/approved this
 * session, and who by" is a filter over the unified stream, not a separate store.
 */
export function gateFirings(entries: ReadonlyArray<UnifiedActionEntry>): UnifiedActionEntry[] {
  return entries.filter(e => e.kind === 'gate');
}

// ============================================================
// Tempdoc 550 G3/G4/G5 — the live read-view (unified change-stream)
// ============================================================

/**
 * What the live ledger stream has established. `snapshotApplied` says a snapshot frame (possibly
 * legitimately empty) was read, so an empty `rows` is a claim about the ledger only then: a
 * connected or heartbeat frame proves nothing about it. `resets` counts reset frames, so a reader
 * that started before one can tell its answer describes discarded state.
 */
export interface LedgerStreamState {
  readonly rows: readonly BackendLedgerEntry[];
  readonly snapshotApplied: boolean;
  readonly resets: number;
  /** UPDATE rows no snapshot has contained yet: a later snapshot that lacks them keeps them. */
  readonly unconfirmed: readonly BackendLedgerEntry[];
}

const INITIAL_LEDGER_STATE: LedgerStreamState = { rows: [], snapshotApplied: false, resets: 0, unconfirmed: [] };

/** The one log is id-keyed; a row without an explicit id has no identity to match. */
const ledgerIdentity = (e: BackendLedgerEntry): string | undefined =>
  typeof e.id === 'string' && e.id.length > 0 ? e.id : undefined;

/** Keep the first row per explicit id (the one log is id-keyed); rows without an id stay independent. */
function dedupById(rows: readonly BackendLedgerEntry[]): BackendLedgerEntry[] {
  const seen = new Set<string>();
  const out: BackendLedgerEntry[] = [];
  for (const e of rows) {
    if (typeof e.id === 'string' && e.id.length > 0) {
      if (seen.has(e.id)) continue;
      seen.add(e.id);
    }
    out.push(e);
  }
  return out;
}

/**
 * Reducer over the unified ledger SSE, arbitrated by {@link applySnapshot}: a `snapshot` frame is
 * the Engine's current retention, so a smaller or empty snapshot after a reconnect drops what it
 * established before, but never an UPDATE row no snapshot has contained yet, on this connection or
 * an earlier one. Only a `reset` discards everything and waits for the next snapshot. Every UPDATE
 * frame appends one row, deduplicated by its explicit event id (snapshot and live rows project the
 * same event through the one ActionLedgerProjection).
 */
function ledgerReducer(
  state: LedgerStreamState,
  envelope: { frameKind: string; payload: unknown },
): LedgerStreamState {
  if (isLifecycleEnvelope(envelope as never)) {
    const payload = envelope.payload as { kind?: string; entries?: BackendLedgerEntry[] };
    if (payload?.kind === 'snapshot' && Array.isArray(payload.entries)) {
      const next = applySnapshot(dedupById(payload.entries), state.unconfirmed, ledgerIdentity);
      return { ...state, rows: next.rows, unconfirmed: next.unconfirmed, snapshotApplied: true };
    }
    if (payload?.kind === 'reset') {
      return { rows: [], snapshotApplied: false, resets: state.resets + 1, unconfirmed: [] };
    }
    return state; // connected / heartbeat / closing — no row change.
  }
  const row = envelope.payload as BackendLedgerEntry;
  if (!row || typeof row.kind !== 'string') return state;
  if (typeof row.id === 'string' && row.id.length > 0 && state.rows.some((r) => r.id === row.id)) return state;
  return { ...state, rows: [...state.rows, row], unconfirmed: observe(state.unconfirmed, row, ledgerIdentity) };
}

/** The ledger stream as a source: its state after every frame, and whether the channel is up. */
export function openLedgerSource(
  config: {
    apiBase?: string;
    eventSourceFactory?: (url: string) => EventSource;
    multiplex?: MultiplexedStream;
  },
  onState: (state: LedgerStreamState, isConnected: boolean) => void,
): () => void {
  if (config.multiplex) {
    return config.multiplex.subscribe<LedgerStreamState>(
      SHELL_EVENT_STREAM_IDS.ACTION_LEDGER,
      () => ({ initialState: INITIAL_LEDGER_STATE, reducer: ledgerReducer as never }),
      (snap: EnvelopeStreamSnapshot<LedgerStreamState>) => onState(snap.payload, snap.isConnected),
    );
  }
  const base = (config.apiBase ?? '').replace(/\/$/, '');
  const stream = new EnvelopeStream<LedgerStreamState>({
    url: `${base}/api/action-ledger/stream`,
    initialState: INITIAL_LEDGER_STATE,
    reducer: ledgerReducer as never,
    ...(config.eventSourceFactory ? { eventSourceFactory: config.eventSourceFactory } : {}),
  });
  const unsubscribe = stream.subscribe((snap) => onState(snap.payload, snap.isConnected));
  stream.start();
  return () => {
    unsubscribe();
    stream.stop();
  };
}

/**
 * Open the live action-ledger stream. Tempdoc 550 thesis I (process-spanning ONE log): `onActivity`
 * is called after every frame with the projected backend rows — which now INCLUDE FE-local effects,
 * because the FE ingests them into the one authoritative log (see {@link startEffectIngest}).
 *
 * Tempdoc 662: when `config.multiplex` is supplied, subscribes the `surface:action-ledger`
 * streamId on the shared `MultiplexedStream` instead of opening a dedicated EventSource, so every
 * caller shares one socket. Omitting `multiplex` keeps the direct-connection fallback
 * (apiBase/eventSourceFactory).
 */
export function openActionLedgerStream(
  config: {
    apiBase?: string;
    eventSourceFactory?: (url: string) => EventSource;
    multiplex?: MultiplexedStream;
    onActivity: (rows: UnifiedActionEntry[]) => void;
  },
): () => void {
  const { onActivity, ...source } = config;
  return openLedgerSource(source, (state) => onActivity(state.rows.map(projectBackend)));
}

/**
 * Tempdoc 550 thesis I (process-spanning ONE log): bridge FE-local effects INTO the one
 * authoritative backend log. Subscribes the Effect Journal and POSTs each new FE-local effect to
 * {@code POST /api/action-ledger/events}; the effect then returns via the stream as one log row.
 * invoke-operation effects are NOT posted — the backend already logs them as Operation events, so
 * posting would double-count. Wired once at shell mount (always-mounted chrome). Returns a stop fn.
 */
export function startEffectIngest(config: { apiBase?: string; fetchImpl?: typeof fetch }): () => void {
  const base = (config.apiBase ?? '').replace(/\/$/, '');
  const fetchImpl = config.fetchImpl ?? ('fetch' in globalThis ? authorizedFetch : undefined);
  let lastId = 0;
  const flush = (): void => {
    if (!fetchImpl) return; // no fetch available (e.g. a non-browser test env) — nothing to ingest.
    for (const j of listJournal()) {
      if (j.id <= lastId) continue;
      lastId = Math.max(lastId, j.id);
      // invoke-operation effects become authoritative backend Operation events — don't double-post.
      if (j.effect.kind === 'invoke-operation') continue;
      const subject = j.effect.kind === 'navigate' ? j.effect.to : '';
      void fetchImpl(`${base}/api/action-ledger/events`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          id: journalEventId(j),
          effectKind: j.effect.kind,
          originator: j.originator,
          subject,
          occurredAt: j.invokedAt,
        }),
      }).catch(() => {
        // Fire-and-forget: a failed ingest must never break the FE; the effect still ran locally.
      });
    }
  };
  flush();
  return subscribeJournal(() => flush());
}
