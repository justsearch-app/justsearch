// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it } from 'vitest';
import { registerShellActions, getAction, __resetActionsForTest } from '../../substrates/actions/index.js';
import { searchPaletteEntries } from '../../commands/CommandPaletteProjection.js';

afterEach(() => __resetActionsForTest());
it('K3: the reading-rules palette action uses the same name as the Library editor', () => {
  registerShellActions({ navigate() {}, toggleInspector() {}, togglePalette() {}, focusComposer() {} });
  expect(getAction('core.action.shell.go-to-indexing-rules')?.title).toBe('Change reading rules');
  expect(searchPaletteEntries('reading rules').map(result => result.entry.label)).toContain('Change reading rules');
});
