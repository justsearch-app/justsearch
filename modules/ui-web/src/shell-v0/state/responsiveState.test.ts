// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';

afterEach(() => { vi.unstubAllGlobals(); vi.resetModules(); });
it('SA15 Shell viewport authority follows matchMedia changes and removes departed subscribers', async () => {
  vi.resetModules();
  let wide = false;
  const listeners: Array<() => void> = [];
  const media = { get matches() { return wide; }, addEventListener: (_: string, listener: () => void) => listeners.push(listener) };
  const matchMedia = vi.fn(() => media);
  vi.stubGlobal('matchMedia', matchMedia);
  const { isWideLayout, subscribeWide } = await import('./responsiveState.js');
  const first = vi.fn(); const second = vi.fn();
  const stopFirst = subscribeWide(first); const stopSecond = subscribeWide(second);
  expect(isWideLayout()).toBe(false);
  expect(first.mock.calls).toEqual([[false]]); expect(second.mock.calls).toEqual([[false]]);
  expect(matchMedia).toHaveBeenCalledWith('(min-width: 64rem)');
  expect(listeners).toHaveLength(1);
  wide = true; listeners[0]!();
  expect(first.mock.calls).toEqual([[false], [true]]); expect(second.mock.calls).toEqual([[false], [true]]);
  stopFirst(); wide = false; listeners[0]!();
  expect(first.mock.calls).toEqual([[false], [true]]); expect(second.mock.calls).toEqual([[false], [true], [false]]);
  stopSecond(); wide = true; listeners[0]!();
  expect(second).toHaveBeenCalledTimes(3);
});
it('SA15 Shell viewport authority keeps the existing wide fallback without matchMedia', async () => {
  vi.resetModules(); vi.stubGlobal('matchMedia', undefined);
  const { isWideLayout, subscribeWide } = await import('./responsiveState.js');
  expect(isWideLayout()).toBe(true);
  const listener = vi.fn(); const stop = subscribeWide(listener);
  expect(listener.mock.calls).toEqual([[true]]); stop();
});
