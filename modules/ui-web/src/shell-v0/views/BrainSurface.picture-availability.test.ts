// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import fixture from '../../api/__fixtures__/status-response-live.json';
import { __resetAiStateForTest, startAiStateStore } from '../state/aiStateStore.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import './BrainSurface.js';

// Real poller and store: only a delayed response through them can start before a receipt and land after it.
// Slice 7a (D2, AM2): one switch under the step shows the saved choice; the step's light and note say
// what works now. The sentence no longer carries per-feature parts.
const NOT_AVAILABLE = 'Not available with this answer model';
const POLL = 5000;
interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
let saved: boolean | null;
let override: boolean | null;
let filesComplete: boolean;
let quarantined: boolean;
let settingsReadFails: boolean;
let mmproj: boolean | null;
let engineOnline: boolean;
let runtimeFails: boolean;
let holdRuntime: boolean;
let heldRuntime: Array<() => void>;
let witness: { acceptedRevision: number; lastCommittedOperationKey: string | null };
let posts: Array<{ pictureReadingEnabled: boolean }>;
let refusal: { status: number; code: string; error: string; commitsAnyway?: boolean } | null;
let interruptPost: boolean;
const receipts = new Map<string, unknown>();

beforeEach(() => {
  vi.useFakeTimers(); __resetAiStateForTest();
  saved = true; override = null; filesComplete = true; quarantined = false; settingsReadFails = false; mmproj = false; engineOnline = true;
  runtimeFails = false; holdRuntime = false; heldRuntime = [];
  witness = { acceptedRevision: 0, lastCommittedOperationKey: null };
  posts = []; refusal = null; interruptPost = false; receipts.clear();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.endsWith('/api/status')) return json({ ...fixture, inference: { ...fixture.inference, engineState: engineOnline ? 'Healthy' : 'Down' } });
    if (path.endsWith('/api/inference/status')) return json({ mode: engineOnline ? 'online' : 'offline', available: engineOnline, activeModelId: 'answer' });
    if (path.endsWith('/api/ai/install/status')) return json({ state: 'completed', phase: 'idle', installedFully: true });
    if (path.endsWith('/api/ai/packs/status')) return json({ state: 'idle', phase: 'idle' });
    if (path.endsWith('/api/ai/runtime/status')) {
      // A read answers with what was true when it reached the server, however late it is delivered.
      const observed = runtimeFails ? null : { active: { mmprojActive: mmproj }, onnxFeatures: [] };
      if (holdRuntime) await new Promise<void>(release => heldRuntime.push(release));
      return observed ? json(observed) : json({}, 500);
    }
    if (path.endsWith('/api/settings/v2') && init?.method === 'POST') {
      const body = JSON.parse(String(init.body));
      posts.push(body);
      if (interruptPost) { interruptPost = false; throw new Error('Delivery interrupted'); }
      if (receipts.has(body.operationKey)) return json(receipts.get(body.operationKey));
      if (refusal) {
        if (refusal.commitsAnyway) { saved = body.pictureReadingEnabled; witness = { acceptedRevision: witness.acceptedRevision + 1, lastCommittedOperationKey: body.operationKey }; }
        return json({ errorCode: refusal.code, error: refusal.error }, refusal.status);
      }
      if (body.witness.acceptedRevision !== witness.acceptedRevision) return json({ errorCode: 'VERSION_CONFLICT' }, 409);
      saved = body.pictureReadingEnabled;
      witness = { acceptedRevision: witness.acceptedRevision + 1, lastCommittedOperationKey: body.operationKey };
      const receipt = { state: 'COMPLETE', operationKey: body.operationKey, witness, pictureReadingEnabled: saved };
      receipts.set(body.operationKey, receipt);
      return json(receipt);
    }
    if (path.endsWith('/api/settings/v2') && settingsReadFails) return json({}, 500);
    if (path.endsWith('/api/settings/v2') && quarantined) return json({ error: 'Settings recovery is required', errorCode: 'SETTINGS_RECOVERY_REQUIRED', retryable: false }, 503);
    if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled: true }, pictureReadingEnabled: saved, pictureReadingOverrideEnabled: override, witness });
    if (path.endsWith('/plan-preview')) return json({ totalDownloadBytes: 0, components: ['embedding', 'reranker', 'chat'].map(id => ({ id, label: id, state: 'installed', totalBytes: 1024, downloadBytes: 0 })) });
    if (path.endsWith('/api/ai/models/status')) return json({ packages: [{ id: 'chat', complete: filesComplete }] });
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; __resetAiStateForTest(); vi.useRealTimers(); vi.unstubAllGlobals(); });

async function settle(...els: Brain[]) {
  for (let i = 0; i < 6; ++i) { await vi.advanceTimersByTimeAsync(0); await Promise.all(els.map(el => el.updateComplete)); }
}
async function poll(...els: Brain[]) { await vi.advanceTimersByTimeAsync(POLL); await settle(...els); }
async function mount(): Promise<Brain> {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = ''; el.host_ = createMockHostApi(); document.body.append(el);
  await vi.advanceTimersByTimeAsync(10); await settle(el);
  return el;
}
async function start(): Promise<Brain> { startAiStateStore(''); await vi.advanceTimersByTimeAsync(10); return mount(); }
function switches(el: Brain) { return [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('[role="switch"][aria-label="Use Text in scans and images"]')]; }
function view(el: Brain) {
  const root = el.shadowRoot!;
  return {
    word: root.querySelector('[data-step="pictures"] .switch-state')?.textContent,
    note: root.querySelector('[data-step="pictures"] .step-note')?.textContent,
    switches: switches(el).map(b => b.getAttribute('aria-checked')),
    lit: root.querySelector('[data-step="pictures"]')?.getAttribute('data-lit'),
  };
}
/** Starts a runtime read that stays unanswered, as a slow response would. */
async function startDelayedRead(el: Brain) {
  holdRuntime = true; await vi.advanceTimersByTimeAsync(POLL); await settle(el);
  expect(heldRuntime).toHaveLength(1);
  holdRuntime = false;
}
async function deliverDelayedRead(el: Brain) { heldRuntime.splice(0).forEach(release => release()); await settle(el); }
async function toggle(el: Brain) { switches(el)[0]!.click(); await settle(el); }
function expectNotAvailable(el: Brain) {
  expect(view(el)).toMatchObject({ word: 'On', note: NOT_AVAILABLE, switches: ['true'], lit: 'false' });
}
function expectReading(el: Brain) {
  expect(view(el)).toMatchObject({ word: 'On', note: undefined, switches: ['true'], lit: 'true' });
}
function expectChecking(el: Brain) {
  // W5: the heading says once that AI status is being checked; the step does not repeat it.
  expect(el.shadowRoot!.querySelector('.brain-headline')?.textContent).toBe('Checking AI status…');
  expect(view(el)).toMatchObject({ note: undefined, lit: 'false' });
  expect(el.shadowRoot!.textContent).not.toContain('Unknown — checking AI status');
  expect(el.shadowRoot!.textContent).not.toContain(NOT_AVAILABLE);
}

describe('saved picture reading with an answer model that cannot read pictures', () => {
  describe('deliver', () => {
    it('P1 agrees in both switch locations and the sentence on first load', async () => {
      const el = await start();
      expectNotAvailable(el);
      expect(posts).toEqual([]);
    });

    it('P2 updates live through text-only, picture-reading and text-only models without a reload', async () => {
      mmproj = true;
      const el = await start();
      expectReading(el);
      mmproj = false; await poll(el);
      expectNotAvailable(el);
      mmproj = true; await poll(el);
      expectReading(el);
      mmproj = false; await poll(el);
      expectNotAvailable(el);
      expect(posts).toEqual([]);
    });

    it('P2 holds after reopening the surface', async () => {
      const first = await start();
      expectNotAvailable(first);
      first.remove();
      const reopened = await mount();
      expectNotAvailable(reopened);
    });

    it('P2 holds across an engine restart once the engine reports the model again', async () => {
      const el = await start();
      expectNotAvailable(el);
      engineOnline = false; mmproj = null; await poll(el); await poll(el);
      expect(el.shadowRoot!.textContent).not.toContain(NOT_AVAILABLE);
      engineOnline = true; mmproj = false; await poll(el); await poll(el);
      expectNotAvailable(el);
    });

    it('P5 a read started before an On receipt cannot show not available or clear the transition', async () => {
      saved = false;
      const el = await start();
      expect(view(el)).toMatchObject({ word: 'Off', note: undefined });
      await startDelayedRead(el);
      await toggle(el);
      expectReading(el);
      await deliverDelayedRead(el);
      expectReading(el);
      expect(el.shadowRoot!.textContent).not.toContain(NOT_AVAILABLE);
    });

    it('P5 a read started after the receipt does show not available in both presentations', async () => {
      saved = false;
      const el = await start();
      await toggle(el);
      expectReading(el);
      await poll(el);
      expectNotAvailable(el);
    });

    it('P5 a read started before an Off receipt keeps Off instead of the saved-Off mismatch', async () => {
      mmproj = true;
      const el = await start();
      expectReading(el);
      await startDelayedRead(el);
      await toggle(el);
      expect(view(el)).toMatchObject({ word: 'Off', note: undefined, switches: ['false'] });
      await deliverDelayedRead(el);
      expect(view(el)).toMatchObject({ word: 'Off', note: undefined, switches: ['false'] });
      expect(el.shadowRoot!.textContent).not.toContain('Off is not applied yet');
    });

    it('P5 repeated switching judges a delayed read against the latest receipt', async () => {
      saved = false;
      const el = await start();
      await toggle(el); // On
      await startDelayedRead(el);
      await toggle(el); // Off
      await toggle(el); // On again; the delayed read started before both of the later receipts.
      expectReading(el);
      await deliverDelayedRead(el);
      expectReading(el);
      await poll(el);
      expectNotAvailable(el);
    });

    it('P5 leaving the screen mid-operation keeps the ordering', async () => {
      saved = false;
      const el = await start();
      await startDelayedRead(el);
      await toggle(el);
      el.remove(); document.body.append(el); await settle(el);
      await deliverDelayedRead(el);
      expectReading(el);
      await poll(el);
      expectNotAvailable(el);
    });

    it('P5 a second window that learns the choice from settings ignores a read started before it did', async () => {
      saved = false;
      const el = await start();
      await startDelayedRead(el);
      saved = true; witness = { acceptedRevision: 1, lastCommittedOperationKey: '01993ba0-0000-7000-8000-000000000002' };
      window.dispatchEvent(new Event('focus')); await settle(el);
      await deliverDelayedRead(el);
      expect(el.shadowRoot!.textContent).not.toContain(NOT_AVAILABLE);
      expect(view(el).switches).toEqual(['true']);
      await poll(el);
      expectNotAvailable(el);
    });

    it('P6 receipt then offline stops claiming the transition and recovers truthfully', async () => {
      saved = false;
      const el = await start();
      await toggle(el);
      expectReading(el);
      runtimeFails = true; await poll(el);
      expectChecking(el);
      expect(view(el).lit).toBe('false');
      runtimeFails = false; await poll(el);
      expectNotAvailable(el);
    });

    it('P6 receipt then a failed restart ends unconfirmed and recovers with a fresh read', async () => {
      saved = false;
      const el = await start();
      await toggle(el);
      expectReading(el);
      engineOnline = false; mmproj = null; await poll(el); await poll(el);
      expect(el.shadowRoot!.textContent).not.toContain(NOT_AVAILABLE);
      expect(view(el).lit).toBe('false');
      engineOnline = true; mmproj = false; await poll(el); await poll(el);
      expectNotAvailable(el);
    });

    it('P6 a failed restart that still committed the choice converges on a later settings read', async () => {
      saved = false;
      const el = await start();
      refusal = { status: 500, code: 'PICTURE_READING_RESTART_FAILED', error: 'Picture reading could not start', commitsAnyway: true };
      await startDelayedRead(el);
      await toggle(el);
      expect(el.shadowRoot!.textContent).toContain('Picture reading could not start');
      expect(view(el)).toMatchObject({ word: 'Off', note: undefined });
      refusal = null;
      window.dispatchEvent(new Event('focus')); await settle(el);
      await deliverDelayedRead(el);
      expect(el.shadowRoot!.textContent).not.toContain(NOT_AVAILABLE);
      await poll(el);
      expectNotAvailable(el);
    });

    it('P6 a refused change keeps the saved choice and the next read still reports the model', async () => {
      const el = await start();
      expectNotAvailable(el);
      refusal = { status: 409, code: 'PICTURE_READING_EXTERNAL_SERVER', error: 'Cannot be switched while using a user-run answer server' };
      await toggle(el);
      expect(el.shadowRoot!.textContent).toContain('Cannot be switched while using a user-run answer server');
      expectNotAvailable(el);
      await poll(el);
      expectNotAvailable(el);
    });

    it('P6 an interrupted change reports uncertainty, then ends on what a fresh read shows', async () => {
      saved = false;
      const el = await start();
      interruptPost = true;
      await toggle(el);
      expect(view(el)).toMatchObject({ word: 'Not known', note: 'Checking after an interrupted change', switches: [] });
      window.dispatchEvent(new Event('focus')); await settle(el);
      expect(view(el).word).toBe('On');
      await poll(el);
      expectNotAvailable(el);
    });

    it('P6 a reconciled receipt is ordered like a direct one', async () => {
      saved = false;
      const el = await start();
      await startDelayedRead(el);
      interruptPost = true;
      await toggle(el);
      // The settings read after the replayed receipt fails, so only the receipt can order this read.
      settingsReadFails = true;
      window.dispatchEvent(new Event('focus')); await settle(el);
      await deliverDelayedRead(el);
      expect(el.shadowRoot!.textContent).not.toContain(NOT_AVAILABLE);
      expect(view(el)).toMatchObject({ word: 'On', switches: ['true'] });
      settingsReadFails = false;
      window.dispatchEvent(new Event('focus')); await settle(el);
      await poll(el);
      expectNotAvailable(el);
    });
  });

  describe('must not break', () => {
    it('P3 a failed runtime read after a confirmed text-only model stays unknown', async () => {
      const el = await start();
      runtimeFails = true; await poll(el);
      expectChecking(el);
    });

    it('P3 a null projector report stays unconfirmed, never text-only', async () => {
      mmproj = null;
      const el = await start();
      expect(view(el)).toMatchObject({ word: 'On', note: 'Not confirmed yet', switches: ['true'], lit: 'false' });
    });

    it('P3 an offline engine keeps a retained text-only report from reading as confirmed', async () => {
      const el = await start();
      engineOnline = false; await poll(el); await poll(el);
      expect(el.shadowRoot!.textContent).not.toContain(NOT_AVAILABLE);
      // The uncertainty stays said: the step's note is W1's "Not confirmed yet", the switch keeps the saved On.
      expect(view(el)).toMatchObject({ word: 'On', note: 'Not confirmed yet', switches: ['true'], lit: 'false' });
    });

    // AM2: the switch shows the saved choice (an operator override is the saved choice that wins);
    // the note says what works now. Unset follows the installed answer model's files (UiSettings).
    it.each([
      { name: 'explicit Off', arrange: () => { saved = false; }, word: 'Off', note: undefined, checked: ['false'], lit: 'false' },
      { name: 'operator override Off', arrange: () => { override = false; }, word: 'Off', note: 'Set by an operator override', checked: ['false'], lit: 'false' },
      { name: 'operator override On', arrange: () => { override = true; }, word: 'On', note: 'Set by an operator override', checked: ['true'], lit: 'false' },
      { name: 'missing files', arrange: () => { filesComplete = false; }, word: 'On', note: 'Not working — its files are missing', checked: ['true'], lit: 'false' },
      { name: 'unknown settings', arrange: () => { quarantined = true; }, word: 'Not known', note: undefined, checked: [], lit: 'false' },
      { name: 'no saved choice', arrange: () => { saved = null; }, word: 'On', note: NOT_AVAILABLE, checked: ['true'], lit: 'false' },
      { name: 'saved Off with runtime On', arrange: () => { saved = false; mmproj = true; }, word: 'Off', note: 'Off is not applied yet', checked: ['false'], lit: 'true' },
    ])('P4 $name keeps its wording and the saved preference', async ({ name, arrange, word, note, checked, lit }) => {
      arrange();
      const el = await start();
      expect(view(el)).toMatchObject({ word, note, switches: checked, lit });
      if (name !== 'no saved choice') expect(el.shadowRoot!.textContent).not.toContain(NOT_AVAILABLE);
      expect(posts).toEqual([]);
    });
  });
});
