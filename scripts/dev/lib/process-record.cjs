#!/usr/bin/env node
/**
 * Tempdoc 861 W1 — the shared process-record grammar and reader.
 *
 * Extracted verbatim (no behaviour change) from `scripts/dev/justsearch-dev-mcp/server.mjs`'s
 * `probeForeignRuns` / `readForeignRegister` / `resolveForeignRegisterDir` cluster (tempdoc 844
 * D3/B3/S3). `foreign/` (backends started outside the dev-runner) is re-pointed at this module as
 * Phase 1's first — and, per the design, only — consumer; a sibling scope (`agent-spawns/`, tempdoc
 * 861 Phase 2) reuses the SAME generic primitives below rather than re-deriving them, which is the
 * whole point of the extraction (861 §6.1: one grammar, two scopes).
 *
 * Layered deliberately:
 *   - GENERIC (top section): directory resolution, the bounded symlink-refusing reader, pid
 *     liveness, an HTTP status probe, and the live|unreachable|stale state-derivation. None of
 *     these know what a "foreign" record looks like. Record *validation* is intentionally NOT
 *     built into the generic reader — it is injected per call (`validateRecord`), because
 *     validation is per-scope, not a property of the shared envelope (861 [A7]/[A8]): a record
 *     shaped for one scope must not be silently accepted by another scope's reader.
 *   - FOREIGN SCOPE (bottom section): `foreign/`'s own schema version (STAYS 1 — 861 [A8]: new
 *     fields are additive-optional here; a sibling scope carries its own, independently-versioned
 *     constant, never a shared version number), its record shape validator, and `probeForeignRuns`,
 *     which composes the generic primitives into the backend/inference-specific probe `foreign/`
 *     needs. This section is a CONSUMER of the generic layer, not part of its contract.
 *
 * `resolveRegisterDir` honours `JUSTSEARCH_DEV_RUNNER_STATE_ROOT` exactly as the dev-runner and
 * `run_register.py` do (861 [A9]), so an isolated dev-runner (integration tests, throwaway stacks)
 * gets an isolated register — and this is the ONE resolution helper, exported generically, so a
 * sibling scope resolves its own directory the same way instead of re-deriving the override logic.
 */
'use strict';

const fsp = require('node:fs/promises');
const path = require('node:path');
const http = require('node:http');

/* ── Generic: directory resolution ──────────────────────────────────────────────────────────── */

/**
 * Where a process-record scope's directory actually lives for THIS process.
 *
 * Tempdoc 844 S3 / 861 [A9] — honours `JUSTSEARCH_DEV_RUNNER_STATE_ROOT` exactly as
 * `dev-runner.cjs`'s own state-root resolution does, so an isolated dev-runner (integration tests,
 * throwaway stacks) gets an isolated register rather than this reader confidently reporting `[]`
 * for a register it was never pointed at (861 §5.6, quoting 844 §12.2's collapse from the other
 * side).
 *
 * @param {string} mainRepoRoot
 * @param {string} dirName - the scope's directory name under `<stateRoot>/tmp/dev-runner/` (e.g.
 *   `'foreign'`), a sibling of the dev-runner's own enumerated children, never inside them.
 * @param {NodeJS.ProcessEnv} [env]
 */
function resolveRegisterDir(mainRepoRoot, dirName, env = process.env) {
  const override = env?.JUSTSEARCH_DEV_RUNNER_STATE_ROOT;
  if (typeof override === 'string' && override.trim()) {
    return path.join(path.resolve(override.trim()), dirName);
  }
  return path.join(mainRepoRoot, 'tmp', 'dev-runner', dirName);
}

/* ── Generic: the bounded, symlink-refusing register reader ────────────────────────────────────*/

const DEFAULT_REGISTER_MAX_RECORDS = 32;
const DEFAULT_REGISTER_MAX_BYTES = 16_000;

/**
 * Tempdoc 844 D3 / 861 [A7] — read a process-record register directory.
 *
 * Returns one entry per `*.json` file, each either `{ ok: true, recordId, record }` or
 * `{ ok: false, recordId, reason }`. A torn, oversized, non-JSON, symlinked, or scope-invalid file
 * becomes an `ok: false` entry, never an exception and never a silent skip: "there is a record
 * here I could not read" is a state the caller must be told about, not one to hide (844 §12.2).
 *
 * Makes NO liveness claim — that is the scope's own prober's job, from the pid and the port.
 *
 * Record shape validation is deliberately NOT built in here — it is injected via `validateRecord`
 * (record) => { ok: true } | { ok: false, reason }. This is 861 [A7]/[A8]'s point: the shared
 * envelope only knows "is this readable JSON under the size/symlink bounds", never "is this a
 * `foreign` record" or "is this an `agent-spawns` record" — that judgement is the caller's, per
 * scope, so a record shaped for one scope cannot be silently accepted by another scope's reader.
 * A `validateRecord` that is omitted accepts any parseable JSON (used by callers that only need
 * the structural bounds, not a schema check).
 */
async function readRegister({
  dir,
  maxRecords = DEFAULT_REGISTER_MAX_RECORDS,
  maxBytes = DEFAULT_REGISTER_MAX_BYTES,
  // Injectable so the non-ENOENT branch below is testable — a real EACCES on a directory is not
  // reproducible on Windows in a unit test, and an untested branch is how M1 shipped.
  readdir = fsp.readdir,
  validateRecord = null,
} = {}) {
  if (!dir) return [];
  let names;
  try {
    const entries = await readdir(dir, { withFileTypes: true });
    // Include every *.json directory entry, then let the lstat/read loop below classify it.
    // Filtering with isFile() here silently hid symlink records even though this reader's
    // contract says a symlink is returned as ok:false. A safety caller cannot distinguish that
    // omission from an empty register.
    names = entries.filter((e) => e.name.endsWith('.json')).map((e) => e.name).sort();
  } catch (err) {
    // 844 §12.2 in this reader's own code: `[]` is the claim "I looked and nothing has ever
    // registered here", and only ENOENT/ENOTDIR (no such directory / the path is not one) support
    // it. Every other readdir failure — EACCES/EPERM (Windows AV or the indexer holding the
    // directory open), EMFILE, EIO — means "I could not look", which is a different claim. Those
    // propagate so the caller yields `null` ("did not probe") instead of a confident empty register.
    if (err?.code === 'ENOENT' || err?.code === 'ENOTDIR') return [];
    throw err;
  }

  const out = [];
  for (const name of names.slice(0, maxRecords)) {
    const recordId = name.replace(/\.json$/, '');
    const abs = path.join(dir, name);
    try {
      const st = await fsp.lstat(abs);
      if (st.isSymbolicLink()) { out.push({ ok: false, recordId, reason: 'record is a symlink' }); continue; }
      if (!st.isFile()) { out.push({ ok: false, recordId, reason: 'record is not a regular file' }); continue; }
      if (st.size > maxBytes) { out.push({ ok: false, recordId, reason: `record too large (${st.size} > ${maxBytes} bytes)` }); continue; }
      const record = JSON.parse(await fsp.readFile(abs, 'utf8'));
      if (validateRecord) {
        const verdict = validateRecord(record);
        if (!verdict?.ok) {
          out.push({ ok: false, recordId, reason: verdict?.reason || 'record failed scope validation' });
          continue;
        }
      }
      out.push({ ok: true, recordId, record });
    } catch (err) {
      out.push({ ok: false, recordId, reason: String(err?.message || err).slice(0, 200) });
    }
  }
  if (names.length > maxRecords) {
    out.push({ ok: false, recordId: '(register)', reason: `${names.length} records present; only the first ${maxRecords} were read` });
  }
  return out;
}

/* ── Generic: the atomic temp+rename record write ───────────────────────────────────────────── */

/**
 * One record file, written atomically (861 §6.1 names "atomic temp+rename write" as part of the
 * shared grammar). A reader that catches a half-written file reports it `unreadable` rather than
 * crashing, but a torn record is still a record nobody can act on — so producers never write in
 * place.
 *
 * The temp name carries this process's pid so two producers writing the same scope concurrently
 * cannot clobber each other's temp file. Added by 861 Phase 2 as the first scope that needs a
 * JS-side writer: `foreign/`'s producer is Python (`run_register.py:163-180`) and the dev-runner
 * has its own (`dev-runner.cjs:117`), so this is the writer for every scope written from Node,
 * not a fourth independent one.
 */
async function writeRecordAtomic(filePath, record) {
  const tmp = `${filePath}.${process.pid}.tmp`;
  const json = JSON.stringify(record, null, 2) + '\n';
  await fsp.mkdir(path.dirname(filePath), { recursive: true });
  try {
    await fsp.writeFile(tmp, json, 'utf8');
    await fsp.rename(tmp, filePath);
  } catch (err) {
    await fsp.rm(tmp, { force: true }).catch(() => {});
    throw err;
  }
}

/* ── Generic: pid liveness, an HTTP status probe, and state derivation ─────────────────────────*/

function pidAlive(pid) {
  if (typeof pid !== 'number' || pid <= 0) return false;
  try { process.kill(pid, 0); return true; } catch { return false; }
}

function httpGetStatusCode(urlStr, timeoutMs) {
  return new Promise((resolve) => {
    let u;
    try {
      u = new URL(urlStr);
    } catch {
      return resolve(null);
    }

    const req = http.request(
      {
        hostname: u.hostname,
        port: Number(u.port),
        path: u.pathname + u.search,
        method: 'GET',
        timeout: timeoutMs,
      },
      (res) => {
        res.resume();
        resolve(typeof res.statusCode === 'number' ? res.statusCode : null);
      },
    );
    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', () => resolve(null));
    req.end();
  });
}

/**
 * The `live | unreachable | stale` state vocabulary for a registered, port-bearing record (844
 * D3 / 861 §6.1) plus `identityStale`: the port answers but the record's pid is dead, so something
 * IS up, but this record's identity may no longer describe it (a launcher pid can die while the
 * process it launched outlives it — 861 §5.6). `unreadable` is not derived here: it is a structural
 * verdict from `readRegister`/`validateRecord`, produced before liveness is even probed.
 *
 * @param {{ portAnswered: boolean, pidAlive: boolean|null }} args
 * @returns {{ state: 'live'|'unreachable'|'stale', identityStale: true|undefined }}
 */
function deriveLivenessState({ portAnswered, pidAlive: pidIsAlive }) {
  const state = portAnswered ? 'live' : (pidIsAlive ? 'unreachable' : 'stale');
  return {
    state,
    ...(portAnswered && pidIsAlive === false ? { identityStale: true } : {}),
  };
}

function isHttpResponseCode(code) {
  return Number.isInteger(code) && code >= 100 && code <= 599;
}

/* ── FOREIGN SCOPE — a consumer of the generic layer above, not part of its contract ───────────*/

/**
 * Well-known loopback ports that can host a JustSearch-shaped backend started OUTSIDE the
 * dev-runner. `33221` is `jseval`'s eval backend, which binds that port hardcoded and ignores
 * `JUSTSEARCH_API_PORT` (`scripts/jseval/jseval/backend.py:21`,
 * `scripts/jseval/jseval/commands/_common.py:14-22`) — the single most common way a JVM ends up
 * holding ports and the GPU while `quick_health` reports nothing running.
 *
 * Deliberately a SHORT fixed list, not a scan: tempdoc 844 §12.4 rules out building a run registry,
 * and `quick_health` must stay cheap (132 calls, 0% errors, no subprocess).
 */
const FOREIGN_BACKEND_PORTS = [33221];

/**
 * Tempdoc 844 D3 — where a non-dev-runner producer declares its run.
 *
 * `scripts/jseval/jseval/run_register.py` writes one small versioned record per backend it
 * spawns; this is the consumer. The directory sits BESIDE the dev-runner's own state, never
 * inside it: `dev-runner.cjs` enumerates only `runs/` (which it also prunes) and reads
 * `active.json` / `active.lock.json` / `op-leases.json` / `sessions/` / `interference-events.ndjson`
 * by exact name — it never lists its state root — so `foreign/` cannot be mistaken for one of its
 * runs and the dev-runner's own lease semantics are untouched.
 */
const FOREIGN_REGISTER_RELPOSIX = 'tmp/dev-runner/foreign';

/** Directory name under the dev-runner state root — the one constant both sides share. */
const FOREIGN_REGISTER_DIRNAME = 'foreign';

/**
 * The record shape this reader understands. A record declaring anything else is reported as
 * unreadable rather than interpreted on a guess.
 *
 * 861 [A8] — this stays at `1`. New fields this tempdoc's later phases add elsewhere are
 * additive-optional here; a sibling scope (`agent-spawns/`) carries its OWN version constant,
 * versioned independently, so a change to one scope's grammar never forces a lockstep bump on the
 * other's existing, working producer (`run_register.py`).
 */
const FOREIGN_RECORD_SCHEMA_VERSION = 1;

/** Bounds: `quick_health` is called ~133×/21 sessions at 0% errors and must stay cheap. */
const FOREIGN_REGISTER_MAX_RECORDS = 32;
const FOREIGN_REGISTER_MAX_BYTES = 16_000;

/** Where the `foreign/` register lives for THIS process — the generic resolver, scoped. */
function resolveForeignRegisterDir(mainRepoRoot, env = process.env) {
  return resolveRegisterDir(mainRepoRoot, FOREIGN_REGISTER_DIRNAME, env);
}

/**
 * 861 [A7]/[A8] — the `foreign/` scope's OWN record validator, injected into the generic reader.
 * Nothing above this line knows a `foreign` record must declare `schemaVersion: 1` and a usable
 * `ports.api`; that knowledge lives here, scoped to the one register it applies to. A record
 * shaped for a different scope (no port, a different envelope) fails this and comes back
 * `unreadable`, never silently accepted — the negative-fixture test this phase requires.
 */
function validateForeignRecord(record) {
  if (record?.schemaVersion !== FOREIGN_RECORD_SCHEMA_VERSION) {
    return { ok: false, reason: `unknown schemaVersion ${JSON.stringify(record?.schemaVersion)} (this reader understands ${FOREIGN_RECORD_SCHEMA_VERSION})` };
  }
  const port = record?.ports?.api;
  if (!Number.isInteger(port) || port <= 0) {
    return { ok: false, reason: `record declares no usable ports.api (${JSON.stringify(port)})` };
  }
  return { ok: true };
}

/** `foreign/`'s reader: the generic bounded reader, with the foreign-scope validator injected. */
async function readForeignRegister({
  dir,
  maxRecords = FOREIGN_REGISTER_MAX_RECORDS,
  maxBytes = FOREIGN_REGISTER_MAX_BYTES,
  readdir = fsp.readdir,
} = {}) {
  return readRegister({ dir, maxRecords, maxBytes, readdir, validateRecord: validateForeignRecord });
}

/** Default inference server port (llama-server), read once at module load — mirrors
 *  `justsearch-dev-mcp/server.mjs`'s own `INFERENCE_PORT`, computed independently here since the
 *  two modules load at effectively the same moment in the same process. */
const DEFAULT_INFERENCE_PORT = parseInt(process.env.JUSTSEARCH_SERVER_PORT, 10) || 8080;

/**
 * Tempdoc 844 B3/§6.1 — probe for backends the dev-runner did not start, and report them as
 * *observed but unowned*, never merged with the owned run.
 *
 * Why this exists: `quick_health` read only `tmp/dev-runner/active.json`, so a `runHeadlessEval`
 * Head+Worker was invisible and a "free" verdict preceded a 100%-GPU neighbour — a contaminated
 * measurement round (session shard `bccfc163`, 2026-08-14).
 *
 * The tri-state is the whole point and must not be collapsed:
 *   `null` = probing was off or the probe itself failed — I did not look.
 *   `[]`   = I looked and found nothing.
 *   `[..]` = I looked and found these listener/record candidates; each carries an explicit
 *            `unowned` or `unknown` attribution relative to the owned run.
 *
 * Tempdoc 844 D3 extends this with the *register* (`readForeignRegister`), which turns "something
 * is listening on 33221" into "jseval's eval backend, from tree X, pid N". The two sources are
 * merged but never conflated — every entry carries `source`:
 *
 *   `source:'registered'` — a producer declared this run. `state` then says what was verified:
 *       `live`        the declared port answered;
 *       `unreachable` the port is silent but the pid is alive (booting, or wedged);
 *       `stale`       the port is silent AND the pid is gone — a record its producer never got to
 *                     retire (a killed `jseval` never runs its cleanup). Reported, NOT deleted:
 *                     deleting another lifecycle's state on a read is exactly the kind of
 *                     confident guess §12.2 forbids, and the file path is given so removal is a
 *                     one-liner for whoever owns it;
 *       `unreadable`  the file exists but could not be parsed/understood — an explicit unknown.
 *   `source:'observed'` — a port answered with no record behind it. Identical to the P5 behaviour;
 *       all that is known is that something is listening.
 *
 * Registration is the authoritative path and probing is the fallback that keeps the register
 * honest about what never registered — so a registered port is never ALSO reported as `observed`.
 *
 * `probe`, `readRegister` and `isPidAlive` are injectable so this is unit-testable without a
 * network, and `registerDir` can point at a fixture. Still spawns no subprocess.
 */
async function probeForeignRuns({
  enabled,
  // Compatibility input for older direct consumers. The MCP passes the typed state below.
  hasActiveRun,
  ownedRunState = hasActiveRun === false ? 'ABSENT' : hasActiveRun === true ? 'ACTIVE' : 'UNKNOWN',
  ownedApiPort = null,
  aiActive = null,
  ports = FOREIGN_BACKEND_PORTS,
  inferencePort = DEFAULT_INFERENCE_PORT,
  timeoutMs = 800,
  probe = httpGetStatusCode,
  registerDir = null,
  readRegister: readRegisterArg = readForeignRegister,
  isPidAlive = pidAlive,
} = {}) {
  if (!enabled) return null;
  if (!['ACTIVE', 'ABSENT', 'UNKNOWN'].includes(ownedRunState)) return null;
  try {
    // The register is consulted only when probing is on. Without a probe a record's liveness
    // cannot be verified, and listing an unverified record as a run would be the confident
    // default §12.2 rules out — so `probe:false` keeps meaning exactly "I did not look".
    const register = registerDir ? await readRegisterArg({ dir: registerDir }) : [];

    const registeredPorts = new Set();
    for (const entry of register) {
      if (entry.ok) registeredPorts.add(entry.record.ports.api);
    }

    // The owned run's own port is not worth a probe on its own account (nothing to learn), but a
    // record that CLAIMS it still has to be resolved — so it comes back in via registeredPorts.
    const backendPorts = new Set([...ports.filter((port) => port !== ownedApiPort), ...registeredPorts]);
    const checks = [...backendPorts]
      .map(async (port) => ({ port, kind: 'backend', probePath: '/api/status', code: await probe(`http://127.0.0.1:${port}/api/status`, timeoutMs) }));
    checks.push(
      probe(`http://127.0.0.1:${inferencePort}/health`, timeoutMs)
        .then((code) => ({ port: inferencePort, kind: 'inference', probePath: '/health', code })),
    );
    const results = await Promise.all(checks);
    // Any HTTP response proves that a process owns the port. Readiness is irrelevant here: a
    // booting or unhealthy 503 backend is still a live, contending foreign listener.
    const answered = new Map(
      results.filter((r) => r.kind === 'backend').map((r) => [r.port, isHttpResponseCode(r.code)]),
    );

    const found = [];

    // 1. Registered records first — identity beats inference.
    for (const entry of register) {
      if (!entry.ok) {
        found.push({
          port: null,
          kind: 'backend',
          probePath: null,
          attribution: 'unknown',
          source: 'registered',
          state: 'unreadable',
          recordId: entry.recordId,
          recordFile: `${FOREIGN_REGISTER_RELPOSIX}/${entry.recordId}.json`,
          reason: entry.reason,
        });
        continue;
      }
      const rec = entry.record;
      const port = rec.ports.api;
      const portAnswered = answered.get(port) === true;
      const pidIsAlive = Number.isInteger(rec.pid) ? isPidAlive(rec.pid) : null;
      const { state, identityStale } = deriveLivenessState({ portAnswered, pidAlive: pidIsAlive });
      found.push({
        port,
        kind: 'backend',
        probePath: '/api/status',
        // A record claiming the port the dev-runner's own run holds cannot be attributed by this
        // probe — whichever process answered, the listener is ambiguous. Unknown, not unowned.
        attribution: ownedRunState === 'ABSENT'
          || (ownedRunState === 'ACTIVE' && ownedApiPort != null && port !== ownedApiPort)
          ? 'unowned'
          : 'unknown',
        source: 'registered',
        state,
        liveness: { portAnswered, pidAlive: pidIsAlive },
        // The port answers but the process the record names is gone: something IS up, but this
        // record's identity may no longer describe it (jseval records the launcher pid,
        // which is not proof of the answering listener's identity). Saying
        // "live" alone would attach verified-listener status to unverified identity.
        ...(identityStale ? { identityStale: true } : {}),
        recordId: rec.recordId ?? entry.recordId,
        recordFile: `${FOREIGN_REGISTER_RELPOSIX}/${entry.recordId}.json`,
        producer: rec.producer ?? null,
        pid: Number.isInteger(rec.pid) ? rec.pid : null,
        repoRoot: rec.repoRoot ?? null,
        dataDir: rec.dataDir ?? null,
        workload: rec.workload ?? null,
        inferenceRequested: typeof rec.inferenceRequested === 'boolean' ? rec.inferenceRequested : null,
        // Passed through verbatim. `"unverified"` is the producer saying it did not measure GPU
        // residency; this reader must not upgrade that to a claim it also cannot verify.
        gpuBound: rec.gpuBound ?? null,
        sessionId: rec.sessionId ?? null,
        startedAt: rec.startedAt ?? null,
      });
    }

    // 2. Then anything listening that nothing declared — today's P5 behaviour, unchanged.
    for (const r of results) {
      if (!isHttpResponseCode(r.code)) continue;
      if (r.kind === 'inference') {
        // The owned run's OWN llama-server answers here too. Only the run's realized AI state can
        // tell them apart, and that state is itself a tri-state — so an unknown stays unknown
        // rather than being reported as somebody else's process.
        if (ownedRunState === 'ACTIVE' && aiActive === true) continue;
        found.push({
          port: r.port,
          kind: 'inference',
          probePath: r.probePath,
          attribution: ownedRunState === 'ABSENT'
            || (ownedRunState === 'ACTIVE' && aiActive === false)
            ? 'unowned'
            : 'unknown',
          source: 'observed',
        });
      } else {
        if (registeredPorts.has(r.port)) continue; // already reported, with identity
        if (ownedRunState === 'ACTIVE' && r.port === ownedApiPort) continue; // owned listener
        const attribution = ownedRunState === 'ABSENT'
          || (ownedRunState === 'ACTIVE' && ownedApiPort != null && r.port !== ownedApiPort)
          ? 'unowned'
          : 'unknown';
        found.push({ port: r.port, kind: 'backend', probePath: r.probePath, attribution, source: 'observed' });
      }
    }
    return found;
  } catch {
    return null; // the probe itself failed — "I did not look" is the honest answer, not []
  }
}

module.exports = {
  // Generic layer — reusable by any future scope (861 Phase 2's `agent-spawns/`).
  resolveRegisterDir,
  readRegister,
  writeRecordAtomic,
  pidAlive,
  httpGetStatusCode,
  deriveLivenessState,
  // Foreign scope.
  FOREIGN_BACKEND_PORTS,
  FOREIGN_REGISTER_RELPOSIX,
  FOREIGN_REGISTER_DIRNAME,
  FOREIGN_RECORD_SCHEMA_VERSION,
  resolveForeignRegisterDir,
  readForeignRegister,
  validateForeignRecord,
  probeForeignRuns,
};
