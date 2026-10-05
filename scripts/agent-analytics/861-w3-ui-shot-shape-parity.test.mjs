/**
 * Tempdoc 861 W3 — the JS <-> Python shape-parity test.
 *
 * `scripts/jseval/jseval/agent_spawn_register.py` MIRRORS `scripts/dev/lib/agent-spawn-record.cjs`
 * field-for-field; it does NOT and must never import it (Node and Python share no runtime). The
 * two staying in sync rests on this one check: spawn the Python producer FOR REAL, capture the
 * record it actually writes, and run it through the REAL JS reader's own validator
 * (`validateAgentSpawnRecord`) — never a hand-authored JSON fixture that could silently drift from
 * what the Python module actually emits.
 *
 * No `pip install -e` required: the Python side is reached by inserting `scripts/jseval` onto
 * `sys.path` directly (the package has no heavy import-time dependencies — see
 * `jseval/__init__.py` and `jseval/_paths.py`), which works identically in this repo's local dev
 * environment and in CI's `public-claims` job (where jseval IS also pip-installed earlier in the
 * same job, but this test does not rely on that).
 *
 * Run with: `node scripts/agent-analytics/861-w3-ui-shot-shape-parity.test.mjs`
 */

import fs from 'node:fs';
import os from 'node:os';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';

import { resolvePyBin, buildUtf8Env } from '../dev/run-py.mjs';
import { writeHarnessFixture, identityEnv } from './lib/agent-identity-fixture.mjs';

const require = createRequire(import.meta.url);
const {
  validateAgentSpawnRecord,
  buildAgentSpawnRecord,
  AGENT_SPAWN_RECORD_SCHEMA_VERSION,
  OWNERSHIP_MODES,
  hasTrustedOwner,
} = require('../dev/lib/agent-spawn-record.cjs');
const { resolveAgentIdentity } = require('../dev/lib/agent-identity.cjs');
const { ownerBlockFor } = require('../dev/lib/owner-presence.cjs');
const { validateForeignRecord, FOREIGN_RECORD_SCHEMA_VERSION } = require('../dev/lib/process-record.cjs');

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..');
const JSEVAL_DIR = path.join(REPO_ROOT, 'scripts', 'jseval');

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

/** Sorted dotted key-paths of every (nested, non-array) field an object declares — a SET
 * comparison of field NAMES, deliberately blind to values (which legitimately differ: JS resolves
 * `resourceRoots` through `realpathNearest`/backslashes, Python does not touch them at all). This
 * is the check that would catch either producer silently gaining or dropping an optional field
 * the other does not mirror — [A8]'s additive-optional rule means `validateAgentSpawnRecord`
 * alone would never notice such a drift, since an extra or missing OPTIONAL field never fails
 * validation on its own. */
function collectKeyPaths(obj, prefix = '') {
  const paths = [];
  for (const key of Object.keys(obj).sort()) {
    const full = prefix ? `${prefix}.${key}` : key;
    const val = obj[key];
    if (val && typeof val === 'object' && !Array.isArray(val)) {
      paths.push(...collectKeyPaths(val, full));
    } else {
      paths.push(full);
    }
  }
  return paths;
}

/** Run a small python snippet with `scripts/jseval` on sys.path, and return parsed stdout JSON. */
// A simulated agent session (agent-identity.cjs's process-table fixture seam): the Python producer
// resolves its owner through the Node CLI, so both builders see the same owner for the same inputs.
const IDENTITY_DIR = fs.mkdtempSync(path.join(os.tmpdir(), '861-w3-parity-identity-'));
process.on('exit', () => { try { fs.rmSync(IDENTITY_DIR, { recursive: true, force: true }); } catch { /* best effort */ } });
const HARNESS = writeHarnessFixture(IDENTITY_DIR);
const SESSION_LABEL = 'bccfc163-shape-parity';

function runPython(snippet) {
  const res = spawnSync(resolvePyBin(), ['-c', snippet], {
    encoding: 'utf8',
    env: buildUtf8Env(identityEnv(HARNESS.file, { CLAUDE_CODE_SESSION_ID: SESSION_LABEL })),
  });
  if (res.error) throw new Error(`failed to spawn python: ${res.error.message}`);
  if (res.status !== 0) {
    throw new Error(`python exited ${res.status}\nstdout: ${res.stdout}\nstderr: ${res.stderr}`);
  }
  return JSON.parse(res.stdout);
}

const SYS_PATH_PREAMBLE = `import sys, json\nsys.path.insert(0, ${JSON.stringify(JSEVAL_DIR)})\n`;

await check('a Python-written ui-shot record validates through the REAL JS validator', () => {
  const record = runPython(`${SYS_PATH_PREAMBLE}
from jseval import agent_spawn_register as reg
rec = reg.build_record(
    record_id="ui-shot-5174",
    producer="ui-shot",
    pid=4242,
    creation_file_time_utc="134320479841300350",
    cmdline_fingerprint="--port 5174",
    port=5174,
    lease_duration_sec=1800,
    repo_root="F:/example/worktree",
    worktree_root="F:/example/worktree",
    node_modules_real_path="F:/example/main/modules/ui-web/node_modules",
)
print(json.dumps(rec))
`);
  assert.equal(record.schemaVersion, AGENT_SPAWN_RECORD_SCHEMA_VERSION);
  const verdict = validateAgentSpawnRecord(record);
  assert.equal(verdict.ok, true, `expected the Python-written record to validate: ${verdict.reason}`);
  assert.equal(record.ownership, OWNERSHIP_MODES.SESSION_OWNED);
  assert.deepEqual(record.resourceRoots, {
    worktreeRoot: 'F:/example/worktree',
    nodeModulesRealPath: 'F:/example/main/modules/ui-web/node_modules',
  });
});

await check('F2: the JS and Python builders produce the SAME key set for equivalent inputs (861 [A8] drift guard)', async () => {
  const pyRecord = runPython(`${SYS_PATH_PREAMBLE}
from jseval import agent_spawn_register as reg
rec = reg.build_record(
    record_id="ui-shot-5176", producer="ui-shot", pid=4244,
    creation_file_time_utc="134320479841300352", cmdline_fingerprint="--port 5176",
    port=5176, lease_duration_sec=1800,
    repo_root="F:/example/worktree", worktree_root="F:/example/worktree",
    node_modules_real_path="F:/example/main/modules/ui-web/node_modules",
)
print(json.dumps(rec))
`);
  const jsRecord = await buildAgentSpawnRecord({
    recordId: 'ui-shot-5176',
    producer: 'ui-shot',
    pid: 4244,
    creationFileTimeUtc: '134320479841300352',
    cmdlineFingerprint: '--port 5176',
    port: 5176,
    leaseDurationSec: 1800,
    sessionId: SESSION_LABEL,
    owner: HARNESS.owner,
    repoRoot: 'F:/example/worktree',
    resourceRoots: {
      worktreeRoot: 'F:/example/worktree',
      nodeModulesRealPath: 'F:/example/main/modules/ui-web/node_modules',
    },
  });
  // A SET comparison, not deepEqual: JS resolves resourceRoots through realpathNearest (which can
  // change slashes/casing on Windows), Python does not touch them at all — the VALUES legitimately
  // differ. What must NOT differ is which FIELDS each side populates for the same inputs; either
  // side gaining or dropping an optional field must fail this, even though [A8] means it would
  // never fail plain validation.
  assert.deepEqual(collectKeyPaths(jsRecord), collectKeyPaths(pyRecord));
});

await check('Python and Node agree on who the session is: same owner block, same label, both trusted', () => {
  const env = identityEnv(HARNESS.file, { CLAUDE_CODE_SESSION_ID: SESSION_LABEL });
  const node = resolveAgentIdentity({ env, noCache: true });
  const py = runPython(`${SYS_PATH_PREAMBLE}
from jseval import agent_identity, agent_spawn_register as reg, run_register
rec = reg.build_record(
    record_id="ui-shot-5177", producer="ui-shot", pid=4245,
    creation_file_time_utc="134320479841300353", cmdline_fingerprint="--port 5177",
    port=5177, lease_duration_sec=1800,
)
print(json.dumps({"identity": agent_identity.resolve_identity(), "record": rec}))
`);
  assert.equal(node.owner.key, HARNESS.owner.key, 'sanity: Node resolves the simulated harness');
  assert.deepEqual(py.record.owner, ownerBlockFor(node), 'the Python record names the owner Node resolves');
  assert.equal(py.identity.owner.key, node.owner.key);
  assert.equal(py.identity.harness, node.harness);
  assert.equal(py.identity.sessionId, node.sessionId);
  assert.equal(py.record.sessionId, node.sessionId);
  assert.equal(hasTrustedOwner(py.record), true, 'a Python-written record carries trusted owner identity');
});

await check('a Python-written record with a session id declared validates too', () => {
  const record = runPython(`${SYS_PATH_PREAMBLE}
from jseval import agent_spawn_register as reg
rec = reg.build_record(
    record_id="ui-shot-5175", producer="ui-shot", pid=4243,
    creation_file_time_utc="134320479841300351", cmdline_fingerprint="--port 5175",
    port=5175, lease_duration_sec=1800,
)
print(json.dumps(rec))
`);
  const verdict = validateAgentSpawnRecord(record);
  assert.equal(verdict.ok, true, `expected the record to validate: ${verdict.reason}`);
  assert.equal(record.sessionId, 'bccfc163-shape-parity');
});

// [A7]/[A8], the OTHER direction: this scope's validator must reject a `foreign/`-shaped record
// (no identity triple, no ownership mode) just as `foreign/`'s validator rejects an agent-spawn
// record — proving the two scopes stayed distinct rather than collapsing into one permissive
// envelope, even when the fixture crossing the boundary is Python-authored on both sides.
await check('a Python-written foreign/-shaped record is rejected by the agent-spawns validator', () => {
  const record = runPython(`${SYS_PATH_PREAMBLE}
from jseval import run_register
from pathlib import Path
rec = run_register.build_record(
    pid=9001, port=33221, repo_root=Path("F:/example"), data_dir=Path("F:/example/data"),
    inference_requested=False,
)
print(json.dumps(rec))
`);
  assert.equal(record.schemaVersion, FOREIGN_RECORD_SCHEMA_VERSION);
  assert.equal(validateForeignRecord(record).ok, true); // sanity: it IS a valid foreign/ record
  const verdict = validateAgentSpawnRecord(record);
  assert.equal(verdict.ok, false, 'a foreign/-shaped record must not be silently accepted here');
});

if (failures.length > 0) {
  console.error(`861-w3-ui-shot-shape-parity: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`861-w3-ui-shot-shape-parity: all ${passed} checks passed`);
