// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Slice 6b-2: Library's skip rules and reading hours save as you change them (picture note fm-07).
// Every saved-state case observes the exact bytes sent and the fake settings owner's patch semantics.
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import '../LibrarySurface.js';
import type { LibrarySurface } from '../LibrarySurface.js';
import type { IndexingRulesEditor } from '../../components/IndexingRulesEditor.js';
import { createMockHostApi } from '../../plugin-api/testHostApi.js';
import { __resetOwnReversalsForTest, listOpenReversals } from '../../state/ownReversals.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../../state/aiStateStore.js';
import { EPHEMERAL_TOAST_EVENT } from '../../components/advisory/ephemeralToast.js';
import { OperationError } from '../../operations/OperationClient.js';

type Witness = { acceptedRevision: number; lastCommittedOperationKey: string | null };
type Schedule = { enabled: boolean; days: number[]; startMinute: number; endMinute: number };
type Pause = { paused: boolean; untilEpochMs: number | null; reason: string | null; schedule?: Schedule; pauseOnBattery: boolean };
type Policy = { source: string; sourceDetail: string | null; patterns: string[] } | null;
const key = (n: number) => `0194f72c-0000-7000-8000-${String(n).padStart(12, '0')}`;
const ASK = 'Remove files already read that match your skip rules from search? Files on disk are not touched.';
const literal = { literal: { version: 1, id: 'aa10bcec-4102-47f8-a26d-d02346c8a210', path: '/docs/report[1].txt ', scope: 'FILE' } };
const doneResult = { dryRun: false, matchedFiles: 3, confirmedRemovals: 3, capped: false,
  perPattern: [{ pattern: '*.tmp', matches: 3 }], uncheckedScopes: [] };

/** One settings owner shared by every window: witness, keyed replay, refusals, patch-only writes. */
function backend(initial: { patterns?: unknown[]; pause?: Partial<Pause>; override?: Policy } = {}) {
  const s = {
    revision: 3, lastKey: key(3) as string | null,
    ui: { theme: 'dark', mode: 'simple', excludePatterns: initial.patterns ?? ['*.log'] as unknown[],
      indexingPause: { paused: false, untilEpochMs: null, reason: null, pauseOnBattery: false, ...initial.pause } as Pause },
  };
  const receipts = new Map<string, unknown>();
  const posts: { body: string; parsed: Record<string, unknown> }[] = [];
  const ops: { id: string; args: Record<string, unknown>; key?: string }[] = [];
  let hold: Promise<void> | null = null, release = () => {};
  let hang = false, workerDown = false, loseNextOpAnswer = false;
  let onRead: (() => void) | null = null;
  /** Answers given instead of running the operation, in order; 'lost' commits nothing and loses the answer. */
  const scripted: unknown[] = [];
  const opReceipts = new Map<string, unknown>();
  const removedFor: unknown[][] = [];
  const witness = (): Witness => ({ acceptedRevision: s.revision, lastCommittedOperationKey: s.lastKey });
  const commit = (operationKey: string | null) => { s.revision++; s.lastKey = operationKey ?? key(900 + s.revision); };
  const wire = () => structuredClone(s.ui);
  const glob = (rule: string) => { let open = 0; for (const c of rule) { if (c === '[') open++; if (c === ']') open = Math.max(0, open - 1); } return open === 0; };
  const fetchSettings = vi.fn(async (path: string, init?: { method?: string; body?: string }) => {
    if (path === '/api/indexing/excludes') return Response.json(initial.override ?? { source: 'settings.json', sourceDetail: null,
      patterns: s.ui.excludePatterns.filter(p => typeof p === 'string') });
    if (path.startsWith('/api/indexing/forgets?')) return Response.json({ items: [], complete: true, nextId: null });
    if (path !== '/api/settings/v2') return Response.json({ items: [] });
    if (init?.method !== 'POST') {
      const observed = Response.json({ settingsMode: 'persistent', ui: wire(), witness: witness() });
      const hook = onRead; onRead = null; hook?.();
      return observed;
    }
    const body = String(init.body);
    const attempt = JSON.parse(body) as { operationKey: string; witness: Witness; ui?: { excludePatterns?: unknown[] } };
    posts.push({ body, parsed: attempt });
    if (hang) return new Promise<Response>(() => {});
    if (hold) await hold;
    const replay = receipts.get(attempt.operationKey);
    if (replay) return Response.json(replay);
    if (attempt.witness.acceptedRevision !== s.revision || attempt.witness.lastCommittedOperationKey !== s.lastKey) {
      return Response.json({ state: 'FAILED', errorCode: 'VERSION_CONFLICT' }, { status: 409 });
    }
    const next = attempt.ui?.excludePatterns;
    if (next) {
      const before = s.ui.excludePatterns;
      if (next.length > 512) return Response.json({ errorCode: 'INVALID_REQUEST', error: 'Invalid settings request' }, { status: 400 });
      for (const entry of next) {
        // Exact-path entries may only be kept or removed (compared by value, as the settings owner does).
        const same = (b: unknown) => typeof b !== 'string' && JSON.stringify(Object.entries((b as typeof literal).literal).sort())
          === JSON.stringify(Object.entries((entry as typeof literal).literal).sort());
        if (typeof entry === 'string' ? !glob(entry) && !before.includes(entry) : !before.some(same)) {
          return Response.json({ errorCode: 'INVALID_REQUEST', error: 'Invalid settings request' }, { status: 400 });
        }
      }
      s.ui = { ...s.ui, excludePatterns: next };
    }
    commit(attempt.operationKey);
    const receipt = { state: 'COMPLETE', operationKey: attempt.operationKey, witness: witness(), ui: wire() };
    receipts.set(attempt.operationKey, receipt);
    return Response.json(receipt);
  });
  const operations = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
    const id = String(url).split('/').at(-2)!;
    const { args = {}, idempotencyKey } = JSON.parse(String(init?.body ?? '{}')) as { args?: Record<string, unknown>; idempotencyKey?: string };
    ops.push({ id, args, key: idempotencyKey });
    if (hold) await hold;
    if (scripted.length) {
      const answer = scripted.shift();
      if (answer === 'lost') throw new TypeError('Failed to fetch');
      return Response.json(answer);
    }
    const replayed = idempotencyKey ? opReceipts.get(idempotencyKey) : undefined;
    if (replayed) return Response.json(replayed);
    if (id === 'core.set-indexing-rules') {
      const based = args.witness as Witness | undefined;
      if (based && (based.acceptedRevision !== s.revision || based.lastCommittedOperationKey !== s.lastKey)) {
        return Response.json({ success: false, message: 'Settings changed since rules were prepared', errorClass: 'HANDLER_FAILURE',
          errorCode: 'VERSION_CONFLICT' });
      }
      const pause = { ...s.ui.indexingPause, pauseOnBattery: args.pauseOnBattery as boolean };
      if (args.schedule) pause.schedule = args.schedule as Schedule; else delete pause.schedule;
      s.ui = { ...s.ui, indexingPause: pause };
    } else if (id === 'core.indexing-pause' || id === 'core.indexing-resume') {
      s.ui = { ...s.ui, indexingPause: { ...s.ui.indexingPause, paused: id === 'core.indexing-pause',
        reason: id === 'core.indexing-pause' ? 'USER_PAUSED' : null, untilEpochMs: null } };
    } else return Response.json({ success: false, message: 'unknown', errorClass: 'HANDLER_FAILURE' });
    commit(idempotencyKey ?? null);
    const receipt = { success: true, message: 'Committed', structuredData: { witness: witness() } };
    if (idempotencyKey) opReceipts.set(idempotencyKey, receipt);
    if (loseNextOpAnswer) { loseNextOpAnswer = false; throw new TypeError('Failed to fetch'); }
    return Response.json(receipt);
  });
  vi.stubGlobal('fetch', operations);
  const invoke = vi.fn(async (id: string, args?: Record<string, unknown>, _options?: unknown) => {
    if (workerDown) throw Object.assign(new Error('Failed to fetch'), { errorClass: 'NETWORK_ERROR' });
    if (id === 'core.apply-excludes' && args && 'rules' in args) {
      // The engine applies only the list consented to; another list in effect removes nothing.
      if (JSON.stringify(args.rules) !== JSON.stringify(s.ui.excludePatterns)) {
        throw new OperationError('The skip rules changed since you were asked', 'HANDLER_FAILURE', 200, 'EXCLUDES_CHANGED');
      }
      removedFor.push(structuredClone(args.rules as unknown[]));
    }
    return { success: true, structuredData: id === 'core.preview-excludes' ? { ...doneResult, dryRun: true } : doneResult };
  });
  const host = createMockHostApi({ data: { fetch: fetchSettings as never, invokeOperation: invoke as never } });
  return {
    s, host, posts, ops, invoke, fetchSettings, removedFor,
    /** Runs once, right after the next settings read is answered (another window acting in between). */
    afterNextRead(hook: () => void) { onRead = hook; },
    /** The next operations are answered with these, in order, without running ('lost': no answer). */
    scriptOpAnswers(...answers: unknown[]) { scripted.push(...answers); },
    /** The next operation commits, but its answer is lost on the way back. */
    loseNextOpAnswer() { loseNextOpAnswer = true; },
    /** Another window (or an unrelated owner) commits through the same settings owner. */
    elsewhere(change: (ui: typeof s.ui) => typeof s.ui) { s.ui = change(structuredClone(s.ui)); commit(null); },
    holdWrites() { hold = new Promise(resolve => { release = () => { hold = null; resolve(); }; }); },
    release: () => release(),
    hangWrites(on: boolean) { hang = on; },
    stopWorker() { workerDown = true; },
  };
}
type Backend = ReturnType<typeof backend>;

async function settle(el?: { updateComplete: Promise<unknown> }) {
  for (let i = 0; i < 40; i++) { await Promise.resolve(); await el?.updateComplete; }
  await new Promise(resolve => setTimeout(resolve, 0));
  for (let i = 0; i < 10; i++) { await Promise.resolve(); await el?.updateComplete; }
}
async function mount(f: Backend): Promise<LibrarySurface> {
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = f.host;
  Object.assign(el, { refresh: vi.fn().mockResolvedValue(true), loading: false });
  document.body.append(el); await settle(el); await settle(editor(el)); await settle(el);
  return el;
}
function editor(el: LibrarySurface): IndexingRulesEditor {
  const found = el.shadowRoot?.querySelector<IndexingRulesEditor>('jf-indexing-rules-editor');
  expect(found, 'Library holds the reading-hours editor').toBeTruthy(); return found!;
}
function rule(el: LibrarySurface, n: number): HTMLInputElement {
  const found = el.shadowRoot?.querySelector<HTMLInputElement>(`input[aria-label="Skip rule ${n}"]`);
  expect(found, `Skip rule ${n}`).toBeTruthy(); return found!;
}
/** Visible words or accessible name of a native or jf-button control. */
function named(root: ParentNode | null | undefined, name: string): HTMLElement | undefined {
  expect(root, `root for ${name}`).toBeTruthy();
  return [...root!.querySelectorAll<HTMLElement>('button, jf-button')].find(c =>
    c.textContent?.trim() === name || c.getAttribute('aria-label') === name || c.getAttribute('label') === name);
}
function control(root: ParentNode | null | undefined, name: string): HTMLElement {
  const found = named(root, name); expect(found, name).toBeDefined(); return found!;
}
async function press(el: LibrarySurface | IndexingRulesEditor, target: HTMLElement) {
  if (target.tagName === 'JF-BUTTON') await (target as HTMLElement & { onActivate: () => unknown }).onActivate();
  else target.click();
  await settle(el);
}
async function type(el: LibrarySurface, field: HTMLInputElement, value: string) {
  field.focus(); field.value = value; field.dispatchEvent(new Event('input', { bubbles: true, composed: true })); await settle(el);
}
async function leave(el: LibrarySurface, field: HTMLElement) { field.blur(); await settle(el); }
const text = (root: ParentNode | null | undefined) => (root?.textContent ?? '').replace(/\s+/g, ' ');
const askRegion = (el: LibrarySurface) => [...(el.shadowRoot?.querySelectorAll<HTMLElement>('[role="region"]') ?? [])]
  .filter(region => region.getAttribute('aria-label') === ASK);
async function addRule(el: LibrarySurface, value: string) {
  await press(el, control(el.shadowRoot, 'Add a rule'));
  const fields = el.shadowRoot!.querySelectorAll<HTMLInputElement>('input[aria-label^="Skip rule "]');
  const last = fields[fields.length - 1]!;
  await type(el, last, value); await leave(el, last);
}
const globsSent = (f: Backend) => f.posts.map(p => (p.parsed.ui as { excludePatterns: unknown[] }).excludePatterns);

beforeEach(() => { __resetOwnReversalsForTest(); });
afterEach(async () => {
  document.body.replaceChildren();
  for (let i = 0; i < 5; i++) await new Promise(resolve => setTimeout(resolve, 0));
  __resetAiStateForTest(); __resetOwnReversalsForTest(); setShowDeveloperTools(false);
  vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.useRealTimers();
});

it('S6b-1: no Save, Reload, Preview or Apply row; first run writes nothing on open', async () => {
  const f = backend({ patterns: [] }); const el = await mount(f);
  for (const name of ['Save patterns', 'Reload saved patterns', 'Preview', 'Apply', 'Save reading rules']) {
    expect(named(el.shadowRoot, name), name).toBeUndefined();
    expect(named(editor(el).shadowRoot, name), name).toBeUndefined();
  }
  const field = rule(el, 1); field.focus(); await leave(el, field);
  const any = editor(el).shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery');
  expect(any).toBeTruthy(); any!.focus(); any!.blur(); await settle(el);
  el.remove(); await settle();
  expect(f.posts).toEqual([]); expect(f.ops).toEqual([]);
});

it('S6b-1: adding a rule and leaving the field saves exactly that list, other settings unchanged, and it survives a restart', async () => {
  const f = backend({ patterns: ['*.log', literal] }); const el = await mount(f);
  await addRule(el, '*.tmp');
  expect(f.posts).toHaveLength(1);
  const sent = f.posts[0]!.parsed;
  expect(Object.keys(sent).sort()).toEqual(['operationKey', 'ui', 'witness']);
  expect(sent.ui).toEqual({ excludePatterns: ['*.log', '*.tmp', literal] });
  expect(sent.witness).toEqual({ acceptedRevision: 3, lastCommittedOperationKey: key(3) });
  expect(f.s.ui.theme).toBe('dark'); expect(f.s.ui.mode).toBe('simple');
  el.remove(); await settle();
  expect(f.posts).toHaveLength(1);
  const again = await mount(f);
  expect(rule(again, 2).value).toBe('*.tmp');
  expect(again.shadowRoot!.querySelector('section[aria-label="Exact paths kept out"] code')?.textContent).toContain('/docs/report[1].txt');
});

it('S6b-1: a blank new row writes nothing; deleting the last rule saves an empty list', async () => {
  const f = backend({ patterns: ['*.log'] }); const el = await mount(f);
  await press(el, control(el.shadowRoot, 'Add a rule'));
  await leave(el, rule(el, 2));
  expect(f.posts).toEqual([]);
  await press(el, control(el.shadowRoot, 'Remove skip rule 2'));
  await press(el, control(el.shadowRoot, 'Remove skip rule 1'));
  expect(globsSent(f)).toEqual([[]]);
  expect(f.s.ui.excludePatterns).toEqual([]);
});

it('S6b-1: exact-path entries keep their id, scope and bytes across glob saves', async () => {
  const folder = { literal: { version: 1, id: 'aa10bcec-4102-47f8-a26d-d02346c8a211', path: '/docs/folder ', scope: 'FOLDER' } };
  const f = backend({ patterns: ['*.log', literal, folder] }); const el = await mount(f);
  await type(el, rule(el, 1), '*.logs'); await leave(el, rule(el, 1));
  await addRule(el, 'build/');
  expect(globsSent(f)).toEqual([['*.logs', literal, folder], ['*.logs', 'build/', literal, folder]]);
  expect(f.s.ui.excludePatterns.slice(2)).toEqual([literal, folder]);
  expect((f.s.ui.excludePatterns[2] as typeof literal).literal.path).toBe('/docs/report[1].txt ');
  expect((f.s.ui.excludePatterns[3] as typeof folder).literal.path).toBe('/docs/folder ');
});

it('S6b-2: adding a rule asks in place, without taking focus; nothing is removed without the answer; Keep them keeps files', async () => {
  const f = backend(); const el = await mount(f);
  await press(el, control(el.shadowRoot, 'Add a rule'));
  const field = rule(el, 2); await type(el, field, '*.tmp');
  const next = control(el.shadowRoot, 'Add a rule'); next.focus(); await settle(el);
  expect(globsSent(f)).toEqual([['*.log', '*.tmp']]);
  const asks = askRegion(el);
  expect(asks, 'one in-place ask').toHaveLength(1);
  expect(text(asks[0])).toContain(ASK);
  expect(asks[0]!.closest('[aria-live="polite"]'), 'announced politely').toBeTruthy();
  expect(el.shadowRoot!.querySelector('[role="dialog"], dialog')).toBeNull();
  expect(el.shadowRoot!.activeElement === next || el.shadowRoot!.activeElement === null).toBe(true);
  expect(f.invoke).not.toHaveBeenCalled();
  await press(el, control(asks[0], 'Keep them'));
  expect(askRegion(el)).toHaveLength(0);
  expect(f.invoke).not.toHaveBeenCalled();
  expect(text(el.shadowRoot)).toContain('stay searchable');
});

it('S6b-2 B4: Remove from search applies the saved rules and says it in plain words, including what was not checked', async () => {
  const f = backend(); const el = await mount(f);
  await addRule(el, '*.tmp');
  const ask = askRegion(el); expect(ask).toHaveLength(1);
  await press(el, control(ask[0], 'Remove from search'));
  expect(f.invoke).toHaveBeenCalledTimes(1);
  expect(f.invoke).toHaveBeenCalledWith('core.apply-excludes', { rules: ['*.log', '*.tmp'] }, { consented: true });
  expect(askRegion(el)).toHaveLength(0);
  const said = text(el.shadowRoot);
  expect(said).toContain('Done. Files already read that match your skip rules are no longer in search');
  expect(said, 'known limitation is stated').toContain('no longer on disk');
  expect(said).not.toContain('core.apply-excludes');
});

it('B4: a partial removal names what was not checked in plain words and offers Try again', async () => {
  const f = backend(); const el = await mount(f);
  f.invoke.mockResolvedValueOnce({ success: true, structuredData: { ...doneResult, confirmedRemovals: 1, uncheckedScopes: [
    { path: '/offline', status: 'not_checked', reason: 'Watched folder is offline or missing' },
    { path: '/docs/locked.tmp', status: 'not_checked', reason: 'Unreadable' },
    { path: '/docs/refused.tmp', status: 'not_removed', reason: 'Engine refused deletion' }] } } as never);
  await addRule(el, '*.tmp');
  await press(el, control(askRegion(el)[0], 'Remove from search'));
  const said = text(el.shadowRoot);
  expect(said).toContain('Partly done');
  for (const words of ['/offline', '/docs/locked.tmp', '/docs/refused.tmp']) expect(said).toContain(words);
  expect(said).toMatch(/offline/i); expect(said).toMatch(/couldn.t be read|unreadable/i); expect(said).toMatch(/couldn.t be removed/i);
  await press(el, control(el.shadowRoot, 'Try again'));
  expect(f.invoke).toHaveBeenCalledTimes(2);
});

it('B4: a bare success receipt is never shown as a complete removal', async () => {
  const f = backend(); const el = await mount(f);
  f.invoke.mockResolvedValueOnce({ success: true } as never);
  await addRule(el, '*.tmp');
  await press(el, control(askRegion(el)[0], 'Remove from search'));
  const said = text(el.shadowRoot);
  expect(said).toContain('Not sure');
  expect(said).not.toMatch(/removed from search\./);
  expect(named(el.shadowRoot, 'Try again')).toBeDefined();
});

it('S6b-7: with the Worker down a skip rule still saves through the Head and removal says it cannot run now', async () => {
  const f = backend(); f.stopWorker(); const el = await mount(f);
  await addRule(el, '*.tmp');
  expect(f.s.ui.excludePatterns).toEqual(['*.log', '*.tmp']);
  await press(el, control(askRegion(el)[0], 'Remove from search'));
  const said = text(el.shadowRoot);
  expect(said).toContain("Couldn't remove files now");
  expect(named(el.shadowRoot, 'Try again')).toBeDefined();
});

it('B2: when the saved list changes while the ask is open, the ask is withdrawn and asked again for the new list', async () => {
  const f = backend(); const el = await mount(f);
  await addRule(el, '*.tmp');
  const first = askRegion(el)[0]; expect(first).toBeDefined();
  // Another window saves a further rule; nothing on this window has refreshed yet.
  f.elsewhere(ui => ({ ...ui, excludePatterns: [...ui.excludePatterns, '*.bak'] }));
  await press(el, control(first, 'Remove from search'));
  expect(f.invoke, 'the old ask does not run over an unasked list').not.toHaveBeenCalled();
  const again = askRegion(el);
  expect(again).toHaveLength(1);
  expect(again[0]).not.toBe(first);
  expect(rule(el, 3).value).toBe('*.bak');
  await press(el, control(again[0], 'Remove from search'));
  expect(f.invoke).toHaveBeenCalledTimes(1);
  // A focus refresh that finds every added rule gone withdraws the ask.
  await addRule(el, '*.new');
  expect(askRegion(el)).toHaveLength(1);
  f.elsewhere(ui => ({ ...ui, excludePatterns: ['*.log'] }));
  window.dispatchEvent(new Event('focus')); await settle(el);
  expect(askRegion(el)).toHaveLength(0);
});

it('B2: one ask at a time across several added rules', async () => {
  const f = backend(); const el = await mount(f);
  await addRule(el, '*.tmp'); await addRule(el, '*.bak');
  expect(askRegion(el)).toHaveLength(1);
  expect(globsSent(f)).toEqual([['*.log', '*.tmp'], ['*.log', '*.tmp', '*.bak']]);
});

it('S6b-3: a half pattern is never saved or applied; the reason shows; fixing it saves', async () => {
  const f = backend(); const el = await mount(f);
  await addRule(el, '[a');
  expect(f.posts).toEqual([]);
  expect(f.invoke).not.toHaveBeenCalled();
  expect(askRegion(el)).toHaveLength(0);
  const field = rule(el, 2);
  expect(field.getAttribute('aria-invalid')).toBe('true');
  const reason = el.shadowRoot!.querySelector(`#${field.getAttribute('aria-describedby')?.split(' ').find(id => id.startsWith('rule-problem'))}`);
  expect(reason, 'the field names its reason').toBeTruthy();
  expect(text(reason)).toContain('[a');
  expect(field.disabled).toBe(false);
  await type(el, field, '[ab]'); await leave(el, field);
  expect(globsSent(f)).toEqual([['*.log', '[ab]']]);
  expect(rule(el, 2).getAttribute('aria-invalid')).toBe('false');
});

it('S6b-3: a legacy saved literal-name rule does not block saving other edits', async () => {
  const f = backend({ patterns: ['[legacy', 'private'] }); const el = await mount(f);
  await type(el, rule(el, 2), 'private2'); await leave(el, rule(el, 2));
  expect(globsSent(f)).toEqual([['[legacy', 'private2']]);
});

it('S6b-4: typing during an in-flight save keeps the field usable, never shows later text as saved, then saves it too', async () => {
  const f = backend(); const el = await mount(f);
  f.holdWrites();
  await type(el, rule(el, 1), '*.logs');
  const add = control(el.shadowRoot, 'Add a rule'); add.focus(); await settle(el);
  expect(f.posts).toHaveLength(1);
  // The person is back in the field and types more while the first save is held.
  const field = rule(el, 1); field.focus();
  await type(el, field, '*.logs2');
  expect(field.disabled).toBe(false);
  expect(el.shadowRoot!.activeElement).toBe(field);
  await leave(el, field);
  expect(f.posts, 'one request in flight at a time').toHaveLength(1);
  f.release(); await settle(el); await settle(el);
  expect(globsSent(f)).toEqual([['*.logs'], ['*.logs2']]);
  expect(f.posts[1]!.parsed.witness).toEqual({ acceptedRevision: 4, lastCommittedOperationKey: f.posts[0]!.parsed.operationKey });
  expect(f.s.ui.excludePatterns).toEqual(['*.logs2']);
});

it('S6b-4: while text is unsaved after an in-flight save it is not called saved', async () => {
  const f = backend(); const el = await mount(f);
  f.holdWrites();
  await type(el, rule(el, 1), 'one'); await leave(el, rule(el, 1));
  const field = rule(el, 1); await type(el, field, 'two');
  f.release(); await settle(el);
  const statuses = () => [...el.shadowRoot!.querySelectorAll('section[aria-label="Exclude patterns"] [role="status"]')].map(text).join(' ');
  expect(statuses()).not.toMatch(/saved/i);
  expect(f.posts).toHaveLength(1);
  await leave(el, field);
  expect(globsSent(f)).toEqual([['one'], ['two']]);
  expect(statuses()).toMatch(/saved/i);
});

it('B3: an unknown outcome says Not sure this saved, keeps the same key and bytes, and reconciles before a newer draft', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  const f = backend(); const el = await mount(f);
  f.hangWrites(true);
  await type(el, rule(el, 1), 'pending'); await leave(el, rule(el, 1));
  await vi.advanceTimersByTimeAsync(10_500); await settle(el);
  expect(text(el.shadowRoot)).toContain('Not sure this saved');
  const retry = control(el.shadowRoot, 'Try again');
  f.hangWrites(false);
  await type(el, rule(el, 1), 'newer');
  await press(el, retry); await settle(el);
  const bodies = f.posts.map(p => p.body);
  expect(bodies[1], 'same key and bytes first').toBe(bodies[0]);
  expect(globsSent(f).slice(1)).toEqual([['pending'], ['newer']]);
  expect(f.s.ui.excludePatterns).toEqual(['newer']);
});

it('B3: leaving Library or switching tabs leaves the field and saves', async () => {
  const f = backend(); const el = await mount(f);
  await type(el, rule(el, 1), 'tab-switch');
  el.shadowRoot!.querySelector('jf-surface-tabs')!.dispatchEvent(new CustomEvent('tab-change', { detail: { id: 'core.browse-surface' } }));
  await settle(el);
  expect(globsSent(f)).toEqual([['tab-switch']]);
  (el as unknown as { activeTab: string }).activeTab = 'folders'; await settle(el);
  await type(el, rule(el, 1), 'leaving');
  el.remove(); await settle();
  expect(globsSent(f)).toEqual([['tab-switch'], ['leaving']]);
});

it('B3: a load failure says so with Try again, and the editor is not editable until loaded', async () => {
  const f = backend();
  const real = f.fetchSettings.getMockImplementation()!;
  let fail = true;
  f.fetchSettings.mockImplementation(async (path, init) => path === '/api/settings/v2' && init?.method !== 'POST' && fail
    ? new Response('{}', { status: 503 }) : real(path, init));
  const el = await mount(f);
  expect(text(el.shadowRoot)).toContain("Couldn't load your skip rules");
  expect(rule(el, 1).disabled).toBe(true);
  fail = false;
  await press(el, control(el.shadowRoot!.querySelector('section[aria-label="Exclude patterns"]'), 'Try again'));
  expect(rule(el, 1).disabled).toBe(false);
  expect(rule(el, 1).value).toBe('*.log');
});

it('S6b-5: another window changed the rules: draft kept, Changed elsewhere offers both choices, no silent overwrite', async () => {
  const f = backend(); const el = await mount(f);
  f.elsewhere(ui => ({ ...ui, excludePatterns: ['*.other'] }));
  await type(el, rule(el, 1), '*.mine'); await leave(el, rule(el, 1));
  expect(f.s.ui.excludePatterns).toEqual(['*.other']);
  expect(rule(el, 1).value).toBe('*.mine');
  const section = el.shadowRoot!.querySelector('section[aria-label="Exclude patterns"]');
  expect(text(section)).toContain('Changed elsewhere');
  expect(text(section)).toContain('*.other');
  const posts = f.posts.length;
  await press(el, control(section, 'Keep my draft'));
  expect(f.posts.length).toBe(posts + 1);
  expect(f.posts.at(-1)!.parsed.witness).toEqual({ acceptedRevision: 4, lastCommittedOperationKey: key(904) });
  expect(f.s.ui.excludePatterns).toEqual(['*.mine']);
});

it('S6b-5: Use the saved rules takes the other window\'s list and writes nothing', async () => {
  const f = backend(); const el = await mount(f);
  f.elsewhere(ui => ({ ...ui, excludePatterns: ['*.other'] }));
  await type(el, rule(el, 1), '*.mine'); await leave(el, rule(el, 1));
  const posts = f.posts.length;
  await press(el, control(el.shadowRoot, 'Use the saved rules'));
  expect(f.posts.length).toBe(posts);
  expect(rule(el, 1).value).toBe('*.other');
  expect(f.s.ui.excludePatterns).toEqual(['*.other']);
});

it('S6b-5: an unrelated save elsewhere does not raise a conflict or lose the draft', async () => {
  const f = backend(); const el = await mount(f);
  f.elsewhere(ui => ({ ...ui, theme: 'light' }));
  await type(el, rule(el, 1), '*.mine'); await leave(el, rule(el, 1));
  expect(text(el.shadowRoot)).not.toContain('Changed elsewhere');
  expect(f.s.ui.excludePatterns).toEqual(['*.mine']);
  expect(f.s.ui.theme).toBe('light');
});

it('512-entry capacity refusal keeps the draft and the saved rules, in plain words', async () => {
  const f = backend(); const el = await mount(f);
  const many = Array.from({ length: 513 }, (_, i) => `file${i}.txt`);
  (el as unknown as { excludesText: string }).excludesText = many.join('\n'); await settle(el);
  rule(el, 1).focus(); await leave(el, rule(el, 1));
  expect(globsSent(f)).toEqual([many]);
  expect(f.s.ui.excludePatterns).toEqual(['*.log']);
  expect((el as unknown as { excludesText: string }).excludesText).toBe(many.join('\n'));
  expect(text(el.shadowRoot)).toContain('512');
});

it('S6b-6: the battery rule saves on change, first change keeps a null schedule, carrying the revision it was based on', async () => {
  const f = backend(); const el = await mount(f);
  const battery = editor(el).shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery');
  expect(battery).toBeTruthy();
  battery!.checked = true; battery!.dispatchEvent(new Event('change')); await settle(editor(el)); await settle(el);
  expect(f.ops.map(o => o.id)).toEqual(['core.set-indexing-rules']);
  expect(f.ops[0]!.args).toEqual({ schedule: null, pauseOnBattery: true, witness: { acceptedRevision: 3, lastCommittedOperationKey: key(3) } });
  expect(f.s.ui.indexingPause.schedule).toBeUndefined();
  const again = await mount(f);
  expect(editor(again).shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!.checked).toBe(true);
});

it('S6b-6: hours save on change; an invalid combination is not sent and says why; turning hours off keeps days and times', async () => {
  const f = backend(); const el = await mount(f); const rules = editor(el);
  const only = rules.shadowRoot!.querySelector<HTMLInputElement>('#indexing-hours'); expect(only).toBeTruthy();
  only!.checked = true; only!.dispatchEvent(new Event('change')); await settle(rules); await settle(el);
  expect(f.ops, 'Only between saves on change').toHaveLength(1);
  expect(f.ops.at(-1)!.args.schedule).toEqual({ enabled: true, days: [1, 2, 3, 4, 5, 6, 7], startMinute: 540, endMinute: 1020 });
  const [start, end] = [...rules.shadowRoot!.querySelectorAll<HTMLInputElement>('input[type=time]')];
  expect(start && end).toBeTruthy();
  start!.focus(); start!.value = '17:00'; start!.dispatchEvent(new Event('input')); start!.dispatchEvent(new Event('change')); start!.blur();
  await settle(rules); await settle(el);
  expect(f.ops).toHaveLength(1);
  expect(text(rules.shadowRoot)).toContain('Start and end times must be different');
  start!.focus(); start!.value = '22:00'; start!.dispatchEvent(new Event('input')); start!.dispatchEvent(new Event('change')); start!.blur();
  await settle(rules); await settle(el);
  expect(f.ops.at(-1)!.args.schedule).toEqual({ enabled: true, days: [1, 2, 3, 4, 5, 6, 7], startMinute: 1320, endMinute: 1020 });
  const any = [...rules.shadowRoot!.querySelectorAll<HTMLInputElement>('input[name="indexing-hours"]')].find(r => r.id !== 'indexing-hours');
  any!.checked = true; any!.dispatchEvent(new Event('change')); await settle(rules); await settle(el);
  expect(f.ops.at(-1)!.args.schedule).toEqual({ enabled: false, days: [1, 2, 3, 4, 5, 6, 7], startMinute: 1320, endMinute: 1020 });
  expect(f.ops.every(o => (o.args.witness as Witness | undefined)?.acceptedRevision !== undefined)).toBe(true);
  expect(f.s.ui.indexingPause.schedule).toEqual({ enabled: false, days: [1, 2, 3, 4, 5, 6, 7], startMinute: 1320, endMinute: 1020 });
});

it('B6: hours changed in another window: draft kept, Changed elsewhere, Keep my draft writes on the shown revision; Use the saved rules writes nothing', async () => {
  for (const choice of ['Keep my draft', 'Use the saved rules']) {
    const f = backend({ pause: { schedule: { enabled: true, days: [7], startMinute: 1320, endMinute: 360 } } });
    const el = await mount(f); const rules = editor(el);
    f.elsewhere(ui => ({ ...ui, indexingPause: { ...ui.indexingPause, pauseOnBattery: true } }));
    const battery = rules.shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!;
    const any = [...rules.shadowRoot!.querySelectorAll<HTMLInputElement>('input[name="indexing-hours"]')].find(r => r.id !== 'indexing-hours')!;
    any.checked = true; any.dispatchEvent(new Event('change')); await settle(rules); await settle(el);
    expect(f.s.ui.indexingPause.pauseOnBattery, 'nothing overwritten').toBe(true);
    expect(f.s.ui.indexingPause.schedule!.enabled).toBe(true);
    expect(text(rules.shadowRoot)).toContain('Changed elsewhere');
    expect(rules.indexingRules.enabled, 'draft kept').toBe(false);
    const writes = f.ops.length;
    await press(rules, control(rules.shadowRoot, choice)); await settle(el);
    if (choice === 'Keep my draft') {
      expect(f.ops.length).toBe(writes + 1);
      expect(f.ops.at(-1)!.args).toEqual({ schedule: { enabled: false, days: [7], startMinute: 1320, endMinute: 360 },
        pauseOnBattery: false, witness: { acceptedRevision: 4, lastCommittedOperationKey: key(904) } });
      expect(f.s.ui.indexingPause.schedule!.enabled).toBe(false);
    } else {
      expect(f.ops.length).toBe(writes);
      expect(battery.checked || rules.indexingRules.pauseOnBattery).toBe(true);
      expect(rules.indexingRules.enabled).toBe(true);
    }
    expect(text(rules.shadowRoot)).not.toContain('Changed elsewhere');
    document.body.replaceChildren(); vi.unstubAllGlobals();
  }
});

it('B6: an unrelated save elsewhere does not stop a reading-hours change', async () => {
  const f = backend(); const el = await mount(f); const rules = editor(el);
  f.elsewhere(ui => ({ ...ui, theme: 'light' }));
  const battery = rules.shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!;
  battery.checked = true; battery.dispatchEvent(new Event('change')); await settle(rules); await settle(el);
  expect(f.s.ui.indexingPause.pauseOnBattery).toBe(true);
  expect(text(rules.shadowRoot)).not.toContain('Changed elsewhere');
  expect((f.ops.at(-1)!.args.witness as Witness).acceptedRevision).toBe(4);
});

it('overlap: skip-rule save, hours save and toolbar pause/resume never leave a stale witness or lose a draft', async () => {
  __feedForTest({ status: { worker: { core: { indexState: 'IDLE', indexHealthy: true } },
    operating: { indexing: { state: 'IDLE', reasons: [] } } } as unknown as StatusSnapshot });
  const f = backend(); const el = await mount(f); const rules = editor(el);
  const toolbar = el.shadowRoot!.querySelector<HTMLElement & { updateComplete: Promise<unknown> }>('jf-indexing-controls[toolbar]');
  expect(toolbar).toBeTruthy(); await settle(toolbar!);
  const pause = toolbar!.shadowRoot!.querySelector<HTMLButtonElement>('#pause-toggle'); expect(pause).toBeTruthy();
  f.holdWrites();
  await type(el, rule(el, 1), '*.overlap'); await leave(el, rule(el, 1));
  const battery = rules.shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!;
  battery.checked = true; battery.dispatchEvent(new Event('change'));
  pause!.click(); await settle(toolbar!);
  toolbar!.shadowRoot!.querySelector<HTMLButtonElement>('.pause-options button')?.click();
  f.release(); await settle(el); await settle(rules); await settle(toolbar!); await settle(el);
  expect(f.s.ui.excludePatterns).toEqual(['*.overlap']);
  expect(f.s.ui.indexingPause.pauseOnBattery).toBe(true);
  expect(f.s.ui.indexingPause.paused, 'manual pause intent kept').toBe(true);
  expect(text(el.shadowRoot)).not.toContain('Changed elsewhere');
  expect(text(rules.shadowRoot)).not.toContain('Changed elsewhere');
  await type(el, rule(el, 1), '*.after'); await leave(el, rule(el, 1));
  expect(f.s.ui.excludePatterns).toEqual(['*.after']);
});

it('S6b-9 B5: Undo is offered for the last glob change only, restores the rule list only and keeps hidden settings', async () => {
  const f = backend({ patterns: ['*.log', literal] }); const el = await mount(f);
  await addRule(el, '*.tmp');
  const offers = listOpenReversals();
  expect(offers).toHaveLength(1);
  expect(offers[0]!.actionLabel).toBe('Undo');
  offers[0]!.run(); await settle(el); await settle(el);
  const undo = f.posts.at(-1)!;
  expect(undo.parsed.ui).toEqual({ excludePatterns: ['*.log', literal] });
  expect(undo.parsed.witness).toEqual({ acceptedRevision: 4, lastCommittedOperationKey: f.posts[0]!.parsed.operationKey });
  expect(f.s.ui.theme).toBe('dark');
  expect(rule(el, 1).value).toBe('*.log');
  expect(el.shadowRoot!.querySelector('input[aria-label="Skip rule 2"]')).toBeNull();
});

it('B5: the Undo detail says removed files come back when read again', async () => {
  const toasts: string[] = [];
  const listen = (event: Event) => { const d = (event as CustomEvent).detail ?? {}; toasts.push(`${d.message ?? ''} ${d.detail ?? ''}`); };
  document.addEventListener(EPHEMERAL_TOAST_EVENT, listen);
  try {
    const f = backend(); const el = await mount(f);
    await addRule(el, '*.tmp');
  } finally { document.removeEventListener(EPHEMERAL_TOAST_EVENT, listen); }
  const said = toasts.join(' ');
  expect(said).toContain('come back when');
  expect(said).toContain('read again');
});

it('B5: no Undo once another settings save happened since, for an earlier glob change, or for exact-path removal', async () => {
  const f = backend({ patterns: ['*.log', literal] }); const el = await mount(f);
  await addRule(el, '*.tmp');
  const first = listOpenReversals()[0]; expect(first).toBeDefined();
  await addRule(el, '*.bak');
  const posts = f.posts.length;
  first!.run(); await settle(el);
  expect(f.posts.length, 'an earlier change is not undone').toBe(posts);
  const latest = listOpenReversals().find(o => o !== first);
  const battery = editor(el).shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!;
  battery.checked = true; battery.dispatchEvent(new Event('change')); await settle(editor(el)); await settle(el);
  latest?.run(); await settle(el);
  expect(f.s.ui.excludePatterns, 'no Undo after another settings save').toEqual(['*.log', '*.tmp', '*.bak', literal]);
  __resetOwnReversalsForTest();
  const keepOut = control(el.shadowRoot!.querySelector('section[aria-label="Exact paths kept out"]'), 'Remove keep-out for "/docs/report[1].txt " (1 trailing whitespace character)');
  await press(el, keepOut);
  expect(f.s.ui.excludePatterns).toEqual(['*.log', '*.tmp', '*.bak']);
  expect(listOpenReversals()).toHaveLength(0);
});

it('B4: per-rule Preview counts are behind developer tools only', async () => {
  const f = backend(); const el = await mount(f);
  expect(text(el.shadowRoot)).not.toContain('Count matching files');
  setShowDeveloperTools(true); await settle(el);
  await press(el, control(el.shadowRoot, 'Count matching files'));
  expect(f.invoke).toHaveBeenCalledWith('core.preview-excludes', {});
  expect(text(el.shadowRoot)).toContain('*.tmp: 3 files');
});

it('S6b-12: an override keeps the editor editable and saved, explains when it takes effect, and asks nothing', async () => {
  const f = backend({ override: { source: 'env_var', sourceDetail: 'JUSTSEARCH_UI_EXCLUDE_PATTERNS', patterns: ['env-only.txt'] } });
  const el = await mount(f);
  expect(rule(el, 1).disabled).toBe(false);
  await addRule(el, '*.tmp');
  expect(globsSent(f)).toEqual([['*.log', '*.tmp']]);
  const note = el.shadowRoot!.querySelector('[data-testid="excludes-override"]');
  expect(note).toBeTruthy();
  expect(text(note)).toContain('JUSTSEARCH_UI_EXCLUDE_PATTERNS');
  expect(text(note)).toMatch(/takes effect .*override is removed|no effect until the override is removed/);
  expect(askRegion(el)).toHaveLength(0);
  expect(f.invoke).not.toHaveBeenCalled();
});

// Review repairs of the committed slice (R6–R10). Each case fails on the slice as committed for the reason named.

it('R6: removal is bound to the list asked about; a list saved elsewhere after the check removes nothing and is asked again', async () => {
  const f = backend(); const el = await mount(f);
  await addRule(el, '*.tmp');
  const first = askRegion(el)[0]; expect(first).toBeDefined();
  // Another window saves "*" right after this window checked the saved list, before the removal runs.
  f.afterNextRead(() => f.elsewhere(ui => ({ ...ui, excludePatterns: [...ui.excludePatterns, '*'] })));
  await press(el, control(first, 'Remove from search'));
  expect(f.invoke).toHaveBeenCalledTimes(1);
  expect(f.invoke.mock.calls[0]![1], 'the removal carries the consented list').toEqual({ rules: ['*.log', '*.tmp'] });
  expect(f.removedFor, 'nothing is removed under a list nobody was asked about').toEqual([]);
  expect(text(el.shadowRoot)).not.toContain('Done.');
  const again = askRegion(el);
  expect(again, 'asked again for the list now saved').toHaveLength(1);
  expect(again[0]).not.toBe(first);
  expect(rule(el, 3).value).toBe('*');
  await press(el, control(again[0], 'Remove from search'));
  expect(f.removedFor).toEqual([['*.log', '*.tmp', '*']]);
});

it('R7: the bytes saved are the draft captured when the field was left, never text typed after returning to it', async () => {
  const f = backend(); const el = await mount(f);
  f.holdWrites();
  await type(el, rule(el, 1), 'A'); await leave(el, rule(el, 1));
  expect(f.posts).toHaveLength(1);
  await type(el, rule(el, 1), 'B'); await leave(el, rule(el, 1));
  // Back in the field: "*" is typed and the field is not left.
  const field = rule(el, 1); await type(el, field, '*');
  f.release(); await settle(el); await settle(el);
  expect(globsSent(f)).toEqual([['A'], ['B']]);
  expect(f.s.ui.excludePatterns).toEqual(['B']);
  expect(field.value).toBe('*');
  const statuses = [...el.shadowRoot!.querySelectorAll('section[aria-label="Exclude patterns"] [role="status"]')].map(text).join(' ');
  expect(statuses, 'the unsaved "*" is not called saved').not.toMatch(/saved/i);
});

it('R7: a re-send after an unrelated save elsewhere sends the captured draft, not text typed meanwhile', async () => {
  const f = backend(); const el = await mount(f);
  f.elsewhere(ui => ({ ...ui, theme: 'light' }));
  const field = rule(el, 1);
  await type(el, field, 'A');
  // While the refused save is being checked, the person returns to the field and types "*".
  f.afterNextRead(() => { field.value = '*'; field.dispatchEvent(new Event('input', { bubbles: true, composed: true })); });
  await leave(el, field);
  await settle(el);
  expect(globsSent(f)).toEqual([['A'], ['A']]);
  expect(f.s.ui.excludePatterns).toEqual(['A']);
  expect(f.s.ui.theme).toBe('light');
  expect(field.value).toBe('*');
});

it('R8: a reading-rule write whose answer is lost says Not sure this saved, and is settled with the same key and bytes before a newer change', async () => {
  const f = backend(); const el = await mount(f); const rules = editor(el);
  const battery = rules.shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!;
  f.loseNextOpAnswer();
  battery.checked = true; battery.dispatchEvent(new Event('change')); await settle(rules); await settle(el);
  expect(f.s.ui.indexingPause.pauseOnBattery, 'the server committed it').toBe(true);
  expect(text(rules.shadowRoot)).toContain('Not sure this saved');
  expect(named(rules.shadowRoot, 'Try again'), 'the promised retry control').toBeDefined();
  // Turning it off again: the uncertain write is settled first, then the newer change is sent.
  battery.checked = false; battery.dispatchEvent(new Event('change')); await settle(rules); await settle(el);
  expect(f.ops).toHaveLength(3);
  expect(f.ops[1]!.key, 'same key').toBe(f.ops[0]!.key);
  expect(f.ops[1]!.args, 'same bytes').toEqual(f.ops[0]!.args);
  expect(f.ops[2]!.args.pauseOnBattery).toBe(false);
  expect(f.s.ui.indexingPause.pauseOnBattery, 'what is shown is what is saved').toBe(false);
  expect(battery.checked).toBe(false);
  expect(text(rules.shadowRoot)).not.toContain('Not sure this saved');
});

it('R8: Try again settles an uncertain reading-rule write by its key without sending anything newer', async () => {
  const f = backend(); const el = await mount(f); const rules = editor(el);
  const battery = rules.shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!;
  f.loseNextOpAnswer();
  battery.checked = true; battery.dispatchEvent(new Event('change')); await settle(rules); await settle(el);
  const revision = f.s.revision;
  await press(rules, control(rules.shadowRoot, 'Try again')); await settle(el);
  expect(f.ops).toHaveLength(2);
  expect(f.ops[1]!.key).toBe(f.ops[0]!.key);
  expect(f.s.revision, 'the replay commits nothing new').toBe(revision);
  expect(text(rules.shadowRoot)).not.toContain('Not sure this saved');
  expect(text(rules.shadowRoot)).toContain('Reading rules saved');
});

it('R9: a reading-rule change queued behind a pending save is still saved after leaving Library', async () => {
  const f = backend(); const el = await mount(f); const rules = editor(el);
  const battery = rules.shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!;
  f.holdWrites();
  battery.checked = true; battery.dispatchEvent(new Event('change')); await settle(rules);
  battery.checked = false; battery.dispatchEvent(new Event('change')); await settle(rules);
  el.remove(); await settle();
  f.release(); await settle(); await settle();
  expect(f.ops.map(o => o.args.pauseOnBattery)).toEqual([true, false]);
  expect(f.s.ui.indexingPause.pauseOnBattery).toBe(false);
});

it('R10: removal words never turn deletion-request counts into a number of files', async () => {
  const f = backend(); const el = await mount(f);
  // One confirmed folder request can stand for 100 read files.
  f.invoke.mockResolvedValueOnce({ success: true, structuredData: { ...doneResult, confirmedRemovals: 1, matchedFiles: 100 } } as never);
  await addRule(el, 'build/');
  await press(el, control(askRegion(el)[0], 'Remove from search'));
  const said = text(el.shadowRoot!.querySelector('.removal-slot'));
  expect(said).toContain('Done.');
  expect(said, 'one folder request is not "1 file removed"').not.toMatch(/\d+ files? removed/);
});

// Second review of the repairs (R11, R12). The four counterexamples fail on 67e5f1400c by assertion; the
// three further orderings check the one save rule: a snapshot per save-triggering event, sent in order.

const timeField = (rules: IndexingRulesEditor, label: 'Start time' | 'End time') => {
  const field = [...rules.shadowRoot!.querySelectorAll<HTMLInputElement>('input[type=time]')]
    .find(input => input.closest('label')?.textContent?.includes(label));
  expect(field, label).toBeTruthy(); return field!;
};
async function setTime(rules: IndexingRulesEditor, field: HTMLInputElement, value: string, leaveIt: boolean) {
  field.focus(); field.value = value; field.dispatchEvent(new Event('input'));
  if (leaveIt) field.blur();
  await settle(rules);
}

it('R11 (review 1): a change queued behind a write whose answer is lost survives leaving and returning, and is saved after that write is settled', async () => {
  const f = backend(); const el = await mount(f); const rules = editor(el);
  const battery = rules.shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!;
  f.holdWrites();
  battery.checked = true; battery.dispatchEvent(new Event('change')); await settle(rules);
  battery.checked = false; battery.dispatchEvent(new Event('change')); await settle(rules);
  el.remove(); await settle();
  // The first write commits, and its answer is lost on the way back.
  f.loseNextOpAnswer(); f.release(); await settle(); await settle();
  expect(f.s.ui.indexingPause.pauseOnBattery, 'the server committed "on"').toBe(true);
  expect(f.ops.map(o => o.args.pauseOnBattery), 'nothing newer is sent before the lost write is settled').toEqual([true]);
  document.body.append(el); await settle(el); await settle(rules); await settle(el);
  expect(battery.checked, 'the queued "off" is not replaced by the older saved base').toBe(false);
  expect(text(rules.shadowRoot)).toContain('Not sure this saved');
  expect(text(rules.shadowRoot)).toContain('not saved yet');
  await press(rules, control(rules.shadowRoot, 'Try again')); await settle(el); await settle(rules);
  expect(f.ops[1]!.key, 'settled with the same key').toBe(f.ops[0]!.key);
  expect(f.ops[1]!.args, 'and the same bytes').toEqual(f.ops[0]!.args);
  expect(f.ops.map(o => o.args.pauseOnBattery)).toEqual([true, true, false]);
  expect(f.s.ui.indexingPause.pauseOnBattery, 'what is shown is what is saved').toBe(false);
  expect(battery.checked).toBe(false);
  expect(text(rules.shadowRoot)).not.toContain('Not sure this saved');
});

it('R11 (review 2): a newer skip-rule draft left while an uncertain write waits is saved after that write is rebased across an unrelated save', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  const f = backend(); const el = await mount(f);
  f.hangWrites(true);
  await type(el, rule(el, 1), 'A'); await leave(el, rule(el, 1));
  await vi.advanceTimersByTimeAsync(10_500); await settle(el);
  expect(text(el.shadowRoot)).toContain('Not sure this saved');
  f.hangWrites(false);
  f.elsewhere(ui => ({ ...ui, theme: 'light' }));
  await type(el, rule(el, 1), 'B'); await leave(el, rule(el, 1)); await settle(el);
  const bodies = f.posts.map(p => p.body);
  expect(bodies[1], 'A is settled first with the same key and bytes').toBe(bodies[0]);
  expect(globsSent(f)).toEqual([['A'], ['A'], ['A'], ['B']]);
  expect(f.posts[2]!.parsed.operationKey, 'the refused A is sent again on the new revision').not.toBe(f.posts[0]!.parsed.operationKey);
  expect(f.s.ui.excludePatterns, 'B is not dropped').toEqual(['B']);
  expect(f.s.ui.theme).toBe('light');
  expect(text(el.shadowRoot)).not.toContain('Changed elsewhere');
  expect(text(el.shadowRoot)).not.toContain('Not sure this saved');
});

it('R11 (review 2): when the uncertain write\'s list is found saved, the newer draft is still saved after it', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  const f = backend(); const el = await mount(f);
  f.hangWrites(true);
  await type(el, rule(el, 1), 'A'); await leave(el, rule(el, 1));
  await vi.advanceTimersByTimeAsync(10_500); await settle(el);
  f.hangWrites(false);
  // Another window saved the very same list meanwhile.
  f.elsewhere(ui => ({ ...ui, excludePatterns: ['A'] }));
  await type(el, rule(el, 1), 'B'); await leave(el, rule(el, 1)); await settle(el);
  expect(globsSent(f)).toEqual([['A'], ['A'], ['B']]);
  expect(f.s.ui.excludePatterns).toEqual(['B']);
  expect(text(el.shadowRoot)).not.toContain('Changed elsewhere');
});

it('R11 (review 3): a reading-hours change is saved as it was when its field was left, not as typed after returning to it', async () => {
  const f = backend(); const el = await mount(f); const rules = editor(el);
  const battery = rules.shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!;
  f.holdWrites();
  battery.checked = true; battery.dispatchEvent(new Event('change')); await settle(rules);
  const start = timeField(rules, 'Start time');
  await setTime(rules, start, '10:00', true);
  // Back in the field: 11:00 is typed and the field is not left.
  await setTime(rules, start, '11:00', false);
  f.release(); await settle(rules); await settle(el); await settle(rules);
  expect(f.ops).toHaveLength(2);
  expect((f.ops[1]!.args.schedule as Schedule).startMinute, 'the left value 10:00').toBe(600);
  expect(f.s.ui.indexingPause.schedule?.startMinute).toBe(600);
  expect(start.value).toBe('11:00');
  expect(text(rules.shadowRoot), 'the unsaved 11:00 is not called saved').not.toContain('Reading rules saved');
  start.blur(); await settle(rules); await settle(el);
  expect(f.s.ui.indexingPause.schedule?.startMinute).toBe(660);
});

it('R12 (review 4): an older removal result keeps the newer ask and names only the list it removed for', async () => {
  const f = backend(); const el = await mount(f);
  const engine = f.invoke.getMockImplementation()!;
  let finish!: () => void;
  const gate = new Promise<void>(resolve => { finish = resolve; });
  // The engine checks the list it was given when asked, and answers later.
  f.invoke.mockImplementationOnce(async (id, args, options) => { const answer = engine(id, args, options); await gate; return answer; });
  await addRule(el, '*.tmp');
  await press(el, control(askRegion(el)[0], 'Remove from search'));
  expect(f.removedFor).toEqual([['*.log', '*.tmp']]);
  // Another rule is saved while that removal's answer is pending: a new ask for the new list.
  await addRule(el, '*.bak');
  const newer = askRegion(el)[0]; expect(newer).toBeDefined();
  finish(); await settle(el); await settle(el);
  expect(askRegion(el), 'the newer ask stays').toHaveLength(1);
  const said = text(el.shadowRoot!.querySelector('.removal-slot'));
  expect(said).toContain('Done.');
  expect(said, 'the outcome names the list it was for').toContain('*.log, *.tmp');
  expect(said, 'never claims files matching the rule added since').not.toContain('*.bak');
  expect(said).not.toContain('match your skip rules are no longer in search');
  await press(el, control(askRegion(el)[0], 'Remove from search'));
  expect(f.removedFor).toEqual([['*.log', '*.tmp'], ['*.log', '*.tmp', '*.bak']]);
  expect(askRegion(el)).toHaveLength(0);
  expect(text(el.shadowRoot!.querySelector('.removal-slot'))).toContain('match your skip rules are no longer in search');
});

it('R11: an unknown outcome, then leaving and returning, keeps the newer draft shown as not saved yet until the write is settled', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  const f = backend(); const el = await mount(f);
  f.hangWrites(true);
  await type(el, rule(el, 1), 'A'); await leave(el, rule(el, 1));
  await vi.advanceTimersByTimeAsync(10_500); await settle(el);
  el.remove(); await settle();
  document.body.append(el); await settle(el);
  expect(rule(el, 1).value, 'the uncertain draft is not replaced by the older saved list').toBe('A');
  await type(el, rule(el, 1), 'B');
  el.remove(); await settle();
  await vi.advanceTimersByTimeAsync(10_500); await settle();
  document.body.append(el); await settle(el);
  expect(rule(el, 1).value).toBe('B');
  expect(text(el.shadowRoot)).toContain('Not sure this saved');
  expect(text(el.shadowRoot)).toContain('not saved yet');
  expect(text(el.shadowRoot)).not.toMatch(/Skip rules saved/);
  f.hangWrites(false);
  await press(el, control(el.shadowRoot, 'Try again')); await settle(el);
  const bodies = f.posts.map(p => p.body);
  expect(new Set(bodies.slice(0, -1)).size, 'every send of A has the same key and bytes').toBe(1);
  expect(globsSent(f).at(-1)).toEqual(['B']);
  expect(f.s.ui.excludePatterns).toEqual(['B']);
  expect(text(el.shadowRoot)).not.toContain('Not sure this saved');
});

it('R11: two queued snapshots of each editor are saved in order across an unrelated revision move', async () => {
  const f = backend(); const el = await mount(f); const rules = editor(el);
  f.holdWrites();
  await type(el, rule(el, 1), 'A'); await leave(el, rule(el, 1));
  await type(el, rule(el, 1), 'B'); await leave(el, rule(el, 1));
  await type(el, rule(el, 1), 'C'); await leave(el, rule(el, 1));
  f.elsewhere(ui => ({ ...ui, theme: 'light' }));
  f.release(); await settle(el); await settle(el);
  expect(globsSent(f)).toEqual([['A'], ['A'], ['B'], ['C']]);
  expect(f.s.ui.excludePatterns).toEqual(['C']);
  const battery = rules.shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!;
  f.holdWrites();
  battery.checked = true; battery.dispatchEvent(new Event('change')); await settle(rules);
  await setTime(rules, timeField(rules, 'Start time'), '10:00', true);
  await setTime(rules, timeField(rules, 'End time'), '18:00', true);
  f.elsewhere(ui => ({ ...ui, mode: 'advanced' }));
  f.release(); await settle(rules); await settle(el); await settle(rules); await settle(el);
  const sent = f.ops.map(o => [o.args.pauseOnBattery, (o.args.schedule as Schedule | null)?.startMinute ?? null, (o.args.schedule as Schedule | null)?.endMinute ?? null]);
  expect(sent).toEqual([[true, null, null], [true, null, null], [true, 600, 1020], [true, 600, 1080]]);
  expect(f.s.ui.indexingPause).toMatchObject({ pauseOnBattery: true, schedule: { startMinute: 600, endMinute: 1080 } });
  expect(f.s.ui.theme).toBe('light'); expect(f.s.ui.mode).toBe('advanced');
  expect(text(el.shadowRoot)).not.toContain('Changed elsewhere');
  expect(text(rules.shadowRoot)).not.toContain('Changed elsewhere');
});

it('R11: Undo pressed behind a write whose outcome is unknown settles that write first and never overwrites it', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  const f = backend(); const el = await mount(f);
  await addRule(el, '*.tmp');
  const undo = listOpenReversals()[0]; expect(undo).toBeDefined();
  f.hangWrites(true);
  await type(el, rule(el, 1), 'B'); await leave(el, rule(el, 1));
  await vi.advanceTimersByTimeAsync(10_500); await settle(el);
  f.hangWrites(false);
  const before = f.posts.length;
  undo!.run(); await settle(el); await settle(el);
  expect(f.posts[before]!.body, 'the uncertain write is settled first, same key and bytes').toBe(f.posts[before - 1]!.body);
  expect(globsSent(f).slice(before), 'the Undo sends nothing over the later change').toEqual([['B', '*.tmp']]);
  expect(f.s.ui.excludePatterns).toEqual(['B', '*.tmp']);
  expect(text(el.shadowRoot)).not.toContain('Not sure this saved');
});

it('R11: a draft left behind an Undo whose answer was lost is saved after that Undo is settled', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  const f = backend(); const el = await mount(f);
  await addRule(el, '*.tmp');
  const undo = listOpenReversals()[0]; expect(undo).toBeDefined();
  f.hangWrites(true);
  undo!.run(); await settle(el);
  await vi.advanceTimersByTimeAsync(10_500); await settle(el);
  f.hangWrites(false);
  await type(el, rule(el, 1), 'C'); await leave(el, rule(el, 1)); await settle(el);
  const undos = f.posts.slice(1, 3).map(p => p.body);
  expect(undos[1], 'the Undo is settled with the same key and bytes').toBe(undos[0]);
  expect(globsSent(f)).toEqual([['*.log', '*.tmp'], ['*.log'], ['*.log'], ['C', '*.tmp']]);
  expect(f.s.ui.excludePatterns).toEqual(['C', '*.tmp']);
});

it('R13 (review 5): text typed while an Undo is pending stays when the Undo completes and is saved when its field is left', async () => {
  const f = backend(); const el = await mount(f);
  await addRule(el, '*.tmp');
  const undo = listOpenReversals()[0]; expect(undo).toBeDefined();
  f.holdWrites();
  undo!.run(); await settle(el);
  // The Undo's request is pending; the person types into a rule without leaving it.
  await type(el, rule(el, 1), '*.typed');
  f.release(); await settle(el); await settle(el);
  expect(f.s.ui.excludePatterns, 'the Undo saved').toEqual(['*.log']);
  expect(rule(el, 1).value, 'the typed text stays').toBe('*.typed');
  expect(rule(el, 2).value).toBe('*.tmp');
  expect(text(el.shadowRoot)).not.toContain('Skip rules saved');
  await leave(el, rule(el, 1));
  expect(globsSent(f).at(-1)).toEqual(['*.typed', '*.tmp']);
  expect(f.s.ui.excludePatterns).toEqual(['*.typed', '*.tmp']);
});

it('R13 (review 5): an Undo completing for an unchanged draft still shows the list it restored', async () => {
  const f = backend(); const el = await mount(f);
  await addRule(el, '*.tmp');
  const undo = listOpenReversals()[0]; expect(undo).toBeDefined();
  f.holdWrites();
  undo!.run(); await settle(el);
  f.release(); await settle(el); await settle(el);
  expect(f.s.ui.excludePatterns).toEqual(['*.log']);
  expect(rule(el, 1).value).toBe('*.log');
  expect(el.shadowRoot!.querySelector('input[aria-label="Skip rule 2"]')).toBeNull();
});

const running = (operationKey: string) => ({ success: true, message: 'Operation RUNNING',
  structuredData: { operationKey, operationRecordId: 7, state: 'RUNNING', unitsCompleted: 0, unitsFailed: 0 } });
const failed = { success: false, message: 'Reading rules could not be saved', errorClass: 'HANDLER_FAILURE', errorCode: 'SETTINGS_COMMIT_FAILED' };

it('R14 (review 5): a replay reporting RUNNING without a witness is not saved; the write is followed until it fails', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  const f = backend(); const el = await mount(f); const rules = editor(el);
  const battery = rules.shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!;
  f.scriptOpAnswers('lost');
  battery.checked = true; battery.dispatchEvent(new Event('change')); await settle(rules); await settle(el);
  expect(text(rules.shadowRoot)).toContain('Not sure this saved');
  expect(f.ops, 'the change was sent once').toHaveLength(1);
  const sentKey = f.ops[0]!.key!;
  f.scriptOpAnswers(running(sentKey), failed);
  await press(rules, control(rules.shadowRoot, 'Try again')); await settle(el);
  expect(text(rules.shadowRoot), 'RUNNING is not saved').not.toContain('Reading rules saved');
  expect(rules.shadowRoot!.querySelector('[role="status"]')?.textContent?.trim(), 'still saving').toBe('Saving…');
  await vi.advanceTimersByTimeAsync(1_000); await settle(rules); await settle(el);
  expect(f.ops.map(o => o.key), 'followed by its own key to its end').toEqual([sentKey, sentKey, sentKey]);
  expect(f.ops.map(o => o.args), 'with the same bytes').toEqual([f.ops[0]!.args, f.ops[0]!.args, f.ops[0]!.args]);
  expect(text(rules.shadowRoot)).toContain('Could not save reading rules');
  expect(text(rules.shadowRoot)).not.toContain('Reading rules saved');
  expect(text(rules.shadowRoot)).not.toContain('Not sure this saved');
  expect(f.s.ui.indexingPause.pauseOnBattery, 'nothing was saved').toBe(false);
  expect(battery.checked, 'the draft is kept').toBe(true);
});

it('R14 (review 5): a newer reading-rule change waits while a RUNNING write is followed, then is saved after it settles', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  const f = backend(); const el = await mount(f); const rules = editor(el);
  const battery = rules.shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!;
  f.scriptOpAnswers('lost');
  battery.checked = true; battery.dispatchEvent(new Event('change')); await settle(rules); await settle(el);
  expect(f.ops, 'the change was sent once').toHaveLength(1);
  const sentKey = f.ops[0]!.key!;
  f.scriptOpAnswers(running(sentKey), failed);
  await press(rules, control(rules.shadowRoot, 'Try again')); await settle(el);
  // Turning it off again while the first write is still running.
  battery.checked = false; battery.dispatchEvent(new Event('change')); await settle(rules); await settle(el);
  expect(f.ops.every(o => o.key === sentKey), 'nothing newer is sent before the write settles').toBe(true);
  await vi.advanceTimersByTimeAsync(1_000); await settle(rules); await settle(el);
  expect(f.ops.slice(0, 3).map(o => o.key)).toEqual([sentKey, sentKey, sentKey]);
  expect(f.ops).toHaveLength(4);
  expect(f.ops[3]!.key).not.toBe(sentKey);
  expect(f.ops[3]!.args.pauseOnBattery).toBe(false);
  expect(f.s.ui.indexingPause.pauseOnBattery).toBe(false);
  expect(battery.checked).toBe(false);
});
