// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 629 (#10) — app-wide auto-lock idle timer.
 *
 * Locks the AUTHORED-store data key after a configurable idle period, so an unlocked window left
 * unattended re-gates itself (the "unlocked window is the real exposure" caveat the honesty copy in #12
 * names). It lives in the always-mounted Shell — NOT a surface — so it keeps running across navigation.
 *
 * The timeout (minutes; 0 = off) is read from `localStorage['enc-autolock-min']` on every tick, so the
 * Settings control's change takes effect within one tick without any cross-component plumbing. The lock
 * only fires when the store is actually `unlocked` (checked against the control endpoint), so it is a
 * no-op when encryption is off or already locked.
 */

const STORAGE_KEY = 'enc-autolock-min';
const TICK_MS = 30_000;
const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'pointermove', 'wheel', 'touchstart'] as const;

export interface AutoLockHandle {
  stop(): void;
}

export function readAutoLockMinutes(): number {
  const v = Number(localStorage.getItem(STORAGE_KEY) ?? '0');
  return Number.isFinite(v) && v > 0 ? v : 0;
}

export function writeAutoLockMinutes(min: number): void {
  localStorage.setItem(STORAGE_KEY, String(min > 0 ? min : 0));
}

type FetchFn = (path: string, init?: { method?: string }) => Promise<Response>;

/**
 * Start the idle watcher. `fetchFn` is the session-authenticated fetch (`host.data.fetch`); `now` is
 * injectable for tests. Returns a handle whose `stop()` removes the listeners + interval.
 */
export function startAutoLock(fetchFn: FetchFn, now: () => number = () => Date.now()): AutoLockHandle {
  let lastActivity = now();
  let locking = false;

  const onActivity = (): void => {
    lastActivity = now();
  };
  for (const e of ACTIVITY_EVENTS) window.addEventListener(e, onActivity, { passive: true });

  const tick = async (): Promise<void> => {
    const min = readAutoLockMinutes();
    if (min === 0 || locking) return;
    if (now() - lastActivity < min * 60_000) return;
    locking = true;
    try {
      const res = await fetchFn('/api/conversations/encryption');
      const state = ((await res.json()) as { state?: string }).state;
      if (state === 'unlocked') {
        await fetchFn('/api/conversations/encryption/auto-lock', { method: 'POST' });
        lastActivity = now(); // avoid re-firing immediately
      }
    } catch {
      /* transient — try again next tick */
    } finally {
      locking = false;
    }
  };

  const interval = window.setInterval(() => void tick(), TICK_MS);

  return {
    stop(): void {
      window.clearInterval(interval);
      for (const e of ACTIVITY_EVENTS) window.removeEventListener(e, onActivity);
    },
  };
}
