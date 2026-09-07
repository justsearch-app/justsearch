// @vitest-environment happy-dom
import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  adoptTransitionPromises,
  surfaceTransitionsEnabled,
  startSurfaceTransition,
} from './viewTransition.js';
import { appLog } from '../../utils/logger.js';

type VTDoc = Document & { startViewTransition?: (cb: () => Promise<void> | void) => unknown };

afterEach(() => {
  delete (document as unknown as { startViewTransition?: unknown }).startViewTransition;
  vi.restoreAllMocks();
});

function stubReducedMotion(reduce: boolean): void {
  vi.spyOn(window, 'matchMedia').mockReturnValue({
    matches: reduce,
    media: '(prefers-reduced-motion: reduce)',
    addEventListener: () => {},
    removeEventListener: () => {},
  } as unknown as MediaQueryList);
}

describe('viewTransition (tempdoc 609 §R T1.1)', () => {
  it('surfaceTransitionsEnabled is false when the API is absent', () => {
    stubReducedMotion(false);
    expect(surfaceTransitionsEnabled()).toBe(false);
  });

  it('surfaceTransitionsEnabled is false when reduced-motion is preferred (even with the API)', () => {
    (document as VTDoc).startViewTransition = vi.fn();
    stubReducedMotion(true);
    expect(surfaceTransitionsEnabled()).toBe(false);
  });

  it('surfaceTransitionsEnabled is true when the API is present and motion is allowed', () => {
    (document as VTDoc).startViewTransition = vi.fn();
    stubReducedMotion(false);
    expect(surfaceTransitionsEnabled()).toBe(true);
  });

  it('startSurfaceTransition is a no-op (returns false, does not call the API) when disabled', () => {
    const spy = vi.fn();
    (document as VTDoc).startViewTransition = spy;
    stubReducedMotion(true); // disabled via reduced motion
    const host = { updateComplete: Promise.resolve(true) };
    expect(startSurfaceTransition(host)).toBe(false);
    expect(spy).not.toHaveBeenCalled();
  });

  it('startSurfaceTransition starts a transition and awaits the host update when enabled', async () => {
    let captured: (() => Promise<void> | void) | null = null;
    (document as unknown as { startViewTransition: unknown }).startViewTransition = vi.fn(
      (cb: () => Promise<void> | void) => {
        captured = cb;
        return {};
      },
    );
    stubReducedMotion(false);
    let resolved = false;
    const host = { updateComplete: Promise.resolve(true).then(() => void (resolved = true)) };

    expect(startSurfaceTransition(host)).toBe(true);
    expect(captured).not.toBeNull();
    // The callback awaits the host's updateComplete (the pending Lit flush) before resolving.
    await captured!();
    expect(resolved).toBe(true);
  });
});

/**
 * Tempdoc 859 (live console, 2026-08-25) — `SES_UNHANDLED_REJECTION: TimeoutError: Transition was
 * aborted because of timeout in DOM update`, on nearly every Search v3 load and again while a run sat
 * at the budget gate, plus the App-level "Unhandled promise rejection" `main.jsx` logs behind it.
 *
 * The cause was a DROPPED handle: `startViewTransition` hands back a `ViewTransition` whose `ready` /
 * `finished` / `updateCallbackDone` are live promises, and a rejected promise nobody adopted IS an
 * unhandled rejection. These pin both halves of the fix — a benign skip is silent, and a real failure
 * is still reported rather than swallowed.
 */
describe('viewTransition rejection adoption (tempdoc 859)', () => {
  /** A rejected promise the runtime would report unless something adopts it. */
  const rejecting = (reason: unknown): Promise<never> => Promise.reject(reason);
  const domError = (name: string, message: string): Error => {
    const e = new Error(message);
    e.name = name;
    return e;
  };

  /** Drain the microtask queue AND the macrotask turn where Node/V8 fires `unhandledRejection`. */
  const settle = async (): Promise<void> => {
    for (let i = 0; i < 3; i += 1) await Promise.resolve();
    await new Promise<void>((r) => setTimeout(r, 0));
  };

  /** Adopt one rejected face and report whether the app's diagnostic channel heard about it. */
  const reportsThrough = async (reason: unknown): Promise<boolean> => {
    const logged = vi.spyOn(appLog, 'error').mockImplementation(() => {});
    try {
      adoptTransitionPromises({ ready: rejecting(reason) });
      await settle();
      return logged.mock.calls.length > 0;
    } finally {
      logged.mockRestore();
    }
  };

  it('treats the two skip reasons as benign and everything else as reportable', async () => {
    // Asserted through the public adopter rather than against the module-private classifier: the
    // behaviour that matters is silent-vs-reported, and reaching in for the predicate would make
    // this test the only cross-module consumer of a symbol production does not need (the
    // export-for-testing shape the `dead-code` gate keeps out).
    expect(await reportsThrough(domError('TimeoutError', 'timeout in DOM update'))).toBe(false);
    expect(await reportsThrough(domError('AbortError', 'superseded'))).toBe(false);
    expect(await reportsThrough(domError('TypeError', 'x is not a function'))).toBe(true);
    // A blanket `.catch(() => {})` would swallow these too. They are what proves it is not one.
    expect(await reportsThrough('a string')).toBe(true);
    expect(await reportsThrough(null)).toBe(true);
    expect(await reportsThrough(undefined)).toBe(true);
  });

  it('an ABORTED transition produces no unhandled rejection and no diagnostic', async () => {
    const unhandled: unknown[] = [];
    const onUnhandled = (reason: unknown): void => {
      unhandled.push(reason);
    };
    process.on('unhandledRejection', onUnhandled);
    const logged = vi.spyOn(appLog, 'error').mockImplementation(() => {});
    try {
      // The exact live shape: the update callback outran the API's DOM-update budget, so `ready`
      // rejects with a TimeoutError while the other faces settle.
      adoptTransitionPromises({
        ready: rejecting(domError('TimeoutError', 'Transition was aborted because of timeout in DOM update')),
        finished: Promise.resolve(),
        updateCallbackDone: Promise.resolve(),
      });
      await settle();
      expect(unhandled, 'the skipped transition reached the global rejection channel').toEqual([]);
      expect(logged, 'a skipped transition is not an app fault').not.toHaveBeenCalled();
    } finally {
      process.off('unhandledRejection', onUnhandled);
      logged.mockRestore();
    }
  });

  it('a REAL transition failure is still reported through the app diagnostic channel', async () => {
    const unhandled: unknown[] = [];
    const onUnhandled = (reason: unknown): void => {
      unhandled.push(reason);
    };
    process.on('unhandledRejection', onUnhandled);
    const logged = vi.spyOn(appLog, 'error').mockImplementation(() => {});
    try {
      adoptTransitionPromises({
        ready: Promise.resolve(),
        finished: rejecting(domError('TypeError', 'the update callback threw')),
        updateCallbackDone: Promise.resolve(),
      });
      await settle();
      expect(unhandled).toEqual([]);
      expect(logged).toHaveBeenCalledWith('View transition failed', {
        reason: { name: 'TypeError', message: 'the update callback threw' },
      });
    } finally {
      process.off('unhandledRejection', onUnhandled);
      logged.mockRestore();
    }
  });

  it('tolerates the handle shapes a browser may hand back', () => {
    // Progressive enhancement: an older/partial implementation, or a stub returning nothing at all,
    // must not turn adoption itself into the fault.
    expect(() => adoptTransitionPromises(undefined)).not.toThrow();
    expect(() => adoptTransitionPromises(null)).not.toThrow();
    expect(() => adoptTransitionPromises({})).not.toThrow();
    expect(() => adoptTransitionPromises({ ready: 'not a promise' })).not.toThrow();
  });

  it('startSurfaceTransition adopts the handle it used to drop', async () => {
    const unhandled: unknown[] = [];
    const onUnhandled = (reason: unknown): void => {
      unhandled.push(reason);
    };
    process.on('unhandledRejection', onUnhandled);
    const logged = vi.spyOn(appLog, 'error').mockImplementation(() => {});
    try {
      (document as unknown as { startViewTransition: unknown }).startViewTransition = vi.fn(() => ({
        ready: rejecting(
          domError('TimeoutError', 'Transition was aborted because of timeout in DOM update'),
        ),
        finished: Promise.resolve(),
        updateCallbackDone: Promise.resolve(),
      }));
      stubReducedMotion(false);
      expect(startSurfaceTransition({ updateComplete: Promise.resolve(true) })).toBe(true);
      await settle();
      expect(unhandled, 'the shell surface swap still leaks its transition handle').toEqual([]);
      expect(logged).not.toHaveBeenCalled();
    } finally {
      process.off('unhandledRejection', onUnhandled);
      logged.mockRestore();
    }
  });
});
