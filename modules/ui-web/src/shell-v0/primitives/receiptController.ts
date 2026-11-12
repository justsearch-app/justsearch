// SPDX-License-Identifier: Apache-2.0
/**
 * ReceiptController — tempdoc 613 §6: the ONE authority for the in-control RECEIPT surface (#1).
 *
 * A RECEIPT is the acknowledgement of a direct, synchronous action on a specific control ("Copied",
 * "Exported N entries") — `locality: 'at-control'` in the routing model. The problem statement is
 * explicit that these are LOCAL receipts, "not a request for a global copy toast … short receipts
 * should not become permanent history." Routing them to the window toast channel is the mis-route
 * §6's `f` (`routePushSurface`) forbids.
 *
 * Before this, the only working receipt was bespoke (SearchSurface's `copyFlash` state). This is that
 * pattern lifted into one reusable ReactiveController so every receipt site shares one authority — a
 * transient in-element "✓ <message>" flash with a single timing/auto-clear policy. The HOST renders
 * the flash (a button swaps its label); the controller owns the state + timer.
 *
 * This is NOT a new surface kind (§7) — it gives the EXISTING receipt surface (#1 in 613 §1's
 * inventory, "the in-button flash; 602 R7") the single authority every other surface already has.
 */
import type { ReactiveController, ReactiveControllerHost } from 'lit';

/** Default flash lifetime — matches the prior bespoke `copyFlash` (~1.5s). */
export const RECEIPT_FLASH_MS = 1500;

/** The active receipt: a message and an optional `key` (so a host with several receipt targets — e.g.
 *  three copy buttons — flashes only the one that was triggered). */
export interface ActiveReceipt {
  readonly message: string;
  readonly key?: string;
}

export class ReceiptController implements ReactiveController {
  private readonly host: ReactiveControllerHost;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private _active: ActiveReceipt | null = null;

  constructor(host: ReactiveControllerHost) {
    this.host = host;
    host.addController(this);
  }

  /** The live receipt, or null. The host reads this to render the in-element confirmation. */
  get active(): ActiveReceipt | null {
    return this._active;
  }

  /** True when `key`'s receipt is currently flashing (convenience for multi-target hosts). */
  isFlashing(key: string): boolean {
    return this._active?.key === key;
  }

  /** Show a transient in-control confirmation; auto-clears after `durationMs`. */
  flash(message: string, opts: { key?: string; durationMs?: number } = {}): void {
    this._active = opts.key !== undefined ? { message, key: opts.key } : { message };
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this._active = null;
      this.timer = null;
      this.host.requestUpdate();
    }, opts.durationMs ?? RECEIPT_FLASH_MS);
    this.host.requestUpdate();
  }

  /** Withdraw the live receipt: a later attempt at the same action must not stand beside an earlier success. */
  clear(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    if (this._active === null) return;
    this._active = null;
    this.host.requestUpdate();
  }

  hostDisconnected(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}
