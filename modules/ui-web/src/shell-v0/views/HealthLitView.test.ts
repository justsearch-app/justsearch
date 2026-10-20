// @vitest-environment happy-dom

/**
 * Render tests for HealthLitView (slice 3a.2).
 *
 * Uses controlled fetch SSE responses so the substrate's SSE consumption is
 * exercised end-to-end (frame parsing → reducer → re-render →
 * StatusCard mount) without a network.
 *
 * Covers:
 *  - Initial render with no apiBase → empty conditions, no stream
 *    started.
 *  - Snapshot frame populates conditions list and renders one
 *    StatusCard per condition.
 *  - condition-added / condition-modified / condition-removed deltas
 *    keep the conditions Map in sync.
 *  - occurrence-appended frames append to the recent-occurrences
 *    list (capped to 50 internally; rendered to top 10).
 *  - reset frame clears the local state.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchSseHarness, flushStreamWork } from '../../test/fetchSse.js';
import './HealthLitView.js';
// Side-effect: register <jf-status-card> so the dispatched child mounts.
import '../components/StatusCard.js';
import type { HealthLitView } from './HealthLitView.js';
import type { HealthEvent } from '../../api/domains/health.js';

let streams: ReturnType<typeof fetchSseHarness>;
beforeEach(() => {
  streams = fetchSseHarness();
  vi.stubGlobal('fetch', streams.fetch);
});
afterEach(() => {
  document.body.replaceChildren();
  vi.unstubAllGlobals();
});

const FAKE_SOURCE = {
  serviceName: 'head',
  serviceInstanceId: 'instance-1',
  serviceVersion: '1.0',
};

function condition(
  id: string,
  subject: string,
  reason = 'TestReason',
  severity: 'INFO' | 'WARNING' | 'ERROR' = 'WARNING',
): HealthEvent {
  return {
    id,
    timestamp: '2026-05-05T12:00:00Z',
    source: FAKE_SOURCE,
    severity,
    body: {
      kind: 'condition',
      subject,
      status: 'TRUE',
      reason,
      lastTransitionTime: '2026-05-05T12:00:00Z',
    },
  };
}

function occurrence(id: string, attrs: Record<string, unknown>): HealthEvent {
  return {
    id,
    timestamp: '2026-05-05T12:00:00Z',
    source: FAKE_SOURCE,
    severity: 'INFO',
    body: { kind: 'lifecycle', attributes: attrs },
  };
}

async function mount(apiBase: string): Promise<HealthLitView> {
  const el = document.createElement('jf-health-view') as HealthLitView;
  if (apiBase) el.apiBase = apiBase;
  document.body.appendChild(el);
  await el.updateComplete;
  await flushStreamWork();
  return el;
}

describe('HealthLitView render', () => {
  it('renders an empty state when no apiBase is set', async () => {
    const el = await mount('');
    expect(el.shadowRoot?.textContent).toContain('No active conditions');
    expect(el.shadowRoot?.textContent).toContain('No occurrences yet');
    el.remove();
  });

  it('prefixes health SSE with api-base for Tauri asset origins', async () => {
    const el = await mount('http://127.0.0.1:33221');
    const fake = streams.connections.find((connection) => !connection.closed)!;
    expect(fake.url).toBe('http://127.0.0.1:33221/api/health/events/stream');
    el.remove();
  });

  it('snapshot frame populates Conditions list and renders one StatusCard per condition', async () => {
    const el = await mount('http://test');
    const fake = streams.connections.find((connection) => !connection.closed)!;
    await fake.emitFrame({
      streamId: 'health-event/v1',
      frameKind: 'LIFECYCLE',
      seq: 1,
      ts: '2026-05-05T12:00:00Z',
      payload: {
        kind: 'snapshot',
        conditions: [
          condition('worker.handshake', 'WorkerHandshake', 'WorkerOffline'),
          condition('index.unavailable', 'IndexAvailable', 'IndexClosed'),
        ],
        occurrences: [],
      },
      resumeToken: 'tok-1',
    });
    await el.updateComplete;
    const cards = el.shadowRoot?.querySelectorAll('jf-status-card');
    expect(cards?.length).toBe(2);
    el.remove();
  });

  it('condition-added / condition-modified / condition-removed deltas keep state in sync', async () => {
    const el = await mount('http://test');
    const fake = streams.connections.find((connection) => !connection.closed)!;

    await fake.emitFrame({
      streamId: 'h/v1',
      frameKind: 'UPDATE',
      seq: 1,
      ts: '...',
      payload: {
        kind: 'condition-added',
        event: condition('a', 'SubjA'),
      },
      resumeToken: 't1',
    });
    await el.updateComplete;
    expect(el.shadowRoot?.querySelectorAll('jf-status-card').length).toBe(1);

    await fake.emitFrame({
      streamId: 'h/v1',
      frameKind: 'UPDATE',
      seq: 2,
      ts: '...',
      payload: {
        kind: 'condition-modified',
        event: condition('a', 'SubjAModified', 'NewReason'),
      },
      resumeToken: 't2',
    });
    await el.updateComplete;
    let cards = el.shadowRoot?.querySelectorAll('jf-status-card');
    expect(cards?.length).toBe(1);
    expect(cards?.[0]?.getAttribute('subject')).toBe('SubjAModified');

    await fake.emitFrame({
      streamId: 'h/v1',
      frameKind: 'UPDATE',
      seq: 3,
      ts: '...',
      payload: {
        kind: 'condition-removed',
        event: condition('a', 'SubjA'),
      },
      resumeToken: 't3',
    });
    await el.updateComplete;
    cards = el.shadowRoot?.querySelectorAll('jf-status-card');
    expect(cards?.length).toBe(0);
    el.remove();
  });

  it('blind-monitor UNKNOWN condition renders calm/info with legible wording (tempdoc 600 Design B)', async () => {
    const el = await mount('http://test');
    const fake = streams.connections.find((connection) => !connection.closed)!;
    const blind: HealthEvent = {
      id: 'monitor.unobservable',
      timestamp: '2026-05-05T12:00:00Z',
      source: FAKE_SOURCE,
      severity: 'INFO',
      body: {
        kind: 'condition',
        subject: 'head.memory',
        status: 'UNKNOWN',
        reason: 'MetricUnavailable',
        message: "The 'memory-pressure' check can't read its data yet.",
        lastTransitionTime: '2026-05-05T12:00:00Z',
      },
    };
    await fake.emitFrame({
      streamId: 'h/v1',
      frameKind: 'UPDATE',
      seq: 1,
      ts: '...',
      payload: { kind: 'condition-added', event: blind },
      resumeToken: 't1',
    });
    await el.updateComplete;
    const card = el.shadowRoot?.querySelector('jf-status-card');
    expect(card).not.toBeNull();
    // Calm/info tone — a blind monitor is diagnostic, NOT an alarm.
    expect(card?.getAttribute('severity')).toBe('INFO');
    // Legible wording, not the raw PascalCase reason code.
    expect(card?.getAttribute('reason')).toBe('Cannot evaluate yet');
    expect(card?.getAttribute('subject')).toBe('head.memory');
    el.remove();
  });

  it('occurrence-appended frames render under Recent occurrences', async () => {
    const el = await mount('http://test');
    const fake = streams.connections.find((connection) => !connection.closed)!;
    for (let i = 0; i < 3; i++) {
      await fake.emitFrame({
        streamId: 'h/v1',
        frameKind: 'UPDATE',
        seq: i + 1,
        ts: '...',
        payload: {
          kind: 'occurrence-appended',
          event: occurrence(`evt.${i}`, { detail: `value-${i}` }),
        },
        resumeToken: `t${i}`,
      });
    }
    await el.updateComplete;
    const occurrences = el.shadowRoot?.querySelectorAll('.occurrence');
    expect(occurrences?.length).toBe(3);
    el.remove();
  });

  it('reset frame clears local state', async () => {
    const el = await mount('http://test');
    const fake = streams.connections.find((connection) => !connection.closed)!;
    await fake.emitFrame({
      streamId: 'h/v1',
      frameKind: 'LIFECYCLE',
      seq: 1,
      ts: '...',
      payload: {
        kind: 'snapshot',
        conditions: [condition('a', 'SubjA')],
        occurrences: [occurrence('o', { x: 1 })],
      },
      resumeToken: 't1',
    });
    await el.updateComplete;
    expect(el.shadowRoot?.querySelectorAll('jf-status-card').length).toBe(1);

    await fake.emitFrame({
      streamId: 'h/v1',
      frameKind: 'LIFECYCLE',
      seq: 2,
      ts: '...',
      payload: { kind: 'reset' },
      resumeToken: 't2',
    });
    await el.updateComplete;
    expect(el.shadowRoot?.querySelectorAll('jf-status-card').length).toBe(0);
    expect(el.shadowRoot?.querySelectorAll('.occurrence').length).toBe(0);
    el.remove();
  });

  it('connection badge debounces the SSE channel (no flicker to a hard down) — 604 Move B', async () => {
    const el = await mount('http://test');
    const fake = streams.connections.find((connection) => !connection.closed)!;
    // 604 — the badge does NOT flip to a hard "down" the instant the channel isn't open yet: within
    // the debounce grace it stays "Connected" (info), so a fast self-heal never flickers the badge.
    let dot = el.shadowRoot?.querySelector('jf-status-dot');
    expect(dot?.getAttribute('tone')).toBe('info');

    await fake.open();
    await el.updateComplete;
    dot = el.shadowRoot?.querySelector('jf-status-dot');
    expect(dot?.getAttribute('tone')).toBe('info');
    el.remove();
  });

  it('connection badge shows a soft "paused" (warning, not error) when SSE is down but the backend is reachable — 604 Move B', async () => {
    const el = await mount('http://test');
    const fake = streams.connections.find((connection) => !connection.closed)!;
    await fake.open();
    await el.updateComplete;
    // The SSE channel drops and stays down past the debounce window; the /api/status poll still
    // reports the backend reachable. This test asserts that condition directly rather than relying
    // on aiStateStore's real global singleton, which is unstarted in a unit test (startAiStateStore
    // is only ever called from the presentation-demo debug route, not this component) — an unstarted
    // store's `connection.reachable` is `false` by design (computeReachability in aiStateStore.ts),
    // not `true`, so asserting "the default in a fresh store" was never actually testing what it
    // said; setting the property directly tests the badge's OWN tone-derivation logic (the thing
    // this test is actually about) without depending on that separate subsystem's startup state.
    el.backendReachable = true;
    await el.updateComplete;
    await fake.error();
    await new Promise((r) => setTimeout(r, 850)); // > DISCONNECT_DEBOUNCE_MS (750)
    await el.updateComplete;
    const dot = el.shadowRoot?.querySelector('jf-status-dot');
    expect(dot?.getAttribute('tone')).toBe('warning');
    expect(dot?.getAttribute('label')).toContain('paused');
    el.remove();
  });

  it('threshold-body conditions render magnitudes', async () => {
    const thresholdEvent: HealthEvent = {
      id: 'memory.high',
      timestamp: '2026-05-05T12:00:00Z',
      source: FAKE_SOURCE,
      severity: 'WARNING',
      body: {
        kind: 'threshold',
        subject: 'MemoryHigh',
        phase: 'FIRING',
        magnitudes: { ratio_pct: 92, ms_over_threshold: 5000 },
        lastTransitionTime: '2026-05-05T12:00:00Z',
        message: 'Memory usage above threshold',
      },
    };
    const el = await mount('http://test');
    const fake = streams.connections.find((connection) => !connection.closed)!;
    await fake.emitFrame({
      streamId: 'h/v1',
      frameKind: 'LIFECYCLE',
      seq: 1,
      ts: '...',
      payload: { kind: 'snapshot', conditions: [thresholdEvent], occurrences: [] },
      resumeToken: 't1',
    });
    await el.updateComplete;
    const card = el.shadowRoot?.querySelector('jf-status-card');
    expect(card?.getAttribute('details')).toContain('ratio_pct=92');
    expect(card?.getAttribute('reason')).toBe('FIRING');
    el.remove();
  });
});
