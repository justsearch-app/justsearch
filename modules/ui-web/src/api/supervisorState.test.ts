// @vitest-environment happy-dom

/**
 * Lane F stage B item B10 — the webview's end of design 7.1's "visible state" row.
 *
 * The event exists because the moment it matters is the moment nothing else can tell the webview
 * anything: an Engine that is `restarting` answers no HTTP and one that is `exhausted` never will.
 * What these cases pin is the part that is easy to get subtly wrong — that the bridge subscribes to
 * the SUPERVISOR event and not to the restart event beside it, and that a half-written record is
 * dropped rather than handed to a consumer that would then have to defend itself against it.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';

const mocks = vi.hoisted(() => ({
  isTauriRuntime: vi.fn(() => true),
  listen: vi.fn(),
}));

vi.mock('../utils/tauriRuntime', () => ({ isTauriRuntime: mocks.isTauriRuntime }));
vi.mock('@tauri-apps/api/event', () => ({ listen: mocks.listen }));

beforeEach(() => {
  vi.resetModules();
  mocks.isTauriRuntime.mockReturnValue(true);
  mocks.listen.mockReset();
});

const RUNNING = {
  schemaVersion: 1,
  kind: 'engine-supervisor-state.v1',
  supervisor: 'tauri',
  state: 'running',
  incarnation: 2,
  restartCount: 1,
  maxRestartAttempts: 3,
};

describe('installSupervisorStateBridge', () => {
  it('subscribes to the supervisor event, NOT to the restart event beside it', async () => {
    mocks.listen.mockResolvedValue(() => {});
    const { installSupervisorStateBridge } = await import('./supervisorState.js');
    await installSupervisorStateBridge(() => {});

    expect(mocks.listen).toHaveBeenCalledWith(
      'justsearch://supervisor-state',
      expect.any(Function),
    );
    // The two events answer different questions and fire at different rates; a bridge that took
    // over `backend-restart` would make every cooldown tick look like a stale binding.
    expect(mocks.listen).not.toHaveBeenCalledWith(
      'justsearch://backend-restart',
      expect.anything(),
    );
  });

  it('hands the record through unchanged', async () => {
    let fire!: (event: { payload: unknown }) => void;
    mocks.listen.mockImplementation((_name: string, handler: (event: { payload: unknown }) => void) => {
      fire = handler;
      return Promise.resolve(() => {});
    });

    const { installSupervisorStateBridge } = await import('./supervisorState.js');
    const seen: unknown[] = [];
    await installSupervisorStateBridge((state) => seen.push(state));

    fire({ payload: RUNNING });

    expect(seen).toEqual([RUNNING]);
  });

  it('drops a malformed payload instead of passing it on', async () => {
    let fire!: (event: { payload: unknown }) => void;
    mocks.listen.mockImplementation((_name: string, handler: (event: { payload: unknown }) => void) => {
      fire = handler;
      return Promise.resolve(() => {});
    });

    const { installSupervisorStateBridge } = await import('./supervisorState.js');
    const seen: unknown[] = [];
    await installSupervisorStateBridge((state) => seen.push(state));

    fire({ payload: undefined });
    fire({ payload: 'exhausted' });
    fire({ payload: { kind: 'engine-supervisor-state.v1' } });

    expect(seen).toEqual([]);
    // ... and the direction that keeps that non-vacuous: a well-formed record still arrives.
    fire({ payload: RUNNING });
    expect(seen).toHaveLength(1);
  });

  it('no-ops outside Tauri (browser dev) without touching the event API', async () => {
    mocks.isTauriRuntime.mockReturnValue(false);

    const { installSupervisorStateBridge } = await import('./supervisorState.js');
    const unsubscribe = await installSupervisorStateBridge(() => {});

    expect(mocks.listen).not.toHaveBeenCalled();
    expect(() => unsubscribe()).not.toThrow();
  });
});

describe('supervisor state predicates', () => {
  it('reads the terminal state, and only the terminal state, as terminal', async () => {
    const { isTerminal, isRecovering } = await import('./supervisorState.js');
    expect(isTerminal({ ...RUNNING, state: 'exhausted' } as never)).toBe(true);
    expect(isTerminal(RUNNING as never)).toBe(false);
    expect(isTerminal(null)).toBe(false);
    expect(isTerminal(undefined)).toBe(false);

    // `starting` counts as recovering on purpose: to a caller waiting for the API, "the supervisor
    // is spawning" and "the Engine is booting" are one outage, not two.
    expect(isRecovering({ ...RUNNING, state: 'restarting' } as never)).toBe(true);
    expect(isRecovering({ ...RUNNING, state: 'starting' } as never)).toBe(true);
    expect(isRecovering(RUNNING as never)).toBe(false);
    expect(isRecovering({ ...RUNNING, state: 'exhausted' } as never)).toBe(false);
  });
});
