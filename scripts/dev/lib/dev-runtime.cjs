'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { nativeTarget } = require('../../lib/native-targets.cjs');

function resolveMainRepoRoot(repoRoot) {
  const gitPath = path.join(repoRoot, '.git');
  try {
    const stat = fs.statSync(gitPath);
    if (stat.isFile()) {
      const content = fs.readFileSync(gitPath, 'utf8').trim();
      const match = content.match(/^gitdir:\s*(.+)$/);
      if (match) {
        const gitDir = path.resolve(repoRoot, match[1]);
        return path.resolve(gitDir, '..', '..', '..');
      }
    }
  } catch { /* not a worktree or no .git — fall through */ }
  return repoRoot;
}


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
  const configured = env.JUSTSEARCH_SERVER_EXE;
  if (configured && executableFile(configured, platform)) return { path: configured, valid: true, source: 'override' };
  if (configured) {
    try {
      fs.statSync(configured);
      return { path: configured, valid: false, source: 'override', reason: 'Configured server executable must name a regular executable file' };
    } catch (error) {
      if (!['ENOENT', 'ENOTDIR'].includes(error.code)) return { path: configured, valid: false, source: 'override',
        reason: `Configured server executable could not be inspected: ${configured}` };
    }
  }
  if (configured && !nativeTarget(platform).capabilities.cpuBaselineRuntime) return {
    path: configured, valid: false, source: 'missing', reason: `Configured server executable is missing: ${configured}` };

  // cuda12 is the existing Windows runtime variant, never a Linux or CPU fallback.
  if (!nativeTarget(platform).capabilities.runtimePacks) return { path: null, valid: false, source: 'missing' };
  const candidate = resolveCuda12ServerExe(repoRoot, mainRepoRoot, nativeTarget(platform).executables['llama-server']);
  return { path: candidate ?? null, valid: Boolean(candidate), source: candidate ? 'cuda12' : 'missing' };
}
module.exports = { resolveMainRepoRoot, executableFile, resolveDevRuntime, resolveCuda12ServerExe };
