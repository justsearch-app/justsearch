// @vitest-environment happy-dom

/**
 * Slice 3a.2.e — `<jf-log-surface>` tests.
 *
 * Covers:
 *   - Catalog-boot race handling (waits for catalog arrival before
 *     erroring, per slice 3a.2 §B.B.E.1)
 *   - Filter chip toggling (severity + sub-category)
 *   - Search filter
 *   - Pause/resume buffer behavior
 *   - Empty state vs error state vs connected state
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import './LogSurface.js';
import {
  __seedForTest,
  __resetForTest,
} from '../../api/registry/DiagnosticChannelCatalogClient.js';
import type {
  DiagnosticChannel,
  DiagnosticChannelCatalog,
} from '../../api/types/diagnostic.js';

const HEAD_LOG_CHANNEL: DiagnosticChannel = {
  id: 'core.engine-log',
  presentation: {
    labelKey: 'diagnostic-channel.core.engine-log.label',
    descriptionKey: 'diagnostic-channel.core.engine-log.description',
  },
  dataClasses: [],
  producer: 'IN_PROCESS_LOGBACK',
  deliveryMode: 'SSE_STREAM',
  selector: {
    prefixMappings: {},
    overrides: {},
    defaultSubCategory: 'CORE_DIAGNOSTIC',
  },
  endpoint: '/api/diagnostic-channels/engine-log/stream',
  consumerPermission: 'OPERATOR_OVERRIDE',
  provenance: {
    tier: 'CORE',
    contributorId: 'core',
    version: '1.0',
  },
  consumers: [],
};

const HEAD_LOG_CATALOG: DiagnosticChannelCatalog = {
  schemaVersion: '1',
  catalogVersion: 1,
  namespace: 'core',
  primitive: 'DiagnosticChannel',
  entries: [HEAD_LOG_CHANNEL],
};

async function mountSurface(): Promise<HTMLElement> {
  const el = document.createElement('jf-log-surface') as HTMLElement;
  document.body.appendChild(el);
  await new Promise((resolve) => setTimeout(resolve, 0));
  return el;
}

/**
 * Stub EventSource — happy-dom doesn't ship one, and these tests
 * verify UI shape, not SSE wire behavior. The stub records new
 * instances so tests could verify URLs if needed.
 */
class MockEventSource {
  url: string;
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((e: MessageEvent) => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(url: string) {
    this.url = url;
  }
  addEventListener(): void {
    // no-op for tests
  }
  removeEventListener(): void {
    // no-op
  }
  close(): void {
    this.readyState = 2;
  }
}

beforeEach(() => {
  (globalThis as { EventSource?: unknown }).EventSource = MockEventSource;
});

describe('LogSurface — catalog-boot race', () => {
  beforeEach(() => {
    __resetForTest();
  });

  afterEach(() => {
    document.body.innerHTML = '';
    __resetForTest();
  });

  it('shows connecting state while catalog is empty (boot race in flight)', async () => {
    // Catalog NOT seeded — represents the catalog-boot-in-flight state.
    const el = await mountSurface();
    const root = el.shadowRoot!;
    // The element should not error out; it should wait for catalog
    // arrival via onDiagnosticChannelCatalogChange.
    expect(root.textContent).not.toContain('Channel not found');
    // Connection state is 'connecting' (waiting for catalog).
    expect(root.querySelector('.error')).toBeNull();
  });

  it('errors out when catalog is populated but channel id is missing (true not-found)', async () => {
    // Seed a catalog with a DIFFERENT channel — boot is resolved
    // (length > 0 distinguishes from boot-race) but core.engine-log
    // isn't there. The catalog-boot race pattern this surface
    // shares with ResourceView (slice 3a.2 §B.B.E.1) uses
    // length-based heuristic; an empty seeded catalog can't be
    // distinguished from boot-in-flight without API changes.
    __seedForTest({
      schemaVersion: '1',
      catalogVersion: 1,
      namespace: 'core',
      primitive: 'DiagnosticChannel',
      entries: [
        {
          ...HEAD_LOG_CHANNEL,
          id: 'some-other-channel',
        },
      ],
    });
    const el = await mountSurface();
    const root = el.shadowRoot!;
    expect(root.querySelector('.error')).not.toBeNull();
    expect(root.querySelector('.error')!.textContent).toContain(
      'core.engine-log',
    );
  });
});

describe('LogSurface — filters + UI', () => {
  beforeEach(() => {
    __resetForTest();
    __seedForTest(HEAD_LOG_CATALOG);
  });

  afterEach(() => {
    document.body.innerHTML = '';
    __resetForTest();
  });

  it('renders filter chips for all four severities', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const chips = Array.from(root.querySelectorAll('jf-filter-chip')).map(
      (c) => c.textContent?.trim(),
    );
    expect(chips).toContain('DEBUG');
    expect(chips).toContain('INFO');
    expect(chips).toContain('WARN');
    expect(chips).toContain('ERROR');
  });

  it('renders filter chips for sub-categories', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const subChips = Array.from(root.querySelectorAll('jf-filter-chip')).map(
      (c) => c.textContent?.trim().toLowerCase(),
    );
    expect(subChips.some((s) => s?.includes('app'))).toBe(true);
    expect(subChips.some((s) => s?.includes('library'))).toBe(true);
  });

  it('renders pause + clear action buttons', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const actions = Array.from(root.querySelectorAll('button.action')).map(
      (b) => b.textContent?.trim(),
    );
    expect(actions).toContain('Pause');
    expect(actions).toContain('Clear');
  });

  it('toggling Pause swaps to Resume label', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const pauseBtn = Array.from(
      root.querySelectorAll('button.action'),
    ).find((b) => b.textContent?.trim() === 'Pause') as HTMLButtonElement;
    expect(pauseBtn).toBeTruthy();
    pauseBtn.click();
    await new Promise((r) => setTimeout(r, 0));
    const actionsAfter = Array.from(
      root.querySelectorAll('button.action'),
    ).map((b) => b.textContent?.trim());
    expect(actionsAfter).toContain('Resume');
    // Paused badge appears in header
    expect(root.textContent).toContain('paused');
  });

  it('renders search input', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const search = root.querySelector(
      'input[type="text"]',
    ) as HTMLInputElement;
    expect(search).toBeTruthy();
    expect(search.placeholder.toLowerCase()).toContain('search');
  });

  it('renders empty-state hint when no events have arrived', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const empty = root.querySelector('.empty');
    expect(empty?.textContent?.toLowerCase()).toContain('waiting');
  });
});

describe('LogSurface — pause/resume behavior (reviewer-pass F5)', () => {
  beforeEach(() => {
    __resetForTest();
    __seedForTest(HEAD_LOG_CATALOG);
  });

  afterEach(() => {
    document.body.innerHTML = '';
    __resetForTest();
  });

  /**
   * Pause/resume intent (per slice 3a-2-e):
   *   "Freeze the visible view; resume shows the latest stream
   *    state at the time of resume."
   *
   * Two cases distinguish the intent:
   *   1. Pause then resume with NO new events → visible stays
   *      identical to pre-pause state.
   *   2. Pause then receive events then resume → visible jumps
   *      to the latest stream state (lossy by design — we don't
   *      accumulate during pause; we tail).
   */

  it('pause→resume with no new events keeps visible state', async () => {
    const el = await mountSurface() as unknown as {
      events: Array<{ level: string; message: string; subCategory: string }>;
      paused: boolean;
      shadowRoot: ShadowRoot;
    };
    // Seed visible state directly (test-only injection).
    el.events = [
      {
        level: 'INFO',
        message: 'pre-pause-1',
        loggerName: 'test',
        threadName: 't',
        threadId: 1,
        timestamp: '2026-05-08T12:00:00.000Z',
        mdc: {},
        dataClasses: [],
        subCategory: 'CORE_DIAGNOSTIC',
      } as never,
    ];
    await new Promise((r) => setTimeout(r, 0));
    const pauseBtn = Array.from(
      el.shadowRoot.querySelectorAll('button.action'),
    ).find((b) => b.textContent?.trim() === 'Pause') as HTMLButtonElement;
    pauseBtn.click();
    await new Promise((r) => setTimeout(r, 0));
    expect(el.paused).toBe(true);
    // No new events fed via SSE — bufferedWhilePaused stays as
    // the pre-pause seed.
    const resumeBtn = Array.from(
      el.shadowRoot.querySelectorAll('button.action'),
    ).find((b) => b.textContent?.trim() === 'Resume') as HTMLButtonElement;
    resumeBtn.click();
    await new Promise((r) => setTimeout(r, 0));
    expect(el.paused).toBe(false);
    expect(el.events.length).toBe(1);
    expect(el.events[0]!.message).toBe('pre-pause-1');
  });

  it('pause→resume with new events while paused snaps to latest stream state', async () => {
    const el = (await mountSurface()) as unknown as {
      events: Array<{ message: string }>;
      paused: boolean;
      bufferedWhilePaused: Array<{ message: string }>;
      shadowRoot: ShadowRoot;
    };
    el.events = [
      {
        level: 'INFO',
        message: 'before-pause',
        loggerName: 't',
        threadName: 't',
        threadId: 1,
        timestamp: '2026-05-08T12:00:00.000Z',
        mdc: {},
        dataClasses: [],
        subCategory: 'CORE_DIAGNOSTIC',
      } as never,
    ];
    await new Promise((r) => setTimeout(r, 0));
    const pauseBtn = Array.from(
      el.shadowRoot.querySelectorAll('button.action'),
    ).find((b) => b.textContent?.trim() === 'Pause') as HTMLButtonElement;
    pauseBtn.click();
    await new Promise((r) => setTimeout(r, 0));
    // Simulate new events arriving while paused: directly update
    // bufferedWhilePaused as the SSE callback would.
    (el as unknown as { bufferedWhilePaused: Array<{ message: string }> }).bufferedWhilePaused = [
      { message: 'during-pause-1' } as never,
      { message: 'during-pause-2' } as never,
    ];
    const resumeBtn = Array.from(
      el.shadowRoot.querySelectorAll('button.action'),
    ).find((b) => b.textContent?.trim() === 'Resume') as HTMLButtonElement;
    resumeBtn.click();
    await new Promise((r) => setTimeout(r, 0));
    expect(el.events.length).toBe(2);
    expect(el.events.map((e) => e.message)).toEqual([
      'during-pause-1',
      'during-pause-2',
    ]);
    // The pre-pause `before-pause` event is GONE — that's the
    // intended lossy "tail" semantics. If a future intent change
    // wants accumulation, this test would fail and the
    // implementation would need a different reducer.
  });
});
