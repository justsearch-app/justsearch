// @vitest-environment happy-dom

/**
 * Render test for HealthSurface — `recommendedActions` panel
 * (slice 447-followup-live-wiring §X.12.8 Item 2.3).
 *
 * Phase 4 of §X.11.5 added the "Recommended for current conditions"
 * sub-section to HealthSurface. The HealthLitView/HealthSurface
 * mix-up surfaced via §X.12 live testing (commit f68deb830) ported
 * the panel to the rail-mounted production component, but no
 * regression test guarded the panel from silent removal during
 * future refactors. This test fills that gap.
 */

import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import './HealthSurface.js';
import type { HealthSurface } from './HealthSurface.js';
import {
  __resetAiStateForTest,
  __feedForTest,
  __tickClockForTest,
  type StatusSnapshot,
  type InferenceSnapshot,
} from '../state/aiStateStore.js';
import { formatCount } from '../display/format.js';

async function mount(): Promise<HealthSurface> {
  const el = document.createElement('jf-health-surface') as HealthSurface;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

function teardown(el: HealthSurface): void {
  el.remove();
}

describe('HealthSurface — recommendedActions panel', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockImplementation(async () => new Response('{}', { headers: { 'Content-Type': 'application/json' } })));
  });
  afterEach(() => { __resetAiStateForTest(); vi.unstubAllGlobals(); });

  // 595: a ready readiness composite fed to the ONE store → the verdict is
  // `operational`, which both header and footer consume.
  function feedReady(over: Record<string, unknown> = {}): void {
    __feedForTest({
      status: {
        worker: { core: { indexedDocuments: 5, indexState: 'IDLE', indexHealthy: true } },
        readiness: {
          composites: {
            retrieval: { state: 'READY', reasonCodes: [] },
            aiFeatures: { state: 'READY', reasonCodes: [] },
          },
        },
        ...over,
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
  }

  it('tempdoc 644: renders per-engine realized state (GPU/CPU pills) in the AI Engine card', async () => {
    __feedForTest({
      status: {
        worker: {
          core: { indexedDocuments: 5, indexState: 'IDLE', indexHealthy: true },
          gpu: {
            rerankerModelPath: '/models/onnx/reranker',
            rerankerOrtCuda: { available: true, attempted: true },
            embedBackend: 'onnx',
            embedOrtCuda: { available: false, attempted: true },
            spladeModelPath: '/models/splade',
            spladeOrtCuda: { available: false, attempted: false, failureReason: 'lazy' },
          },
        },
        readiness: {
          composites: {
            retrieval: { state: 'READY', reasonCodes: [] },
            aiFeatures: { state: 'READY', reasonCodes: [] },
          },
        },
      } as unknown as StatusSnapshot,
      // The AI Engine card (and the engine rows inside it) only render when inference is present.
      inference: { mode: 'online', starting: false, available: true } as unknown as InferenceSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const card = Array.from(el.shadowRoot?.querySelectorAll('.card.section') ?? []).find((c) =>
        c.textContent?.includes('Retrieval engines'),
      );
      expect(card, 'the AI Engine card should contain the Retrieval engines block').not.toBeUndefined();
      const text = card?.textContent ?? '';
      expect(text).toContain('Reranker');
      expect(text).toContain('Embeddings');
      expect(text).toContain('SPLADE');
      // reranker available:true → GPU; embed attempted but not available → CPU.
      expect(text).toContain('GPU');
      expect(text).toContain('CPU');
    } finally {
      teardown(el);
    }
  });

  it('renders "All healthy" when recommendedActions is empty and the verdict is operational', async () => {
    feedReady();
    const el = await mount();
    try {
      const section = el.shadowRoot?.querySelector('.card.section.recommended');
      expect(section).not.toBeNull();
      expect(section?.textContent).toContain('All systems operational');
    } finally {
      teardown(el);
    }
  });

  it('shows a fatal indexing loop explicitly as Indexing failed', async () => {
    feedReady({
      worker: { core: { indexedDocuments: 5, indexState: 'FAILED', indexHealthy: false } },
    });
    const el = await mount();
    try {
      const value = [...(el.shadowRoot?.querySelectorAll('.data-row .val') ?? [])]
        .find((node) => node.textContent?.trim() === 'Indexing failed');
      expect(value).toBeDefined();
      expect(value?.getAttribute('style')).toContain('var(--accent-danger)');
    } finally {
      teardown(el);
    }
  });

  it('595 §1.1: header and footer agree on the unknown boundary — both "Checking…", NEVER a split', async () => {
    // retrieval 'unknown' (settled) was the boundary where the header alarmed
    // ("Service degraded") while the footer greened ("✓ All systems operational").
    // With one verdict, both must read the SAME non-green, non-alarming "Checking…".
    __feedForTest({
      status: {
        worker: { core: { indexedDocuments: 5, indexState: 'IDLE', indexHealthy: true } },
        readiness: {
          composites: {
            retrieval: { state: 'UNKNOWN', reasonCodes: [] },
            aiFeatures: { state: 'READY', reasonCodes: [] },
          },
        },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const badge = el.shadowRoot?.querySelector('.header jf-status-badge');
      const footer = el.shadowRoot?.querySelector('.card.section.recommended');
      expect(badge?.textContent).toContain('Checking');
      expect(footer?.textContent).toContain('Checking');
      // The contradiction must be GONE on both surfaces.
      expect(badge?.textContent).not.toContain('Service degraded');
      expect(footer?.textContent).not.toContain('All systems operational');
    } finally {
      teardown(el);
    }
  });

  it('595 §10.3: a cosmetic degradation (LambdaMART) reads calm on both surfaces (not an alarm)', async () => {
    feedReady({
      readiness: {
        composites: {
          retrieval: { state: 'DEGRADED', reasonCodes: ['lambdamart.not_configured'] },
          aiFeatures: { state: 'READY', reasonCodes: [] },
        },
      },
    });
    const el = await mount();
    try {
      const badge = el.shadowRoot?.querySelector('.header jf-status-badge');
      expect(badge?.textContent).toContain('Reduced capability');
      expect(badge?.getAttribute('tone')).toBe('info'); // calm, not 'warning'
      const footer = el.shadowRoot?.querySelector('.card.section.recommended');
      expect(footer?.textContent).toContain('Reduced capability');
    } finally {
      teardown(el);
    }
  });

  it('§2.C: shows "Connecting…" (not all-operational) before health data arrives', async () => {
    const el = await mount();
    try {
      // Fresh mount, no poll completed → must NOT fail-open to "operational".
      const section = el.shadowRoot?.querySelector('.card.section.recommended');
      expect(section?.textContent).toContain('Connecting');
      expect(section?.textContent).not.toContain('All systems operational');
    } finally {
      teardown(el);
    }
  });

  it('renders one catalog action per entry when recommendedActions is populated', async () => {
    const el = await mount();
    try {
      el.recommendedActions = new Map([
        ['schema.reindex-required|worker.schema', { target: 'core.reindex', args: {} }],
        ['index.unavailable|worker', { target: 'core.rebuild-index', args: {} }],
      ]);
      await el.updateComplete;

      const section = el.shadowRoot?.querySelector('.card.section.recommended');
      expect(section).not.toBeNull();

      const buttons = el.shadowRoot?.querySelectorAll(
        '.card.section.recommended .recommended-action',
      );
      expect(buttons?.length).toBe(2);

      const buttonText = Array.from(buttons ?? [])
        .map((b) => b.textContent?.trim())
        .join(' | ');
      // §2.A: the visible label is humanized via present({kind:'condition'}) —
      // the raw dotted condition id must NOT reach the user (Q7 leak class).
      expect(buttonText).toContain('Schema Reindex Required');
      expect(buttonText).toContain('Index Unavailable');
      expect(buttonText).not.toContain('schema.reindex-required');
      expect(buttonText).not.toContain('index.unavailable');
    } finally {
      teardown(el);
    }
  });

  it('renders entries in sorted (conditionId, subject) order for stable display', async () => {
    const el = await mount();
    try {
      // Insertion order is reverse-alphabetical; the render should sort by key.
      el.recommendedActions = new Map([
        ['z.condition|sub', { target: 'core.z-op', args: {} }],
        ['a.condition|sub', { target: 'core.a-op', args: {} }],
      ]);
      await el.updateComplete;

      const buttons = el.shadowRoot?.querySelectorAll(
        '.card.section.recommended .recommended-action',
      );
      const texts = Array.from(buttons ?? []).map((b) =>
        b.textContent?.trim() ?? '',
      );
      expect(texts.length).toBe(2);
      // a.* sorts before z.*; assert button ordering reflects key sort.
      // Labels are humanized (present({kind:'condition'})): "A Condition" / "Z Condition".
      expect(texts[0]).toContain('A Condition');
      expect(texts[1]).toContain('Z Condition');
    } finally {
      teardown(el);
    }
  });

  it('B7/B2: reads observed-state from the store; an impairing degraded readiness → "Service degraded"', async () => {
    // Feed the ONE store (no local /api/status poll) with an impairing degraded
    // retrieval composite. HealthSurface must pick it up via its store
    // subscription and refuse to claim "operational" (no reasonCodes ⇒ warn).
    __feedForTest({
      status: {
        worker: { core: { indexedDocuments: 5, indexState: 'IDLE', indexHealthy: true } },
        readiness: { composites: { retrieval: { state: 'DEGRADED', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const section = el.shadowRoot?.querySelector('.card.section.recommended');
      expect(section?.textContent).toContain('Service degraded');
      expect(section?.textContent).not.toContain('All systems operational');
    } finally {
      teardown(el);
    }
  });

  it('recommendation title includes the conditionId and subject for accessibility', async () => {
    const el = await mount();
    try {
      el.recommendedActions = new Map([
        ['schema.reindex-required|worker.schema', { target: 'core.reindex', args: {} }],
      ]);
      await el.updateComplete;

      const button = el.shadowRoot?.querySelector(
        '.card.section.recommended .recommended-action',
      ) as HTMLButtonElement | null;
      expect(button).not.toBeNull();
      expect(button?.title).toContain('schema.reindex-required');
      expect(button?.title).toContain('worker.schema');
    } finally {
      teardown(el);
    }
  });

  it('595 §4.3: during a worker-restart provisional, the Files/Size stat cards show "…" not a settled "0"', async () => {
    // indexState UNAVAILABLE ⇒ the worker-down fallback (docs/size = 0). Those
    // worker-sourced cards must render "…" (provisional), never a settled "0".
    // Memory is Head-process state and stays.
    __feedForTest({
      status: {
        worker: {
          core: { indexedDocuments: 0, indexState: 'UNAVAILABLE', indexHealthy: false },
        },
        memoryUsedBytes: 1234567,
        memoryMaxBytes: 8000000,
        readiness: { composites: { retrieval: { state: 'UNKNOWN', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const values = Array.from(el.shadowRoot?.querySelectorAll('.card-value') ?? []).map(
        (v) => v.textContent?.trim() ?? '',
      );
      // Files (first) + Size (second) provisional → "…", not "0" / "0 B".
      expect(values[0]).toBe('…');
      expect(values[0]).not.toBe('0');
      expect(values[1]).toBe('…');
      // Memory (third) is Head-process state — still a real value.
      expect(values[2]).not.toBe('…');
      expect(values[2]).toMatch(/B$/); // formatBytes output (e.g. "1.2 MB")
    } finally {
      teardown(el);
    }
  });

  it('595 §15.2 (E4): an OVERDUE rebuild escalates the header to "taking longer than expected" (warning)', async () => {
    __feedForTest({
      status: {
        worker: {
          core: { indexedDocuments: 0, indexState: 'IDLE', indexHealthy: true },
          migration: {
            migrationState: 'MIGRATING',
            activeGenerationId: 'g1',
            buildingGenerationId: 'g2',
            servingSearchGenerationId: 'g1',
            servingIngestGenerationId: 'g1',
            migrationSwitchingAgeMs: 120000,
            migrationSwitchingMaxDurationMs: 60000,
          },
        },
        readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const badge = el.shadowRoot?.querySelector('.header jf-status-badge');
      expect(badge?.textContent).toContain('taking longer than expected');
      expect(badge?.getAttribute('tone')).toBe('warning'); // escalated, no longer calm
    } finally {
      teardown(el);
    }
  });

  // 595 §15.3 (E2) — last-settled retention: Files/Size keep the last good value (dimmed)
  // across a provisional window instead of collapsing to "…".
  it('E2: after a settled poll, a worker-restart provisional shows the last-known Files (not "…")', async () => {
    // 1) a settled poll stamps the retained value.
    __feedForTest({
      status: {
        worker: { core: { indexedDocuments: 1234, indexSizeBytes: 4096, indexState: 'IDLE', indexHealthy: true } },
        readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    // 2) the worker restarts: a successful poll returns the fallback (0 / UNAVAILABLE).
    __feedForTest({
      status: {
        worker: { core: { indexedDocuments: 0, indexState: 'UNAVAILABLE', indexHealthy: false } },
        readiness: { composites: { retrieval: { state: 'UNKNOWN', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const values = Array.from(el.shadowRoot?.querySelectorAll('.card-value') ?? []).map(
        (v) => v.textContent?.trim() ?? '',
      );
      expect(values[0]).toBe(formatCount(1234)); // last-known Files, NOT "…" and NOT "0"
      expect(values[0]).not.toBe('…');
      const subs = Array.from(el.shadowRoot?.querySelectorAll('.card-sub') ?? []).map(
        (s) => s.textContent?.trim() ?? '',
      );
      expect(subs).toContain('Last known');
    } finally {
      teardown(el);
    }
  });

  it('E2: when the settled poll had no size, Size shows "…" while Files shows the last-known count', async () => {
    // A settled snapshot with a doc count but no indexSizeBytes → size retained as null. During a
    // later provisional window Files shows the last-known count; Size must NOT assert a fake "0 B".
    __feedForTest({
      status: {
        worker: { core: { indexedDocuments: 1234, indexState: 'IDLE', indexHealthy: true } },
        readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    __feedForTest({
      status: {
        worker: { core: { indexedDocuments: 0, indexState: 'UNAVAILABLE', indexHealthy: false } },
        readiness: { composites: { retrieval: { state: 'UNKNOWN', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const values = Array.from(el.shadowRoot?.querySelectorAll('.card-value') ?? []).map(
        (v) => v.textContent?.trim() ?? '',
      );
      expect(values[0]).toBe(formatCount(1234)); // Files last-known
      expect(values[1]).toBe('…'); // Size: no observed size → not "0 B"
    } finally {
      teardown(el);
    }
  });

  // 595 §15.3 (E3) — an indicative rebuild progress bar during a MIGRATING window.
  it('E3: a rebuild renders a progressbar with the clamped building/active fraction', async () => {
    __feedForTest({
      status: {
        worker: {
          core: { indexedDocuments: 0, indexState: 'IDLE', indexHealthy: true },
          migration: {
            migrationState: 'MIGRATING',
            activeGenerationId: 'g1',
            buildingGenerationId: 'g2',
            servingSearchGenerationId: 'g1',
            servingIngestGenerationId: 'g1',
            activeIndexedDocuments: 1000,
            buildingIndexedDocuments: 600,
          },
        },
        readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const bar = el.shadowRoot?.querySelector('[role="progressbar"]');
      expect(bar).not.toBeNull();
      expect(bar?.getAttribute('aria-valuenow')).toBe('60'); // 600/1000, clamped
      expect(bar?.textContent).toContain('Rebuilding…');
      const inner = bar?.querySelector('.progress-bar') as HTMLElement | null;
      expect(inner?.style.width).toBe('60%');
    } finally {
      teardown(el);
    }
  });

  it('E3: no progressbar when settled', async () => {
    feedReady();
    const el = await mount();
    try {
      expect(el.shadowRoot?.querySelector('[role="progressbar"]')).toBeNull();
    } finally {
      teardown(el);
    }
  });
});

// 630 D1 — the Queue card's calm status vocabulary + the explicit "Up to date" terminal close.
describe('HealthSurface — Queue card status vocabulary (630 D1)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockImplementation(async () => new Response('{}', { headers: { 'Content-Type': 'application/json' } })));
  });
  afterEach(() => { __resetAiStateForTest(); vi.unstubAllGlobals(); });

  function feed(core: Record<string, unknown>): void {
    __feedForTest({
      status: {
        worker: { core: { indexState: 'IDLE', ...core } },
        readiness: {
          composites: {
            retrieval: { state: 'READY', reasonCodes: [] },
            aiFeatures: { state: 'READY', reasonCodes: [] },
          },
        },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
  }

  const queueSubs = (el: HealthSurface) =>
    Array.from(el.shadowRoot?.querySelectorAll('.card-sub') ?? []).map((s) => s.textContent?.trim() ?? '');

  // Tempdoc 813 §4 — "Up to date" is now the COVERAGE-complete close, not the job-drain close. This
  // case keeps its original intent (idle + verified-healthy ⇒ the terminal trust state, never a bare
  // "Idle") and now exercises it with nothing left to enrich, which is when the claim is true.
  it('idle + verified-healthy index ⇒ "Up to date" (the terminal trust state), never "Idle"', async () => {
    feed({ indexedDocuments: 5, pendingJobs: 0, indexHealthy: true });
    const el = await mount();
    try {
      const subs = queueSubs(el);
      expect(subs).toContain('Up to date');
      expect(subs).not.toContain('Idle');
    } finally {
      teardown(el);
    }
  });

  it('idle but NOT confirmed-healthy ⇒ stays honest "Idle" (no false "Up to date")', async () => {
    feed({ indexedDocuments: 5, pendingJobs: 0, indexHealthy: false });
    const el = await mount();
    try {
      const subs = queueSubs(el);
      expect(subs).toContain('Idle');
      expect(subs).not.toContain('Up to date');
    } finally {
      teardown(el);
    }
  });

  // 809 finding 1 — the terminal trust close is gated on COVERAGE, not on job-queue drain. The job
  // queue draining only proves extraction + the Lucene write finished; the enrichment backfill that
  // makes semantic search work runs afterwards, and "Up to date" during it claims a capability the
  // system does not have. Supersedes the 630 D1 pin above, which asserted the terminal close from
  // `pendingJobs === 0 && indexHealthy` alone.
  it('809: idle + healthy BUT the enrichment backfill is running ⇒ no "Up to date"', async () => {
    __feedForTest({
      status: {
        worker: {
          core: { indexState: 'IDLE', indexedDocuments: 5, pendingJobs: 0, indexHealthy: true },
          // 809 finding 9's trap: the doc-level tier is clean, the passage tier is not.
          // `chunkDocCount` is the passage tier's DENOMINATOR — the wire always carries it beside
          // the pending count (one `ChunkCoverageView` message), and since 813 §3b the card's phase
          // comes from the coverage ratio, so a pending count without its denominator is not the
          // shape this trap ever arrives in.
          enrichment: {
            embeddingEnabled: true,
            embeddingPendingCount: 0,
            embeddingCoveragePercent: 100,
            chunk: {
              chunkDocCount: 2000,
              chunkEmbeddingPendingCount: 1554,
              chunkVectorsReady: false,
            },
          },
        },
        readiness: {
          composites: {
            retrieval: { state: 'READY', reasonCodes: [] },
            aiFeatures: { state: 'READY', reasonCodes: [] },
          },
        },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const subs = queueSubs(el);
      expect(subs).not.toContain('Up to date');
      expect(subs).not.toContain('Idle');
      // 446 of 2000 passages settled ⇒ the card names the work AND its honest percent.
      expect(subs).toContain('Building semantic search — 22%');
    } finally {
      teardown(el);
    }
  });

  /**
   * Round-15 F1, on the Queue card: the same terminal close, reached the same wrong way. With no
   * embedding model nothing can be queued, so job-drain + `indexHealthy` alone used to land on the
   * coverage-complete "Up to date" over an index with zero vectors. Enrichment block verbatim from
   * the round's captured `/api/status` (`evidence/api-history/20260807-011454/api-api-status.json`).
   */
  it('F1: no embedding model ⇒ no "Up to date" close over coverage that was never built', async () => {
    __feedForTest({
      status: {
        worker: {
          core: { indexState: 'IDLE', indexedDocuments: 5, pendingJobs: 0, indexHealthy: true },
          enrichment: {
            backfillMode: 'idle',
            embeddingEnabled: false,
            spladeEnabled: false,
            nerEnabled: false,
            embeddingDocCount: 5,
            embeddingPendingCount: 5,
            embeddingCoveragePercent: 0,
            spladeDocCount: 5,
            spladePendingCount: 5,
            pendingNerCount: 5,
            completedNerCount: 0,
            chunk: { chunkDocCount: 2, chunkEmbeddingPendingCount: 2, chunkVectorsReady: false },
          },
        },
        readiness: {
          composites: {
            retrieval: { state: 'READY', reasonCodes: [] },
            aiFeatures: { state: 'READY', reasonCodes: [] },
          },
        },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const subs = queueSubs(el);
      expect(subs).not.toContain('Up to date');
      expect(subs).toContain('Semantic search waiting for AI install');
      // Nothing is progressing, so no percent and no "Building…" present tense.
      expect(subs.join(' ')).not.toContain('Building semantic search');
      expect(subs.join(' ')).not.toContain('%');
    } finally {
      teardown(el);
    }
  });

  it('queued work ⇒ "Indexing"', async () => {
    feed({ indexedDocuments: 5, pendingJobs: 5, indexHealthy: true });
    const el = await mount();
    try {
      const subs = queueSubs(el);
      expect(subs).toContain('Indexing');
      expect(subs).not.toContain('Up to date');
    } finally {
      teardown(el);
    }
  });

  // Tempdoc 813 §4 — the two-tier terminal close. Job drain buys the KEYWORD tier only; the semantic
  // layers keep building afterwards, and `indexState` stays IDLE throughout that window. Claiming
  // "Up to date" there was the §1d completion lie ("a completion claim is a capability claim").
  function feedEnrichment(enrichment: Record<string, unknown>): void {
    __feedForTest({
      status: {
        worker: {
          core: { indexState: 'IDLE', indexedDocuments: 5, pendingJobs: 0, indexHealthy: true },
          enrichment,
        },
        readiness: {
          composites: {
            retrieval: { state: 'READY', reasonCodes: [] },
            aiFeatures: { state: 'READY', reasonCodes: [] },
          },
        },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
  }

  it('813: jobs drained but coverage incomplete ⇒ "Building semantic search — N%", never "Up to date"', async () => {
    feedEnrichment({
      backfillMode: 'combined',
      embeddingEnabled: true,
      embeddingDocCount: 100,
      embeddingPendingCount: 36,
    });
    const el = await mount();
    try {
      const subs = queueSubs(el);
      expect(subs).toContain('Building semantic search — 64%');
      expect(subs).not.toContain('Up to date');
      expect(subs).not.toContain('Idle');
    } finally {
      teardown(el);
    }
  });

  it('813: the chunk tier alone can hold the card off the terminal claim', async () => {
    feedEnrichment({
      backfillMode: 'idle',
      embeddingEnabled: true,
      embeddingDocCount: 100,
      embeddingPendingCount: 0,
      chunk: { chunkDocCount: 100, chunkEmbeddingPendingCount: 50 },
    });
    const el = await mount();
    try {
      const subs = queueSubs(el);
      expect(subs).toContain('Building semantic search — 75%');
      expect(subs).not.toContain('Up to date');
    } finally {
      teardown(el);
    }
  });

  it('813: coverage complete ⇒ the terminal "Up to date" returns', async () => {
    feedEnrichment({
      backfillMode: 'idle',
      embeddingEnabled: true,
      embeddingDocCount: 100,
      embeddingPendingCount: 0,
      chunk: { chunkDocCount: 100, chunkEmbeddingPendingCount: 0 },
    });
    const el = await mount();
    try {
      const subs = queueSubs(el);
      expect(subs).toContain('Up to date');
      expect(subs.some((s) => s.startsWith('Building semantic search'))).toBe(false);
    } finally {
      teardown(el);
    }
  });

  it('energy-pause still wins precedence over the working label', async () => {
    feed({ indexedDocuments: 5, pendingJobs: 5, indexHealthy: true, power: undefined });
    // power lives at the top level of the status, not under core — feed it directly.
    __feedForTest({
      status: {
        worker: { core: { indexState: 'IDLE', indexedDocuments: 5, pendingJobs: 5, indexHealthy: true } },
        power: { energyReduced: true },
        readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const subs = queueSubs(el);
      expect(subs).toContain('Paused — saving energy');
      expect(subs).not.toContain('Indexing');
    } finally {
      teardown(el);
    }
  });
});
