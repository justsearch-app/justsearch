'use strict';
/**
 * A dev tool cleans only a data folder it created and marked itself.
 *
 * dev-all and dev-runner remove most of a data folder's contents at start (a soft or hard clean).
 * Pointed at the wrong folder, through a typo or a person's own data, that would delete what no
 * tool can restore, so a folder is cleaned only when it carries MARKER, a file naming the tool and
 * the time it was created. Never delete on uncertainty:
 *   - a folder that does not exist is created and marked;
 *   - an empty folder is marked and used;
 *   - the default dev folder (modules/ui-web/.dev-data) without a marker, from runs before the
 *     marker existed, is adopted (marked) only when it holds nothing but known dev-data contents;
 *   - any other folder without the marker is refused, and nothing in it is changed.
 */
const fs = require('node:fs');
const path = require('node:path');

const MARKER = '.justsearch-dev-data.json';
const MARKER_SCHEMA = 'justsearch-dev-data.v1';

/**
 * Top-level names a dev data folder holds: what the Engine writes in its data and home folder,
 * beside every store the recoverability register names (read when given). A name outside this set
 * makes an unmarked default folder something other than ours.
 */
const KNOWN_NAMES = Object.freeze([
  MARKER, 'config', 'index', 'ui', 'runtime', 'logs', 'temp', 'cache', 'telemetry', 'crashes', 'diagnostics',
  'audit', 'feedback', 'feedback-capture.json', 'file-operations', 'memories', 'conversations', 'agent-runs',
  'agent-history', 'plugins', 'upgrade', 'encryption', 'fixtures', 'models', 'native-bin', 'ai',
  'watched_roots.json', 'watched-roots.json', 'installed-packs.v1.json', 'policy.v1.json', 'install-attempts.json',
  'install-contract.v2.json', 'pack-import-state.json', 'data-version.json', 'inference-model-id.txt',
  'worker-fatal-reason', '.help-ingested-version', 'gpl-training-triples.ndjson', 'gpl-eval-snapshot.json',
  'gpl-stage3a-analysis.json', 'gpl-stage3b-branch-fusion.json', 'entity-clusters.db', 'jobs.db', 'jobs.db.bak',
  'operations.db', 'slowapi', 'tmp', 'install-state.json', 'lambdamart-model.txt', 'reload-build-stamp.txt',
]);
const KNOWN_PATTERNS = Object.freeze([
  /^[^\\/]+\.lock$/i, // app.lock, worker_signal.lock
  /^(entity-clusters|jobs|operations)\.db-(shm|wal)$/i,
  /^operations\.db\.corrupt-[^\\/]+$/i,
]);

/** The register's top-level names (globs in the first segment skipped); empty when unreadable. */
function registerNames(registerPath) {
  const names = new Set();
  if (!registerPath) return names;
  try {
    const register = JSON.parse(fs.readFileSync(registerPath, 'utf8'));
    for (const store of register.durableStores || []) {
      for (const owned of store.ownedPaths || []) {
        const head = String(owned).split(/[\\/]/)[0];
        if (head && !head.includes('*')) names.add(head);
      }
    }
  } catch { /* the hand list stands alone */ }
  return names;
}

function isKnownName(name, extra) {
  return KNOWN_NAMES.includes(name) || extra.has(name) || KNOWN_PATTERNS.some((re) => re.test(name));
}

function readMarker(dir) {
  try {
    const marker = JSON.parse(fs.readFileSync(path.join(dir, MARKER), 'utf8'));
    return marker?.schema === MARKER_SCHEMA && typeof marker.tool === 'string' ? marker : null;
  } catch {
    return null;
  }
}

/**
 * What a clean of `dir` may do, without changing anything.
 * @returns {{ ok: true, action: 'create'|'owned'|'mark-empty'|'adopt', marker?: object }
 *   | { ok: false, reason: string, unknown?: string[], message: string }}
 */
function inspectDevDataFolder(dir, { isDefault = false, registerPath = null, defaultHint = null } = {}) {
  const shown = path.resolve(dir);
  const refuse = (reason, detail, unknown) => ({
    ok: false, reason, ...(unknown ? { unknown } : {}),
    message: `Refusing to clean ${shown}: ${detail}; nothing in it was changed. A dev tool cleans only a data folder `
      + `it created and marked (${MARKER}). Pass a new or empty folder`
      + `${defaultHint ? `, or omit it to use the default dev folder (${defaultHint})` : ''}.`,
  });
  let stat;
  try {
    stat = fs.lstatSync(dir);
  } catch (err) {
    if (err?.code === 'ENOENT') return { ok: true, action: 'create' };
    return refuse('unreadable', `it could not be read (${err?.code ?? err?.message ?? err})`);
  }
  if (stat.isSymbolicLink()) return refuse('link', 'it is a link, not a folder this tool created');
  if (!stat.isDirectory()) return refuse('not-a-folder', 'it is not a folder');
  const marker = readMarker(dir);
  if (marker) return { ok: true, action: 'owned', marker };
  let entries;
  try {
    entries = fs.readdirSync(dir);
  } catch (err) {
    return refuse('unreadable', `it could not be listed (${err?.code ?? err?.message ?? err})`);
  }
  if (entries.length === 0) return { ok: true, action: 'mark-empty' };
  if (isDefault) {
    const extra = registerNames(registerPath);
    const unknown = entries.filter((name) => !isKnownName(name, extra));
    if (unknown.length === 0) return { ok: true, action: 'adopt' };
    return refuse('unmarked-unknown-contents', `it has no ${MARKER} and holds what a dev data folder does not `
      + `(${unknown.slice(0, 5).join(', ')}${unknown.length > 5 ? ', ...' : ''})`, unknown);
  }
  return refuse('unmarked-not-empty', `it is not empty and has no ${MARKER}`);
}

/**
 * Makes `dir` this tool's own when inspectDevDataFolder allows it: creates and marks a missing
 * folder, marks an empty or adoptable one. Returns the verdict; a refusal changes nothing.
 */
function claimDevDataFolder(dir, { tool, isDefault = false, registerPath = null, defaultHint = null, now = () => new Date() } = {}) {
  if (!tool) throw new TypeError('tool is required');
  const verdict = inspectDevDataFolder(dir, { isDefault, registerPath, defaultHint });
  if (!verdict.ok || verdict.action === 'owned') return verdict;
  if (verdict.action === 'create') fs.mkdirSync(dir, { recursive: true });
  const marker = { schema: MARKER_SCHEMA, tool, createdAt: now().toISOString(),
    ...(verdict.action === 'adopt' ? { adopted: true } : {}) };
  // Exclusive create: a folder another start marked meanwhile keeps its own marker.
  try {
    fs.writeFileSync(path.join(dir, MARKER), `${JSON.stringify(marker, null, 2)}\n`, { flag: 'wx' });
  } catch (err) {
    if (err?.code !== 'EEXIST') throw err;
    const existing = readMarker(dir);
    return existing ? { ok: true, action: 'owned', marker: existing }
      : inspectDevDataFolder(dir, { isDefault, registerPath, defaultHint });
  }
  return { ...verdict, marker };
}

module.exports = { MARKER, MARKER_SCHEMA, KNOWN_NAMES, inspectDevDataFolder, claimDevDataFolder, readMarker };
