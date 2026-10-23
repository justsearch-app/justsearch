/**
 * Port discovery, data directory resolution, and session token acquisition
 * for the JustSearch production MCP server.
 *
 * Data dir resolution replicates PlatformPaths.java:66-120. Manifest validation and the desktop
 * app's data folder come from packaging/mcpb/server/engine-discovery.cjs, shared with the MCPB
 * bridge.
 */

import path from 'node:path';
import os from 'node:os';
import {devDataDirCandidates, isPidAlive, resolveDataDir as resolveUserDataDir} from '../../lib/platform-paths.mjs';
import {describeTried, selectEngine, selectPinned, standardCandidates} from '../../../packaging/mcpb/server/engine-discovery.cjs';

/**
 * Resolve the JustSearch data directory.
 * Mirrors PlatformPaths.resolveDataDir() from
 * modules/configuration/src/main/java/io/justsearch/configuration/PlatformPaths.java
 *
 * Priority: JUSTSEARCH_DATA_DIR env var -> platform default.
 * The JVM-only canonical sysprop (justsearch.data.dir) does not apply outside a JVM
 * process and is skipped here.
 */
export function resolveDataDir() {
  const env = process.env.JUSTSEARCH_DATA_DIR;
  if (env && env.trim()) return path.resolve(env.trim());

  if (process.platform === 'win32') {
    const localAppData = process.env.LOCALAPPDATA;
    if (localAppData && localAppData.trim()) {
      return path.join(localAppData, 'JustSearch');
    }
    return path.join(os.homedir(), 'AppData', 'Local', 'JustSearch');
  }

  if (process.platform === 'darwin') {
    return path.join(os.homedir(), 'Library', 'Application Support', 'JustSearch');
  }

  return path.join(os.homedir(), '.justsearch');
}

// Tempdoc 501 Phase 18: the legacy `readPortFile` helper (read
// <dir>/runtime/api-port.txt) is removed alongside the producer's api-port.txt
// write. Discovery now flows exclusively through findRunningManifest() →
// manifest.json, exposed at the top of this module via the platform-paths
// shared library.

/**
 * The data folders whose runtime manifests discovery validates, in order:
 * JUSTSEARCH_HOME (legacy), JUSTSEARCH_DATA_DIR, the desktop app's folder, the dev `.dev-data`
 * folders above cwd, then the user data folder of headless/CLI starts.
 */
export function engineCandidates({env = process.env, cwd = process.cwd()} = {}) {
  const out = [];
  const jsHome = env.JUSTSEARCH_HOME && env.JUSTSEARCH_HOME.trim();
  if (jsHome) out.push({source: 'JUSTSEARCH_HOME', dataDir: jsHome});
  out.push(...standardCandidates({env}));
  for (const dataDir of devDataDirCandidates(cwd)) out.push({source: 'dev checkout', dataDir});
  out.push({source: 'user data folder', dataDir: resolveUserDataDir({env})});
  return out;
}

/** The session token for a pinned port: JUSTSEARCH_SESSION_TOKEN, which a pin requires. */
function explicitSessionToken() {
  const token = process.env.JUSTSEARCH_SESSION_TOKEN?.trim();
  if (!token) throw new Error('An explicit port requires JUSTSEARCH_SESSION_TOKEN. Read it from the Engine owner-only runtime/manifest.json.');
  return token;
}

/**
 * A port the operator pinned (label: '--port <n>' or 'JUSTSEARCH_API_PORT=<n>'). The pin's token
 * is JUSTSEARCH_SESSION_TOKEN (required: throws without it), and it goes only to a proven Engine:
 * when a manifest names the port, the pin is that manifest Engine, with the same proof as
 * discovery. A manifest that names it but is not proven, or no manifest naming it, is a refusal
 * (recorded in attempts, result null), never a fallback to whatever answers the port.
 */
export async function pinnedEngine(port, label, attempts) {
  const token = explicitSessionToken();
  const { selected, tried } = await selectPinned(engineCandidates(), port);
  if (selected) {
    process.stderr.write(
      `[justsearch-mcp] Using the Engine in ${selected.dataDir} (${selected.source}, pid ${selected.pid}, port ${port}, pinned by ${label})\n`,
    );
    return { port, baseUrl: `http://127.0.0.1:${port}`, token, instanceId: selected.instanceId, dataDir: selected.dataDir, source: selected.source, engine: selected, pin: label };
  }
  if (tried.length > 0) {
    attempts.push(...describeTried(tried).map((line) => `${label}: refused, the manifest naming this port is not proven (${line})`));
  } else {
    attempts.push(`${label}: refused, no runtime manifest names this port, so nothing proves the Engine there and JUSTSEARCH_SESSION_TOKEN is not sent to it; set JUSTSEARCH_DATA_DIR to that Engine's data folder so its runtime manifest is found`);
  }
  return null;
}

/**
 * Discover a running JustSearch instance.
 *
 * Priority:
 * 1. Explicit port (--port CLI arg)
 * 2. JUSTSEARCH_API_PORT env var
 * 3. The first runtime manifest, in engineCandidates() order, whose pid is alive and whose port,
 *    over a connection the OS reports as that pid's (engine-discovery.cjs engineRequest), answers
 *    GET /api/runtime/live with the manifest's pid and instanceId and /api/health with 200.
 *
 * A pin (1, 2) is used through pinnedEngine(): only as the proven Engine of a manifest naming it,
 * with JUSTSEARCH_SESSION_TOKEN. There is no port probe: a port with no validated manifest behind
 * it is never used. A refused pin ends discovery with the refusal: it never falls back to another
 * Engine, whose data the operator did not choose. A manifest Engine's session token is the one in
 * that validated manifest.
 *
 * @param {{ port?: number, verbose?: boolean }} opts
 * @returns {Promise<{ port: number, baseUrl: string, token: string, instanceId: string, dataDir: string, source: string, engine: object, pin?: string }>}
 *   engine: the validated manifest candidate; every request goes through engine-discovery.cjs
 *   engineRequest({ port, engine, token }).
 */
export async function discover({ port: explicitPort, verbose } = {}) {
  const log = verbose ? (msg) => process.stderr.write(`[justsearch-mcp] ${msg}\n`) : () => {};
  const attempts = [];

  // 1. Explicit port (operator override).
  if (explicitPort != null) {
    log(`Trying explicit port ${explicitPort}...`);
    const found = await pinnedEngine(explicitPort, `--port ${explicitPort}`, attempts);
    if (found) return found;
    throw notFound(attempts);
  }

  // 2. JUSTSEARCH_API_PORT env var (legacy operator override).
  const envPort = parseInt(process.env.JUSTSEARCH_API_PORT, 10);
  if (Number.isFinite(envPort) && envPort > 0) {
    log(`Trying JUSTSEARCH_API_PORT=${envPort}...`);
    const found = await pinnedEngine(envPort, `JUSTSEARCH_API_PORT=${envPort}`, attempts);
    if (found) return found;
    throw notFound(attempts);
  }

  // 3. Validated runtime manifests (tempdoc 501 — the canonical discovery path).
  const { selected, tried } = await selectEngine(engineCandidates());
  attempts.push(...describeTried(tried.filter((t) => !t.ok)));
  if (selected) {
    const { port } = selected;
    const token = selected.token;
    process.stderr.write(
      `[justsearch-mcp] Using the Engine in ${selected.dataDir} (${selected.source}, pid ${selected.pid}, port ${port})\n`,
    );
    log(`token=${token ? 'obtained' : token === '' ? 'empty (dev mode)' : 'unavailable'}`);
    return {
      port,
      baseUrl: `http://127.0.0.1:${port}`,
      token,
      instanceId: selected.instanceId,
      dataDir: selected.dataDir,
      source: selected.source,
      engine: selected,
    };
  }

  throw notFound(attempts);
}

/** The error discover() ends with: every location tried, and how to point it at an Engine. */
function notFound(attempts) {
  return new Error(
    'Could not find a running JustSearch instance.\n' +
      'Tried:\n' +
      attempts.map((a) => `  - ${a}`).join('\n') +
      '\n\nMake sure JustSearch is running, or specify the port:\n' +
      '  node justsearch-mcp.mjs --port <port>\n' +
      '  JUSTSEARCH_API_PORT=<port> node justsearch-mcp.mjs\n' +
      'A pinned port needs JUSTSEARCH_SESSION_TOKEN and is used only when a runtime manifest names it and its Engine is proven.',
  );
}

// Keep isPidAlive importable from this module too for any caller that wants
// freshness checks against an already-known port without re-running discovery.
export {isPidAlive};
