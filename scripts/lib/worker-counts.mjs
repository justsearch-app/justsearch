/**
 * Whether the Worker's counts in an Engine status are counts — the one answer the node tooling
 * (the dev doctor, the production MCP server) asks before reading one.
 *
 * False when the status marks its document counts unknown: `worker.core.indexedDocumentsUnknown`
 * on `/api/status`, `indexedDocumentsUnknown` on `/api/knowledge/status`. The Engine also sets it
 * while it has no Worker reading. That flag covers every count the Worker supplies (documents,
 * pending jobs, index size, queue and enrichment counts; health-readiness contract): their zeros
 * are then placeholders, never an empty index or a drained queue.
 *
 * @param {Record<string, any> | null | undefined} status a parsed status body
 * @returns {boolean}
 */
export function workerCountsKnown(status) {
  const core = status?.worker?.core;
  const flag = core && typeof core === 'object' ? core.indexedDocumentsUnknown : status?.indexedDocumentsUnknown;
  return flag !== true;
}
