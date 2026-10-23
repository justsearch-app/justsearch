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
 *  4. A port the operator pinned (--port, JUSTSEARCH_API_PORT): selectPinned() looks for a
 *     manifest naming that port; when one does, the pin is that manifest Engine and gets the
 *     proof above (a manifest that names the port but fails it is a refusal, never a fallback).
 *     When none does, nothing proves what answers there, so the clients refuse the pin: the
 *     session token (JUSTSEARCH_SESSION_TOKEN for a pin) goes only to a proven Engine.
 *  5. Idle pooled connections are retired after IDLE_RETIRE_MS (http.Agent timeout, idleRetireMs),
 *     before the Engine's own idle expiry (Jetty: 30 s) can close one just as a request is
 *     written on it.
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
/** A pooled connection idle this long is closed by the agent (the Engine closes idle ones at 30 s). */
const IDLE_RETIRE_MS = 25000;
const IDLE_RETIRE_ENV = 'JUSTSEARCH_DISCOVERY_IDLE_RETIRE_MS';

/**
 * The idle retirement both clients' agents use: IDLE_RETIRE_MS, unless
 * JUSTSEARCH_DISCOVERY_IDLE_RETIRE_MS holds a whole number of milliseconds. That variable is a
 * test seam (so a test can scale the boundary down with its fixture's idle expiry) and is read
 * only here.
 */
function idleRetireMs(env = process.env) {
  const raw = String(env[IDLE_RETIRE_ENV] ?? '').trim();
  return /^\d+$/.test(raw) ? Number(raw) : IDLE_RETIRE_MS;
}
const OS_QUERY_TIMEOUT_MS = 10000;
/**
 * The Roaming folder lookup starts PowerShell, whose first start on a cold or busy machine takes
 * about 10 s (a fresh hosted Windows runner took 10.06 s and 10.8 s), so it gets its own bound.
 */
const ROAMING_QUERY_TIMEOUT_MS = 30000;
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

function runTool(file, args, timeoutMs = OS_QUERY_TIMEOUT_MS) {
  return new Promise((resolve) => {
    execFile(
      file,
      args,
      { encoding: 'utf8', timeout: timeoutMs, maxBuffer: 16 * 1024 * 1024, windowsHide: true },
      (error, stdout) =>
        resolve(error ? { error: error.message, timedOut: error.killed === true && error.code !== 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER' } : { stdout }),
    );
  });
}

function roamingQuery() {
  return {
    file: windowsTool('WindowsPowerShell', 'v1.0', 'powershell.exe'),
    args: [
      '-NoProfile',
      '-NonInteractive',
      '-Command',
      "[Console]::OutputEncoding = [Text.Encoding]::UTF8; [Environment]::GetFolderPath('ApplicationData')",
    ],
    timeoutMs: ROAMING_QUERY_TIMEOUT_MS,
  };
}

function lookupError(code, detail) {
  return Object.assign(new Error(`the Roaming folder lookup ${detail}`), { code });
}

/**
 * The RoamingAppData known folder as Windows reports it ([Environment]::GetFolderPath
 * ('ApplicationData') calls the shell folder API, as Tauri's dirs-sys does through
 * SHGetKnownFolderPath). Covers a process started without APPDATA and a redirected folder.
 * Resolves to null off Windows. A lookup that does not answer within its bound rejects with
 * code ROAMING_LOOKUP_TIMEOUT, and one that fails or answers no absolute folder with
 * ROAMING_LOOKUP_FAILED, so discovery names it instead of skipping the folder silently.
 * query ({file, args, timeoutMs}) replaces the PowerShell lookup in tests.
 */
async function roamingAppDataFromOs(query = process.platform === 'win32' ? roamingQuery() : null) {
  if (!query) return null;
  const res = await runTool(query.file, query.args, query.timeoutMs);
  if (res.timedOut) throw lookupError('ROAMING_LOOKUP_TIMEOUT', `(${path.basename(query.file)}) did not answer within ${query.timeoutMs} ms`);
  if (res.error) throw lookupError('ROAMING_LOOKUP_FAILED', `(${path.basename(query.file)}) failed: ${res.error}`);
  const folder = res.stdout.trim();
  if (!path.win32.isAbsolute(folder)) throw lookupError('ROAMING_LOOKUP_FAILED', `(${path.basename(query.file)}) answered ${JSON.stringify(folder)}, not an absolute folder`);
  return folder;
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

const SESSION_HEADER = 'x-justsearch-session';

/** Connections to a port no manifest names: used as given, never with a credential (engineRequest). */
const unprovenAgent = new http.Agent({ keepAlive: true, timeout: idleRetireMs() });
const engineAgents = new Map();

function engineKey(engine) {
  return [engine.dataDir, engine.pid, engine.instanceId, engine.port].join('\0');
}

/** The keep-alive agent for a target: per manifest Engine, connections proven by owner (provenConnection). */
function agentFor(engine) {
  if (!engine) return unprovenAgent;
  const key = engineKey(engine);
  let agent = engineAgents.get(key);
  if (!agent) {
    agent = new http.Agent({ keepAlive: true, timeout: idleRetireMs() });
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
 * The only way the clients send a request to an Engine: one http.request to 127.0.0.1 through
 * agentFor(target.engine), ended here with body. target is { port, engine, token }: engine is the
 * manifest candidate ({ dataDir, pid, instanceId, port }) and token its session token (from that
 * validated manifest, or JUSTSEARCH_SESSION_TOKEN for a pin the manifest names; '' is dev mode).
 * engine null is a port nothing proves: it gets public probes only. The request's identity (the
 * Engine's pid/instanceId) and token are left on it (req.connectionIdentity, req.connectionToken).
 * credential: true adds the token (X-JustSearch-Session) and fails closed: on a target with no
 * engine, or once the connection is proven when the target holds no token (not a string), the
 * request is destroyed before anything is written, with err.code 'ENGINE_NOT_PROVEN' or
 * 'NO_TOKEN'. session ({ id, identity }: what a client negotiated with one Engine) adds
 * Mcp-Session-Id when id is set (a server may assign none); for any other identity the request is
 * destroyed before anything is written, with err.code 'SESSION_TARGET_CHANGED', so nothing
 * negotiated with one Engine (session id, protocol version) reaches another, whether or not it
 * assigned a session id. Returns the http.ClientRequest; a failed proof surfaces as its 'error'
 * with err.code 'ENGINE_NOT_PROVEN' (and err.status, err.detail).
 */
function engineRequest(target, { method = 'GET', path: urlPath, headers = {}, timeoutMs, body, credential = false, session = null }, onResponse) {
  const engine = target.engine || null;
  const req = http.request(
    { host: '127.0.0.1', port: engine ? engine.port : target.port, path: urlPath, method, headers, timeout: timeoutMs, agent: agentFor(engine) },
    onResponse,
  );
  const identity = engine ? `${engine.pid}/${engine.instanceId}` : null;
  req.connectionToken = target.token;
  req.connectionIdentity = identity;
  const refuse = (code, message) => process.nextTick(() => req.destroy(Object.assign(new Error(message), { code })));
  if ((credential || session) && !engine) {
    refuse('ENGINE_NOT_PROVEN', `nothing proves what answers port ${target.port}; nothing that needs the Engine was sent`);
    return req;
  }
  if (session && session.identity !== identity) {
    refuse('SESSION_TARGET_CHANGED', 'the MCP session was opened on another Engine than this connection reaches; nothing was sent');
    return req;
  }
  if (credential && typeof target.token !== 'string') {
    // Decided once a connection is proven, so a replaced Engine surfaces as ENGINE_NOT_PROVEN.
    req.once('socket', () => req.destroy(Object.assign(new Error(`the Engine on port ${target.port} offers no session token; nothing that needs one was sent`), { code: 'NO_TOKEN' })));
    return req;
  }
  if (credential && target.token) req.setHeader(SESSION_HEADER, target.token);
  if (session && session.id) req.setHeader('mcp-session-id', session.id);
  req.end(body);
  return req;
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
    return Object.assign(reject(candidate, 'stale', `pid ${pid} is not running (port ${port})`), { port });
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
      try {
        candidate = { source: candidate.source, dataDir: await candidate.resolveDataDir() };
      } catch (err) {
        // The folder could not be determined: name why among the folders tried.
        tried.push({ source: candidate.source, dataDir: null, ok: false, status: err.code || 'lookup-failed', detail: err.message });
        continue;
      }
    }
    if (!candidate || !nonEmpty(candidate.dataDir)) continue;
    if (tried.some((t) => t.dataDir && sameFolder(t.dataDir, candidate.dataDir))) continue;
    const result = await validateCandidate(candidate, options);
    tried.push(result);
    if (result.ok) return { selected: result, tried };
  }
  return { selected: null, tried };
}

/**
 * The Engine behind a pinned port: the first candidate whose manifest names port and passes
 * validateCandidate. Result: { selected, tried }; tried lists the candidates whose manifest named
 * the port, including a stale one (its pid is gone). selected null with tried non-empty is a
 * refusal: the port belongs to a manifest Engine that is not proven, and the pin must not be
 * used without that proof. Both empty: no manifest names the port, so nothing can prove what
 * answers it and the clients refuse the pin as well.
 */
async function selectPinned(candidates, port, options = {}) {
  const seen = [];
  const tried = [];
  for (let candidate of candidates) {
    if (candidate && !candidate.dataDir && typeof candidate.resolveDataDir === 'function') {
      let dataDir = null;
      try {
        dataDir = await candidate.resolveDataDir();
      } catch {
        // A folder that cannot be determined holds no manifest this pin can be checked against.
      }
      candidate = { source: candidate.source, dataDir };
    }
    if (!candidate || !nonEmpty(candidate.dataDir)) continue;
    if (seen.some((dataDir) => sameFolder(dataDir, candidate.dataDir))) continue;
    seen.push(candidate.dataDir);
    const read = readManifest(candidate);
    if (read.ok === false && read.status === 'stale' && read.port === port) {
      // A manifest whose Engine is gone still names the port: whatever answers there now is not
      // that Engine, and the pin is not one no manifest names.
      tried.push({ ...read, detail: `${manifestPathFor(candidate.dataDir)} names this port, but its ${read.detail}` });
      continue;
    }
    if (read.ok === false || read.port !== port) continue;
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
  return tried.map((t) => `${t.source}: ${t.dataDir ?? 'folder unknown'} (${t.ok ? 'selected' : `${t.status}: ${t.detail}`})`);
}

/**
 * Why a pin (label, e.g. '--port 4242') was refused, from selectPinned's tried (the manifests
 * naming the port, none proven; empty when none names it), one line each with the next step.
 */
function pinRefusals(label, tried) {
  if (tried.length === 0) {
    return [`${label}: refused, no runtime manifest names this port, so nothing proves the Engine there and JUSTSEARCH_SESSION_TOKEN is not sent to it; set JUSTSEARCH_DATA_DIR to that Engine's data folder so its runtime manifest is found`];
  }
  return describeTried(tried).map((line) => `${label}: refused, the manifest naming this port is not proven (${line}); what answers the port is not the Engine that manifest describes, so restart that Engine (it rewrites its manifest) or check which process owns the port`);
}

module.exports = {
  DESKTOP_APP_IDENTIFIER,
  STARTUP_WAIT_MS,
  IDLE_RETIRE_MS,
  idleRetireMs,
  startupWaitMs,
  desktopAppDataDir,
  validateCandidate,
  selectEngine,
  selectPinned,
  engineRequest,
  ROAMING_QUERY_TIMEOUT_MS,
  roamingAppDataFromOs,
  standardCandidates,
  describeTried,
  pinRefusals,
};
