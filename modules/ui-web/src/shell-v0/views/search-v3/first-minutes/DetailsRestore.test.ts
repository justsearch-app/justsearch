// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it } from 'vitest';
import { Sv3DetailsDomain } from '../sv3-details-domain.js';
import { SV3_SESSIONS_EMPTY, submitInSession } from '../sv3-sessions.js';
import { getDetailsMemory, rememberDetails } from '../../../state/detailsMemory.js';
afterEach(() => localStorage.clear());
it.each(['/saved.md', ''])('S5-8 restored Context outside Ask falls back without changing saved memory: %s', path => {
  const sessions = submitInSession(SV3_SESSIONS_EMPTY, 'model question', 1, 'model', 'restore-model');
  const pane = { paneTab: null as string | null, paneDocPath: null as string | null, paneSource: null, paneCitation: null, paneCitationHeader: null };
  const memory = { open: true, tab: 'context', docPath: path, turnId: '', sourceIndex: -1 };
  rememberDetails('restore-model', memory);
  const domain = new Sv3DetailsDomain({ sessions: () => sessions, pane: () => pane,
    sharedInspector: () => false,
    openPane: detail => { pane.paneTab = 'document'; pane.paneDocPath = detail.docPath; domain.remember(true); },
    preparePaneBox() {}, dismissPane() {}, owns: () => false, paneElement: () => null,
    focusComposer() {}, whenUpdated: async () => {}, requestUpdate() {},
  });
  domain.syncConversation();
  expect(pane.paneTab).toBe(path ? 'document' : null);
  expect(getDetailsMemory('restore-model')).toEqual(memory);
  expect(domain.tabs().some(tab => tab.id === 'context')).toBe(false);
});
