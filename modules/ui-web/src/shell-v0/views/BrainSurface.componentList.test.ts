// @vitest-environment happy-dom

/**
 * Tempdoc 840 Phase 5 — the component list and the honest progress line, as RENDERED.
 *
 * The defect this closes was a rendered screen, not an internal value: ~7 GB of download presented as
 * one percentage with no rate, no ETA, and no way to tell what any of it was for. So these drive the
 * real chain — a plan-preview fetch through the surface's own mount path into `shadowRoot.textContent`
 * — rather than asserting on the composers (which `state/installComponents.test.ts` pins directly).
 */

import { afterEach, describe, expect, it } from 'vitest';
import './BrainSurface';
import { computeAiEngineVerdict } from '../state/aiVerdict.js';
import type { InstallStatus } from '../state/aiStateStore.js';

interface BrainHost extends HTMLElement {
  apiBase: string;
  settings: { mode?: 'simple' | 'advanced' };
  installStatus: InstallStatus | null;
  requestUpdate(): void;
  updateComplete: Promise<boolean>;
}

const PREVIEW = {
  intent: 'full-desktop',
  totalDownloadBytes: 7_000_000_000,
  components: [
    {
      id: 'embedding',
      label: 'Search embeddings',
      description: 'Turns your documents into vectors so search can find them by meaning.',
      tier: 'retrieval-core',
      necessity: 'required',
      declinable: false,
      declined: false,
      totalBytes: 1_200_000_000,
      downloadBytes: 1_200_000_000,
      state: 'to-download',
    },
    {
      id: 'reranker',
      label: 'Search reranker',
      description: 'Re-orders the top results so the best answer is first.',
      tier: 'retrieval-enrichment',
      necessity: 'improves-results',
      declinable: true,
      declined: false,
      totalBytes: 340_000_000,
      downloadBytes: 340_000_000,
      state: 'to-download',
    },
    {
      id: 'cuda-runtime',
      label: 'GPU runtime libraries',
      description: 'CUDA libraries the GPU models run on.',
      tier: 'runtime',
      necessity: 'infrastructure',
      declinable: false,
      declined: false,
      totalBytes: 2_000_000_000,
      downloadBytes: 0,
      state: 'unavailable',
      unavailableReason: 'No CUDA-capable GPU was detected on this machine.',
    },
  ],
};

/**
 * The real seven-component shape, with the four-member `improves-results` tier the size bar exists for.
 * PREVIEW's three components each sit alone in their category, so it can only exercise the NO-bar case;
 * this fixture is what pins the within-group scale and the group subtotals.
 */
const PREVIEW_ENRICHMENT = {
  ...PREVIEW,
  components: [
    ...PREVIEW.components.map((c) =>
      c.id === 'reranker' ? { ...c, totalBytes: 616_300_000, downloadBytes: 616_300_000 } : c,
    ),
    { ...PREVIEW.components[1]!, id: 'splade', label: 'Sparse retrieval', totalBytes: 481_500_000 },
    { ...PREVIEW.components[1]!, id: 'ner', label: 'Named entities', totalBytes: 259_900_000 },
    {
      ...PREVIEW.components[1]!,
      id: 'citation-scorer',
      label: 'Citation scorer',
      totalBytes: 22_600_000,
    },
    {
      ...PREVIEW.components[1]!,
      id: 'chat',
      label: 'Chat model',
      necessity: 'adds-feature',
      totalBytes: 6_400_000_000,
    },
  ],
};

/** Lets pending microtasks + fetch promises settle without an unbounded wait. */
async function settle(el: BrainHost): Promise<void> {
  for (let i = 0; i < 10; i++) {
    await new Promise((r) => setTimeout(r, 0));
    await el.updateComplete;
  }
}

describe('BrainSurface — the component list', () => {
  const realFetch = globalThis.fetch;
  const declineCalls: Array<{ url: string; method: string }> = [];

  afterEach(() => {
    globalThis.fetch = realFetch;
    declineCalls.length = 0;
    document.body.innerHTML = '';
  });

  /** What the backend reports for a machine that has never installed: idle, nothing on disk. */
  const IDLE: InstallStatus = { state: 'idle', phase: 'idle', installedFully: false };

  async function mountWith(
    preview: unknown,
    installStatus: InstallStatus | null = IDLE,
  ): Promise<BrainHost> {
    globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/decline')) {
        declineCalls.push({ url, method: init?.method ?? 'GET' });
        return new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } });
      }
      if (url.includes('/api/ai/install/plan-preview')) {
        return new Response(JSON.stringify(preview), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        });
      }
      return new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } });
    }) as typeof fetch;

    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.apiBase = '';
    el.settings = { mode: 'simple' };
    el.installStatus = installStatus;
    document.body.appendChild(el);
    await settle(el);
    // Acquisition details now live under Advanced settings → Models.
    el.shadowRoot?.querySelector<HTMLButtonElement>('.brain-links button')?.click();
    await el.updateComplete;
    const models = Array.from(el.shadowRoot?.querySelectorAll<HTMLButtonElement>('.accordion-header') ?? []).find(button => button.textContent?.includes('Models'));
    models?.click();
    await el.updateComplete;
    return el;
  }

  const mount = (installStatus: InstallStatus | null = IDLE) => mountWith(PREVIEW, installStatus);

  it('names every component, says what it does, and what it costs', async () => {
    const el = await mount();
    const text = el.shadowRoot?.textContent ?? '';
    expect(text).toContain('Search embeddings');
    expect(text).toContain('Turns your documents into vectors so search can find them by meaning.');
    expect(text).toContain('Search reranker');
    expect(text).toContain('1.12 GB');
  });

  it('groups by necessity and states the CONSEQUENCE of each category', async () => {
    const el = await mount();
    const text = el.shadowRoot?.textContent ?? '';
    // The consequence is a CLAUSE on the group heading line now (840 post-review C.3), not a sentence
    // on its own line. Same three consequences, still rendered — that is what this pinned.
    expect(text).toContain('search does not work without this');
    expect(text).toContain('results are worse without these');
    expect(text).toContain('plumbing, not a capability you use directly');
    expect(el.shadowRoot?.querySelector('[data-testid="component-group-required"]')).not.toBeNull();
  });

  it('constrains the list and columnises it, so the figures cannot drift with the names', async () => {
    const el = await mount();
    const list = el.shadowRoot?.querySelector('[data-testid="install-component-list"]');
    expect(list).not.toBeNull();
    // Every row emits the control cell, INCLUDING the rows with no toggle — that reservation is what
    // keeps the size figures in one column (a trailing flex control shifted them per-row before).
    const rows = Array.from(
      el.shadowRoot?.querySelectorAll('[data-testid^="component-row-"]') ?? [],
    );
    expect(rows).toHaveLength(3);
    for (const row of rows) {
      expect(row.querySelector('.component-control')).not.toBeNull();
      expect(row.querySelector('.component-size')).not.toBeNull();
    }
    // …and the reserved cell really is empty on a non-togglable row.
    const embedding = el.shadowRoot?.querySelector('[data-testid="component-row-embedding"]');
    expect(embedding?.querySelector('.component-control')?.children).toHaveLength(0);
  });

  it('shows a dash, not a zero, for a component with no declared size', async () => {
    const el = await mountWith({
      ...PREVIEW_ENRICHMENT,
      components: PREVIEW_ENRICHMENT.components.map((c) =>
        c.id === 'splade' ? { ...c, totalBytes: 0 } : c,
      ),
    });
    const row = el.shadowRoot?.querySelector('[data-testid="component-row-splade"]');
    // A dash says "not stated"; a 0 B would claim the component is weightless.
    expect(row?.querySelector('.component-size')?.textContent?.trim()).toBe('—');
  });

  it('emits the same three grid cells on every row, bar the description', async () => {
    const el = await mountWith(PREVIEW_ENRICHMENT);
    // happy-dom does no layout, so the identical cell SEQUENCE is what pins the figures into one
    // column — the measured pixel alignment is verified live in the browser.
    const shapes = Array.from(
      el.shadowRoot?.querySelectorAll('[data-testid^="component-row-"]') ?? [],
    ).map((row) =>
      Array.from(row.children)
        .map((c) => c.className)
        .filter((c) => c !== 'component-desc')
        .join(','),
    );
    expect(shapes).toHaveLength(7);
    expect(new Set(shapes)).toEqual(
      new Set(['component-name,component-size,component-control']),
    );
  });

  it('states what a MULTI-member group costs, and nothing for a group of one', async () => {
    const el = await mountWith(PREVIEW_ENRICHMENT);
    const subtotal = (n: string) =>
      el.shadowRoot?.querySelector(`[data-testid="component-subtotal-${n}"]`)?.textContent?.trim();
    expect(subtotal('improves-results')).toBe('4 components · 1.29 GB');
    // A one-member group prints no subtotal: its row already carries that figure.
    expect(subtotal('required')).toBeUndefined();
    expect(subtotal('adds-feature')).toBeUndefined();
    expect(subtotal('infrastructure')).toBeUndefined();
    // The section-wide summary is a different claim (what is on disk) and is unaffected by these.
    expect(el.shadowRoot?.querySelector('[data-testid="install-component-summary"]')).toBeNull();
  });

  it('says nothing where a component is simply installed, and speaks up where it is not', async () => {
    const el = await mount();
    // Nothing in this preview is installed, so every row deviates and every row says so.
    expect(el.shadowRoot?.textContent).not.toContain('Installed');
    expect(
      el.shadowRoot?.querySelector('[data-testid="component-state-embedding"]')?.textContent?.trim(),
    ).toBe('Will download');

    const installedPreview = {
      ...PREVIEW,
      components: PREVIEW.components.map((c) => ({ ...c, state: 'installed' })),
    };
    const el2 = await mountWith(installedPreview);
    // …and when everything IS installed, the per-row text is gone entirely — the summary line carries
    // the fact once instead of seven times.
    expect(el2.shadowRoot?.querySelector('[data-testid="component-state-embedding"]')).toBeNull();
    expect(el2.shadowRoot?.textContent).not.toContain('Will download');
    expect(
      el2.shadowRoot?.querySelector('[data-testid="install-component-summary"]')?.textContent?.trim(),
    ).toBe('3 installed · 3.30 GB on disk');
  });

  it('keeps the primary action beside the status it acts on, and only there', async () => {
    const el = await mount();
    const actions = el.shadowRoot?.querySelectorAll('[data-testid="brain-simple-action"]') ?? [];
    expect(actions).toHaveLength(1);
    expect(actions[0]!.closest('.status-row')).not.toBeNull();
  });

  it('offers a control ONLY where the user may say no (opt-out on a declinable row)', async () => {
    const el = await mount();
    expect(el.shadowRoot?.querySelector('[data-testid="component-toggle-reranker"]')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('[data-testid="component-toggle-embedding"]')).toBeNull();
  });

  it('shows unavailable hardware with its reason and NO toggle — not as an unticked choice', async () => {
    const el = await mount();
    expect(el.shadowRoot?.querySelector('[data-testid="component-toggle-cuda-runtime"]')).toBeNull();
    const reason = el.shadowRoot?.querySelector('[data-testid="component-unavailable-cuda-runtime"]');
    expect(reason?.textContent).toContain('No CUDA-capable GPU was detected on this machine.');
    expect(el.shadowRoot?.textContent).toContain('Not supported here');
    expect(el.shadowRoot?.textContent).not.toContain('Turned off');
  });

  it('declining a component POSTs the decline (and the toggle reads as an opt-OUT)', async () => {
    const el = await mount();
    const toggle = el.shadowRoot?.querySelector('[data-testid="component-toggle-reranker"]') as
      | (HTMLElement & { onActivate?: () => void; label?: string; pressed?: boolean })
      | null;
    // 840 post-review F — the control is a SWITCH now, so its state lives in `aria-pressed` and its
    // name is stable ("Search reranker") instead of flipping between "Turn off"/"Turn on". What the old
    // label assertion pinned — that this is an opt-OUT on a selected component, so activating it
    // DECLINES rather than enables — is pinned below by the POSTed decline plus `pressed === true`.
    expect(toggle?.label).toBe('Search reranker');
    expect(toggle?.pressed).toBe(true);
    expect(toggle?.shadowRoot?.querySelector('button')?.getAttribute('aria-pressed')).toBe('true');
    toggle?.onActivate?.();
    await settle(el);
    expect(declineCalls).toHaveLength(1);
    expect(declineCalls[0]!.method).toBe('POST');
    expect(declineCalls[0]!.url).toContain('/api/ai/install/packages/reranker/decline');
  });
});

const RUNNING_STATUS: InstallStatus = {
  state: 'running',
  phase: 'download',
  message: 'Downloading onnx/gte-multilingual-base/model.onnx...',
  downloadedBytes: 500,
  totalBytes: 1_000,
  bytesPerSecond: 1_048_576,
  remainingSeconds: 125,
  currentStage: 'enrichment',
  readyCapabilities: ['retrieval-core', 'runtime'],
  packages: [{ packageId: 'embedding', label: 'Search embeddings', state: 'downloading' }],
  stages: [
    {
      stage: 'core',
      label: 'Search core',
      state: 'completed',
      capabilities: ['retrieval-core'],
      totalBytes: 500,
      downloadedBytes: 500,
    },
    {
      stage: 'enrichment',
      label: 'Retrieval enrichment',
      state: 'running',
      capabilities: ['retrieval-enrichment'],
      totalBytes: 500,
      downloadedBytes: 0,
    },
    {
      stage: 'chat',
      label: 'Chat & AI answers',
      state: 'blocked',
      capabilities: ['llm'],
      totalBytes: 6_000,
      downloadedBytes: 0,
      blockedReason: 'Not enough disk space: 6.0 GB needed, 2.1 GB free.',
    },
  ],
};

describe('BrainSurface — honest install progress', () => {
  const realFetch = globalThis.fetch;
  afterEach(() => {
    globalThis.fetch = realFetch;
    document.body.innerHTML = '';
  });

  async function renderInstalling(status: InstallStatus): Promise<BrainHost> {
    globalThis.fetch = (async () =>
      new Response(JSON.stringify(PREVIEW), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })) as typeof fetch;
    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.apiBase = '';
    el.settings = { mode: 'simple' };
    document.body.appendChild(el);
    await settle(el);
    // Set AFTER mount: connectedCallback's store subscription overwrites the observed state.
    el.installStatus = status;
    // Derive the verdict the way the store does, so a regression in `computeAiEngineVerdict` fails
    // these render assertions too rather than being papered over by a hand-written verdict.
    (el as unknown as { _unifiedAiState: unknown })._unifiedAiState = {
      aiEngine: computeAiEngineVerdict({
        installStatus: status,
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

  it('shows the measured rate and time remaining', async () => {
    const el = await renderInstalling(RUNNING_STATUS);
    const line = el.shadowRoot?.querySelector('[data-testid="install-transfer-line"]');
    expect(line?.textContent?.trim()).toBe('1.0 MB/s · ~2m 5s left');
  });

  it('renders NO transfer line at all when the wire says the rate and horizon are unknown', async () => {
    const el = await renderInstalling({
      ...RUNNING_STATUS,
      bytesPerSecond: -1,
      remainingSeconds: -1,
    });
    expect(el.shadowRoot?.querySelector('[data-testid="install-transfer-line"]')).toBeNull();
    const text = el.shadowRoot?.textContent ?? '';
    expect(text).not.toContain('0 B/s');
    expect(text).not.toContain('0s left');
  });

  it('says search is usable while the rest is still downloading', async () => {
    const el = await renderInstalling(RUNNING_STATUS);
    const ready = el.shadowRoot?.querySelector('[data-testid="install-search-ready"]');
    expect(ready?.textContent).toContain('Search is ready');
    expect(ready?.textContent).toContain('Retrieval enrichment');
  });

  it('shows a blocked stage as an actionable reason, not a generic failure', async () => {
    const el = await renderInstalling(RUNNING_STATUS);
    const blocked = el.shadowRoot?.querySelector('[data-testid="install-stage-blocked-chat"]');
    expect(blocked?.textContent).toContain('Not enough disk space');
  });

  it('names the component being downloaded instead of its filesystem path (U5)', async () => {
    const el = await renderInstalling(RUNNING_STATUS);
    const text = el.shadowRoot?.textContent ?? '';
    expect(text).toContain('Downloading Search embeddings');
    expect(text).not.toContain('gte-multilingual-base/model.onnx');
  });

  it('offers pause — which is not cancel — while a run is in flight', async () => {
    const el = await renderInstalling(RUNNING_STATUS);
    const pause = el.shadowRoot?.querySelector('[data-testid="install-pause-toggle"]') as
      | (HTMLElement & { label?: string })
      | null;
    expect(pause?.label).toBe('Pause download');
  });

  it('reads as PAUSED (and offers resume) when the backend says the run is paused', async () => {
    const el = await renderInstalling({ ...RUNNING_STATUS, paused: true });
    const pause = el.shadowRoot?.querySelector('[data-testid="install-pause-toggle"]') as
      | (HTMLElement & { label?: string })
      | null;
    expect(pause?.label).toBe('Resume download');
    expect(el.shadowRoot?.textContent).toContain('Paused');
  });
});
