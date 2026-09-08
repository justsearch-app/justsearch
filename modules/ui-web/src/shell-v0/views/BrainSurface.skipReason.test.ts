// @vitest-environment happy-dom

/**
 * Tempdoc 941 — a skipped package says it was skipped, and says why.
 *
 * `PackageStatus.skipReason` (the planner's authored sentence) and `PackageStatus.skipCause` (its
 * typed `SkipCause` id) have been on the wire since 840 Phase 2 / 941 round 19, and the frontend
 * read neither. The Models list rendered every package identically — name plus a byte count — so a
 * component the user DECLINED, one the packager excluded, and one their machine cannot run all
 * looked exactly like a component that had been downloaded and installed.
 *
 * The assertions read the rendered list, not only the helper, because "the reason is somewhere in
 * the data" was already true before this change; what was missing was it reaching the screen.
 */

import { afterEach, describe, expect, it } from 'vitest';
import './BrainSurface';
import { skipExplanation } from './BrainSurface.js';
import type { InstallStatus } from '../state/aiStateStore.js';
import { __resetUiModeForTest, setUiMode } from '../state/uiModeState.js';

interface BrainHost extends HTMLElement {
  apiBase: string;
  settings: { mode?: 'simple' | 'advanced' };
  installStatus: InstallStatus | null;
  expanded: Record<string, boolean>;
  requestUpdate(): void;
  updateComplete: Promise<boolean>;
}

/** Mounts Detailed with the Models accordion open and returns its rendered text. */
async function modelsText(installStatus: InstallStatus): Promise<string> {
  const el = document.createElement('jf-brain-surface') as BrainHost;
  el.apiBase = '';
  setUiMode('advanced');
  el.settings = { mode: 'advanced' };
  document.body.appendChild(el);
  await el.updateComplete;
  el.installStatus = installStatus;
  el.expanded = { models: true };
  el.requestUpdate();
  await el.updateComplete;
  const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
  document.body.removeChild(el);
  return text;
}

const MIXED_PACKAGES: InstallStatus = {
  state: 'completed',
  phase: 'done',
  installedFully: true,
  packages: [
    {
      packageId: 'embedding',
      label: 'Embeddings',
      state: 'installed',
      bytesTotal: 120_000_000,
    },
    {
      packageId: 'chat-standard',
      label: 'Chat (standard)',
      state: 'skipped',
      bytesTotal: 5_000_000_000,
      skipCause: 'hardware',
      skipReason:
        'Insufficient VRAM for Chat (standard) (6144 MB available, 7680 MB required)',
    },
    {
      packageId: 'reranker',
      label: 'Reranker',
      state: 'skipped',
      bytesTotal: 90_000_000,
      skipCause: 'user-declined',
      skipReason: 'Reranker was declined — you chose not to install it.',
    },
  ],
} as InstallStatus;

afterEach(() => __resetUiModeForTest());

describe('skipExplanation (941)', () => {
  it('returns null for a package that was not skipped', () => {
    expect(skipExplanation({ state: 'installed', bytesTotal: 1 } as never)).toBeNull();
    expect(skipExplanation({ state: 'failed', skipReason: 'stale' } as never)).toBeNull();
  });

  it('prefers the planner-authored prose — no derived copy can reconstruct it', () => {
    const explained = skipExplanation({
      state: 'skipped',
      skipCause: 'hardware',
      skipReason: 'Insufficient VRAM for Chat (standard) (6144 MB available, 7680 MB required)',
    });
    expect(explained?.badge).toBe('Unsupported here');
    expect(explained?.detail).toBe(
      'Insufficient VRAM for Chat (standard) (6144 MB available, 7680 MB required)',
    );
  });

  it('derives a sentence per typed cause when the record carries no prose', () => {
    expect(skipExplanation({ state: 'skipped', skipCause: 'user-declined' })).toEqual({
      badge: 'Declined',
      detail: 'You chose not to install this component.',
    });
    expect(skipExplanation({ state: 'skipped', skipCause: 'intent' })?.badge).toBe(
      'Not in this mode',
    );
    expect(skipExplanation({ state: 'skipped', skipCause: 'dev-only' })?.badge).toBe(
      'Development only',
    );
  });

  it('an unknown or empty cause stays neutral — it must not blame the machine', () => {
    // The backend leaves `skipCause` EMPTY for an unclassified skip deliberately ("unknown is
    // empty, never a guess"). Rendering that as "unsupported hardware" would be the
    // prose-as-assumption defect 941 round 19 F3 removed from the completion message.
    for (const cause of ['', '   ', 'policy', undefined]) {
      const explained = skipExplanation({ state: 'skipped', skipCause: cause });
      expect(explained?.badge).toBe('Not installed');
      expect(explained?.detail).toBeNull();
    }
  });

  it('keeps the authored prose even when the cause is unclassified', () => {
    expect(
      skipExplanation({ state: 'skipped', skipReason: 'Something specific happened.' })?.detail,
    ).toBe('Something specific happened.');
  });
});

describe('BrainSurface Models list renders the skip reason (941)', () => {
  it('names each skipped package’s status and reason', async () => {
    const text = await modelsText(MIXED_PACKAGES);
    expect(text).toContain('Unsupported here');
    expect(text).toContain('6144 MB available, 7680 MB required');
    expect(text).toContain('Declined');
    expect(text).toContain('Reranker was declined');
  });

  it('replaces the never-downloaded byte count with the status word', async () => {
    const text = await modelsText(MIXED_PACKAGES);
    // The installed package still shows its size…
    expect(text).toContain('114.4 MB');
    // …while the ~4.7 GB a skipped package would have taken is NOT presented as though it landed.
    // The badge stands in its place; a size for a download that never ran is the claim this fixes.
    expect(text).not.toMatch(/\bGB\b/);
  });

  it('an installed-only list is unchanged (no skip chrome appears)', async () => {
    const text = await modelsText({
      state: 'completed',
      phase: 'done',
      installedFully: true,
      packages: [
        { packageId: 'embedding', label: 'Embeddings', state: 'installed', bytesTotal: 120_000_000 },
      ],
    } as InstallStatus);
    expect(text).not.toContain('Not installed');
    expect(text).not.toContain('Declined');
  });
});
