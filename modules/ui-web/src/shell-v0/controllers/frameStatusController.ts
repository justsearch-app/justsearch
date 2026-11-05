// SPDX-License-Identifier: Apache-2.0
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import { subscribeAiState, type AiState } from '../state/aiStateStore.js';
import { deriveFrameStatus, completedLongWork, transientPlacement, FINISHED_DWELL_MS, type FrameStatus, type StatusFact, type DurableStatusOperation } from '../state/frameStatus.js';
import { subscribeTasks, listTasks, completeTask } from '../substrates/tasks/index.js';
import { subscribePending, listPending } from '../substrates/pending-effects/index.js';
import { subscribeConsent, listPendingConsentRequests } from '../substrates/consent/index.js';
import { subscribeAppUpdate, getAppUpdateStatus } from '../state/appUpdateState.js';
import { onStatusBarChange } from '../commands/StatusBarRegistry.js';
import type { AdvisorySnapshot, AdvisoryStore } from '../components/advisory/AdvisoryStore.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { z } from 'zod';
import { indexedRootViewSchema, type IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import { operationOutcomeViewSchema } from '../../api/generated/schema-types/operation-outcome-view.js';
import { openActionLedgerStream, type UnifiedActionEntry } from '../operations/ActionLedgerClient.js';
import type { MultiplexedStream } from '../streaming/MultiplexedStream.js';
import type { AuthorizationHost } from '../components/AuthorizationHost.js';
import { emitEphemeralToast } from '../components/advisory/ephemeralToast.js';
import { sendDesktopNotification } from '../../utils/notify.js';
import type { PresenceRun } from './AgentSessionController.js';
import { getOperationClient } from '../operations/OperationClient.js';
import { withdrawAuthorization, withdrawnAuthorizations } from '../operations/authorizationBroker.js';
import type { PluginErrorEventDetail } from '../state/pluginLoadEvents.js';
import { presentationForSeverity } from '../state/messageClasses.js';
import { isWindowFocused } from '../../utils/windowFocus.js';
import { presentVerdict, type VerdictPresentation } from '../state/verdict.js';
import { presentAiEngineVerdict, type AiEngineVerdictPresentation } from '../state/aiVerdict.js';
import { isIndexingObservation } from '../state/readinessNotice.js';
import { resolvePathLazy } from '../hooks/resolvePathLazy.js';
import { needsTypedConfirm } from '../components/acceptPendingEffect.js';

/** Which observation a needs-you row comes from (C6: only an observed source can announce an arrival). */
function needsSource(id: string): string {
  if (id.startsWith('proposal:')) return 'proposals';
  if (id.startsWith('consent:')) return 'consents';
  if (id === 'app-update') return 'update';
  if (id.startsWith('background:')) return 'presence';
  if (id.startsWith('operation:')) return id;
  return 'status';
}

interface StatusHost extends ReactiveControllerHost {
  apiBase: string;
  multiplex: MultiplexedStream | null;
  authorizationHost: (() => AuthorizationHost | null) | null;
}
/** Always mounted with the pill; owns no global state, streams or new poller. */
export class FrameStatusController implements ReactiveController {
  ai: AiState | null = null;
  model: FrameStatus;
  snapshot: AdvisorySnapshot = { advisories: [], unreadCount: 0, isConnected: false, lastFrameKind: 'initial' };
  polite = '';
  politeKey = '';
  indexingAnnouncement = '';
  verdictAnnouncement: VerdictPresentation['announce'] | null = null;
  aiEngineAnnouncement: AiEngineVerdictPresentation['announce'] | null = null;
  private indexingTransition = false;
  private indexingWasPaused = false;
  private indexingAnnouncementKey: string | null = null;
  private transientQueue: string[] = [];
  assertive = '';
  assertiveKey = '';
  transientKey: string | null = null;
  private unsubs: (() => void)[] = [];
  private stopStore: (() => void) | undefined;
  private timer: ReturnType<typeof setInterval> | undefined;
  private seen = new Set<string>();
  private contactLostAt: number | null = null;
  private finished: { title: string; at: number } | null = null;
  private activeWork: FrameStatus | null = null;
  private installing = false;
  private sawTransitioning = false;
  private needsKey: Set<string> | null = null;
  private transientUntil = 0;
  private remaining = 0;
  private paused = false;
  private conditions: StatusFact[] = [];
  private readonly pluginErrors = new Map<string, PluginErrorEventDetail>();
  private connected = false;
  private store: AdvisoryStore | null = null;
  private stopActivity: (() => void) | undefined;
  private activity: readonly UnifiedActionEntry[] = [];
  private operations = new Map<string, DurableStatusOperation>();
  private roots: readonly IndexedRootView[] = [];
  private rootsLive = false;
  private readonly rootPaths = new Map<string, string>();
  private lastRemoteStamp: number | null = null;
  private remoteBusy = false;
  private lifecycle = 0;
  private remoteAbort: AbortController | null = null;
  private presence: readonly PresenceRun[] = [];
  private presenceLive = false;
  private deadlines = new Map<string, string>();
  /** Agent requests already announced in this window: one announcement per arrival (DE3). */
  private readonly announced = new Set<string>();
  /** Sources observed in the previous refresh; only these can announce a needs-you arrival. */
  private observedSources = new Set<string>();

  constructor(private readonly host: StatusHost) {
    this.model = deriveFrameStatus({ ai: null, now: Date.now(), contactLostAt: null });
    host.addController(this);
  }
  hostConnected(): void {
    this.connected = true;
    this.lifecycle++;
    this.remoteBusy = false;
    this.lastRemoteStamp = null;
    if (this.store) this.setStore(this.store);
    this.unsubs = [subscribeAiState(ai => {
      this.ai = ai; this.refresh();
      if (ai.connection.lastSuccessMs !== this.lastRemoteStamp) {
        this.lastRemoteStamp = ai.connection.lastSuccessMs;
        void this.readRemote();
      }
    }),
      subscribeTasks(() => this.refresh()), subscribePending(() => this.refresh()),
      subscribeConsent(() => this.refresh()), subscribeAppUpdate(() => this.refresh()),
      onStatusBarChange(() => this.host.requestUpdate())];
    this.timer = setInterval(() => this.refresh(), 1_000);
    document.addEventListener('jf-plugin-load-error', this.pluginError);
    document.addEventListener('jf-plugin-loaded', this.pluginLoaded);
    queueMicrotask(() => { if (this.connected) this.refresh(); });
  }
  hostDisconnected(): void {
    this.connected = false;
    this.lifecycle++;
    this.remoteAbort?.abort(); this.remoteAbort = null;
    this.stopActivity?.(); this.stopActivity = undefined;
    for (const stop of this.unsubs) stop();
    this.unsubs = [];
    this.stopStore?.(); this.stopStore = undefined;
    this.activeWork = null; this.installing = false; this.sawTransitioning = false; this.needsKey = null;
    this.observedSources = new Set();
    clearInterval(this.timer);
    document.removeEventListener('jf-plugin-load-error', this.pluginError);
    document.removeEventListener('jf-plugin-loaded', this.pluginLoaded);
  }
  setStore(store: AdvisoryStore | null): void {
    this.store = store;
    this.stopStore?.();
    let initial = true;
    this.stopStore = store?.subscribe(snapshot => {
      this.snapshot = snapshot;
      for (const record of snapshot.advisories) {
        if (this.seen.has(record.key)) continue;
        this.seen.add(record.key);
        if (record.origin === 'local' && !initial) {
          if (this.paused) this.transientQueue.push(record.key);
          else this.showTransient(record.key);
          const presentation = presentationForSeverity(record.event.severity);
          if (presentation.live === 'alert') { this.assertive = record.toast?.message ?? ''; this.assertiveKey = record.key; }
          else { this.polite = record.toast?.message ?? ''; this.politeKey = record.key; }
        }
        // Agent requests announce through their arrival in Needs you (refresh), once, never twice.
      }
      initial = false;
      this.refresh();
    });
  }
  private showTransient(key: string): void {
    this.transientKey = key;
    const duration = this.snapshot.advisories.find(r => r.key === key)?.toast?.durationMs ?? FINISHED_DWELL_MS;
    this.transientUntil = Date.now() + duration; this.remaining = duration;
  }
  setMultiplex(multiplex: MultiplexedStream | null): void {
    this.stopActivity?.(); this.stopActivity = undefined;
    if (!multiplex || !this.connected) return;
    this.stopActivity = openActionLedgerStream({ multiplex, onActivity: activity => {
      this.activity = activity;
      for (const entry of activity) if (entry.operationOutcomeKey && !this.operations.has(entry.operationOutcomeKey)) {
        this.operations.set(entry.operationOutcomeKey, { id: entry.operationOutcomeKey, label: entry.label, outcome: null });
      }
      this.refresh(); void this.readRemote();
    } });
  }
  /** Reuses the shared status cadence. Read serially, retain values on failure, ignore late teardown responses. */
  private async readRemote(): Promise<void> {
    if (this.remoteBusy || !this.connected || this.lastRemoteStamp === null) return;
    this.remoteBusy = true;
    const lifecycle = this.lifecycle;
    const observationStamp = this.lastRemoteStamp;
    const abort = new AbortController(); this.remoteAbort = abort;
    const alive = (): boolean => this.connected && this.lifecycle === lifecycle;
    const current = (): boolean => alive() && observationStamp === this.lastRemoteStamp;
    try {
      const response = await authorizedFetch(`${this.host.apiBase}/api/indexing-roots/substrate`, { signal: abort.signal });
      if (!response.ok) throw new Error('Folder observation unavailable');
      const roots = z.object({ items: z.array(indexedRootViewSchema) }).parse(await response.json());
      if (current()) { this.roots = roots.items; this.rootsLive = true; }
    } catch { if (current()) this.rootsLive = false; }
    // R4 — folder rows are named by folder; the observation carries hashes only (memoized resolver).
    for (const root of this.roots) {
      if (!current()) break;
      const hash = root.pathHash;
      if (!hash || this.rootPaths.has(hash)) continue;
      const path = await resolvePathLazy('core.indexed-roots', hash, { apiBase: this.host.apiBase }).catch(() => null);
      if (path && current()) this.rootPaths.set(hash, path);
    }
    if (current()) try {
      const response = await authorizedFetch(`${this.host.apiBase}/api/presence`, { signal: abort.signal });
      if (!response.ok) throw new Error('Background observation unavailable');
      const presence = z.object({ runs: z.array(z.object({ sessionId: z.string(), state: z.string(), actor: z.string(),
        toolCalls: z.number(), turns: z.number(), iterations: z.number() })) }).parse(await response.json());
      if (current()) { this.presence = presence.runs; this.presenceLive = true; }
    } catch { if (current()) this.presenceLive = false; }
    for (const request of this.host.authorizationHost?.()?.statusRequests() ?? []) {
      if (!current()) break;
      // Only the engine's pending store is asked here. A run's own tool approval (owningRunId) is not
      // a pending-store id: its run withdraws it (cancelAuthorizationsForRun), never a 404 from here.
      if (request.kind !== 'authorize' || request.prompt.pendingId.startsWith('proposal:') || request.prompt.owningRunId) continue;
      try {
        const detail = await getOperationClient(this.host.apiBase).peekPending(request.prompt.pendingId);
        if (!current()) break;
        if (!detail) withdrawAuthorization(request.prompt.pendingId);
        else if (typeof detail.expiresAt === 'string') this.deadlines.set(request.prompt.pendingId, detail.expiresAt);
      } catch { /* A failed read never expires or withdraws a decision. */ }
    }
    for (const [key, operation] of this.operations) {
      if (!current()) break;
      if (operation.fresh && ['complete', 'failed'].includes(operation.outcome?.state ?? '') && operation.outcome?.phase !== 'awaiting_acceptance') continue;
      try {
        const response = await authorizedFetch(`${this.host.apiBase}/api/operation-history/${encodeURIComponent(key)}`, { signal: abort.signal });
        if (!response.ok) throw new Error('Operation observation unavailable');
        const outcome = operationOutcomeViewSchema.parse(await response.json());
        if (!current() || this.operations.get(key) !== operation) continue;
        this.operations.set(key, { ...operation, outcome, fresh: true });
        // ST6 — the invocation task finishes with the durable operation, never on invocation return.
        if (operation.invocationTaskId && outcome.phase !== 'awaiting_acceptance' && ['complete', 'failed'].includes(outcome.state)) {
          completeTask(operation.invocationTaskId, outcome.state === 'complete' ? 'succeeded' : 'failed');
        }
        // Initial snapshots and late-discovered terminal receipts never announce a completion.
        if (operation.outcome && ['accepted', 'running'].includes(operation.outcome.state)
          && operation.outcome.phase !== 'awaiting_acceptance' && outcome.state === 'complete' && outcome.phase !== 'awaiting_acceptance') {
          this.finished = { title: `${operation.label} finished`, at: Date.now() };
          emitEphemeralToast({ message: this.finished.title, classId: 'core.verdict.settled' });
        }
      } catch {
        if (current() && this.operations.get(key) === operation) this.operations.set(key, { ...operation, fresh: false });
      }
    }
    if (alive()) {
      this.remoteBusy = false; this.remoteAbort = null;
      if (observationStamp !== this.lastRemoteStamp) void this.readRemote();
      else this.refresh();
    }
  }
  pauseTransient(paused: boolean): void {
    if (this.paused === paused) return;
    if (paused) this.remaining = Math.max(0, this.transientUntil - Date.now());
    else this.transientUntil = Date.now() + this.remaining;
    this.paused = paused;
  }
  private readonly pluginError = (event: Event): void => {
    const detail = (event as CustomEvent<PluginErrorEventDetail>).detail;
    if (!detail?.message) return;
    const key = detail.pluginId ?? detail.pluginUrl ?? 'unknown';
    this.pluginErrors.set(key, detail);
    emitEphemeralToast({ message: `Plugin load failed: ${key}. ${detail.message}`, severity: 'error' });
    this.refresh();
  };
  private readonly pluginLoaded = (event: Event): void => {
    const key = (event as CustomEvent<{ pluginUrl: string }>).detail.pluginUrl;
    this.pluginErrors.delete(key); this.refresh();
  };
  async copyPluginError(key: string): Promise<void> {
    const error = this.pluginErrors.get(key);
    if (!error) return;
    try {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
      await navigator.clipboard.writeText([key, error.message, error.stack].filter(Boolean).join('\n'));
      emitEphemeralToast({ message: 'Plugin error details copied.' });
    } catch { emitEphemeralToast({ message: 'Could not copy plugin error details.', severity: 'error' }); }
  }
  dismissPluginError(key: string): void { this.pluginErrors.delete(key); this.refresh(); }
  refresh(): void {
    const now = Date.now();
    if (this.ai?.connection.reachable) this.contactLostAt = null;
    else this.contactLostAt ??= now;
    const migration = this.ai?.status?.worker?.migration;
    const generation = migration?.buildingGenerationId ?? '';
    if (['MIGRATING', 'SWITCHING', 'AWAITING_ACCEPTANCE'].includes(migration?.migrationState ?? '')
      && /^g-[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(generation)
      && !this.operations.has(generation.slice(2))) {
      this.operations.set(generation.slice(2), { id: generation.slice(2), label: 'Rebuilding search', outcome: null });
    }
    for (const task of listTasks()) if (task.operationKey && !this.operations.has(task.operationKey)) {
      this.operations.set(task.operationKey, { id: task.operationKey, label: task.label,
        invocationTaskId: task.id, outcome: null });
      void this.readRemote();
    }
    this.conditions = [...this.pluginErrors].map(([key, error]) => ({ id: `plugin-error:${key}`, section: 'problems',
      title: 'Plugin load failed', detail: `${key}: ${error.message}`,
      actions: [{ kind: 'navigate', target: 'core.settings-surface', label: 'Open plugin settings' },
        { kind: 'plugin-copy', target: key, label: 'Copy details' }, { kind: 'dismiss-plugin', target: key, label: 'Dismiss' }] }));
    const authorizationHost = this.host.authorizationHost?.() ?? null;
    const requests = authorizationHost?.statusRequests() ?? [];
    const derive = (): FrameStatus => deriveFrameStatus({ ai: this.ai, now, contactLostAt: this.contactLostAt,
      advisories: this.snapshot.advisories, tasks: listTasks(),
      proposals: listPending().map(p => ({ ...p, typed: p.effect.kind === 'invoke-operation' && needsTypedConfirm(p.effect, p.originator) })),
      authorizations: requests.flatMap(item => item.kind === 'authorize'
        ? [{ ...item.prompt, expiresAt: item.prompt.expiresAt ?? this.deadlines.get(item.prompt.pendingId),
          state: authorizationHost?.statusRequestState(item.prompt.pendingId) ?? null }] : []),
      consents: listPendingConsentRequests().map(c => ({ id: String(c.id), pluginId: c.contributorId, capability: c.capability })),
      update: getAppUpdateStatus(), conditions: this.conditions, finished: this.finished,
      roots: this.roots, rootPaths: this.rootPaths, rootsLive: this.rootsLive && !!this.ai?.indexingStatusLive,
      activity: this.activity, withdrawn: withdrawnAuthorizations(), presence: { runs: this.presence, live: this.presenceLive && !!this.ai?.snapshotLive }, operations: [...this.operations.values()] });
    let next = derive();
    const finishedBefore = this.finished;
    const p = next.progress;
    if (p.activity.confirmed && ['indexing', 'enriching'].includes(p.phase)) this.activeWork = next;
    // 595 §15.3 N1 — a witnessed transition (rebuild, schema switch, indexing) completes once on
    // reaching operational, even through an intermediate checking/degraded stop. First load and
    // reconnects never saw a transition and stay silent; one completion is announced once.
    const verdictKind = this.ai?.phase === 'connected' ? this.ai.verdict.kind : null;
    if (verdictKind === 'transitioning') this.sawTransitioning = true;
    const settled = verdictKind === 'operational' && this.sawTransitioning;
    if (verdictKind === 'operational') this.sawTransitioning = false;
    if (completedLongWork(this.activeWork, next) || settled) {
      this.activeWork = null;
      if (!this.finished || this.finished.title !== 'Search is up to date' || now - this.finished.at >= FINISHED_DWELL_MS) {
        this.finished = { title: 'Search is up to date', at: now };
        emitEphemeralToast({ message: this.finished.title, classId: 'core.verdict.settled' });
      }
    }
    if (this.ai?.snapshotLive && this.ai.aiEngine.kind === 'installing') this.installing = true;
    if (this.installing && this.ai?.snapshotLive && ['online', 'install_failed'].includes(this.ai.aiEngine.kind)) {
      this.installing = false;
      if (this.ai.aiEngine.kind === 'online') {
        this.finished = { title: 'AI install complete', at: now };
        emitEphemeralToast({ message: 'AI install complete', classId: 'core.ai-engine.settled' });
      } else emitEphemeralToast({ message: 'AI install failed', classId: 'core.ai-engine.failed' });
    }
    // 595 §15.1 — one verdict announcer and its AI-engine sibling, moved from the retired status
    // bar into this always-mounted owner. Indexing transitions speak through the indexing announcer.
    const verdict = this.ai ? presentVerdict(this.ai.verdict).announce : null;
    this.indexingTransition = this.ai?.verdict.kind === 'transitioning' && this.ai.verdict.reasons.some(isIndexingObservation);
    this.verdictAnnouncement = verdict && !this.indexingTransition ? verdict : null;
    this.aiEngineAnnouncement = this.ai && this.ai.aiEngine.kind !== 'indexing' ? presentAiEngineVerdict(this.ai.aiEngine).announce : null;
    const activityAnnouncement = [this.indexingTransition && p.activity.confirmed
      ? verdict?.text ?? p.activity.summary : p.activity.summary, p.activity.detail].filter(Boolean).join(' — ');
    if (p.activity.confirmed) {
      const resumedKey = `resumed:${activityAnnouncement}`;
      if (p.pause) {
        if (this.indexingAnnouncementKey !== p.pause.state) this.indexingAnnouncement = activityAnnouncement;
        this.indexingAnnouncementKey = p.pause.state;
      } else if (this.indexingWasPaused) {
        this.indexingAnnouncement = 'Indexing resumed.';
        this.indexingAnnouncementKey = resumedKey;
      } else if (this.indexingAnnouncementKey !== resumedKey) {
        this.indexingAnnouncement = activityAnnouncement;
        this.indexingAnnouncementKey = null;
      }
      this.indexingWasPaused = !!p.pause;
    } else if (this.indexingAnnouncementKey === null) this.indexingAnnouncement = activityAnnouncement;
    // An edge observed in this refresh shows in this refresh, not a timer tick later.
    if (this.finished !== finishedBefore) next = derive();
    const needsKey = new Set(next.sections.needs.map(f => f.id));
    // C6 — an arrival is a new item from a source that was already observed in the previous
    // refresh. Startup snapshots, reconnects and late discovery only establish the baseline.
    // Approval requests announce through their own live REQUIRES_ACK arrival, never here.
    const arrived = [...needsKey].filter(id => !this.needsKey?.has(id) && !id.startsWith('authorization:')
      && this.observedSources.has(needsSource(id)));
    if (arrived.length) {
      const n = next.sections.needs.length;
      this.assertive = `${n} ${n === 1 ? 'item needs' : 'items need'} you.`;
      this.assertiveKey = `needs:${[...needsKey].sort().join('|')}`;
    }
    // DE3 — an agent request that arrived live in this window is announced once, assertively; a
    // request rediscovered after a reload or replayed by a reconnect is listed without a word.
    const arrivals: StatusFact[] = [];
    for (const item of requests) {
      if (item.kind !== 'authorize' || item.prompt.arrival !== 'needs-you' || this.announced.has(item.prompt.pendingId)) continue;
      this.announced.add(item.prompt.pendingId);
      if (this.announced.size > 500) this.announced.delete(this.announced.values().next().value!);
      const row = next.sections.needs.find(fact => fact.id === `authorization:${item.prompt.pendingId}`);
      if (item.prompt.live && row) arrivals.push(row);
    }
    if (arrivals.length) {
      this.assertive = arrivals.length === 1 ? `Needs you: ${arrivals[0]!.title}.` : `${arrivals.length} agent requests need you.`;
      this.assertiveKey = arrivals.map(row => row.id).join('|');
      const first = arrivals[0]!;
      void isWindowFocused().then(focused => {
        if (!focused && this.connected) void sendDesktopNotification(first.title, first.detail);
      });
    }
    this.needsKey = needsKey;
    // R1 — while a more urgent state holds the pill, a hover or focus pause cannot extend a notice
    // past its window; an engine wait notice ends at once while the engine is unreachable.
    const placement = (key: string | null): ReturnType<typeof transientPlacement> => transientPlacement(next.pill,
      this.snapshot.advisories.find(r => r.key === key)?.event.classId ?? '');
    const urgent = transientPlacement(next.pill, '') !== 'pill';
    if (urgent && this.paused) this.remaining = Math.max(0, this.transientUntil - now);
    if (placement(this.transientKey) === 'hidden' || (!this.paused || urgent) && now >= this.transientUntil) {
      this.transientKey = null;
      const waiting = this.transientQueue.pop(); this.transientQueue = [];
      if (waiting && placement(waiting) !== 'hidden') this.showTransient(waiting);
    }
    this.observedSources = new Set(['proposals', 'consents',
      ...(this.ai?.indexingStatusLive && p.activity.confirmed ? ['status'] : []),
      ...(getAppUpdateStatus() ? ['update'] : []),
      ...(this.presenceLive && this.ai?.snapshotLive ? ['presence'] : []),
      ...[...this.operations.values()].filter(o => o.fresh && o.outcome).map(o => `operation:${o.id}`)]);
    this.model = next;
    this.host.requestUpdate();
  }
}
