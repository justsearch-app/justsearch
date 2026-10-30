// SPDX-License-Identifier: Apache-2.0

/** Direction vocabulary. Ownership and delivery are separate from the work's terminal outcome. */
export type IntentOwnerScope = 'window' | 'run' | 'conversation';
export interface CapturedIntent { readonly owner: IntentOwnerScope; readonly target: string }
export type AttemptIdentity = number;
export type IntentPolicy = 'once-per-attempt';
export type DirectionAct = 'leave' | 'cancel-delivery' | 'stop-work';
export type StopRequestOutcome = 'accepted' | 'refused' | 'unknown';
export type RunTerminalOutcome = 'cancelled' | 'completed' | 'failed';

export const STOP_RESPONSE_WAIT_MS = 10_000;

/** One run's latched intent. An accepted request never implies that its work stopped. */
export class StopAttempt {
  intent = false;
  pending = false;
  outcome: StopRequestOutcome | null = null;
  attempt: AttemptIdentity = 0;

  latch(): void { this.intent = true; }

  /** Unknown and refused attempts can be retried explicitly; updates never retry them. */
  async deliver(request: (signal: AbortSignal) => Promise<{ readonly ok: boolean }>): Promise<void> {
    this.latch();
    if (this.pending || this.outcome === 'accepted') return;
    this.pending = true;
    this.outcome = null;
    this.attempt++;
    const abort = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      this.outcome = await Promise.race([
        request(abort.signal).then((response): StopRequestOutcome => response.ok ? 'accepted' : 'refused'),
        new Promise<StopRequestOutcome>((resolve) => {
          timer = setTimeout(() => { resolve('unknown'); abort.abort(); }, STOP_RESPONSE_WAIT_MS);
        }),
      ]);
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
