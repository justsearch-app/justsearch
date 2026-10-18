'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { nativeTarget } = require('../../lib/native-targets.cjs');

function executableFile(file, platform = process.platform) {
  try {
    if (!fs.statSync(file).isFile()) return false;
    fs.accessSync(file, platform === 'win32' ? fs.constants.F_OK : fs.constants.X_OK);
    return true;
  } catch { return false; }
}

/** Existing Windows cuda12 resolution: worktree first, then the shared checkout. */
function resolveCuda12ServerExe(worktreeRoot, sharedRoot, exeName) {
  const cuda12 = ['modules', 'ui', 'native-bin', 'llama-server', 'variants', 'cuda12', exeName];
  return [path.join(worktreeRoot, ...cuda12), path.join(sharedRoot, ...cuda12)]
    .find(file => fs.existsSync(file)) || null;
}

function resolveDevRuntime(repoRoot, mainRepoRoot, env = process.env, platform = process.platform) {
  if (env.JUSTSEARCH_SERVER_EXE) return { path: env.JUSTSEARCH_SERVER_EXE,
    valid: executableFile(env.JUSTSEARCH_SERVER_EXE, platform), source: 'override' };
  // cuda12 is the existing Windows runtime variant, never a Linux or CPU fallback.
  if (platform !== 'win32') return { path: null, valid: false, source: 'missing' };
  const candidate = resolveCuda12ServerExe(repoRoot, mainRepoRoot, nativeTarget(platform).executables['llama-server']);
  return { path: candidate ?? null, valid: Boolean(candidate), source: candidate ? 'cuda12' : 'missing' };
}
module.exports = { executableFile, resolveDevRuntime, resolveCuda12ServerExe };
