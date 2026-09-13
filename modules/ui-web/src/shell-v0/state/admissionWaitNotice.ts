// SPDX-License-Identifier: Apache-2.0
import { emitEphemeralToast } from '../components/advisory/ephemeralToast.js';

/** Capacity refusal is a waiting state, surfaced through the existing informational message channel. */
export function reportAdmissionWait(upgrade: boolean, delayMs: number): void {
  emitEphemeralToast({
    classId: 'core.engine.wait',
    message: upgrade
      ? 'JustSearch is preparing an update. Waiting to reconnect.'
      : 'JustSearch is busy. Waiting to try again.',
    durationMs: Math.max(5_000, delayMs),
  });
}
