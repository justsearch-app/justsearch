// @vitest-environment happy-dom

/**
 * Tempdoc 807 A.3 (round-13 R13-F2) — with the backend dead, no snapshot-derived Brain surface may
 * render a PRESENT-TENSE progress or capability claim.
 *
 * The round photographed, with both java processes killed: an animating "Building semantic search
 * 2.0% · 5,084 pending", "Search Quality Features 4/4 active", and a populated Runtime card. Each
 * value was a true past measurement rendered as a present fact. These tests assert on the RENDERED
 * TEXT, and each has an anti-regression twin proving a healthy verdict still renders normally.
 */

import { describe, expect, it, vi } from 'vitest';
import { nothing, render, type TemplateResult } from 'lit';
import './BrainSurface.js';
import '../components/Control.js';
import type { Control } from '../components/Control.js';
import { EPHEMERAL_TOAST_EVENT } from '../components/advisory/ephemeralToast.js';
import type { Availability } from '../state/availability.js';
import type { AiRuntime, AiState, InstallStatus } from '../state/aiStateStore.js';
import { computeAiEngineVerdict } from '../state/aiVerdict.js';
import { UNKNOWN, type Maybe } from '../state/known.js';
import { reasonFor } from '../state/readinessNotice.js';

/** The disconnection wording is IMPORTED from the one cause vocabulary, never a string literal here. */
const DISCONNECTED = reasonFor('binding.unreachable').wording;

/** The surface formats counts with the ambient locale's grouping — assert against the same. */
const num = (n: number) => new Intl.NumberFormat().format(n);

const DEAD_VERDICT = { kind: 'transitioning', severity: 'warn', reasons: ['channel-stale'] } as const;
const LIVE_VERDICT = { kind: 'operational', severity: 'ok', reasons: [] } as const;

interface Harness {
  apiBase: string;
  systemStatus: unknown;
  runtimeStatus: unknown;
  inference: unknown;
  policy: unknown;
  busy: Record<string, boolean>;
  _unifiedAiState: unknown;
  renderEnrichmentProgress(): TemplateResult | symbol;
  renderSearchQualityFeatures(): TemplateResult;
  renderRuntimeSection(): TemplateResult;
}

/**
 * A detached BrainSurface (no connectedCallback ⇒ no poll/subscribe) primed with the round-13 data.
 *
 * The `worker` block was added by the round-15 scope fix: the card reads the shared index-wide
 * projection now, not `status.embedding` alone, so the fixture has to carry what that projection
 * reads. The round-13 figures are preserved — one stage, 104 of 5,188 settled — so these cases keep
 * asserting about tense, which is their subject.
 */
function harness(live: boolean): Harness {
  const el = document.createElement('jf-brain-surface') as unknown as Harness;
  el.apiBase = '';
  el.busy = {};
  el.policy = {};
  el.systemStatus = {
    embedding: { pendingCount: 5084, completedCount: 104, docCount: 5188, coveragePercent: 2.0 },
    worker: {
      core: { indexState: 'IDLE', pendingJobs: 0 },
      enrichment: {
        backfillMode: 'combined',
        embeddingEnabled: true,
        embeddingDocCount: 5188,
        embeddingPendingCount: 5084,
      },
    },
  };
  el.runtimeStatus = {
    onnxFeatures: [
      { id: 'reranker', label: 'Reranker', modelActive: true, executionProvider: 'cuda' },
      { id: 'splade', label: 'SPLADE', modelActive: true, executionProvider: 'cuda' },
      { id: 'embed', label: 'Embeddings', modelActive: true, executionProvider: 'cuda' },
      { id: 'citation_scorer', label: 'Citations', modelActive: true, executionProvider: 'cpu' },
    ],
  };
  el.inference = {
    mode: 'online',
    gpu: { cudaAvailable: true, vramDescription: '12.0 GB' },
    embeddingQueueSize: 4789,
  };
  el._unifiedAiState = {
    verdict: live ? LIVE_VERDICT : DEAD_VERDICT,
    snapshotLive: live,
    indexingStatusLive: live,
    aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
    episodeMaxPendingJobs: 0,
    enrichSettleSamples: [],
  } as unknown as AiState;
  return el;
}

function textOf(tpl: TemplateResult | symbol): string {
  const container = document.createElement('div');
  render(tpl as TemplateResult, container);
  return container.textContent ?? '';
}

function domOf(tpl: TemplateResult | symbol): HTMLElement {
  const container = document.createElement('div');
  render(tpl as TemplateResult, container);
  return container;
}

describe('BrainSurface — enrichment progress card (807)', () => {
  it('S33.4 an expired indexing observation is unconfirmed even while the Engine remains reachable, then recovers', () => {
    const el = harness(true);
    const state = el._unifiedAiState as AiState;
    state.indexingStatusLive = false;
    const stale = domOf(el.renderEnrichmentProgress());
    expect(stale.textContent).toContain('Indexing status unconfirmed');
    expect(stale.textContent).not.toContain(DISCONNECTED);
    expect(stale.textContent).not.toContain('Building semantic search');
    expect(stale.querySelector('.spin, [class*="spin"]')).toBeNull();
    state.indexingStatusLive = true;
    expect(textOf(el.renderEnrichmentProgress())).toContain('Building semantic search');
  });

  it('stops presenting as live progress when the snapshot is not live', () => {
    const text = textOf(harness(false).renderEnrichmentProgress());
    expect(text).not.toContain('Building semantic search');
    expect(text).toContain('last known');
    expect(text).toContain(DISCONNECTED);
    // The figures survive — this bundle changes their tense, not their existence.
    expect(text).toContain('2%');
    expect(text).toContain('pending when last observed');
  });

  it('stops ANIMATING when the snapshot is not live (the spinner is the present-tense claim)', () => {
    const dead = domOf(harness(false).renderEnrichmentProgress());
    expect(dead.querySelector('.spin, [class*="spin"]')).toBeNull();
    const live = domOf(harness(true).renderEnrichmentProgress());
    expect(live.querySelector('.spin, [class*="spin"]')).not.toBeNull();
  });

  it('ANTI-REGRESSION: a healthy verdict still renders the live progress card unchanged', () => {
    const text = textOf(harness(true).renderEnrichmentProgress());
    expect(text).toContain('Building semantic search');
    expect(text).toContain('2%');
    expect(text).toContain(`${num(5084)} pending`);
    expect(text).not.toContain('last known');
  });
});

/**
 * Round-15 "two progress indicators, two scopes, neither declared"
 * (`evidence/progress-indicator-scope-mismatch.md`).
 *
 * The round captured one frame in which this card read 96.8% while the Tasks card read 19%, both
 * calling it "semantic search"; then the card VANISHED at 100% of its single signal while overall
 * enrichment was 46%, leaving the AI-status surface reading idle mid-run. The fixture below is that
 * document's own worked example — `Semantic vectors 570/570 ✓ · Keyword expansion 104/570 · Entity
 * recognition 57/570 · Passage vectors 4,689/15,910`, whose unit-weighted blend it computes as
 * 30.8% against a displayed 31%.
 */
describe('BrainSurface — enrichment progress SCOPE (round-15 scope mismatch)', () => {
  function scoped(): Harness {
    const el = harness(true);
    el.systemStatus = {
      // Document-level embedding is COMPLETE — the single signal the card used to track, and the
      // exact reading that used to make it disappear.
      embedding: { pendingCount: 0, completedCount: 570, docCount: 570, coveragePercent: 100 },
      worker: {
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'combined',
          embeddingEnabled: true,
          spladeEnabled: true,
          nerEnabled: true,
          embeddingDocCount: 570,
          embeddingPendingCount: 0,
          spladeDocCount: 570,
          spladePendingCount: 466,
          completedNerCount: 57,
          pendingNerCount: 513,
          chunk: { chunkDocCount: 15910, chunkEmbeddingPendingCount: 11221 },
        },
      },
    };
    return el;
  }

  it('THE defect: the card does not disappear when its old single signal hits 100%', () => {
    const tpl = scoped().renderEnrichmentProgress();
    expect(tpl).not.toBe(nothing);
    expect(textOf(tpl)).toContain('Building semantic search');
  });

  it('shows the AGGREGATE percent, the one the other surfaces show — not the embedding stage', () => {
    const text = textOf(scoped().renderEnrichmentProgress());
    // (570 + 104 + 57 + 4,689) / (570 + 570 + 570 + 15,910) = 30.8% → 31%.
    expect(text).toContain('31%');
    // The stage-scoped number this card used to render, and would render again if it regressed.
    expect(text).not.toContain('100%');
  });

  it('declares its SCOPE, and no longer promises a quantity it is not measuring', () => {
    const text = textOf(scoped().renderEnrichmentProgress());
    expect(text).toContain('Overall enrichment across all stages');
    // The subtitle that named chunk embeddings while displaying document vectors.
    expect(text).not.toContain('Generating chunk embeddings');
    // The pending figure is the blend's, so it is labelled as the blend's.
    expect(text).toContain('pending across all stages');
  });

  it('withdraws once the WHOLE blend settles, not once one stage does', () => {
    const el = scoped();
    el.systemStatus = {
      embedding: { pendingCount: 0, completedCount: 570, docCount: 570, coveragePercent: 100 },
      worker: {
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingEnabled: true,
          spladeEnabled: true,
          nerEnabled: true,
          embeddingDocCount: 570,
          embeddingPendingCount: 0,
          spladeDocCount: 570,
          spladePendingCount: 0,
          completedNerCount: 570,
          pendingNerCount: 0,
          chunk: { chunkDocCount: 15910, chunkEmbeddingPendingCount: 0 },
        },
      },
    };
    expect(el.renderEnrichmentProgress()).toBe(nothing);
  });

  it('renders no progress bar when semantic enrichment cannot run at all (round-15 F1)', () => {
    // Nothing is progressing, so a bar would be a fabrication; the surface's own Install AI section
    // is the remedy this state actually has.
    const el = harness(true);
    el.systemStatus = {
      embedding: { pendingCount: 5, completedCount: 0, docCount: 5, coveragePercent: 0 },
      worker: {
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingEnabled: false,
          spladeEnabled: false,
          nerEnabled: false,
          embeddingDocCount: 5,
          embeddingPendingCount: 5,
          chunk: { chunkDocCount: 2, chunkEmbeddingPendingCount: 2 },
        },
      },
    };
    expect(el.renderEnrichmentProgress()).toBe(nothing);
  });
});

describe('BrainSurface — capability counts (807)', () => {
  it('"4/4 active" stops claiming active when the snapshot is not live', () => {
    const text = textOf(harness(false).renderSearchQualityFeatures());
    expect(text).not.toContain('4/4 active');
    expect(text).toContain('4/4 when last observed');
  });

  it('ANTI-REGRESSION: a healthy verdict still says "4/4 active"', () => {
    expect(textOf(harness(true).renderSearchQualityFeatures())).toContain('4/4 active');
  });
});

/**
 * Tempdoc 807 Part A (W4) — the SENTENCE itself, asserted where the round photographed it: the Brain
 * status card. W1 fixed the surfaces around this card; the card's own words come from the AI-engine
 * verdict, so this harness derives `aiEngine` through the real `computeAiEngineVerdict` (never a
 * hand-written verdict literal) — the test fails if EITHER the derivation or this render regresses.
 */
describe('BrainSurface — the status card sentence (807 Part A)', () => {
  interface PanelHarness {
    apiBase: string;
    busy: Record<string, boolean>;
    policy: unknown;
    installStatus: InstallStatus | null;
    inference: unknown;
    _unifiedAiState: unknown;
    renderSimplePanel(): TemplateResult;
  }

  const RUNTIME: AiRuntime = {
    mode: 'online',
    modelId: 'qwen3-4b',
    modelLabel: 'Qwen3 4B',
    contextWindow: 8192,
    contextWindowDerived: null,
    gpu: null,
    installed: UNKNOWN as Maybe<boolean>,
    installing: UNKNOWN as Maybe<boolean>,
    loadStartedAtMs: null,
  };

  /** A healthy engine observed at some past moment; `live` says whether that observation still holds. */
  function panel(live: boolean): PanelHarness {
    const el = document.createElement('jf-brain-surface') as unknown as PanelHarness;
    el.apiBase = '';
    el.busy = {};
    el.policy = {};
    el.installStatus = { state: 'idle', phase: 'idle', installedFully: true };
    el.inference = { mode: 'online' };
    const aiEngine = computeAiEngineVerdict({
      installStatus: el.installStatus,
      runtimeStatus: null,
      runtime: RUNTIME,
      reachable: live,
      snapshotLive: live,
      engineState: 'Healthy',
      chatEnabledSpec: true,
    });
    el._unifiedAiState = {
      verdict: live ? LIVE_VERDICT : DEAD_VERDICT,
      snapshotLive: live,
      aiEngine,
      runtime: RUNTIME,
      inference: el.inference,
      status: {},
    } as unknown as AiState;
    return el;
  }

  it('THE round-13 photograph: with the backend gone, the card no longer reads "Online / Chat and summaries ready."', () => {
    const dom = domOf(panel(false).renderSimplePanel());
    expect(dom.textContent).not.toContain('Chat and summaries ready.');
    expect(dom.textContent).not.toContain('Online');
    // ...and the dot beside it is not the settled green one.
    // `tone` is bound as an ATTRIBUTE (`tone=${...}`), and the atom is not upgraded in this detached
    // render — read the attribute, not a property that would be `undefined` either way.
    const dot = dom.querySelector('jf-status-dot');
    expect(dot?.getAttribute('tone')).not.toBe('success');
  });

  it('the card offers no action whose precondition is a live backend (807 A.3)', () => {
    const dom = domOf(panel(false).renderSimplePanel());
    const action = dom.querySelector('[data-testid="brain-simple-action"]') as unknown as {
      label: string;
      availability?: { kind: string };
    } | null;
    expect(action?.label).not.toBe('Shut Down AI');
    expect(action?.availability?.kind).not.toBe('available');
  });

  it('ANTI-REGRESSION: a live observation still renders the green "Online / Chat and summaries ready."', () => {
    const dom = domOf(panel(true).renderSimplePanel());
    expect(dom.textContent).toContain('Online');
    expect(dom.textContent).toContain('Chat and summaries ready.');
    // `tone` is bound as an ATTRIBUTE (`tone=${...}`), and the atom is not upgraded in this detached
    // render — read the attribute, not a property that would be `undefined` either way.
    const dot = dom.querySelector('jf-status-dot');
    expect(dot?.getAttribute('tone')).toBe('success');
  });
});

describe('BrainSurface — Runtime card (807)', () => {
  it('labels the readout as last-known and blocks its controls with a reason when not live', () => {
    const dom = domOf(harness(false).renderRuntimeSection());
    expect(dom.textContent).toContain(DISCONNECTED);
    expect(dom.textContent).toContain('last observed');
    // Every control whose precondition is a live backend is unavailable-WITH-A-REASON (the 596 soft
    // block), not silently clickable-and-failing.
    const buttons = Array.from(dom.querySelectorAll('jf-button')) as unknown as {
      label: string;
      availability?: { kind: string; reason?: string; transient?: boolean };
    }[];
    const gated = buttons.filter((b) => ['Online', 'Indexing', 'Reload'].includes(b.label));
    expect(gated.length).toBe(3);
    for (const b of gated) {
      expect(b.availability?.kind, b.label).toBe('unavailable');
      expect(b.availability?.reason, b.label).toBe(DISCONNECTED);
      // Round-13 review, P2: NOT transient. `transient` is what makes `jf-control` queue the intent
      // and replay it on reconnect (Control.activate/resolveQueued) — for RUNTIME MUTATIONS that
      // turns an offline click into a burst of conflicting POSTs the moment the backend returns,
      // the opposite of the "disabled while disconnected" these controls are supposed to be.
      expect(b.availability?.transient, b.label).toBeFalsy();
    }
  });

  it('P2: an offline click on a runtime control neither queues nor fires when the backend returns', async () => {
    // The REAL gate value the surface produces, driven through a REAL jf-control.
    const dom = domOf(harness(false).renderRuntimeSection());
    const online = Array.from(dom.querySelectorAll('jf-button')).find(
      (b) => (b as unknown as { label: string }).label === 'Online',
    ) as unknown as { availability?: Availability };
    expect(online?.availability?.kind).toBe('unavailable');

    const onActivate = vi.fn();
    const toasts: string[] = [];
    const listener = (e: Event) => toasts.push((e as CustomEvent).detail?.message ?? '');
    document.addEventListener(EPHEMERAL_TOAST_EVENT, listener);
    const el = document.createElement('jf-control') as Control;
    el.label = 'Online';
    el.availability = online.availability as Availability;
    el.onActivate = onActivate;
    document.body.appendChild(el);
    await el.updateComplete;

    el.shadowRoot!.querySelector('button')!.click();
    expect(onActivate).not.toHaveBeenCalled();
    expect(toasts.some((m) => /queued/i.test(m))).toBe(false);

    el.availability = { kind: 'available' }; // the backend comes back
    await el.updateComplete;
    document.removeEventListener(EPHEMERAL_TOAST_EVENT, listener);
    expect(onActivate).not.toHaveBeenCalled(); // no unattended runtime mutation
    el.remove();
  });

  it('ANTI-REGRESSION: a healthy verdict leaves the Runtime readout and its controls alone', () => {
    const dom = domOf(harness(true).renderRuntimeSection());
    expect(dom.textContent).not.toContain(DISCONNECTED);
    expect(dom.textContent).toContain('available'); // CUDA: available
    expect(dom.textContent).toContain(num(4789)); // embed queue, un-caveated
    expect(dom.textContent).not.toContain('last observed');
    const buttons = Array.from(dom.querySelectorAll('jf-button')) as unknown as {
      label: string;
      availability?: { kind: string };
    }[];
    for (const b of buttons.filter((x) => ['Online', 'Indexing', 'Reload'].includes(x.label))) {
      expect(b.availability?.kind, b.label).toBe('available');
    }
  });
});
