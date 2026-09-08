#!/usr/bin/env node
/**
 * The fake engine (design 7.1 "Conformance", lane F stage B item B7).
 *
 * A Node process a supervisor can spawn INSTEAD of the real Engine, which exits with a code, hangs,
 * or answers health on demand. It exists because the three things a supervisor is for — a crash, a
 * wedge, and a boot that cannot succeed — are all things the real Engine only does by accident, and
 * a contract you can only observe by accident is not one either implementation can be held to.
 *
 * WHAT IT IS FAITHFUL ABOUT, and why each one is load-bearing:
 *   - it publishes a real `<dataDir>/runtime/manifest.json`, so the dev-runner's discovery loop and
 *     the Tauri shell's `watch_manifest` work UNMODIFIED. A harness that needed a special discovery
 *     path would be testing the harness;
 *   - it exits with the codes `io.justsearch.app.engine.EngineExit` declares, taken from the
 *     register (which `contract.mjs` re-checks against the Java source before any case runs), so a
 *     classification case cannot pass against an invented integer;
 *   - it reads `<dataDir>/runtime/shutdown-request.v1.json` and deletes it on consumption, exactly
 *     as `ShutdownRequestWatcher` does — including in `hang-soft`, whose whole point is that the
 *     watcher thread is still runnable while nothing else is.
 *
 * WHAT IT IS NOT: an Engine. It has no index, no readiness vector and no ordered shutdown; `quit`
 * closes a socket. Every case here is about the SUPERVISOR's behaviour, and the Engine's own
 * shutdown sequence is item B4's, tested against the real thing.
 *
 * Modes (design's list, plus the per-incarnation plan below):
 *   clean-exit  — answer health, then exit `exitCode` (default 0) after `exitAfterMs`
 *   crash       — answer health, then exit 1 (EngineExit.FATAL_OR_UNCAUGHT)
 *   oom         — answer health, then exit 3 (EngineExit.OUT_OF_MEMORY — the ExitOnOutOfMemoryError code)
 *   boot-fail   — exit `exitCode` (default 2) WITHOUT ever publishing a manifest
 *   hang-soft   — answer health, then stop answering; still reads the request file
 *   hang-hard   — answer health, then stop answering AND stop reading the request file
 *   honour      — answer health forever; exit 0 when a request arrives
 *   slow-start  — delay publishing the manifest by `slowStartMs` (exceeds the start deadline)
 *
 * Usage:
 *   node fake-engine.mjs --mode crash --exit-after-ms 500
 *   JUSTSEARCH_FAKE_ENGINE_PLAN=<plan.json> node fake-engine.mjs      (per-incarnation behaviour)
 *
 * The plan file is how a case drives an Engine that behaves DIFFERENTLY on each incarnation without
 * the supervisor being restarted between them: `{"incarnations":[{...},{...}]}`, consumed by an
 * index this process advances in a sibling state file, with the last entry sticky.
 */

import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';

const MODES = new Set([
  'clean-exit',
  'crash',
  'oom',
  'boot-fail',
  'hang-soft',
  'hang-hard',
  'honour',
  'slow-start',
]);

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith('--')) continue;
    const key = arg.slice(2).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    const next = argv[i + 1];
    if (next === undefined || next.startsWith('--')) {
      out[key] = true;
    } else {
      out[key] = next;
      i++;
    }
  }
  return out;
}

function num(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

/**
 * Pick this incarnation's behaviour. A plan file wins over flags, because the plan is how a
 * multi-incarnation case (crash, restart, crash again) expresses itself to a supervisor that only
 * ever spawns one command line.
 */
function resolveBehaviour(args, env) {
  const planPath = env.JUSTSEARCH_FAKE_ENGINE_PLAN;
  const base = {
    mode: args.mode ?? env.JUSTSEARCH_FAKE_ENGINE_MODE ?? 'honour',
    exitCode: num(args.exitCode ?? env.JUSTSEARCH_FAKE_ENGINE_EXIT_CODE, null),
    exitAfterMs: num(args.exitAfterMs ?? env.JUSTSEARCH_FAKE_ENGINE_EXIT_AFTER_MS, 500),
    hangAfterMs: num(args.hangAfterMs ?? env.JUSTSEARCH_FAKE_ENGINE_HANG_AFTER_MS, 500),
    slowStartMs: num(args.slowStartMs ?? env.JUSTSEARCH_FAKE_ENGINE_SLOW_START_MS, 60_000),
    // How often the fake watcher reads the request file. A FIXTURE cadence, not a subject one: the
    // Engine deletes the file the instant it consumes it, so a supervisor and an Engine polling at
    // the same rate race over a window neither controls. Widening the Engine's side widens the
    // window the SUPERVISOR has to observe the request in — it does not make the supervisor's
    // observation easier to fake, because the supervisor still has to read the file itself.
    requestPollMs: num(args.requestPollMs ?? env.JUSTSEARCH_FAKE_ENGINE_REQUEST_POLL_MS, 100),
  };
  let incarnation = 1;
  if (planPath && fs.existsSync(planPath)) {
    const plan = JSON.parse(fs.readFileSync(planPath, 'utf8'));
    const statePath = `${planPath}.state.json`;
    let index = 0;
    try {
      index = JSON.parse(fs.readFileSync(statePath, 'utf8')).next ?? 0;
    } catch {
      index = 0;
    }
    incarnation = index + 1;
    fs.writeFileSync(statePath, JSON.stringify({ next: index + 1 }), 'utf8');
    const list = plan.incarnations ?? [];
    if (list.length > 0) {
      Object.assign(base, list[Math.min(index, list.length - 1)]);
    }
  }
  if (!MODES.has(base.mode)) {
    process.stderr.write(`fake-engine: unknown mode \`${base.mode}\`\n`);
    process.exit(64);
  }
  if (base.exitCode === null) {
    base.exitCode = base.mode === 'crash' ? 1 : base.mode === 'oom' ? 3 : base.mode === 'boot-fail' ? 2 : 0;
  }
  return { ...base, incarnation };
}

const args = parseArgs(process.argv.slice(2));
const env = process.env;
const behaviour = resolveBehaviour(args, env);

const dataDir = path.resolve(
  args.dataDir ?? env.JUSTSEARCH_DATA_DIR ?? env.JUSTSEARCH_HOME ?? path.join(os.tmpdir(), 'fake-engine-data'),
);
const runtimeDir = path.join(dataDir, 'runtime');
const manifestPath = path.join(runtimeDir, 'manifest.json');
const requestPath = path.join(runtimeDir, 'shutdown-request.v1.json');
const requestedPort = num(args.apiPort ?? env.JUSTSEARCH_API_PORT, 0);
const instanceId = crypto.randomUUID();
const sessionToken = crypto.randomUUID();

function log(message) {
  // Timestamped because every question anyone asks of this process is a timing question: did it
  // answer before it hung, did it read the request before the deadline, did it exit before the kill.
  const line = `[fake-engine] ${new Date().toISOString()} ${message}`;
  process.stdout.write(`${line}\n`);
  // ... and APPENDED to <dataDir>/logs/engine.log, because that is what the real Engine's Logback
  // does and it is what item B1's per-run log preservation preserves. Without a real appending log
  // there would be nothing for the per-incarnation snapshot to be a snapshot OF, and the assertion
  // that incarnation N's evidence survives incarnation N+1 would be vacuous.
  try {
    fs.mkdirSync(path.join(dataDir, 'logs'), { recursive: true });
    fs.appendFileSync(path.join(dataDir, 'logs', 'engine.log'), `${line}\n`, 'utf8');
  } catch {
    /* the log is evidence, not a dependency */
  }
}

log(
  `incarnation=${behaviour.incarnation} mode=${behaviour.mode} exitCode=${behaviour.exitCode} `
    + `pid=${process.pid} dataDir=${dataDir}`,
);

// `boot-fail` never binds and never publishes: this is the non-transient class, and the thing that
// makes it non-transient is that the SAME input fails the same way. Publishing a manifest first
// would make it a crash-after-boot instead, which is a different row of the table.
if (behaviour.mode === 'boot-fail') {
  process.stderr.write(`[fake-engine] boot failed (simulated), exiting ${behaviour.exitCode}\n`);
  setTimeout(() => process.exit(behaviour.exitCode), Math.min(behaviour.exitAfterMs, 250));
} else {
  start();
}

function writeJsonAtomic(target, value) {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const tmp = `${target}.tmp`;
  fs.writeFileSync(tmp, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
  fs.renameSync(tmp, target);
}

function publishManifest(port) {
  writeJsonAtomic(manifestPath, {
    schemaVersion: 1,
    instanceId,
    pid: process.pid,
    lifecycle: 'READY',
    head: { apiPort: port, sessionToken },
    fakeEngine: { mode: behaviour.mode, incarnation: behaviour.incarnation },
  });
  log(`manifest published instanceId=${instanceId} port=${port}`);
}

function orderlyExit(code, why) {
  log(`exiting ${code} (${why})`);
  try {
    // The real Engine's ordered shutdown deletes the manifest; a supervisor that watches the
    // manifest must not see a dead instance's residue as a live one.
    fs.rmSync(manifestPath, { force: true });
  } catch {
    /* best effort */
  }
  process.exit(code);
}

function start() {
  let answering = true;
  let readingRequests = true;

  const server = http.createServer((req, res) => {
    if (!answering) {
      // A hang is NOT a refusal: the socket is accepted and then nothing happens, which is what a
      // wedged JVM looks like to a poller. Answering 503 here would be a health check that works.
      return;
    }
    const url = (req.url ?? '/').split('?')[0];
    if (req.method === 'POST' && url === '/api/lifecycle/shutdown') {
      res.writeHead(202, { 'content-type': 'application/json' });
      res.end('{"accepted":true}');
      setTimeout(() => orderlyExit(0, 'POST /api/lifecycle/shutdown'), 50);
      return;
    }
    if (url === '/api/health') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end('{"status":"UP"}');
      return;
    }
    if (url === '/api/status') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ lifecycle: 'READY', instanceId, ready: true }));
      return;
    }
    res.writeHead(404, { 'content-type': 'application/json' });
    res.end('{"error":"not found"}');
  });

  server.listen(requestedPort, '127.0.0.1', () => {
    const port = server.address().port;
    // Kept for human parity with the real Engine's stdout. Nothing parses it — discovery is the
    // manifest (tempdoc 501 §3.1) — and a harness that reintroduced a stdout parse would be
    // testing a path the product deleted.
    log(`JUSTSEARCH_API_PORT=${port}`);

    const publishDelay = behaviour.mode === 'slow-start' ? behaviour.slowStartMs : 0;
    setTimeout(() => publishManifest(port), publishDelay);

    if (behaviour.mode === 'hang-soft' || behaviour.mode === 'hang-hard') {
      setTimeout(() => {
        answering = false;
        readingRequests = behaviour.mode === 'hang-soft';
        log(`hanging now (mode=${behaviour.mode}, readingRequests=${readingRequests})`);
      }, behaviour.hangAfterMs);
    }

    if (behaviour.mode === 'clean-exit' || behaviour.mode === 'crash' || behaviour.mode === 'oom') {
      setTimeout(() => orderlyExit(behaviour.exitCode, behaviour.mode), behaviour.exitAfterMs);
    }
  });

  server.on('error', (err) => {
    process.stderr.write(`[fake-engine] listen failed: ${err.message}\n`);
    process.exit(behaviour.exitCode || 1);
  });

  // The request-file watcher, on its own timer — the JS shape of item B3's dedicated thread. An
  // unparseable or unknown-reason file is IGNORED, never guessed at, for the reason B2's register
  // row states: guessing a reason from a file caught mid-write could stop the product.
  setInterval(() => {
    if (!readingRequests) return;
    let request;
    try {
      request = JSON.parse(fs.readFileSync(requestPath, 'utf8'));
    } catch {
      return;
    }
    const reason = request?.reason;
    if (!['quit', 'restart', 'upgrade', 'hang'].includes(reason)) {
      log(`ignoring request with unknown reason ${JSON.stringify(reason)}`);
      return;
    }
    try {
      fs.rmSync(requestPath, { force: true });
    } catch {
      /* the supervisor rewrites it if it still wants us gone */
    }
    orderlyExit(0, `shutdown request reason=${reason}`);
  }, behaviour.requestPollMs).unref();
  // The interval is unref'd on purpose: the listening server is what holds the event loop open, so
  // once the server closes the process can exit instead of being pinned by a poll that has nothing
  // left to poll for.
}
