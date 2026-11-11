// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
import { gateFactoryReset } from './factoryReset.js';

afterEach(() => { document.body.replaceChildren(); localStorage.clear(); sessionStorage.clear(); vi.restoreAllMocks(); });

it('SD6 full local-data deletion clears developer tools in every user-state profile before acknowledging cleanup', async () => {
  localStorage.setItem('justsearch.userState.v2', JSON.stringify({ version: 2, activeProfileId: 'default', profiles: {
    default: { showDeveloperTools: true }, other: { showDeveloperTools: false },
  } }));
  mocks.invoke.mockImplementation(async command => {
    if (command === 'factory_reset_status') return { phase: 'awaiting_storage', remaining: [] };
    if (command === 'complete_factory_reset_storage') expect(localStorage.getItem('justsearch.userState.v2')).toBeNull();
  });
  const root = document.createElement('main'); document.body.append(root);
  expect(await gateFactoryReset(root)).toBe(false);
  expect(localStorage.getItem('justsearch.userState.v2')).toBeNull();
  expect(mocks.invoke).toHaveBeenCalledWith('complete_factory_reset_storage');
});
