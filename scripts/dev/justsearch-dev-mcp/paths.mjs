import fs from 'node:fs';
import path from 'node:path';

function fileExists(p) {
  try {
    return fs.existsSync(p);
  } catch {
    return false;
  }
}

function normalizeForCompare(p) {
  const abs = path.resolve(p);
  return process.platform === 'win32' ? abs.toLowerCase() : abs;
}

function ensureUnderRepo(repoRoot, absolutePath, label = 'path') {
  const root = normalizeForCompare(repoRoot);
  const target = normalizeForCompare(absolutePath);
  const rootWithSep = root.endsWith(path.sep) ? root : root + path.sep;
  if (target === root) {
    throw new Error(`${label} must not be the repo root`);
  }
  if (!target.startsWith(rootWithSep)) {
    throw new Error(`${label} must be under repoRoot. repoRoot=${repoRoot} path=${absolutePath}`);
  }
}

export function resolveRepoRoot() {
  const env = process.env.JUSTSEARCH_DEV_MCP_REPO_ROOT
    || process.env.JUSTSEARCH_REPO_ROOT
    || process.env.justsearch_repo_root;
  const repoRoot = env ? path.resolve(process.cwd(), env) : process.cwd();

  const devRunnerPath = path.join(repoRoot, 'scripts', 'dev', 'dev-runner.cjs');
  if (!fileExists(devRunnerPath)) {
    throw new Error(
      `Repo root not found (missing scripts/dev/dev-runner.cjs). ` +
        `Set JUSTSEARCH_DEV_MCP_REPO_ROOT/JUSTSEARCH_REPO_ROOT or run from repo root. Looked at: ${devRunnerPath}`,
    );
  }

  return { repoRoot, devRunnerPath };
}

/**
 * Resolve a caller-provided path-like string and ensure it stays under repoRoot.
 * Returns a repo-relative path (platform-native separators).
 */
export function resolveUnderRepo(repoRoot, pathLike, label = 'path') {
  const raw = String(pathLike || '').trim();
  if (!raw) throw new Error(`${label} must be a non-empty string`);

  const abs = path.isAbsolute(raw) ? path.resolve(raw) : path.resolve(repoRoot, raw);
  ensureUnderRepo(repoRoot, abs, label);

  const rel = path.relative(repoRoot, abs);
  if (!rel || rel === '.' || rel.startsWith('..') || path.isAbsolute(rel)) {
    throw new Error(`${label} must resolve under repoRoot. repoRoot=${repoRoot} path=${raw}`);
  }

  return rel;
}

/**
 * Resolve the main repo root from a worktree-local repoRoot.
 * In git worktrees, `.git` is a file containing `gitdir: <path>` pointing
 * to `<mainRepo>/.git/worktrees/<name>`. Walk up 3 levels to find main repo.
 */
export function resolveMainRepoRoot(repoRoot) {
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

/**
 * The dev-runner state root this server coordinates on: the shared main-checkout location, or the
 * isolated `JUSTSEARCH_DEV_RUNNER_STATE_ROOT` (tests, throwaway stacks) - the same rule as
 * `dev-runner.cjs`. Owner touch records live under it.
 */
export function resolveDevRunnerStateRoot(mainRepoRoot, env = process.env) {
  return env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT
    ? path.resolve(env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT)
    : path.resolve(mainRepoRoot, 'tmp', 'dev-runner');
}

export function ensureLoopbackUrl(urlStr, label = 'url') {
  let u;
  try {
    u = new URL(String(urlStr));
  } catch {
    throw new Error(`${label} must be a valid URL: ${urlStr}`);
  }

  if (u.protocol !== 'http:') {
    throw new Error(`${label} must use http: (loopback-only). got=${u.protocol}`);
  }
  if (u.username || u.password) {
    throw new Error(`${label} must not include credentials`);
  }

  const host = String(u.hostname || '').toLowerCase();
  if (!(host === '127.0.0.1' || host === 'localhost' || host === '::1')) {
    throw new Error(`${label} must be loopback-only. host=${u.hostname}`);
  }
  return u;
}

