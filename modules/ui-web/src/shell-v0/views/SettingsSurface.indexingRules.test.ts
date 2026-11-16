// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { takeIndexingRuleFocus } from '../router/indexingRuleIntent.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './SettingsSurface.js';
import './LibrarySurface.js';
import { StatusPill } from '../components/StatusPill.js';
import type { StatusPanel } from '../components/StatusPanel.js';
import type { IndexingRulesEditor } from '../components/IndexingRulesEditor.js';
import type { LibrarySurface } from './LibrarySurface.js';
import type { StatusSnapshot } from '../state/aiStateStore.js';
import { __feedForTest, __feedContactForTest, __resetAiStateForTest } from '../state/aiStateStore.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { selectIndexingProgress } from '../state/indexingProgress.js';
import { folderStatus } from '../state/folderStatus.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import { setAuthorizationPresenter } from '../operations/authorizationBroker.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };
let fetchMock: ReturnType<typeof vi.fn>;
const saved = { enabled: true, days: [1, 7], startMinute: 1320, endMinute: 360 };
// Slice 6b-2: every rule write carries the settings revision its draft was based on.
const witness = { acceptedRevision: 2, lastCommittedOperationKey: '0194f72c-0000-7000-8000-000000000002' };
function status(reasons: string[], state = 'PAUSED', pendingJobs = 3): StatusSnapshot {
  return { operating: { indexing: { state, reasons, untilEpochMs: null, waitingForResume: pendingJobs > 0 } },
    worker: { core: { indexState: pendingJobs ? 'INDEXING' : 'IDLE', pendingJobs, indexHealthy: true },
      migration: { processingJobsCount: state === 'PAUSING' ? 1 : 0 }, enrichment: { backfillMode: 'idle' } },
  } as unknown as StatusSnapshot;
}
function observe(reasons: string[], state = 'PAUSED', pending = 3): void {
  __feedForTest({ status: status(reasons, state, pending) }); __feedContactForTest();
}
async function settle(el: Mounted): Promise<void> {
  await el.updateComplete; await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete;
}
async function mount(tag = 'jf-indexing-rules-editor'): Promise<Mounted> {
  const el = document.createElement(tag) as Mounted;
  const host = createMockHostApi(); host.data.fetch = (path, init) => fetch(path, { ...init, body: typeof init?.body === 'object' ? JSON.stringify(init.body) : init?.body });
  Object.assign(el, { host_: host, activeCategory: 'indexing' });
  document.body.append(el); await settle(el); return el;
}
function input(el: Mounted, selector: string): HTMLInputElement {
  const result = el.shadowRoot!.querySelector<HTMLInputElement>(selector);
  expect(result, `control ${selector} is mounted`).not.toBeNull(); return result!;
}
async function edit(el: Mounted, selector: string, value: string | boolean): Promise<void> {
  const field = input(el, selector === '#indexing-hours' && value === false ? 'input[name=indexing-hours]:not(#indexing-hours)' : selector);
  if (typeof value === 'boolean') field.checked = value; else field.value = value;
  field.dispatchEvent(new Event(typeof value === 'boolean' ? 'change' : 'input', { bubbles: true })); await settle(el);
}
async function save(el: Mounted): Promise<void> {
  const form = el.shadowRoot!.querySelector('form[aria-label="Reading rules"]');
  expect(form, 'rules form is mounted').not.toBeNull();
  form!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await settle(el);
}
function ruleCalls() { return fetchMock.mock.calls.filter(c => String(c[0]).endsWith('/core.set-indexing-rules/invoke')); }
async function control(el: Mounted): Promise<Mounted> {
  const c = el.shadowRoot!.querySelector<Mounted>('jf-indexing-controls'); expect(c).not.toBeNull(); await settle(c!); return c!;
}
function action(el: Mounted, text: string): HTMLButtonElement {
  const button = [...el.shadowRoot!.querySelectorAll('button')].find(b => b.textContent?.trim() === text);
  expect(button, `action ${text} is mounted`).toBeDefined(); return button!;
}
beforeEach(() => {
  vi.stubEnv('TZ', 'UTC'); // Compute every locale/date expectation only after the TZ stub.
  __resetAiStateForTest();
  fetchMock = vi.fn(async (url: RequestInfo | URL) => String(url).endsWith('/api/settings/v2')
    ? Response.json({ ui: { indexingPause: { schedule: saved, pauseOnBattery: true } }, witness })
    : Response.json({ success: true, message: 'Accepted' }));
  vi.stubGlobal('fetch', fetchMock); observe([], 'RUNNING');
});
afterEach(async () => {
  document.body.replaceChildren(); setAuthorizationPresenter(null); __resetAiStateForTest();
  await new Promise(resolve => setTimeout(resolve, 0)); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks();
});

describe('WP3.8b rules editor', () => {
  it('S38.1 S38.7 loads labelled days and native locale times and saves both rules retaining disabled hours', async () => {
    const el = await mount();
    expect([...el.shadowRoot!.querySelectorAll('.indexing-days label')].map(d => d.textContent?.trim())).toEqual(['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']);
    expect(input(el, 'input[type=time]').value).toBe('22:00'); expect(input(el, '#indexing-battery').checked).toBe(true);
    expect(el.shadowRoot!.textContent).toContain("this computer's local time");
    await edit(el, '#indexing-hours', false);
    expect(JSON.parse(String(ruleCalls()[0]![1].body)).args).toEqual({ schedule: { ...saved, enabled: false }, pauseOnBattery: true, witness });
    await edit(el, '#indexing-battery', false);
    expect(JSON.parse(String(ruleCalls()[1]![1].body)).args).toEqual({ schedule: { ...saved, enabled: false }, pauseOnBattery: false, witness });
    await save(el); expect(ruleCalls(), 'an unchanged draft is not sent again').toHaveLength(2);
    expect(el.shadowRoot!.textContent).toContain('Reading rules saved');
  });
  for (const [name, value, error] of [
    ['equal endpoints', '06:00', 'Start and end times must be different.'],
    ['missing time', '', 'Enter a start time and an end time.'],
  ]) {
    it(`S38.2 announces ${name} and retains the refused draft`, async () => {
      const el = await mount(); await edit(el, 'input[type=time]', value!); await save(el);
      expect(el.shadowRoot!.querySelector('#indexing-rules-error[role=alert]')?.textContent).toContain(error);
      expect(input(el, 'input[type=time]').value).toBe(value); expect(ruleCalls()).toHaveLength(0);
    });
  }
  it('S38.2 refuses enabled hours without days and accepts the retained empty set when off', async () => {
    const el = await mount();
    await edit(el, '.indexing-days label:nth-child(1) input', false);
    expect(JSON.parse(String(ruleCalls()[0]![1].body)).args.schedule.days, 'a valid change saves').toEqual([7]);
    await edit(el, '.indexing-days label:nth-child(7) input', false); await save(el);
    expect(el.shadowRoot!.querySelector('#indexing-rules-error[role=alert]')?.textContent).toContain('Choose at least one day');
    expect(ruleCalls(), 'the invalid combination is not sent').toHaveLength(1);
    expect([...el.shadowRoot!.querySelectorAll<HTMLInputElement>('.indexing-days input')].every(c => !c.checked)).toBe(true);
    await edit(el, '#indexing-hours', false);
    expect(JSON.parse(String(ruleCalls()[1]![1].body)).args.schedule.days).toEqual([]);
  });
  for (const [code, errorCode] of [[400, 'BAD_REQUEST'], [409, 'VERSION_CONFLICT'], [200, 'VERSION_CONFLICT']] as const) {
    it(`S38.2 retains the draft and announces Engine ${code} without stale replay`, async () => {
      const el = await mount(); await edit(el, 'input[type=time]', '21:15');
      const elsewhere = { acceptedRevision: 3, lastCommittedOperationKey: '0194f72c-0000-7000-8000-000000000003' };
      fetchMock.mockImplementation(async (url: RequestInfo | URL) => String(url).endsWith('/api/settings/v2')
        // A conflict is checked against the saved rules: here another window turned the battery rule off.
        ? Response.json({ ui: { indexingPause: { schedule: saved, pauseOnBattery: false } }, witness: elsewhere })
        : Response.json({ success: false, errorClass: 'HANDLER_FAILURE', message: 'Rule field refused', errorCode }, { status: code }));
      await save(el);
      if (errorCode === 'VERSION_CONFLICT') {
        expect(el.shadowRoot!.querySelector('[aria-label="Saved reading rules"][role=alert]')?.textContent).toContain('Changed elsewhere');
        expect(el.shadowRoot!.querySelector('[aria-label="Saved reading rules"]')?.textContent).toContain('Pause while on battery: off');
      } else expect(el.shadowRoot!.querySelector('#indexing-rules-error[role=alert]')?.textContent).toContain('Rule field refused');
      expect(input(el, 'input[type=time]').value).toBe('21:15'); expect(ruleCalls()).toHaveLength(1);
      fetchMock.mockResolvedValue(Response.json({ success: true, message: 'Accepted' }));
      if (errorCode === 'VERSION_CONFLICT') action(el, 'Keep my draft').click(); else await save(el);
      await settle(el);
      expect(ruleCalls()).toHaveLength(2); expect(JSON.parse(String(ruleCalls()[1]![1].body)).preparationNonce).toBeUndefined();
      expect(JSON.parse(String(ruleCalls()[1]![1].body)).args.witness).toEqual(errorCode === 'VERSION_CONFLICT' ? elsewhere : witness);
    });
  }
  it('S38.7 waits for saved rules before editing or applying deferred rule focus', async () => {
    let release!: (response: Response) => void;
    fetchMock.mockImplementation(async (url: RequestInfo | URL) => String(url).endsWith('/api/settings/v2')
      ? new Promise<Response>(resolve => { release = resolve; }) : Response.json({}));
    const el = await mount() as IndexingRulesEditor;
    expect(input(el, '#indexing-hours').closest('fieldset')?.disabled).toBe(true);
    await el.focusIndexingRule('hours');
    release(Response.json({ ui: { indexingPause: { schedule: saved, pauseOnBattery: true } } }));
    await settle(el);
    await vi.waitFor(() => expect(el.shadowRoot!.activeElement?.id).toBe('indexing-hours'));
    expect(input(el, 'input[type=time]').value).toBe('22:00');
  });
  it('S38.7 keeps read-only rules readable without issuing a save', async () => {
    fetchMock.mockResolvedValue(Response.json({ settingsMode: 'in_memory', ui: { indexingPause: { schedule: saved } } }));
    const el = await mount();
    expect(input(el, '#indexing-hours').closest('fieldset')?.disabled).toBe(true);
    await save(el); expect(ruleCalls()).toHaveLength(0);
  });
  it('S38.7 keeps focus on the changed control while its save is pending and sends one rule pair', async () => {
    const el = await mount(); let release!: (response: Response) => void;
    fetchMock.mockImplementation(() => new Promise<Response>(resolve => { release = resolve; }));
    const battery = input(el, '#indexing-battery'); battery.focus();
    await edit(el, '#indexing-battery', false);
    expect(el.shadowRoot!.activeElement).toBe(battery);
    expect(battery.closest('fieldset')?.disabled, 'a save never disables the controls').toBe(false);
    await save(el); expect(ruleCalls()).toHaveLength(1);
    release(Response.json({ success: true, message: 'Accepted' })); await settle(el);
    expect(ruleCalls()).toHaveLength(1);
    expect(el.shadowRoot!.activeElement).toBe(battery);
    expect(el.shadowRoot!.textContent).toContain('Reading rules saved');
  });
  it('D4 preserves both rules through approval and uses the approved preparation key', async () => {
    const el = await mount(); setAuthorizationPresenter(vi.fn().mockResolvedValue({ approved: true, allowAlways: false }));
    fetchMock.mockImplementation(async (url: RequestInfo | URL, init?: RequestInit) => {
      if (String(url).endsWith('/authorizations/approve')) return Response.json({ capsule: 'yes', operationKey: 'key', preparationNonce: 'nonce' });
      return JSON.parse(String(init?.body)).confirmationToken ? Response.json({ success: true, message: 'Accepted' })
        : Response.json({ success: false, errorClass: 'CONFIRMATION_REQUIRED', pendingId: 'p', operationKey: 'key', preparationNonce: 'nonce' }, { status: 428 });
    });
    await edit(el, '#indexing-battery', false); const requests = ruleCalls().map(c => JSON.parse(String(c[1].body)));
    expect(requests).toHaveLength(2); expect(requests[1].args).toEqual(requests[0].args);
    expect(requests[1]).toMatchObject({ preparationNonce: 'nonce', idempotencyKey: 'key', confirmationToken: 'yes' });
  });
  it('D4 changing battery preserves an absent schedule until the hours are edited', async () => {
    fetchMock.mockImplementation(async (url: RequestInfo | URL) => String(url).endsWith('/api/settings/v2')
      ? Response.json({ ui: { indexingPause: { paused: true, reason: 'USER_PAUSED', pauseOnBattery: false } }, witness })
      : Response.json({ success: true, message: 'Accepted' }));
    const el = await mount(); await edit(el, '#indexing-battery', true);
    expect(JSON.parse(String(ruleCalls()[0]![1].body)).args).toEqual({ schedule: null, pauseOnBattery: true, witness });
    await edit(el, '#indexing-hours', true);
    expect(JSON.parse(String(ruleCalls()[1]![1].body)).args.schedule).toMatchObject({ enabled: true, startMinute: 540, endMinute: 1020 });
  });
  it('D6 keeps battery available with unknown power and explains the rule', async () => {
    const el = await mount(); expect(input(el, '#indexing-battery').disabled).toBe(false);
    expect(input(el, '#indexing-battery').closest('fieldset')?.disabled).toBe(false);
    expect(el.shadowRoot!.textContent).toContain('known to run on battery'); expect(el.shadowRoot!.textContent).toContain('power source is unknown');
    await edit(el, '#indexing-battery', false);
    expect(JSON.parse(String(ruleCalls()[0]![1].body)).args).toEqual({ schedule: saved, pauseOnBattery: false, witness });
  });
  for (const destination of ['hours', 'battery'] as const) {
    it(`S38.7 focuses ${destination} on Library even from Browse`, async () => {
      const el = await mount('jf-library-surface') as LibrarySurface;
      el.activeTab = 'core.browse-surface'; await settle(el);
      await el.focusIndexingRule(destination);
      const editor = el.shadowRoot!.querySelector<IndexingRulesEditor>('jf-indexing-rules-editor')!;
      await settle(editor);
      expect(el.activeTab).toBe('folders');
      expect(editor.shadowRoot!.activeElement?.id).toBe(destination === 'hours' ? 'indexing-hours' : 'indexing-battery');
    });
  }
});

describe('WP3.8b policy status and actions', () => {
  for (const reason of ['schedule', 'on_battery']) for (const state of ['PAUSING', 'PAUSED']) {
    it(`D5 ${state} ${reason} names the rule with an empty queue and offers its Library action`, async () => {
      observe([reason], state, 0); const el = await mount('jf-settings-surface'); const c = await control(el);
      expect(el.shadowRoot!.textContent).toContain(reason === 'schedule' ? 'Outside reading hours.' : 'Paused while this computer is running on battery.');
      expect(el.shadowRoot!.textContent).not.toContain('Ready'); expect(c.shadowRoot!.textContent).not.toContain('Resume reading');
      const navigate = vi.fn(); document.addEventListener('jf-navigate-to-surface', navigate);
      try { action(c, reason === 'schedule' ? 'Change hours' : 'Change battery setting').click(); }
      finally { document.removeEventListener('jf-navigate-to-surface', navigate); }
      expect(navigate).toHaveBeenCalledWith(expect.objectContaining({ detail: { surfaceId: 'core.library-surface' } }));
      expect(takeIndexingRuleFocus()).toBe(reason === 'schedule' ? 'hours' : 'battery');
      const p = selectIndexingProgress(status([reason], state, 0), true, 0, []);
      expect(p.phase).toBe(state === 'PAUSING' ? 'pausing' : 'paused'); expect(p.etaSeconds).toBeNull();
    });
  }
  it('D5 orders combined reasons and restricts recovery actions', async () => {
    observe(['on_battery', 'schedule', 'user_paused', 'settings_recovery']); const el = await mount('jf-settings-surface'); const c = await control(el);
    const text = el.shadowRoot!.textContent!;
    expect(text.indexOf('Settings recovery')).toBeLessThan(text.indexOf('Paused by you'));
    expect(text.indexOf('Paused by you')).toBeLessThan(text.indexOf('Outside reading hours'));
    expect(text.indexOf('Outside reading hours')).toBeLessThan(text.indexOf('Paused while this computer'));
    expect(c.shadowRoot!.textContent).toContain('Open Settings at Reset to defaults'); expect(c.shadowRoot!.textContent).not.toContain('Change hours');
  });
  it('D5 names every folder rule without Waiting for Resume and preserves failure evidence', () => {
    for (const reasons of [['schedule'], ['on_battery'], ['schedule', 'on_battery']]) {
      const row = folderStatus({ status: 'indexed', fileCount: 10, inFlightCount: 3, failedCount: 2 } as IndexedRootView,
        { relativeTime: '', verifiedRelativeTime: '', provisional: false, indexingPaused: true, indexingPauseReason: reasons[0], indexingPauseReasons: reasons });
      expect(row.metaText).not.toContain('Waiting for Resume');
      if (reasons.includes('schedule')) expect(row.metaText).toContain('reading hours');
      if (reasons.includes('on_battery')) expect(row.metaText).toContain('battery rule'); expect(row.failed).toBe(2);
    }
  });
  it('S38.5 Resume clears manual only and announces both remaining rules after confirmed status', async () => {
    observe(['user_paused', 'schedule', 'on_battery']); const el = await mount('jf-settings-surface'); const c = await control(el);
    expect(c.shadowRoot!.textContent).toContain('Change hours'); expect(c.shadowRoot!.textContent).toContain('Change battery setting');
    action(c, 'Resume reading').click(); await settle(c);
    expect(fetchMock.mock.calls.some(call => String(call[0]).endsWith('/core.indexing-resume/invoke'))).toBe(true);
    observe(['schedule', 'on_battery']); await settle(el); await settle(c);
    expect(c.shadowRoot!.querySelector('[role=status]')?.textContent).toContain('Reading is still held by reading hours and battery power');
    expect(el.shadowRoot!.textContent).not.toContain('Paused by you'); expect(el.shadowRoot!.textContent).not.toContain('Work is waiting for Resume');
  });
  it('S38.5 waits for manual-pause removal before announcing it cleared', async () => {
    observe(['user_paused', 'schedule']); const el = await mount('jf-settings-surface'); const c = await control(el);
    action(c, 'Resume reading').click(); await settle(c);
    observe(['user_paused', 'schedule']); await settle(el); await settle(c);
    expect(c.shadowRoot!.querySelector('[role=status]')?.textContent).toContain('Waiting for status');
    expect(c.shadowRoot!.querySelector('[role=status]')?.textContent).not.toContain('Manual pause cleared');
    observe(['schedule']); await settle(el); await settle(c);
    expect(c.shadowRoot!.querySelector('[role=status]')?.textContent).toContain('Manual pause cleared');
  });
  it('D5 does not invoke a focused Resume when manual pause clears leaving hours', async () => {
    observe(['user_paused', 'schedule']); const el = await mount('jf-settings-surface'); const c = await control(el);
    const resume = action(c, 'Resume reading'); resume.focus(); observe(['schedule']); await settle(el); await settle(c);
    expect(c.shadowRoot!.activeElement).toBe(resume); expect(resume.getAttribute('aria-disabled')).toBe('true');
    expect(c.shadowRoot!.textContent).toContain('still held by reading hours'); resume.click(); await settle(c);
    expect(fetchMock.mock.calls.some(call => String(call[0]).endsWith('/core.indexing-resume/invoke'))).toBe(false);
  });
  // The retired task tray's hold row now lives in the status panel's Needs you section.
  for (const tag of ['jf-library-surface', 'jf-status-pill']) {
    it(`D5 ${tag} names schedule holds for queued work`, async () => {
      observe(['schedule']); const mounted = await mount(tag);
      if (mounted instanceof StatusPill) { mounted.open = true; await settle(mounted); }
      const el = mounted instanceof StatusPill ? mounted.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')! : mounted;
      await settle(el);
      expect(el.shadowRoot!.textContent).toContain('Outside reading hours');
      expect(el.shadowRoot!.textContent).not.toContain('Work is waiting for Resume'); const controls = [...el.shadowRoot!.querySelectorAll<Mounted>('jf-indexing-controls')];
      for (const c of controls) await settle(c);
      const remedy = tag === 'jf-library-surface' ? controls.find(c => !c.hasAttribute('toolbar')) : controls[0];
      expect(remedy?.shadowRoot!.textContent).toContain(tag === 'jf-library-surface' ? 'Change when to read files' : 'Change hours');
      if (tag === 'jf-library-surface') expect(controls[0]!.shadowRoot!.textContent).toContain('Pause reading');
    });
  }
});
