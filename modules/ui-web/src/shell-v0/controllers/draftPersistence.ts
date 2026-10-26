// SPDX-License-Identifier: Apache-2.0
/**
 * DraftPersistence — tempdoc 609 §R (T2.1 / E1): reload-durable drafts.
 *
 * Instance-retention keeps a draft in renderer memory across NAVIGATION, but a full page reload mounts a
 * fresh instance and the draft is gone. This controller adds the next durability tier: flush a surface's
 * draft to localStorage at the reliable save points (`visibilitychange`→hidden and `pagehide` — never
 * `unload`, per the Page Lifecycle guidance) and rehydrate it on a fresh mount.
 *
 * Retention-aware rehydration: restore only on an instance's FIRST connect. A retained surface re-connects
 * with its in-memory draft already current, so re-reading storage there would clobber newer edits with a
 * stale snapshot; a post-reload instance is brand new (first connect) and correctly restores. This keeps
 * the in-memory draft authoritative within a session and storage authoritative only across reloads.
 *
 * Usage (in a surface that extends JfElement):
 *   new DraftPersistence(this, 'unified-chat.composer', () => this.inputDraft, (v) => { this.inputDraft = v; });
 */
import type { ReactiveController, ReactiveControllerHost } from 'lit';

const PREFIX = 'justsearch.draft.';

interface Provider {
  readonly key: string;
  readonly get: () => string;
  saved: string | null;
  readable: boolean;
}

const providers = new Set<Provider>();
const attachmentFlushes = new Set<() => void>();
let wired = false;

function storageKey(key: string): string {
  return PREFIX + key;
}

function readDraft(key: string, failed?: () => void): string | null {
  try {
    return localStorage.getItem(storageKey(key));
  } catch {
    failed?.();
    return null;
  }
}

function writeDraft(provider: Provider, value: string): void {
  // An unreadable existing slot may hold the only copy. This mount cannot replace it.
  if (!provider.readable) return;
  const next = value.trim() ? value : null;
  try {
    // An unchanged snapshot cannot overwrite another tab's newer durable draft. In particular,
    // a tab still waiting for cold ingress may have read empty before another tab transferred.
    if (next === provider.saved && readDraft(provider.key) !== provider.saved) return;
    if (next !== null) localStorage.setItem(storageKey(provider.key), next);
    else localStorage.removeItem(storageKey(provider.key));
    provider.saved = next;
  } catch {
    // quota exceeded / storage unavailable — non-fatal (draft simply won't survive reload).
  }
}

function flushAll(): void {
  for (const flush of attachmentFlushes) flush();
  for (const p of providers) writeDraft(p, p.get());
}

function ensureWired(): void {
  if (wired || typeof document === 'undefined') return;
  wired = true;
  // The recommended save points: `hidden` is the last reliably-delivered moment; `pagehide` is the
  // bfcache-safe unload signal. `unload` is intentionally NOT used.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') flushAll();
  });
  window.addEventListener('pagehide', flushAll);
}

export class DraftPersistence implements ReactiveController {
  private readonly host: ReactiveControllerHost;
  private readonly provider: Provider;
  private readonly set: (v: string) => void;
  private rehydrated = false;
  private restorationFailed = false;
  /** An unreadable raw draft is not evidence that the durable slot was empty. */
  get restoreUnavailable(): boolean { return this.restorationFailed; }
  private attachment: {
    key: string; expected: string | null; safe: boolean;
    validate: (value: unknown) => boolean; get: () => unknown; failed: () => void;
  } | null = null;
  private readonly flushAttachment = (): void => {
    if (this.attachment !== null) this.saveSessionAttachment(this.attachment.get());
  };

  constructor(
    host: ReactiveControllerHost,
    key: string,
    get: () => string,
    set: (v: string) => void,
  ) {
    this.host = host;
    this.provider = { key, get, saved: null, readable: true };
    this.set = set;
    host.addController(this);
  }

  hostConnected(): void {
    ensureWired();
    providers.add(this.provider);
    attachmentFlushes.add(this.flushAttachment);
    if (this.rehydrated) return; // reconnect of a retained instance — keep the live in-memory draft
    this.rehydrated = true;
    const saved = readDraft(this.provider.key, () => { this.restorationFailed = true; this.provider.readable = false; });
    this.provider.saved = saved;
    if (saved !== null) {
      this.set(saved);
      void this.host.updateComplete;
    }
  }

  /** Opt-in, fixed-owner recovery for a surviving tab session. The raw local draft stays a string. */
  openSessionAttachment(
    owner: string,
    validate: (value: unknown) => boolean,
    get: () => unknown,
    failed: () => void,
  ): unknown {
    if (this.attachment !== null) return null;
    const key = `justsearch.draft.attachment.v1.${owner}`;
    const attachment = { key, expected: null as string | null, safe: true, validate, get, failed };
    this.attachment = attachment;
    try {
      attachment.expected = sessionStorage.getItem(key);
      if (attachment.expected === null) return null;
      if (new TextEncoder().encode(attachment.expected).length > 262144) throw new Error('oversize');
      const data: unknown = JSON.parse(attachment.expected);
      if (data === null || typeof data !== 'object' || Array.isArray(data)) throw new Error('invalid');
      const row = data as Record<string, unknown>;
      if (Object.keys(row).sort().join(',') !== 'incarnation,payload,version' || row.version !== 1
        || typeof row.incarnation !== 'string' || row.incarnation.length > 128 || !validate(row.payload)) throw new Error('invalid');
      return row.payload;
    } catch {
      attachment.safe = false;
      failed();
      return null;
    }
  }

  /** ACK before issuance/clear. Compare bytes rejects stale overlapping mounts; never delete on failure. */
  saveSessionAttachment(payload: unknown): boolean {
    const a = this.attachment;
    if (a === null || !a.safe) return false;
    try {
      if (!a.validate(payload)) throw new Error('invalid');
      // Each ACK advances the opaque incarnation, even when the inputs return to identical text.
      // Otherwise an overlapping stale writer could miss an intervening write (an ABA collision).
      const bytes = JSON.stringify({ version: 1, incarnation: crypto.randomUUID(), payload });
      if (new TextEncoder().encode(bytes).length > 262144 || sessionStorage.getItem(a.key) !== a.expected) throw new Error('stale or oversize');
      sessionStorage.setItem(a.key, bytes);
      if (sessionStorage.getItem(a.key) !== bytes) throw new Error('not acknowledged');
      a.expected = bytes;
      return true;
    } catch {
      a.failed();
      return false;
    }
  }

  /**
   * One-time cutover of an unscoped legacy draft. The destination and disposition are written
   * before removing the source. On quota/security failure the source remains recoverable.
   * Called only on a mount without an incoming task; an explicit arrival owns its composer.
   */
  adoptLegacy(key: string): void {
    const marker = storageKey(`${key}.migrated-to.${this.provider.key}`);
    try {
      // Ingress can settle after another tab saved the destination. Re-read before disposition
      // so this mount adopts that durable text rather than retaining its earlier empty snapshot.
      const saved = localStorage.getItem(storageKey(this.provider.key));
      if (!this.provider.get().trim() && saved?.trim()) {
        this.set(saved);
        this.provider.saved = saved;
        void this.host.updateComplete;
      }
      if (localStorage.getItem(marker) !== null) return;
      const legacy = localStorage.getItem(storageKey(key));
      if (!legacy?.trim()) {
        localStorage.setItem(marker, 'no-legacy-draft');
        return;
      }
      const current = this.provider.get() || readDraft(this.provider.key) || '';
      if (current.trim()) {
        localStorage.setItem(marker, 'kept-successor');
        return;
      }
      localStorage.setItem(storageKey(this.provider.key), legacy);
      localStorage.setItem(marker, 'transferred');
      this.set(legacy);
      this.provider.saved = legacy;
      void this.host.updateComplete;
      localStorage.removeItem(storageKey(key));
    } catch {
      this.restorationFailed = true;
      this.provider.readable = false;
      // Do not mark or remove the only durable copy when the transfer could not be saved.
    }
  }

  hostDisconnected(): void {
    // Flush on hide too (belt-and-suspenders beyond visibilitychange/pagehide), then stop contributing.
    this.flushAttachment();
    writeDraft(this.provider, this.provider.get());
    providers.delete(this.provider);
    attachmentFlushes.delete(this.flushAttachment);
  }
}

/** Test-only helpers. */
export function __draftStorageKey(key: string): string {
  return storageKey(key);
}
export function __resetDraftProvidersForTest(): void {
  providers.clear();
  attachmentFlushes.clear();
}
