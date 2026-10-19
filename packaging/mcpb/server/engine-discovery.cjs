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
 *     its pid is alive, the operating system says that pid holds the listening socket on the
 *     recorded port, the service there reports the manifest's pid and instanceId
 *     (GET /api/runtime/live) and /api/health answers 200. The first valid candidate wins; every
 *     rejected one is reported. confirmEngine() repeats the no-network part before a client sends
 *     credentials again.
 *
 * Why the socket owner: whatever answers on the port can claim any pid and instanceId, so only
 * the OS's record of who holds the listening socket ties the endpoint to the manifest's process.
 * This closes a service of another user (or any other process) that binds the port after the
 * Engine released it, or answers on a reused port, however well it copies the identity. It does
 * not close a process of the same user: that one can already read the data folder, the manifest
 * and the session token's source.
 *
 * Node builtins only, loopback only, CommonJS (the bridge's host may run Node 18).
 */

'use strict';

const { execFile } = require('child_process');
const fs = require('fs');
const http = require('http');
const os = require('os');
const path = require('path');

/** tauri.conf.json `identifier`; Tauri appends it to the OS data folder. */
const DESKTOP_APP_IDENTIFIER = 'io.justsearch.shell';
const PROBE_TIMEOUT_MS = 2000;
/**
 * How long a proven Engine (owner and identity match) that answers /api/health 503 — still
 * starting — is waited for before it is rejected. Hosts often start an agent client and the
 * desktop app together at login. Each attempt is bounded by PROBE_TIMEOUT_MS.
 */
const STARTUP_WAIT_MS = 30000;
const STARTUP_RETRY_MS = 1000;
const STARTUP_WAIT_ENV = 'JUSTSEARCH_DISCOVERY_STARTUP_WAIT_MS';

/**
 * The startup wait both clients use: STARTUP_WAIT_MS, unless JUSTSEARCH_DISCOVERY_STARTUP_WAIT_MS
 * holds a whole number of milliseconds. That variable is a test seam (so tests need not sit
 * through the real bound) and is read only here.
 */
function startupWaitMs(env = process.env) {
  const raw = String(env[STARTUP_WAIT_ENV] ?? '').trim();
  return /^\d+$/.test(raw) ? Number(raw) : STARTUP_WAIT_MS;
}
const PROBE_MAX_BYTES = 64 * 1024;
const OS_QUERY_TIMEOUT_MS = 10000;

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
 *    reads APPDATA (set from that folder at logon, redirection included) and falls back to
 *    <home>\AppData\Roaming; without APPDATA, standardCandidates() also asks the OS for the
 *    folder (roamingAppDataFromOs) after that guess.
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

function windowsTool(...parts) {
  return path.win32.join(nonEmpty(process.env.SystemRoot) || 'C:\\Windows', 'System32', ...parts);
}

function runTool(file, args) {
  return new Promise((resolve) => {
    execFile(
      file,
      args,
      { encoding: 'utf8', timeout: OS_QUERY_TIMEOUT_MS, maxBuffer: 16 * 1024 * 1024, windowsHide: true },
      (error, stdout) => resolve(error ? { error: error.message } : { stdout }),
    );
  });
}

/**
 * The RoamingAppData known folder as Windows reports it ([Environment]::GetFolderPath
 * ('ApplicationData') calls the shell folder API, as Tauri's dirs-sys does through
 * SHGetKnownFolderPath). Covers a process started without APPDATA and a redirected folder.
 * Resolves to null when it cannot be asked.
 */
async function roamingAppDataFromOs() {
  if (process.platform !== 'win32') return null;
  const res = await runTool(windowsTool('WindowsPowerShell', 'v1.0', 'powershell.exe'), [
    '-NoProfile',
    '-NonInteractive',
    '-Command',
    "[Console]::OutputEncoding = [Text.Encoding]::UTF8; [Environment]::GetFolderPath('ApplicationData')",
  ]);
  const folder = res.stdout ? res.stdout.trim() : '';
  return folder && path.win32.isAbsolute(folder) ? folder : null;
}

/** Listening TCP sockets on port (Linux): their inodes from /proc/net/tcp and tcp6. */
function linuxListenerInodes(port) {
  const inodes = new Set();
  for (const file of ['tcp', 'tcp6']) {
    let text;
    try {
      text = fs.readFileSync(`/proc/net/${file}`, 'utf8');
    } catch (e) {
      if (e.code === 'ENOENT') continue; // no IPv6
      throw e;
    }
    for (const row of text.trim().split('\n').slice(1)) {
      const f = row.trim().split(/\s+/);
      const local = f[1] || '';
      if (f[3] === '0A' && parseInt(local.slice(local.lastIndexOf(':') + 1), 16) === port) inodes.add(f[9]);
    }
  }
  return inodes;
}

/**
 * Does the OS say that pid, and only pid, holds every listening socket on port? Never contacts
 * the port. Result: { ok: true } or { ok: false, status, detail }.
 *  - Linux: every LISTEN inode on the port (any address, IPv4 and IPv6) must be one of pid's
 *    open sockets (/proc/<pid>/fd). Another user's process cannot be read, so it never passes.
 *  - Windows: every LISTENING row for the port in the TCP table (netstat -ano, which lists the
 *    owning pid of every user's sockets without elevation) must name pid. Listening rows are
 *    told apart by their foreign port 0, so the localized state column is not read.
 *  - Elsewhere the owner cannot be checked and the candidate is refused.
 */
async function portOwnedBy(port, pid) {
  if (process.platform === 'linux') {
    let inodes;
    try {
      inodes = linuxListenerInodes(port);
    } catch (e) {
      return { ok: false, status: 'owner-unknown', detail: `cannot read the TCP table: ${e.code || e.message}` };
    }
    if (inodes.size === 0) return { ok: false, status: 'not-responding', detail: `nothing listens on port ${port}` };
    const held = new Set();
    let fds;
    try {
      fds = fs.readdirSync(`/proc/${pid}/fd`);
    } catch (e) {
      return { ok: false, status: 'owner-mismatch', detail: `cannot read pid ${pid}'s sockets (${e.code || e.message}); port ${port} is not proven to be its` };
    }
    for (const fd of fds) {
      try {
        const m = /^socket:\[(\d+)\]$/.exec(fs.readlinkSync(`/proc/${pid}/fd/${fd}`));
        if (m) held.add(m[1]);
      } catch {
        /* fd closed meanwhile */
      }
    }
    const foreign = [...inodes].filter((inode) => !held.has(inode));
    return foreign.length === 0
      ? { ok: true }
      : { ok: false, status: 'owner-mismatch', detail: `port ${port} is held by a process other than pid ${pid}` };
  }
  if (process.platform === 'win32') {
    const res = await runTool(windowsTool('NETSTAT.EXE'), ['-ano']);
    if (!res.stdout) return { ok: false, status: 'owner-unknown', detail: `netstat failed: ${res.error}` };
    const owners = new Set();
    for (const line of res.stdout.split(/\r?\n/)) {
      const f = line.trim().split(/\s+/);
      // TCP <local> <foreign> <state> <pid>; the state may be localized, so it is not read.
      if (f.length < 5 || f[0] !== 'TCP') continue;
      if (Number(f[1].slice(f[1].lastIndexOf(':') + 1)) !== port || !/:0$/.test(f[2])) continue;
      owners.add(Number(f[f.length - 1]));
    }
    if (owners.size === 0) return { ok: false, status: 'not-responding', detail: `nothing listens on port ${port}` };
    const others = [...owners].filter((owner) => owner !== pid);
    return others.length === 0
      ? { ok: true }
      : { ok: false, status: 'owner-mismatch', detail: `port ${port} is held by pid ${others.join(', ')}, manifest says pid ${pid}` };
  }
  return { ok: false, status: 'owner-unknown', detail: `cannot check who holds port ${port} on ${process.platform}` };
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

/** Read and check a folder's runtime manifest. Result: { manifest, pid, instanceId, port } or a rejection. */
function readManifest(candidate) {
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
  return { manifest, pid, instanceId, port };
}

/**
 * Validate one candidate folder. Accepts only when the manifest is complete, its pid is alive
 * (or cannot be checked), the OS says that pid holds the listening socket on head.apiPort, the
 * service there answers /api/runtime/live with the manifest's pid and instanceId, and
 * /api/health answers 200. A proven Engine that answers 503 (starting) is re-checked for up to
 * startupWaitMs, the owner proof repeated before each attempt; anything else is rejected at once.
 * Nothing is sent to the port before the owner check, and no credentials are sent at all.
 */
async function validateCandidate(candidate, { timeoutMs = PROBE_TIMEOUT_MS, startupWaitMs: waitMs = startupWaitMs() } = {}) {
  const read = readManifest(candidate);
  if (read.ok === false) return read;
  const { manifest, pid, instanceId, port } = read;
  const owner = await portOwnedBy(port, pid);
  if (!owner.ok) return reject(candidate, owner.status, owner.detail);
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
  const deadline = Date.now() + waitMs;
  for (;;) {
    const health = await getJson(port, '/api/health', timeoutMs);
    if (health.status === 200) break;
    const why = health.error ? health.error : `HTTP ${health.status}`;
    if (health.status !== 503 || Date.now() + STARTUP_RETRY_MS > deadline) {
      const waited = health.status === 503 ? ` after waiting ${Math.round(waitMs / 1000)} s` : '';
      return reject(candidate, 'unhealthy', `pid ${pid} on port ${port} is not ready${waited}: /api/health ${why}`);
    }
    await new Promise((resolve) => setTimeout(resolve, STARTUP_RETRY_MS));
    const still = await portOwnedBy(port, pid);
    if (!still.ok) return reject(candidate, still.status, still.detail);
  }
  return { ...candidate, ok: true, status: 'ok', port, pid, instanceId, lifecycle: manifest.lifecycle || null };
}

/**
 * Before credentials go to a previously selected Engine again: its manifest still names the same
 * pid, instance and port, the pid is alive and the OS says it still holds the port. Never
 * contacts the port. Result: { ok: true } or a rejection; on a rejection, select again.
 */
async function confirmEngine(selected) {
  const read = readManifest(selected);
  if (read.ok === false) return read;
  if (read.pid !== selected.pid || read.instanceId !== selected.instanceId || read.port !== selected.port) {
    return reject(selected, 'replaced', `the manifest now names pid ${read.pid} on port ${read.port}`);
  }
  const owner = await portOwnedBy(selected.port, selected.pid);
  return owner.ok ? { ok: true } : reject(selected, owner.status, owner.detail);
}

function sameFolder(a, b) {
  const ra = path.resolve(a);
  const rb = path.resolve(b);
  return process.platform === 'win32' ? ra.toLowerCase() === rb.toLowerCase() : ra === rb;
}

/**
 * Validate candidates ({source, dataDir}, or {source, resolveDataDir: async () => dataDir} for a
 * folder that costs an OS query and is only worth asking for when earlier ones failed) in order
 * and return the first valid one. Duplicate folders are tried once.
 * Result: { selected: <validated candidate> | null, tried }.
 */
async function selectEngine(candidates, options = {}) {
  const tried = [];
  for (let candidate of candidates) {
    if (candidate && !candidate.dataDir && typeof candidate.resolveDataDir === 'function') {
      candidate = { source: candidate.source, dataDir: await candidate.resolveDataDir() };
    }
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
  const appData = nonEmpty(env.APPDATA);
  if (platformKey(platform) === 'windows' && !(appData && path.win32.isAbsolute(appData))) {
    // Started without APPDATA: the home-folder guess above may miss a redirected Roaming folder.
    out.push({
      source: 'desktop app (Roaming folder the OS reports)',
      resolveDataDir: async () => {
        const roaming = await roamingAppDataFromOs();
        return roaming ? path.win32.join(roaming, DESKTOP_APP_IDENTIFIER) : null;
      },
    });
  }
  return out;
}

/** One line per folder tried, for error messages. */
function describeTried(tried) {
  return tried.map((t) => `${t.source}: ${t.dataDir} (${t.ok ? 'selected' : `${t.status}: ${t.detail}`})`);
}

module.exports = {
  DESKTOP_APP_IDENTIFIER,
  STARTUP_WAIT_MS,
  startupWaitMs,
  desktopAppDataDir,
  validateCandidate,
  confirmEngine,
  selectEngine,
  roamingAppDataFromOs,
  standardCandidates,
  describeTried,
};
