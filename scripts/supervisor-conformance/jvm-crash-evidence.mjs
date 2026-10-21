// Real Engine JVM evidence check. Run on Linux with an installDist; no shared dev stack.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readLinuxProcess, signalLinuxProcess } from '../dev/lib/process-identity.cjs';
const value = flag => process.argv[process.argv.indexOf(flag) + 1];
const root = path.resolve(process.argv.includes('--root') ? value('--root') : fileURLToPath(new URL('../..', import.meta.url)));
const dist = path.resolve(value('--dist'));
assert.equal(process.platform, 'linux', 'Windows needs its own real-machine qualification');
assert(fs.existsSync(path.join(dist, 'lib')) && fs.readdirSync(path.join(dist, 'lib')).some(name => /^ui-[0-9].*\.jar$/.test(name)), 'Build :modules:ui:installDist first');
const work = fs.mkdtempSync(path.join(process.env.TMPDIR || path.join(root, 'tmp/builder'), 'jvm-evidence-'));
const java = path.join(process.env.JAVA_HOME, 'bin/java');
const source = fs.readFileSync(path.join(root, 'modules/shell/src-tauri/src/lib.rs'), 'utf8');
const baseline = !source.includes('recovery::jvm_options(&app_data_dir)');
function options(profile) {
  if (!baseline) return JSON.parse(execFileSync(path.join(root, 'modules/shell/src-tauri/target/debug/supervisor-conformance'), ['--jvm-options', profile], { encoding: 'utf8' }));
  // Baseline-only scaffolding: these are the original packaged launch expressions. The original
  // path has no directory creation. This probe changes neither that launcher nor the JVM.
  assert(source.includes('"-XX:ErrorFile={}/hs_err_pid%p.log"'));
  assert(source.includes('.arg(format!("-XX:HeapDumpPath={}/", crash_dir.to_string_lossy()))'));
  return [`-XX:ErrorFile=${profile}/crashes/hs_err_pid%p.log`, `-XX:HeapDumpPath=${profile}/crashes/`];
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function waitFor(fn, timeout = 45000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { const result = fn(); if (result) return result; await sleep(50); }
  throw new Error('Engine/probe deadline elapsed');
}
function launch(profile, flags = [], heap = '256m') {
  const args = [`-Xmx${heap}`, ...options(profile), '-XX:+HeapDumpOnOutOfMemoryError', '-XX:+ExitOnOutOfMemoryError',
    '-XX:-CreateCoredumpOnCrash', '--enable-native-access=ALL-UNNAMED',
    `-Djustsearch.data.dir=${profile}`, '-Djustsearch.mode=mcp-lite', '-Djustsearch.prod=true',
    ...flags, '-cp', path.join(dist, 'lib/*'), 'io.justsearch.ui.HeadlessApp'];
  const child = spawn(java, args, { cwd: profile, env: { ...process.env, JUSTSEARCH_HOME: profile, JUSTSEARCH_DATA_DIR: profile, JUSTSEARCH_API_PORT: '0' }, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  child.stdout.on('data', b => { output += b; }); child.stderr.on('data', b => { output += b; });
  const done = new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', (code, signal) => resolve({ code, signal })); });
  return { child, done, output: () => output };
}
async function birth(run) {
  return waitFor(() => {
    if (run.child.exitCode !== null) throw new Error(run.output().slice(-2000));
    const seen = readLinuxProcess(run.child.pid);
    return seen?.present ? seen.processIdentity : null;
  }, 5000);
}
function signal(identity, name) { const result = signalLinuxProcess(identity, name); assert(result.ok || result.outcome === 'mismatch', JSON.stringify(result)); }
async function nativeEvidence() {
  const profile = path.join(work, 'Native Profile With Spaces'); fs.mkdirSync(profile);
  assert(!fs.existsSync(path.join(profile, 'crashes')));
  let first, bytes;
  for (let i = 0; i < 4; i++) {
    const run = launch(profile); const identity = await birth(run);
    try {
      await waitFor(() => {
        if (run.child.exitCode !== null || run.child.signalCode !== null) throw new Error(run.output().slice(-3000));
        try { return JSON.parse(fs.readFileSync(path.join(profile, 'runtime/manifest.json'))).pid === run.child.pid; } catch { return false; }
      });
      signal(identity, 'SIGSEGV'); const result = await run.done;
      fs.writeFileSync(path.join(work, `native-${i}.txt`), run.output());
      const reports = fs.existsSync(path.join(profile, 'crashes')) ? fs.readdirSync(path.join(profile, 'crashes')).filter(name => name.startsWith(`hs_err_pid${run.child.pid}`) && name.endsWith('.log')) : [];
      assert.equal(reports.length, 1, 'native Engine fatal log is missing from fresh profile/crashes');
      const report = path.join(profile, 'crashes', reports[0]);
      console.log(JSON.stringify({ check: 'E1-native', pid: run.child.pid, result, report: reports[0], bytes: fs.statSync(report).size }));
      assert.match(fs.readFileSync(report, 'utf8'), /SIGSEGV/);
      if (!first) { first = report; bytes = fs.readFileSync(first); }
      assert.deepEqual(fs.readFileSync(first), bytes, 'the first failure must survive repeated crashes');
    } finally { if (run.child.exitCode === null && run.child.signalCode === null) { signal(identity, 'SIGKILL'); await run.done; } }
  }
  // A new options command is a fresh shell process; relaunch the Engine against the same data.
  const run = launch(profile); const identity = await birth(run);
  try {
    await waitFor(() => { try { return JSON.parse(fs.readFileSync(path.join(profile, 'runtime/manifest.json'))).pid === run.child.pid; } catch { return false; } });
    assert.deepEqual(fs.readFileSync(first), bytes, 'an app relaunch must preserve the first fatal log');
    signal(identity, 'SIGTERM');
    await Promise.race([run.done, sleep(10000)]);
  } finally { if (run.child.exitCode === null && run.child.signalCode === null) { signal(identity, 'SIGKILL'); await run.done; } }
  console.log('PASS E1: four real Engine native crashes and a relaunch keep the first attributable fatal log in crashes/ (profile path has spaces)');
}
async function heapEvidence() {
  const agent = path.join(work, 'agent'); fs.mkdirSync(agent);
  fs.writeFileSync(path.join(agent, 'OomProbe.java'), 'public class OomProbe { public static void premain(String args) { var live = new java.util.ArrayList<byte[]>(); while (true) live.add(new byte[4*1024*1024]); } }');
  execFileSync(path.join(process.env.JAVA_HOME, 'bin/javac'), [path.join(agent, 'OomProbe.java')]);
  fs.writeFileSync(path.join(agent, 'MANIFEST.MF'), 'Premain-Class: OomProbe\n\n');
  const jar = path.join(agent, 'oom.jar');
  execFileSync(path.join(process.env.JAVA_HOME, 'bin/jar'), ['--create', '--file', jar, '--manifest', path.join(agent, 'MANIFEST.MF'), '-C', agent, 'OomProbe.class']);
  const profile = path.join(work, 'Heap Profile With Spaces'); fs.mkdirSync(profile);
  const run = launch(profile, [`-javaagent:${jar}`], '32m'); const identity = await birth(run);
  try {
    const result = await Promise.race([run.done, sleep(20000).then(() => { throw new Error('OOM deadline elapsed'); })]);
    fs.writeFileSync(path.join(work, 'heap.txt'), run.output());
    assert.equal(result.code, 3, run.output());
    const dumps = fs.existsSync(path.join(profile, 'crashes')) ? fs.readdirSync(path.join(profile, 'crashes')).filter(name => name.endsWith('.hprof')) : [];
    assert.equal(dumps.length, 1, 'the Engine JVM flags must leave the promised heap dump in crashes/');
    const dump = path.join(profile, 'crashes', dumps[0]);
    assert(fs.statSync(dump).size > 1024);
    const fd = fs.openSync(dump, 'r'); const header = Buffer.alloc(12); fs.readSync(fd, header, 0, 12, 0); fs.closeSync(fd);
    assert.equal(header.toString(), 'JAVA PROFILE');
    console.log(`PASS E1: Engine JVM heap OOM exits 3 and leaves ${dumps[0]} (HPROF; never auto-exported)`);
  } finally { if (run.child.exitCode === null && run.child.signalCode === null) { signal(identity, 'SIGKILL'); await run.done; } }
}
let failures = 0;
try {
  for (const check of [nativeEvidence, heapEvidence]) { try { await check(); } catch (error) { failures++; console.error(`FAIL E1: ${error.stack}`); } }
} finally { fs.rmSync(work, { recursive: true, force: true }); }
console.log(`JVM evidence ${baseline ? 'baseline (original inline launch flags)' : 'candidate (production jvm_options)'}: ${failures} failure(s); Engine distribution ${dist}`);
process.exitCode = failures ? 1 : 0;
