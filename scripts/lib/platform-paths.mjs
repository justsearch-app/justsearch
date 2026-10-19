/**
 * JustSearch platform-paths — Node implementation of the
 * `contracts/platform-paths/spec.v1.json` contract (tempdoc 501 §3.6).
 *
 * This module is the single Node source of truth for resolving the
 * JustSearch data directory. It replaces the drifting copies that
 * previously lived in `modules/ui-web/vite.config.js`,
 * `scripts/dev/dev-runner.cjs`, and `scripts/prod/justsearch-mcp/discovery.mjs`.
 *
 * The Java reference is `modules/configuration/.../PlatformPaths.java`.
 * The cross-language `PlatformPathsContractTest` feeds the same fixture set
 * into every implementation and asserts identical outputs.
 *
 * The contract's `discovery.desktopAppDataDir` (the desktop app's data folder) is implemented in
 * `packaging/mcpb/server/engine-discovery.cjs`, which ships inside the MCPB bundle.
 *
 * Node has no JVM system properties — sysprop-class sources in the spec are
 * skipped here. The env-var + platform-default branches are authoritative
 * for the Node side.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SPEC_PATH = path.resolve(__dirname, '..', '..', 'contracts', 'platform-paths', 'spec.v1.json');

/** Lazily-loaded contract spec (for callers wanting to introspect or test). */
let _spec = null;
export function loadSpec() {
  if (_spec === null) {
    _spec = JSON.parse(fs.readFileSync(SPEC_PATH, 'utf8'));
  }
  return _spec;
}

/**
 * Replace literal `${user.home}` placeholders. Returns the input unchanged if
 * no placeholder is present. Refuses to leave unexpanded placeholders.
 */
export function expandUserHomePlaceholders(raw, userHomeOverride = null) {
  if (raw == null || raw === '') return raw;
  if (!raw.includes('${user.home}')) return raw;
  const userHome = userHomeOverride ?? os.homedir();
  if (!userHome) return raw;
  return raw.replace(/\$\{user\.home\}/g, userHome);
}

/** Detect the canonical platform key used by the contract spec. */
export function detectPlatform(platformOverride = null) {
  const p = platformOverride ?? process.platform;
  // Already a contract-spec name (passed from a fixture/test) — return as-is.
  if (p === 'windows' || p === 'macos' || p === 'linux') return p;
  // node process.platform aliases.
  if (p === 'win32') return 'windows';
  if (p === 'darwin') return 'macos';
  return 'linux';
}

/**
 * Resolve the data dir following `contracts/platform-paths/spec.v1.json`.
 *
 * Accepts an optional override bundle for testing — pass {env, userHome,
 * platform} to feed the contract fixtures into this function without
 * touching the actual process environment.
 */
export function resolveDataDir({env = process.env, userHome = null, platform = null} = {}) {
  const home = userHome ?? os.homedir();
  const plat = detectPlatform(platform);

  // 1. Env-var sources (in spec order). sysprop sources are JVM-only.
  const envValue = env['JUSTSEARCH_DATA_DIR'];
  if (envValue && envValue.trim()) {
    return expandUserHomePlaceholders(envValue.trim(), home);
  }

  // 2. Platform default.
  if (plat === 'windows') {
    const localAppData = env['LOCALAPPDATA'];
    if (localAppData && localAppData.trim()) {
      return path.join(localAppData, 'JustSearch');
    }
    return path.join(home, 'AppData', 'Local', 'JustSearch');
  }
  if (plat === 'macos') {
    return path.join(home, 'Library', 'Application Support', 'JustSearch');
  }
  return path.join(home, '.justsearch');
}

/** Convenience: data dir resolved against the live process environment. */
export function resolveDataDirLive() {
  return resolveDataDir();
}

/** Convenience for the manifest readers: `<dataDir>/runtime/manifest.json`. */
export function resolveManifestPath(dataDir = null) {
  const d = dataDir ?? resolveDataDirLive();
  return path.join(d, 'runtime', 'manifest.json');
}

/**
 * Convenience: read the runtime manifest from the resolved data directory,
 * returning the parsed JSON or null if the file is absent / malformed.
 */
export function readManifestSync(dataDir = null) {
  try {
    const content = fs.readFileSync(resolveManifestPath(dataDir), 'utf8');
    return JSON.parse(content);
  } catch {
    return null;
  }
}

/**
 * Check if a PID is alive on the current OS. Returns true if alive, false if
 * dead, null if we can't tell (lacking permissions etc.).
 *
 * Implementation: process.kill(pid, 0) throws ESRCH for dead, EPERM for alive
 * without permission, no-throw for alive-with-permission. We treat the no-perm
 * case as alive (safer assumption: don't claim a stale manifest is fresh).
 */
export function isPidAlive(pid) {
  if (typeof pid !== 'number' || !Number.isFinite(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    if (e.code === 'ESRCH') return false;
    if (e.code === 'EPERM') return true;
    return null;
  }
}

/**
 * Dev convention: walk up from cwd looking for `modules/ui-web/.dev-data` or `.dev-data` at the
 * repo root (at most 8 levels).
 */
export function devDataDirCandidates(cwd = process.cwd()) {
  const out = [];
  let walk = path.resolve(cwd);
  for (let i = 0; i < 8; i++) {
    out.push(path.join(walk, 'modules', 'ui-web', '.dev-data'));
    out.push(path.join(walk, '.dev-data'));
    const parent = path.dirname(walk);
    if (parent === walk) break;
    walk = parent;
  }
  return out;
}

/**
 * Find a running JustSearch backend by walking a prioritized list of
 * candidate data directories, reading each one's runtime manifest, and
 * returning the first manifest whose `pid` is alive on this OS.
 *
 * Why a candidate list rather than the single resolveDataDir result: under
 * bare `npm run dev` (no orchestrator-injected JUSTSEARCH_DATA_DIR) the
 * canonical resolver falls through to the platform default, which is
 * usually a stale production install. Dev backends bind to a worktree-local
 * `.dev-data` instead. The resolver's algorithm is correct; the candidate
 * list extends it with dev conventions documented in this function.
 *
 * @param {object} [opts]
 * @param {string} [opts.cwd] — starting point for worktree/repo-root probing
 * @param {string[]} [opts.extraDataDirs] — caller-supplied data dirs probed
 *   FIRST (e.g., a Vite worktree's `modules/ui-web/.dev-data`).
 * @returns {{manifest: object, dataDir: string} | null}
 */
export function findRunningManifest({cwd = process.cwd(), extraDataDirs = []} = {}) {
  const candidates = [];

  for (const d of extraDataDirs) {
    if (d) candidates.push(d);
  }

  if (process.env.JUSTSEARCH_DATA_DIR && process.env.JUSTSEARCH_DATA_DIR.trim()) {
    candidates.push(process.env.JUSTSEARCH_DATA_DIR.trim());
  }

  candidates.push(...devDataDirCandidates(cwd));

  // Platform default (covers production installs).
  candidates.push(resolveDataDir());

  const tried = [];
  for (const dataDir of candidates) {
    const manifest = readManifestSync(dataDir);
    if (!manifest) {
      tried.push({dataDir, status: 'no-manifest'});
      continue;
    }
    const alive = isPidAlive(manifest.pid);
    if (alive === false) {
      tried.push({dataDir, status: 'stale-pid', pid: manifest.pid});
      continue;
    }
    return {manifest, dataDir};
  }
  return null;
}
