// @vitest-environment happy-dom

/**
 * Tempdoc 805 G.1 — the shell's restart announcement must drop the webview's cached token.
 * Without it the webview keeps the previous boot's token and every mutating call 401s forever
 * (R11-F2). 637 gave the event a reload consumer; this pins the binding consumer.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';

const mocks = vi.hoisted(() => ({
  isTauriRuntime: vi.fn(() => true),
  invoke: vi.fn(),
  listen: vi.fn(),
}));

vi.mock('../utils/tauriRuntime', () => ({ isTauriRuntime: mocks.isTauriRuntime }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
vi.mock('@tauri-apps/api/event', () => ({ listen: mocks.listen }));

beforeEach(() => {
  vi.resetModules();
  mocks.isTauriRuntime.mockReturnValue(true);
  mocks.invoke.mockReset();
  mocks.listen.mockReset();
});

describe('installBackendRestartBridge', () => {
  it('invalidates the cached session token when the shell announces a restart', async () => {
    let fire!: () => void;
    mocks.listen.mockImplementation((_name: string, handler: () => void) => {
      fire = handler;
      return Promise.resolve(() => {});
    });
    mocks.invoke.mockResolvedValueOnce({ origin: 'http://127.0.0.1:43210', token: 'tok-boot-1' }).mockResolvedValueOnce({ origin: 'http://127.0.0.1:43210', token: 'tok-boot-2' });

    const { resolveSessionTokenFromTauri, getSessionToken } = await import('./http.js');
    const { installBackendRestartBridge } = await import('./backendRestart.js');
    await installBackendRestartBridge();

    expect(mocks.listen).toHaveBeenCalledWith(
      'justsearch://backend-restart',
      expect.any(Function),
    );
    expect(await resolveSessionTokenFromTauri()).toBe('tok-boot-1');

    fire();

    expect(getSessionToken()).toBeNull();
    expect(await resolveSessionTokenFromTauri()).toBe('tok-boot-2');
  });

  it('runs the caller callback AFTER invalidation', async () => {
    let fire!: () => void;
    mocks.listen.mockImplementation((_name: string, handler: () => void) => {
      fire = handler;
      return Promise.resolve(() => {});
    });
    mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:43210', token: 'tok-boot-1' });

    const { resolveSessionTokenFromTauri, getSessionToken } = await import('./http.js');
    const { installBackendRestartBridge } = await import('./backendRestart.js');
    let tokenSeenByCallback: string | null = 'unset';
    await installBackendRestartBridge(() => {
      tokenSeenByCallback = getSessionToken();
    });
    await resolveSessionTokenFromTauri();

    fire();

    expect(tokenSeenByCallback).toBeNull();
  });

  it('no-ops outside Tauri (browser dev) without touching the event API', async () => {
    mocks.isTauriRuntime.mockReturnValue(false);

    const { installBackendRestartBridge } = await import('./backendRestart.js');
    const unsubscribe = await installBackendRestartBridge();

    expect(mocks.listen).not.toHaveBeenCalled();
    expect(() => unsubscribe()).not.toThrow();
  });
});
