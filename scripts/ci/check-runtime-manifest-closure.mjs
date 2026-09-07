#!/usr/bin/env node
/**
 * Tempdoc 501 §6 — closure-rule mechanical enforcement.
 *
 * The runtime manifest design constrains every future answer to "how does
 * non-JVM consumer C find runtime fact F?" to exactly one form: "F is a
 * field on the manifest at <dataDir>/runtime/manifest.json (or the HTTP
 * endpoint), scoped to the current instanceId." The convention is
 * load-bearing — without it the design's headline pitch dissolves into
 * "we built a thing alongside the other things."
 *
 * This script makes the convention mechanical:
 *
 *   1. It enumerates every code path that resolves to a write into
 *      <dataDir>/runtime/. The producer's writers are an allowlist; new
 *      writers must be added here AND reviewed against the design.
 *   2. It greps for new sibling-file patterns (literal "runtime/<name>.txt"
 *      / ".json" / ".lock" path components in source) and flags any that
 *      aren't on the allowlist.
 *
 * Exit non-zero on any violation. Wire into pre-commit / CI to close the
 * door §6 promises is closed.
 *
 * Allowlist additions must include a `tempdoc-ref:` comment justifying
 * the new sibling file (e.g., "supersedes manifest field X" or "different
 * scope per Appendix Y"). This is a paper trail, not a workaround.
 */

import {execSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..', '..');

/**
 * Files inside <dataDir>/runtime/ whose existence is sanctioned by the
 * tempdoc 501 design. Each entry must reference the canonical authority
 * (tempdoc section, ADR, or named carve-out) so the reader can trace why.
 */
const ALLOWED_RUNTIME_ARTIFACTS = new Map([
  ['manifest.json', 'tempdoc 501 §3.5 — canonical runtime manifest (filesystem transport)'],
  ['manifest.json.tmp', 'tempdoc 501 — atomic-rename staging file for manifest.json writes'],
  // Tempdoc 501 Phase 18 (2026-05-21) removed api-port.txt outright. All consumers
  // (Vite proxy, dev-runner, prod MCP, integration test harness, sidecar smoke)
  // now read manifest.json. The entry is no longer on this allowlist.
  ['worker-config-snapshot.json', 'tempdoc 501 §5 carve-out — Head→Worker config passing (-Djustsearch.worker.config_snapshot), not an external discovery surface'],
  ['instances', 'tempdoc 501 §3.7 — per-instance history directory (mirror of tmp/dev-runner/runs/)'],
]);

/**
 * Source globs to scan. Tests and tempdocs are excluded — the closure rule
 * gates production code, not commentary about it.
 */
const SCAN_GLOBS = [
  'modules/**/src/main/**/*.java',
  'modules/**/src/main/**/*.kt',
  'modules/ui-web/src/**/*.{ts,tsx,js,mjs}',
  'modules/shell/src-tauri/src/**/*.rs',
  'scripts/**/*.{cjs,mjs,js}',
];

const SKIP_PATHS = [
  // The producer (HeadlessApp) intentionally writes runtime artifacts; the
  // publisher owns the canonical set.
  'modules/ui/src/main/java/io/justsearch/ui/runtime/RuntimeManifestPublisher.java',
  // HeadlessApp no longer writes api-port.txt at all (tempdoc 501 Phase 18
  // removed the mirror outright); its comments merely document that removal,
  // so the literal string still appears here for historical context only.
  'modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java',
  // The dev-runner writes nothing into <dataDir>/runtime/ — it observes —
  // but the path strings live there for cleanup. Permit by file name.
  'scripts/dev/dev-runner.cjs',
  // Vite/Node consumers read manifest.json by name; that's not creating new
  // sibling files. The shared platform-paths module is the canonical reader.
  'modules/ui-web/vite.config.js',
  'scripts/lib/platform-paths.mjs',
  'scripts/prod/justsearch-mcp/discovery.mjs',
  // Tauri shell reads manifest.json by name.
  'modules/shell/src-tauri/src/lib.rs',
  // This script itself.
  'scripts/ci/check-runtime-manifest-closure.mjs',
];

// Patterns the check fires on. Each must be SPECIFIC to "constructing a
// dataDir-relative path with `runtime` as a segment", not arbitrary string
// mentions of "runtime/" (HTTP routes, test directory names, package-lock
// noise all use that substring). The capture group must end in a known
// artifact-class extension (.json, .txt, .lock, .log, .ndjson) or be the
// literal `instances` directory.
const ARTIFACT_TAIL = '(?:[A-Za-z0-9._-]+\\.(?:json|txt|lock|log|ndjson)|instances)';
const PATTERNS = [
  // Java/Kotlin Path API: .resolve("runtime").resolve("<artifact>")
  new RegExp(`\\.resolve\\(["']runtime["']\\)\\s*\\.resolve\\(["'](${ARTIFACT_TAIL})["']\\)`, 'g'),
  // Node path.join(...): path.join(<anything>, "runtime", "<artifact>")
  new RegExp(`path\\.join\\([^)]*?["']runtime["']\\s*,\\s*["'](${ARTIFACT_TAIL})["']`, 'g'),
  // Rust PathBuf: .join("runtime").join("<artifact>")
  new RegExp(`\\.join\\(["']runtime["']\\)\\s*\\.join\\(["'](${ARTIFACT_TAIL})["']\\)`, 'g'),
];

/**
 * Tempdoc 501 Phase 31 + Phase 38 (§13.5 closure-rule hardening): the §6
 * rule says stdout is not a discovery channel. The runtime manifest is
 * the discovery channel; every emit of
 * {@code System.out.println("JUSTSEARCH_X=...")} is a parallel mechanism
 * the rule forbids. The Tauri sidecar drain in {@code HeadlessApp.emitPortSignals}
 * is the §5 carve-out; that set is the allowlist.
 *
 * <p>Phase 38 hardenings:
 *   - Catches PrintStream aliasing: {@code var out = System.out; out.println("JUSTSEARCH_...")}.
 *     Tracks aliases of {@code System.out} within a file and applies the
 *     same emit pattern to method calls on the alias.
 *   - Allowlist is derived from {@code HeadlessApp} source at script start
 *     instead of being a hardcoded literal. If a future Phase removes an
 *     emit from HeadlessApp, the allowlist follows automatically — no
 *     drift between the source-of-truth (the producer's emit code) and
 *     the rule allowlist.
 */
const STDOUT_EMIT_PATTERN =
  /System\.out\.print(?:ln)?\(\s*"JUSTSEARCH_([A-Z_]+)\s*=/g;
const STDOUT_ALIAS_DECL_PATTERN =
  /(?:final\s+)?(?:PrintStream|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*System\.out\b/g;

const ALLOWED_STDOUT_EMITS = deriveAllowedStdoutEmits();

/**
 * Scan HeadlessApp for the producer's current set of
 * {@code System.out.print*("JUSTSEARCH_NAME=...")} emits and return their
 * names. The result IS the allowlist — adding or removing an emit in
 * HeadlessApp shifts the allowlist accordingly without manual sync.
 *
 * <p>Falls back to a minimal grandfathered set if HeadlessApp can't be
 * read (script run in an unusual cwd) so the check still fires
 * meaningfully rather than failing open.
 */
function deriveAllowedStdoutEmits() {
  const headlessAppPath = path.join(
      repoRoot, 'modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java');
  const fallback = new Set(['API_PORT', 'SESSION_TOKEN']);
  let content;
  try {
    content = fs.readFileSync(headlessAppPath, 'utf8');
  } catch {
    return fallback;
  }
  const found = new Set();
  STDOUT_EMIT_PATTERN.lastIndex = 0;
  let m;
  while ((m = STDOUT_EMIT_PATTERN.exec(content)) !== null) {
    found.add(m[1]);
  }
  return found.size > 0 ? found : fallback;
}

/**
 * Tempdoc 501 Phase 31 + Phase 38: only the publisher package writes
 * into {@code <dataDir>/runtime/}. The closure rule forbids any other
 * code from writing there. This pattern is a *secondary* defence — the
 * primary sibling-file regex above already catches the common
 * {@code .resolve("runtime").resolve("<artifact>")} construction.
 *
 * <p>Phase 38 tightening: require the "runtime" literal to appear inside
 * a {@code .resolve(...)} call within the {@code Files.write*}
 * statement, not anywhere in the statement. This eliminates the false
 * positive class: {@code Files.writeString(logFile, "runtime config
 * dumped at " + now)} no longer fires (the "runtime" is in a string
 * argument, not in a path-construction call).
 */
const FILES_WRITE_TO_RUNTIME =
  /Files\.(?:writeString|write|newBufferedWriter)\s*\([^;]*?\.resolve\(\s*["']runtime["']\s*\)[^;]*?\)/gs;

function listSourceFiles() {
  // Use git ls-files for speed + .gitignore awareness.
  const lines = execSync('git ls-files', {cwd: repoRoot, encoding: 'utf8'}).split(/\r?\n/);
  return lines.filter((f) => {
    if (!f) return false;
    if (SKIP_PATHS.includes(f)) return false;
    return SCAN_GLOBS.some((g) => matchesGlob(f, g));
  });
}

function matchesGlob(filePath, glob) {
  // Tiny glob translator — handles ** and single * and {a,b}.
  const reStr =
    '^' +
    glob
      .replace(/[.+^${}()|[\]\\]/g, '\\$&')
      .replace(/\{([^}]+)\}/g, (_, opts) => `(?:${opts.split(',').join('|')})`)
      .replace(/\*\*/g, '.*')
      .replace(/(?<!\.)\*/g, '[^/]*') +
    '$';
  return new RegExp(reStr).test(filePath);
}

function scanFile(filePath) {
  const abs = path.join(repoRoot, filePath);
  let content;
  try {
    content = fs.readFileSync(abs, 'utf8');
  } catch {
    return [];
  }
  const hits = [];
  for (const pat of PATTERNS) {
    pat.lastIndex = 0;
    let m;
    while ((m = pat.exec(content)) !== null) {
      const filename = m[1];
      if (!ALLOWED_RUNTIME_ARTIFACTS.has(filename)) {
        hits.push({
          file: filePath,
          kind: 'sibling-file',
          detail: `runtime/${filename}`,
          lineNumber: lineOf(content, m.index),
        });
      }
    }
  }
  // Phase 31 + Phase 38: stdout-emit check (Java only). Catches direct
  // System.out.print*("JUSTSEARCH_X=...") AND aliased emits via
  // PrintStream/var alias = System.out; alias.println("JUSTSEARCH_X=...").
  if (filePath.endsWith('.java')) {
    STDOUT_EMIT_PATTERN.lastIndex = 0;
    let m;
    while ((m = STDOUT_EMIT_PATTERN.exec(content)) !== null) {
      const name = m[1];
      if (!ALLOWED_STDOUT_EMITS.has(name)) {
        hits.push({
          file: filePath,
          kind: 'stdout-emit',
          detail: `System.out … JUSTSEARCH_${name}=`,
          lineNumber: lineOf(content, m.index),
        });
      }
    }
    // Phase 38 (F10): detect aliased emits. First collect all alias
    // identifiers in this file, then look for "alias.print*(\"JUSTSEARCH_..."
    // patterns using each alias.
    STDOUT_ALIAS_DECL_PATTERN.lastIndex = 0;
    const aliases = new Set();
    let a;
    while ((a = STDOUT_ALIAS_DECL_PATTERN.exec(content)) !== null) {
      aliases.add(a[1]);
    }
    for (const alias of aliases) {
      const pat = new RegExp(
          `\\b${alias.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\$&')}\\.print(?:ln)?\\(\\s*"JUSTSEARCH_([A-Z_]+)\\s*=`,
          'g');
      let am;
      while ((am = pat.exec(content)) !== null) {
        const name = am[1];
        if (!ALLOWED_STDOUT_EMITS.has(name)) {
          hits.push({
            file: filePath,
            kind: 'stdout-emit',
            detail: `${alias} (System.out alias) … JUSTSEARCH_${name}=`,
            lineNumber: lineOf(content, am.index),
          });
        }
      }
    }
  }
  // Phase 31: Files.write*-into-runtime check (Java only; the publisher
  // package is in SKIP_PATHS so its own writes don't trip the rule).
  if (filePath.endsWith('.java')) {
    FILES_WRITE_TO_RUNTIME.lastIndex = 0;
    let m;
    while ((m = FILES_WRITE_TO_RUNTIME.exec(content)) !== null) {
      hits.push({
        file: filePath,
        kind: 'unauthorized-write',
        detail: 'Files.write*(... .resolve("runtime") ...)',
        lineNumber: lineOf(content, m.index),
      });
    }
  }
  return hits;
}

function lineOf(content, idx) {
  return content.slice(0, idx).split('\n').length;
}

const {createRequire} = await import('node:module');
const require = createRequire(import.meta.url);

const files = listSourceFiles();
const allHits = [];
for (const f of files) {
  for (const h of scanFile(f)) allHits.push(h);
}

if (allHits.length === 0) {
  console.log(`[closure-check] ${files.length} files scanned, 0 violations.`);
  console.log('[closure-check] tempdoc 501 §6 closure rule holds: no unsanctioned sibling files in <dataDir>/runtime/.');
  process.exit(0);
}

console.error(`[closure-check] FAILED — ${allHits.length} closure-rule violations:`);
for (const h of allHits) {
  console.error(`  ${h.file}:${h.lineNumber} [${h.kind}] ${h.detail}`);
}
console.error('');
console.error('Tempdoc 501 §6 closure rule:');
console.error('  sibling-file: every fact a non-JVM consumer needs is a field on');
console.error('    manifest.json, not a new sibling file. Add the file name to');
console.error('    ALLOWED_RUNTIME_ARTIFACTS with a tempdoc-ref justification if');
console.error('    the sibling is genuinely necessary.');
console.error('  stdout-emit: stdout is not a discovery channel. The Tauri sidecar');
console.error('    has two grandfathered emits (API_PORT, SESSION_TOKEN); new emits');
console.error('    are a closure-rule violation. Land the new fact on the manifest.');
console.error('  unauthorized-write: only the publisher (RuntimeManifestPublisher)');
console.error('    writes into <dataDir>/runtime/. If a new writer is genuinely');
console.error('    necessary, route it through the publisher or document the carve-');
console.error('    out in tempdoc 501 §5.');
process.exit(1);
