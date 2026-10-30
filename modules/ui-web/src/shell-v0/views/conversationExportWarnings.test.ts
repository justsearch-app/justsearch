// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }));
vi.mock('@tauri-apps/plugin-autostart', () => ({ isEnabled: vi.fn(async () => false) }));
import './SettingsSurface.js';
import type { SettingsSurface } from './SettingsSurface.js';
import type { JfButton } from '../components/Button.js';
import type { ConfirmDialog } from '../components/ConfirmDialog.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __resetForTest, __seedForTest } from '../../api/registry/SurfaceCatalogClient.js';
import { confirmConversationTree } from '../components/chat/conversationDelete.js';

const settle = async () => { for (let i = 0; i < 5; i++) await new Promise(resolve => setTimeout(resolve, 0)); };
beforeEach(() => {
  __resetForTest();
  __seedForTest({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [] });
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}')));
});
afterEach(async () => {
  for (const dialog of document.querySelectorAll('jf-confirm-dialog')) dialog.dispatchEvent(new CustomEvent('cancel'));
  document.body.replaceChildren(); await settle(); vi.unstubAllGlobals(); vi.restoreAllMocks(); __resetForTest();
});
it('S5710 points from Delete all local data to conversation export before loss', async () => {
  const view = document.createElement('jf-settings-surface') as SettingsSurface;
  view.host_ = createMockHostApi({ platform: { capabilities: new Set(['native-notifications']) } });
  document.body.append(view); await settle(); view.activeCategory = 'data'; await view.updateComplete;
  const remove = view.shadowRoot!.querySelector<JfButton>('jf-button[label="Delete all local data"]')!;
  remove.onActivate?.(); await view.updateComplete;
  const text = view.shadowRoot!.textContent!;
  expect(text).toContain('choose Export conversation beside its other actions');
  expect(text).toContain('before resetting'); expect(text).toContain('Earlier backups and exports');
});
it('S5710 points from conversation delete to export and preserves cancel', async () => {
  const pending = confirmConversationTree([], [{ id: 'root', depth: 0, title: 'Root', firstUserMessage: '', titleSource: 'user',
    createdAt: 1, lastActiveAt: 1, shapeId: 'core.rag-ask', storeBacked: true, busy: false }]);
  await settle(); const dialog = document.querySelector<ConfirmDialog>('jf-confirm-dialog')!; await dialog.updateComplete;
  const text = dialog.shadowRoot!.textContent!;
  expect(text).toContain('cancel and open each conversation in Search'); expect(text).toContain('choose Export conversation');
  expect(text).toContain('Exports and backups you made remain');
  dialog.dispatchEvent(new CustomEvent('cancel')); expect(await pending).toBe(false);
});
