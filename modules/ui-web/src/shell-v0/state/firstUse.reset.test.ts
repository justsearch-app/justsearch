// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { beforeEach, expect, it, vi } from 'vitest';
const native = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: native.invoke }));
import { gateFactoryReset } from '../../boot/factoryReset.js';
import { __resetUserStateForTest, __resetInMemoryStateForTest, getWalkthroughProgress } from './UserStateDocument.js';
const files = import.meta.glob('./firstUse.ts', { eager: true });
const api = Object.values(files)[0] as typeof import('./firstUse.js') | undefined;
beforeEach(() => { document.body.replaceChildren(); __resetUserStateForTest(); native.invoke.mockReset(); });
it('completed factory-reset storage cleanup clears Hide and Help marks before a fresh launch', async () => {
  expect(api, 'Getting started must participate in the existing reset format').toBeDefined();
  api?.reopenGettingStarted(); api?.hideGettingStarted();
  native.invoke.mockImplementation(async command => command === 'factory_reset_status' ? { phase: 'awaiting_storage', remaining: [] } : undefined);
  const root = document.createElement('div'); document.body.append(root);
  expect(await gateFactoryReset(root)).toBe(false);
  expect(native.invoke).toHaveBeenCalledWith('complete_factory_reset_storage');
  __resetInMemoryStateForTest();
  expect(getWalkthroughProgress('core.getting-started')).toBeUndefined();
  expect(api?.projectFirstUse({ roots: 0, searchable: 0, waiting: 0, reading: false, aiActive: false })).toMatchObject({ visible: false, done: [false, false, false] });
});
