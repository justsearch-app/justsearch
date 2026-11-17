// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Slice 8 (fm-15) — the status panel as the picture shows it: "What's going on", "Search works",
// Running "Installing AI" with its amounts and Pause, Earlier "Your Documents folder is searchable" with
// its age, footer "All activity" and "Health"; no counts, no "unread", no "Mark all read", no Details on
// Earlier rows (H1, H4'), all back with developer tools; "Open AI Brain" only away from AI Brain (H5').
// The real status pill posts Pause/Resume as today and keeps focus on the control (S8-9'), and a clicked
// notification whose row the default view leaves out opens the panel without error (S8-8').
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AiState } from '../state/aiStateStore.js';
const store = vi.hoisted(() => ({ listeners: new Set<(state: AiState) => void>(), current: null as AiState | null }));
vi.mock('../state/aiStateStore.js', async original => ({
  ...await original<typeof import('../state/aiStateStore.js')>(),
  subscribeAiState: (listener: (state: AiState) => void) => {
    store.listeners.add(listener); if (store.current) listener(store.current);
    return () => store.listeners.delete(listener);
  },
}));
vi.mock('../../utils/windowFocus.js', () => ({ isWindowFocused: vi.fn(async () => false) }));
vi.mock('../../utils/notify.js', () => ({ sendDesktopNotification: vi.fn(async () => {}) }));
import { StatusPanel } from './StatusPanel.js';
import { StatusPill } from './StatusPill.js';
import { deriveFrameStatus, type FrameStatus } from '../state/frameStatus.js';
import { EPHEMERAL_TOAST_EVENT } from './advisory/ephemeralToast.js';
import { __resetUserStateForTest } from '../state/UserStateDocument.js';
import { setShowDeveloperTools } from '../state/developerToolsState.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import type { AdvisoryRecord } from './advisory/AdvisoryStore.js';
import type { UnifiedActionEntry } from '../operations/ActionLedgerClient.js';

const now = Date.parse('2026-11-16T10:00:00Z');
const MIN = 60_000;
const GIB = 1024 ** 3;
const BRAIN = 'core.brain-surface';
const PICTURED = { state: 'running', paused: false, phase: 'download', totalBytes: 2.6 * GIB, downloadedBytes: 1.3 * GIB, remainingSeconds: 24 * 60 };
function ai(install: Record<string, unknown> = PICTURED): AiState {
  return { phase: 'connected', connection: { reachable: true, lastSuccessMs: now, lastContactMs: now, consecutiveFailures: 0 },
    // While AI installs, search still reports the AI-absent verdict a real Engine without AI models gives.
    verdict: { kind: 'degraded', severity: 'warn',
      reasons: ['index.dense_unavailable', 'lambdamart.not_configured', 'inference.deactivated', 'encoders.health.embedding_not_ready'] },
    stability: { kind: 'settled' },
    aiEngine: { kind: 'installing', stability: { kind: 'settled' }, installFailure: null },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    // A fresh install's enrichment (frameStatus.optionalAi.test.ts): without AI models the meaning step
    // cannot run, so a folder that finished reading is keyword-only (H3''').
    status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0, ownSearchableDocuments: 8 }, enrichment: {
      embeddingEnabled: false, spladeEnabled: false, nerEnabled: false, embeddingDocCount: 8, embeddingPendingCount: 8,
      chunk: { chunkDocCount: 3, chunkEmbeddingPendingCount: 3, chunkVectorsReady: false } } } },
    installStatus: { ...install, updatedAtEpochMs: Date.now() }, installObservationLive: true, runtimeObservationLive: true,
    indexingStatusLive: true, snapshotLive: true, episodeMaxPendingJobs: 0, enrichSettleSamples: [],
    capabilities: { chat: false }, runtime: { mode: 'offline' },
  } as unknown as AiState;
}
const READY = { pathHash: 'h-docs', collection: 'default', status: 'indexed', lastIndexedIsoTime: '2026-11-16T09:00:00Z',
  inFlightCount: 0, failedCount: 0, walkCompleted: true, fileCount: 8 } as IndexedRootView;
const READING = { ...READY, lastIndexedIsoTime: undefined, status: 'scanning', inFlightCount: 5 } as IndexedRootView;
const unread = { key: 'u1', origin: 'stream', acknowledged: false, sourceRenderHint: 'PERSISTED', event: { id: 'u1', classId: 'health.recoverable',
  occurredAt: '2026-11-16T09:30:00Z', renderHint: 'PERSISTED', classExtras: { conditionId: 'folder.unavailable', subject: 'Notes' },
  diagnosticsLink: null, provenance: null, primaryAction: null, bodyI18nKey: null } } as AdvisoryRecord;
const gate: UnifiedActionEntry = { id: 'g1', source: 'backend', kind: 'gate', occurredAt: '2026-11-16T09:58:00Z', originator: 'agent',
  label: 'APPROVED Add Watched Folder', gate: { disposition: 'APPROVED', operation: 'Add Watched Folder', pendingId: 'p1' } };
const failed: UnifiedActionEntry = { id: 'f1', source: 'backend', kind: 'operation', occurredAt: '2026-11-16T09:57:00Z', originator: 'user',
  label: 'Reindex everything', outcome: 'FAILURE' };

/** The pictured moment: the Documents folder became searchable in this window 12 minutes ago; AI installs. */
function pictured(developer = false, extra: Partial<Parameters<typeof deriveFrameStatus>[0]> = {}): FrameStatus {
  const base = { ai: ai(), contactLostAt: null, gettingStarted: { visible: false, known: true, currentStep: 2, searchable: 8 },
    rootPaths: new Map([['h-docs', '/home/me/Documents']]), rootsLive: true, developer, ...extra };
  const reading = deriveFrameStatus({ ...base, now: now - 13 * MIN, roots: [READING] });
  const ready = deriveFrameStatus({ ...base, now: now - 12 * MIN, roots: [READY], folders: reading.folders! });
  return deriveFrameStatus({ ...base, now, roots: [READY], folders: ready.folders! });
}
const text = (node: Element | ShadowRoot | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
let panel: StatusPanel;
const buttons = (scope: ParentNode = panel.shadowRoot!) => [...scope.querySelectorAll('button')].map(b => text(b)).filter(Boolean);
const row = (title: string) => [...panel.shadowRoot!.querySelectorAll('article')].find(a => text(a.querySelector('.text')).includes(title))!;
async function show(model: FrameStatus, props: { area?: string | null; developer?: boolean } = {}) {
  panel = new StatusPanel(); panel.model = model; panel.area = props.area ?? BRAIN; panel.developer = props.developer ?? false;
  document.body.append(panel); await panel.updateComplete;
}
afterEach(() => document.body.replaceChildren());

describe('S8-1 the picture', () => {
  it('"What\'s going on", "Search works", Running "Installing AI" with amounts and Pause, Earlier with its age, footer', async () => {
    await show(pictured());
    const root = panel.shadowRoot!;
    expect(text(root.querySelector('.header h2'))).toBe("What's going on");
    expect(text(root.querySelector('.header .detail'))).toBe('Search works');
    expect([...root.querySelectorAll('section h3')].map(h => text(h))).toEqual(['Running', 'Earlier']);
    const install = row('Installing AI');
    expect(text(install.querySelector('.text'))).toBe('Installing AI');
    expect(text(install.querySelector('.detail'))).toBe('1.3 of 2.6 GB · about 24 min left');
    expect(buttons(install)).toEqual(['Pause']);
    const folder = row('Your Documents folder is searchable');
    expect(text(folder.querySelector('time'))).toBe('12 min');
    expect(text(folder)).not.toMatch(/files/);
    expect(buttons(folder)).toEqual([]);
    expect(buttons(root.querySelector('.footer')!)).toEqual(['All activity', 'Health']);
    expect(text(root)).not.toMatch(/unread|Unread|Mark all read|Draft kept|Allowed|Add Watched Folder/);
  });
});

// PH3 (fm-15) — a settings save is the Engine's settings operation (core.reconfigure from the Settings
// page, core.apply-settings from settings.apply-public), not something that happened to the person: its
// success is no row; its failure still is. The filter goes by operation, never by the row's words.
describe('PH3 settings saves and the close mark', () => {
  const save = (operationId: string, outcome: string, id = 's1'): UnifiedActionEntry => ({ id, source: 'backend', kind: 'operation',
    occurredAt: '2026-11-16T09:59:00Z', originator: 'user', label: 'Apply Settings', operationId, outcome });
  for (const operationId of ['core.reconfigure', 'core.apply-settings']) {
    it(`a successful ${operationId} save is no row; a failed one is`, async () => {
      await show(pictured(false, { activity: [save(operationId, 'SUCCESS'), save(operationId, 'SUCCESS', 's2')] }));
      expect(text(panel.shadowRoot!)).not.toMatch(/Apply Settings/);
      panel.model = pictured(false, { activity: [save(operationId, 'FAILURE')] }); await panel.updateComplete;
      expect(text(row('Apply Settings'))).toContain('Failed');
    });
    it(`a ${operationId} advisory that succeeded is no row; one that failed is`, () => {
      const advisory = (outcome: string): AdvisoryRecord => ({ ...unread, key: `a-${outcome}`, event: { ...unread.event, id: `a-${outcome}`,
        classId: 'operation.completed', classExtras: { operationId, outcome } } } as AdvisoryRecord);
      const titles = (model: FrameStatus) => model.sections.earlier.map(fact => fact.title).join(' | ');
      expect(titles(pictured(false, { advisories: [advisory('SUCCESS')] }))).not.toMatch(/ completed/);
      expect(titles(pictured(false, { advisories: [advisory('FAILURE')] }))).toMatch(/ failed/);
    });
  }
  it('another operation labelled like a settings save still shows its success', async () => {
    await show(pictured(false, { activity: [save('core.rebuild-index', 'SUCCESS')] }));
    expect(text(panel.shadowRoot!)).toMatch(/Apply Settings/);
  });
  it('the close button draws the app\'s close icon, not a text cross', async () => {
    await show(pictured());
    const close = panel.shadowRoot!.querySelector<HTMLButtonElement>('button[aria-label="Close status panel"]')!;
    expect(text(close)).toBe('');
    const icon = close.querySelector('svg');
    expect(icon, 'close icon').not.toBeNull();
    expect([...icon!.querySelectorAll('path')].map(p => p.getAttribute('d'))).toEqual(['M18 6 6 18', 'm6 6 12 12']);
  });
});

describe('S8-3 / H5\' "Open AI Brain" only away from AI Brain', () => {
  it('shows on Search and Library, not on AI Brain, and follows the area while open', async () => {
    await show(pictured(), { area: 'core.search-v3-surface' });
    expect(buttons(row('Installing AI'))).toEqual(['Pause', 'Open AI Brain']);
    panel.area = 'core.library-surface'; await panel.updateComplete;
    expect(buttons(row('Installing AI'))).toContain('Open AI Brain');
    panel.area = BRAIN; await panel.updateComplete;
    expect(buttons()).not.toContain('Open AI Brain');
  });
});

describe('S8-4 / H1 / H4\' the default view and developer tools', () => {
  it('no counts, unread marker, Mark all read or Earlier Details by default; a failure keeps its Details', async () => {
    await show(pictured(false, { advisories: [unread], activity: [gate, failed] }));
    const root = panel.shadowRoot!;
    expect([...root.querySelectorAll('section h3')].map(h => text(h))).toEqual(['Running', 'Earlier']);
    expect(text(root)).not.toMatch(/unread|Unread|Mark all read|Allowed/);
    expect(buttons(row('Your Documents folder is searchable'))).toEqual([]);
    expect(buttons(row('Notes'))).not.toContain('Details');
    expect(buttons(row('Reindex everything'))).toContain('Details');
  });
  it('developer tools bring back gate history, Details, the unread count and Mark all read', async () => {
    await show(pictured(true, { advisories: [unread], activity: [gate, failed] }), { developer: true });
    const root = panel.shadowRoot!;
    expect(text(root.querySelector('section[aria-label="Earlier"] h3'))).toBe('Earlier 1 unread');
    expect(buttons()).toContain('Mark all read');
    expect(text(row('Notes'))).toContain('Unread');
    expect(buttons(row('Notes'))).toContain('Details');
    expect(row('Allowed: Add Watched Folder')).toBeDefined();
  });
  // RV8-4 — open Details of a row whose Details are a developer tool close when developer tools turn off.
  it('an expanded Earlier Details closes when developer tools turn off; a failure\'s Details stay', async () => {
    await show(pictured(true, { advisories: [unread], activity: [failed] }), { developer: true });
    for (const title of ['Notes', 'Reindex everything']) [...row(title).querySelectorAll('button')].find(b => text(b) === 'Details')!.click();
    await panel.updateComplete;
    expect(row('Notes').querySelector('[data-expanded]')).not.toBeNull();
    panel.developer = false; panel.model = pictured(false, { advisories: [unread], activity: [failed] }); await panel.updateComplete;
    expect(row('Notes').querySelector('[data-expanded]')).toBeNull();
    expect(buttons(row('Notes'))).not.toContain('Hide details');
    expect(row('Reindex everything').querySelector('[data-expanded]')).not.toBeNull();
    panel.developer = true; await panel.updateComplete;
    expect(row('Notes').querySelector('[data-expanded]')).toBeNull();
  });
});

describe('S8-9\' / S8-8\' through the real status pill', () => {
  let pill: StatusPill;
  let toasts: string[];
  const onToast = (e: Event) => toasts.push((e as CustomEvent<{ message: string }>).detail.message);
  const pillPanel = () => pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!;
  async function flush() { for (let i = 0; i < 4; i++) { await Promise.resolve(); await pill.updateComplete; await pillPanel().updateComplete; } }
  const control = (label: string) => [...pillPanel().shadowRoot!.querySelectorAll('button')].find(b => text(b) === label);
  async function publish(state: AiState) { store.current = state; for (const listener of store.listeners) listener(state); await flush(); }
  beforeEach(async () => {
    __resetUserStateForTest(); toasts = []; document.addEventListener(EPHEMERAL_TOAST_EVENT, onToast);
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
    store.current = ai();
    pill = new StatusPill(); pill.area = BRAIN; document.body.append(pill); await flush();
  });
  afterEach(() => { pill.remove(); store.listeners.clear(); document.removeEventListener(EPHEMERAL_TOAST_EVENT, onToast); vi.unstubAllGlobals(); __resetUserStateForTest(); });

  it('Pause posts to the same endpoint, keeps focus on the control, and reads Resume once the status says paused', async () => {
    const posts: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') posts.push(String(url));
      return new Response(JSON.stringify({ state: 'running', paused: true }), { status: 200, headers: { 'content-type': 'application/json' } });
    }));
    pill.toggle(); await flush();
    expect(control('Pause')).toBeDefined();
    const pause = control('Pause')!;
    pause.focus(); pause.click(); await flush();
    expect(posts).toEqual(['/api/ai/install/pause']);
    expect(pillPanel().shadowRoot!.activeElement).toBe(pause);
    expect(text(pause)).toBe('Pause');
    await publish(ai({ ...PICTURED, paused: true }));
    expect(control('Resume')).toBe(pause);
    expect(pillPanel().shadowRoot!.activeElement).toBe(pause);
  });
  it('a refused request shows today\'s error and keeps focus', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 500 })));
    pill.toggle(); await flush();
    expect(control('Pause')).toBeDefined();
    const pause = control('Pause')!; pause.focus(); pause.click(); await flush();
    expect(toasts).toContain('Could not pause the download.');
    expect(pillPanel().shadowRoot!.activeElement).toBe(pause);
  });
  it('developer tools on and off, from the existing per-profile preference', async () => {
    pill.toggle(); await flush();
    expect(pill.status.model.sections.earlier).toEqual([]);
    setShowDeveloperTools(true); await flush();
    expect(pillPanel().developer).toBe(true);
    setShowDeveloperTools(false); await flush();
    expect(pillPanel().developer).toBe(false);
  });
  it('a clicked notification for a row the default view leaves out opens the panel without error', async () => {
    const activated = (pill as unknown as { noticeActivated(id: string): void }).noticeActivated;
    expect(() => activated('operation:hidden-add-folder')).not.toThrow();
    await flush();
    expect(pill.open).toBe(true);
    expect(pillPanel().hasAttribute('hidden')).toBe(false);
  });
});
