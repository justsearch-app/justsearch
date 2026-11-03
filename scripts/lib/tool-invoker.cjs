#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');
const ROOT = path.resolve(__dirname, '../..');

function resolveNpmInvocation({ platform = process.platform, execPath = process.execPath, env = process.env } = {}) {
  const candidates = [env.npm_execpath, path.join(path.dirname(execPath), 'node_modules/npm/bin/npm-cli.js')].filter(Boolean);
  const npmCli = candidates.find(candidate => fs.existsSync(candidate));
  if (npmCli) return { command: execPath, prefixArgs: [npmCli] };
  if (platform === 'win32') throw new Error('npm CLI could not be located beside Node; reinstall Node with npm included.');
  return { command: 'npm', prefixArgs: [] };
}

function toolInvocation(tool, args = [], { repoRoot = ROOT, platform = process.platform, env = process.env, execPath = process.execPath } = {}) {
  if (tool === 'npm') {
    const npm = resolveNpmInvocation({ platform, env, execPath });
    return { command: npm.command, args: [...npm.prefixArgs, ...args], shell: false };
  }
  if (tool !== 'gradle') throw new Error(`Unknown tool: ${tool}`);
  const wrapper = path.join(repoRoot, platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  return { command: wrapper, args, shell: false };
}

// cmd.exe parses metacharacters before the batch file forwards its arguments. Protect that
// parse separately from the executable's backslash/quote argument grammar. No npm install is
// available at this layer (prepare-worktree and the installer hooks call us during bootstrap).
function quoteCmdArgument(value) {
  const raw = String(value);
  if (/[\0\r\n]/.test(raw)) throw new Error('cmd.exe arguments cannot contain NUL or line breaks');
  const quoted = '"' + raw.replace(/(\\*)"/g, '$1$1\\"').replace(/(\\+)$/g, '$1$1') + '"';
  return quoted.replace(/([()\][%!^"`<>&|;, *?])/g, '^$1');
}

function windowsBatchInvocation(command, args, env = process.env) {
  const escapedCommand = String(command).replace(/([()\][%!^"`<>&|;, *?])/g, '^$1');
  const line = [escapedCommand, ...args.map(quoteCmdArgument)].join(' ');
  return { command: env.ComSpec || env.COMSPEC || 'cmd.exe', args: ['/d', '/v:off', '/s', '/c', `"${line}"`],
    shell: false, windowsVerbatimArguments: true };
}

const ADMISSION = path.join(ROOT, 'scripts/dev/admission.py');
const warnUnavailable = reason => process.stderr.write(
  `[admission] machine admission unavailable (${reason}); continuing without admission\n`);

function invocationForSpawn(tool, args, options = {}) {
  const invocation = toolInvocation(tool, args, options);
  const platform = options.platform ?? process.platform;
  // Linux Gradle runs as heavy machine work. A missing wrapper is spawned directly so its own
  // ENOENT is reported; a missing admission tool or python3 runs Gradle without admission.
  if (tool === 'gradle' && platform === 'linux' && fs.existsSync(invocation.command)) {
    if (!fs.existsSync(ADMISSION)) {
      warnUnavailable(`${ADMISSION} is missing`);
      return invocation;
    }
    return { command: 'python3', args: ['-I', ADMISSION, 'heavy', '--purpose', 'Gradle', '--',
      invocation.command, ...invocation.args], shell: false, unadmitted: invocation };
  }
  return platform === 'win32' && /\.(cmd|bat)$/i.test(invocation.command)
    ? windowsBatchInvocation(invocation.command, invocation.args, options.env) : invocation;
}

// A pipe-scoped shared holder: readiness releases it; runner death closes the pipe too.
// The child emits the nesting token only after acquiring admission. Fail-open: if admission is
// unavailable the start proceeds with one warning. Release never throws: an abnormal holder exit
// is logged, so it cannot turn a successful start into a failed one or replace a start's error.
async function acquireHeavy(purpose) {
  const noop = async () => {};
  if (process.platform !== 'linux') return noop;
  if (!fs.existsSync(ADMISSION)) { warnUnavailable(`${ADMISSION} is missing`); return noop; }
  const child = spawn('python3', ['-I', ADMISSION, 'heavy', '--purpose', purpose, '--', 'python3', '-I', '-u', '-c',
    'import os,sys; print(os.environ.get("JS_ADMISSION_TOKEN", ""), flush=True); sys.stdin.buffer.read()'],
  { stdio: ['pipe', 'pipe', 'inherit'], env: process.env });
  child.stdin.on('error', () => {});
  const ended = new Promise(resolve => {
    child.once('error', error => resolve({ error }));
    child.once('close', (code, signal) => resolve({ code, signal }));
  });
  const acquired = await new Promise(resolve => {
    let output = '';
    child.stdout.on('data', data => {
      output += data;
      if (output.includes('\n')) resolve({ token: output.split('\n')[0] });
    });
    ended.then(resolve);
  });
  if (!('token' in acquired)) {
    const { error, code, signal } = acquired;
    if (code === 75) throw new Error('machine admission refused: the exclusive gate stayed closed past its deadline');
    warnUnavailable(error ? `${error.message}` : `admission tool exited ${signal ?? code}`);
    return noop;
  }
  const previous = process.env.JS_ADMISSION_TOKEN;
  if (acquired.token) process.env.JS_ADMISSION_TOKEN = acquired.token;
  let released = false;
  let reported = false;
  const report = ({ error, code, signal }) => {
    if (reported || (!error && code === 0)) return;
    reported = true;
    process.stderr.write(`[admission] machine admission holder ended abnormally (${error?.message ?? signal ?? code})`
      + `${released ? '' : ' before release; the rest of this start runs without admission'}\n`);
  };
  ended.then(result => { if (!released) report(result); });
  return async () => {
    if (released) return;
    released = true;
    if (previous === undefined) delete process.env.JS_ADMISSION_TOKEN;
    else process.env.JS_ADMISSION_TOKEN = previous;
    child.stdin.end();
    report(await ended);
  };
}

function runToolSync(tool, args, options = {}) {
  const { command, args: argv, unadmitted, ...spawnOptions } = invocationForSpawn(tool, args, options);
  const result = spawnSync(command, argv, { windowsHide: true, ...options, ...spawnOptions });
  if (unadmitted && result.error?.code === 'ENOENT') {
    warnUnavailable('python3 not found');
    const { command: direct, args: directArgs, ...directOptions } = unadmitted;
    return spawnSync(direct, directArgs, { windowsHide: true, ...options, ...directOptions });
  }
  return result;
}

if (require.main === module) {
  const [tool, ...args] = process.argv.slice(2);
  try {
    const run = ({ command, args: argv, unadmitted, ...options }) => {
      const child = spawn(command, argv, { ...options, cwd: process.cwd(), env: process.env, stdio: 'inherit', windowsHide: true });
      const forward = signal => child.kill(signal);
      for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, forward);
      child.once('error', error => {
        for (const signal of ['SIGINT', 'SIGTERM']) process.off(signal, forward);
        if (unadmitted && error.code === 'ENOENT') { warnUnavailable('python3 not found'); run(unadmitted); return; }
        console.error(error.message); process.exitCode = 1;
      });
      child.once('exit', (code, signal) => {
        if (signal) { process.removeAllListeners(signal); process.kill(process.pid, signal); }
        else process.exitCode = code ?? 1;
      });
    };
    run(invocationForSpawn(tool, args));
  } catch (error) { console.error(error.message); process.exitCode = 2; }
}
module.exports = { resolveNpmInvocation, toolInvocation, runToolSync, quoteCmdArgument, windowsBatchInvocation, invocationForSpawn, acquireHeavy };
