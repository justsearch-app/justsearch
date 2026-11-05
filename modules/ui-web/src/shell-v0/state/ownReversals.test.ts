// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * DE6 — a real reversal of the person's own action: offered in the pill and Earlier, run at most
 * once at a time and never after it succeeded, kept for a retry after an ordinary failure, and closed
 * (not offered again) when the world changed since. Ctrl+Z after the notice runs the newest open one.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EPHEMERAL_TOAST_EVENT, type EphemeralToastSpec } from '../components/advisory/ephemeralToast.js';
import { offerReversal, undoLatestReversal, latestReversalAt, listOpenReversals, ReversalClosedError,
  __resetOwnReversalsForTest } from './ownReversals.js';

const seen: EphemeralToastSpec[] = [];
const listen = (event: Event) => seen.push((event as CustomEvent<EphemeralToastSpec>).detail);
document.addEventListener(EPHEMERAL_TOAST_EVENT, listen);
afterEach(() => { seen.length = 0; __resetOwnReversalsForTest(); });
const settle = () => new Promise(resolve => setTimeout(resolve, 0));

describe('ownReversals (DE6)', () => {
  it('offers an Undo notice in the own-action class, marked as a reversal with Ctrl+Z', () => {
    offerReversal({ message: 'Skip rule added', detail: '*.log is skipped from now on.', reverse: async () => {}, reversed: 'Back' });
    expect(seen[0]).toMatchObject({ classId: 'core.own-action', message: 'Skip rule added', actionLabel: 'Undo', reversal: true });
    expect(seen[0]!.actionAvailable!()).toBe(true);
  });

  it('runs once even when pressed twice, says it worked, and is then no longer offered', async () => {
    let finish!: () => void;
    const reverse = vi.fn(() => new Promise<void>(resolve => { finish = resolve; }));
    offerReversal({ message: 'Skip rule added', reverse, reversed: 'Skip rules are back as they were' });
    seen[0]!.onAction!(); seen[0]!.onAction!();
    expect(undoLatestReversal()).toBe(true); // a running reversal holds Ctrl+Z without a second run
    finish(); await settle();
    expect(reverse).toHaveBeenCalledTimes(1);
    expect(seen.map(n => n.message)).toContain('Skip rules are back as they were');
    expect(seen[0]!.actionAvailable!()).toBe(false);
    expect(undoLatestReversal()).toBe(false);
    expect(listOpenReversals()).toEqual([]);
  });

  it('after an ordinary failure it says so in its own words and can be tried again', async () => {
    const reverse = vi.fn().mockRejectedValueOnce(new Error('Engine busy')).mockResolvedValue(undefined);
    offerReversal({ message: 'Removed Notes from your library', actionLabel: 'Add Notes again', reverse, reversed: 'Added Notes again' });
    seen[0]!.onAction!(); await settle();
    expect(seen.find(n => n.severity === 'error')?.message).toBe('Could not add Notes again. Engine busy');
    expect(seen[0]!.actionAvailable!()).toBe(true);
    seen[0]!.onAction!(); await settle();
    expect(reverse).toHaveBeenCalledTimes(2);
    expect(seen.map(n => n.message)).toContain('Added Notes again');
  });

  it('a reversal whose window closed says why and is not offered again', async () => {
    const reverse = vi.fn().mockRejectedValue(new ReversalClosedError('the settings were saved again since.'));
    offerReversal({ message: 'Skip rule added', reverse, reversed: 'Back' });
    seen[0]!.onAction!(); await settle();
    expect(seen.find(n => n.severity === 'warning')?.message).toBe("Can't undo: the settings were saved again since.");
    expect(seen[0]!.actionAvailable!()).toBe(false);
    seen[0]!.onAction!(); await settle();
    expect(reverse).toHaveBeenCalledTimes(1);
  });

  it('Ctrl+Z after the notice runs the newest open reversal first', async () => {
    const older = vi.fn(async () => {}); const newer = vi.fn(async () => {});
    offerReversal({ message: 'Skip rule removed', reverse: older, reversed: 'Back' });
    offerReversal({ message: 'Skip rule added', reverse: newer, reversed: 'Back' });
    expect(latestReversalAt()).not.toBeNull();
    expect(undoLatestReversal()).toBe(true); await settle();
    expect(newer).toHaveBeenCalledTimes(1); expect(older).not.toHaveBeenCalled();
    expect(undoLatestReversal()).toBe(true); await settle();
    expect(older).toHaveBeenCalledTimes(1);
    expect(latestReversalAt()).toBeNull();
  });
});
