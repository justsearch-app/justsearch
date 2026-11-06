// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const mocks = vi.hoisted(() => ({
  isTauriRuntime: vi.fn(() => false),
  isFocused: vi.fn(async () => true),
  isVisible: vi.fn(async () => true),
  isMinimized: vi.fn(async () => false),
  getCurrentWindow: vi.fn(() => ({ isFocused: mocks.isFocused, isVisible: mocks.isVisible, isMinimized: mocks.isMinimized })),
}));

vi.mock('./tauriRuntime', () => ({
  isTauriRuntime: mocks.isTauriRuntime,
}));

vi.mock('@tauri-apps/api/window', () => ({
  getCurrentWindow: mocks.getCurrentWindow,
}));

describe('isWindowFocused', () => {
  beforeEach(() => {
    mocks.isTauriRuntime.mockReturnValue(false);
    mocks.isFocused.mockReset().mockResolvedValue(true);
    mocks.isVisible.mockReset().mockResolvedValue(true);
    mocks.isMinimized.mockReset().mockResolvedValue(false);
    vi.resetModules();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('outside Tauri, defers to document.hasFocus()', async () => {
    mocks.isTauriRuntime.mockReturnValue(false);
    const hasFocusSpy = vi.spyOn(document, 'hasFocus').mockReturnValue(true);
    const { isWindowFocused } = await import('./windowFocus.js');

    await expect(isWindowFocused()).resolves.toBe(true);
    expect(hasFocusSpy).toHaveBeenCalled();
    expect(mocks.getCurrentWindow).not.toHaveBeenCalled();
    hasFocusSpy.mockRestore();
  });

  it('DE5 inside Tauri, a window hidden to the tray is in the background even if it kept its focus flag', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.isVisible.mockResolvedValue(false);
    const { isWindowFocused } = await import('./windowFocus.js');

    await expect(isWindowFocused()).resolves.toBe(false);
  });

  it('DE5 inside Tauri, a minimised window is in the background even if it kept its focus flag', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.isMinimized.mockResolvedValue(true);
    const { isWindowFocused } = await import('./windowFocus.js');

    await expect(isWindowFocused()).resolves.toBe(false);
  });

  it('outside Tauri, reflects document.hasFocus() === false', async () => {
    mocks.isTauriRuntime.mockReturnValue(false);
    const hasFocusSpy = vi.spyOn(document, 'hasFocus').mockReturnValue(false);
    const { isWindowFocused } = await import('./windowFocus.js');

    await expect(isWindowFocused()).resolves.toBe(false);
    hasFocusSpy.mockRestore();
  });

  it('inside Tauri, uses the native getCurrentWindow().isFocused() signal, not document.hasFocus()', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.isFocused.mockResolvedValue(false);
    const hasFocusSpy = vi.spyOn(document, 'hasFocus').mockReturnValue(true);
    const { isWindowFocused } = await import('./windowFocus.js');

    // Tauri's own signal (false) wins even though document.hasFocus() (true, the
    // confirmed-unreliable Page Visibility-adjacent signal) would say otherwise.
    await expect(isWindowFocused()).resolves.toBe(false);
    expect(mocks.getCurrentWindow).toHaveBeenCalled();
    hasFocusSpy.mockRestore();
  });

  it('inside Tauri with a focused window, returns true', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.isFocused.mockResolvedValue(true);
    const { isWindowFocused } = await import('./windowFocus.js');

    await expect(isWindowFocused()).resolves.toBe(true);
  });

  it('inside Tauri, fails safe (assumes focused) if the native API throws', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.isFocused.mockRejectedValue(new Error('plugin not available'));
    const { isWindowFocused } = await import('./windowFocus.js');

    await expect(isWindowFocused()).resolves.toBe(true);
  });
});
