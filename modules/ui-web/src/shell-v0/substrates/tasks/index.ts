// SPDX-License-Identifier: Apache-2.0
/**
 * Task substrate — Tempdoc 543 §32 R-E1 (async / long-running work).
 *
 * The Effect union is synchronous (dispatch-and-done). Real agent work —
 * search, index rebuild, summarize, AI install — is long-running, so it does
 * NOT fit the closed Effect union (exactly the gap §32.9 named). This is a
 * separate substrate modelled on MCP's 2026 Tasks extension: a task has an
 * id, label, status (running → succeeded | failed | cancelled), optional
 * progress, and may register a cancel callback.
 *
 * Production consumer (§32): the Shell wraps agent-originated operation
 * invocations as tasks (running while the HTTP call is in flight; succeeded /
 * failed on settle), so the task list shows in-flight agent work. The
 * substrate is also the natural home for the backend indexing-jobs stream
 * (SubscribeIndexingJobs + CancelIndexingJobHandler) — wiring that real-time
 * feed is an additive follow-up.
 *
 * The cancel callback is injected by the task's producer (decoupled, like
 * PendingEffect's applyFn): cancellable tasks pass a `cancel` fn; cancelTask
 * invokes it and marks the task cancelled. Tasks with no cancel fn are
 * non-cancellable (the UI hides the cancel affordance).
 */

import { notifyAll } from '../../primitives/notify.js';

/**
 * Explicit lifecycle states (tempdoc 550 Thesis II). `queued` is non-terminal
 * but NOT running — work that is enqueued and waiting, not actively executing.
 * Distinguishing `queued` from `running` is the whole point: a projection must
 * render an item's TRUE state, never collapse "not-yet-failed" into "running"
 * (the F-1 defect: PENDING indexing jobs shown as perpetual RUNNING pills).
 * `running` is asserted only by an authoritative running-signal (e.g. a job in
 * PROCESSING), never by mere membership in a live set.
 */
export type TaskStatus =
  | 'queued'
  | 'running'
  | 'succeeded'
  | 'failed'
  | 'cancelled';

/** Terminal states never re-transition and are eligible for pruning. */
const TERMINAL_STATUSES: ReadonlySet<TaskStatus> = new Set<TaskStatus>([
  'succeeded',
  'failed',
  'cancelled',
]);

export function isTerminalStatus(status: TaskStatus): boolean {
  return TERMINAL_STATUSES.has(status);
}

export interface Task {
  readonly id: string;
  readonly label: string;
  readonly status: TaskStatus;
  /** 0..1 when known; absent = indeterminate. */
  readonly progress?: number;
  readonly startedAt: string;
  readonly cancellable: boolean;
  /**
   * Tempdoc 609 §R (T1.3) — the surface a task originated from, so the TaskList row + status-bar chip can
   * navigate back to it ("return to running job"). Absent = no return target (the row is non-navigable).
   */
  readonly originSurfaceId?: string;
}

const _tasks = new Map<string, Task>();
const _cancelFns = new Map<string, () => void>();
const _listeners = new Set<() => void>();
let _seq = 0;
// External mirrors own their bounded live set, including failures. Pruning those rows here
// fights their next reconciliation and turns a stable window into endless remove/insert churn.
const _mirrored = new Set<string>();
const _namespaces = new Map<string, Set<string>>();
let batchDepth = 0;
let changedInBatch = false;

function namespace(id: string): string { return id.slice(0, id.indexOf(':') + 1); }
function putTask(task: Task): void {
  _tasks.set(task.id, task);
  const key = namespace(task.id);
  let ids = _namespaces.get(key);
  if (!ids) { ids = new Set(); _namespaces.set(key, ids); }
  ids.add(task.id);
}
function deleteTask(id: string): void {
  _tasks.delete(id);
  _mirrored.delete(id);
  _cancelFns.delete(id);
  const key = namespace(id);
  const ids = _namespaces.get(key);
  ids?.delete(id);
  if (ids?.size === 0) _namespaces.delete(key);
}

/** Publish a reconciled set once, after all its row changes have been applied. */
export function batchTaskChanges(update: () => void): void {
  batchDepth++;
  try { update(); }
  finally {
    batchDepth--;
    if (batchDepth === 0 && changedInBatch) { changedInBatch = false; notify(); }
  }
}

// S7 review follow-up: bound in-memory accumulation. Finished tasks are
// pruned oldest-first (Map preserves insertion order) once the total exceeds
// this cap; running tasks are never pruned.
const MAX_RETAINED = 50;

function pruneFinished(): void {
  if (_tasks.size <= MAX_RETAINED) return;
  for (const [id, task] of _tasks) {
    if (_tasks.size <= MAX_RETAINED) break;
    if (!_mirrored.has(id) && isTerminalStatus(task.status)) deleteTask(id);
  }
}

function notify(): void {
  if (batchDepth > 0) { changedInBatch = true; return; }
  notifyAll(_listeners);
}

export interface StartTaskOptions {
  /** Stable id (e.g., a backend job id). Auto-generated when omitted. */
  readonly id?: string;
  readonly label: string;
  readonly progress?: number;
  /** When supplied, the task is cancellable; cancelTask invokes this. */
  readonly cancel?: () => void;
  /** Tempdoc 609 §R (T1.3) — surface to return to when the row/chip is clicked. */
  readonly originSurfaceId?: string;
}

export function startTask(opts: StartTaskOptions): string {
  const id = opts.id ?? `task-${++_seq}`;
  _mirrored.delete(id);
  putTask({
    id,
    label: opts.label,
    status: 'running',
    startedAt: new Date().toISOString(),
    cancellable: typeof opts.cancel === 'function',
    ...(opts.progress !== undefined ? { progress: opts.progress } : {}),
    ...(opts.originSurfaceId !== undefined ? { originSurfaceId: opts.originSurfaceId } : {}),
  });
  if (opts.cancel) _cancelFns.set(id, opts.cancel);
  pruneFinished();
  notify();
  return id;
}

export function updateTaskProgress(id: string, progress: number): void {
  const t = _tasks.get(id);
  if (!t || t.status !== 'running') return;
  putTask({ ...t, progress });
  notify();
}

export function completeTask(
  id: string,
  status: 'succeeded' | 'failed' | 'cancelled',
): void {
  const t = _tasks.get(id);
  // Terminal-state guard (S7 review): only a running task may transition.
  // Prevents e.g. a late completeTask flipping a cancelled task to succeeded.
  if (!t || t.status !== 'running') return;
  putTask({
    ...t,
    status,
    ...(status === 'succeeded' ? { progress: 1 } : {}),
  });
  _cancelFns.delete(id);
  notify();
}

/** User-requested cancel: invokes the registered cancel fn + marks cancelled. */
export function cancelTask(id: string): void {
  const t = _tasks.get(id);
  if (!t || t.status !== 'running') return;
  const fn = _cancelFns.get(id);
  if (fn) {
    try {
      fn();
    } catch {
      /* swallow — best-effort cancel */
    }
  }
  putTask({ ...t, status: 'cancelled' });
  _cancelFns.delete(id);
  notify();
}

/**
 * Remove a task outright (no terminal status). Unlike completeTask, this works
 * regardless of the task's current status and drops it from the list entirely.
 * Used by producers that mirror an external live set (e.g. the indexing-jobs
 * bridge, §32 #1) where a departed item should vanish, not show a guessed
 * terminal status — and where a stale terminal task must not block a same-id
 * item from re-appearing as running.
 */
export function removeTask(id: string): void {
  if (!_tasks.has(id)) return;
  deleteTask(id);
  notify();
}

/**
 * Upsert an externally-mirrored task to an explicit lifecycle state (tempdoc 550
 * Thesis II). Unlike startTask (always `running`) / completeTask (running→terminal
 * only), this lets a producer that mirrors an external lifecycle — e.g. the
 * indexing-jobs bridge — set the task's TRUE state (`queued` / `running` /
 * `failed`) and transition it freely as the source changes (queued→running→…).
 * The source of truth is the external feed, so there is no running-only guard.
 * Mirrored tasks are read-only (no cancel affordance); `startedAt` is preserved
 * across transitions of the same id.
 */
export function upsertMirroredTask(opts: {
  readonly id: string;
  readonly label: string;
  readonly status: TaskStatus;
  readonly progress?: number;
  readonly originSurfaceId?: string;
}): void {
  const existing = _tasks.get(opts.id);
  if (
    existing &&
    existing.status === opts.status &&
    existing.label === opts.label &&
    existing.progress === opts.progress &&
    existing.originSurfaceId === opts.originSurfaceId
  ) {
    return; // no-op: suppress redundant listener fires (stable reconcile)
  }
  _mirrored.add(opts.id);
  putTask({
    id: opts.id,
    label: opts.label,
    status: opts.status,
    startedAt: existing?.startedAt ?? new Date().toISOString(),
    cancellable: false,
    ...(opts.progress !== undefined ? { progress: opts.progress } : {}),
    ...(opts.originSurfaceId !== undefined ? { originSurfaceId: opts.originSurfaceId } : {}),
  });
  notify();
}

export function getTask(id: string): Task | undefined {
  return _tasks.get(id);
}

export function listTasks(taskNamespace?: string): readonly Task[] {
  if (taskNamespace !== undefined) {
    return Array.from(_namespaces.get(taskNamespace) ?? [], id => _tasks.get(id)!);
  }
  return Array.from(_tasks.values());
}

export function listRunningTasks(): readonly Task[] {
  return listTasks().filter((t) => t.status === 'running');
}

export function clearFinishedTasks(): void {
  for (const [id, t] of _tasks) {
    // Clear only terminal tasks; queued + running are live and retained.
    if (isTerminalStatus(t.status)) deleteTask(id);
  }
  notify();
}

export function subscribeTasks(listener: () => void): () => void {
  _listeners.add(listener);
  return () => {
    _listeners.delete(listener);
  };
}

/** Test-only reset. */
export function __resetTasksForTest(): void {
  _tasks.clear();
  _cancelFns.clear();
  _listeners.clear();
  _seq = 0;
  _mirrored.clear();
  _namespaces.clear();
  batchDepth = 0;
  changedInBatch = false;
}
