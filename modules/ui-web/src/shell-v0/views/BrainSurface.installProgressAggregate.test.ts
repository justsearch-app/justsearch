// @vitest-environment happy-dom

/**
 * Tempdoc 840 finding B12 — the install progress bar must read the backend's own aggregate
 * (`installStatus.downloadedBytes`/`totalBytes`, maintained by the install service) rather than
 * re-deriving it by summing `installStatus.packages[].bytesDownloaded`/`bytesTotal`. Two sources for
 * one number meant a package that never populated its byte fields silently disagreed with the
 * backend's own denominator.
 *
 * This test pins the wire aggregate as the source of truth: `packages[]` carries NO byte fields at
 * all, so the old per-package-sum implementation would compute 0/0 and hide the bar entirely
 * (`pct === null`). The fix must still render the correct percentage and byte counts from
 * `installStatus.downloadedBytes`/`totalBytes` directly.
 */

import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';

// Incidental requests are outside these render/event scenarios. Keep their
// unavailable-backend behavior explicit, without contacting a real server.
const originalFetch = globalThis.fetch;
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Test backend unavailable')));
});
afterEach(() => { globalThis.fetch = originalFetch; });
import './BrainSurface';
import { computeAiEngineVerdict } from '../state/aiVerdict.js';
import type { AiEngineVerdict } from '../state/aiVerdict.js';
import type { InstallStatus } from '../state/aiStateStore.js';

interface BrainHost extends HTMLElement {
  apiBase: string;
  settings: { mode?: 'simple' | 'advanced' };
  installStatus: InstallStatus | null;
  _unifiedAiState: { aiEngine: AiEngineVerdict } | null;
  requestUpdate(): void;
  updateComplete: Promise<boolean>;
}

/** Mounts the surface in the state the backend reports for `installStatus`, returns the element. */
async function mountFor(installStatus: InstallStatus): Promise<BrainHost> {
  const el = document.createElement('jf-brain-surface') as BrainHost;
  el.apiBase = '';
  el.settings = { mode: 'simple' };
  document.body.appendChild(el);
  await el.updateComplete;
  // The store computes this once for every consumer; do the same rather than hand-writing a verdict,
  // so a regression in the derivation fails these render assertions too.
  el.installStatus = installStatus;
  el._unifiedAiState = {
    aiEngine: computeAiEngineVerdict({
      installStatus,
      runtimeStatus: null,
      runtime: {
        mode: 'offline',
        modelId: null,
        modelLabel: null,
        contextWindow: null,
        gpu: null,
        installed: { known: false },
        installing: { known: false },
        loadStartedAtMs: null,
      } as never,
      reachable: true,
      snapshotLive: true,
    }),
  };
  el.requestUpdate();
  await el.updateComplete;
  return el;
}

// `state: 'running'` is what drives `computeAiEngineVerdict` to the `installing` kind, which is what
// gates the progress bar's render.
const RUNNING_NO_PER_PACKAGE_BYTES: InstallStatus = {
  state: 'running',
  phase: 'downloading',
  installedFully: false,
  downloadedBytes: 3_000_000_000,
  totalBytes: 6_000_000_000,
  packages: [
    { packageId: 'core', state: 'running' },
    { packageId: 'reranker', state: 'pending' },
  ],
};

describe('BrainSurface — install progress reads the wire aggregate, not the per-package sum', () => {
  it('renders the percentage and byte counts from downloadedBytes/totalBytes even when no package has byte fields', async () => {
    const el = await mountFor(RUNNING_NO_PER_PACKAGE_BYTES);
    // 3_000_000_000 / 6_000_000_000 = 50%; a per-package sum over byte-less packages would be 0/0
    // (null), hiding the bar entirely instead of rendering it at 50% width.
    const bar = el.shadowRoot?.querySelector('.progress-bar') as HTMLElement | null;
    expect(bar).not.toBeNull();
    expect(bar?.style.width).toBe('50%');

    const text = el.shadowRoot?.textContent ?? '';
    expect(text).toContain('2.79 GB');
    expect(text).toContain('5.59 GB');
    document.body.removeChild(el);
  });
});
