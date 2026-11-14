// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ open: vi.fn() }));
vi.mock('../../../utils/tauriRuntime.js', () => ({ isTauriRuntime: () => true }));
vi.mock('@tauri-apps/plugin-dialog', () => ({ open: mocks.open }));
import { createPlatformApi } from './platform.js';
import { pickFolder } from '../../../utils/folderPicker.js';
import type { PluginPlatform } from '../plugin-types.js';

it('plugins keep the path-or-null contract for selection, cancel and native failure', async () => {
  const platform = createPlatformApi('CORE');
  mocks.open.mockResolvedValueOnce('/notes').mockResolvedValueOnce(null).mockRejectedValueOnce(new Error('dialog unavailable'));
  expect(await platform.pickFolder()).toBe('/notes');
  expect(await platform.pickFolder()).toBeNull();
  expect(await platform.pickFolder()).toBeNull();
});
it('the plugin type remains Promise<string | null>', () => {
  type Same<A, B> = (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
  const unchanged: Same<ReturnType<PluginPlatform['pickFolder']>, Promise<string | null>> = true;
  expect(unchanged).toBe(true);
});
it('internal folder picking retains actionable failure details', async () => {
  mocks.open.mockRejectedValueOnce(new Error('dialog unavailable'));
  expect(await pickFolder()).toEqual({ error: 'dialog unavailable' });
});
