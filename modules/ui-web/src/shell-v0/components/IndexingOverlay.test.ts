// @vitest-environment happy-dom

import { describe, expect, it, beforeEach } from 'vitest';
import { nothing } from 'lit';
import './IndexingOverlay.js';
import type { IndexingOverlay } from './IndexingOverlay.js';
import type { AiState } from '../state/aiStateStore.js';
import { known } from '../state/known.js';
import { modalOwnsFocus, __resetModalityForTest } from '../primitives/modality.js';

function make(): IndexingOverlay {
  const el = document.createElement('jf-indexing-overlay') as IndexingOverlay;
  document.body.appendChild(el);
  return el;
}

describe('IndexingOverlay (slice 460)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    // 864 F2 — the overlay now enters the app's modality authority, whose depth is module state and
    // outlives the DOM. Reset it so one case's overlay cannot make a later case's keys look dead.
    __resetModalityForTest();
  });

  it('S33.4 without an observation renders an unconfirmed header and explanation', async () => {
    const el = make();
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('h3')?.textContent).toBe('Indexing status unconfirmed');
    expect(el.shadowRoot?.querySelector('.explain')?.textContent).toContain(
      'Waiting for a fresh status observation.',
    );
  });

  it('renders queue rows when work is pending', async () => {
    const el = make();
    el.embeddingQueueSize = 333;
    el.vduQueueSize = 0;
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.queue-row.embed')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('.queue-row.vdu')).toBeNull();
  });

  it('omits the queue card when total is 0', async () => {
    const el = make();
    el.embeddingQueueSize = 0;
    el.vduQueueSize = 0;
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.queue')).toBeNull();
  });

  it('emits go-online on CTA click', async () => {
    const el = make();
    await el.updateComplete;
    let fired = false;
    el.addEventListener('go-online', () => (fired = true));
    el.shadowRoot?.querySelector<HTMLButtonElement>('button.cta')?.click();
    expect(fired).toBe(true);
  });

  it('CTA disabled while switching', async () => {
    const el = make();
    el.switching = true;
    await el.updateComplete;
    const btn = el.shadowRoot?.querySelector<HTMLButtonElement>('button.cta');
    expect(btn?.disabled).toBe(true);
  });

  it('emits dismiss on close button click', async () => {
    const el = make();
    el.dismissible = true;
    await el.updateComplete;
    let fired = false;
    el.addEventListener('dismiss', () => (fired = true));
    el.shadowRoot?.querySelector<HTMLButtonElement>('button.close')?.click();
    expect(fired).toBe(true);
  });

  it('hides close button when not dismissible', async () => {
    const el = make();
    el.dismissible = false;
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('button.close')).toBeNull();
  });

  /**
   * Tempdoc 864 review F2 — this overlay is a real modal (`role="dialog" aria-modal="true"` over a
   * `pointer-events: auto` backdrop), so it owns the keyboard while it is up. Its host renders the
   * element only while the overlay is up, so mounted IS the modal state and both edges are asserted
   * here; `Sv3Main.navigation.test.ts` asserts the consuming half (a key standing down under it).
   */
  describe('864 F2 — it owns the keyboard while it is up', () => {
    it('enters the modality authority on mount and releases it on teardown', async () => {
      expect(modalOwnsFocus(), 'nothing is up yet').toBe(false);
      const el = make();
      await el.updateComplete;
      expect(modalOwnsFocus(), 'a full-screen indexing overlay did not claim the keyboard').toBe(true);
      el.remove();
      expect(modalOwnsFocus(), 'the withdrawn overlay leaked its modality').toBe(false);
    });
  });
});

/**
 * Tempdoc 807 A.3 (round-13 R13-F2) — the HOST decides whether the overlay may assert "indexing is
 * happening right now". Its inputs are fields off the retained snapshot, so with the backend dead it
 * kept asserting live work (and offered a "Go online" button that would POST into the void).
 */
describe('IndexingOverlayHost — snapshot liveness (807)', () => {
  interface HostHarness {
    aiState: AiState | null;
    render(): unknown;
  }
  /** Detached (never appended) ⇒ no connectedCallback ⇒ the store subscription can't overwrite the fixture. */
  const host = (snapshotLive: boolean): HostHarness => {
    const el = document.createElement('jf-indexing-overlay-host') as unknown as HostHarness;
    el.aiState = {
      runtime: { mode: 'indexing' },
      index: { embeddingQueueSize: known(4789), vduQueueSize: known(0) },
      snapshotLive,
      // 813 §20 — the store's cross-poll memories are non-optional on AiState; a fixture that omits
      // them is not a smaller AiState, it is an impossible one.
      episodeMaxPendingJobs: 0,
      enrichSettleSamples: [],
    } as unknown as AiState;
    return el;
  };

  it('withdraws when the snapshot is no longer a live observation', () => {
    expect(host(false).render()).toBe(nothing);
  });

  it('ANTI-REGRESSION: still renders while the snapshot IS live', () => {
    expect(host(true).render()).not.toBe(nothing);
  });
});

/**
 * Tempdoc 813 §10.6 — the overlay's private two-row queue readout is retired. Both numbers are the
 * same worker counts `/api/status` carries; reaching them through `/api/inference/status` was the
 * §1a "one subject, two transports" divergence class.
 */
describe('IndexingOverlayHost — numbers come from the one projection (813)', () => {
  interface HostHarness {
    aiState: AiState | null;
    render(): unknown;
  }
  const host = (status: unknown): HostHarness => {
    const el = document.createElement('jf-indexing-overlay-host') as unknown as HostHarness;
    el.aiState = {
      runtime: { mode: 'indexing' },
      // Deliberately divergent inference-poll residue: if these still fed the rows, the assertions
      // below would read 999/999.
      index: { embeddingQueueSize: known(999), vduQueueSize: known(999) },
      snapshotLive: true,
      indexingStatusLive: true,
      status,
      // 813 §20 — see above: both store memories are part of the shape this fixture claims to be.
      episodeMaxPendingJobs: 0,
      enrichSettleSamples: [],
    } as unknown as AiState;
    return el;
  };

  it('does not present retry waiting as active work in a blocking overlay', () => {
    expect(host({ worker: {
      core: { indexState: 'WAITING', pendingJobs: 8, pendingVduCount: 7 },
      enrichment: { embeddingEnabled: true, embeddingDocCount: 100, embeddingPendingCount: 12 },
    } }).render()).toBe(nothing);
  });

  it('renders the status-poll pending counts, not the inference-poll queue residue', () => {
    const out = host({
      worker: {
        core: { indexState: 'IDLE', pendingJobs: 0, pendingVduCount: 7 },
        enrichment: {
          backfillMode: 'combined',
          embeddingEnabled: true,
          embeddingDocCount: 100,
          embeddingPendingCount: 12,
        },
      },
    }).render();
    // The template's interpolations, in source order: embedding-queue-size, vdu-queue-size, …
    const values = (out as { values: unknown[] }).values;
    expect(values[0]).toBe(12);
    expect(values[1]).toBe(7);
  });

  it('withdraws when the status authority says everything is settled (stale residue, not work)', () => {
    expect(
      host({
        worker: {
          core: { indexState: 'IDLE', pendingJobs: 0, pendingVduCount: 0 },
          enrichment: {
            backfillMode: 'idle',
            embeddingEnabled: true,
            embeddingDocCount: 100,
            embeddingPendingCount: 0,
          },
        },
      }).render(),
    ).toBe(nothing);
  });
});
