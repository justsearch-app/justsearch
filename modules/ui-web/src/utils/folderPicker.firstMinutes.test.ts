// SPDX-License-Identifier: Apache-2.0
import { beforeEach, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ native: true, open: vi.fn() }));
vi.mock('./tauriRuntime', () => ({ isTauriRuntime: () => mocks.native }));
vi.mock('@tauri-apps/plugin-dialog', () => ({ open: mocks.open }));
import { pickFolder } from './folderPicker.js';
beforeEach(() => { mocks.native = true; mocks.open.mockReset(); });
it('S4-4 native success and cancel remain distinct from failure', async () => {
  mocks.open.mockResolvedValueOnce('/notes').mockResolvedValueOnce(null).mockRejectedValueOnce(new Error('dialog refused'));
  expect(await pickFolder()).toBe('/notes'); expect(await pickFolder()).toBeNull();
  expect(await pickFolder()).toEqual({ error: 'dialog refused' });
});
it('S4-8 browser picker returns no selection without calling the native plugin', async () => {
  mocks.native = false; expect(await pickFolder()).toBeNull(); expect(mocks.open).not.toHaveBeenCalled();
});
