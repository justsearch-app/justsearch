// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/** DE5/C10 — every change in the window's focus is reported, and stopping ends the watch. */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  isTauriRuntime: vi.fn(() => false),
  onFocusChanged: vi.fn(),
  unlisten: vi.fn(),
}));
vi.mock('./tauriRuntime', () => ({ isTauriRuntime: mocks.isTauriRuntime }));
vi.mock('@tauri-apps/api/window', () => ({ getCurrentWindow: () => ({ onFocusChanged: mocks.onFocusChanged }) }));
import { onWindowFocusChange } from './windowFocusChange.js';

const settled = () => vi.waitFor(() => expect(mocks.onFocusChanged).toHaveBeenCalled()).then(() => new Promise(resolve => setTimeout(resolve, 0)));

beforeEach(() => {
  mocks.isTauriRuntime.mockReturnValue(false);
  mocks.unlisten.mockReset();
  mocks.onFocusChanged.mockReset().mockResolvedValue(mocks.unlisten);
});

describe('DE5 onWindowFocusChange', () => {
  it.each([['focus'], ['blur'], ['visibilitychange']])('reports %s, until stopped', (event) => {
    const target = event === 'visibilitychange' ? document : window;
    const changed = vi.fn();
    const stop = onWindowFocusChange(changed);
    target.dispatchEvent(new Event(event));
    expect(changed).toHaveBeenCalledTimes(1);
    stop();
    target.dispatchEvent(new Event(event));
    expect(changed).toHaveBeenCalledTimes(1);
  });
  it('inside the shell also reports the native signal, and stopping removes it', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    const changed = vi.fn();
    const stop = onWindowFocusChange(changed);
    await settled();
    mocks.onFocusChanged.mock.calls[0]![0]({ payload: true });
    expect(changed).toHaveBeenCalledTimes(1);
    stop();
    expect(mocks.unlisten).toHaveBeenCalledTimes(1);
  });
  it('C10 stopping before the native subscription lands still removes it', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    onWindowFocusChange(vi.fn())();
    await settled();
    expect(mocks.unlisten).toHaveBeenCalledTimes(1);
  });
  it('outside the shell asks for no native signal', () => {
    onWindowFocusChange(vi.fn())();
    expect(mocks.onFocusChanged).not.toHaveBeenCalled();
  });
});
