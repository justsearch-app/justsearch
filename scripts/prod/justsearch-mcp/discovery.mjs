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
import {describeTried, engineRequest, selectEngine, selectPinned, standardCandidates} from '../../../packaging/mcpb/server/engine-discovery.cjs';

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
 * Validate that a JustSearch instance is responding on a pinned port no manifest names.
 * Issues GET /api/status (over a token-bound connection, engine-discovery.cjs) and confirms the
 * response contains status: "ok". Result: { ok: true } or { ok: false, detail }.
 */
export function validatePort(port, timeoutMs = 2000) {
  return new Promise((resolve) => {
    const req = engineRequest(
      { port, engine: null },
      { path: '/api/status', timeoutMs, headers: { Accept: 'application/json' } },
      (res) => {
        if (res.statusCode !== 200) {
          res.resume();
          return resolve({ ok: false, detail: `GET /api/status answered ${res.statusCode}` });
        }
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('error', (e) => resolve({ ok: false, detail: e.message }));
        res.on('end', () => {
          try {
            const json = JSON.parse(Buffer.concat(chunks).toString('utf8'));
            resolve(json.status === 'ok' ? { ok: true } : { ok: false, detail: 'GET /api/status is not a JustSearch status' });
          } catch {
            resolve({ ok: false, detail: 'GET /api/status is not JSON' });
          }
        });
      },
    );
    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', (e) => resolve({ ok: false, detail: e.message }));
  });
}

/**
 * Fetch the session token from GET /api/mcp/token of target ({ port, engine }, engine-discovery.cjs
 * engineRequest: a manifest Engine's connection is proven first).
 * Returns the token string, empty string (dev mode), or null (endpoint not available or not proven).
 */
export function fetchToken(target, timeoutMs = 3000) {
  return new Promise((resolve) => {
    const req = engineRequest(
      target,
      { path: '/api/mcp/token', timeoutMs, headers: { Accept: 'application/json' } },
      (res) => {
        if (res.statusCode !== 200) {
          res.resume();
          return resolve(null);
        }
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('error', () => resolve(null));
        res.on('end', () => {
          try {
            const json = JSON.parse(Buffer.concat(chunks).toString('utf8'));
            resolve(typeof json.token === 'string' ? json.token : null);
          } catch {
            resolve(null);
          }
        });
      },
    );
    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', () => resolve(null));
  });
}

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

/**
 * A port the operator pinned (label: '--port <n>' or 'JUSTSEARCH_API_PORT=<n>'). When a manifest
 * names it, the pin is that manifest Engine, with the same proof as discovery; a manifest that
 * names it but is not proven is a refusal (recorded in attempts), never a fallback. When none
 * does, the pin answers over token-bound connections: token is undefined, because each
 * connection carries the token it fetched itself (engine-discovery.cjs). requireManifest: the pin
 * was accepted through a manifest, so with none naming it now it is refused as well (server.mjs
 * re-selects this way after the Engine restarts on the pinned port).
 */
export async function pinnedEngine(port, label, attempts, log, { requireManifest = false } = {}) {
  const { selected, tried } = await selectPinned(engineCandidates(), port);
  if (selected) {
    const token = await fetchToken({ port, engine: selected });
    process.stderr.write(
      `[justsearch-mcp] Using the Engine in ${selected.dataDir} (${selected.source}, pid ${selected.pid}, port ${port}, pinned by ${label})\n`,
    );
    log(`token=${token ? 'obtained' : token === '' ? 'empty (dev mode)' : 'unavailable'}`);
    return { port, baseUrl: `http://127.0.0.1:${port}`, token, instanceId: selected.instanceId, dataDir: selected.dataDir, source: selected.source, engine: selected, pin: label };
  }
  if (tried.length > 0) {
    attempts.push(...describeTried(tried).map((line) => `${label}: refused, the manifest naming this port is not proven (${line})`));
    return null;
  }
  if (requireManifest) {
    attempts.push(`${label}: refused, a runtime manifest named this port when it was accepted and none does now`);
    return null;
  }
  const live = await validatePort(port);
  if (live.ok) {
    log(`Connected on ${label}: no manifest names this port; each connection fetches its own token`);
    return { port, baseUrl: `http://127.0.0.1:${port}`, token: undefined, pin: label };
  }
  attempts.push(`${label} (not responding: ${live.detail})`);
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
 * A pin (1, 2) is used through pinnedEngine(). There is no port probe: a port with no validated
 * manifest behind it is never used unless the operator named it. A manifest Engine's session
 * token is fetched once, via GET /api/mcp/token over its proven connections.
 *
 * @param {{ port?: number, verbose?: boolean }} opts
 * @returns {Promise<{ port: number, baseUrl: string, token: string | null | undefined, instanceId?: string, dataDir?: string, source?: string, engine?: object, pin?: string }>}
 *   engine: the validated manifest candidate (absent for a pin no manifest names); every
 *   request goes through engine-discovery.cjs engineRequest({ port, engine, token }).
 */
export async function discover({ port: explicitPort, verbose } = {}) {
  const log = verbose ? (msg) => process.stderr.write(`[justsearch-mcp] ${msg}\n`) : () => {};
  const attempts = [];

  // 1. Explicit port (operator override).
  if (explicitPort != null) {
    log(`Trying explicit port ${explicitPort}...`);
    const found = await pinnedEngine(explicitPort, `--port ${explicitPort}`, attempts, log);
    if (found) return found;
  }

  // 2. JUSTSEARCH_API_PORT env var (legacy operator override).
  const envPort = parseInt(process.env.JUSTSEARCH_API_PORT, 10);
  if (Number.isFinite(envPort) && envPort > 0) {
    log(`Trying JUSTSEARCH_API_PORT=${envPort}...`);
    const found = await pinnedEngine(envPort, `JUSTSEARCH_API_PORT=${envPort}`, attempts, log);
    if (found) return found;
  }

  // 3. Validated runtime manifests (tempdoc 501 — the canonical discovery path).
  const { selected, tried } = await selectEngine(engineCandidates());
  attempts.push(...describeTried(tried.filter((t) => !t.ok)));
  if (selected) {
    const { port } = selected;
    const token = await fetchToken({ port, engine: selected });
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

  throw new Error(
    'Could not find a running JustSearch instance.\n' +
      'Tried:\n' +
      attempts.map((a) => `  - ${a}`).join('\n') +
      '\n\nMake sure JustSearch is running, or specify the port:\n' +
      '  node justsearch-mcp.mjs --port <port>\n' +
      '  JUSTSEARCH_API_PORT=<port> node justsearch-mcp.mjs\n' +
      'A pinned port that no runtime manifest names is trusted with request content from whatever answers it, but never with a session token fetched on another connection.',
  );
}

// Keep isPidAlive importable from this module too for any caller that wants
// freshness checks against an already-known port without re-running discovery.
export {isPidAlive};
