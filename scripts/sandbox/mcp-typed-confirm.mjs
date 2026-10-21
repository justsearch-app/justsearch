#!/usr/bin/env node
/**
 * Drives the shipped MCPB stdio bridge to exercise the TYPED_CONFIRM
 * mutating-tool procedure for `cohort:mcp` (tempdoc 728-followup, D2).
 *
 * The MCP Inspector CLI's `--tool-arg` string-coerces every value and cannot
 * express `justsearch_ingest`'s `paths: string[]` argument -- the procedure
 * `governance/sandbox-coverage.v1.json` used to mandate is unfollowable as
 * written (verified in a real round: round3-tools/mcp-call.js was a
 * hand-rolled workaround for exactly this).
 *
 * Instead of promoting a second, divergent HTTP client, this script spawns
 * the REAL shipped MCPB stdio bridge (`index.js`, a verbatim copy of
 * packaging/mcpb/server/index.js, staged next to this script by
 * sandbox-launch.py) as a child process and speaks newline-delimited
 * JSON-RPC to its stdin/stdout -- exactly how a real MCP host (e.g. Claude
 * Desktop) drives it. That means this round validates the actual artifact
 * JustSearch ships in the MCPB package, not a parallel bespoke client.
 *
 * Sequence driven: initialize -> notifications/initialized ->
 * tools/call justsearch_ingest(paths: [<target>]).
 *
 * STATUS: this driver replaces a PowerShell predecessor that HUNG waiting
 * on the tools/call response, both before and after a server-side fix.
 * Live isolation on 2026-07-15
 * against a running dev stack established, independently, that:
 *   1. The server is fixed and conformant -- `notifications/initialized`
 *      now returns 202 Accepted with an empty body (JSON-RPC 2.0 SS4.1
 *      forbids replying to a Notification); an unknown *request* still
 *      returns -32601. Verified by curl.
 *   2. The shipped MCPB bridge (packaging/mcpb/server/index.js), driven over
 *      stdio from a Node driver using exactly this script's message
 *      sequence, WORKS end-to-end: `initialize` OK, then `tools/call
 *      justsearch_ingest` with a real array `paths` returns
 *      isError:true plus "requires your approval (gate: TYPED_CONFIRM)".
 *      It also handles the 202-no-body notification response correctly.
 *   3. The PowerShell driver hung -- it never received the `tools/call`
 *      response, on either side of the server fix. The server and bridge
 *      are exonerated; the PowerShell driver itself was the defect (most
 *      likely synchronous stdout ReadLine / not draining stderr / pipe
 *      handling).
 *   4. THIS script, exactly as it stands, was then run live against that
 *      dev stack (2026-07-15) and PASSED: exit 0, with the tools/call
 *      response matched by id and identified as the TYPED_CONFIRM gate.
 *      So the whole chain -- driver -> shipped bridge -> server -> gate --
 *      is verified end-to-end, not merely by construction. What a round
 *      still adds beyond this: the post-approval half (resolve the pending
 *      via POST /api/authorizations/approve, then confirm the ingest
 *      actually proceeds) on a real clean install.
 * The PowerShell driver has been removed (it does not work and would
 * invite a future round to run it and hang). This Node driver is the sole
 * replacement -- Node is already a sandbox requirement (the MCP Inspector
 * CLI needs npx), so a Node driver spawning the Node bridge is the natural
 * shape, not a new dependency.
 *
 * Usage:
 *   node mcp-typed-confirm.mjs --target <absolutePath> [--port <n>]
 *       [--bridge <path>] [--timeout <seconds>] [--out <file>]
 *
 * Exit code is 0 only if the tools/call response for id=2 was received
 * before the timeout. A PASS/FAIL line names whether that response was the
 * TYPED_CONFIRM gate (isError:true + "requires your approval") or a silent
 * execution -- a silent ingest is the actual bug this check exists to catch.
 *
 * On a PASS, the script ALSO resolves and prints the gate's pendingId as a
 * greppable `PENDING_ID=<id>` line (plus ready-to-use peek/approve URLs).
 * The gate response text itself never carries this id (verified against
 * McpToolSurface.handleConfirmationRequired's message text) -- this script
 * gets it the same way any other out-of-band subscriber would: GET
 * /api/advisory/authorization-pending/stream, whose snapshot-on-subscribe
 * frame replays the pending's classExtras.pendingId even when the driver
 * connects to it after the gate already fired. Best-effort: a failure to
 * resolve the pendingId prints a WARN but never flips the PASS/FAIL verdict,
 * which is already decided by the tools/call response above it.
 */

import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';
import { createRequire } from 'node:module';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));

// ---------------------------------------------------------------------------
// pendingId resolution (post-TYPED_CONFIRM-gate)
//
// The tools/call gate response text does NOT carry the pendingId (verified:
// McpToolSurface.handleConfirmationRequired's message says only "A request is
// now showing in the JustSearch app" -- zero hits for pendingId in that file).
// A prior round hand-scraped SSE with a regex to find it, which this replaces
// with the same discovery path used generically: GET the per-class advisory
// SSE stream (GET /api/advisory/authorization-pending/stream) and read the
// `authorization.pending` advisory's classExtras.pendingId
// (AuthorizationPendingAdvisoryStreamController / AdvisoryStreamController /
// PendingAuthorizationAdvisoryProjector). Connecting AFTER the gate already
// fired still works: the stream's snapshot-on-subscribe frame replays
// AdvisoryLog.recent(), so there is no race to win against the broadcast.
//
// Use the staged bridge's manifest validation and connection proof for this protected stream.
// Explicit port overrides use only JUSTSEARCH_SESSION_TOKEN, just like the bridge.
// ---------------------------------------------------------------------------

// The advisory record's classExtras.operationId is the WIRE operation id, not
// the MCP tool name -- these differ. McpToolSurface.callTool dispatches
// `justsearch_ingest` via `callOperation("core.ingest-files", ...)`
// (modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java), and
// PendingAuthorizationAdvisoryProjector.project() stamps classExtras with
// `event.operationId()` -- the operation ref's id, i.e. "core.ingest-files" --
// never the tool name that triggered it. Passing the tool name here as
// wantOperationId used to make findPendingId's operationId-match condition
// never succeed, so it fell through to the recursive null-return path and
// every PASS run printed "WARN: could not resolve pendingId" instead of
// resolving it. Kept as a real filter (not dropped) because AdvisoryLog.recent()
// replays the whole recent snapshot, which can contain more than one pending
// advisory in the same session -- matching the correct id, not skipping the
// check, is what keeps this resolving the RIGHT pending rather than the most
// recent unrelated one.
export const INGEST_OPERATION_ID = 'core.ingest-files';

async function discoverApiEngine(explicitPort, discovery) {
  const override = explicitPort ?? process.env.JUSTSEARCH_API_PORT;
  if (override) {
    const token = process.env.JUSTSEARCH_SESSION_TOKEN?.trim();
    if (!token) throw new Error('An explicit port requires JUSTSEARCH_SESSION_TOKEN');
    const port = Number(override);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid API port');
    return { port, token, engine: null };
  }
  const { selected } = await discovery.selectEngine(discovery.standardCandidates());
  return selected ? { port: selected.port, token: selected.token, engine: selected } : null;
}

/**
 * Recursively searches a parsed SSE frame payload for classExtras.pendingId
 * (or a bare pendingId key at any depth, for robustness against nesting
 * differences between the LIFECYCLE snapshot frame -- payload.advisories: []
 * -- and an UPDATE frame -- payload IS a single AdvisoryRecord). When
 * wantOperationId is given, prefers a match whose sibling operationId agrees;
 * accepts the id regardless once no operationId is present to check.
 */
export function findPendingId(node, wantOperationId) {
  if (node === null || typeof node !== 'object') return null;
  if (Array.isArray(node)) {
    for (const item of node) {
      const hit = findPendingId(item, wantOperationId);
      if (hit) return hit;
    }
    return null;
  }
  const extras = node.classExtras && typeof node.classExtras === 'object' ? node.classExtras : node;
  if (typeof extras.pendingId === 'string' && extras.pendingId) {
    if (!wantOperationId || !extras.operationId || extras.operationId === wantOperationId) {
      return extras.pendingId;
    }
  }
  for (const key of Object.keys(node)) {
    const hit = findPendingId(node[key], wantOperationId);
    if (hit) return hit;
  }
  return null;
}

/**
 * Opens GET /api/advisory/authorization-pending/stream and resolves the first
 * pendingId found (matching wantOperationId when present), or null on
 * timeout/error. Best-effort: a failure here must never fail the driver's
 * primary PASS/FAIL verdict, which is already decided by the tools/call
 * response itself.
 */
function resolvePendingId(target, wantOperationId, timeoutSeconds, discovery) {
  return new Promise((resolve) => {
    let settled = false;
    let req;
    const finish = (id) => {
      if (settled) return;
      settled = true;
      try {
        req.destroy();
      } catch {
        /* best effort */
      }
      resolve(id);
    };
    req = discovery.engineRequest(
      target,
      {
        path: '/api/advisory/authorization-pending/stream',
        headers: { Accept: 'text/event-stream', ...(target.token ? { 'X-JustSearch-Session': target.token } : {}) },
        timeoutMs: timeoutSeconds * 1000,
      },
      (res) => {
        let buf = '';
        res.setEncoding('utf8');
        res.on('data', (chunk) => {
          buf += chunk;
          let idx;
          // eslint-disable-next-line no-cond-assign
          while ((idx = buf.indexOf('\n\n')) >= 0) {
            const rawEvent = buf.slice(0, idx);
            buf = buf.slice(idx + 2);
            const dataText = rawEvent
              .split('\n')
              .filter((l) => l.startsWith('data:'))
              .map((l) => l.slice(5).trim())
              .join('\n');
            if (!dataText) continue;
            let parsed;
            try {
              parsed = JSON.parse(dataText);
            } catch {
              continue;
            }
            const found = findPendingId(parsed, wantOperationId);
            if (found) {
              finish(found);
              return;
            }
          }
        });
        res.on('end', () => finish(null));
      },
    );
    req.on('timeout', () => finish(null));
    req.on('error', () => finish(null));
    req.end();
    setTimeout(() => finish(null), timeoutSeconds * 1000 + 500);
  });
}

function parseArgs(argv) {
  const args = {
    target: null,
    port: null,
    bridge: path.join(SCRIPT_DIR, 'index.js'),
    timeout: 45,
    out: null,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    switch (a) {
      case '--target':
        args.target = argv[++i];
        break;
      case '--port':
        args.port = argv[++i];
        break;
      case '--bridge':
        args.bridge = argv[++i];
        break;
      case '--timeout':
        args.timeout = Number.parseInt(argv[++i], 10);
        break;
      case '--out':
        args.out = argv[++i];
        break;
      default:
        process.stderr.write(`[mcp-typed-confirm] unknown argument: ${a}\n`);
        process.exit(2);
    }
  }
  if (!args.target) {
    process.stderr.write(
      '[mcp-typed-confirm] --target <absolutePath> is required\n' +
        'Usage: node mcp-typed-confirm.mjs --target <absolutePath> [--port <n>] ' +
        '[--bridge <path>] [--timeout <seconds>] [--out <file>]\n',
    );
    process.exit(2);
  }
  if (!Number.isInteger(args.timeout) || args.timeout <= 0) {
    args.timeout = 45;
  }
  return args;
}

function log(msg) {
  process.stderr.write(`[mcp-typed-confirm] ${msg}\n`);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));

  if (!fs.existsSync(args.bridge)) {
    log(`Bridge not found at ${args.bridge} -- was mcp-client/ staged correctly by sandbox-launch.py?`);
    process.exit(2);
  }

  const frames = []; // { direction: '>>>' | '<<<', text: string }
  const record = (direction, text) => {
    frames.push({ direction, text });
    log(`${direction} ${text}`);
  };

  const childEnv = { ...process.env };
  if (args.port) {
    childEnv.JUSTSEARCH_API_PORT = String(args.port);
  }

  log(`Launching bridge: node "${args.bridge}"`);
  const child = spawn(process.execPath, [args.bridge], {
    cwd: path.dirname(args.bridge),
    env: childEnv,
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  let stdoutBuf = '';
  const pending = new Map(); // id -> resolve(msg)

  child.stdout.on('data', (d) => {
    stdoutBuf += d.toString('utf8');
    let idx;
    // eslint-disable-next-line no-cond-assign
    while ((idx = stdoutBuf.indexOf('\n')) >= 0) {
      const line = stdoutBuf.slice(0, idx).trim();
      stdoutBuf = stdoutBuf.slice(idx + 1);
      if (!line) continue;
      record('<<<', line);
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        log(`[WARN] non-JSON frame on bridge stdout, discarding: ${line}`);
        continue;
      }
      if (msg && Object.prototype.hasOwnProperty.call(msg, 'id') && msg.id !== null && pending.has(msg.id)) {
        const resolve = pending.get(msg.id);
        pending.delete(msg.id);
        resolve(msg);
      } else {
        log(`[WARN] discarding unsolicited/unmatched frame (id=${msg && msg.id})`);
      }
    }
  });

  // MUST drain stderr -- an undrained pipe is a real deadlock source once
  // the child writes enough bytes to fill the OS pipe buffer.
  child.stderr.on('data', (d) => {
    for (const line of d.toString('utf8').split('\n')) {
      if (line.trim()) log(`[bridge stderr] ${line}`);
    }
  });

  let childExited = false;
  let childExitInfo = null;
  child.on('exit', (code, signal) => {
    childExited = true;
    childExitInfo = { code, signal };
  });

  const send = (obj) => {
    const json = JSON.stringify(obj);
    record('>>>', json);
    child.stdin.write(`${json}\n`);
  };

  function waitForId(id, seconds) {
    return new Promise((resolve, reject) => {
      const deadline = setTimeout(() => {
        pending.delete(id);
        const dump =
          frames.length > 0 ? frames.map((f) => `    ${f.direction} ${f.text}`).join('\n') : '    (no frames received)';
        reject(new Error(`Timed out after ${seconds}s waiting for response id=${id}. Frames seen while waiting:\n${dump}`));
      }, seconds * 1000);
      pending.set(id, (msg) => {
        clearTimeout(deadline);
        resolve(msg);
      });
    });
  }

  let exitCode = 1;
  try {
    send({
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: {
        protocolVersion: '2025-06-18',
        capabilities: {},
        clientInfo: { name: 'sandbox-typed-confirm-client', version: '1.0.0' },
      },
    });
    const initResp = await waitForId(1, args.timeout);
    if (!initResp.result) {
      throw new Error(`initialize did not return a result: ${JSON.stringify(initResp)}`);
    }
    log('initialize OK');

    send({ jsonrpc: '2.0', method: 'notifications/initialized' });

    send({
      jsonrpc: '2.0',
      id: 2,
      method: 'tools/call',
      params: {
        name: 'justsearch_ingest',
        arguments: { paths: [args.target] },
      },
    });
    const callResp = await waitForId(2, args.timeout);

    process.stdout.write('\n=== tools/call justsearch_ingest result ===\n');
    process.stdout.write(`${JSON.stringify(callResp, null, 2)}\n`);

    const result = callResp && callResp.result;
    const text = result && Array.isArray(result.content) ? result.content.map((c) => c.text || '').join(' ') : '';
    const isGate =
      result &&
      result.isError === true &&
      /requires your approval/i.test(text) &&
      /TYPED_CONFIRM/i.test(text);

    if (isGate) {
      process.stdout.write('\nPASS: tools/call justsearch_ingest response was received AND is the TYPED_CONFIRM gate (isError:true, "requires your approval").\n');
      exitCode = 0;

      // Resolve the pendingId so the round doesn't have to hand-scrape SSE:
      // the gate response text itself never carries it (verified against
      // McpToolSurface.handleConfirmationRequired).
      const discovery = createRequire(import.meta.url)(path.join(path.dirname(path.resolve(args.bridge)), 'engine-discovery.cjs'));
      const target = await discoverApiEngine(args.port, discovery);
      if (target) {
        const resolvedPort = target.port;
        log(
          `Resolving pendingId via GET http://127.0.0.1:${resolvedPort}/api/advisory/authorization-pending/stream ...`,
        );
        const pendingId = await resolvePendingId(target, INGEST_OPERATION_ID, Math.min(args.timeout, 15), discovery);
        if (pendingId) {
          process.stdout.write(`\nPENDING_ID=${pendingId}\n`);
          process.stdout.write(
            `Peek (no approval): GET http://127.0.0.1:${resolvedPort}/api/authorizations/pending/${pendingId}\n`,
          );
          process.stdout.write(
            `Approve + execute: POST http://127.0.0.1:${resolvedPort}/api/authorizations/approve  body: {"pendingId":"${pendingId}","execute":true}\n`,
          );
        } else {
          process.stdout.write(
            '\nWARN: could not resolve pendingId from GET /api/advisory/authorization-pending/stream within the timeout. ' +
              'Check the JustSearch app UI for the pending approval, or query that endpoint directly.\n',
          );
        }
      } else {
        process.stdout.write(
          '\nWARN: could not discover a validated Engine to resolve pendingId. ' +
            'Check the JustSearch app UI for the pending approval instead.\n',
        );
      }
    } else if (result && result.isError === true) {
      process.stdout.write('\nFAIL: tools/call justsearch_ingest returned an error, but not the expected TYPED_CONFIRM gate shape. See the raw result above.\n');
      exitCode = 1;
    } else if (callResp && callResp.error) {
      process.stdout.write('\nFAIL: tools/call justsearch_ingest returned a JSON-RPC error, not a TYPED_CONFIRM gate. See the raw result above.\n');
      exitCode = 1;
    } else {
      process.stdout.write('\nFAIL: tools/call justsearch_ingest did NOT return isError:true -- this looks like a SILENT/IMMEDIATE ingest, not a TYPED_CONFIRM gate. This is the actual bug this check exists to catch.\n');
      exitCode = 1;
    }
  } catch (err) {
    log(`mcp-typed-confirm.mjs failed: ${err.message}`);
    process.stdout.write(`\nFAIL: ${err.message}\n`);
    exitCode = 1;
  } finally {
    if (args.out) {
      try {
        fs.writeFileSync(args.out, frames.map((f) => `${f.direction} ${f.text}`).join('\n') + '\n', 'utf8');
        log(`Wrote ${frames.length} frames to ${args.out}`);
      } catch (e) {
        log(`[WARN] failed to write --out file: ${e.message}`);
      }
    }
    if (!childExited) {
      try {
        child.stdin.end();
      } catch {
        /* best effort */
      }
      await new Promise((resolve) => setTimeout(resolve, 200));
      if (!childExited) {
        try {
          child.kill();
        } catch {
          /* best effort */
        }
      }
    }
    if (childExitInfo) {
      log(`bridge exited (code ${childExitInfo.code}, signal ${childExitInfo.signal})`);
    }
  }

  process.exit(exitCode);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
