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

function invocationForSpawn(tool, args, options = {}) {
  const invocation = toolInvocation(tool, args, options);
  return (options.platform ?? process.platform) === 'win32' && /\.(cmd|bat)$/i.test(invocation.command)
    ? windowsBatchInvocation(invocation.command, invocation.args, options.env) : invocation;
}

function runToolSync(tool, args, options = {}) {
  const invocation = invocationForSpawn(tool, args, options);
  const { command, args: argv, ...spawnOptions } = invocation;
  return spawnSync(command, argv, { windowsHide: true, ...options, ...spawnOptions });
}

if (require.main === module) {
  const [tool, ...args] = process.argv.slice(2);
  try {
    const { command, args: argv, ...options } = invocationForSpawn(tool, args);
    const child = spawn(command, argv, { ...options, cwd: process.cwd(), env: process.env, stdio: 'inherit', windowsHide: true });
    for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
    child.once('error', error => { console.error(error.message); process.exitCode = 1; });
    child.once('exit', (code, signal) => {
      if (signal) { process.removeAllListeners(signal); process.kill(process.pid, signal); }
      else process.exitCode = code ?? 1;
    });
  } catch (error) { console.error(error.message); process.exitCode = 2; }
}
module.exports = { resolveNpmInvocation, toolInvocation, runToolSync, quoteCmdArgument, windowsBatchInvocation, invocationForSpawn };
