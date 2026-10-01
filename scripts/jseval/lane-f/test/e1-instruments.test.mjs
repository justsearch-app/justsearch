import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const here = path.dirname(fileURLToPath(import.meta.url));
const lane = path.dirname(here);
const fixtures = path.join(here, 'fixtures');
const bash = process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash';
const posix = (p) => p.replaceAll('\\', '/').replace(/^([A-Za-z]):/, (_, drive) => `/${drive.toLowerCase()}`);
function temporary(t) {
  const dir = fs.mkdtempSync(path.join(here, '.tmp-e1-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}
function analyze(t, rss = 'engine.csv', extra = {}) {
  const dir = temporary(t);
  fs.copyFileSync(path.join(fixtures, rss), path.join(dir, 'head-rss.csv'));
  for (const [name, fixture] of Object.entries(extra)) fs.copyFileSync(path.join(fixtures, fixture), path.join(dir, name));
  return spawnSync(process.execPath, [path.join(lane, 'analyze-head-run.cjs'), dir], { encoding: 'utf8' });
}
test('a: discovers engine, llama-server and extraction-child roles', (t) => {
  const r = analyze(t);
  assert.equal(r.status, 0, r.stderr);
  for (const role of ['engine', 'llama-server', 'extraction-child']) assert.match(r.stdout, new RegExp(`${role} \\(all samples\\): n=1`));
});
test('a: obsolete split roles and empty phases fail loudly', (t) => {
  const old = analyze(t, 'old-roles.csv');
  assert.notEqual(old.status, 0);
  assert.match(old.stderr, /Invalid RSS role set/);
  const empty = analyze(t, 'engine.csv', { 'phases.json': 'empty-phase.json' });
  assert.notEqual(empty.status, 0);
  assert.match(empty.stderr, /Empty RSS phase: idle/);
});
test('b: dry-run resolves heap and collector without creating output', (t) => {
  const out = path.join(temporary(t), 'must-not-exist');
  const run = (...args) => spawnSync(bash, [posix(path.join(lane, 'head-flag-run.sh')), 'test', posix(out), ...args, '--dry-run'], { encoding: 'utf8' });
  assert.equal(run().status, 0);
  assert.equal(run().stdout.trim(), 'JAVA options: -Xmx2g -XX:-UseSerialGC -XX:-UseZGC -XX:+UseG1GC');
  const zgc = run('33222', '--heap', '768m', '--collector', 'zgc');
  assert.equal(zgc.status, 0, zgc.stderr);
  assert.equal(zgc.stdout.trim(), 'JAVA options: -Xmx768m -XX:-UseSerialGC -XX:-UseG1GC -XX:+UseZGC');
  assert.notEqual(run('--collector', 'bad').status, 0);
  assert.notEqual(run('--heap', '-Xmx2g').status, 0);
  assert.notEqual(run('--heap').status, 0);
  assert.equal(fs.existsSync(out), false);
  const script = fs.readFileSync(path.join(lane, 'head-flag-run.sh'), 'utf8');
  assert.match(script, /JUSTSEARCH_HEAD_HEAP="\$heap" UI_OPTS="\$collector_opts"/);
});
test('c: sampler identifies roles, parent PID and private bytes (static)', () => {
  const script = fs.readFileSync(path.join(lane, 'head-rss-sampler.ps1'), 'utf8');
  for (const role of ['engine', 'llama-server', 'extraction-child']) assert.ok(script.includes(`$role = '${role}'`));
  assert.match(script, /\$engineIds -contains \$p.ParentProcessId.*ExtractionSandboxChild/);
  assert.match(script, /Name -eq 'llama-server.exe'/);
  assert.match(script, /PrivateMemorySize64/);
  assert.match(script, /ts,role,pid,workingSetMB,privateMB/);
  assert.match(script, /-f \$ts, \$role, \$p.ProcessId, \$ws, \$pm/);
});
test('d: status sampler records real field names at five seconds with loopback Host', (t) => {
  const dir = temporary(t);
  const stop = path.join(dir, 'stop');
  const mock = path.join(dir, 'mock');
  fs.mkdirSync(mock);
  fs.writeFileSync(path.join(mock, 'curl'), `#!/usr/bin/env bash\nprintf '%s\\n' "$*" >> "$CALLS"\ncase "\${*: -1}" in\n */api/knowledge/status) cat "$FIXTURES/knowledge.json" ;;\n */api/status) cat "$FIXTURES/status.json" ;;\n *) exit 9 ;;\nesac\n`, { mode: 0o755 });
  fs.writeFileSync(path.join(mock, 'sleep'), '#!/usr/bin/env bash\necho "$*" >> "$CALLS"\ntouch "$STOP"\n', { mode: 0o755 });
  const r = spawnSync(bash, ['-c', 'export PATH="$MOCK:$PATH"; bash "$SAMPLER" "$OUT" 33222 "$STOP"'], {
    encoding: 'utf8', env: { ...process.env, MOCK: posix(mock), SAMPLER: posix(path.join(lane, 'status-sampler.sh')), OUT: posix(dir), STOP: posix(stop), CALLS: posix(path.join(dir, 'calls')), FIXTURES: posix(fixtures) },
  });
  assert.equal(r.status, 0, r.stderr);
  const csv = fs.readFileSync(path.join(dir, 'status-series.csv'), 'utf8');
  assert.match(csv, /ts,indexedDocuments,pendingNerCount,completedNerCount,embeddingCoveragePercent,spladeCoveragePercent,chunkDocCount/);
  assert.match(csv, /,2,3,4,50,25,150\n/);
  const calls = fs.readFileSync(path.join(dir, 'calls'), 'utf8').trim().split('\n');
  assert.match(calls[0], /Host: 127.0.0.1:33222 http:\/\/127.0.0.1:33222\/api\/knowledge\/status/);
  assert.equal(calls[2], '5');
  const bad = spawnSync(bash, [posix(path.join(lane, 'status-sampler.sh')), posix(dir), 'example.com'], { encoding: 'utf8' });
  assert.notEqual(bad.status, 0);
});
test('e: admission call p95 and chunks/s use known caller records and time deltas', (t) => {
  const r = analyze(t, 'engine.csv', { 'context-many.json': 'context-many.json', 'status-series.csv': 'status-series.csv' });
  assert.equal(r.status, 0, r.stderr);
  // Existing analyzer percentile convention: sorted[floor(0.95 * n)].
  assert.match(r.stdout, /admission context-many.json all: n=20 p95 200.0 ms/);
  assert.match(r.stdout, /admission context-many.json admitted: n=19 p95 200.0 ms/);
  assert.match(r.stdout, /00:00:05Z: chunks\/s 10.000/);
  assert.match(r.stdout, /00:00:15Z: chunks\/s 2.000/);
});

test('e: streamed terminal errors never enter the analyzer admitted count', t => {
  const dir = temporary(t);
  fs.copyFileSync(path.join(fixtures, 'engine.csv'), path.join(dir, 'head-rss.csv'));
  fs.writeFileSync(path.join(dir, 'workload.json'), JSON.stringify({ requests: [
    { status: 200, durationMs: 200 },
    { status: 200, durationMs: 8, streamed: true, terminal: { doneCount: 0, errorCount: 1, eof: true, errorCode: 'AI_OFFLINE' } },
  ] }));
  const result = spawnSync(process.execPath, [path.join(lane, 'analyze-head-run.cjs'), dir], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /terminal errors: {"AI_OFFLINE":1}/);
  assert.match(result.stdout, /admission workload.json admitted: n=1 p95 200.0 ms/);
});
