// SPDX-License-Identifier: Apache-2.0
// SB12 retires the core welcome. Keep the same manifest lifecycle coverage on a contributed tour.
import { describe, it, expect, beforeEach } from 'vitest';
import { makeCoreProvenance } from '../../primitives/provenance.js';
import { installContributionManifest } from './index.js';
import { uninstallContributionManifest, __resetManifestRegistryForTest } from './index.js';
import {
  listWalkthroughs,
  __resetWalkthroughsForTest,
} from '../../commands/WalkthroughRegistry.js';

async function installTour(): Promise<void> {
  await installContributionManifest({ id: 'sample', version: '1.0.0', provenance: makeCoreProvenance(),
    contributes: { walkthroughs: [{ id: 'welcome', title: 'Plugin walkthrough', priority: 0,
      steps: ['a', 'b', 'c', 'd'].map(id => ({ id, title: id, body: id })) }] } });
}

beforeEach(async () => {
  await __resetManifestRegistryForTest();
  __resetWalkthroughsForTest();
});

describe('plugin walkthroughs through ContributionManifest (SB12)', () => {
  it('installing a contributed tour registers its steps with manifest provenance', async () => {
    expect(listWalkthroughs().find((w) => w.id === 'welcome')).toBeUndefined();

    await installTour();

    const welcome = listWalkthroughs().find((w) => w.id === 'welcome');
    expect(welcome).toBeDefined();
    expect(welcome?.title).toBe('Plugin walkthrough');
    expect(welcome?.steps.length).toBe(4);
    // Provenance + source are DERIVED from the one manifest, not hand-plumbed.
    expect(welcome?.provenance.tier).toBe('CORE');
    expect(welcome?.source).toBe('core');
  });

  it('rejects a duplicate manifest without duplicating the walkthrough', async () => {
    await installTour();
    await expect(installTour()).rejects.toThrow('ContributionManifest already installed: sample');
    expect(listWalkthroughs().filter((w) => w.id === 'welcome')).toHaveLength(1);
  });

  it('uninstall rolls the walkthrough back atomically', async () => {
    await installTour();
    expect(listWalkthroughs().some((w) => w.id === 'welcome')).toBe(true);

    const removed = await uninstallContributionManifest('sample');
    expect(removed).toBe(true);
    expect(listWalkthroughs().some((w) => w.id === 'welcome')).toBe(false);
  });
});
