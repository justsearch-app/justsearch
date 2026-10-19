/**
 * Engine discovery shared by the production MCP server (scripts/prod/justsearch-mcp) and the
 * MCPB bridge (server/index.js). It lives under packaging/mcpb/server/ because the bridge runs
 * from an extracted bundle outside the checkout and the packer ships server/** verbatim; the
 * checkout-side callers import it from here, so there is one copy.
 *
 * Two jobs:
 *  1. desktopAppDataDir(): the desktop shell's data folder, resolved as Tauri resolves
 *     app.path().app_data_dir() — the `discovery.desktopAppDataDir` entry of
 *     contracts/platform-paths/spec.v1.json (fixtures there; test: engine-discovery.test.mjs).
 *  2. selectEngine(): read each candidate folder's runtime/manifest.json and accept it only when
 *     its pid is alive and the service on its recorded port reports the same pid and instanceId
 *     (GET /api/runtime/live). The first valid candidate wins; every rejected one is reported.
 *
 * Node builtins only, loopback only, CommonJS (the bridge's host may run Node 18).
 */

'use strict';

const fs = require('fs');
const http = require('http');
const os = require('os');
const path = require('path');

/** tauri.conf.json `identifier`; Tauri appends it to the OS data folder. */
const DESKTOP_APP_IDENTIFIER = 'io.justsearch.shell';
const PROBE_TIMEOUT_MS = 2000;
const PROBE_MAX_BYTES = 64 * 1024;

function platformKey(platform) {
  const p = platform || process.platform;
  if (p === 'windows' || p === 'win32') return 'windows';
  if (p === 'macos' || p === 'darwin') return 'macos';
  return 'linux';
}

function nonEmpty(value) {
  return typeof value === 'string' && value.length > 0 ? value : null;
}

/** The home folder as dirs-sys reads it: HOME when non-empty, else the passwd entry (Unix). */
function defaultUserHome(plat, env) {
  if (plat === 'windows') return os.homedir();
  const home = nonEmpty(env.HOME);
  if (home) return home;
  try {
    return nonEmpty(os.userInfo().homedir);
  } catch {
    return null;
  }
}

/**
 * The desktop app's data folder (Tauri 2 app_data_dir = dirs::data_dir() + identifier):
 *  - Windows: the RoamingAppData known folder. Node cannot call SHGetKnownFolderPath, so this
 *    reads APPDATA (set from that folder at logon) and falls back to <home>\AppData\Roaming.
 *  - Linux: XDG_DATA_HOME only when absolute (relative or empty is ignored, as dirs does), else
 *    <home>/.local/share.
 *  - macOS: <home>/Library/Application Support.
 * JUSTSEARCH_DATA_DIR is deliberately not consulted: it never redirects the desktop app.
 * Returns null when no home folder can be determined (Tauri fails the same way).
 */
function desktopAppDataDir({ env = process.env, platform = null, userHome = null } = {}) {
  const plat = platformKey(platform);
  const home = nonEmpty(userHome) || defaultUserHome(plat, env);
  if (plat === 'windows') {
    const appData = nonEmpty(env.APPDATA);
    if (appData && path.win32.isAbsolute(appData)) return path.win32.join(appData, DESKTOP_APP_IDENTIFIER);
    return home ? path.win32.join(home, 'AppData', 'Roaming', DESKTOP_APP_IDENTIFIER) : null;
  }
  if (plat === 'macos') {
    return home ? path.posix.join(home, 'Library', 'Application Support', DESKTOP_APP_IDENTIFIER) : null;
  }
  const xdg = nonEmpty(env.XDG_DATA_HOME);
  if (xdg && path.posix.isAbsolute(xdg)) return path.posix.join(xdg, DESKTOP_APP_IDENTIFIER);
  return home ? path.posix.join(home, '.local', 'share', DESKTOP_APP_IDENTIFIER) : null;
}

function manifestPathFor(dataDir) {
  return path.join(dataDir, 'runtime', 'manifest.json');
}

/** true alive, false dead, null unknown. EPERM means it exists under another user. */
function pidState(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    if (e.code === 'ESRCH') return false;
    if (e.code === 'EPERM') return true;
    return null;
  }
}

/** GET http://127.0.0.1:<port><urlPath> as JSON; never throws. */
function getJson(port, urlPath, timeoutMs) {
  return new Promise((resolve) => {
    let settled = false;
    const done = (value) => {
      if (!settled) {
        settled = true;
        resolve(value);
      }
    };
    const req = http.get(
      { host: '127.0.0.1', port, path: urlPath, timeout: timeoutMs, headers: { accept: 'application/json' } },
      (res) => {
        const chunks = [];
        let size = 0;
        res.on('data', (chunk) => {
          size += chunk.length;
          if (size > PROBE_MAX_BYTES) {
            req.destroy();
            done({ status: res.statusCode || 0, error: 'response too large' });
            return;
          }
          chunks.push(chunk);
        });
        res.on('error', (e) => done({ status: res.statusCode || 0, error: e.message }));
        res.on('end', () => {
          let body = null;
          try {
            body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          } catch {
            /* not JSON */
          }
          done({ status: res.statusCode || 0, body });
        });
      },
    );
    req.on('timeout', () => req.destroy(new Error(`no answer within ${timeoutMs} ms`)));
    req.on('error', (e) => done({ status: 0, error: e.message }));
  });
}

function reject(candidate, status, detail) {
  return { ...candidate, ok: false, status, detail };
}

/**
 * Validate one candidate folder. Accepts only when the manifest is complete, its pid is alive
 * (or cannot be checked), and the service on head.apiPort answers /api/runtime/live with the
 * manifest's own pid and instanceId — which rejects stale manifests, recycled pids and a
 * foreign service that happens to answer health checks on the recorded port.
 */
async function validateCandidate(candidate, { timeoutMs = PROBE_TIMEOUT_MS } = {}) {
  const manifestPath = manifestPathFor(candidate.dataDir);
  let raw;
  try {
    raw = fs.readFileSync(manifestPath, 'utf8');
  } catch (e) {
    return e.code === 'ENOENT' || e.code === 'ENOTDIR'
      ? reject(candidate, 'no-manifest', 'no runtime/manifest.json')
      : reject(candidate, 'unreadable', `cannot read runtime/manifest.json: ${e.code || e.message}`);
  }
  let manifest;
  try {
    manifest = JSON.parse(raw);
  } catch {
    return reject(candidate, 'malformed', 'runtime/manifest.json is not valid JSON');
  }
  const pid = manifest && manifest.pid;
  const instanceId = manifest && manifest.instanceId;
  if (!Number.isInteger(pid) || pid <= 0 || typeof instanceId !== 'string' || instanceId.length === 0) {
    return reject(candidate, 'malformed', 'manifest has no valid pid and instanceId');
  }
  const port = manifest.head ? manifest.head.apiPort : undefined;
  if (port === undefined || port === null) {
    return reject(candidate, 'startup-incomplete', `pid ${pid} has not published an API port yet`);
  }
  if (!Number.isInteger(port) || port <= 0 || port > 65535) {
    return reject(candidate, 'malformed', `manifest API port ${JSON.stringify(port)} is not a port`);
  }
  if (pidState(pid) === false) {
    return reject(candidate, 'stale', `pid ${pid} is not running (port ${port})`);
  }
  const live = await getJson(port, '/api/runtime/live', timeoutMs);
  if (live.status !== 200) {
    const why = live.error ? live.error : `HTTP ${live.status} from /api/runtime/live`;
    return reject(candidate, 'not-responding', `port ${port}: ${why}`);
  }
  const body = live.body;
  if (!body || body.alive !== true || body.pid !== pid || body.instanceId !== instanceId) {
    const seen = body && typeof body === 'object' ? `pid ${body.pid}, instance ${body.instanceId}` : 'no identity';
    return reject(
      candidate,
      'identity-mismatch',
      `port ${port} answers as ${seen}, manifest says pid ${pid}, instance ${instanceId}`,
    );
  }
  return { ...candidate, ok: true, status: 'ok', port, pid, instanceId, lifecycle: manifest.lifecycle || null };
}

function sameFolder(a, b) {
  const ra = path.resolve(a);
  const rb = path.resolve(b);
  return process.platform === 'win32' ? ra.toLowerCase() === rb.toLowerCase() : ra === rb;
}

/**
 * Validate candidates ({source, dataDir}) in order and return the first valid one.
 * Duplicate folders are tried once. Result: { selected: <validated candidate> | null, tried }.
 */
async function selectEngine(candidates, options = {}) {
  const tried = [];
  for (const candidate of candidates) {
    if (!candidate || !nonEmpty(candidate.dataDir)) continue;
    if (tried.some((t) => sameFolder(t.dataDir, candidate.dataDir))) continue;
    const result = await validateCandidate(candidate, options);
    tried.push(result);
    if (result.ok) return { selected: result, tried };
  }
  return { selected: null, tried };
}

/** The explicit data-folder setting, then the desktop app's folder. */
function standardCandidates({ env = process.env, platform = null, userHome = null } = {}) {
  const out = [];
  const explicit = nonEmpty((env.JUSTSEARCH_DATA_DIR || '').trim());
  if (explicit) out.push({ source: 'JUSTSEARCH_DATA_DIR', dataDir: explicit });
  const desktop = desktopAppDataDir({ env, platform, userHome });
  if (desktop) out.push({ source: 'desktop app', dataDir: desktop });
  return out;
}

/** One line per folder tried, for error messages. */
function describeTried(tried) {
  return tried.map((t) => `${t.source}: ${t.dataDir} (${t.ok ? 'selected' : `${t.status}: ${t.detail}`})`);
}

module.exports = {
  DESKTOP_APP_IDENTIFIER,
  desktopAppDataDir,
  validateCandidate,
  selectEngine,
  standardCandidates,
  describeTried,
};
