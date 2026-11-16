// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Slice 6c: "Read waiting files now" under "When to read files" (owner decision Q2, decisions C1'-C4').
import { afterEach, expect, it, vi } from 'vitest';
import '../LibrarySurface.js';
import type { LibrarySurface } from '../LibrarySurface.js';
import { createMockHostApi } from '../../plugin-api/testHostApi.js';
import { getOperationClient } from '../../operations/OperationClient.js';
import { OperationError } from '../../operations/OperationClient.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../../state/aiStateStore.js';
import { selectIndexingProgress } from '../../state/indexingProgress.js';
import type { StatusResponse } from '../../../api/generated/schema-types/index.js';

const ACTION = 'Read waiting files now';
const LINE = 'Reading the waiting files. Your reading rules are unchanged.';
type Hold = { reasons?: string[]; rules?: string[]; waiting?: boolean; active?: boolean; state?: string; stale?: boolean };

function status({ reasons = ['schedule'], rules = reasons.filter(r => r === 'schedule' || r === 'on_battery'),
  waiting = true, active = false, state = active ? 'RUNNING' : 'PAUSED', stale = false }: Hold = {}): StatusSnapshot {
  return { ...(stale ? { meta: { workerRpcStale: true } } : {}),
    operating: { indexing: { state, reasons, reasonCode: null, since: '2026-11-16T00:00:00Z', affects: ['INDEXING', 'VDU'],
      pendingJobs: 3, agingReleaseAt: null, sampledAt: '2026-11-16T00:00:00Z', untilEpochMs: null,
      waitingForResume: false, readingRuleReasons: active ? [] : rules, heldFilesWaiting: !active && waiting,
      release: { active } } },
    worker: { core: { indexedDocuments: 8, ownSearchableDocuments: 8, indexState: 'IDLE', indexHealthy: true, pendingJobs: 3 },
      migration: { migrationState: 'IDLE', pendingReadyJobsCount: 3 } },
    readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } as unknown as StatusSnapshot;
}
async function settle(el: { updateComplete: Promise<unknown> }) {
  for (let i = 0; i < 40; i++) { await Promise.resolve(); await el.updateComplete; }
}
async function library(hold: Hold = {}) {
  __feedForTest({ status: status(hold) });
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('{}', { status: 503 }));
  const host = createMockHostApi();
  host.data.fetch = vi.fn(async (path: string) => path.startsWith('/api/indexing-roots/substrate')
    ? Response.json({ items: [{ pathHash: 'slice6c', status: 'indexed', fileCount: 8, fileCountCapped: false,
      failedCount: 0, inFlightCount: 3, walkCompleted: true }] }) : new Response('{}', { status: 503 }));
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = host; el.resolvedPaths = { slice6c: '/Documents' };
  document.body.append(el); await settle(el);
  const editor = el.shadowRoot!.querySelector('jf-indexing-rules-editor') as HTMLElement & { updateComplete: Promise<unknown> };
  expect(editor, 'the reading-rules editor').not.toBeNull();
  await settle(editor);
  return { el, editor };
}
async function feed(hold: Hold, el: LibrarySurface, editor: HTMLElement & { updateComplete: Promise<unknown> }) {
  __feedForTest({ status: status(hold) }); await settle(el); await settle(editor);
}
function action(editor: HTMLElement): HTMLButtonElement | undefined {
  return [...editor.shadowRoot!.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent?.trim() === ACTION);
}
function offered(editor: HTMLElement): HTMLButtonElement {
  const found = action(editor);
  expect(found, 'the action is offered').toBeDefined();
  return found!;
}
function line(editor: HTMLElement): string {
  return editor.shadowRoot!.getElementById('read-now-status')?.textContent?.trim() ?? '';
}
function answer(outcome: string, reason?: string) {
  return vi.spyOn(getOperationClient(''), 'invokeWithConsent').mockResolvedValue({ success: true, message: outcome,
    structuredData: { outcome, ...(reason ? { reason } : {}) } } as never);
}
afterEach(() => { document.body.replaceChildren(); __resetAiStateForTest(); vi.restoreAllMocks(); });

it('S6c-1: outside reading hours with files waiting, the text action sits under "When to read files" and reads them without changing the rules', async () => {
  const invoke = answer('started');
  const { el, editor } = await library();
  const section = editor.shadowRoot!.querySelector('section[aria-labelledby="when-to-index"]')!;
  expect(section.querySelector('#when-to-index')?.textContent).toBe('When to read files');
  const button = action(editor);
  expect(button, 'the action is offered').toBeDefined();
  expect(section.contains(button!)).toBe(true);
  expect(button!.getAttribute('type')).toBe('button');
  expect(button!.closest('form'), 'not part of the rules form').toBeNull();
  const fieldset = section.querySelector('fieldset')!;
  expect(fieldset.compareDocumentPosition(button!) & Node.DOCUMENT_POSITION_FOLLOWING, 'below the rule choices').toBeTruthy();
  button!.focus(); button!.click(); await settle(el); await settle(editor);
  expect(invoke).toHaveBeenCalledTimes(1);
  expect(invoke.mock.calls[0]![0]).toBe('core.indexing-read-now');
  expect(invoke.mock.calls[0]![1]).toMatchObject({ args: {} });
  expect(invoke.mock.calls.some(call => call[0] === 'core.set-indexing-rules'), 'the rules are not written').toBe(false);
  expect(action(editor), 'the line replaces the action').toBeUndefined();
  expect(line(editor)).toBe(LINE);
  expect(editor.shadowRoot!.getElementById('read-now-status')!.getAttribute('role')).toBe('status');
  expect(editor.shadowRoot!.activeElement?.id, 'focus stays at that place').toBe('read-now-status');
  // The release reported by status keeps the line; its end removes it without claiming completion.
  await feed({ active: true }, el, editor);
  expect(line(editor)).toBe(LINE);
  await feed({ waiting: false }, el, editor);
  expect(line(editor)).toBe('');
  expect(action(editor)).toBeUndefined();
  expect(editor.shadowRoot!.textContent).not.toContain('Waiting files read');
});

it.each([
  ['battery', { reasons: ['on_battery'] }],
  ['hours and battery', { reasons: ['schedule', 'on_battery'] }],
  ['hours with an automatic bulk reason', { reasons: ['schedule', 'game_mode'] }],
])('S6c-3: a %s hold offers the action', async (_name, hold) => {
  const { editor } = await library(hold);
  expect(action(editor)).toBeDefined();
});

it.each([
  ['nothing waiting', { waiting: false }],
  ['not held', { reasons: [], rules: [], waiting: false, state: 'IDLE' }],
  ['manual Pause', { reasons: ['user_paused', 'schedule'] }],
  ['settings recovery', { reasons: ['settings_recovery', 'on_battery'] }],
  ['automatic holds only', { reasons: ['game_mode'], rules: [], waiting: true }],
  ['stale status', { stale: true }],
])('S6c-2/7: %s shows no action and no disabled substitute', async (_name, hold) => {
  const { editor } = await library(hold);
  expect(action(editor)).toBeUndefined();
  expect(editor.shadowRoot!.textContent).not.toContain('Nothing is waiting');
  expect(line(editor)).toBe('');
});

it('S6c-5: another window that sees the release shows the line, not the action; a second click joins', async () => {
  const { editor } = await library({ active: true });
  expect(action(editor)).toBeUndefined();
  expect(line(editor)).toBe(LINE);
  const invoke = answer('joined');
  const second = await library();
  offered(second.editor).click(); await settle(second.el); await settle(second.editor);
  expect(invoke).toHaveBeenCalledTimes(1);
  expect(line(second.editor)).toBe(LINE);
});

it('S6c-4: Pause during the release removes the line; the hold is said as before', async () => {
  const { el, editor } = await library({ active: true });
  expect(line(editor)).toBe(LINE);
  await feed({ reasons: ['user_paused', 'schedule'] }, el, editor);
  expect(line(editor)).toBe('');
  expect(action(editor)).toBeUndefined();
});

it('S6c-7: a stale click that finds nothing says so once, in place, with focus on it', async () => {
  answer('nothing-waiting');
  const { el, editor } = await library();
  const button = offered(editor);
  button.focus(); button.click(); await settle(el); await settle(editor);
  expect(line(editor)).toBe('Nothing is waiting to be read.');
  expect(action(editor)).toBeUndefined();
  expect(editor.shadowRoot!.activeElement?.id).toBe('read-now-status');
  await feed({ waiting: false }, el, editor);
  expect(line(editor)).toBe('');
});

it('C4\': not-held refreshes status without words; a failure uses the existing reading-request words', async () => {
  answer('not-held', 'paused');
  const { el, editor } = await library();
  offered(editor).click(); await settle(el); await settle(editor);
  expect(line(editor)).toBe('');
  vi.restoreAllMocks();
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('{}', { status: 503 }));
  vi.spyOn(getOperationClient(''), 'invokeWithConsent').mockRejectedValue(new OperationError('Engine unavailable', 'NETWORK_ERROR'));
  offered(editor).click(); await settle(el); await settle(editor);
  expect(line(editor)).toContain('Could not confirm the reading request. Check the current status before trying again.');
  expect(action(editor), 'the action stays offered after a failure').toBeDefined();
});

it('C4\': when the action disappears while focused, focus moves to the "When to read files" heading', async () => {
  const { el, editor } = await library();
  offered(editor).focus();
  expect(editor.shadowRoot!.activeElement?.textContent?.trim()).toBe(ACTION);
  await feed({ waiting: false }, el, editor);
  expect(action(editor)).toBeUndefined();
  expect(editor.shadowRoot!.activeElement?.id).toBe('when-to-index');
});

it('C3\': the shared projection never says "Reading paused" while released and offers only on fresh status', () => {
  const released = selectIndexingProgress(status({ active: true }) as unknown as StatusResponse, true, 0, []);
  expect(released.pause).toBeNull();
  expect(released.activity.summary).not.toBe('Reading paused');
  expect(released.readWaiting).toEqual({ offered: false, active: true });
  expect(selectIndexingProgress(status() as unknown as StatusResponse, true, 0, []).readWaiting)
    .toEqual({ offered: true, active: false });
  expect(selectIndexingProgress(status() as unknown as StatusResponse, false, 0, []).readWaiting)
    .toEqual({ offered: false, active: false });
});

it.each([
  ['hours', 'game_mode'], ['hours', 'energy_saver'], ['hours', 'gpu_busy_external'],
  ['battery', 'game_mode'], ['battery', 'energy_saver'], ['battery', 'gpu_busy_external'],
])('RV6c-3: released under %s with %s, the shared pause derivation never says "Reading paused"', async (rule, bulk) => {
  const hold = { active: true, reasons: [rule === 'hours' ? 'schedule' : 'on_battery', bulk], state: 'PAUSED' };
  const released = selectIndexingProgress(status(hold) as unknown as StatusResponse, true, 0, []);
  expect(released.pause, 'no reading-rule pause while released').toBeNull();
  expect(released.activity.summary).not.toBe('Reading paused');
  expect(released.readWaiting).toEqual({ offered: false, active: true });
  const { el, editor } = await library(hold);
  expect(line(editor)).toBe(LINE);
  expect(el.shadowRoot!.textContent).not.toContain('Reading paused');
});

it('RV6c-4: after "Nothing is waiting", the next fresh status offering waiting work shows the action again', async () => {
  const invoke = answer('nothing-waiting');
  const { el, editor } = await library();
  offered(editor).click(); await settle(el); await settle(editor);
  expect(line(editor)).toBe('Nothing is waiting to be read.');
  expect(action(editor)).toBeUndefined();
  // New files arrived before the next sample: fresh status keeps offering, so the action returns.
  await feed({}, el, editor);
  expect(line(editor)).toBe('');
  invoke.mockResolvedValue({ success: true, message: 'started', structuredData: { outcome: 'started' } } as never);
  offered(editor).click(); await settle(el); await settle(editor);
  expect(invoke).toHaveBeenCalledTimes(2);
  expect(line(editor)).toBe(LINE);
});
