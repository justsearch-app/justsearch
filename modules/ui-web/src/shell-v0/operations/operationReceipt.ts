// SPDX-License-Identifier: Apache-2.0
/**
 * Reading one operation's receipt (`GET /api/operation-history/{operationKey}`): the metadata the
 * Engine retains for an invocation — state, phase, unit counts, reason, result code — and nothing
 * richer. Shared by the inline failure reader on ledger rows and Activity's details.
 *
 * A watch follows one key: it reads again every few seconds while the invocation is accepted or
 * running, keeps the last answer it had when a read fails, and never moves back from a terminal
 * state (complete, failed, expired) to an earlier one: a read that started before the terminal one
 * and answers after it is a sample of the past. An answer from a stopped watch changes nothing.
 */
import { authorizedFetch } from '../api/authorizedFetch.js';
import { operationOutcomeViewSchema, type OperationOutcomeView } from '../../api/generated/schema-types/index.js';
import { parseWireContract } from '../../api/schemas.js';

/** An invocation key (a UUIDv7): the only kind of key the receipt endpoint answers for. */
export const OPERATION_KEY_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const POLL_MS = 4_000;
const TERMINAL = new Set<OperationOutcomeView['state']>(['complete', 'failed', 'expired']);

/** The invocation has not finished: accepted, or running. */
export function isPending(outcome: OperationOutcomeView | null): boolean {
  return outcome !== null && (outcome.state === 'accepted' || outcome.state === 'running');
}

export function isTerminalReceipt(state: OperationOutcomeView['state']): boolean {
  return TERMINAL.has(state);
}

/** One read. Throws when the request fails or the answer is not a receipt. */
export async function readOperationReceipt(apiBase: string, operationKey: string): Promise<OperationOutcomeView> {
  const response = await authorizedFetch(`${apiBase.replace(/\/$/, '')}/api/operation-history/${encodeURIComponent(operationKey)}`);
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return parseWireContract(operationOutcomeViewSchema, await response.json(), 'operation outcome');
}

export interface ReceiptState {
  /** The latest answer that is not older than a terminal one; null before the first. */
  readonly outcome: OperationOutcomeView | null;
  /** A read is outstanding. */
  readonly reading: boolean;
  /** The latest read failed (the last answer, if any, is kept). */
  readonly readFailed: boolean;
}

export class ReceiptWatch {
  private state: ReceiptState = { outcome: null, reading: false, readFailed: false };
  private serial = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private stopped = false;

  constructor(
    private readonly apiBase: string,
    readonly operationKey: string,
    private readonly onChange: (state: ReceiptState) => void,
  ) {}

  get current(): ReceiptState {
    return this.state;
  }

  /** Read now (also a retry after a failed read). Does nothing for a key the endpoint cannot answer. */
  read(): void {
    if (this.stopped || !OPERATION_KEY_PATTERN.test(this.operationKey)) return;
    clearTimeout(this.timer);
    const serial = ++this.serial;
    this.set({ ...this.state, reading: true });
    void readOperationReceipt(this.apiBase, this.operationKey).then(
      (outcome) => {
        if (this.stopped || serial !== this.serial) return;
        const held = this.state.outcome;
        const regresses = held !== null && isTerminalReceipt(held.state) && !isTerminalReceipt(outcome.state);
        this.set({ outcome: regresses ? held : outcome, reading: false, readFailed: false });
        if (isPending(this.state.outcome)) this.schedule();
      },
      () => {
        if (this.stopped || serial !== this.serial) return;
        this.set({ ...this.state, reading: false, readFailed: true });
        // A restart can briefly interrupt reads while a running invocation continues.
        if (isPending(this.state.outcome)) this.schedule();
      },
    );
  }

  stop(): void {
    this.stopped = true;
    this.serial += 1;
    clearTimeout(this.timer);
  }

  private schedule(): void {
    this.timer = setTimeout(() => this.read(), POLL_MS);
  }

  private set(state: ReceiptState): void {
    this.state = state;
    this.onChange(state);
  }
}
