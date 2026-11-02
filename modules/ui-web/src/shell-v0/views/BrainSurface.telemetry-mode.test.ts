// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './BrainSurface.js';
import './HealthSurface.js';
import type { HealthSurface } from './HealthSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { requestMemberTab } from '../router/memberTabIntent.js';
import { __resetUiModeForTest, setUiMode } from '../state/uiModeState.js';

const transitions = [
  { timestampMs: 100, fromMode: 'OFFLINE', toMode: 'ONLINE', reason: 'USER_SWITCH', success: true, durationMs: 5 },
  { timestampMs: 200, fromMode: 'ONLINE', toMode: 'INDEXING', reason: 'USER_SWITCH', success: true, durationMs: 5 },
];
const spans = [{ trace_id: 'abc123', name: 'search.execute', status: 'OK', duration_ms: 12 }];
let transitionsBody: unknown;
let tracesBody: unknown;
let unavailable: boolean;
let late: Promise<Response> | null;
let endpointRead: ((path: string, signal?: AbortSignal) => Promise<Response>) | null;
let signals: AbortSignal[];
let requests: string[];
const json = (body: unknown) => new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json' } });
async function settle(el: HealthSurface) { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); await el.updateComplete; }
async function mount(): Promise<HealthSurface> {
  const el = document.createElement('jf-health-surface') as HealthSurface;
  el.host_ = createMockHostApi({ data: { fetch: async (path: string, init?: {signal?: AbortSignal}) => {
    if (path.includes('/transitions') || path.includes('/traces?')) {
      requests.push(path); if (init?.signal) signals.push(init.signal);
      if (endpointRead) return endpointRead(path, init?.signal);
      if (late) return late;
      if (unavailable) throw new Error('Unavailable');
      return json(path.includes('/transitions') ? transitionsBody : tracesBody);
    }
    return json({});
  } } });
  document.body.appendChild(el); await settle(el);
  return el;
}
beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal('fetch', vi.fn(async () => json({})));
  transitionsBody = { transitions }; tracesBody = { spans, tracesAvailable: true };
  unavailable = false; late = null; endpointRead = null; requests = []; signals = [];
});
afterEach(() => { document.body.innerHTML = ''; vi.useRealTimers(); vi.unstubAllGlobals(); __resetUiModeForTest(); });
describe('developer telemetry belongs to Health AI Engine', () => {
  it.each(['simple', 'advanced'] as const)('Health opened first fetches and renders transitions, generation and spans under %s mode', async mode => {
    setUiMode(mode); const el = await mount();
    el.inference = { generation: 2, mode: 'offline' } as HealthSurface['inference']; await settle(el);
    const engine = el.shadowRoot!.querySelector('#ai-engine')!;
    expect(requests).toEqual(['/api/inference/transitions?limit=8', '/api/diagnostics/traces?limit=10']);
    expect(engine.textContent).toContain('Recent inference transitions');
    expect(engine.textContent).not.toContain('Recent mode transitions');
    expect(engine.textContent).toContain('engine generation:');
    expect(engine.textContent).toContain('Recent spans');
    expect(engine.querySelector('[data-testid="brain-generation-sparkline-line"]')).not.toBeNull();
    expect(engine.querySelectorAll('[data-testid="brain-transition-row"]')).toHaveLength(2);
    expect(engine.querySelectorAll('[data-testid="brain-trace-row"]')).toHaveLength(1);
  });
  it('Brain never polls telemetry, with Advanced closed or open', async () => {
    const el = document.createElement('jf-brain-surface') as HTMLElement & { updateComplete: Promise<boolean> };
    document.body.appendChild(el); await el.updateComplete;
    el.shadowRoot!.querySelector<HTMLButtonElement>('.brain-links button')!.click(); await el.updateComplete;
    await vi.advanceTimersByTimeAsync(10000);
    expect(vi.mocked(fetch).mock.calls.map(([url]) => String(url)).filter(url => /\/transitions|\/traces\?/.test(url))).toEqual([]);
    expect(el.shadowRoot!.querySelector('[data-testid="brain-transitions-timeline"]')).toBeNull();
    expect(el.shadowRoot!.querySelector('[data-testid="brain-trace-explorer"]')).toBeNull();
  });
  it('empty and unavailable states keep their sections visible, without fabricated data', async () => {
    transitionsBody = { transitions: [] }; tracesBody = { spans: [], tracesAvailable: true };
    const el = await mount();
    expect(el.shadowRoot!.textContent).toContain('No inference transitions yet.');
    expect(el.shadowRoot!.textContent).toContain('No recent spans.');
    unavailable = true; await vi.advanceTimersByTimeAsync(5000); await settle(el);
    expect(el.shadowRoot!.textContent).toContain('Inference transitions unavailable.');
    expect(el.shadowRoot!.textContent).toContain('Recent spans unavailable');
    expect(el.shadowRoot!.querySelector('[data-testid="brain-transition-row"]')).toBeNull();
  });
  it('a trace file unavailable flag does not display supplied spans', async () => {
    tracesBody = { spans, tracesAvailable: false }; const el = await mount();
    expect(el.shadowRoot!.querySelector('[data-testid="brain-transitions-timeline"]')).not.toBeNull();
    expect(el.shadowRoot!.querySelector('[data-testid="brain-trace-row"]')).toBeNull();
    expect(el.shadowRoot!.textContent).toContain('Recent spans unavailable');
  });
  it('unmount aborts reads and stops polling; late responses cannot replace the old snapshot', async () => {
    const el = await mount(); let release!: (value: Response) => void;
    late = new Promise(resolve => { release = resolve; });
    await vi.advanceTimersByTimeAsync(5000); const count = requests.length;
    el.remove(); expect(signals.at(-1)?.aborted).toBe(true);
    release(json({ transitions: [], spans: [], tracesAvailable: false }));
    await vi.advanceTimersByTimeAsync(10000);
    expect(requests).toHaveLength(count); expect(el.transitions).toHaveLength(2);
  });
  it('engine history link focuses the section when Health is mounted already and when it mounts later', async () => {
    const brain = document.createElement('jf-brain-surface') as HTMLElement & { updateComplete: Promise<boolean> };
    document.body.appendChild(brain); await brain.updateComplete;
    const link = brain.shadowRoot!.querySelector<HTMLAnchorElement>('.brain-links a')!;
    link.addEventListener('click', event => event.preventDefault()); link.click();
    const el = await mount();
    await vi.waitFor(() => expect(el.shadowRoot!.activeElement?.id).toBe('ai-engine'));
    el.shadowRoot!.querySelector<HTMLElement>('#ai-engine')!.blur();
    requestMemberTab('core.health-surface', 'ai-engine');
    await vi.waitFor(() => expect(el.shadowRoot!.activeElement?.id).toBe('ai-engine'));
  });
});

describe('Health telemetry request independence', () => {
  it.each(['transitions', 'traces'])('S6 slow %s survives a poll tick while the other endpoint publishes independently', async slow => {
    let release!: (response: Response) => void;
    endpointRead = path => path.includes('/' + slow + '?') ? new Promise(resolve => { release = resolve; })
      : Promise.resolve(json(path.includes('/transitions') ? { transitions } : { spans, tracesAvailable: true }));
    const el = await mount();
    await vi.advanceTimersByTimeAsync(0); await settle(el);
    const fastRow = slow === 'traces' ? 'brain-transition-row' : 'brain-trace-row';
    // One case pins independent publication first; the other pins non-starvation
    // first, so fail-before evidence identifies both original defects.
    if (slow === 'transitions') expect(el.shadowRoot!.querySelectorAll(`[data-testid="${fastRow}"]`).length).toBeGreaterThan(0);
    await vi.advanceTimersByTimeAsync(6000); await settle(el);
    expect(requests.filter(path => path.includes('/' + slow + '?'))).toHaveLength(1);
    expect(signals.every(signal => !signal.aborted)).toBe(true);
    expect(el.shadowRoot!.querySelectorAll(`[data-testid="${fastRow}"]`).length).toBeGreaterThan(0);
    release(json(slow === 'traces' ? { spans, tracesAvailable: true } : { transitions })); await vi.advanceTimersByTimeAsync(0); await settle(el);
    expect(el.shadowRoot!.querySelectorAll('[data-testid="brain-transition-row"]')).toHaveLength(2);
    expect(el.shadowRoot!.querySelectorAll('[data-testid="brain-trace-row"]')).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(4000); await settle(el);
    expect(requests.filter(path => path.includes('/' + slow + '?'))).toHaveLength(2);
  });
  it('S6 a bounded pending read becomes unavailable and can retry, ignoring late results', async () => {
    const el = await mount();
    let release!: (response: Response) => void;
    endpointRead = path => path.includes('/traces?') ? new Promise(resolve => { release = resolve; }) : Promise.resolve(json({ transitions }));
    await vi.advanceTimersByTimeAsync(5000);
    const pendingSignal = signals.at(-1)!;
    await vi.advanceTimersByTimeAsync(15000); await settle(el);
    expect(pendingSignal.aborted).toBe(true);
    expect(el.shadowRoot!.textContent).toContain('Recent spans unavailable');
    endpointRead = null; release(json({ spans: [{ ...spans[0], name: 'obsolete' }], tracesAvailable: true })); await settle(el);
    expect(el.shadowRoot!.textContent).not.toContain('obsolete');
    await vi.advanceTimersByTimeAsync(5000); await settle(el);
    expect(el.shadowRoot!.querySelectorAll('[data-testid="brain-trace-row"]')).toHaveLength(1);
  });
});
