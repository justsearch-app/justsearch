// SPDX-License-Identifier: Apache-2.0

/**
 * What one send of the head snapshot established: `saved` and `dropped` (refused, nothing saved) move
 * on to the next snapshot; `uncertain` (the answer was lost) and `stopped` (waiting for a choice or a
 * read) keep the head and stop until the next save-triggering event or Try again.
 */
export type SendOutcome = 'saved' | 'dropped' | 'uncertain' | 'stopped';

/**
 * R11 — one editor's saves, in order. Every save-triggering event appends an immutable snapshot of
 * exactly what is to be saved; one snapshot is sent at a time. A write whose outcome is unknown stays at
 * the head and stops the queue until it is sent again and settled; only then do later snapshots go.
 * The queue belongs to the editor instance, so it outlives leaving the page and coming back.
 */
export class SaveQueue<S> {
  private readonly items: S[] = [];
  private running: Promise<void> | null = null;
  private active = false;
  /** The running drain stops after the head (R24); any later save-triggering event lifts this. */
  private headOnly = false;
  private lastOutcome: SendOutcome | null = null;
  private changes = 0;

  constructor(private readonly send: (snapshot: S) => Promise<SendOutcome>, private readonly changed: () => void) {}

  /** Snapshots not saved yet, the head first. */
  get snapshots(): readonly S[] { return this.items; }
  get sending(): boolean { return this.active; }
  /** The head was sent and its answer was lost. */
  get uncertain(): boolean { return this.lastOutcome === 'uncertain' && this.items.length > 0; }
  /** Moves on every snapshot captured, settled or dropped: a read begun before can tell it is stale (R17). */
  get version(): number { return this.changes; }

  push(snapshot: S): Promise<void> {
    this.items.push(snapshot);
    this.changes++;
    this.changed();
    return this.resume();
  }

  /**
   * R24 — snapshots kept across a page reload, the head first. Nothing is sent: the editor settles an
   * uncertain head with {@link settleHead}; the rest wait for the next save-triggering event.
   */
  restore(snapshots: readonly S[], uncertain: boolean): void {
    this.items.splice(0, this.items.length, ...snapshots);
    this.lastOutcome = uncertain ? 'uncertain' : 'stopped';
    this.changes++;
    this.changed();
  }

  /** Sends the head (again) alone; later snapshots wait for the next save-triggering event. */
  settleHead(): Promise<void> {
    if (!this.running) this.headOnly = true;
    return this.start();
  }

  /** Sends the head (again) and drains; while sending, the running drain picks up what was pushed. */
  resume(): Promise<void> {
    this.headOnly = false;
    return this.start();
  }

  private start(): Promise<void> {
    this.running ??= this.drain().finally(() => { this.running = null; this.headOnly = false; this.changed(); });
    return this.running;
  }

  /** Drops every snapshot not sent yet (the person chose the saved state instead). */
  clear(): S[] {
    this.lastOutcome = null;
    this.changes++;
    const dropped = this.items.splice(0);
    this.changed();
    return dropped;
  }

  private async drain(): Promise<void> {
    this.active = true;
    try {
      for (let sent = 0; this.items.length && !(this.headOnly && sent > 0); sent++) {
        const head = this.items[0]!;
        this.changed();
        this.lastOutcome = await this.send(head);
        if (this.lastOutcome === 'uncertain' || this.lastOutcome === 'stopped') return;
        if (this.items[0] === head) { this.items.shift(); this.changes++; }
      }
    } finally { this.active = false; }
  }
}

/**
 * R24 — an editor's unsettled saves in this window's sessionStorage, so the app's own reload (after an
 * Engine restart) does not drop them. `null` removes the record.
 *
 * R27 — the record is the whole queue or nothing: when the newer record cannot be written (storage full
 * or unavailable), the older one is removed, never left to be restored as if it were the queue. Returns
 * whether the record now kept is exactly `value`; on `false` the saves live in memory only and the
 * editor says a reload may lose them.
 */
export function keepUnsettled(key: string, value: unknown): boolean {
  try {
    if (value === null) sessionStorage.removeItem(key); else sessionStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    try { sessionStorage.removeItem(key); } catch { /* Storage unavailable: nothing older can be read back either. */ }
    return false;
  }
}

/** R27 — said next to an editor's save status while its unsettled saves could not be kept for a reload. */
export const MAY_BE_LOST = 'Your changes may be lost if this window reloads';

/** The record {@link keepUnsettled} left in this window, unvalidated; null when none or unreadable. */
export function readUnsettled(key: string): unknown {
  try {
    const raw = sessionStorage.getItem(key);
    return raw === null ? null : JSON.parse(raw) as unknown;
  } catch {
    return null;
  }
}
