// SPDX-License-Identifier: Apache-2.0
/**
 * URLProjector — slice 489 §6 source-of-truth projection.
 *
 * State is the source of truth; the URL is a projection. The projector
 * subscribes to every store referenced by the active surface's
 * {@code SurfaceStateSchema.bindings} and writes the canonical URL to the
 * browser bar via {@code history.replaceState} when any of them publishes.
 *
 * Per slice 489 §10: the router never sets state; it only reads. The
 * Shell.activateSurface(...) caller is responsible for writing state before
 * activating; the projector then mirrors the resulting state onto the URL.
 *
 * Push vs replace semantics:
 *   - `replaceState` (default): used for incremental edits within a surface
 *     (typing in the search box) — the URL bar tracks the live state without
 *     bloating browser history.
 *   - `pushState`: used when the active surface changes (rail click, palette,
 *     deep-link). The Shell calls `pushAddress(...)` directly at activation
 *     time; the projector's subsequent state-driven writes use replaceState.
 *   - `pushState` again, tempdoc 864 PR C: an IN-SURFACE state change the
 *     emitting store declares to be a navigation — see
 *     {@link projectAsNavigation}. A surface can replace everything the reader
 *     is looking at without changing surface, and slice 489's two-case split
 *     had no room for that.
 */

import { canonicalize, parseUrl } from './parser.js';
import { getStore } from './storeRegistry.js';
import { getJournalState, recordNavigation } from '../state/NavigationJournal.js';
import { present } from '../display/present.js';
import { peekSearchV3Handoff } from '../utils/compose.js';
import type { ShellAddressNavigation, StateSnapshot } from './types.js';
import {
  getSurfaceStateSchema,
  resolveSurfaceStateSchema,
  type ResolvedSchema,
} from './surfaceSchemas.js';

interface ActiveProjection {
  surfaceId: string;
  schema: ResolvedSchema;
  unsubscribers: Array<() => void>;
}

let active: ActiveProjection | null = null;

/**
 * Slice 489 T1/G2 — trailing debounce for subscribe-driven URL writes.
 *
 * Without debouncing, typing in the search box fires history.replaceState on
 * every keystroke (visible flicker in the URL bar and a useless write rate
 * during fast typing). The projection model still demands eventual
 * consistency between state and URL — debouncing is the trailing-edge
 * collapse, not a semantic change.
 *
 * Window choice: 75ms. Typical fast-typist inter-keystroke is 80-150ms;
 * burst clusters hit 30-50ms. 75ms coalesces bursts cleanly without making
 * the URL feel stale on a normal-cadence typist (the trailing write lands
 * before the next intentional pause).
 *
 * The explicit final write inside activateProjection stays synchronous —
 * existing tests + the round-7 ordering invariant (pushAddress before
 * replaceState) depend on it.
 */
const URL_WRITE_DEBOUNCE_MS = 75;
let pendingTimer: ReturnType<typeof setTimeout> | null = null;

/**
 * Tempdoc 864 PR C — does the pending write want a history ENTRY?
 *
 * Slice 489's split was "surface change pushes, in-surface edit replaces", and it was drawn at the
 * wrong altitude for a surface that swaps its whole subject without changing surface. Search v3
 * replaces the entire transcript when the reader claims another conversation; under replace-only
 * projection that swap left no history entry, so Back did nothing and an accidental one was
 * recoverable only by reload (§2.7a — the incident this PR closes).
 *
 * The distinction is per-CHANGE, not per-surface and not per-store: the same store write is a
 * navigation when the reader opened something and a rehydration when the URL, a popstate or a
 * reload pointer replayed it. Only the emitting store knows which, so it says so by wrapping the
 * emission in {@link projectAsNavigation}. Everything that does not wrap keeps slice 489's
 * replaceState behaviour byte for byte.
 */
let pendingPush = false;
let emittingNavigation = false;

/**
 * Declare that the store change made inside `emit` is a navigation: the URL write it triggers adds
 * a history entry instead of replacing the current one, so the browser's own Back undoes it.
 *
 * Synchronous by contract — the flag is read by the subscriber firing inside `emit`, so a store
 * that notifies asynchronously would fall back to replace (safe direction: a missing entry, never
 * a spurious one).
 */
export function projectAsNavigation(emit: () => void): void {
  const previous = emittingNavigation;
  emittingNavigation = true;
  try {
    emit();
  } finally {
    emittingNavigation = previous;
  }
}

/**
 * Activate the projector for a given surface. Subscribes to every store the
 * surface's schema names; writes the URL on every store change.
 *
 * Idempotent: re-calling with the same surfaceId is a no-op; re-calling with
 * a different surfaceId tears down the prior subscriptions first.
 */
export function activateProjection(surfaceId: string): void {
  if (active?.surfaceId === surfaceId) return;
  deactivateProjection();

  const schema = getSurfaceStateSchema(surfaceId);
  if (!schema) {
    // Surface has no declared stateSchema — not URL-addressable; nothing to project.
    return;
  }
  const resolved = resolveSurfaceStateSchema(schema);

  const unsubscribers: Array<() => void> = [];
  for (const adapter of resolved.adapters) {
    unsubscribers.push(
      adapter.subscribe(() => {
        scheduleWrite(surfaceId, resolved);
      }),
    );
  }
  active = { surfaceId, schema: resolved, unsubscribers };
  // Adapter.subscribe fires the listener once on subscribe (matches production
  // store posture); those firings scheduled a debounced write. Cancel it
  // because the explicit final write below is synchronous + immediate.
  clearPendingWrite();
  // Initial write — make sure the URL reflects the current store state at
  // activation time.
  writeUrl(surfaceId, resolved);
}

/** Tear down all store subscriptions. Safe to call when no projection is active. */
export function deactivateProjection(): void {
  clearPendingWrite();
  if (!active) return;
  for (const unsub of active.unsubscribers) {
    try {
      unsub();
    } catch {
      // Subscriber teardown errors are non-fatal; ignore.
    }
  }
  active = null;
}

/** Replace accepted transient address inputs with the current durable navigation state. */
export function refreshProjection(): void {
  if (active === null) return;
  clearPendingWrite();
  writeUrl(active.surfaceId, active.schema);
}

function scheduleWrite(surfaceId: string, schema: ResolvedSchema): void {
  // A navigational change anywhere in a coalesced burst makes the trailing write a push: the
  // burst's net effect is that the reader is somewhere else, and that is one entry, not none.
  if (emittingNavigation) pendingPush = true;
  if (pendingTimer !== null) clearTimeout(pendingTimer);
  pendingTimer = setTimeout(() => {
    pendingTimer = null;
    const push = pendingPush;
    pendingPush = false;
    // Re-check `active` — deactivateProjection may have fired between schedule
    // and timer firing; the active surface may also have changed.
    if (active?.surfaceId === surfaceId) {
      writeUrl(surfaceId, schema, push);
    }
  }, URL_WRITE_DEBOUNCE_MS);
}

function clearPendingWrite(): void {
  pendingPush = false;
  if (pendingTimer !== null) {
    clearTimeout(pendingTimer);
    pendingTimer = null;
  }
}

/**
 * Flush any pending debounced URL write synchronously. Call this before
 * reading window.location.hash when you need the hash to reflect the
 * latest store state (e.g., bookmark save, copy URL). Without flushing,
 * the hash may lag by up to 75ms after the last store change.
 */
export function flushPendingProjection(): void {
  if (pendingTimer === null) return;
  clearTimeout(pendingTimer);
  pendingTimer = null;
  const push = pendingPush;
  pendingPush = false;
  if (active) {
    writeUrl(active.surfaceId, active.schema, push);
  }
}

/**
 * Flush a pending write ONLY when it is a navigational one — tempdoc 864 PR C, review finding F4.
 *
 * A claim made inside the 75ms debounce window and followed immediately by a surface change used to
 * be dropped: `activateProjection` tears the old projection down, and teardown cancels the pending
 * timer. Before this PR that cost a slightly stale URL; now it costs a history ENTRY the reader
 * earned, so the conversation they were in is not on the stack to go back to.
 *
 * Called at the TOP of `NavigationHandler.handle`, and that placement is the whole point. The
 * reviewer's suggested site — inside `deactivateProjection` — cannot work: `handle` calls
 * `pushAddress` BEFORE `activateProjection` (the slice 489 T1/N2 ordering invariant), so a flush
 * during teardown would write the OUTGOING surface's URL on top of the entry the incoming
 * navigation had just pushed. Here the browser is still on the outgoing address, so the entry lands
 * where it belongs.
 *
 * A no-op unless a navigational write is pending, so no other surface's behaviour changes.
 */
export function flushPendingNavigationWrite(): void {
  if (!pendingPush) return;
  flushPendingProjection();
}

/**
 * Explicitly push a new navigation address as a history entry (used when the
 * active surface itself changes, vs incremental state edits within a surface).
 * The projector then activates for the new surface, which performs its own
 * initial replaceState.
 */
export function pushAddress(addr: ShellAddressNavigation): void {
  const url = canonicalize(addr);
  try {
    window.history.pushState({ surfaceId: addr.target }, '', toRelative(url));
  } catch {
    // history API failure is non-fatal — chrome still navigates in-memory.
  }
}

/**
 * Read the current `window.location` and return the parsed Navigation address,
 * or null if the URL does not encode a justsearch:// navigation. Falls back
 * to scanning for a `?ja=<encoded>` style param (legacy escape hatch — not
 * currently used; reserved for future).
 */
export function currentAddress(): ShellAddressNavigation | null {
  if (typeof window === 'undefined') return null;
  const hash = window.location.hash;
  if (hash && hash.startsWith('#justsearch://')) {
    const parsed = parseUrl(hash.slice(1));
    if (parsed && parsed.kind === 'navigate') return parsed;
  }
  // The chrome runs at http://127.0.0.1:<port>/ — full `justsearch://` URLs
  // only arrive via Tauri deep-link callbacks or programmatic constructors,
  // not from window.location directly. The browser-bar projection writes the
  // canonical form as a hash (`#justsearch://surface/...`) so it survives
  // refresh while staying under the loopback origin's URL.
  return null;
}

// ----- helpers -----

function writeUrl(surfaceId: string, schema: ResolvedSchema, push = false): void {
  // Refused or not-yet-mounted intake still owns its address inputs. Project again
  // after acceptance; an ordinary store refresh must not erase the only incoming copy.
  if (surfaceId === 'core.search-v3-surface' && peekSearchV3Handoff()?.origin === 'fresh') return;
  const state = collectState(schema);
  const addr: ShellAddressNavigation = {
    kind: 'navigate',
    target: surfaceId,
    state,
  };
  const address = canonicalize(addr);
  const url = toRelative(address);
  const conversationView = surfaceId === 'core.search-v3-surface';
  try {
    // Encode the canonical justsearch:// URL into the hash fragment so the
    // browser bar projection survives refresh under the loopback HTTP origin.
    if (push && !isCurrentUrl(url)) {
      // Conversation claims bypass IntentRouter, whose Shell subscriber journals surface
      // navigation. Use that same journal here, before leaving the departing browser address.
      if (conversationView) {
        const previous = getJournalState();
        const departing = previous.entries[previous.cursor];
        if (departing) window.history.replaceState(
          { ...window.history.state, navigationJournalId: departing.id }, '',
        );
        recordNavigation(surfaceId, address, present({ kind: 'surface', id: surfaceId }).label, 'BUTTON');
      }
      const journal = conversationView ? getJournalState() : null;
      const entry = journal?.entries[journal.cursor];
      window.history.pushState({ surfaceId, ...(entry ? { navigationJournalId: entry.id } : {}) }, '', url);
      return;
    }
    const journal = conversationView ? getJournalState() : null;
    const entry = journal?.entries[journal.cursor];
    window.history.replaceState(conversationView
      ? { ...window.history.state, surfaceId, ...(entry?.url === address ? { navigationJournalId: entry.id } : {}) }
      : { surfaceId }, '', url);
  } catch {
    // history API failure non-fatal.
  }
}

/**
 * Is the browser already showing this URL? A push that would duplicate the current entry is
 * downgraded to a replace: two adjacent entries for one address make Back a no-op that costs a
 * press, which is the failure mode this whole projection exists to remove.
 */
function isCurrentUrl(relative: string): boolean {
  if (typeof window === 'undefined') return false;
  return window.location.hash === relative;
}

function collectState(schema: ResolvedSchema): StateSnapshot {
  const out: StateSnapshot = {};
  for (const adapter of schema.adapters) {
    const slice = adapter.serialize();
    for (const [k, v] of Object.entries(slice)) {
      if (v !== undefined && v !== null) {
        out[k] = v;
      }
    }
  }
  return out;
}

function toRelative(canonical: string): string {
  // Convert "justsearch://surface/<id>?<args>" to "#justsearch://surface/<id>?<args>"
  // so it lives under the chrome's loopback HTTP origin. The hash never round-
  // trips to the server (no extra request), and refresh preserves it.
  return '#' + canonical;
}

// Test-only helper to read the active projection's surfaceId (vitest).
export function __activeSurfaceIdForTest(): string | null {
  return active?.surfaceId ?? null;
}

/**
 * Test-only helper — flush any pending debounced URL write synchronously.
 * Tests that emit state and assert URL-write side effects in the same tick
 * call this to bypass the trailing debounce.
 */
export function __flushPendingWriteForTest(): void {
  if (pendingTimer === null) return;
  clearTimeout(pendingTimer);
  pendingTimer = null;
  const push = pendingPush;
  pendingPush = false;
  if (active) {
    writeUrl(active.surfaceId, active.schema, push);
  }
}

/** Look up the registered store-adapter for a `storeId`. Test-friendly export. */
export function __getStoreForTest(storeId: string) {
  return getStore(storeId);
}
