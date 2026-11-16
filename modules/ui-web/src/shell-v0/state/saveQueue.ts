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
  private lastOutcome: SendOutcome | null = null;

  constructor(private readonly send: (snapshot: S) => Promise<SendOutcome>, private readonly changed: () => void) {}

  /** Snapshots not saved yet, the head first. */
  get snapshots(): readonly S[] { return this.items; }
  get sending(): boolean { return this.active; }
  /** The head was sent and its answer was lost. */
  get uncertain(): boolean { return this.lastOutcome === 'uncertain' && this.items.length > 0; }

  push(snapshot: S): Promise<void> {
    this.items.push(snapshot);
    return this.resume();
  }

  /** Sends the head (again) and drains; while sending, the running drain picks up what was pushed. */
  resume(): Promise<void> {
    this.running ??= this.drain().finally(() => { this.running = null; this.changed(); });
    return this.running;
  }

  /** Drops every snapshot not sent yet (the person chose the saved state instead). */
  clear(): S[] {
    this.lastOutcome = null;
    return this.items.splice(0);
  }

  private async drain(): Promise<void> {
    this.active = true;
    try {
      while (this.items.length) {
        const head = this.items[0]!;
        this.changed();
        this.lastOutcome = await this.send(head);
        if (this.lastOutcome === 'uncertain' || this.lastOutcome === 'stopped') return;
        if (this.items[0] === head) this.items.shift();
      }
    } finally { this.active = false; }
  }
}
