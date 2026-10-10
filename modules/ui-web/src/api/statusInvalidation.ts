// SPDX-License-Identifier: Apache-2.0
/** Completed writes invalidate the shared canonical observation, never assume its new value. */
let binding: { refresh: () => Promise<void> } | null = null;
let queued: Promise<void> | null = null;

export function bindStatusRefresh(callback: () => Promise<void>): () => void {
  const current = { refresh: callback };
  binding = current;
  return () => { if (binding === current) { binding = null; queued = null; } };
}

/** Batch completions in the same turn; the poller serializes reads and admission waits. */
export function statusChanged(): Promise<void> {
  if (!binding) return Promise.resolve();
  if (queued) return queued;
  const current = binding;
  const request = Promise.resolve().then(() => {
    if (queued === request) queued = null;
    if (binding === current) return current.refresh();
  }).catch(() => { /* Observation failure cannot revoke a completed write. */ });
  queued = request;
  return request;
}

// Read-only operations must not turn search/diagnostic activity into status polling.
const STATUS_OPERATIONS = new Set([
  'core.set-chat-enabled', 'core.switch-inference-mode', 'core.trigger-offline-processing',
  'core.activate-runtime-variant', 'core.deactivate-runtime-variant', 'core.activate-installed-models',
  'core.start-ai-install', 'core.cancel-ai-install', 'core.repair-ai-install', 'core.import-ai-pack',
  'core.reconfigure', 'core.reset-settings', 'core.recover-component',
  'core.add-watched-root', 'core.remove-watched-root', 'core.reconcile-root',
  'core.reindex', 'core.bulk-reindex', 'core.rebuild-index', 'core.cancel-reindex', 'core.accept-gaps',
  'core.apply-excludes', 'core.clear-failed-jobs',
  'core.cancel-indexing-job', 'core.retry-indexing-job', 'core.index-gc', 'core.settle-index',
]);

export function statusOperationCompleted(operationId: string): Promise<void> {
  return STATUS_OPERATIONS.has(operationId) ? statusChanged() : Promise.resolve();
}

/** Direct REST writes outside the validated settings and operation completion owners. */
export function statusWriteCompleted(input: RequestInfo | URL, init: RequestInit | undefined,
  response: Response): Promise<void> {
  const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase();
  if (!response.ok || ['GET', 'HEAD', 'OPTIONS'].includes(method)) return Promise.resolve();
  const url = new URL(input instanceof Request ? input.url : String(input), 'http://status.local');
  const path = url.pathname;
  if (url.searchParams.get('dryRun') === 'true' || path.endsWith('/preview') || path.endsWith('/preflight')) {
    return Promise.resolve();
  }
  return /^\/api\/(?:ai\/|inference\/|indexing\/|lifecycle\/|chat\/load-anyway$|upgrade\/|worker\/restart$|admin\/runtime\/reload$)/.test(path)
    ? statusChanged() : Promise.resolve();
}
