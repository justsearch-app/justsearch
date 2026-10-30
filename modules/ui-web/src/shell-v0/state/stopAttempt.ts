// SPDX-License-Identifier: Apache-2.0

export type StopRequestOutcome = 'accepted' | 'refused' | 'unknown';
export type RunTerminalOutcome = 'cancelled' | 'completed' | 'failed';

export const STOP_RESPONSE_WAIT_MS = 10_000;

/** One run's latched intent. An accepted request never implies that its work stopped. */
export class StopAttempt {
  intent = false;
  pending = false;
  outcome: StopRequestOutcome | null = null;
  attempt = 0;

  latch(): void { this.intent = true; }

  /** Unknown and refused attempts can be retried explicitly; updates never retry them. */
  async deliver(request: (signal: AbortSignal) => Promise<{ readonly ok: boolean }>, waitMs?: number): Promise<void> {
    this.latch();
    if (this.pending || this.outcome === 'accepted') return;
    this.pending = true;
    this.outcome = null;
    this.attempt++;
    const abort = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      const response = request(abort.signal).then((response): StopRequestOutcome => response.ok ? 'accepted' : 'refused');
      this.outcome = await (waitMs === undefined ? response : Promise.race([
        response,
        new Promise<StopRequestOutcome>((resolve) => {
          timer = setTimeout(() => { resolve('unknown'); abort.abort(); }, waitMs);
        }),
      ]));
    } catch {
      this.outcome = 'unknown';
    } finally {
      clearTimeout(timer);
      this.pending = false;
    }
  }

  /** A caller can start a new attempt after losing observation of an accepted request. */
  retry(): void {
    if (!this.pending) this.outcome = null;
  }
}
