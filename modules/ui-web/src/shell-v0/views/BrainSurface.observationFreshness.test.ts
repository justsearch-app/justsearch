// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import fixture from '../../api/__fixtures__/status-response-live.json';
import {
  startAiStateStore, subscribeAiState, getAiState, __resetAiStateForTest, setAiActivity,
} from '../state/aiStateStore.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import './BrainSurface.js';

interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
const json = (body: unknown, status = 200, headers?: Record<string, string>) => new Response(JSON.stringify(body), { status, headers });
let installState = 'running';
let activationState = 'idle';
let delayInstall = 200;
let delayRuntime = 400;
let failed: string | undefined;
let hanging: string | undefined;
let admission = false;
let requests: string[];

function setup() {
  vi.useFakeTimers();
  __resetAiStateForTest();
  installState = 'running'; activationState = 'idle'; delayInstall = 200; delayRuntime = 400;
  failed = undefined; hanging = undefined; admission = false; requests = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.endsWith('/api/status')) return json(fixture);
    if (url.endsWith('/api/inference/status')) return json({ mode: 'online', available: true, activeModelId: 'answer' });
    const endpoint = /\/api\/ai\/(install|runtime|packs)\/status$/.exec(url)?.[1];
    if (endpoint) {
      requests.push(endpoint);
      if (hanging === endpoint) return new Promise<Response>(() => {});
      if (admission && endpoint === 'runtime') {
        admission = false;
        return json({ errorCode: 'ADMISSION_ENGINE_LIMIT', retrySafe: true }, 429, { 'Retry-After': '12' });
      }
      await new Promise(resolve => setTimeout(resolve, endpoint === 'install' ? delayInstall : endpoint === 'runtime' ? delayRuntime : 600));
      if (failed === endpoint) return json({}, 500);
      return json(endpoint === 'install' ? { state: installState, phase: installState === 'running' ? 'downloading' : 'idle', installedFully: installState !== 'running', percent: 25 }
        : endpoint === 'runtime' ? { activation: { state: activationState }, active: { activeVariantId: 'cpu' } } : { state: 'idle', phase: 'idle' });
    }
    if (url.endsWith('/plan-preview')) return json({ components: [], totalDownloadBytes: 0 });
    return json({});
  }));
}
async function advance(ms: number, brain?: Brain) { await vi.advanceTimersByTimeAsync(ms); await brain?.updateComplete; }
function live() { const s = getAiState(); return s.installObservationLive && s.runtimeObservationLive; }
function watchConfirmed() {
  const seen: boolean[] = [];
  let confirmed = false;
  subscribeAiState(s => {
    const current = s.installObservationLive && s.runtimeObservationLive;
    if (current) confirmed = true;
    if (confirmed) seen.push(current);
  });
  return seen;
}
afterEach(() => { document.body.innerHTML = ''; __resetAiStateForTest(); vi.useRealTimers(); vi.unstubAllGlobals(); });

describe('Brain observation freshness repair', () => {
  it('H1 delayed successful download polls preserve the same Pause control and keyboard focus', async () => {
    setup(); startAiStateStore('');
    const brain = document.createElement('jf-brain-surface') as Brain;
    brain.apiBase = ''; brain.host_ = createMockHostApi(); document.body.append(brain);
    await advance(700, brain);
    const pause = brain.shadowRoot!.querySelector<HTMLElement>('[data-testid="install-pause-toggle"]')!;
    expect(pause).not.toBeNull();
    await (pause as HTMLElement & { updateComplete: Promise<boolean> }).updateComplete;
    const control = pause.shadowRoot!.querySelector<HTMLElement>('jf-control')!;
    await (control as HTMLElement & { updateComplete: Promise<boolean> }).updateComplete;
    const button = control.shadowRoot!.querySelector<HTMLButtonElement>('button')!;
    button.focus();
    await vi.waitFor(() => expect(control.shadowRoot!.activeElement).toBe(button));
    for (let i = 0; i < 80; i++) {
      await advance(100, brain);
      expect(brain.shadowRoot!.querySelector('[data-testid="install-pause-toggle"]')).toBe(pause);
      expect(brain.shadowRoot!.activeElement).toBe(pause);
      expect(control.shadowRoot!.activeElement).toBe(button);
    }
    expect(requests.filter(r => r === 'install').length).toBeGreaterThan(4);
  });

  it.each(['install first', 'runtime first'])('H2 reversed replies and both cadence transitions keep successful observations live: %s', async order => {
    setup(); installState = 'idle';
    delayInstall = order === 'install first' ? 200 : 400; delayRuntime = order === 'install first' ? 400 : 200;
    const seen = watchConfirmed(); startAiStateStore('');
    await advance(600); expect(live()).toBe(true);
    await advance(5100); expect(live()).toBe(true);
    // Activation discovered on an idle tick changes the next cadence from 5s to 1s.
    activationState = 'running'; await advance(5600); expect(live()).toBe(true);
    await advance(5000); expect(live()).toBe(true);
    // Completion changes the next cadence back to 5s, with the opposite response still pending.
    activationState = 'idle'; await advance(9000); expect(live()).toBe(true);
    expect(seen.length).toBeGreaterThan(8); expect(seen).not.toContain(false);
  });

  it.each(['install', 'runtime'])('H3 a failed %s read closes the gate immediately and a successful retry recovers', async endpoint => {
    setup(); startAiStateStore(''); await advance(700); expect(live()).toBe(true);
    failed = endpoint; await advance(1400);
    expect(live()).toBe(false); expect(getAiState().snapshotLive).toBe(true);
    expect(getAiState()[endpoint === 'install' ? 'installStatus' : 'runtimeStatus']).not.toBeNull();
    failed = undefined; await advance(1600); expect(live()).toBe(true);
  });

  it.each(['install', 'runtime'])('H4 an unanswered %s read invalidates at the status read bound on existing store updates', async endpoint => {
    setup(); delayInstall = 0; delayRuntime = 0; startAiStateStore(''); await advance(700);
    expect(live()).toBe(true); hanging = endpoint;
    await advance(1000); // Next read starts at 1600ms: its unanswered bound is 11600ms.
    await advance(9800); expect(live()).toBe(true);
    await advance(200); setAiActivity({ state: 'thinking' }); await advance(0);
    expect(live()).toBe(false); expect(getAiState().snapshotLive).toBe(true);
    // This check uses the running store rather than manually calling its clock helper.
    await advance(5000); expect(live()).toBe(false);
  });

  it('H6 retained successful observations age out after 15 seconds when another endpoint holds the batch open', async () => {
    setup(); delayInstall = 0; delayRuntime = 0; startAiStateStore(''); await advance(700);
    hanging = 'packs'; await advance(1000);
    await advance(14_800); expect(live()).toBe(true);
    await advance(200); setAiActivity({ state: 'thinking' }); await advance(0);
    expect(live()).toBe(false);
    expect(getAiState().installStatus).not.toBeNull(); expect(getAiState().runtimeStatus).not.toBeNull();
  });

  it('H5 a directed admission wait extends the unanswered bound and its successful retry keeps the gate open', async () => {
    setup(); delayInstall = 0; delayRuntime = 0; startAiStateStore(''); await advance(700);
    expect(live()).toBe(true); admission = true;
    const seen = watchConfirmed();
    await advance(11_000); expect(live()).toBe(true);
    await advance(3000); expect(live()).toBe(true); expect(seen).not.toContain(false);
    expect(requests.filter(r => r === 'runtime').length).toBeGreaterThanOrEqual(3);
  });
});
