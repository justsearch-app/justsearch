// @vitest-environment happy-dom

/**
 * Sandbox round 8 — the idle Brain surface over a paused download.
 *
 * The defect: after cancelling a multi-GB install (whose dialog promised "everything already
 * downloaded stays on disk and the next install resumes from where it stopped"), the surface read
 * "Not Installed — Install AI models to get started." above a bare "Install AI" button, with
 * ~1.2 GB still on disk. Nothing acknowledged the retained bytes, so the promise was contradicted by
 * the very next screen.
 *
 * These tests drive the REAL chain — the backend's disk-probed `installStatus.resumableBytes`
 * through `computeAiEngineVerdict` into the rendered panel — because the failure was a rendered
 * sentence, not an internal value.
 */

import { afterEach, describe, expect, it } from 'vitest';
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

/** Mounts the surface in the state the backend reports for `installStatus`, returns rendered text. */
async function renderFor(installStatus: InstallStatus): Promise<string> {
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
      // Tempdoc 807 — the install axis is deliberately NOT liveness-gated, but the input is explicit.
      snapshotLive: true,
    }),
  };
  el.requestUpdate();
  await el.updateComplete;
  const text = el.shadowRoot?.textContent ?? '';
  document.body.removeChild(el);
  return text;
}

const PAUSED: InstallStatus = {
  state: 'idle', // what a restart leaves behind — `cancelled` does NOT survive one
  phase: 'idle',
  installedFully: false,
  resumableBytes: 1_140_000_000,
};

const FRESH: InstallStatus = { state: 'idle', phase: 'idle', installedFully: false };
const ACTIVATION_REQUIRED: InstallStatus = {
  state: 'completed',
  phase: 'activation_required',
  message: 'Downloaded — activation required.',
  installedFully: false,
};

describe('BrainSurface — idle panel over a paused download', () => {
  it('acknowledges the retained bytes instead of claiming "Not Installed"', async () => {
    const text = await renderFor(PAUSED);
    expect(text).toContain('Download Paused');
    expect(text).toContain('1.06 GB already downloaded is kept on disk');
    expect(text).not.toContain('Not Installed');
    expect(text).not.toContain('Install AI models to get started');
  });

  it('offers to resume rather than to start over', async () => {
    const text = await renderFor(PAUSED);
    expect(text).toContain('Resume Download');
  });

  it('a genuinely fresh install is untouched — still "Not Installed" / "Install AI"', async () => {
    const text = await renderFor(FRESH);
    expect(text).toContain('Not Installed');
    expect(text).toContain('Install AI models to get started');
    expect(text).not.toContain('Download Paused');
  });

  it('shows the retained download and the explicit activation CTA', async () => {
    const text = await renderFor(ACTIVATION_REQUIRED);
    expect(text).toContain('Downloaded — activation required');
    expect(text).toContain('Activate downloaded models');
    expect(text).not.toContain('Not Installed');
  });
});

/**
 * The second half of the round-8 defect: the consent dialog quoted a MOUNT-time total. `refreshAll()`
 * ran from `connectedCallback`, the manual refresh button and one op-success handler; `startInstall()`
 * only opened the dialog. So a plan the backend had already superseded — by finishing files, or by
 * keeping a cancelled run's bytes — was still what the user was asked to consent to.
 */
describe('BrainSurface — the consent dialog re-asks the backend before opening', () => {
  const realFetch = globalThis.fetch;
  afterEach(() => {
    globalThis.fetch = realFetch;
  });

  /** Lets pending microtasks + fetch promises settle without an unbounded wait. */
  async function settle(el: BrainHost): Promise<void> {
    for (let i = 0; i < 10; i++) {
      await new Promise((r) => setTimeout(r, 0));
      await el.updateComplete;
    }
  }

  it('shows the plan as of the click, not as of mount', async () => {
    const previewFetches: string[] = [];
    let totalDownloadBytes = 10_890_000_000; // "10.14 GB" — the stale mount-time plan
    globalThis.fetch = (async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/ai/install/plan-preview')) {
        previewFetches.push(url);
        return new Response(JSON.stringify({ intent: 'full', totalDownloadBytes }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        });
      }
      return new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } });
    }) as typeof fetch;

    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.apiBase = '';
    el.settings = { mode: 'simple' };
    el.installStatus = FRESH;
    document.body.appendChild(el);
    await settle(el);
    expect(previewFetches.length).toBe(1);
    expect(el.shadowRoot?.textContent ?? '').toContain('10.14 GB');

    // The backend moves on while the surface sits mounted — files finish, or a cancelled run's bytes
    // are kept. Nothing in the old code path would ever ask again.
    totalDownloadBytes = 9_750_000_000; // "9.08 GB"

    // Set the observed verdict last: `connectedCallback`'s store subscription overwrites
    // `_unifiedAiState` on mount, and only the not_installed verdict renders the install action.
    el._unifiedAiState = {
      aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null },
    };
    el.requestUpdate();
    await el.updateComplete;
    const action = el.shadowRoot?.querySelector('[data-testid="brain-simple-action"]') as
      | (HTMLElement & { onActivate?: () => void })
      | null;
    action?.onActivate?.();
    await settle(el);

    expect(previewFetches.length).toBe(2);
    const text = el.shadowRoot?.textContent ?? '';
    expect(text).toContain('9.08 GB');
    expect(text).not.toContain('10.14 GB');
    document.body.removeChild(el);
  });
});
