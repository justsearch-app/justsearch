import fsp from 'node:fs/promises';
import path from 'node:path';
import { resolveDevRunnerStateRoot } from './paths.mjs';

function nowIso() {
  return new Date().toISOString();
}

function writeStderr(line) {
  try {
    process.stderr.write(line);
  } catch (_) {
    // ignore
  }
}

export function logInfo(msg, details) {
  writeStderr(
    `[justsearch-dev-mcp] ${nowIso()} INFO ${msg}${details != null ? ` ${String(details)}` : ''}\n`,
  );
}

export function logWarn(msg, details) {
  writeStderr(
    `[justsearch-dev-mcp] ${nowIso()} WARN ${msg}${details != null ? ` ${String(details)}` : ''}\n`,
  );
}

export function logError(msg, details) {
  writeStderr(
    `[justsearch-dev-mcp] ${nowIso()} ERROR ${msg}${details != null ? ` ${String(details)}` : ''}\n`,
  );
}

let ndjsonChain = Promise.resolve();

function ndjsonEnabled() {
  const v = String(process.env.JUSTSEARCH_DEV_MCP_LOG_NDJSON || '').trim().toLowerCase();
  return v === '1' || v === 'true' || v === 'yes';
}

/**
 * Optional. Writes NDJSON under the same configured state root as the dev-runner.
 *
 * Workflow-telemetry coupling removed 2026-05-12: the original implementation
 * forwarded mapped events to `createWorkflowRunStore` from
 * `scripts/lib/workflow-telemetry.mjs`, which was deleted by commit
 * `a9c484f59` (2026-03-16). The forward was unreachable in practice — it
 * required `JUSTSEARCH_DEV_MCP_WORKFLOW_RUN_ID` / `JUSTSEARCH_WORKFLOW_RUN_ID`
 * to be set, neither of which is set anywhere live — but the eager import
 * crashed module load whenever this file was reached. Removing the
 * coupling restores log.mjs to runnable state.
 */
export function maybeAppendNdjson(repoRoot, event) {
  if (!repoRoot) return;
  if (!ndjsonEnabled()) return;

  const filePath = path.join(resolveDevRunnerStateRoot(repoRoot), 'justsearch-dev-mcp.ndjson');
  const line = JSON.stringify({ ts: nowIso(), ...event }) + '\n';

  ndjsonChain = ndjsonChain
    .then(async () => {
      await fsp.mkdir(path.dirname(filePath), { recursive: true });
      await fsp.appendFile(filePath, line, 'utf8');
    })
    .catch(() => {});
}
