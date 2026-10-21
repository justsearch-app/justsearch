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
import {describeTried, engineRequest, selectEngine, standardCandidates} from '../../../packaging/mcpb/server/engine-discovery.cjs';

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
 * Validate that a JustSearch instance is responding on the given port.
 * Issues GET /api/status and confirms the response contains status: "ok".
 */
export function validatePort(port, timeoutMs = 2000) {
  return new Promise((resolve) => {
    const req = engineRequest(
      { port, engine: null },
      { path: '/api/status', timeoutMs, headers: { Accept: 'application/json' } },
      (res) => {
        if (res.statusCode !== 200) {
          res.resume();
          return resolve(false);
        }
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('error', () => resolve(false));
        res.on('end', () => {
          try {
            const json = JSON.parse(Buffer.concat(chunks).toString('utf8'));
            resolve(json.status === 'ok');
          } catch {
            resolve(false);
          }
        });
      },
    );
    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', () => resolve(false));
    req.end();
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
 * Discover a running JustSearch instance.
 *
 * Priority:
 * 1. Explicit port (--port CLI arg)
 * 2. JUSTSEARCH_API_PORT env var
 * 3. The first runtime manifest, in engineCandidates() order, whose pid is alive and whose port,
 *    over a connection the OS reports as that pid's (engine-discovery.cjs engineRequest), answers
 *    GET /api/runtime/live with the manifest's pid and instanceId and /api/health with 200.
 *
 * There is no port probe: a port with no validated manifest behind it is never used unless the
 * operator named it. Once proven, uses the token from that validated manifest.
 *
 * @param {{ port?: number, verbose?: boolean }} opts
 * @returns {Promise<{ port: number, baseUrl: string, token: string | null, instanceId?: string, dataDir?: string, source?: string, engine?: object }>}
 *   engine: the validated manifest candidate (absent for --port / JUSTSEARCH_API_PORT); every
 *   request to it goes through engine-discovery.cjs engineRequest({ port, engine }).
 */
function explicitSessionToken() {
  const token = process.env.JUSTSEARCH_SESSION_TOKEN?.trim();
  if (!token) throw new Error('An explicit port requires JUSTSEARCH_SESSION_TOKEN. Read it from the Engine owner-only runtime/manifest.json.');
  return token;
}

export async function discover({ port: explicitPort, verbose } = {}) {
  const log = verbose ? (msg) => process.stderr.write(`[justsearch-mcp] ${msg}\n`) : () => {};
  const attempts = [];

  // 1. Explicit port (operator override; bypasses manifest discovery entirely).
  if (explicitPort != null) {
    explicitSessionToken();
    log(`Trying explicit port ${explicitPort}...`);
    if (await validatePort(explicitPort)) {
      const token = explicitSessionToken();
      log(`Connected on port ${explicitPort}, token=${token ? 'obtained' : token === '' ? 'empty (dev mode)' : 'unavailable'}`);
      return { port: explicitPort, baseUrl: `http://127.0.0.1:${explicitPort}`, token };
    }
    attempts.push(`--port ${explicitPort} (not responding)`);
  }

  // 2. JUSTSEARCH_API_PORT env var (legacy operator override).
  const envPort = parseInt(process.env.JUSTSEARCH_API_PORT, 10);
  if (Number.isFinite(envPort) && envPort > 0) {
    explicitSessionToken();
    log(`Trying JUSTSEARCH_API_PORT=${envPort}...`);
    if (await validatePort(envPort)) {
      const token = explicitSessionToken();
      log(`Connected on port ${envPort}, token=${token ? 'obtained' : token === '' ? 'empty (dev mode)' : 'unavailable'}`);
      return { port: envPort, baseUrl: `http://127.0.0.1:${envPort}`, token };
    }
    attempts.push(`JUSTSEARCH_API_PORT=${envPort} (not responding)`);
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

  throw new Error(
    'Could not find a running JustSearch instance.\n' +
      'Tried:\n' +
      attempts.map((a) => `  - ${a}`).join('\n') +
      '\n\nMake sure JustSearch is running, or specify the port:\n' +
      '  node justsearch-mcp.mjs --port <port>\n' +
      '  JUSTSEARCH_API_PORT=<port> node justsearch-mcp.mjs',
  );
}

// Keep isPidAlive importable from this module too for any caller that wants
// freshness checks against an already-known port without re-running discovery.
export {isPidAlive};
