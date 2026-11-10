// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Preservation: the shell's single hidden dispatcher still owns plugin completion events.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import './WalkthroughCard.js';
import { registerWalkthrough, __resetWalkthroughsForTest, type WalkthroughStep } from '../commands/WalkthroughRegistry.js';
import { registerCommand, invokeCommand, __resetForTest as resetCommands } from '../commands/CommandRegistry.js';
import { __resetUserStateForTest, getWalkthroughProgress, mutateDocument, __resetInMemoryStateForTest } from '../state/UserStateDocument.js';
import { __resetSessionRegistryForTest, getSessionPluginRegistry } from '../plugin-api/sessionRegistry.js';
import { makePluginProvenance } from '../primitives/provenance.js';
import { PLUGIN_CONTRACT_VERSION } from '../plugin-api/plugin-types.js';
const provenance = makePluginProvenance('sample', '1.0.0', 'TRUSTED_PLUGIN');
beforeEach(() => { __resetUserStateForTest(); __resetWalkthroughsForTest(); resetCommands(); __resetSessionRegistryForTest(); });
afterEach(() => document.body.replaceChildren());
describe('plugin walkthrough triggers after the core welcome retirement', () => {
  for (const event of ['onCommand:sample.command', 'onSettingChanged:activeThemeId', 'extensionInstalled:other'] as const) {
    it(`preserves ${event} completion and saved progress across reload`, async () => {
      registerWalkthrough({ id: 'sample.tour', title: 'Sample', source: 'plugin', provenance, priority: 0,
        steps: [{ id: 'one', title: 'One', body: 'First', completionEvent: event as WalkthroughStep['completionEvent'] }] });
      const driver = document.createElement('jf-walkthrough-card') as HTMLElement & { updateComplete: Promise<unknown> };
      document.body.append(driver); await driver.updateComplete;
      if (event.startsWith('onCommand:')) {
        registerCommand({ id: 'sample.command', label: 'Sample command', source: 'plugin', provenance, handler: () => {} });
        invokeCommand('sample.command');
      } else if (event.startsWith('onSettingChanged:')) mutateDocument(d => ({ ...d, activeThemeId: 'light' }));
      if (event.startsWith('extensionInstalled:')) {
        getSessionPluginRegistry().install({ id: 'other', version: '1.0.0', displayName: 'Other', capabilities: {},
          register: () => {}, contractVersion: PLUGIN_CONTRACT_VERSION, tagNamespace: 'other' });
      }
      expect(getWalkthroughProgress('sample.tour')).toEqual({ activeStepIndex: 1, completedStepIds: ['one'], dismissed: false });
      driver.remove(); __resetInMemoryStateForTest();
      expect(getWalkthroughProgress('sample.tour')).toEqual({ activeStepIndex: 1, completedStepIds: ['one'], dismissed: false });
    });
  }
});
