// A dev tool cleans only a data folder it created and marked. A non-empty folder without the
// marker, such as a person's own data named by a typo, is never cleaned: dev-all and dev-runner
// refuse the start and leave it byte-identical. A new folder is created and marked, an empty one
// marked, and the default dev folder from older runs adopted only when it holds nothing but known
// dev-data contents. The real CLIs refuse before they start anything.
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const repo = path.resolve(import.meta.dirname, '../..');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dev-data-folder-'));
fs.mkdirSync(path.join(repo, 'tmp'), { recursive: true });
const inRepo = fs.mkdtempSync(path.join(repo, 'tmp', 'dev-data-folder-'));
process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = path.join(root, 'unit-state');
const MARKER = '.justsearch-dev-data.json';
const tests = [];
const test = (name, fn) => tests.push([name, fn]);

/** Every entry under `dir` with its kind, size, modification time and content hash. */
function fingerprint(dir) {
  const rows = [];
  const walk = (d) => {
    for (const name of fs.readdirSync(d).sort()) {
      const full = path.join(d, name), st = fs.lstatSync(full), rel = path.relative(dir, full);
      if (st.isDirectory()) { rows.push([rel, 'dir', st.mtimeMs]); walk(full); }
      else rows.push([rel, 'file', st.size, st.mtimeMs, crypto.createHash('sha256').update(fs.readFileSync(full)).digest('hex')]);
    }
  };
  walk(dir);
  rows.push(['.', fs.statSync(dir).mtimeMs]);
  return rows;
}

/** A person's own data: an index, settings and a document, none of it a dev tool's. */
function personalFolder(base) {
  const dir = path.join(base, 'my-documents');
  fs.mkdirSync(path.join(dir, 'index', 'default'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'ui'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'index', 'default', 'segments_7'), 'segments');
  fs.writeFileSync(path.join(dir, 'ui', 'settings.json'), '{"schemaVersion":5}');
  fs.writeFileSync(path.join(dir, 'thesis.docx'), crypto.randomBytes(4096));
  fs.writeFileSync(path.join(dir, 'logs'), 'a file where a dev folder has a folder');
  return dir;
}

/** dev-all in a copy of the repository whose Gradle is a stub that only records it was run. */
function fakeRepository(name) {
  const fake = path.join(root, name);
  for (const dir of ['scripts/dev/lib', 'modules/ui-web/scripts']) {
    fs.cpSync(path.join(repo, dir), path.join(fake, dir), { recursive: true });
  }
  for (const file of ['governance/store-recoverability.v1.json', 'governance/supervision-contract.v1.json']) {
    if (!fs.existsSync(path.join(repo, file))) continue;
    fs.mkdirSync(path.dirname(path.join(fake, file)), { recursive: true });
    fs.copyFileSync(path.join(repo, file), path.join(fake, file));
  }
  fs.writeFileSync(path.join(fake, 'modules/ui-web/package.json'), JSON.stringify({ name: 'fixture-ui', private: true }));
  fs.writeFileSync(path.join(fake, 'gradlew.bat'), '@echo off\r\necho started>"%~dp0gradle-started.txt"\r\nexit /b 3\r\n');
  fs.writeFileSync(path.join(fake, 'gradlew'), '#!/bin/sh\nprintf \'started\\n\' > "$(dirname "$0")/gradle-started.txt"\nexit 3\n',
    { mode: 0o755 });
  return fake;
}

test('dev-all refuses a non-empty unmarked --data-dir and leaves it byte-identical', () => {
  const fake = fakeRepository('dev-all-repo');
  const dir = personalFolder(path.join(root, 'dev-all'));
  const before = fingerprint(dir);
  const r = spawnSync(process.execPath, [path.join(fake, 'modules/ui-web/scripts/dev-all.cjs'), '--data-dir', dir],
    { cwd: path.join(fake, 'modules/ui-web'), encoding: 'utf8', windowsHide: true, timeout: 60000,
      env: { ...process.env, NODE_OPTIONS: '', JUSTSEARCH_JOB_RECORD_DIR: path.join(root, 'dev-all-jobs') } });
  assert.notEqual(r.status, 0, 'the start is refused: ' + r.stdout + r.stderr);
  assert.deepEqual(fingerprint(dir), before, 'the folder is byte-identical: ' + r.stdout + r.stderr);
  assert.match(r.stderr, /Refusing to clean/, 'the refusal names what it will not do: ' + r.stderr);
  assert.ok(r.stderr.includes(path.resolve(dir)), 'the refusal names the folder: ' + r.stderr);
  assert.match(r.stderr, /new or empty folder/, 'and how to proceed');
  assert.match(r.stderr, /modules\/ui-web\/\.dev-data/, 'including the default dev folder');
  assert.equal(fs.existsSync(path.join(fake, 'gradle-started.txt')), false, 'nothing was started');
});

test('dev-all creates and marks a new --data-dir, keeps the marker through its clean, and cleans it again next start', () => {
  const fake = fakeRepository('dev-all-own');
  const dir = path.join(root, 'dev-all-own-data');
  const run = () => spawnSync(process.execPath, [path.join(fake, 'modules/ui-web/scripts/dev-all.cjs'), '--data-dir', dir],
    { cwd: path.join(fake, 'modules/ui-web'), encoding: 'utf8', windowsHide: true, timeout: 60000,
      env: { ...process.env, NODE_OPTIONS: '', JUSTSEARCH_JOB_RECORD_DIR: path.join(root, 'dev-all-own-jobs') } });
  const first = run();
  assert.ok(fs.existsSync(path.join(fake, 'gradle-started.txt')), 'the start went ahead: ' + first.stdout + first.stderr);
  assert.equal(JSON.parse(fs.readFileSync(path.join(dir, MARKER), 'utf8')).tool, 'dev-all', 'the folder is marked and the clean kept it');
  fs.rmSync(path.join(fake, 'gradle-started.txt'));
  fs.writeFileSync(path.join(dir, 'stale.log'), 'from the last run');
  const second = run();
  assert.ok(fs.existsSync(path.join(fake, 'gradle-started.txt')), 'its own folder is started again: ' + second.stdout + second.stderr);
  assert.equal(fs.existsSync(path.join(dir, 'stale.log')), false, 'and cleaned');
  assert.ok(fs.existsSync(path.join(dir, MARKER)));
});

test('dev-runner start refuses a non-empty unmarked --data-dir, leaves it byte-identical and starts nothing', () => {
  const dir = personalFolder(path.join(inRepo, 'dev-runner'));
  const before = fingerprint(dir);
  const state = path.join(root, 'cli-state');
  const r = spawnSync(process.execPath, [path.join(repo, 'scripts/dev/dev-runner.cjs'), 'start', '--json', '--skip-build',
    '--api-port', '0', '--ui-port', '5598', '--data-dir', dir, '--session-id', 'data-folder'],
  { cwd: repo, encoding: 'utf8', windowsHide: true, timeout: 120000,
    env: { ...process.env, NODE_OPTIONS: '', CI: '', JUSTSEARCH_DEV_RUNNER_STATE_ROOT: state, JUSTSEARCH_DEV_MCP_LOG_NDJSON: '0' } });
  assert.notEqual(r.status, 0, 'the start is refused: ' + r.stdout + r.stderr);
  assert.deepEqual(fingerprint(dir), before, 'the folder is byte-identical: ' + r.stdout + r.stderr);
  assert.match(r.stdout + r.stderr, /DATA_DIR_NOT_OURS/, 'with its code: ' + r.stdout + r.stderr);
  assert.match(r.stdout + r.stderr, /Refusing to clean/);
  assert.equal(fs.existsSync(path.join(state, 'active.json')), false, 'no run was admitted');
});

test('a dev tool creates and marks a new folder, marks an empty one, and cleans only what it marked', async () => {
  const lib = require('./lib/dev-data-folder.cjs');
  const api = require('./dev-runner.cjs').__test;
  const fresh = path.join(inRepo, 'fresh', 'data');
  const created = lib.claimDevDataFolder(fresh, { tool: 'dev-runner' });
  assert.equal(created.action, 'create');
  assert.equal(JSON.parse(fs.readFileSync(path.join(fresh, MARKER), 'utf8')).tool, 'dev-runner', 'the marker names the tool');
  assert.ok(Number.isFinite(Date.parse(lib.readMarker(fresh).createdAt)), 'and the creation time');
  const empty = path.join(inRepo, 'empty'); fs.mkdirSync(empty);
  assert.equal(lib.claimDevDataFolder(empty, { tool: 'dev-all' }).action, 'mark-empty');
  // A marked folder is cleaned as before; the marker itself is kept.
  fs.writeFileSync(path.join(fresh, 'stale.log'), 'x');
  fs.mkdirSync(path.join(fresh, 'index'));
  await api.cleanOwnDataDir(fresh, 'soft');
  assert.deepEqual(fs.readdirSync(fresh).sort(), [MARKER, 'index'].sort());
  await api.cleanOwnDataDir(fresh, 'hard');
  assert.deepEqual(fs.readdirSync(fresh), [MARKER]);
  // An unmarked non-empty folder is refused by the clean itself, unchanged.
  const mine = personalFolder(path.join(inRepo, 'direct'));
  const before = fingerprint(mine);
  await assert.rejects(api.cleanOwnDataDir(mine, 'soft'), (err) => err.code === 'DATA_DIR_NOT_OURS');
  assert.deepEqual(fingerprint(mine), before);
  // `--clean none` deletes nothing and needs no marker.
  await api.cleanOwnDataDir(mine, 'none');
  assert.deepEqual(fingerprint(mine), before);
});

test('the default dev folder from older runs is adopted only when it holds nothing but dev-data contents', () => {
  const lib = require('./lib/dev-data-folder.cjs');
  const register = path.join(repo, 'governance', 'store-recoverability.v1.json');
  const old = path.join(root, 'old-default');
  for (const d of ['index', 'logs', 'runtime', 'ui', 'memories', 'telemetry']) fs.mkdirSync(path.join(old, d), { recursive: true });
  for (const f of ['jobs.db', 'jobs.db-wal', 'app.lock', 'watched_roots.json', '.help-ingested-version']) fs.writeFileSync(path.join(old, f), '');
  assert.equal(lib.claimDevDataFolder(old, { tool: 'dev-all', isDefault: true, registerPath: register }).action, 'adopt');
  assert.equal(lib.readMarker(old).adopted, true);
  const odd = path.join(root, 'odd-default');
  fs.mkdirSync(path.join(odd, 'index'), { recursive: true });
  fs.writeFileSync(path.join(odd, 'thesis.docx'), 'mine');
  const before = fingerprint(odd);
  const verdict = lib.claimDevDataFolder(odd, { tool: 'dev-all', isDefault: true, registerPath: register, defaultHint: 'x' });
  assert.equal(verdict.ok, false);
  assert.deepEqual(verdict.unknown, ['thesis.docx'], 'the refusal names what does not belong');
  assert.deepEqual(fingerprint(odd), before, 'and changes nothing');
  // The same contents outside the default path are not adopted.
  const elsewhere = path.join(root, 'elsewhere');
  fs.cpSync(path.join(root, 'old-default'), elsewhere, { recursive: true }); fs.rmSync(path.join(elsewhere, MARKER));
  assert.equal(lib.claimDevDataFolder(elsewhere, { tool: 'dev-all', registerPath: register }).ok, false);
});

let failed = 0;
try {
  for (const [name, fn] of tests) {
    try { await fn(); console.log(`PASS ${name}`); }
    catch (error) { failed++; console.error(`FAIL ${name}: ${error.stack}`); }
  }
} finally {
  // Plain files and folders created above; no links, no processes.
  fs.rmSync(root, { recursive: true, force: true });
  fs.rmSync(inRepo, { recursive: true, force: true });
}
console.log(`data-folder: ${tests.length - failed}/${tests.length} passed`);
process.exitCode = failed ? 1 : 0;
