// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ isTauriRuntime: vi.fn(() => true), invoke: vi.fn(async () => undefined) }));
vi.mock('./tauriRuntime', () => ({ isTauriRuntime: mocks.isTauriRuntime }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
import { setTaskbarBadge } from './taskbarBadge.js';

const drawn: string[] = [];
const canvas = { width: 0, height: 0 };
const context = { canvas, fillStyle: '', font: '', textAlign: '', textBaseline: '', beginPath() {}, arc() {}, fill() {},
  fillText: (text: string) => drawn.push(text), getImageData: () => ({ data: new Uint8ClampedArray(32 * 32 * 4) }) };

beforeEach(() => {
  drawn.length = 0;
  sessionStorage.clear();
  mocks.isTauriRuntime.mockReturnValue(true);
  mocks.invoke.mockReset().mockResolvedValue(undefined);
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context as unknown as ReturnType<HTMLCanvasElement['getContext']>);
});
afterEach(() => { vi.restoreAllMocks(); });

describe('DE5 setTaskbarBadge', () => {
  it('sends the count with the overlay Windows shows instead of a badge', async () => {
    await setTaskbarBadge(3);
    expect(mocks.invoke).toHaveBeenCalledWith('set_needs_you_badge', { count: 3, overlay: expect.any(Array) });
    expect((mocks.invoke.mock.calls[0] as unknown[])[1]).toHaveProperty('overlay.length', 32 * 32 * 4);
    expect(drawn).toEqual(['3']);
  });
  it('more than nine reads 9+', async () => {
    await setTaskbarBadge(12);
    expect(drawn).toEqual(['9+']);
  });
  it('zero clears it, with nothing drawn', async () => {
    await setTaskbarBadge(2);
    mocks.invoke.mockClear(); drawn.length = 0;
    await setTaskbarBadge(0);
    expect(mocks.invoke).toHaveBeenCalledWith('set_needs_you_badge', { count: 0, overlay: null });
    expect(drawn).toEqual([]);
  });
  it('a start with nothing waiting makes no native call', async () => {
    await setTaskbarBadge(0);
    expect(mocks.invoke).not.toHaveBeenCalled();
  });
  it('an unchanged count is not sent again', async () => {
    await setTaskbarBadge(2); await setTaskbarBadge(2);
    expect(mocks.invoke).toHaveBeenCalledTimes(1);
  });
  it('a reload clears the count the page before it left on the taskbar', async () => {
    sessionStorage.setItem('justsearch.taskbarBadge', '2');
    await setTaskbarBadge(0);
    expect(mocks.invoke).toHaveBeenCalledWith('set_needs_you_badge', { count: 0, overlay: null });
  });
  it('without a canvas the count is still sent', async () => {
    vi.mocked(HTMLCanvasElement.prototype.getContext).mockReturnValue(null);
    await setTaskbarBadge(1);
    expect(mocks.invoke).toHaveBeenCalledWith('set_needs_you_badge', { count: 1, overlay: null });
  });
  it('does nothing in a plain browser', async () => {
    mocks.isTauriRuntime.mockReturnValue(false);
    await setTaskbarBadge(1);
    expect(mocks.invoke).not.toHaveBeenCalled();
  });
  it('a platform without a badge raises no error', async () => {
    mocks.invoke.mockRejectedValue(new Error('unsupported'));
    await expect(setTaskbarBadge(1)).resolves.toBeUndefined();
  });
});
