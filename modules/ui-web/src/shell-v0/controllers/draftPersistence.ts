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
}

const providers = new Set<Provider>();
let wired = false;

function storageKey(key: string): string {
  return PREFIX + key;
}

function readDraft(key: string): string | null {
  try {
    return localStorage.getItem(storageKey(key));
  } catch {
    return null;
  }
}

function writeDraft(key: string, value: string): void {
  try {
    if (value && value.trim().length > 0) localStorage.setItem(storageKey(key), value);
    else localStorage.removeItem(storageKey(key));
  } catch {
    // quota exceeded / storage unavailable — non-fatal (draft simply won't survive reload).
  }
}

function flushAll(): void {
  for (const p of providers) writeDraft(p.key, p.get());
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

  constructor(
    host: ReactiveControllerHost,
    key: string,
    get: () => string,
    set: (v: string) => void,
  ) {
    this.host = host;
    this.provider = { key, get };
    this.set = set;
    host.addController(this);
  }

  hostConnected(): void {
    ensureWired();
    providers.add(this.provider);
    if (this.rehydrated) return; // reconnect of a retained instance — keep the live in-memory draft
    this.rehydrated = true;
    const saved = readDraft(this.provider.key);
    if (saved !== null) {
      this.set(saved);
      void this.host.updateComplete;
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
      void this.host.updateComplete;
      localStorage.removeItem(storageKey(key));
    } catch {
      // Do not mark or remove the only durable copy when the transfer could not be saved.
    }
  }

  hostDisconnected(): void {
    // Flush on hide too (belt-and-suspenders beyond visibilitychange/pagehide), then stop contributing.
    writeDraft(this.provider.key, this.provider.get());
    providers.delete(this.provider);
  }
}

/** Test-only helpers. */
export function __draftStorageKey(key: string): string {
  return storageKey(key);
}
export function __resetDraftProvidersForTest(): void {
  providers.clear();
}
