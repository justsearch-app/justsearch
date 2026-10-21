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
 *     its pid is alive, the service on the recorded port, over a connection proven to be that
 *     pid's (below), reports the manifest's pid and instanceId (GET /api/runtime/live) and
 *     /api/health answers 200. The first valid candidate wins; every rejected one is reported.
 *  3. engineRequest(): the one way both clients send anything to an Engine. For a manifest
 *     Engine it uses a keep-alive agent whose every new TCP connection is handed to the request
 *     only after (a) the manifest still names the same pid, instance and port and (b) the OS
 *     says the server end of this exact connection (127.0.0.1:<port> <-> our local port) is a
 *     socket of that pid. Otherwise the socket is destroyed with nothing written, and the request
 *     fails with err.code 'ENGINE_NOT_PROVEN'. A reused connection needs no new proof: a process
 *     that binds the port later cannot take over an established connection. So there is no gap
 *     between a check and the request it guards, and the OS is asked once per connection.
 *
 * Why the socket owner: whatever answers on the port can claim any pid and instanceId, so only
 * the OS's record of who holds the connection ties the endpoint to the manifest's process.
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
const net = require('net');
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
/** The server may not have accepted a fresh connection yet; it is looked up again this often. */
const PROOF_RETRY_MS = 10;

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

/** A /proc/net/tcp{,6} address (hex, kernel byte order) as dotted IPv4, or null if not IPv4. */
function procIPv4(hex) {
  const v4 = hex.length === 8 ? hex : hex.length === 32 && hex.startsWith('0000000000000000FFFF0000') ? hex.slice(24) : null;
  return v4 ? [6, 4, 2, 0].map((i) => parseInt(v4.slice(i, i + 2), 16)).join('.') : null;
}

/** A socket or netstat address without brackets or the IPv4-mapped prefix. */
function plainAddress(address) {
  return String(address || '').replace(/^\[|\]$/g, '').replace(/^::ffff:/i, '');
}

/**
 * Linux: the server end of the connection is the established row whose local end is
 * server and whose remote end is client; it must be one of pid's open sockets (/proc/<pid>/fd).
 * Another user's process cannot be read, so it never passes. { pending: true } while the server
 * has not accepted it yet (no row, or inode 0).
 */
function linuxServerEnd(server, client, pid) {
  let inode = null;
  for (const file of ['tcp', 'tcp6']) {
    let text;
    try {
      text = fs.readFileSync(`/proc/net/${file}`, 'utf8');
    } catch (e) {
      if (e.code === 'ENOENT') continue; // no IPv6
      return { ok: false, status: 'owner-unknown', detail: `cannot read the TCP table: ${e.code || e.message}` };
    }
    for (const row of text.trim().split('\n').slice(1)) {
      const f = row.trim().split(/\s+/);
      if (f[3] !== '01') continue; // ESTABLISHED
      const [la, lp] = (f[1] || '').split(':');
      const [ra, rp] = (f[2] || '').split(':');
      if (parseInt(lp, 16) !== server.port || parseInt(rp, 16) !== client.port) continue;
      if (procIPv4(la) !== server.address || procIPv4(ra) !== client.address) continue;
      inode = f[9];
    }
  }
  if (!inode || inode === '0') return { pending: true };
  let fds;
  try {
    fds = fs.readdirSync(`/proc/${pid}/fd`);
  } catch (e) {
    return { ok: false, status: 'owner-mismatch', detail: `cannot read pid ${pid}'s sockets (${e.code || e.message}); the connection to port ${server.port} is not proven to be its` };
  }
  const wanted = `socket:[${inode}]`;
  for (const fd of fds) {
    try {
      if (fs.readlinkSync(`/proc/${pid}/fd/${fd}`) === wanted) return { ok: true };
    } catch {
      /* fd closed meanwhile */
    }
  }
  return { ok: false, status: 'owner-mismatch', detail: `the connection to port ${server.port} is held by a process other than pid ${pid}` };
}

/**
 * Windows: the row of the TCP table (netstat -ano, which lists the owning pid of every user's
 * sockets without elevation) whose local end is server and foreign end is client must name pid.
 * The localized state column is not read.
 */
async function windowsServerEnd(server, client, pid) {
  const res = await runTool(windowsTool('NETSTAT.EXE'), ['-ano']);
  if (!res.stdout) return { ok: false, status: 'owner-unknown', detail: `netstat failed: ${res.error}` };
  const end = (field) => {
    const i = field.lastIndexOf(':');
    return { address: plainAddress(field.slice(0, i)), port: Number(field.slice(i + 1)) };
  };
  const same = (a, b) => a.address === b.address && a.port === b.port;
  for (const line of res.stdout.split(/\r?\n/)) {
    const f = line.trim().split(/\s+/);
    if (f.length < 5 || f[0] !== 'TCP' || !same(end(f[1]), server) || !same(end(f[2]), client)) continue;
    const owner = Number(f[f.length - 1]);
    return owner === pid
      ? { ok: true }
      : { ok: false, status: 'owner-mismatch', detail: `the connection to port ${server.port} is held by pid ${owner}, manifest says pid ${pid}` };
  }
  return { pending: true };
}

/** Does the OS say the server end of this connected socket belongs to pid? */
async function connectionOwnedBy(socket, pid) {
  const server = { address: plainAddress(socket.remoteAddress), port: socket.remotePort };
  const client = { address: plainAddress(socket.localAddress), port: socket.localPort };
  const lookUp = process.platform === 'linux' ? linuxServerEnd : process.platform === 'win32' ? windowsServerEnd : null;
  if (!lookUp) return { ok: false, status: 'owner-unknown', detail: `cannot check who holds a connection on ${process.platform}` };
  const deadline = Date.now() + PROBE_TIMEOUT_MS;
  for (;;) {
    const result = await lookUp(server, client, pid);
    if (!result.pending) return result;
    if (Date.now() >= deadline) {
      return { ok: false, status: 'owner-mismatch', detail: `the OS lists no server end for the connection to port ${server.port}` };
    }
    await new Promise((resolve) => setTimeout(resolve, PROOF_RETRY_MS));
  }
}

function notProven(status, detail) {
  return Object.assign(new Error(`not proven to be the Engine (${status}: ${detail})`), { code: 'ENGINE_NOT_PROVEN', status, detail });
}

/**
 * http.Agent createConnection for a manifest Engine: connect, prove, and only then hand the
 * socket to the agent (which then writes the request). Nothing is written before the proof.
 */
function provenConnection(engine, options, done) {
  const read = readManifest(engine);
  if (read.ok === false) return done(notProven(read.status, read.detail));
  if (read.pid !== engine.pid || read.instanceId !== engine.instanceId || read.port !== engine.port) {
    return done(notProven('replaced', `the manifest now names pid ${read.pid} on port ${read.port}`));
  }
  const socket = net.connect({ host: options.host, port: options.port });
  let settled = false;
  const settle = (err) => {
    if (settled) return;
    settled = true;
    socket.removeListener('error', settle);
    if (err) {
      socket.destroy();
      done(err);
    } else {
      done(null, socket);
    }
  };
  socket.once('error', settle);
  socket.once('connect', () => {
    connectionOwnedBy(socket, engine.pid).then(
      (proof) => settle(proof.ok ? null : notProven(proof.status, proof.detail)),
      (e) => settle(notProven('owner-unknown', e.message)),
    );
  });
  return undefined;
}

const pinnedAgent = new http.Agent({ keepAlive: true });
const engineAgents = new Map();

function engineKey(engine) {
  return [engine.dataDir, engine.pid, engine.instanceId, engine.port].join('\0');
}

/** One keep-alive agent per manifest Engine, whose connections are proven (provenConnection). */
function agentFor(engine) {
  if (!engine) return pinnedAgent;
  const key = engineKey(engine);
  let agent = engineAgents.get(key);
  if (!agent) {
    agent = new http.Agent({ keepAlive: true });
    agent.createConnection = (options, done) => provenConnection(engine, options, done);
    engineAgents.set(key, agent);
  }
  return agent;
}

/** Close the connections to a candidate that was not selected. */
function forgetEngine(engine) {
  const key = engineKey(engine);
  const agent = engineAgents.get(key);
  if (agent) agent.destroy();
  engineAgents.delete(key);
}

/**
 * The only way the clients send a request to an Engine: http.request to 127.0.0.1 through
 * agentFor(target.engine). target is { port, engine }: engine is the manifest candidate
 * ({ dataDir, pid, instanceId, port }), or null for a port the operator named (--port,
 * JUSTSEARCH_API_PORT), which is used as given. Returns the http.ClientRequest; a failed proof
 * surfaces as its 'error' with err.code 'ENGINE_NOT_PROVEN' (and err.status, err.detail).
 */
function engineRequest(target, { method = 'GET', path: urlPath, headers = {}, timeoutMs }, onResponse) {
  const engine = target.engine || null;
  return http.request(
    {
      host: '127.0.0.1',
      port: engine ? engine.port : target.port,
      path: urlPath,
      method,
      headers,
      timeout: timeoutMs,
      agent: agentFor(engine),
    },
    onResponse,
  );
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

/** GET <urlPath> from target (engineRequest) as JSON; never throws. A failed proof is in .proof. */
function getJson(target, urlPath, timeoutMs) {
  return new Promise((resolve) => {
    let settled = false;
    const done = (value) => {
      if (!settled) {
        settled = true;
        resolve(value);
      }
    };
    const req = engineRequest(
      target,
      { path: urlPath, timeoutMs, headers: { accept: 'application/json' } },
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
    req.on('error', (e) => done({ status: 0, error: e.message, proof: e.code === 'ENGINE_NOT_PROVEN' ? e : null }));
    req.end();
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
 * (or cannot be checked), the service on head.apiPort, over a connection the OS says is that
 * pid's (engineRequest), answers /api/runtime/live with the manifest's pid and instanceId, and
 * /api/health answers 200. A proven Engine that answers 503 (starting) is re-checked for up to
 * startupWaitMs; anything else is rejected at once. Nothing is written to a connection before
 * its proof, and no credentials are sent at all.
 */
async function validateCandidate(candidate, { timeoutMs = PROBE_TIMEOUT_MS, startupWaitMs: waitMs = startupWaitMs() } = {}) {
  const read = readManifest(candidate);
  if (read.ok === false) return read;
  const { manifest, pid, instanceId, port } = read;
  const engine = { dataDir: candidate.dataDir, pid, instanceId, port };
  const target = { port, engine };
  const refuse = (status, detail) => {
    forgetEngine(engine);
    return reject(candidate, status, detail);
  };
  const live = await getJson(target, '/api/runtime/live', timeoutMs);
  if (live.proof) return refuse(live.proof.status, live.proof.detail);
  if (live.status !== 200) {
    const why = live.error ? live.error : `HTTP ${live.status} from /api/runtime/live`;
    return refuse('not-responding', `port ${port}: ${why}`);
  }
  const body = live.body;
  if (!body || body.alive !== true || body.pid !== pid || body.instanceId !== instanceId) {
    const seen = body && typeof body === 'object' ? `pid ${body.pid}, instance ${body.instanceId}` : 'no identity';
    return refuse('identity-mismatch', `port ${port} answers as ${seen}, manifest says pid ${pid}, instance ${instanceId}`);
  }
  const deadline = Date.now() + waitMs;
  for (;;) {
    const health = await getJson(target, '/api/health', timeoutMs);
    if (health.proof) return refuse(health.proof.status, health.proof.detail);
    if (health.status === 200) break;
    const why = health.error ? health.error : `HTTP ${health.status}`;
    if (health.status !== 503 || Date.now() + STARTUP_RETRY_MS > deadline) {
      const waited = health.status === 503 ? ` after waiting ${Math.round(waitMs / 1000)} s` : '';
      return refuse('unhealthy', `pid ${pid} on port ${port} is not ready${waited}: /api/health ${why}`);
    }
    await new Promise((resolve) => setTimeout(resolve, STARTUP_RETRY_MS));
  }
  return { ...candidate, ok: true, status: 'ok', ...engine,
    token: typeof manifest.head.sessionToken === 'string' ? manifest.head.sessionToken : '',
    lifecycle: manifest.lifecycle || null };
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
  selectEngine,
  engineRequest,
  roamingAppDataFromOs,
  standardCandidates,
  describeTried,
};
