// SPDX-License-Identifier: Apache-2.0
/**
 * Effect Journal substrate — Tempdoc 543 §13.2.2.
 *
 * Every `applyEffect` invocation (Action substrate Slice 7) writes
 * to a kernel-owned typed log. Consumers (Undo UI, Macro panel, AI
 * preview, Audit) project over the log; they are not bespoke logging
 * — they are different views of the same event stream.
 *
 * Slice 4 ships:
 *   - The journal store + recordEffect / listJournal / undoLastEffect
 *     API.
 *   - Per-Effect declared inverses (navigate, open-pane/close-pane
 *     symmetric; toast/noop irreversible; invoke-operation
 *     placeholder pending Operation.policy.inverse Java wire field).
 *   - Cross-session persistence via localStorage (500-entry LRU cap).
 *   - The first production consumer wires `recordEffect({kind:
 *     'navigate'})` from `NavigationJournal.recordNavigation` so
 *     every navigation in main lands in the Effect Journal.
 *
 * Layer placement (per 507 / KCS bridge):
 *   - JournalEntry type: kernel primitive.
 *   - Write-side (journal store, inverse derivation, cursor):
 *     kernel substrate.
 *   - Consumers (Undo UI, Macro): future feature modules.
 *
 * Per §13.5 rule 2 (per-Provenance read isolation): plugins read
 * only their own entries via `listJournalFor(contributorId)`; never
 * write arbitrary entries. The recordEffect API today is
 * unconditionally callable but the documented intent is "kernel
 * write boundary."
 */

import type { Effect } from '../effect.js';
import type { Provenance } from '../../primitives/provenance.js';

/**
 * The journal entry record. Append-only; entries are never mutated.
 *
 *   `id`         — monotonic unique id (cursor + chained-action refs).
 *   `effect`     — the closed-union Effect that was dispatched.
 *   `invokedBy`  — Provenance of the caller (CORE / TRUSTED_PLUGIN /
 *                  UNTRUSTED_PLUGIN + contributor id + version).
 *   `invokedAt`  — ISO-8601 timestamp of the write.
 *   `inverse`    — the derived inverse Effect, OR null when no
 *                  inverse is computable from the Effect shape alone.
 *   `causation`  — parent entry's id when chained (future Macro use).
 *
 * Shape mirrors 513's lazy-pointer DAG: child references parent by
 * id, no eager copy.
 */
/**
 * §21.E / D7 — originator distinguishes who initiated an effect:
 *   - 'user': direct user interaction (default for existing call sites).
 *   - 'agent': AI-emitted; required for the per-originator filter the
 *     AI-preview projection consumes (§22.5.8).
 *   - 'system': scheduled, lifecycle, or boot-time effects.
 */
export type EffectOriginator = 'user' | 'agent' | 'system';

export interface JournalEntry {
  readonly id: number;
  /** Globally unique ledger identity; absent only on entries persisted before this field existed. */
  readonly ledgerId?: string;
  readonly effect: Effect;
  readonly invokedBy: Provenance;
  readonly invokedAt: string;
  readonly inverse: Effect | null;
  readonly causation?: number;
  /** §21.E — defaults to 'user' when not supplied. */
  readonly originator: EffectOriginator;
  /**
   * §21.E — when this JournalEntry was the outcome of an acceptPending
   * / rejectPending call, the lifecycle state of the original Pending:
   *   - 'accepted': Pending → dispatched (regular entry behavior).
   *   - 'rejected': Pending dropped; the journal records that it was
   *     considered but vetoed (the Effect was NOT dispatched).
   * Absent when the entry didn't originate from a Pending.
   */
  readonly pendingOutcome?: 'accepted' | 'rejected';
}

const _entries: JournalEntry[] = [];

/** Preserve historical wire identities on restore; new effects persist a UUID identity once. */
export function journalEventId(entry: JournalEntry): string {
  return entry.ledgerId ?? `fe-effect:${entry.id}`;
}
const _listeners = new Set<(entry: JournalEntry) => void>();
let _nextId = 1;

// ============================================================
// Cross-session persistence (Tempdoc 543 §13.7 q.3)
// ============================================================

const PERSIST_KEY = 'justsearch.effect-journal.v1';
const MAX_PERSISTED_ENTRIES = 500;

interface PersistedJournal {
  readonly version: 1;
  readonly nextId: number;
  readonly entries: ReadonlyArray<JournalEntry>;
}

// §25.α4 — shared primitive (was duplicated in profiles/index.ts).
import { safeLocalStorage } from '../../primitives/storage.js';
// §25.α5 — shared notify primitive.
import { notifyAllWith } from '../../primitives/notify.js';

// §25.γ5 — manifest-declared per-operation inverse hook. The manifest
// substrate calls `setEffectInverseLookup` at module load so this
// substrate can consult declarative inverses for invoke-operation
// effects without importing manifest/ (which would create a cycle —
// manifest depends on effects' Effect type via the effectInverses
// field).
let _effectInverseLookup: ((operationId: string) => Effect | undefined) | null =
  null;
export function setEffectInverseLookup(
  fn: (operationId: string) => Effect | undefined,
): void {
  _effectInverseLookup = fn;
}
function lookupEffectInverseForOperation(operationId: string): Effect | undefined {
  return _effectInverseLookup ? _effectInverseLookup(operationId) : undefined;
}

// ── Backend-declared per-operation inverses (tempdoc 560 WS3 / §4.2) ──
// The Java `OperationPolicy.inverseOperationRef` flows over the wire as
// `OperationPolicy.inverseOperationId` (a sibling operation id). Unlike the
// manifest lookup above — which ships a full Effect — the backend declares the
// inverse as another operation, so we materialize it into an invoke-operation
// Effect. `deriveInverse` consults this AFTER the manifest lookup, so a
// manifest-shipped Effect (which can be richer / pure-FE) still wins. The map
// is owned here (not in OperationCatalogClient) to keep the api/ layer free of
// a shell-v0 import; the composition root wires `syncBackendOperationInverses`
// to the catalog client's `onCatalogChange`.
const _backendOperationInverses = new Map<string, string>();

/**
 * Replace the backend-declared inverse map from the current Operation catalog.
 * Idempotent: clears then repopulates, so a mid-session catalog refresh that
 * drops an inverse correctly removes it.
 */
export function syncBackendOperationInverses(
  ops: ReadonlyArray<{
    readonly id: string;
    readonly policy?: { readonly inverseOperationId?: string | null };
  }>,
): void {
  _backendOperationInverses.clear();
  for (const op of ops) {
    const inverse = op.policy?.inverseOperationId;
    if (inverse) _backendOperationInverses.set(op.id, inverse);
  }
}

/** Test-only: clear backend-declared inverses. */
export function __clearBackendOperationInversesForTest(): void {
  _backendOperationInverses.clear();
}

function writePersisted(): void {
  const storage = safeLocalStorage();
  if (!storage) return;
  const cap =
    _entries.length > MAX_PERSISTED_ENTRIES
      ? _entries.slice(-MAX_PERSISTED_ENTRIES)
      : _entries;
  const payload: PersistedJournal = {
    version: 1,
    nextId: _nextId,
    entries: cap,
  };
  try {
    storage.setItem(PERSIST_KEY, JSON.stringify(payload));
  } catch {
    /* swallow quota / serialization */
  }
}

/**
 * Restore the journal from localStorage. Idempotent — subsequent
 * calls within a session are no-ops. Called once at boot.
 */
let _restored = false;
export function restoreJournalFromStorage(): void {
  if (_restored) return;
  _restored = true;
  const storage = safeLocalStorage();
  if (!storage) return;
  const raw = storage.getItem(PERSIST_KEY);
  if (!raw) return;
  try {
    const parsed = JSON.parse(raw) as PersistedJournal;
    if (parsed?.version !== 1) return;
    if (!Array.isArray(parsed.entries)) return;
    _entries.push(...parsed.entries);
    if (typeof parsed.nextId === 'number') {
      _nextId = Math.max(_nextId, parsed.nextId);
    }
    _undoCursor = _entries.length;
  } catch {
    /* swallow corrupt storage */
  }
}

/** Test-only: clear localStorage persistence. */
export function __clearJournalPersistenceForTest(): void {
  const storage = safeLocalStorage();
  storage?.removeItem(PERSIST_KEY);
  _restored = false;
}

// ============================================================
// Undo cursor + previous-nav tracking
// ============================================================

let _undoCursor = 0;
let _previousNavTarget: string | null = null;

// 543-fwd #1 — navigate replay-suppression (async-safe). When the cursor-based
// undo/redo dispatches a `navigate` effect, the side-effect (location.hash =) is
// caught ASYNCHRONOUSLY by the router → NavigationJournal.recordNavigation →
// recordEffect, which would re-journal the navigation and reset _undoCursor to
// the end — truncating the redo stack. A synchronous suppression window can't
// span that async gap, so instead we stash the target URL and let
// NavigationJournal consume it on the next matching navigation, skipping the
// re-journal exactly once. Set only by the cursor undo/redo; the by-originator
// "Undo AI" path deliberately appends (forward-inverse model) and never sets it.
let _replayNavTarget: string | null = null;

// Navigate URLs reach the journal in two formats: NavigationJournal records
// `to` WITHOUT a leading '#' (e.g. "justsearch://surface/x"), while a derived
// inverse may carry the live `window.location.hash` WITH '#'. Normalize both
// sides so the replay-suppression match is format-insensitive.
function _normNav(s: string): string {
  return s.startsWith('#') ? s.slice(1) : s;
}

/**
 * 543-fwd #1 — NavigationJournal calls this before re-journaling a navigation.
 * Returns true (and clears the flag) when the navigation to `url` was driven by
 * a cursor undo/redo replay and must NOT be re-recorded into the Effect Journal.
 */
export function consumeReplayNavSuppression(url: string): boolean {
  if (_replayNavTarget !== null && _replayNavTarget === _normNav(url)) {
    _replayNavTarget = null;
    return true;
  }
  return false;
}

/**
 * Derive the inverse Effect BEFORE dispatch (so navigate can record
 * the pre-navigation hash). Returns null when no inverse is
 * computable from the Effect shape alone.
 *
 * REVERSIBILITY IS TWO-TIERED (569 §18.B — the journal carries both):
 *   1. Per-effect inverse — for effects whose prior value is knowable from
 *      the Effect shape + live state (navigate, open/close pane/modal,
 *      set-form-value). This function returns it; `undoLastEffect` replays it.
 *   2. Journal-REPLAY reversibility — the 6 forward-only v3 presentation/search
 *      effects (set-appearance, set-ui-mode, apply-presentation, save-settings,
 *      set-search-query, set-search-filter) deliberately return `null` here:
 *      the declaration is static, so there is no author-knowable prior value to
 *      restore; reversibility is via replaying the forward journal sequence,
 *      not a per-effect inverse. `undoLastEffect` skips `inverse === null`
 *      entries (continuing to the nearest reversible one).
 *
 * 'invoke-operation' inverse (§32 R-P2): consults the manifest-declared
 * inverse (`contributes.effectInverses`, via setEffectInverseLookup) and
 * returns it when present; otherwise null. It no longer fabricates a
 * `toast("Undo: …")` (which fired on undo without reversing anything).
 * Backend-undoable operations (OperationPolicy.undoSupported +
 * handler.undo(executionId)) are reversed through the executionId bridge
 * (§32 U2 / S5), NOT through this Effect inverse.
 */
function deriveInverse(effect: Effect): Effect | null {
  switch (effect.kind) {
    case 'noop':
      return null;
    case 'navigate': {
      const live =
        typeof window !== 'undefined' && window.location
          ? window.location.hash
          : (_previousNavTarget ?? '');
      const before = _previousNavTarget ?? live;
      _previousNavTarget = effect.to;
      if (!before) return null;
      return { kind: 'navigate' as const, to: before };
    }
    case 'open-pane':
      return { kind: 'close-pane' as const, paneId: effect.paneId };
    case 'close-pane':
      return { kind: 'open-pane' as const, paneId: effect.paneId };
    case 'toast':
      return null;
    case 'invoke-operation': {
      // §25.γ5 — consult manifest-declared inverses first. Plugins
      // (and first-party manifests) can ship per-operation declarative
      // inverses via `contributes.effectInverses` without waiting for
      // the Java-side Operation.policy.inverse? wire extension.
      const declared = lookupEffectInverseForOperation(effect.operationId);
      if (declared) return declared;
      // Tempdoc 560 WS3 — backend-declared inverse (OperationPolicy.inverseOperationId).
      // Materialize the sibling operation id into an invoke-operation inverse Effect. This is
      // the wire extension §32 R-P2 named as pending: backend operations can now declare a
      // symmetric inverse (add-watched-root ↔ remove-watched-root) without a manifest entry.
      const backendInverse = _backendOperationInverses.get(effect.operationId);
      if (backendInverse) {
        return { kind: 'invoke-operation' as const, operationId: backendInverse };
      }
      // §32 R-P2 — no declared inverse ⇒ null (irreversible at the
      // Effect-inverse layer). The prior `toast("Undo: …")` surfaced a
      // MISLEADING "Undo: X" toast on undo without actually reversing
      // anything. Backend-undoable operations (OperationPolicy.undoSupported
      // + handler.undo(executionId)) are reversed through the executionId
      // bridge (§32 U2 / S5), NOT through this Effect inverse; the undo
      // cursor correctly walks past entries that carry no real inverse.
      return null;
    }
    // ──────────────────────────────────────────────────────────────
    // §21.D Effect union v2 — inverse derivations.
    //
    // Symmetric pairs (set ↔ clear, open ↔ close, set-form-value with
    // previousValue) derive structurally. Irreversible kinds (focus,
    // scroll, copy-to-clipboard) return null so the undo cursor walks
    // past them to the next reversible entry.
    // ──────────────────────────────────────────────────────────────
    case 'set-selection': {
      // If caller supplied previousIds, restore them; otherwise clear.
      if (effect.previousIds !== undefined) {
        return {
          kind: 'set-selection' as const,
          surfaceId: effect.surfaceId,
          ids: effect.previousIds,
        };
      }
      return { kind: 'clear-selection' as const, surfaceId: effect.surfaceId };
    }
    case 'clear-selection': {
      if (effect.previousIds !== undefined && effect.previousIds.length > 0) {
        return {
          kind: 'set-selection' as const,
          surfaceId: effect.surfaceId,
          ids: effect.previousIds,
        };
      }
      return null;
    }
    case 'focus-element':
      // Focus is non-reversible without prior-focus tracking; keep null.
      return null;
    case 'scroll-to':
      // Scroll position not tracked; non-reversible from shape alone.
      return null;
    case 'open-modal':
      return { kind: 'close-modal' as const, modalId: effect.modalId };
    case 'close-modal':
      return { kind: 'open-modal' as const, modalId: effect.modalId };
    case 'copy-to-clipboard':
      // Clipboard mutation can't be undone (no read-prior-clipboard
      // permission); the toast already signals the user.
      return null;
    case 'set-form-value': {
      if (effect.previousValue === undefined) return null;
      return {
        kind: 'set-form-value' as const,
        formId: effect.formId,
        path: effect.path,
        value: effect.previousValue,
      };
    }
    // 569 §14 — Effect v3 presentation + search intent kinds. Forward-only
    // authored UI-state mutations: the declaration is static, so there is no
    // author-knowable prior value to restore. Reversibility is via journal
    // REPLAY of the forward sequence, not a per-effect inverse (cf. focus /
    // scroll / copy). Null inverse ⇒ the undo cursor walks past them.
    case 'set-appearance':
    case 'set-ui-mode':
    case 'apply-presentation':
    case 'save-settings':
    case 'set-search-query':
    case 'set-search-filter':
      return null;
    // §32 U2 — an undo-operation IS the reversal; no further inverse.
    case 'undo-operation':
      return null;
    // §25.β5 — DataEffect arm: data results / errors are inherently
    // non-reversible (a returned value cannot be "un-returned"). The
    // journal entry exists so consumers can audit/replay, but undo
    // walks past these.
    case 'data-result':
      return null;
    case 'data-error':
      return null;
    default: {
      const _exhaustive: never = effect;
      void _exhaustive;
      return null;
    }
  }
}

/**
 * Append an Effect to the journal. Returns the new entry.
 *
 * Called by `applyEffect()` (Action substrate, Slice 7) immediately
 * before dispatch — substrate-write happens FIRST so a failed
 * dispatch still leaves an audit trail. Today also called directly
 * by NavigationJournal so every navigation in chrome lands in the
 * journal even before Action substrate ships.
 */
export interface RecordEffectOptions {
  readonly causation?: number;
  readonly originator?: EffectOriginator;
  readonly pendingOutcome?: 'accepted' | 'rejected';
}

export function recordEffect(
  effect: Effect,
  invokedBy: Provenance,
  opts: RecordEffectOptions = {},
): JournalEntry {
  // §32 R-S2 — options-only. The legacy `number` causation overload
  // (§21.E) had zero production or test callers (verified 2026-05-25),
  // so it was dropped; `causation` now travels via `opts.causation`.
  const entry: JournalEntry = {
    id: _nextId++,
    ledgerId: `fe-effect:${crypto.randomUUID()}`,
    effect,
    invokedBy,
    invokedAt: new Date().toISOString(),
    inverse: deriveInverse(effect),
    originator: opts.originator ?? 'user',
    ...(opts.causation !== undefined ? { causation: opts.causation } : {}),
    ...(opts.pendingOutcome !== undefined ? { pendingOutcome: opts.pendingOutcome } : {}),
  };
  _entries.push(entry);
  // 543-fwd #1 (live fix) — truncate-on-new-append applies to REAL effects only.
  // A `noop` changes nothing, so it must be transparent to the undo/redo cursor:
  // notably the global Undo/Redo kernel Actions return `{kind:'noop'}`, which
  // invokeAndApply journals — if that reset the cursor it would kill the redo
  // stack the undo just created (the integration bug live-validation caught;
  // the substrate unit tests called undo/redo directly and missed it).
  if (effect.kind !== 'noop') {
    _undoCursor = _entries.length;
  }
  notifyAllWith(_listeners, entry);
  writePersisted();
  return entry;
}

/** Return a read-only snapshot of all journal entries (in order). */
export function listJournal(): readonly JournalEntry[] {
  return _entries.slice();
}

/**
 * Filtered view by Provenance contributor. Per §13.5 rule 2: a
 * plugin can read its own entries, never others'.
 */
export function listJournalFor(
  contributorId: string,
): readonly JournalEntry[] {
  return _entries.filter((e) => e.invokedBy.contributorId === contributorId);
}

/**
 * §21.E (D7) — filtered view by originator. Powers UIs like
 * "show me everything the AI did this session" (§22.5.8 surveyed UX).
 */
export function listJournalByOriginator(
  originator: EffectOriginator,
): readonly JournalEntry[] {
  return _entries.filter((e) => e.originator === originator);
}

/**
 * §32 U3 — summarize agent-originated activity since `sinceId` (exclusive)
 * for the "What the AI did" digest. Counts agent actions grouped by Effect
 * kind. Passing the last-seen entry id makes the digest say "since you last
 * looked".
 *
 * Counting rule: an entry counts when `originator === 'agent'` and it was
 * not `rejected` (a vetoed proposal never happened). This avoids
 * double-counting accepted proposals — `acceptPending` records the
 * dispatch entry under the default `'user'` originator and a separate
 * `'agent' + pendingOutcome:'accepted'` marker, so counting agent entries
 * tallies exactly one per action.
 */
export interface AgentActivityDigest {
  readonly total: number;
  readonly byKind: Readonly<Record<string, number>>;
  /** Highest agent entry id seen — advance the "last seen" cursor to this. */
  readonly latestId: number;
}

export function summarizeAgentActivity(sinceId = 0): AgentActivityDigest {
  const byKind: Record<string, number> = {};
  let total = 0;
  let latestId = sinceId;
  for (const e of _entries) {
    if (e.originator !== 'agent') continue;
    if (e.id <= sinceId) continue;
    if (e.id > latestId) latestId = e.id;
    if (e.pendingOutcome === 'rejected') continue; // vetoed — never happened
    byKind[e.effect.kind] = (byKind[e.effect.kind] ?? 0) + 1;
    total += 1;
  }
  return { total, byKind, latestId };
}

/**
 * §21.E α8 — preview an Effect without dispatching it. Returns the
 * JournalEntry shape (id + inverse + originator) that WOULD be
 * appended, but does not actually append. Foundation for PendingEffect
 * (§14.4 / §21.E): the preview becomes the "what would happen" payload
 * the user sees before they accept/reject.
 *
 * The preview's id is informational only — the real append in
 * `recordEffect` advances `_nextId` independently.
 */
export function previewEffect(
  effect: Effect,
  invokedBy: Provenance,
  originator: EffectOriginator = 'user',
): JournalEntry {
  return {
    id: _nextId, // informational; not advanced
    effect,
    invokedBy,
    invokedAt: new Date().toISOString(),
    inverse: deriveInverse(effect),
    originator,
  };
}

/**
 * §32 R-E5 — preview a SEQUENCE of effects without dispatching any. Returns the
 * would-be JournalEntry per effect (id + derived inverse + originator) so a
 * consumer can show "this plan would do: …" before the user accepts/runs it
 * (Intent Preview). No journal append, no dispatch.
 */
export function previewSequence(
  effects: ReadonlyArray<Effect>,
  invokedBy: Provenance,
  originator: EffectOriginator = 'user',
): readonly JournalEntry[] {
  return effects.map((e) => previewEffect(e, invokedBy, originator));
}

/** Total entry count. */
export function getJournalSize(): number {
  return _entries.length;
}

// ============================================================
// 543-fwd #19 — journal export / session archive / replay
// ============================================================
//
// Serialize journal entries to a portable JSON archive (no sync infra) and
// re-hydrate the effect sequence for replay through the macro engine. The
// Effect union is closed + JSON-safe (no functions/cycles), so a round-trip is
// lossless for the parts replay needs (the effects, in order). A seam toward
// future cross-device sync.

export const JOURNAL_ARCHIVE_VERSION = 1 as const;

export interface JournalArchive {
  readonly version: typeof JOURNAL_ARCHIVE_VERSION;
  readonly exportedAt: string;
  /** The replayable payload: each entry's effect + light metadata. */
  readonly entries: ReadonlyArray<{
    readonly effect: Effect;
    readonly originator: EffectOriginator;
    readonly invokedAt: string;
  }>;
}

/**
 * Serialize the given entries (default: the whole journal) to a portable JSON
 * archive string. Skips pending-lifecycle markers (`pendingOutcome`) — those
 * are proposal bookkeeping, not dispatched actions to replay.
 */
export function exportJournalArchive(
  entries: ReadonlyArray<JournalEntry> = _entries,
): string {
  const archive: JournalArchive = {
    version: JOURNAL_ARCHIVE_VERSION,
    exportedAt: new Date().toISOString(),
    entries: entries
      .filter((e) => e.pendingOutcome === undefined)
      .map((e) => ({ effect: e.effect, originator: e.originator, invokedAt: e.invokedAt })),
  };
  return JSON.stringify(archive, null, 2);
}

/**
 * Parse a journal archive (produced by `exportJournalArchive`) back to the
 * ordered Effect sequence, for replay via the macro engine. Returns [] on
 * malformed input or a version mismatch — callers surface a friendly error.
 */
export function importJournalArchive(json: string): readonly Effect[] {
  try {
    const parsed = JSON.parse(json) as Partial<JournalArchive>;
    if (parsed?.version !== JOURNAL_ARCHIVE_VERSION) return [];
    if (!Array.isArray(parsed.entries)) return [];
    return parsed.entries
      .map((e) => (e && typeof e === 'object' ? (e as { effect?: Effect }).effect : undefined))
      .filter((eff): eff is Effect => !!eff && typeof (eff as Effect).kind === 'string');
  } catch {
    return [];
  }
}

/** Entry at id, or undefined. */
export function getJournalEntry(id: number): JournalEntry | undefined {
  return _entries.find((e) => e.id === id);
}

/**
 * §32 R-E3 — walk the causation chain from an entry up to its root.
 * Returns the ancestor entries oldest-first (root … direct parent),
 * following each entry's `causation` pointer (513 lazy-pointer DAG). Empty
 * when the entry has no `causation` parent or the id is unknown. Guards
 * against cycles defensively (the append-only journal can't form one, but
 * a corrupt restore could). Powers the audit log's "caused by" trace.
 */
export function getCausationChain(id: number): readonly JournalEntry[] {
  const chain: JournalEntry[] = [];
  const seen = new Set<number>();
  let cur = getJournalEntry(id);
  while (cur && cur.causation !== undefined && !seen.has(cur.causation)) {
    seen.add(cur.causation);
    const parent = getJournalEntry(cur.causation);
    if (!parent) break;
    chain.push(parent);
    cur = parent;
  }
  return chain.reverse();
}

// ============================================================
// §32 U2 — undoable-operation side-map
// ============================================================
//
// An invoke-operation effect is journaled at dispatch time, BEFORE the
// backend runs and returns an executionId. When the Shell jf-invoke-operation
// listener gets a successful result carrying an executionId (undoSupported
// ops), it associates that executionId with the journal entry here. The audit
// log then offers "Undo" for entries with an association; clicking dispatches
// an undo-operation Effect → POST /api/undo/{operationId}. Kept as a side-map
// so JournalEntry stays append-only / immutable.

export interface UndoableOperation {
  readonly operationId: string;
  readonly executionId: string;
}

const _undoable = new Map<number, UndoableOperation>();

/** Associate a journal entry with a backend-undoable operation execution. */
export function markUndoableOperation(
  journalEntryId: number,
  operationId: string,
  executionId: string,
): void {
  _undoable.set(journalEntryId, { operationId, executionId });
  // Re-fire journal listeners so the audit log re-renders the Undo
  // affordance for this entry (the entry itself is unchanged).
  const entry = getJournalEntry(journalEntryId);
  if (entry) notifyAllWith(_listeners, entry);
}

/** The backend-undoable operation associated with an entry, if any. */
export function getUndoableOperation(
  journalEntryId: number,
): UndoableOperation | undefined {
  return _undoable.get(journalEntryId);
}

/**
 * 543-fwd P1 — the effect that REVERSES a journal entry, across BOTH undo
 * models §32 supports:
 *   1. FE inverse (symmetric UI effects: open/close pane, navigate, …).
 *   2. Backend COMPENSATING action (saga pattern) — when the entry is a
 *      backend op that ran with undo support and captured an `executionId`
 *      (e.g. the agent's `core_file_operations` mutation), reverse it by
 *      dispatching an `undo-operation` Effect → `POST /api/undo/{id}`. These
 *      entries carry `inverse === null` (no FE inverse), so the originator-
 *      scoped undos previously SKIPPED them — meaning "Undo all AI actions"
 *      ignored the exact agent mutations worth undoing. This bridges that gap.
 * Returns null when the entry is genuinely non-reversible (e.g. a read such as
 * `core_search_index` — non-compensable, correctly nothing to undo).
 */
function undoEffectFor(entry: JournalEntry): Effect | null {
  if (entry.inverse !== null) return entry.inverse;
  const compensable = _undoable.get(entry.id);
  if (compensable) {
    return {
      kind: 'undo-operation' as const,
      operationId: compensable.operationId,
      executionId: compensable.executionId,
    };
  }
  return null;
}

/**
 * Undo the last reversible effect. Returns the inverse Effect that
 * was dispatched, or null when:
 *   - the journal is empty,
 *   - the cursor is at 0 (already undone everything), or
 *   - no reversible entries remain in front of the cursor.
 *
 * Cursor walks backward past irreversible entries until it finds a
 * reversible one. Prevents "many toasts block undo" UX failure. Also
 * walks past pending-lifecycle marker entries (any `pendingOutcome`),
 * because the canonical undoable record is the markerless dispatch
 * entry, not the marker:
 *   - 'rejected': the proposal was never dispatched — undoing its
 *     derivable inverse would reverse something that never happened.
 *   - 'accepted': acceptPending records the dispatch via applyFn (a
 *     markerless entry) AND this marker; both carry the same inverse,
 *     so undoing the marker too would double-apply it.
 * (The originator-scoped undos skip ONLY 'rejected' — there the
 * 'accepted' agent marker is the sole originator-attributed undoable,
 * since the dispatch entry is attributed to 'user'.)
 *
 * The applyFn is the kernel dispatcher (Slice 7's `applyEffect` once
 * landed); pre-Slice-7 callers can pass a custom dispatcher.
 */
export function undoLastEffect(
  applyFn?: (effect: Effect) => void,
): Effect | null {
  while (_undoCursor > 0) {
    _undoCursor -= 1;
    const entry = _entries[_undoCursor];
    if (!entry) continue;
    if (entry.pendingOutcome !== undefined) continue; // pending-lifecycle marker — canonical entry is the markerless dispatch
    if (entry.inverse === null) continue;
    if (applyFn) {
      // 543-fwd #1 — a navigate inverse re-journals via the router async; flag
      // it so NavigationJournal skips the re-record (else the cursor resets).
      if (entry.inverse.kind === 'navigate') _replayNavTarget = _normNav(entry.inverse.to);
      applyFn(entry.inverse);
    }
    return entry.inverse;
  }
  return null;
}

/**
 * 543-fwd #1 (redo) — re-apply the next undone effect, advancing the SAME
 * `_undoCursor` that `undoLastEffect` retracts. Symmetric to undoLastEffect:
 * walks FORWARD from the cursor, skipping pending-lifecycle markers and
 * irreversible (null-inverse) entries — the exact set undo skipped on the way
 * back — and re-applies the original `effect` (not the inverse) of the next
 * reversible entry. Returns that effect, or null when the cursor is already at
 * the end (nothing was undone / everything re-done).
 *
 * The applyFn MUST be the journal-suppressed dispatcher
 * (`dispatchEffectToChrome`), NOT `applyEffect`: re-applying through applyEffect
 * would append a fresh journal entry and reset `_undoCursor` to the end
 * (recordEffect's truncate-on-append), destroying the redo stack. Redo moves
 * the cursor over existing history; it does not write history.
 *
 * Truncate-on-new-append + persistence: any real new effect calls recordEffect,
 * which sets `_undoCursor = _entries.length` — so a new action discards the redo
 * stack (the standard two-stack rule). The cursor is NOT persisted; restore sets
 * it to `_entries.length`, so redo is session-only by design (a restored session
 * has nothing to redo).
 */
export function redoLastEffect(applyFn?: (effect: Effect) => void): Effect | null {
  while (_undoCursor < _entries.length) {
    const entry = _entries[_undoCursor];
    _undoCursor += 1;
    if (!entry) continue;
    if (entry.pendingOutcome !== undefined) continue; // pending-lifecycle marker
    if (entry.inverse === null) continue; // irreversible — undo skipped it too
    if (applyFn) {
      // 543-fwd #1 — see undoLastEffect: suppress the navigate re-journal so the
      // forward replay doesn't reset the cursor and truncate further redo.
      if (entry.effect.kind === 'navigate') _replayNavTarget = _normNav(entry.effect.to);
      applyFn(entry.effect);
    }
    return entry.effect;
  }
  return null;
}

/**
 * §28.W4 / §14.4 #1 closure — originator-grouped undo.
 *
 * Walks backward through the journal looking for the most recent
 * reversible entry whose originator matches the requested filter.
 * If found, dispatches its inverse via applyFn and returns the
 * inverse Effect. Returns null when no matching reversible entry
 * exists.
 *
 * Does NOT advance the global _undoCursor — originator-grouped undo
 * is a SEPARATE projection over the journal (e.g., "undo last AI
 * action" should not move the user's own undo cursor). The dispatched
 * inverse is itself recorded as a fresh journal entry (originator
 * defaults to 'user' since the human pressed the button).
 */
export function undoLastEffectByOriginator(
  originator: EffectOriginator,
  applyFn?: (effect: Effect) => void,
): Effect | null {
  for (let i = _entries.length - 1; i >= 0; i--) {
    const entry = _entries[i];
    if (!entry) continue;
    if (entry.originator !== originator) continue;
    if (entry.pendingOutcome === 'rejected') continue; // vetoed — never dispatched, nothing to undo
    const undo = undoEffectFor(entry); // FE inverse OR backend compensating action
    if (undo === null) continue;
    if (applyFn) applyFn(undo);
    return undo;
  }
  return null;
}

/**
 * 543-fwd idea #4 (undo-label) — peek at the entry that
 * `undoLastEffectByOriginator(originator)` WOULD undo next, without
 * dispatching anything. Returns the original JournalEntry (so callers can
 * label the undo, e.g. "Undid: Open knowledge pane") or null when nothing
 * reversible matches. Walk is identical to undoLastEffectByOriginator so the
 * label names exactly the effect the undo will reverse.
 */
export function peekLastUndoableByOriginator(
  originator: EffectOriginator,
): JournalEntry | null {
  for (let i = _entries.length - 1; i >= 0; i--) {
    const entry = _entries[i];
    if (!entry) continue;
    if (entry.originator !== originator) continue;
    if (entry.pendingOutcome === 'rejected') continue; // vetoed — never dispatched
    if (undoEffectFor(entry) === null) continue; // neither FE inverse nor compensable
    return entry;
  }
  return null;
}

/**
 * 543-fwd #8 (mass-undo-confirm) — the entries `undoAllByOriginator(originator)`
 * WOULD reverse, in the same reverse-chronological order it dispatches them, but
 * without dispatching. Powers the "you're about to undo these N actions" preview
 * shown before the user commits. Filter is identical to undoAllByOriginator
 * (same originator, skip vetoed, skip irreversible) so the preview is exact.
 */
export function previewUndoAllByOriginator(
  originator: EffectOriginator,
): readonly JournalEntry[] {
  const out: JournalEntry[] = [];
  for (let i = _entries.length - 1; i >= 0; i--) {
    const entry = _entries[i];
    if (!entry) continue;
    if (entry.originator !== originator) continue;
    if (entry.pendingOutcome === 'rejected') continue;
    if (undoEffectFor(entry) === null) continue; // neither FE inverse nor compensable
    out.push(entry);
  }
  return out;
}

/**
 * §28.W4 — undo ALL reversible effects from a given originator in
 * reverse chronological order. Powers "Undo all AI actions" UX.
 * Returns the count of inverses dispatched.
 */
export function undoAllByOriginator(
  originator: EffectOriginator,
  applyFn: (effect: Effect) => void,
): number {
  let count = 0;
  for (let i = _entries.length - 1; i >= 0; i--) {
    const entry = _entries[i];
    if (!entry) continue;
    if (entry.originator !== originator) continue;
    if (entry.pendingOutcome === 'rejected') continue; // vetoed — never dispatched, nothing to undo
    const undo = undoEffectFor(entry); // FE inverse OR backend compensating action
    if (undo === null) continue;
    try {
      applyFn(undo);
      count++;
    } catch {
      break;
    }
  }
  return count;
}

/**
 * 543-fwd #5 — TIME-TRAVEL (scoped). The entries `undoToEntry(entryId)` WOULD
 * reverse: every journal entry strictly AFTER `entryId`, newest-first, that is
 * reversible (FE inverse OR backend-compensable via undoEffectFor). Non-
 * reversible entries (reads such as core_search_index; irreversible UI) are
 * skipped — this is selective time-travel over the reversible/compensable
 * subset, NOT a full agent state-rewind (that needs a checkpointer / state
 * snapshots, a separate project — the Effect journal logs effects, not state).
 */
export function previewUndoToEntry(entryId: number): readonly JournalEntry[] {
  const idx = _entries.findIndex((e) => e.id === entryId);
  if (idx < 0) return [];
  const out: JournalEntry[] = [];
  for (let i = _entries.length - 1; i > idx; i--) {
    const entry = _entries[i];
    if (!entry) continue;
    if (entry.pendingOutcome === 'rejected') continue;
    if (undoEffectFor(entry) === null) continue; // non-reversible — skip
    out.push(entry);
  }
  return out;
}

/**
 * 543-fwd #5 — restore the journal to the point just after `entryId` by
 * dispatching the reversal (FE inverse OR backend compensating undo-operation)
 * of every reversible entry after it, newest-first. Returns the count reversed.
 * Like the originator-scoped undos, this is a forward projection (each reversal
 * is itself a new effect when dispatched through applyEffect) — it does not move
 * the global cursor. Non-reversible entries after the point are left as-is.
 */
export function undoToEntry(
  entryId: number,
  applyFn: (effect: Effect) => void,
): number {
  const idx = _entries.findIndex((e) => e.id === entryId);
  if (idx < 0) return 0;
  let count = 0;
  for (let i = _entries.length - 1; i > idx; i--) {
    const entry = _entries[i];
    if (!entry) continue;
    if (entry.pendingOutcome === 'rejected') continue;
    const undo = undoEffectFor(entry);
    if (undo === null) continue;
    try {
      applyFn(undo);
      count++;
    } catch {
      break;
    }
  }
  return count;
}

// ============================================================
// 543-fwd #7 — grouped/atomic undo via causation
// ============================================================
//
// An agent turn's tool-calls share a causation chain (P3 enrichment: each
// chains to the previous; the first is the root). "Undo this turn" collapses
// the whole group into one undo step. Grouping key = the chain ROOT id.

/**
 * The causation-group root for an entry: the oldest ancestor in its causation
 * chain, or the entry's own id when it has no causation parent (it IS a root).
 * Null only when the id is unknown.
 */
export function getGroupRoot(id: number): number | null {
  const entry = getJournalEntry(id);
  if (!entry) return null;
  if (entry.causation === undefined) return entry.id;
  const chain = getCausationChain(id); // root … parent, oldest-first
  return chain.length > 0 ? chain[0]!.id : entry.id;
}

/**
 * The entries that `undoGroup(rootId)` WOULD reverse: every reversible /
 * compensable entry sharing the causation-group root `rootId`, newest-first.
 */
export function previewUndoGroup(rootId: number): readonly JournalEntry[] {
  const out: JournalEntry[] = [];
  for (let i = _entries.length - 1; i >= 0; i--) {
    const entry = _entries[i];
    if (!entry) continue;
    if (entry.pendingOutcome === 'rejected') continue;
    if (getGroupRoot(entry.id) !== rootId) continue;
    if (undoEffectFor(entry) === null) continue;
    out.push(entry);
  }
  return out;
}

/**
 * Reverse a whole causation group (an agent turn) atomically: dispatch the
 * reversal (FE inverse OR backend compensating undo) of every reversible member
 * sharing `rootId`, newest-first. Returns the count reversed.
 */
export function undoGroup(
  rootId: number,
  applyFn: (effect: Effect) => void,
): number {
  let count = 0;
  for (let i = _entries.length - 1; i >= 0; i--) {
    const entry = _entries[i];
    if (!entry) continue;
    if (entry.pendingOutcome === 'rejected') continue;
    if (getGroupRoot(entry.id) !== rootId) continue;
    const undo = undoEffectFor(entry);
    if (undo === null) continue;
    try {
      applyFn(undo);
      count++;
    } catch {
      break;
    }
  }
  return count;
}

/** Test helper. */
export function __resetUndoCursorForTest(): void {
  _undoCursor = _entries.length;
}

/** Subscribe to journal-append notifications. */
export function subscribeJournal(
  listener: (entry: JournalEntry) => void,
): () => void {
  _listeners.add(listener);
  return () => {
    _listeners.delete(listener);
  };
}

/** Test-only reset. */
export function __resetJournalForTest(): void {
  _entries.length = 0;
  _listeners.clear();
  _nextId = 1;
  _undoCursor = 0;
  _previousNavTarget = null;
  _replayNavTarget = null;
  _undoable.clear();
  __clearJournalPersistenceForTest();
}
