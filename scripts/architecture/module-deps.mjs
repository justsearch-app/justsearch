#!/usr/bin/env node
/**
 * Generate a directed module dependency graph from Gradle Kotlin DSL files under modules/**.
 *
 * Output:
 *  - tmp/arch-preflight/module-deps.json
 *  - tmp/arch-preflight/module-deps.md
 *  - tmp/arch-preflight/module-deps.mermaid.md (Mermaid diagram)
 *  - tmp/arch-preflight/module-deps-tempdoc.md (tempdoc 34 A1-A4 format)
 *  - (optional) update a canonical doc in-place:
 *    - docs/reference/architecture/module-deps.md (via --update-canonical)
 *
 * Options:
 *  --out-dir=<path>      Output directory (default: tmp/arch-preflight)
 *  --update-canonical    Update docs/reference/architecture/module-deps.md
 *  --update-doc=<path>   Update a specific doc (repeatable)
 *  --check-canonical     Verify docs/reference/architecture/module-deps.md is up to date
 *  --check-doc=<path>    Verify a specific doc is up to date (repeatable)
 *  --include-staleness   Add staleness analysis (git history + reverse deps)
 */

import fsp from 'node:fs/promises';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { execSync } from 'node:child_process';

const CANONICAL_MODULE_DEPS_DOC = 'docs/reference/architecture/module-deps.md';
const GENERATED_BEGIN_MARKER = '<!-- GENERATED:MODULE_DEPS:BEGIN -->';
const GENERATED_END_MARKER = '<!-- GENERATED:MODULE_DEPS:END -->';

// Configuration types that indicate production dependencies
const PRODUCTION_CONFIGS = new Set(['api', 'implementation', 'runtimeOnly']);
// Configuration types that indicate test-only dependencies
const TEST_CONFIGS = new Set(['testImplementation', 'testRuntimeOnly', 'integrationTestImplementation']);

// Staleness detection configuration
const STALE_THRESHOLD_MONTHS = 6;
// Modules that naturally have zero dependents (not suspicious)
// Includes: entry points, deferred enhancements, and JNI/native modules
const ENTRY_POINT_MODULES = new Set([
  'app-launcher',      // CLI entry point
  'ui',                // Head process entry
  // Lane F stage A item A13: no longer a process entry point (the Worker main and its
  // distribution are gone), but still a leaf library nobody depends on — the Engine dist pulls it
  // in from :modules:ui, so zero dependents remains expected, not suspicious.
  'indexer-worker',    // Engine-side leaf: no dependents by construction
  'system-tests',      // Test entry
  'benchmarks',        // Benchmark entry
  'reports',           // Report generation (not under modules/)
  'app-api-tck',       // TCK test module
  'ai-engine-native',  // JNI bridge (invisible to Gradle dependency graph)
]);

function nowIso() {
  return new Date().toISOString();
}

function detectNewline(text) {
  return String(text).includes('\r\n') ? '\r\n' : '\n';
}

async function mkdirp(dir) {
  await fsp.mkdir(dir, { recursive: true });
}

async function writeUtf8(filePath, text) {
  await mkdirp(path.dirname(filePath));
  await fsp.writeFile(filePath, String(text ?? ''), 'utf8');
}

async function writeJsonPretty(filePath, obj) {
  await writeUtf8(filePath, JSON.stringify(obj ?? null, null, 2) + '\n');
}

async function listFilesRecursive(rootDir, { fileName } = {}) {
  const out = [];
  const stack = [rootDir];
  while (stack.length > 0) {
    const dir = stack.pop();
    let entries = [];
    try {
      entries = await fsp.readdir(dir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const ent of entries) {
      const full = path.join(dir, ent.name);
      if (ent.isDirectory()) {
        // Skip heavyweight dirs
        if (ent.name === 'build' || ent.name === 'node_modules' || ent.name === '.gradle') continue;
        stack.push(full);
      } else if (ent.isFile()) {
        if (fileName && ent.name !== fileName) continue;
        out.push(full);
      }
    }
  }
  out.sort();
  return out;
}

function parseArgs(argv) {
  const out = {
    outDir: 'tmp/arch-preflight',
    updateDocs: [],
    checkDocs: [],
    includeStaleness: false,
  };
  const args = [...argv];
  for (let i = 0; i < args.length; i += 1) {
    const token = args[i];
    if (!token.startsWith('--')) continue;

    const [key, inlineValue] = token.split('=', 2);
    const takeValue = () => {
      if (inlineValue != null) return inlineValue;
      const next = args[i + 1];
      i += 1;
      return next ?? null;
    };

    switch (key) {
      case '--out-dir': {
        const value = takeValue();
        out.outDir = String(value || '').trim() || out.outDir;
        break;
      }
      case '--update-doc': {
        const value = takeValue();
        const p = String(value || '').trim();
        if (!p) throw new Error(`--update-doc requires a path`);
        out.updateDocs.push(p);
        break;
      }
      case '--update-canonical': {
        out.updateDocs.push(CANONICAL_MODULE_DEPS_DOC);
        break;
      }
      case '--check-doc': {
        const value = takeValue();
        const p = String(value || '').trim();
        if (!p) throw new Error(`--check-doc requires a path`);
        out.checkDocs.push(p);
        break;
      }
      case '--check-canonical': {
        out.checkDocs.push(CANONICAL_MODULE_DEPS_DOC);
        break;
      }
      case '--include-staleness': {
        out.includeStaleness = true;
        break;
      }
      default:
        throw new Error(`Unknown arg: ${token}`);
    }
  }
  out.updateDocs = [...new Set(out.updateDocs)];
  out.checkDocs = [...new Set(out.checkDocs)];
  if (out.updateDocs.length > 0 && out.checkDocs.length > 0) {
    throw new Error('Cannot combine --update-* options with --check-* options in the same run');
  }
  return out;
}

async function updateFileBetweenMarkers({ filePath, beginMarker, endMarker, content }) {
  const original = await fsp.readFile(filePath, 'utf8');
  const nl = detectNewline(original);

  const beginIdx = original.indexOf(beginMarker);
  if (beginIdx === -1) {
    throw new Error(`Begin marker not found in ${filePath}: ${beginMarker}`);
  }
  const endIdx = original.indexOf(endMarker, beginIdx + beginMarker.length);
  if (endIdx === -1) {
    throw new Error(`End marker not found in ${filePath}: ${endMarker}`);
  }

  const before = original.slice(0, beginIdx);
  const after = original.slice(endIdx + endMarker.length);

  const normalizedContent = String(content ?? '').replace(/\r?\n/g, nl).trimEnd();
  const next = before + beginMarker + nl + normalizedContent + nl + endMarker + after;
  if (next === original) return false;
  await writeUtf8(filePath, next);
  return true;
}

function buildGeneratedCanonicalBlock(graph, settingsModules, fanIn) {
  return [
    '<!-- Generated by: node scripts/architecture/module-deps.mjs -->',
    '',
    generateTempdocFormat(graph, settingsModules, fanIn, { includeFooter: false }).trimEnd(),
  ].join('\n');
}

function previewLine(line) {
  if (line == null) return '(EOF)';
  const text = String(line);
  return text.length > 140 ? `${text.slice(0, 137)}...` : text;
}

function firstDiff(expectedText, actualText) {
  const expectedLines = String(expectedText).split(/\r?\n/);
  const actualLines = String(actualText).split(/\r?\n/);
  const max = Math.max(expectedLines.length, actualLines.length);
  for (let i = 0; i < max; i += 1) {
    if (expectedLines[i] !== actualLines[i]) {
      return {
        line: i + 1,
        expected: previewLine(expectedLines[i]),
        actual: previewLine(actualLines[i]),
      };
    }
  }
  return {
    line: 1,
    expected: previewLine(expectedLines[0]),
    actual: previewLine(actualLines[0]),
  };
}

async function checkFileBetweenMarkers({ filePath, beginMarker, endMarker, content }) {
  const original = await fsp.readFile(filePath, 'utf8');
  const nl = detectNewline(original);
  const beginIdx = original.indexOf(beginMarker);
  if (beginIdx === -1) {
    throw new Error(`Begin marker not found in ${filePath}: ${beginMarker}`);
  }
  const endIdx = original.indexOf(endMarker, beginIdx + beginMarker.length);
  if (endIdx === -1) {
    throw new Error(`End marker not found in ${filePath}: ${endMarker}`);
  }

  const before = original.slice(0, beginIdx);
  const after = original.slice(endIdx + endMarker.length);
  const normalizedContent = String(content ?? '').replace(/\r?\n/g, nl).trimEnd();
  const expected = before + beginMarker + nl + normalizedContent + nl + endMarker + after;

  return {
    matches: expected === original,
    diff: firstDiff(expected, original),
  };
}

/**
 * Parse settings.gradle.kts for the canonical list of included projects.
 * @param {string} settingsPath - Path to settings.gradle.kts
 * @returns {string[]} Array of Gradle project paths (e.g., ':modules:core', ':reports')
 */
function parseSettingsGradle(settingsPath) {
  const text = fs.readFileSync(settingsPath, 'utf8');
  // Match the include(...) block which may span multiple lines
  const includeMatch = text.match(/include\s*\(\s*([\s\S]*?)\s*\)/);
  if (!includeMatch) return [];

  // Extract all quoted strings from the include block
  const modules = [...includeMatch[1].matchAll(/"([^"]+)"/g)]
    .map(m => m[1]);
  return modules;
}

/**
 * Parse a build.gradle.kts file and extract project dependencies with their configuration type.
 * @param {string} buildFilePath - Path to build.gradle.kts
 * @returns {{ production: Set<string>, test: Set<string> }} Sets of dependency module names
 */
function parseBuildGradleDeps(buildFilePath) {
  const text = fs.readFileSync(buildFilePath, 'utf8');
  const production = new Set();
  const test = new Set();

  // Enhanced regex to capture configuration type
  // Matches: api(project(":modules:xyz")), implementation(project(...)), testImplementation(project(...))
  const re = /(api|implementation|runtimeOnly|testImplementation|testRuntimeOnly|integrationTestImplementation)\s*\(\s*project\s*\(\s*["'](:modules:[^"']+)["']\s*\)\s*\)/g;

  for (const m of text.matchAll(re)) {
    const configType = m[1];
    const projectPath = m[2];
    const depName = gradlePathToModuleName(projectPath);
    if (!depName) continue;

    if (PRODUCTION_CONFIGS.has(configType)) {
      production.add(depName);
    } else if (TEST_CONFIGS.has(configType)) {
      test.add(depName);
    }
  }

  return { production, test };
}

/**
 * Calculate fan-in (number of outgoing edges) for each module.
 * @param {Array<{from: string, to: string}>} edges
 * @returns {Array<{module: string, count: number}>} Sorted by count descending
 */
function calculateFanIn(edges) {
  const counts = new Map();
  for (const { from } of edges) {
    counts.set(from, (counts.get(from) || 0) + 1);
  }
  return [...counts.entries()]
    .map(([module, count]) => ({ module, count }))
    .sort((a, b) => b.count - a.count);
}

/**
 * Calculate dependents (reverse edges) for each module.
 * @param {Array<{from: string, to: string}>} edges
 * @returns {Map<string, string[]>} Map of module -> modules that depend on it
 */
function calculateDependents(edges) {
  const dependents = new Map();
  for (const { from, to } of edges) {
    if (!dependents.has(to)) dependents.set(to, []);
    dependents.get(to).push(from);
  }
  return dependents;
}

/**
 * Get last commit date for a module directory.
 * @param {string} modulePath - Path like 'modules/core'
 * @returns {string|null} ISO date string or null
 */
function getLastCommitDate(modulePath) {
  try {
    const result = execSync(
      `git log -1 --format=%cI -- "${modulePath}"`,
      { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }
    ).trim();
    return result || null;
  } catch {
    return null;
  }
}

/**
 * Check if a date is older than the staleness threshold.
 * @param {string|null} lastCommitDate - ISO date string
 * @returns {boolean}
 */
function isStale(lastCommitDate) {
  if (!lastCommitDate) return false;
  const threshold = new Date();
  threshold.setMonth(threshold.getMonth() - STALE_THRESHOLD_MONTHS);
  return new Date(lastCommitDate) < threshold;
}

/**
 * Detect module staleness based on dependents and commit history.
 * @param {string[]} modules - List of module names
 * @param {Array<{from: string, to: string}>} edges - Production edges
 * @param {string} modulesRoot - Path to modules directory
 * @returns {Array<Object>} Staleness data for each module
 */
function detectStaleness(modules, edges, modulesRoot) {
  const dependents = calculateDependents(edges);
  const results = [];

  for (const mod of modules) {
    const modulePath = path.join(modulesRoot, mod);
    const lastCommit = getLastCommitDate(modulePath);
    const deps = dependents.get(mod) || [];
    const isOrphaned = deps.length === 0;
    const stale = isStale(lastCommit);
    const isEntryPoint = ENTRY_POINT_MODULES.has(mod);

    results.push({
      module: mod,
      lastCommit,
      daysSinceCommit: lastCommit ? Math.floor((Date.now() - new Date(lastCommit).getTime()) / (1000 * 60 * 60 * 24)) : null,
      dependentCount: deps.length,
      dependents: deps.sort(),
      isOrphaned,
      isStale: stale,
      isEntryPoint,
      isSuspicious: isOrphaned && stale && !isEntryPoint,
    });
  }

  // Sort: suspicious first, then by days since commit (oldest first)
  results.sort((a, b) => {
    if (a.isSuspicious !== b.isSuspicious) return b.isSuspicious ? 1 : -1;
    if (a.isOrphaned !== b.isOrphaned) return b.isOrphaned ? 1 : -1;
    return (b.daysSinceCommit ?? 0) - (a.daysSinceCommit ?? 0);
  });

  return results;
}

/**
 * Generate Mermaid diagram from graph.
 * @param {Array<{from: string, to: string}>} edges
 * @param {string[]} nodes - All module names (to include isolated nodes)
 * @returns {string} Mermaid markdown
 */
function generateMermaid(edges, nodes) {
  const lines = ['```mermaid', 'graph TD'];

  // Add nodes with no edges (isolated foundation modules)
  const connectedNodes = new Set();
  for (const { from, to } of edges) {
    connectedNodes.add(from);
    connectedNodes.add(to);
  }

  // Add isolated nodes explicitly
  for (const node of nodes) {
    if (!connectedNodes.has(node)) {
      lines.push(`  ${node}["${node}"]`);
    }
  }

  // Add edges
  for (const { from, to } of edges) {
    lines.push(`  ${from} --> ${to}`);
  }

  lines.push('```');
  return lines.join('\n');
}

/**
 * Generate tempdoc A1-A4 sections format.
 */
function generateTempdocFormat(graph, settingsModules, fanIn, { includeFooter = true } = {}) {
  const lines = [];
  const now = graph.generated_at;
  const bt = '`'; // backtick helper for template literals

  // A1: Gradle project inventory
  lines.push('### A1. Gradle project inventory');
  lines.push('');
  lines.push('**Included in ' + bt + 'settings.gradle.kts' + bt + ':**');
  const moduleCount = settingsModules.filter(p => p.startsWith(':modules:')).length;
  const hasReports = settingsModules.includes(':reports');
  lines.push('- ' + bt + ':modules:*' + bt + ' (' + moduleCount + ' JVM projects)');
  if (hasReports) {
    lines.push('- ' + bt + ':reports' + bt + ' (test/coverage aggregation project)');
  }
  lines.push('');
  lines.push('**Not Gradle projects (invoked by the build):**');
  lines.push('- ' + bt + 'modules/ui-web' + bt + ' (Lit/Vite/TailwindCSS frontend)');
  lines.push('- ' + bt + 'modules/shell' + bt + ' (Tauri/Rust desktop wrapper)');
  lines.push('');

  // A2: Direct internal dependencies
  lines.push('### A2. Direct internal dependencies (production)');
  lines.push('');
  lines.push('Legend: ' + bt + 'A -> B' + bt + ' means ' + bt + 'A' + bt + ' declares a direct Gradle project dependency on ' + bt + 'B' + bt + ' in its main ' + bt + 'dependencies {}' + bt + ' block.');
  lines.push('');

  // Group by dependency count
  const foundation = graph.modules.filter(m => m.productionDeps.length === 0);
  const withDeps = graph.modules.filter(m => m.productionDeps.length > 0);

  if (foundation.length > 0) {
    lines.push('**Foundation (no internal deps)**');
    for (const m of foundation.sort((a, b) => a.name.localeCompare(b.name))) {
      lines.push('- ' + bt + ':modules:' + m.name + bt);
    }
    lines.push('');
  }

  lines.push('**With dependencies**');
  for (const m of withDeps.sort((a, b) => a.name.localeCompare(b.name))) {
    const deps = m.productionDeps.map(d => bt + ':modules:' + d + bt).join(', ');
    lines.push('- ' + bt + ':modules:' + m.name + bt + ' -> ' + deps);
  }
  lines.push('');

  // A2.1: Test-only coupling
  const withTestDeps = graph.modules.filter(m => m.testDeps.length > 0);
  if (withTestDeps.length > 0) {
    lines.push('### A2.1 Test-only coupling (high-signal)');
    lines.push('');
    lines.push('These edges exist only in test suites and should not be treated as production layering:');
    lines.push('');
    for (const m of withTestDeps.sort((a, b) => a.name.localeCompare(b.name))) {
      const deps = m.testDeps.map(d => bt + ':modules:' + d + bt).join(', ');
      lines.push('- ' + bt + ':modules:' + m.name + bt + ' uses ' + bt + 'testImplementation' + bt + ' on ' + deps);
    }
    lines.push('');
  }

  // A3: Mermaid graph
  lines.push('### A3. Build dependency graph (production)');
  lines.push('');
  lines.push(generateMermaid(graph.productionEdges, graph.modules.map(m => m.name)));
  lines.push('');

  // A4: Notable build/packaging facts
  lines.push('### A4. Notable build/packaging facts');
  lines.push('');
  lines.push('**Heaviest "internal fan-in" (direct module deps, production)**');
  lines.push('');
  lines.push('| Module | Direct deps | Notes |');
  lines.push('|--------|-------------|-------|');

  const top6 = fanIn.slice(0, 6);
  for (const { module, count } of top6) {
    let notes = '';
    if (module === 'app-services') notes = 'Orchestration + glue across large portions of the stack';
    else if (module === 'app-launcher') notes = 'CLI/distribution wiring; pulls in most runtime modules';
    else if (module === 'indexer-worker') notes = 'Knowledge-server runtime hosted in the Engine, includes AI bridge + Lucene';
    else if (module === 'ui') notes = 'Head REST API + orchestration bridge';
lines.push('| ' + bt + module + bt + ' | ' + count + ' | ' + notes + ' |');
  }
  lines.push('');

  if (includeFooter) {
    lines.push('---');
    lines.push('');
    lines.push('*Generated at ' + now + ' by ' + bt + 'scripts/architecture/module-deps.mjs' + bt + '*');
  }

  return lines.join('\n');
}

function gradlePathToModuleName(projectPath) {
  // ":modules:app-services" -> "app-services"
  const p = String(projectPath || '').trim();
  const parts = p.split(':').filter(Boolean);
  return parts.length > 0 ? parts[parts.length - 1] : null;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const __filename = fileURLToPath(import.meta.url);
  const __dirname = path.dirname(__filename);
  const repoRoot = path.resolve(__dirname, '..', '..');
  const modulesRoot = path.join(repoRoot, 'modules');
  const settingsPath = path.join(repoRoot, 'settings.gradle.kts');
  const outDirAbs = path.resolve(repoRoot, opts.outDir);

  if (!fs.existsSync(modulesRoot)) {
    throw new Error(`modules/ not found at ${modulesRoot}`);
  }

  // Parse settings.gradle.kts for canonical module list
  const settingsModules = parseSettingsGradle(settingsPath);

  const buildFiles = await listFilesRecursive(modulesRoot, { fileName: 'build.gradle.kts' });

  // Enhanced: track production vs test dependencies separately
  const moduleProductionDeps = new Map(); // module -> Set<dep>
  const moduleTestDeps = new Map(); // module -> Set<dep>
  const moduleDeps = new Map(); // module -> Set<dep> (all deps, for backward compat)

  for (const bf of buildFiles) {
    // bf: <repo>/modules/<module>/build.gradle.kts
    const rel = path.relative(modulesRoot, bf);
    const segs = rel.split(path.sep);
    const moduleName = segs.length > 0 ? segs[0] : null;
    if (!moduleName) continue;

    if (!moduleDeps.has(moduleName)) moduleDeps.set(moduleName, new Set());
    if (!moduleProductionDeps.has(moduleName)) moduleProductionDeps.set(moduleName, new Set());
    if (!moduleTestDeps.has(moduleName)) moduleTestDeps.set(moduleName, new Set());

    // Parse with configuration type awareness
    const { production, test } = parseBuildGradleDeps(bf);

    for (const dep of production) {
      if (dep === moduleName) continue;
      moduleProductionDeps.get(moduleName).add(dep);
      moduleDeps.get(moduleName).add(dep);
    }

    for (const dep of test) {
      if (dep === moduleName) continue;
      // Only add to test if not already a production dep
      if (!production.has(dep)) {
        moduleTestDeps.get(moduleName).add(dep);
      }
      moduleDeps.get(moduleName).add(dep);
    }
  }

  const nodes = [...moduleDeps.keys()].sort();

  // All edges (backward compatible)
  const edges = [];
  for (const from of nodes) {
    for (const to of [...(moduleDeps.get(from) || [])].sort()) {
      edges.push({ from, to });
    }
  }

  // Production-only edges
  const productionEdges = [];
  for (const from of nodes) {
    for (const to of [...(moduleProductionDeps.get(from) || [])].sort()) {
      productionEdges.push({ from, to });
    }
  }

  // Calculate fan-in based on production deps only
  const fanIn = calculateFanIn(productionEdges);

  // Calculate staleness if requested
  const staleness = opts.includeStaleness
    ? detectStaleness(nodes, productionEdges, modulesRoot)
    : null;

  const graph = {
    schema_version: staleness ? 3 : 2, // Bump to 3 when staleness included
    generated_at: nowIso(),
    root: 'modules',
    settings_modules: settingsModules,
    modules: nodes.map((name) => ({
      name,
      build_file: `modules/${name}/build.gradle.kts`,
      deps: [...(moduleDeps.get(name) || [])].sort(), // All deps (backward compat)
      productionDeps: [...(moduleProductionDeps.get(name) || [])].sort(),
      testDeps: [...(moduleTestDeps.get(name) || [])].sort(),
    })),
    edges, // All edges (backward compat)
    productionEdges,
    fanIn,
    ...(staleness ? { staleness } : {}),
  };

  const jsonPath = path.join(outDirAbs, 'module-deps.json');
  const mdPath = path.join(outDirAbs, 'module-deps.md');
  const mermaidPath = path.join(outDirAbs, 'module-deps.mermaid.md');
  const tempdocPath = path.join(outDirAbs, 'module-deps-tempdoc.md');

  // Original markdown format (backward compat)
  const md = [];
  md.push('# Module dependency summary');
  md.push('');
  md.push(`- generated_at: \`${graph.generated_at}\``);
  md.push(`- modules: \`${nodes.length}\``);
  md.push(`- edges: \`${edges.length}\` (all) / \`${productionEdges.length}\` (production only)`);
  md.push('');
  md.push('## Per-module direct deps (all)');
  md.push('');
  for (const m of graph.modules) {
    md.push(`- \`${m.name}\`: ${m.deps.length ? m.deps.map((d) => `\`${d}\``).join(', ') : '(none)'}`);
  }
  md.push('');
  md.push('## Per-module production deps');
  md.push('');
  for (const m of graph.modules) {
    md.push(`- \`${m.name}\`: ${m.productionDeps.length ? m.productionDeps.map((d) => `\`${d}\``).join(', ') : '(none)'}`);
  }
  md.push('');
  md.push('## Fan-in (production deps, top 10)');
  md.push('');
  for (const { module, count } of fanIn.slice(0, 10)) {
    md.push(`- \`${module}\`: ${count}`);
  }
  md.push('');

  // Staleness analysis (if enabled)
  if (staleness) {
    md.push('## Staleness Analysis');
    md.push('');
    md.push(`Threshold: ${STALE_THRESHOLD_MONTHS} months without commits`);
    md.push('');

    const suspicious = staleness.filter(s => s.isSuspicious);
    const orphaned = staleness.filter(s => s.isOrphaned && !s.isSuspicious);
    const staleOnly = staleness.filter(s => s.isStale && !s.isOrphaned);

    if (suspicious.length > 0) {
      md.push('### ⚠️ Suspicious (orphaned + stale)');
      md.push('');
      md.push('These modules have no dependents AND have not been modified recently:');
      md.push('');
      for (const s of suspicious) {
        md.push(`- \`${s.module}\`: ${s.daysSinceCommit} days since last commit`);
      }
      md.push('');
    }

    if (orphaned.length > 0) {
      md.push('### Orphaned (no dependents)');
      md.push('');
      md.push('These modules have no dependents but are recently active or are entry points:');
      md.push('');
      for (const s of orphaned) {
        const note = s.isEntryPoint ? ' (entry point)' : '';
        md.push(`- \`${s.module}\`: ${s.daysSinceCommit ?? '?'} days${note}`);
      }
      md.push('');
    }

    if (staleOnly.length > 0) {
      md.push('### Stale (but has dependents)');
      md.push('');
      md.push('These modules have dependents but have not been modified recently (stable/mature):');
      md.push('');
      for (const s of staleOnly) {
        md.push(`- \`${s.module}\`: ${s.daysSinceCommit} days, ${s.dependentCount} dependents`);
      }
      md.push('');
    }
  }

  await writeJsonPretty(jsonPath, graph);
  await writeUtf8(mdPath, md.join('\n') + '\n');

  // Mermaid output
  const mermaidContent = generateMermaid(productionEdges, nodes);
  await writeUtf8(mermaidPath, mermaidContent + '\n');

  // Tempdoc format output
  const tempdocContent = generateTempdocFormat(graph, settingsModules, fanIn);
  await writeUtf8(tempdocPath, tempdocContent + '\n');

  const generatedBlock = buildGeneratedCanonicalBlock(graph, settingsModules, fanIn);

  // Canonical doc update (optional)
  if (opts.updateDocs.length > 0) {
    for (const relPath of opts.updateDocs) {
      const absPath = path.resolve(repoRoot, relPath);
      await updateFileBetweenMarkers({
        filePath: absPath,
        beginMarker: GENERATED_BEGIN_MARKER,
        endMarker: GENERATED_END_MARKER,
        content: generatedBlock,
      });
    }
  }

  // Canonical doc drift checks (optional)
  if (opts.checkDocs.length > 0) {
    const failures = [];
    for (const relPath of opts.checkDocs) {
      const absPath = path.resolve(repoRoot, relPath);
      const result = await checkFileBetweenMarkers({
        filePath: absPath,
        beginMarker: GENERATED_BEGIN_MARKER,
        endMarker: GENERATED_END_MARKER,
        content: generatedBlock,
      });
      if (!result.matches) {
        failures.push({
          relPath,
          ...result.diff,
        });
      }
    }

    if (failures.length > 0) {
      // eslint-disable-next-line no-console
      console.error(`module-deps check: FAIL (${failures.length} file(s) out of date)`);
      for (const f of failures) {
        // eslint-disable-next-line no-console
        console.error(`- ${f.relPath}:${f.line} expected="${f.expected}" actual="${f.actual}"`);
      }
      if (
        opts.checkDocs.length === 1
        && opts.checkDocs[0] === CANONICAL_MODULE_DEPS_DOC
      ) {
        // eslint-disable-next-line no-console
        console.error('Run: node scripts/architecture/module-deps.mjs --update-canonical');
      } else {
        // eslint-disable-next-line no-console
        console.error('Run one of:');
        for (const relPath of opts.checkDocs) {
          // eslint-disable-next-line no-console
          console.error(`  node scripts/architecture/module-deps.mjs --update-doc=${relPath}`);
        }
      }
      process.exit(1);
    }
    // eslint-disable-next-line no-console
    console.log(`module-deps check: OK (files=${opts.checkDocs.length})`);
  }

  // Print output dir as a stable last line (useful for wrappers).
  // eslint-disable-next-line no-console
  console.log(outDirAbs);
}

main().catch((err) => {
  // eslint-disable-next-line no-console
  console.error('module-deps failed:', err);
  process.exit(2);
});

