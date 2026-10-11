// SPDX-License-Identifier: Apache-2.0
/** Completed writes invalidate the shared canonical observation, never assume its new value. */
let binding: { refresh: () => Promise<void> } | null = null;
let queued: Promise<void> | null = null;
// A transport and its client may both see the same receipt. Observe it exactly once.
const executionReceipts = new WeakMap<Response, Promise<void>>();

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
  'core.ingest-files', 'core.file-operations',
]);
// The agent emitter uses OpenAI-safe wire names; workflow and REST receipts use ids.
const STATUS_TOOL_NAMES = new Set([...STATUS_OPERATIONS].map(id => id.replace(/[.\-]/g, '_')));

/** One completion owner for receipts and server-side execution events. Unknown identity
 * (approval-by-pending-id or an evicted replay prefix) cannot establish that a tool was read-only. */
function statusExecutionCompleted(success: unknown, operationId?: string): Promise<void> {
  return success === true && affectsStatus(operationId)
    ? statusChanged() : Promise.resolve();
}

function affectsStatus(operationId?: string): boolean {
  return operationId === undefined || STATUS_OPERATIONS.has(operationId) || STATUS_TOOL_NAMES.has(operationId)
    || (!operationId.startsWith('core.') && !operationId.startsWith('core_') && !operationId.startsWith('vop_'));
}

/** Per-stream routing context, shared by the host AI and direct run readers. No status is
 * inferred from tool output; successful execution only requests the canonical observation. */
export function createStatusExecutionObserver(): (event: string, payload: unknown) => Promise<void> {
  const operations = new Map<string, string>();
  function remember(value: unknown): void {
    if (!value || typeof value !== 'object') return;
    const call = value as Record<string, unknown>;
    if (typeof call.callId === 'string' && typeof call.toolName === 'string') {
      operations.set(call.callId, call.toolName);
    }
  }
  return (event, payload) => {
    if (!payload || typeof payload !== 'object') return Promise.resolve();
    const data = payload as Record<string, unknown>;
    if (event === 'tool_batch_proposed' && Array.isArray(data.calls)) data.calls.forEach(remember);
    if (event === 'state_snapshot' && Array.isArray(data.pendingApprovals)) data.pendingApprovals.forEach(remember);
    if (['tool_call_proposed', 'tool_call_pending', 'tool_exec_started'].includes(event)) remember(data);
    if (event === 'tool_exec_completed') {
      const callId = typeof data.callId === 'string' ? data.callId : undefined;
      const operationId = callId === undefined ? undefined : operations.get(callId);
      if (callId !== undefined) operations.delete(callId);
      return statusExecutionCompleted(data.success, operationId);
    }
    if (event === 'tool_call_rejected' && typeof data.callId === 'string') operations.delete(data.callId);
    return Promise.resolve();
  };
}

/** Direct REST writes outside the validated settings and operation completion owners. */
export function statusWriteCompleted(input: RequestInfo | URL, init: RequestInit | undefined,
  response: Response, payload?: unknown): Promise<void> {
  const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase();
  if (!response.ok || ['GET', 'HEAD', 'OPTIONS'].includes(method)) return Promise.resolve();
  const url = new URL(input instanceof Request ? input.url : String(input), 'http://status.local');
  const path = url.pathname;
  if (url.searchParams.get('dryRun') === 'true' || path.endsWith('/preview') || path.endsWith('/preflight')) {
    return Promise.resolve();
  }
  const operation = path.match(/^\/api\/(?:operations\/([^/]+)\/invoke|undo\/([^/]+))$/);
  const operationId = operation ? decodeURIComponent(operation[1] ?? operation[2]!) : undefined;
  if (operation && !affectsStatus(operationId)) return Promise.resolve();
  if (operation || path === '/api/authorizations/approve') {
    const observed = executionReceipts.get(response);
    if (observed) return observed;
    // All UI transports share the execution receipt owner, including trusted plugin fetch.
    // Clone before decoding so the caller retains its response body and error contract.
    const receipt = payload === undefined ? response.clone().json().catch(() => null) : Promise.resolve(payload);
    const completion = receipt.then(value => {
      if (!value || typeof value !== 'object') return;
      const result = value as Record<string, unknown>;
      if (operation) return statusExecutionCompleted(result.success, operationId);
      return statusExecutionCompleted(result.executed === true && result.executeSuccess === true);
    });
    executionReceipts.set(response, completion);
    return completion;
  }
  return /^\/api\/(?:ai\/|inference\/|indexing\/|lifecycle\/|chat\/load-anyway$|upgrade\/|worker\/restart$|admin\/runtime\/reload$)/.test(path)
    ? statusChanged() : Promise.resolve();
}
