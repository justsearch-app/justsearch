import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { ROOT, ARMS, buildPlan, parseArgs } from './e-run.mjs';
import { measurementIdentity } from './e-pair-identity.mjs';
import { LiveCollector, crashExperiment, childPathExperiment, hangExperiment } from './e456-live.mjs';
import { identifyArmRoot, requireCollectorSample } from './e456-root.mjs';

const birth = ms => (116444736000000000n + BigInt(ms) * 10000n).toString();
const issuedAt = new Date(1000000).toISOString();
const java = (pid, parent, cls = 'io.justsearch.ui.HeadlessApp', born = 1002000) => ({
  ProcessId: pid, ParentProcessId: parent, Name: 'java.exe', ExecutablePath: 'C:/jdk/bin/java.exe',
  CommandLine: `"C:/jdk/bin/java.exe" ${cls}`, CreationFileTimeUtc: birth(born),
});
const launcher = { ProcessId: 10, ParentProcessId: 1, Name: 'cmd.exe', ExecutablePath: 'C:/Windows/System32/cmd.exe',
  CommandLine: 'cmd.exe /c owned-start.bat', CreationFileTimeUtc: birth(1001000) };
const receipt = dataDir => ({ ok: true, runId: 'owned-run', dataDir, pids: { backendRootPid: 10, runnerPid: 1 } });
const mainTable = () => [launcher, java(20, 10), java(30, 20, 'io.justsearch.indexerworker.IndexerWorker', 1003000),
  java(99, 98)]; // An unrelated HeadlessApp must not affect selection.

test('MAIN receipt launcher -> Head JVM -> Worker needs no data-dir command argument', () => {
  const table = mainTable(), root = identifyArmRoot({ arm: 'main', dataDir: ROOT, receipt: receipt(ROOT), issuedAt }, table);
  assert.equal(root.ProcessId, 20); assert.ok(!root.CommandLine.includes(ROOT));
  const direct = { ...receipt(ROOT), pids: { backendRootPid: 20 } };
  assert.equal(identifyArmRoot({ arm: 'main', dataDir: ROOT, receipt: direct, issuedAt }, table).ProcessId, 20);
});
test('BRANCH uses owned manifest PID, including supervised replacement after receipt PID exits', () => {
  const root = java(20, 1); root.CommandLine += ` -Djustsearch.data.dir="${ROOT}"`;
  const options = { arm: 'branch', dataDir: ROOT, receipt: receipt(ROOT), issuedAt };
  assert.equal(identifyArmRoot(options, [root, java(99, 98)], { pid: 20, instanceId: 'owned' }).ProcessId, 20);
  assert.throws(() => identifyArmRoot(options, [root], { pid: 99, instanceId: 'wrong' }), /Arm root identity unavailable/);
  assert.throws(() => identifyArmRoot(options, [root], null), /manifest PID\/instance missing/);
});
test('receipt roots reject absent/old identities, non-Java, ambiguous descendants and conflicting data directory', () => {
  const options = { arm: 'main', dataDir: ROOT, receipt: receipt(ROOT), issuedAt };
  assert.throws(() => identifyArmRoot(options, []), /receipt process 10 absent/);
  assert.throws(() => identifyArmRoot(options, [{ ...launcher, CreationFileTimeUtc: birth(999999) }, java(20, 10)]), /predates/);
  assert.throws(() => identifyArmRoot(options, [launcher, { ...java(20, 10), Name: 'node.exe' }]), /0 HeadlessApp JVMs/);
  assert.throws(() => identifyArmRoot(options, [launcher, java(20, 10), java(21, 10)]), /2 HeadlessApp JVMs/);
  assert.throws(() => identifyArmRoot(options, [launcher, java(20, 10, undefined, 999999)]), /0 HeadlessApp JVMs/);
  assert.throws(() => identifyArmRoot(options, [launcher, { ...java(20, 10), CreationFileTimeUtc: null }]), /0 HeadlessApp JVMs/);
  const wrong = java(20, 10); wrong.CommandLine += ' "-Djustsearch.data.dir=C:/foreign"';
  assert.throws(() => identifyArmRoot(options, [launcher, wrong]), /JVM data directory disagrees/);
  assert.throws(() => identifyArmRoot({ ...options, receipt: receipt('C:/foreign') }, mainTable()), /receipt data directory mismatch/);
});
function collectorFixture(t, arm) {
  const raw = fs.mkdtempSync(path.join(ROOT, 'tmp/e-root-'));
  t.after(() => fs.rmSync(raw, { recursive: true, force: true }));
  const dataDir = path.join(raw, 'data'), directory = path.join(raw, 'soak');
  fs.mkdirSync(path.join(dataDir, 'runtime'), { recursive: true }); fs.mkdirSync(directory);
  const context = { raw, dataDir, owned: { runId: 'owned-run', command: { label: 'start-soak' } },
    record: { arm, groups: ['E4'], commands: [{ mode: 'start', label: 'start-soak', startedAt: issuedAt }] } };
  fs.writeFileSync(path.join(raw, 'start-soak-receipt.json'), JSON.stringify(receipt(dataDir)));
  const table = mainTable();
  if (arm === 'branch') {
    table.splice(2, 1); table[1].CommandLine += ` -Djustsearch.data.dir="${dataDir}"`;
    fs.writeFileSync(path.join(dataDir, 'runtime/manifest.json'), JSON.stringify({ pid: 20, instanceId: 'owned', children: [] }));
  }
  const collector = new LiveCollector(context, { directory });
  collector.table = () => ({ ok: true, readAt: Date.now(), table });
  collector.jcmd = () => { throw new Error('Unexpected live JVM diagnostic in fake-table test'); };
  for (const row of table.filter(p => p.Name === 'java.exe')) collector.flags.set(`${row.ProcessId}/${row.CreationFileTimeUtc}`, { code: 0, stdout: '' });
  return { collector, context, table };
}
for (const arm of ['main', 'branch']) test(`${arm}: real sampleOnce writes scoped process evidence using own receipt`, async t => {
  const { collector } = collectorFixture(t, arm);
  assert.deepEqual(collector.current, []);
  await collector.sample();
  const scope = JSON.parse(fs.readFileSync(collector.scopeFile));
  assert.deepEqual(scope.processes.map(p => p.role), arm === 'main' ? ['head', 'worker'] : ['engine']);
  assert.equal(collector.root.pid, 20);
  assert.ok(collector.current.length); assert.equal(collector.snapshots.length, 1);
  assert.equal(collector.snapshots[0].rootSource.backendRootPid, 10);
  requireCollectorSample(collector, 'test', arm === 'main' ? 'worker' : 'engine');
  if (arm === 'main') assert.equal(collector.manifest, null);
});
test('missing root sample clears stale process state and fails immediately with retained named error', async t => {
  const { collector, table } = collectorFixture(t, 'main'); await collector.sample();
  table.splice(0, table.length);
  await assert.rejects(collector.sample(), /Arm root identity unavailable/);
  assert.deepEqual(collector.current, []); assert.equal(collector.root, null);
  assert.match(JSON.parse(fs.readFileSync(collector.artifact)).snapshots.at(-1).identityError, /receipt process/);
});
test('receipt mismatch cannot select another run and missing start command has a named failure', async t => {
  const { collector, context } = collectorFixture(t, 'main');
  context.owned.runId = 'foreign-run'; await assert.rejects(collector.sample(), /start receipt\/run ID.*mismatched/);
  context.record.commands = []; await assert.rejects(collector.sample(), /owned start command\/raw directory missing/);
});
test('MAIN startup identity cannot silently rebind a reused JVM PID', async t => {
  const { collector, table } = collectorFixture(t, 'main'); await collector.sample();
  table[1] = { ...table[1], CreationFileTimeUtc: birth(1002500) };
  await assert.rejects(collector.sample(), /MAIN receipt JVM identity changed\/reused/);
  assert.deepEqual(collector.current, []);
});
test('E5/E6 experiment entry points reject missing current/snapshot/manifest before any mutation', async () => {
  for (const collector of [undefined, {}, { root: {}, current: [] }, { root: {}, current: [{}], snapshots: [] }]) {
    const context = { collector, record: { arm: 'main' } };
    await assert.rejects(crashExperiment(context), /Collector sample unavailable \(E5 crash\)/);
    await assert.rejects(childPathExperiment(context, 'restart'), /Collector sample unavailable \(E5 child restart\)/);
    await assert.rejects(hangExperiment(context, { kind: 'soft' }), /Collector sample unavailable \(E6 soft\)/);
  }
  const branch = { arm: 'branch', root: { pid: 20 }, current: [{}], snapshots: [{ processes: [{}] }], manifest: null };
  assert.throws(() => requireCollectorSample(branch, 'branch missing manifest'), /Collector manifest unavailable/);
  const main = { ...branch, arm: 'main' };
  assert.throws(() => requireCollectorSample(main, 'missing target', 'worker'), /Collector target unavailable.*worker/);
});
test('E1/E2 plans exclude the E456 collector/root identity instrument closure', () => {
  const values = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts/jseval/lane-f/test/fixtures/values-before-e0.json'))).values;
  for (const group of ['e1-quality', 'e2-e3-load']) for (const arm of ['main', 'branch']) {
    const plan = buildPlan(parseArgs([group, '--arm', arm, ...(group === 'e2-e3-load' ? ['--workload', 'agent-idle'] : [])]), values, ROOT, { decision: 'recapture' });
    const inputs = measurementIdentity(plan, { sourceRoot: ROOT, outputRoot: ROOT, armTree: ARMS[arm], arm, group }).pairIdentityInputs;
    assert.ok(!inputs.instruments['scripts/jseval/lane-f/e456-live.mjs']);
    assert.ok(!inputs.instruments['scripts/jseval/lane-f/e456-root.mjs']);
  }
});
