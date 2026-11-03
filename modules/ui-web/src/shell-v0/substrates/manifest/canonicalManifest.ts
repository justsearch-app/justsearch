// SPDX-License-Identifier: Apache-2.0
/** First-party welcome walkthrough, installed at boot through ContributionManifest. */

import { installContributionManifest } from './index.js';
import { makeCoreProvenance } from '../../primitives/provenance.js';

/**
 * "Welcome to JustSearch" walkthrough (moved here from CorePlugin as part of
 * the §4.3(d) migration). Four steps cover the primitives a new user needs to
 * discover: command palette, search, library, theme. The last step
 * auto-advances on `onSettingChanged:activeThemeId`.
 */
const CORE_WELCOME_WALKTHROUGH = {
  id: 'welcome',
  title: 'Welcome to JustSearch',
  description: 'A quick tour of the basics.',
  priority: 0,
  steps: [
    {
      id: 'open-palette',
      title: 'Open the command palette',
      body:
        'Press Ctrl+K (or Cmd+K on macOS) to open the command palette. ' +
        'Everything you can do in JustSearch is reachable from here.',
    },
    {
      id: 'run-a-search',
      title: 'Run your first search',
      body:
        'Switch to the Search surface from the left rail and try a query — ' +
        'JustSearch indexes whatever you point it at, so any keyword from ' +
        'your library should produce hits.',
    },
    {
      id: 'open-library',
      title: 'Open the Library',
      body:
        'The Library surface (left rail) is where you add watched roots ' +
        'and manage indexing. Add a folder to see how the indexer reacts.',
    },
    {
      id: 'switch-theme',
      title: 'Try a different theme',
      body:
        'Settings → Theme picks a different color palette. Themes are ' +
        'plugin contributions, so this is also how third-party themes ' +
        'show up once you install them.',
      completionEvent: 'onSettingChanged:activeThemeId',
    },
  ],
} as const;

let walkthroughInstalled = false;

/** Install the core welcome-walkthrough manifest. Idempotent. */
export async function installCoreWalkthroughManifest(): Promise<void> {
  if (walkthroughInstalled) return;
  walkthroughInstalled = true;
  await installContributionManifest({
    id: 'core.core-welcome',
    version: '1.0.0',
    provenance: makeCoreProvenance(),
    contributes: {
      walkthroughs: [CORE_WELCOME_WALKTHROUGH],
    },
  });
}

/** Test-only reset for the walkthrough manifest installer. */
export function __resetCoreWalkthroughManifestInstalledForTest(): void {
  walkthroughInstalled = false;
}
