// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

const { invoke } = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock('@tauri-apps/api/core', () => ({ invoke }));
import { gateFactoryReset } from './factoryReset.js';

beforeEach(() => {
  invoke.mockReset();
  localStorage.clear(); sessionStorage.clear();
});
afterEach(() => {
  document.body.replaceChildren();
  localStorage.clear(); sessionStorage.clear();
});

it('RA7 a newer reset file shows honest guidance and only Cancel, preserving window data', async () => {
  // The reason controls presentation even when the backend detail text changes.
  invoke.mockImplementation(async (command) => command === 'factory_reset_status'
    ? { phase: 'incomplete', reason: 'newer_version', remaining: [{ path: 'app-data/.justsearch-reset-requested', error: 'Newer reset file' }] }
    : undefined);
  localStorage.setItem('justsearch.draft.search-v3.composer', 'keep draft');
  sessionStorage.setItem('justsearch.search-v3.session', 'keep conversation');
  const root = document.createElement('div'); document.body.append(root);
  expect(await gateFactoryReset(root)).toBe(false);
  const shadow = root.querySelector('jf-factory-reset-recovery')!.shadowRoot!;
  expect(shadow.querySelector('h1')?.textContent).toBe('Update JustSearch or cancel reset');
  expect(shadow.querySelector('[role="status"]')?.textContent).toBe('Nothing was deleted. A newer version of JustSearch left a reset request this version cannot read. Update JustSearch to finish it or cancel reset to keep using this version.');
  expect(shadow.textContent).not.toContain('Reset incomplete');
  expect(shadow.textContent).not.toContain('could not be removed');
  expect(shadow.textContent).not.toContain('cannot be recovered');
  expect(shadow.querySelectorAll('button')).toHaveLength(1);
  const cancel = shadow.querySelector('button')!;
  expect(cancel.textContent?.trim()).toBe('Cancel reset');
  expect(cancel.disabled).toBe(false);
  cancel.click();
  await Promise.resolve();
  expect(invoke).toHaveBeenCalledWith('cancel_factory_reset');
  expect(invoke).not.toHaveBeenCalledWith('retry_factory_reset');
  expect(invoke).not.toHaveBeenCalledWith('complete_factory_reset_storage');
  expect(localStorage.getItem('justsearch.draft.search-v3.composer')).toBe('keep draft');
  expect(sessionStorage.getItem('justsearch.search-v3.session')).toBe('keep conversation');
});
