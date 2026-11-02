// @vitest-environment happy-dom

/** Slice A retains telemetry below the links inside Advanced, independent of ui.mode.
 * Slice B moves its fetch ownership and presentation to Health. */

import { afterEach, describe, expect, it, beforeEach, vi } from 'vitest';

// Incidental requests are outside these render/event scenarios. Keep their
// unavailable-backend behavior explicit, without contacting a real server.
const originalFetch = globalThis.fetch;
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Test backend unavailable')));
});
afterEach(() => { globalThis.fetch = originalFetch; });
import './BrainSurface';
import { __resetUiModeForTest, setUiMode } from '../state/uiModeState.js';

interface BrainHost extends HTMLElement {
  settings: { mode?: 'simple' | 'advanced' };
  inference: { generation?: number; mode?: string; activeModelId?: string | null } | null;
  transitions: Array<{
    timestampMs: number;
    fromMode: string;
    toMode: string;
    reason: string;
    success: boolean;
    durationMs: number;
    wireCode?: string;
  }>;
  recentSpans: Array<{ trace_id?: string; name?: string; status?: string; duration_ms?: number }>;
  tracesAvailable: boolean;
  updateComplete: Promise<boolean>;
}

const TRANSITIONS: BrainHost['transitions'] = [
  { timestampMs: 100, fromMode: 'OFFLINE', toMode: 'ONLINE', reason: 'USER_SWITCH', success: true, durationMs: 5 },
  { timestampMs: 200, fromMode: 'ONLINE', toMode: 'INDEXING', reason: 'USER_SWITCH', success: true, durationMs: 5 },
];

const SPANS: BrainHost['recentSpans'] = [
  { trace_id: 'abc123', name: 'search.execute', status: 'OK', duration_ms: 12 },
];

/** Mount with telemetry data present in BOTH panels' feeding state; return the rendered shadow DOM. */
async function mount(mode: 'simple' | 'advanced', open = true): Promise<string> {
  setUiMode(mode);
  const el = document.createElement('jf-brain-surface') as BrainHost;
  el.settings = { mode };
  el.inference = { generation: 2, mode: 'offline', activeModelId: null };
  el.transitions = TRANSITIONS;
  el.recentSpans = SPANS;
  el.tracesAvailable = true;
  document.body.appendChild(el);
  await el.updateComplete;
  if (open) {
    el.shadowRoot?.querySelector<HTMLButtonElement>('.brain-links button')?.click();
    await el.updateComplete;
  }
  expect(el.shadowRoot?.querySelector('.brain-path')).not.toBeNull();
  const settings = el.shadowRoot?.querySelector<HTMLElement>('.brain-existing-settings');
  const html = open ? settings?.innerHTML ?? '' : settings?.hidden ? '' : settings?.innerHTML ?? '';
  document.body.removeChild(el);
  return html;
}

afterEach(() => __resetUiModeForTest());

describe('BrainSurface developer telemetry placement (round 8)', () => {
  it('collapsed Advanced exposes NO transition timeline and NO trace explorer', async () => {
    const html = await mount('simple', false);
    expect(html).toBe('');
    expect(html).not.toContain('brain-transitions-timeline');
    expect(html).not.toContain('brain-generation-sparkline');
    expect(html).not.toContain('brain-trace-explorer');
    expect(html).not.toContain('Recent inference transitions');
    expect(html).not.toContain('Recent spans');
    expect(html).not.toContain('copy trace ID');
  });

  it.each(['simple', 'advanced'] as const)('Advanced renders both telemetry panels under %s ui.mode', async mode => {
    const html = await mount(mode);
    expect(html).toContain('brain-transitions-timeline');
    expect(html).toContain('brain-trace-explorer');
    // Round-14 finding 10 — the labels name their axis: "mode" was ambiguous against this window's
    // own Simple|Detailed mode and the search rungs; `gen:` collided with the index's own
    // `servingSearchGenerationId` and read as a model-version claim beside the model filename.
    expect(html).toContain('Recent inference transitions');
    expect(html).not.toContain('Recent mode transitions');
    expect(html).toContain('engine generation:');
    expect(html).toContain('Recent spans');
  });

  it('the Detailed panel keeps the emptiness gates — no telemetry data, no sections', async () => {
    setUiMode('advanced');
    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.settings = { mode: 'advanced' };
    el.inference = { generation: 0, mode: 'offline', activeModelId: null };
    el.transitions = [];
    el.recentSpans = [];
    el.tracesAvailable = false;
    document.body.appendChild(el);
    await el.updateComplete;
    const html = el.shadowRoot?.innerHTML ?? '';
    document.body.removeChild(el);
    expect(html).not.toContain('brain-transitions-timeline');
    expect(html).not.toContain('brain-trace-explorer');
  });

  it('the trace explorer is gated on tracesAvailable alone — spans present but unavailable stays hidden', async () => {
    setUiMode('advanced');
    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.settings = { mode: 'advanced' };
    el.inference = { generation: 2, mode: 'offline', activeModelId: null };
    el.transitions = TRANSITIONS;
    el.recentSpans = SPANS;
    el.tracesAvailable = false;
    document.body.appendChild(el);
    await el.updateComplete;
    const html = el.shadowRoot?.innerHTML ?? '';
    document.body.removeChild(el);
    expect(html).toContain('brain-transitions-timeline');
    expect(html).not.toContain('brain-trace-explorer');
  });
});
