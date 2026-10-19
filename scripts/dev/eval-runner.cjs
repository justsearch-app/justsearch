#!/usr/bin/env node
'use strict';
const path = require('node:path');
const { once } = require('node:events');
const job = require('./lib/job-run.cjs');
const { engineJavaLaunch } = require('./lib/engine-java-launch.cjs');
const { evalLaunch } = require('./lib/eval-launch.cjs');
const { resolveJdkHome } = require('./lib/resolve-jdk.cjs');
const { writeShutdownRequestFileSync } = require('./lib/engine-actuator.cjs');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../..');
let run = null, stopping = false;
const incarnation = `eval-${crypto.randomUUID()}/1`;
const launch = evalLaunch(root);
const env = { ...process.env, ...launch.environment, JAVA_HOME: resolveJdkHome(), JUSTSEARCH_ENGINE_INCARNATION: incarnation };
let finished = false;

async function stop() {
  if (stopping || !run) return;
  stopping = true;
  let timer;
  try {
    const data = launch.environment.JUSTSEARCH_DATA_DIR;
    writeShutdownRequestFileSync(data, { reason: 'QUIT', deadlineEpochMs: Date.now() + 6000, incarnation });
    await Promise.race([once(run.child, 'exit'), new Promise(resolve => { timer = setTimeout(resolve, 6500); })]);
  } catch (error) { console.error(`Eval cooperative shutdown unavailable: ${error.message}`); }
  finally { clearTimeout(timer); }
  try {
    const result = job.settleRunRecord(run);
    if (!result.ok) throw new Error(`Eval cleanup incomplete: ${JSON.stringify(result)}`);
  } catch (error) {
    console.error(error.stack);
    // End our own lifetime, which closes the child binding; report failure so Python keeps the
    // registration. An unreadable identity must never produce a successful cleanup verdict.
    process.exit(1);
  }

}

for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => { void stop(); });
process.stdin.setEncoding('utf8');
process.stdin.on('data', text => { if (text.includes('stop')) void stop(); });
// A closed Python control pipe requests an ordered stop too; owner SIGKILL is covered by bound-exec.
process.stdin.on('end', () => { if (!finished) void stop(); });

async function main() {
  const startScript = path.join(root, 'modules/ui/build/install/ui/bin', process.platform === 'win32' ? 'ui.bat' : 'ui');
  const engine = engineJavaLaunch({ startScript, javaHome: env.JAVA_HOME, javaOpts: process.env.JAVA_OPTS, uiOpts: process.env.UI_OPTS });
  // Keep exact JVM args as argv: whitespace in data/model paths never passes through a shell parser.
  const index = engine.args.indexOf('-classpath');
  engine.args.splice(index, 0, ...launch.jvmArgs);
  run = job.startInJob({ ...engine, cwd: root, env, stdio: ['ignore', 'inherit', 'inherit'], label: 'eval-engine', holdAfterRoot: true });
  if (process.platform === 'win32') {
    const record = job.waitForRootSync(run);
    if (!record) throw new Error('Eval Engine root identity unavailable');
    run.root = record.root;
  }
  const [exitCode] = await once(run.child, 'exit');
  const result = job.settleRunRecord(run);
  finished = true;
  if (!result.ok) throw new Error(`Eval cleanup incomplete: ${JSON.stringify(result)}`);
  process.exitCode = stopping ? (process.exitCode ?? 0) : exitCode ?? 1;
  process.stdin.destroy();
}
main().catch(async error => { console.error(error.stack); await stop(); process.stdin.destroy(); process.exitCode = 1; });
