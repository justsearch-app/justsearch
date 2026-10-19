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
 * OS says that pid holds the listening socket on its port, the service there reports the same pid
 * and instance (GET /api/runtime/live) and /api/health answers 200; there is no default-port
 * guess, so an unrelated service is never picked up. Before each message that carries the session
 * token, the bridge re-checks (without network) that the same Engine still holds the port, and
 * selects again — fetching that Engine's own token — when it does not.
 *
 * Fully offline by design: zero dependencies, Node builtins only, and no
 * network access beyond 127.0.0.1 — this bridge never downloads anything.
 */

'use strict';

const http = require('http');
const { confirmEngine, describeTried, selectEngine, standardCandidates } = require('./engine-discovery.cjs');

const HEALTH_TIMEOUT_MS = 2500;
const REQUEST_TIMEOUT_MS = 300000; // long: answer/ingest calls can be slow on CPU
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

function httpGet(port, urlPath, timeoutMs) {
  return new Promise((resolve) => {
    const req = http.get(
      { host: '127.0.0.1', port, path: urlPath, timeout: timeoutMs },
      (res) => {
        res.resume(); // drain
        resolve(res.statusCode || 0);
      }
    );
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', () => resolve(0));
  });
}

async function healthy(port) {
  return (await httpGet(port, '/api/health', HEALTH_TIMEOUT_MS)) === 200;
}

/**
 * Fetch the desktop session token (GET /api/mcp/token). In prod mode the API
 * requires it on non-GET requests (X-JustSearch-Session); GETs are always
 * allowed, which is exactly what lets a local MCP client bootstrap. Returns
 * null when absent/empty (dev mode / enforcement disabled).
 */
function fetchSessionToken(port) {
  return new Promise((resolve) => {
    const req = http.get(
      { host: '127.0.0.1', port, path: '/api/mcp/token', timeout: HEALTH_TIMEOUT_MS },
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
 * Order: JUSTSEARCH_API_PORT pin (engine null: the operator named the port), then validated
 * runtime manifests (engine-discovery.cjs; engine is the selected candidate).
 */
async function discoverEngine(triedOut) {
  const tried = triedOut || [];
  const envPort = Number.parseInt(process.env.JUSTSEARCH_API_PORT || '', 10);
  if (Number.isInteger(envPort) && envPort > 0) {
    if (await healthy(envPort)) return { port: envPort, engine: null };
    tried.push(`JUSTSEARCH_API_PORT=${envPort} (not responding)`);
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
  port: null,
  engine: null, // the validated manifest candidate behind port (null for a JUSTSEARCH_API_PORT pin)
  apiToken: null, // desktop session token (X-JustSearch-Session), if enforced
  sessionId: null, // Mcp-Session-Id from the server, if it assigns one
  protocolVersion: null, // negotiated during initialize
  initializeParams: null, // cached client initialize params (for re-init)
  reinitCounter: 0,
  getStream: null, // the optional server->client GET /mcp SSE stream
};

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
 * POST one JSON-RPC message to the live /mcp endpoint.
 * Resolves when the HTTP response is fully consumed; JSON-RPC messages found
 * in the response (JSON body or SSE events) are emitted to stdout as they
 * arrive — except when `intercept` is given, which receives them instead.
 * Rejects with err.retryable = true on transport/session-level failures.
 */
function postToServer(message, intercept) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify(message);
    const headers = {
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
      'content-length': Buffer.byteLength(body),
    };
    if (state.sessionId) headers['mcp-session-id'] = state.sessionId;
    if (state.protocolVersion) headers['mcp-protocol-version'] = state.protocolVersion;
    if (state.apiToken) headers['x-justsearch-session'] = state.apiToken;

    const req = http.request(
      {
        host: '127.0.0.1',
        port: state.port,
        path: '/mcp',
        method: 'POST',
        headers,
        timeout: REQUEST_TIMEOUT_MS,
      },
      (res) => {
        const status = res.statusCode || 0;
        const newSession = res.headers['mcp-session-id'];
        if (typeof newSession === 'string' && newSession.length > 0) {
          state.sessionId = newSession;
        }

        if (status === 404 || status === 400) {
          // Streamable HTTP: 404 = session expired/unknown -> re-initialize.
          res.resume();
          const err = new Error(`HTTP ${status} from /mcp (stale session?)`);
          err.retryable = true;
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

        const deliver = intercept || emit;
        const contentType = String(res.headers['content-type'] || '');
        res.setEncoding('utf8');
        if (contentType.includes('text/event-stream')) {
          const feed = sseFeed(deliver);
          res.on('data', (chunk) => feed.push(chunk));
          res.on('end', resolve);
        } else {
          let raw = '';
          res.on('data', (chunk) => (raw += chunk));
          res.on('end', () => {
            if (raw.trim().length > 0) {
              try {
                deliver(JSON.parse(raw));
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
          err.retryable = true;
          reject(err);
        });
      }
    );
    req.on('timeout', () => req.destroy(new Error('request timeout')));
    req.on('error', (e) => {
      const err = new Error(`connection failed: ${e.message}`);
      err.retryable = true; // ECONNREFUSED etc: app restarted / port moved
      reject(err);
    });
    req.write(body);
    req.end();
  });
}

/**
 * Best-effort server->client stream (GET /mcp), opened only after the owner proof
 * (ensureSameEngine). Optional per spec; if the server answers 405 or the stream drops, or the
 * Engine cannot be confirmed, we simply go without it.
 */
function openGetStream() {
  ensureSameEngine().then(openGetStreamNow, () => {});
}

function openGetStreamNow() {
  if (state.getStream) {
    state.getStream.destroy();
    state.getStream = null;
  }
  const headers = { accept: 'text/event-stream' };
  if (state.sessionId) headers['mcp-session-id'] = state.sessionId;
  if (state.protocolVersion) headers['mcp-protocol-version'] = state.protocolVersion;
  const req = http.request(
    { host: '127.0.0.1', port: state.port, path: '/mcp', method: 'GET', headers },
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
  req.end();
  state.getStream = req;
}

/**
 * Recover after a transport failure: re-discover the port (the app may have
 * restarted on a different one), then re-establish an MCP session with the
 * cached initialize params so the retried call lands in a valid session.
 * Throws if JustSearch is unreachable.
 */
async function recoverConnection() {
  state.apiToken = null; // the old token never goes to whatever is found next
  state.engine = null;
  const tried = [];
  const found = await discoverEngine(tried);
  if (found === null) {
    const err = new Error('JustSearch unreachable');
    err.tried = tried;
    throw err;
  }
  const { port } = found;
  state.port = port;
  state.engine = found.engine;
  state.sessionId = null;
  state.apiToken = await fetchSessionToken(port); // token rotates per app start
  if (!state.initializeParams) return; // nothing to re-establish yet

  state.reinitCounter += 1;
  const reinitId = `justsearch-mcpb-reinit-${state.reinitCounter}`;
  let reinitResult = null;
  await postToServer(
    { jsonrpc: '2.0', id: reinitId, method: 'initialize', params: state.initializeParams },
    (msg) => {
      if (msg && msg.id === reinitId && msg.result) reinitResult = msg.result;
    }
  );
  if (!reinitResult) throw new Error('re-initialize got no result');
  if (typeof reinitResult.protocolVersion === 'string') {
    state.protocolVersion = reinitResult.protocolVersion;
  }
  await postToServer({ jsonrpc: '2.0', method: 'notifications/initialized' });
  openGetStream();
  log(`reconnected to http://127.0.0.1:${state.port}/mcp (new session)`);
}

/**
 * Before every request to the Engine (sendToEngine, openGetStream): the Engine selected earlier
 * must still be the one the OS says holds its port, under the same manifest (confirmEngine; no
 * network). Otherwise select again, which re-proves the endpoint and fetches its own token, before
 * anything is sent. Concurrent requests share one check. Only recoverConnection's re-initialize,
 * sent right after its own proof, uses postToServer directly. A JUSTSEARCH_API_PORT pin is the
 * operator's choice and is not re-checked.
 */
let pendingCheck = null;
function ensureSameEngine() {
  if (!state.engine) return Promise.resolve();
  if (!pendingCheck) {
    pendingCheck = (async () => {
      const check = await confirmEngine(state.engine);
      if (check.ok) return;
      log(`the Engine on port ${state.port} changed (${check.status}: ${check.detail}) — re-checking where JustSearch is running...`);
      await recoverConnection();
    })().finally(() => {
      pendingCheck = null;
    });
  }
  return pendingCheck;
}

/** POST to the Engine after the owner proof; throws (err.tried) if the app is gone. */
async function sendToEngine(message, intercept) {
  await ensureSameEngine();
  return postToServer(message, intercept);
}

/** Handle one client->server message, with one transparent reconnect+retry. */
async function handleClientMessage(message) {
  const isInitialize = message && message.method === 'initialize';
  if (isInitialize && message.params) {
    state.initializeParams = message.params;
    state.sessionId = null; // a fresh client handshake starts a fresh session
  }

  const send = () =>
    isInitialize
      ? sendToEngine(message, (msg) => {
          if (msg && msg.id === message.id && msg.result) {
            if (typeof msg.result.protocolVersion === 'string') {
              state.protocolVersion = msg.result.protocolVersion;
            }
            // Session header was captured in postToServer; the standalone
            // server->client stream can be opened once the session exists.
            setImmediate(openGetStream);
          }
          emit(msg);
        })
      : sendToEngine(message);

  try {
    await send();
  } catch (firstError) {
    if (!firstError.retryable) throw firstError;
    log(`transport error (${firstError.message}) — re-checking where JustSearch is running...`);
    await recoverConnection(); // throws if the app is gone
    await send(); // replay once against the recovered session
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
  const tried = [];
  const found = await discoverEngine(tried);
  if (found === null) {
    process.stderr.write(`${actionableUnreachableMessage(tried)}\n`);
    process.exit(1);
  }
  const { port } = found;
  state.port = port;
  state.engine = found.engine;
  state.apiToken = await fetchSessionToken(port);
  log(`bridging stdio <-> http://127.0.0.1:${port}/mcp${state.apiToken ? ' (session token attached)' : ''}`);

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
      handleClientMessage(message).catch((err) => {
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
}

main().catch((err) => {
  log(`fatal: ${err.stack || err.message}`);
  process.exit(1);
});
