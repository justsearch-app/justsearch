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

function runToolSync(tool, args, options = {}) {
  const invocation = toolInvocation(tool, args, options);
  const { command, args: argv, ...spawnOptions } = invocation;
  const run = (options.platform ?? process.platform) === 'win32' && /\.(cmd|bat)$/i.test(command)
    ? require('cross-spawn').sync : spawnSync;
  return run(command, argv, { windowsHide: true, ...options, ...spawnOptions });
}

if (require.main === module) {
  const [tool, ...args] = process.argv.slice(2);
  try {
    const { command, args: argv, ...options } = toolInvocation(tool, args);
    const run = process.platform === 'win32' && /\.(cmd|bat)$/i.test(command) ? require('cross-spawn') : spawn;
    const child = run(command, argv, { ...options, cwd: process.cwd(), env: process.env, stdio: 'inherit', windowsHide: true });
    for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
    child.once('error', error => { console.error(error.message); process.exitCode = 1; });
    child.once('exit', (code, signal) => {
      if (signal) { process.removeAllListeners(signal); process.kill(process.pid, signal); }
      else process.exitCode = code ?? 1;
    });
  } catch (error) { console.error(error.message); process.exitCode = 2; }
}
module.exports = { resolveNpmInvocation, toolInvocation, runToolSync };
