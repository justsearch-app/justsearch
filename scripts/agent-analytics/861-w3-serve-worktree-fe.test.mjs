/**
 * Tempdoc 861 W3 [A3] — `serve-worktree-fe.cjs`'s agent-spawn registration.
 *
 * Three layers:
 *
 *   1. Pure-unit coverage of the injectable helpers (`resolveListenerIdentity`,
 *      `waitForPortListening`, `resolveSessionId`) with fakes — no real process, no real port.
 *
 *   2. F5 — the one-port-two-servers corroboration gap: an EXPLICIT `--port` gives no guarantee
 *      that whatever answers the readiness poll is OUR spawn (a pre-existing stranger on that
 *      port would otherwise be corroborated as ours, while our own `--strictPort` Vite silently
 *      fails to bind). `registerServedVite` refuses to register a listener that PREDATES the
 *      `spawn()` call when the port was explicit, and the record id is keyed by pid so a
 *      clean-exit delete can never remove a stranger's record.
 *
 *   3. THE [A3] ACCEPTANCE TEST: a REAL disposable process this test owns, spawned with the exact
 *      shell-shim shape `serve-worktree-fe.cjs` uses (`spawn(cmd, args, { shell: true })` on
 *      win32, so `child.pid` is a `cmd.exe` intermediate, not the surviving listener) — proving
 *      the record names the port's actual listener, that killing the recorded INTERMEDIATE leaves
 *      the record still resolving to the SURVIVING listener, and that `verifyProcessIdentity`
 *      still MATCHES against it afterward. Never the running dev stack's own Vite (:5173) — this
 *      spawns and tears down its own throwaway listener on a scanned free port.
 *
 * Windows-only (spawn shell-shim shape, PowerShell-based process-table reads, and
 * `process-identity.cjs`/`port-owner.cjs` are both implemented for win32 only) — guarded, not
 * silently skipped-and-reported-green.
 *
 * Run with: `node scripts/agent-analytics/861-w3-serve-worktree-fe.test.mjs`
 */

import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const sw = require('../dev/serve-worktree-fe.cjs');
const { verifyProcessIdentity, readProcessTable } = require('../dev/lib/process-identity.cjs');

let passed = 0;
const failures = [];
async function check(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

/* ── Pure-unit: resolveSessionId ──────────────────────────────────────────────────────────── */

// The label is the calling harness's OWN variable, by the one identity rule (agent-identity.cjs).
const HARNESS_TABLE = (name) => () => ({
  ok: true,
  readAt: Date.now(),
  table: [
    { ProcessId: 500, ParentProcessId: 4, Name: name, CommandLine: name, CreationFileTimeUtc: '134000000000000000' },
    { ProcessId: 501, ParentProcessId: 500, Name: 'node.exe', CommandLine: 'node', CreationFileTimeUtc: '134000000000001000' },
  ],
});

await check('resolveSessionId is the Claude harness label under a Claude harness', () => {
  const id = sw.resolveSessionId({ CLAUDE_CODE_SESSION_ID: 'aaaa-claude', JUSTSEARCH_AGENT_SESSION_ID: 'bbbb-export' }, { readTable: HARNESS_TABLE('claude.exe'), selfPid: 501 });
  assert.equal(id, 'aaaa-claude');
});

await check('resolveSessionId never reads JUSTSEARCH_AGENT_SESSION_ID: no harness means no label', () => {
  const id = sw.resolveSessionId({ JUSTSEARCH_AGENT_SESSION_ID: 'bbbb-export' }, { readTable: HARNESS_TABLE('explorer.exe'), selfPid: 501 });
  assert.equal(id, null);
});

/* ── Pure-unit: waitForPortListening ──────────────────────────────────────────────────────── */

await check('waitForPortListening resolves true as soon as the probe answers', async () => {
  let calls = 0;
  const probe = async () => { calls += 1; return calls >= 2; };
  const ok = await sw.waitForPortListening(1, { intervalMs: 1, probe });
  assert.equal(ok, true);
  assert.ok(calls >= 2);
});

await check('waitForPortListening gives up at the timeout and returns false', async () => {
  const ok = await sw.waitForPortListening(1, { timeoutMs: 20, intervalMs: 5, probe: async () => false });
  assert.equal(ok, false);
});

/* ── The dual-stack readiness probe (O-3) ─────────────────────────────────────────────────────
 *
 * Vite binds `localhost`, which on Windows resolves to `::1` FIRST — so the dev server that is
 * demonstrably serving (curl answers, `netstat` shows `TCP [::1]:5174 LISTENING`) is IPv6-ONLY.
 * A readiness poll that probed just `127.0.0.1` therefore reported "never started accepting
 * connections", skipped registration, and left a Vite no `agent-spawn-sweep.cjs` run could reap.
 * These two use the REAL default probe against REAL one-stack listeners — a fake probe cannot
 * catch this class, because the bug lives in which HOST the poll asks about.
 */

/** Bind a throwaway listener to exactly one loopback stack. Resolves `null` if that stack is absent. */
async function listenOnStack(host) {
  const port = await sw.pickPort(15960);
  const srv = net.createServer((s) => { s.on('error', () => {}); });
  const bound = await new Promise((resolve) => {
    srv.once('error', () => resolve(false));
    srv.listen(port, host, () => resolve(true));
  });
  if (!bound) return null;
  return { port, address: srv.address().address, close: () => new Promise((r) => srv.close(r)) };
}

for (const [host, otherStack] of [['::1', '127.0.0.1'], ['127.0.0.1', '::1']]) {
  await check(`waitForPortListening sees a listener bound ONLY to ${host}`, async () => {
    const srv = await listenOnStack(host);
    if (!srv) {
      // Never silently green: an unavailable stack is reported, not counted as a pass.
      throw new Error(`could not bind a listener on ${host} — this host has no ${host} loopback, so the assertion was not exercised`);
    }
    try {
      assert.equal(srv.address, host, `expected the listener on the ${host} stack, got ${srv.address}`);
      const ok = await sw.waitForPortListening(srv.port, { timeoutMs: 4_000, intervalMs: 100 });
      assert.equal(
        ok, true,
        `a server listening on ${host} must be seen as ready; a probe that asks only ${otherStack} misses it`,
      );
    } finally {
      await srv.close();
    }
  });
}

/* ── Register-or-kill: an unready spawn must never be left running unregistered ───────────── */

await check('registerServedVite kills the child when readiness is never observed', async () => {
  let killedWith = 'not called';
  const child = { pid: 4242 };
  const result = await sw.registerServedVite({
    port: 5196,
    sessionId: 'o3-test',
    child,
    waitForPort: async () => false,
    killChild: (c) => { killedWith = c; return { ok: true }; },
  });
  assert.equal(result, null, 'an unready spawn must not be registered');
  assert.equal(killedWith, child, 'the unregistered child must be killed — no record means no reaper can ever collect it');
});

await check('registerServedVite does NOT kill when readiness IS observed but identity fails', async () => {
  // Something answers on the port, so it may not be ours — refusing to register is right, killing is not.
  let killCalls = 0;
  const result = await sw.registerServedVite({
    port: 5195,
    sessionId: 'o3-test',
    child: { pid: 4243 },
    waitForPort: async () => true,
    resolveIdentity: () => ({ ok: false, reason: 'nothing resolvable' }),
    killChild: () => { killCalls += 1; return { ok: true }; },
  });
  assert.equal(result, null);
  assert.equal(killCalls, 0, 'a listening port may belong to a stranger — never kill on the identity branch');
});

await check('killSpawnTree reports a reason rather than throwing when there is no child', () => {
  const verdict = sw.killSpawnTree(null);
  assert.equal(verdict.ok, false);
  assert.match(verdict.reason, /no child process/);
});

/* ── Pure-unit: resolveListenerIdentity, fully injected ───────────────────────────────────── */

const T = '134320479841300350';

await check('resolveListenerIdentity builds the identity triple from ONE process-table row', () => {
  const identity = sw.resolveListenerIdentity(5191, {
    resolvePid: () => ({ ok: true, pid: 777 }),
    table: () => ({ ok: true, table: [{ ProcessId: 777, CreationFileTimeUtc: T, CommandLine: 'node vite.js --port 5191 --strictPort' }] }),
  });
  assert.deepEqual(identity, { ok: true, pid: 777, creationFileTimeUtc: T, cmdlineFingerprint: '--port 5191' });
});

await check('resolveListenerIdentity refuses when the port owner cannot be resolved', () => {
  const identity = sw.resolveListenerIdentity(5191, {
    resolvePid: () => ({ ok: false, reason: 'nothing listening' }),
    table: () => ({ ok: true, table: [] }),
  });
  assert.equal(identity.ok, false);
  assert.match(identity.reason, /nothing listening/);
});

await check('resolveListenerIdentity refuses when the process table is unavailable', () => {
  const identity = sw.resolveListenerIdentity(5191, {
    resolvePid: () => ({ ok: true, pid: 777 }),
    table: () => ({ ok: false, reason: 'PowerShell unavailable' }),
  });
  assert.equal(identity.ok, false);
  assert.match(identity.reason, /process table/);
});

await check('resolveListenerIdentity refuses when the resolved pid vanished before the table read', () => {
  const identity = sw.resolveListenerIdentity(5191, {
    resolvePid: () => ({ ok: true, pid: 777 }),
    table: () => ({ ok: true, table: [{ ProcessId: 999, CreationFileTimeUtc: T, CommandLine: 'x' }] }),
  });
  assert.equal(identity.ok, false);
  assert.match(identity.reason, /vanished/);
});

await check('resolveListenerIdentity refuses on an unreadable creation time', () => {
  const identity = sw.resolveListenerIdentity(5191, {
    resolvePid: () => ({ ok: true, pid: 777 }),
    table: () => ({ ok: true, table: [{ ProcessId: 777, CreationFileTimeUtc: null, CommandLine: 'node vite.js --port 5191' }] }),
  });
  assert.equal(identity.ok, false);
  assert.match(identity.reason, /creation time/);
});

await check('resolveListenerIdentity refuses when the cmdline does not contain the port fingerprint', () => {
  // The mismatch/impostor case: SOMETHING is listening on the port, on a pid with a valid
  // creation time, but its command line does not name THIS port — refuse rather than record an
  // unverified identity.
  const identity = sw.resolveListenerIdentity(5191, {
    resolvePid: () => ({ ok: true, pid: 777 }),
    table: () => ({ ok: true, table: [{ ProcessId: 777, CreationFileTimeUtc: T, CommandLine: 'node vite.js --port 9999' }] }),
  });
  assert.equal(identity.ok, false);
  assert.match(identity.reason, /does not contain/);
});

/* ── F5: the one-port-two-servers corroboration gap ───────────────────────────────────────── */

/** Inverse of `fileTimeToEpochMs`, for building fixture creation times relative to a known instant. */
function epochMsToFileTime(epochMs) {
  return ((BigInt(Math.round(epochMs)) + 11644473600000n) * 10000n).toString();
}

await check('fileTimeToEpochMs round-trips a known instant', () => {
  const now = Date.now();
  assert.equal(sw.fileTimeToEpochMs(epochMsToFileTime(now)), now);
});

await check('F5: an explicit --port pointed at a PRE-EXISTING listener is refused, never registered', async () => {
  const spawnStartTime = Date.now();
  const staleCreationTime = epochMsToFileTime(spawnStartTime - 60_000); // started a minute BEFORE we spawned
  const result = await sw.registerServedVite({
    port: 5199,
    explicitPort: true,
    spawnStartTime,
    sessionId: 'f5-test',
    waitForPort: async () => true,
    resolveIdentity: () => ({ ok: true, pid: 555, creationFileTimeUtc: staleCreationTime, cmdlineFingerprint: '--port 5199' }),
  });
  assert.equal(result, null, 'a listener that predates this spawn must never be attributed to it');
});

await check('F5: a non-explicit port (from pickPort) is registered even with an "old" creation time — no corroboration needed', async () => {
  const stateDir = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w3-f5-state-'));
  const originalEnv = process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT;
  process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = stateDir;
  try {
    const spawnStartTime = Date.now();
    const staleCreationTime = epochMsToFileTime(spawnStartTime - 60_000);
    const result = await sw.registerServedVite({
      port: 5197,
      explicitPort: false, // the scanned-free-port path: nothing was listening a moment ago by construction
      spawnStartTime,
      sessionId: 'f5-test',
      waitForPort: async () => true,
      resolveIdentity: () => ({ ok: true, pid: 557, creationFileTimeUtc: staleCreationTime, cmdlineFingerprint: '--port 5197' }),
    });
    assert.ok(result, 'the corroboration gate only applies to an EXPLICIT --port');
  } finally {
    if (originalEnv === undefined) delete process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT;
    else process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = originalEnv;
    await fsp.rm(stateDir, { recursive: true, force: true }).catch(() => {});
  }
});

await check('F5: an explicit --port whose listener started AFTER this spawn is registered, keyed by pid', async () => {
  const stateDir = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w3-f5-state-'));
  const originalEnv = process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT;
  process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = stateDir;
  try {
    const spawnStartTime = Date.now();
    const freshCreationTime = epochMsToFileTime(spawnStartTime + 500); // started just AFTER we spawned
    const result = await sw.registerServedVite({
      port: 5198,
      explicitPort: true,
      spawnStartTime,
      sessionId: 'f5-test',
      waitForPort: async () => true,
      resolveIdentity: () => ({ ok: true, pid: 556, creationFileTimeUtc: freshCreationTime, cmdlineFingerprint: '--port 5198' }),
    });
    assert.ok(result, 'a listener that starts after this spawn IS ours and must be registered');
    assert.equal(result.recordId, 'serve-worktree-fe-5198-556');
    const rec = JSON.parse(await fsp.readFile(path.join(result.dir, `${result.recordId}.json`), 'utf8'));
    assert.equal(rec.pid, 556);
  } finally {
    if (originalEnv === undefined) delete process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT;
    else process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = originalEnv;
    await fsp.rm(stateDir, { recursive: true, force: true }).catch(() => {});
  }
});

/* ── [A3] ACCEPTANCE: a REAL disposable process, the shell-shim shape, a real kill ────────── */

if (process.platform !== 'win32') {
  console.log('861-w3-serve-worktree-fe: [A3] acceptance test SKIPPED (win32-only)');
} else {
  await check('[A3] killing the recorded shell intermediate still resolves to the surviving listener', async () => {
    const workDir = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w3-a3-'));
    const stateDir = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w3-a3-state-'));
    const listenerScript = path.join(workDir, 'listener.cjs');
    const port = await sw.pickPort(15980); // well clear of any real dev-stack port

    await fsp.writeFile(
      listenerScript,
      "const net = require('net');\n" +
      `const srv = net.createServer((s) => { s.on('error', () => {}); });\n` +
      `srv.listen(${port}, '127.0.0.1');\n` +
      'process.stdin.resume();\n', // keep the event loop alive without a timer to clear
      'utf8',
    );

    // The EXACT shape [A3] describes: `spawn(cmd, args, { shell: true })` on win32 means Node
    // launches `cmd.exe /d /s /c "<cmd> <args>"` — `child.pid` is that cmd.exe shim, and the real
    // listener is a grandchild below it.
    const child = spawn('node', [listenerScript, '--port', String(port), '--strictPort'], {
      shell: true,
      stdio: 'ignore',
    });
    const shimPid = child.pid;

    let grandchildPid;
    let originalEnv;
    try {
      const ready = await sw.waitForPortListening(port, { timeoutMs: 15_000, intervalMs: 100 });
      assert.equal(ready, true, `listener never started accepting connections on port ${port}`);

      originalEnv = process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT;
      process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = stateDir;

      const registered = await sw.registerServedVite({ port, sessionId: '861-w3-a3-test' });
      assert.ok(registered, 'registration must succeed against a real, verifiable listener');

      const recordPath = path.join(registered.dir, `${registered.recordId}.json`);
      const record = JSON.parse(await fsp.readFile(recordPath, 'utf8'));
      grandchildPid = record.pid;

      // The whole point of [A3]: the recorded pid must NOT be the shell shim.
      assert.notEqual(record.pid, shimPid, 'the record must name the surviving listener, not the cmd.exe shim');
      assert.equal(record.cmdlineFingerprint, `--port ${port}`);
      assert.equal(record.producer, 'serve-worktree-fe');

      // Kill ONLY the recorded intermediate (the shim) — simulates a `TaskStop`/`taskkill /PID`
      // (no `/T`) that targets the wrong process in the tree, the exact §2-bis shape.
      try { process.kill(shimPid); } catch { /* may have already self-exited after spawning cmd.exe's child */ }

      // Give Windows a moment to actually tear down the terminated shim.
      await new Promise((resolve) => setTimeout(resolve, 500));

      // The surviving listener must still be reachable — proving cmd.exe's death did not cascade.
      const stillUp = await new Promise((resolve) => {
        const sock = net.connect({ port, host: '127.0.0.1' });
        sock.setTimeout(2000);
        sock.once('connect', () => { sock.destroy(); resolve(true); });
        sock.once('timeout', () => { sock.destroy(); resolve(false); });
        sock.once('error', () => resolve(false));
      });
      assert.equal(stillUp, true, 'the surviving listener must still answer after the shim is killed');

      // And identity verification must still MATCH against the record's pid — a FRESH table read,
      // not the one taken at registration time.
      const table = readProcessTable();
      assert.equal(table.ok, true, `expected a real process-table read to succeed: ${table.reason}`);
      // A generous freshness bound for THIS test only — a loaded dev box's PowerShell enumeration
      // can be slow; the 2000ms production default is about staleness between snapshot and
      // decision, not about how long the enumeration itself took (`readAt` is stamped after).
      const verdict = verifyProcessIdentity({ record, table, maxTableAgeMs: 10_000 });
      assert.equal(verdict.verdict, 'match', `expected MATCH, got ${verdict.verdict}: ${verdict.reason}`);
    } finally {
      if (originalEnv === undefined) delete process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT;
      else process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = originalEnv;
      // Clean up every process THIS TEST spawned — never a discovered/foreign process.
      if (grandchildPid) { try { process.kill(grandchildPid); } catch { /* already gone */ } }
      try { process.kill(shimPid); } catch { /* already gone */ }
      await fsp.rm(workDir, { recursive: true, force: true }).catch(() => {});
      await fsp.rm(stateDir, { recursive: true, force: true }).catch(() => {});
    }
  });
}

if (failures.length > 0) {
  console.error(`861-w3-serve-worktree-fe: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`861-w3-serve-worktree-fe: all ${passed} checks passed`);
