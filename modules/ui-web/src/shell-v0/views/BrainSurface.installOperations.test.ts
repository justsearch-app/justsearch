// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import fixture from '../../api/__fixtures__/status-response-live.json';
import { startAiStateStore, getAiState, __resetAiStateForTest, type InstallStatus } from '../state/aiStateStore.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import './BrainSurface.js';

interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
interface Control extends HTMLElement { updateComplete: Promise<boolean>; busy?: boolean }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
afterEach(() => { document.body.innerHTML = ''; __resetAiStateForTest(); vi.useRealTimers(); vi.unstubAllGlobals(); });

async function advance(ms = 0, el?: Brain) { await vi.advanceTimersByTimeAsync(ms); await el?.updateComplete; }
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = ''; el.host_ = createMockHostApi(); document.body.append(el); await advance(0, el); return el;
}
async function click(el: Brain, label: string) {
  const button = el.shadowRoot!.querySelector<Control>(`jf-button[label="${label}"]`);
  expect(button).not.toBeNull(); await button!.updateComplete;
  const control = button!.shadowRoot!.querySelector<Control>('jf-control')!; await control.updateComplete;
  const native = control.shadowRoot!.querySelector<HTMLButtonElement>('button')!;
  expect(native.disabled).toBe(false); native.click(); await advance(0, el);
  return native;
}

describe('install operation observations through the real poller and store', () => {
  it.each(['Pause', 'Resume'])('%s waits for the requested store read and releases busy state while it hangs', async action => {
    vi.useFakeTimers(); __resetAiStateForTest();
    let run: InstallStatus = { state: 'running', phase: 'download', paused: action === 'Resume',
      installedFully: false, updatedAtEpochMs: 100, downloadedBytes: 256, totalBytes: 1024,
      packages: [{ packageId: 'embedding', state: 'downloading', bytesDownloaded: 256, bytesTotal: 1024 }] };
    let installReads = 0; let fail = false;
    let release: (() => void) | undefined;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/api/status')) return json(fixture);
      if (url.endsWith('/api/inference/status')) return json({ mode: 'online', available: true });
      if (url.endsWith('/install/status')) {
        installReads++;
        if (release === undefined && installReads === 2) await new Promise<void>(resolve => { release = resolve; });
        return fail ? json({}, 503) : json(run);
      }
      if (url.endsWith('/install/pause') || url.endsWith('/install/resume')) {
        run = { ...run, paused: url.endsWith('/pause') }; return json(run);
      }
      if (url.endsWith('/runtime/status')) return json({ active: { activeVariantId: 'cpu' } });
      if (url.endsWith('/packs/status')) return json({ state: 'idle' });
      if (url.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled: false } });
      if (url.endsWith('/plan-preview')) return json({ totalDownloadBytes: 1024, components: [{ id: 'embedding', label: 'Understand meaning', state: 'not-installed', totalBytes: 1024 }] });
      return json({});
    }));
    startAiStateStore(''); const first = await mount(); const second = await mount();
    expect(installReads).toBe(1);
    const native = await click(first, `${action} download`);
    // A hung follow-up read must release the actual shared button's busy state.
    expect(native.disabled).toBe(false);
    expect(installReads).toBe(2);
    for (const el of [first, second]) expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent)
      .toBe(action === 'Pause' ? 'Installing… 25%' : 'Paused 25%');
    release!(); await advance(0, first); await second.updateComplete;
    for (const el of [first, second]) expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent)
      .toBe(action === 'Pause' ? 'Paused 25%' : 'Installing… 25%');
    const retained = getAiState().installStatus;
    fail = true; await advance(1000, first); await second.updateComplete;
    expect(getAiState().installStatus).toBe(retained);
    expect(getAiState().installObservationLive).toBe(false);
    expect(getAiState().snapshotLive).toBe(true); // Other endpoints still respond.
    const reloaded = await mount();
    for (const el of [first, second, reloaded]) {
      expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Checking AI status…');
      expect(el.shadowRoot!.querySelector('.progress-bar')).toBeNull();
      expect(el.shadowRoot!.querySelector('[data-testid="install-staged-progress"]')).toBeNull();
      expect(el.shadowRoot!.querySelector('[label="Pause download"], [label="Resume download"]')).toBeNull();
      expect(el.shadowRoot!.querySelector('[data-testid="brain-simple-action"][label="Cancel"]')).toBeNull();
      expect(el.shadowRoot!.textContent).not.toMatch(/Downloading 25%|Preparing download|Download stalled/);
    }
    first.remove();
    await advance(3000, second); expect(getAiState().installObservationLive).toBe(false);
    fail = false; await advance(1000, second); await reloaded.updateComplete;
    for (const el of [second, reloaded]) {
      expect(el.shadowRoot!.querySelector('[label="Pause download"], [label="Resume download"]')).not.toBeNull();
      expect(el.shadowRoot!.querySelector('[data-testid="brain-simple-action"][label="Cancel"]')).not.toBeNull();
      expect(el.shadowRoot!.querySelector('.progress-bar')).not.toBeNull();
    }
  });
  it.each(['Pause', 'Resume'])('%s success then failed install reads close the gate until recovery across windows and remount', async action => {
    vi.useFakeTimers(); __resetAiStateForTest();
    let run: InstallStatus = { state: 'running', phase: 'download', paused: action === 'Resume',
      installedFully: false, updatedAtEpochMs: 100, downloadedBytes: 256, totalBytes: 1024,
      packages: [{ packageId: 'embedding', state: 'downloading', bytesDownloaded: 256, bytesTotal: 1024 }] };
    let installReads = 0; let fail = false;
    let release: (() => void) | undefined;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/api/status')) return json(fixture);
      if (url.endsWith('/api/inference/status')) return json({ mode: 'online', available: true });
      if (url.endsWith('/install/status')) {
        installReads++;
        if (release === undefined && installReads === 2) await new Promise<void>(resolve => { release = resolve; });
        return fail ? json({}, 503) : json(run);
      }
      if (url.endsWith('/install/pause') || url.endsWith('/install/resume')) {
        run = { ...run, paused: url.endsWith('/pause') }; return json(run);
      }
      if (url.endsWith('/runtime/status')) return json({ active: { activeVariantId: 'cpu' } });
      if (url.endsWith('/packs/status')) return json({ state: 'idle' });
      if (url.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled: false } });
      if (url.endsWith('/plan-preview')) return json({ totalDownloadBytes: 1024, components: [{ id: 'embedding', label: 'Understand meaning', state: 'not-installed', totalBytes: 1024 }] });
      return json({});
    }));
    startAiStateStore(''); const first = await mount(); const second = await mount();
    expect(installReads).toBe(1);
    fail = true;
    await click(first, `${action} download`);
    await advance(1000, first); // Baseline reaches its ordinary poll; candidate already requested it.
    expect(release).toBeTypeOf('function');
    release!(); await advance(0, first);
    const retained = getAiState().installStatus;
    fail = true; await advance(1000, first); await second.updateComplete;
    expect(getAiState().installStatus).toBe(retained);
    expect(getAiState().installObservationLive).toBe(false);
    expect(getAiState().snapshotLive).toBe(true); // Other endpoints still respond.
    const reloaded = await mount();
    for (const el of [first, second, reloaded]) {
      expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Checking AI status…');
      expect(el.shadowRoot!.querySelector('.progress-bar')).toBeNull();
      expect(el.shadowRoot!.querySelector('[data-testid="install-staged-progress"]')).toBeNull();
      expect(el.shadowRoot!.querySelector('[label="Pause download"], [label="Resume download"]')).toBeNull();
      expect(el.shadowRoot!.querySelector('[data-testid="brain-simple-action"][label="Cancel"]')).toBeNull();
      expect(el.shadowRoot!.textContent).not.toMatch(/Downloading 25%|Preparing download|Download stalled/);
    }
    first.remove();
    await advance(3000, second); expect(getAiState().installObservationLive).toBe(false);
    fail = false; await advance(1000, second); await reloaded.updateComplete;
    for (const el of [second, reloaded]) {
      expect(el.shadowRoot!.querySelector('[label="Pause download"], [label="Resume download"]')).not.toBeNull();
      expect(el.shadowRoot!.querySelector('[data-testid="brain-simple-action"][label="Cancel"]')).not.toBeNull();
      expect(el.shadowRoot!.querySelector('.progress-bar')).not.toBeNull();
    }
  });
});
