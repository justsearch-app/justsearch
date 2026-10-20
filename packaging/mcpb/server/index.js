#!/usr/bin/env node
/**
 * JustSearch MCPB bridge — thin stdio <-> Streamable-HTTP proxy.
 *
 * The real MCP server runs in-process inside the JustSearch desktop app,
 * as Streamable HTTP on the loopback API port (`POST /mcp`; see
 * docs/reference/mcp-production-server.md). MCPB installs are launched by
 * the host (e.g. Claude Desktop) over stdio, so this script bridges the two:
 *
 *   stdin  (newline-delimited JSON-RPC)  --->  POST http://127.0.0.1:<port>/mcp
 *   stdout (newline-delimited JSON-RPC)  <---  JSON or SSE response bodies
 *
 * Port discovery (in order): the JUSTSEARCH_API_PORT env pin, then the runtime manifest
 * (runtime/manifest.json) in JUSTSEARCH_DATA_DIR when set, then in the desktop app's data folder
 * (Windows: %APPDATA%\io.justsearch.shell; Linux: $XDG_DATA_HOME or ~/.local/share, then
 * io.justsearch.shell — see engine-discovery.cjs). A manifest counts only if its pid is alive, the
 * service on its port, over a connection the OS says is that pid's, reports the same pid and
 * instance (GET /api/runtime/live) and /api/health answers 200; there is no default-port
 * guess, so an unrelated service is never picked up. Every request to the Engine goes through
 * engine-discovery.cjs engineRequest(), which writes nothing on a new connection until the OS
 * says its server end is the manifest pid's; when that fails, the bridge selects again (fetching
 * that Engine's own token) and retries once. A JUSTSEARCH_API_PORT pin that a manifest names is
 * that manifest Engine, with the same proof (a manifest naming it that is not proven refuses the
 * pin); a pin no manifest names answers over token-bound connections, each sending only the
 * session token it fetched itself, so whatever takes the port never gets a token from another
 * connection (it does get request content: that is what the pin trusts).
 *
 * Fully offline by design: zero dependencies, Node builtins only, and no
 * network access beyond 127.0.0.1 — this bridge never downloads anything.
 */

'use strict';

const { describeTried, engineRequest, selectEngine, selectPinned, standardCandidates } = require('./engine-discovery.cjs');

const HEALTH_TIMEOUT_MS = 2500;
const REQUEST_TIMEOUT_MS = 300000; // long: answer/ingest calls can be slow on CPU
// Session setup (initialize, notifications/initialized) is cheap; requests wait for it, so it is bounded.
const INITIALIZE_TIMEOUT_MS = 10000;
const LOG_PREFIX = '[justsearch-mcpb]';

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

function log(...args) {
  process.stderr.write(`${LOG_PREFIX} ${args.join(' ')}\n`);
}

function actionableUnreachableMessage(tried) {
  return [
    `${LOG_PREFIX} Could not reach a running JustSearch instance on 127.0.0.1. Tried:`,
    ...(tried.length > 0 ? tried.map((t) => `  - ${t}`) : ['  - (no candidate location)']),
    '',
    'This connector is only a bridge: the actual MCP server runs inside the',
    'JustSearch desktop app. To fix this:',
    '',
    '  1. Install JustSearch if you have not: download the installer from',
    '     https://github.com/justsearch-app/justsearch/releases and follow the',
    '     "Install (Windows)" section of the README',
    '     (https://github.com/justsearch-app/justsearch#install-windows).',
    '  2. Launch JustSearch and wait for the window to load.',
    '  3. Retry from your MCP client (e.g. toggle the connector in Claude Desktop).',
    '',
    'If JustSearch IS running, its runtime/manifest.json in the desktop data folder above',
    'should carry the live API port at head.apiPort, and',
    'http://127.0.0.1:<port>/api/health should answer in a browser.',
  ].join('\n');
}

/** GET /api/health of a pin no manifest names: { ok: true } or { ok: false, detail }. */
function healthy(port) {
  return new Promise((resolve) => {
    const req = engineRequest({ port, engine: null }, { path: '/api/health', timeoutMs: HEALTH_TIMEOUT_MS }, (res) => {
      res.resume(); // drain
      resolve(res.statusCode === 200 ? { ok: true } : { ok: false, detail: `GET /api/health answered ${res.statusCode}` });
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', (e) => resolve({ ok: false, detail: e.message }));
  });
}

/**
 * Fetch the desktop session token (GET /api/mcp/token) of a manifest Engine. In prod mode the API
 * requires it on non-GET requests (X-JustSearch-Session); GETs are always
 * allowed, which is exactly what lets a local MCP client bootstrap. Returns
 * null when absent/empty (dev mode / enforcement disabled), and for a pin no manifest names,
 * whose connections each fetch their own.
 */
function fetchSessionToken(found) {
  if (!found.engine) return Promise.resolve(null);
  return new Promise((resolve) => {
    const req = engineRequest(
      found,
      { path: '/api/mcp/token', timeoutMs: HEALTH_TIMEOUT_MS },
      (res) => {
        let raw = '';
        res.setEncoding('utf8');
        res.on('data', (chunk) => (raw += chunk));
        res.on('end', () => {
          try {
            const token = JSON.parse(raw).token;
            resolve(typeof token === 'string' && token.length > 0 ? token : null);
          } catch {
            resolve(null);
          }
        });
      }
    );
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', () => resolve(null));
  });
}

/**
 * Returns { port, engine } or null; appends one line per location tried to triedOut.
 * Order: JUSTSEARCH_API_PORT pin (the manifest Engine naming that port, or engine null when no
 * manifest names it), then validated runtime manifests (engine-discovery.cjs; engine is the
 * selected candidate).
 */
async function discoverEngine(triedOut) {
  const tried = triedOut || [];
  const envPort = Number.parseInt(process.env.JUSTSEARCH_API_PORT || '', 10);
  if (Number.isInteger(envPort) && envPort > 0) {
    const pin = `JUSTSEARCH_API_PORT=${envPort}`;
    const { selected, tried: named } = await selectPinned(standardCandidates(), envPort);
    if (selected) {
      log(`using the Engine in ${selected.dataDir} (${selected.source}, pid ${selected.pid}, port ${envPort}, pinned by ${pin})`);
      state.pinnedByManifest = true;
      return { port: envPort, engine: selected };
    }
    if (named.length > 0) {
      tried.push(...describeTried(named).map((line) => `${pin}: refused, the manifest naming this port is not proven (${line})`));
    } else if (state.pinnedByManifest) {
      // Accepted through a manifest: without one it is refused, never used with per-connection tokens.
      tried.push(`${pin}: refused, a runtime manifest named this port when it was accepted and none does now`);
    } else {
      const live = await healthy(envPort);
      if (live.ok) return { port: envPort, engine: null };
      tried.push(`${pin} (not responding: ${live.detail})`);
    }
  }
  const { selected, tried: folders } = await selectEngine(standardCandidates());
  tried.push(...describeTried(folders.filter((f) => !f.ok)));
  if (selected) {
    log(`using the Engine in ${selected.dataDir} (${selected.source}, pid ${selected.pid}, port ${selected.port})`);
    return { port: selected.port, engine: selected };
  }
  return null;
}

// ---------------------------------------------------------------------------
// Bridge state
// ---------------------------------------------------------------------------

const state = {
  epoch: null, // the current Engine epoch (newEpoch): everything the bridge holds for one Engine
  pinnedByManifest: false, // the JUSTSEARCH_API_PORT pin was accepted through a manifest
  initializeParams: null, // cached client initialize params (for re-init)
  reinitCounter: 0,
};

/**
 * One Engine epoch: everything the bridge holds for the Engine it talks to. target is
 * { port, engine, token } (engine the validated manifest candidate behind port, null for a
 * JUSTSEARCH_API_PORT pin no manifest names; token its desktop session token, if enforced). Then
 * what was negotiated with it over /mcp: identity (the Engine of the connection that answered its
 * initialize; engineRequest writes nothing on a connection to any other), sessionId (null when the
 * server assigns none), protocolVersion, the server->client stream, and the requests in flight.
 * status: 'new' (no initialize sent), 'initializing', 'ready' (initialize answered, identity
 * known) or 'failed'; settled resolves when it becomes ready or fails. Nothing but its initialize
 * goes out in an epoch before it is ready (readyEpoch).
 */
function newEpoch(target) {
  const epoch = { target, status: 'new', identity: null, sessionId: null, protocolVersion: null, stream: null, pending: new Set() };
  epoch.settled = new Promise((resolve) => (epoch.settle = resolve));
  return epoch;
}

/**
 * Begin a new epoch on target and drop the current one wholesale: its stream closes and its
 * requests in flight are destroyed, so no answer or event of an ended epoch reaches the client
 * or the bridge again. An initialize it had in flight thereby fails, which wakes the requests
 * waiting for it (readyEpoch), and they move on to the new epoch.
 */
function switchEpoch(target) {
  const old = state.epoch;
  state.epoch = newEpoch(target);
  if (old) {
    if (old.stream) old.stream.destroy();
    for (const req of old.pending) req.destroy(Object.assign(new Error('the Engine epoch it was sent in ended'), { code: 'EPOCH_ENDED' }));
  }
  return state.epoch;
}

function settleEpoch(epoch, status) {
  epoch.status = status;
  epoch.settle();
  if (status === 'ready') openGetStream(epoch);
}

/**
 * Send initialize as the first message of epoch (bounded by INITIALIZE_TIMEOUT_MS); its answers go
 * to onMessage. Resolves with its result, or null when it got none; the epoch has then failed, as
 * when this rejects. The caller marks the epoch ready.
 */
async function initializeEpoch(epoch, message, onMessage) {
  epoch.status = 'initializing';
  let result = null;
  try {
    await postToServer(
      epoch,
      message,
      (msg) => {
        if (msg && msg.id === message.id && msg.result) result = msg.result;
        onMessage(msg);
      },
      INITIALIZE_TIMEOUT_MS,
    );
  } catch (e) {
    settleEpoch(epoch, 'failed');
    throw e;
  }
  if (!result) settleEpoch(epoch, 'failed');
  else if (typeof result.protocolVersion === 'string') epoch.protocolVersion = result.protocolVersion;
  return result;
}

/**
 * The current epoch once it is ready or has failed, waiting through replacements: a request goes
 * out only in an epoch whose own initialize was answered. Each wait is bounded by that epoch's
 * initialize (INITIALIZE_TIMEOUT_MS) or its replacement; in an epoch where no initialize was sent,
 * there is nothing to wait for.
 */
async function readyEpoch() {
  for (;;) {
    const epoch = state.epoch;
    if (epoch.status === 'ready' || epoch.status === 'failed') return epoch;
    if (epoch.status === 'new') throw new Error('no MCP session with JustSearch: the client has not sent initialize');
    await epoch.settled;
  }
}

/** engineRequest's session for a request of epoch: none until an Engine answered in it. */
function sessionOf(epoch) {
  return epoch.identity ? { id: epoch.sessionId, identity: epoch.identity } : null;
}

function mcpHeaders(epoch, headers) {
  if (epoch.protocolVersion) headers['mcp-protocol-version'] = epoch.protocolVersion;
  return headers;
}

/** Emit one JSON-RPC message to the stdio client (single line, always). */
function emit(messageObject) {
  process.stdout.write(`${JSON.stringify(messageObject)}\n`);
}

/** Parse an SSE stream incrementally; call onMessage(parsedJson) per event. */
function sseFeed(onMessage) {
  let buffer = '';
  return {
    push(chunk) {
      buffer += chunk;
      let sep;
      // Events are separated by a blank line (\n\n or \r\n\r\n).
      while ((sep = buffer.search(/\r?\n\r?\n/)) !== -1) {
        const rawEvent = buffer.slice(0, sep);
        buffer = buffer.slice(sep).replace(/^\r?\n\r?\n/, '');
        const dataLines = [];
        for (const line of rawEvent.split(/\r?\n/)) {
          if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
        }
        if (dataLines.length === 0) continue;
        const data = dataLines.join('\n');
        try {
          onMessage(JSON.parse(data));
        } catch {
          log(`ignoring non-JSON SSE event: ${data.slice(0, 200)}`);
        }
      }
    },
  };
}

/**
 * POST one JSON-RPC message to /mcp in epoch. Resolves when the HTTP response is fully consumed;
 * JSON-RPC messages found in the response (JSON body or SSE events) go to onMessage (default: to
 * stdout) as they arrive; none once epoch has ended, since that destroys the request.
 * Rejects with err.retryable = true on transport/session-level failures.
 */
function postToServer(epoch, message, onMessage = emit, timeoutMs = REQUEST_TIMEOUT_MS) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify(message);
    const headers = mcpHeaders(epoch, {
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
      'content-length': Buffer.byteLength(body),
    });
    let written = false; // the request had a connection, so it may have reached the server
    const req = engineRequest(
      epoch.target,
      { method: 'POST', path: '/mcp', headers, timeoutMs, body, credential: true, session: sessionOf(epoch) },
      (res) => {
        const status = res.statusCode || 0;
        const assigned = res.headers['mcp-session-id'];
        if (!epoch.identity) epoch.identity = req.connectionIdentity;
        if (typeof assigned === 'string' && assigned.length > 0) epoch.sessionId = assigned;

        if (status === 404 || status === 400) {
          // Streamable HTTP: 404 = session expired/unknown -> re-initialize.
          res.resume();
          const err = new Error(`HTTP ${status} from /mcp (stale session?)`);
          err.retryable = true; // the server answered that it did not take the request
          reject(err);
          return;
        }
        if (status === 202 || status === 204) {
          res.resume();
          resolve();
          return;
        }
        if (status !== 200) {
          res.resume();
          reject(new Error(`HTTP ${status} from /mcp`));
          return;
        }

        const contentType = String(res.headers['content-type'] || '');
        res.setEncoding('utf8');
        if (contentType.includes('text/event-stream')) {
          const feed = sseFeed(onMessage);
          res.on('data', (chunk) => feed.push(chunk));
          res.on('end', resolve);
        } else {
          let raw = '';
          res.on('data', (chunk) => (raw += chunk));
          res.on('end', () => {
            if (raw.trim().length > 0) {
              try {
                onMessage(JSON.parse(raw));
              } catch (e) {
                reject(new Error(`unparseable /mcp response body: ${e.message}`));
                return;
              }
            }
            resolve();
          });
        }
        res.on('error', (e) => {
          const err = new Error(`response stream error: ${e.message}`);
          err.transport = true; // the server had the request: its outcome is unknown
          reject(err);
        });
      }
    );
    epoch.pending.add(req);
    req.once('close', () => epoch.pending.delete(req));
    req.once('socket', () => {
      written = true;
    });
    req.on('timeout', () => req.destroy(new Error('request timeout')));
    req.on('error', (e) => {
      if (e.code === 'NO_TOKEN') return reject(e); // refused before writing: the Engine offers no token
      const err = new Error(`connection failed: ${e.message}`);
      err.transport = true;
      // Only a request that never had a connection (ECONNREFUSED: app restarted or port moved;
      // ENGINE_NOT_PROVEN: refused before writing), or was stopped on its connection before
      // writing because the session belongs to another Engine, is surely unsent and may be
      // sent after recovery.
      err.retryable = !written || e.code === 'SESSION_TARGET_CHANGED';
      reject(err);
    });
  });
}

/**
 * Best-effort server->client stream (GET /mcp) of epoch, opened once when it becomes ready and
 * closed by switchEpoch. Optional per spec; if the server answers 405, the stream drops or its
 * connection is not proven, we go without it.
 */
function openGetStream(epoch) {
  const req = engineRequest(
    epoch.target,
    { path: '/mcp', headers: mcpHeaders(epoch, { accept: 'text/event-stream' }), session: sessionOf(epoch) },
    (res) => {
      if (res.statusCode !== 200) {
        res.resume();
        return; // 405 = server offers no standalone stream; fine.
      }
      res.setEncoding('utf8');
      const feed = sseFeed(emit);
      res.on('data', (chunk) => feed.push(chunk));
      res.on('error', () => {});
    }
  );
  req.on('error', () => {}); // purely best-effort
  epoch.stream = req;
}

/**
 * Recover after a transport failure: re-discover the port (the app may have
 * restarted on a different one), then set up an MCP session in a new epoch there: with
 * clientInitialize (whose answers go to the client) when that is what failed, otherwise with the
 * cached initialize params, so the retried call lands in a valid session.
 * Throws if JustSearch is unreachable or the session could not be set up.
 */
async function recoverConnection(clientInitialize = null) {
  const tried = [];
  const found = await discoverEngine(tried);
  if (found === null) {
    const err = new Error('JustSearch unreachable');
    err.tried = tried;
    throw err;
  }
  // The token rotates per app start; it is fetched from (and only ever sent to) the Engine found,
  // in a new epoch that carries nothing of the old one.
  const epoch = switchEpoch({ port: found.port, engine: found.engine, token: await fetchSessionToken(found) });
  if (clientInitialize) {
    if (await initializeEpoch(epoch, clientInitialize, emit)) settleEpoch(epoch, 'ready');
    return;
  }
  if (!state.initializeParams) return; // nothing to re-establish (yet)

  state.reinitCounter += 1;
  const reinitId = `justsearch-mcpb-reinit-${state.reinitCounter}`;
  const reinit = { jsonrpc: '2.0', id: reinitId, method: 'initialize', params: state.initializeParams };
  if (!(await initializeEpoch(epoch, reinit, () => {}))) {
    throw new Error('re-initialize got no result');
  }
  try {
    await postToServer(epoch, { jsonrpc: '2.0', method: 'notifications/initialized' }, emit, INITIALIZE_TIMEOUT_MS);
  } catch (e) {
    settleEpoch(epoch, 'failed');
    throw e;
  }
  settleEpoch(epoch, 'ready');
  log(`reconnected to http://127.0.0.1:${epoch.target.port}/mcp (new session)`);
}

/**
 * Handle one client->server message, with at most one reconnect and resend. A client initialize
 * begins a new epoch and is redone on the recovered target after any transport failure; any other
 * message waits until the current epoch is ready (readyEpoch). It is sent again only when it
 * surely did not take effect (it never had a connection, or the server answered 400/404 for its
 * session); when the connection failed after it may have been sent, it fails with an error
 * saying its outcome is unknown, so a call such as an ingest is never run twice.
 */
async function handleClientMessage(message) {
  const isInitialize = message && message.method === 'initialize';
  const isSetup = isInitialize || (message && message.id === undefined);
  if (isInitialize && message.params) state.initializeParams = message.params;

  let epoch = null;
  const send = async () => {
    if (isInitialize) {
      epoch = switchEpoch(state.epoch.target);
      if (await initializeEpoch(epoch, message, emit)) settleEpoch(epoch, 'ready');
      return;
    }
    epoch = await readyEpoch();
    if (epoch.status === 'failed') {
      throw Object.assign(new Error('the MCP session with JustSearch could not be set up'), { retryable: true });
    }
    await postToServer(epoch, message);
  };

  try {
    await send();
  } catch (firstError) {
    const ended = epoch !== state.epoch; // the epoch it was sent in is gone, with all it was bound to
    if (ended && isSetup) throw new Error(`${message.method || 'the message'} was superseded: the session it belonged to ended`);
    if (!firstError.retryable && !(isSetup && firstError.transport)) {
      if (!firstError.transport) throw firstError;
      throw new Error(
        `the connection to JustSearch failed after ${message.method || 'the request'} may have been sent (${firstError.message}); ` +
          'its outcome is unknown, so it was not sent again. Check before repeating it.',
      );
    }
    if (!ended) {
      log(`transport error (${firstError.message}) — re-checking where JustSearch is running...`);
      // throws if the app is gone; a client initialize is itself the new handshake
      if (isInitialize) return recoverConnection(message);
      await recoverConnection();
    }
    await send(); // once, in the current epoch
  }
}

function failRequest(message, text) {
  if (message && message.id !== undefined && message.id !== null) {
    emit({
      jsonrpc: '2.0',
      id: message.id,
      error: { code: -32000, message: text },
    });
  }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main() {
  // stdin is read from the start, so closing it stops the bridge even during discovery; messages
  // that arrive before an Engine is found wait for it, in order.
  let markReady;
  const ready = new Promise((resolve) => {
    markReady = resolve;
  });
  let stdinBuffer = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (chunk) => {
    stdinBuffer += chunk;
    let newline;
    while ((newline = stdinBuffer.indexOf('\n')) !== -1) {
      const line = stdinBuffer.slice(0, newline).trim();
      stdinBuffer = stdinBuffer.slice(newline + 1);
      if (line.length === 0) continue;
      let message;
      try {
        message = JSON.parse(line);
      } catch {
        log(`ignoring unparseable stdin line: ${line.slice(0, 200)}`);
        continue;
      }
      ready
        .then(() => handleClientMessage(message))
        .catch((err) => {
          const unreachable = Array.isArray(err.tried);
          const text = unreachable
            ? 'JustSearch is not running. Launch the JustSearch desktop app and retry (install: https://github.com/justsearch-app/justsearch/releases).'
            : `JustSearch bridge error: ${err.message}`;
          failRequest(message, text);
          if (unreachable) {
            process.stderr.write(`${actionableUnreachableMessage(err.tried)}\n`);
            process.exit(1);
          }
          log(`error handling ${message.method || 'message'}: ${err.message}`);
        });
    }
  });
  process.stdin.on('end', () => {
    log('stdin closed — exiting');
    process.exit(0);
  });

  const tried = [];
  const found = await discoverEngine(tried);
  if (found === null) {
    process.stderr.write(`${actionableUnreachableMessage(tried)}\n`);
    process.exit(1);
  }
  const { target } = switchEpoch({ port: found.port, engine: found.engine, token: await fetchSessionToken(found) });
  const tokenNote = target.token ? ' (session token attached)' : found.engine ? '' : ' (pinned port: each connection fetches its own token)';
  log(`bridging stdio <-> http://127.0.0.1:${found.port}/mcp${tokenNote}`);
  markReady();
}

main().catch((err) => {
  log(`fatal: ${err.stack || err.message}`);
  process.exit(1);
});
