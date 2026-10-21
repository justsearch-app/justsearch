#!/usr/bin/env node
/**
 * Report Gradle lockfile version skew across the repo.
 *
 * Example:
 *   node scripts/ci/report-lock-skew.mjs --root . --fail-on-unexpected-skew
 *   node scripts/ci/report-lock-skew.mjs --families com.fasterxml.jackson,org.slf4j --out-json tmp/lock-skew.json
 *   node scripts/ci/report-lock-skew.mjs --allow-duplicate-coords com.example:legacy-lib --fail-on-unexpected-skew
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

function usage(code = 1) {
  // eslint-disable-next-line no-console
  console.error(
    [
      'Usage:',
      '  node scripts/ci/report-lock-skew.mjs [options]',
      '',
      'Options:',
      '  --root <path>                 Repo root (default: current working directory)',
      '  --families <csv>              Group prefixes to report (default: com.fasterxml.jackson,org.slf4j)',
      '  --top-files <n>               Number of top skewed lockfiles to print (default: 10)',
      '  --top-coords <n>              Number of top duplicate coordinates to print (default: 15)',
      '  --out-json <path>             Optional JSON output path',
      '  --allow-duplicate-coords <csv>Optional duplicate coordinates to allow temporarily',
      '  --fail-on-family-skew         Exit 1 when any selected family has duplicate coordinates',
      '  --fail-on-unexpected-skew     Exit 1 when any duplicate coordinate is outside allowlist (empty allowlist => zero-skew gate)',
      '  -h, --help',
    ].join('\n'),
  );
  process.exit(code);
}

function parseArgs(argv) {
  const out = {
    root: process.cwd(),
    families: ['com.fasterxml.jackson', 'org.slf4j'],
    topFiles: 10,
    topCoords: 15,
    outJson: null,
    allowDuplicateCoords: [],
    failOnFamilySkew: false,
    failOnUnexpectedSkew: false,
  };
  const args = [...argv];
  const takeValue = (i) => {
    const token = args[i];
    const eq = token.indexOf('=');
    if (eq !== -1) return token.slice(eq + 1);
    return args[i + 1] ?? null;
  };
  const consumed = new Set();
  for (let i = 0; i < args.length; i += 1) {
    if (consumed.has(i)) continue;
    const token = args[i];
    if (token === '-h' || token === '--help') usage(0);
    if (!token.startsWith('--')) continue;
    const key = token.split('=')[0];
    const inline = token.includes('=');
    const value = takeValue(i);
    if (!inline) consumed.add(i + 1);
    switch (key) {
      case '--root':
        out.root = value || out.root;
        break;
      case '--families':
        out.families = String(value || '')
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean);
        break;
      case '--top-files':
        out.topFiles = Number.parseInt(value || '', 10);
        break;
      case '--top-coords':
        out.topCoords = Number.parseInt(value || '', 10);
        break;
      case '--out-json':
        out.outJson = value || null;
        break;
      case '--allow-duplicate-coords':
        out.allowDuplicateCoords = String(value || '')
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean);
        break;
      case '--fail-on-family-skew':
        out.failOnFamilySkew = true;
        if (!inline) consumed.delete(i + 1);
        break;
      case '--fail-on-unexpected-skew':
        out.failOnUnexpectedSkew = true;
        if (!inline) consumed.delete(i + 1);
        break;
      default:
        throw new Error(`Unknown option: ${token}`);
    }
  }
  if (!Number.isInteger(out.topFiles) || out.topFiles <= 0) {
    throw new Error('--top-files must be a positive integer');
  }
  if (!Number.isInteger(out.topCoords) || out.topCoords <= 0) {
    throw new Error('--top-coords must be a positive integer');
  }
  return out;
}

async function listLockfiles(root) {
  const lockfiles = [];
  const skipDirs = new Set(['.git', '.gradle', '.claude', 'build', 'node_modules']);
  const stack = [root];
  while (stack.length > 0) {
    const current = stack.pop();
    let entries = [];
    try {
      entries = await fs.readdir(current, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const ent of entries) {
      const full = path.join(current, ent.name);
      if (ent.isDirectory()) {
        if (skipDirs.has(ent.name)) continue;
        stack.push(full);
        continue;
      }
      if (ent.isFile() && ent.name === 'gradle.lockfile') {
        lockfiles.push(full);
      }
    }
  }
  return lockfiles.sort();
}

function parseLockLine(line) {
  const trimmed = line.trim();
  if (!trimmed || trimmed.startsWith('#')) return null;
  const eq = trimmed.indexOf('=');
  const left = eq === -1 ? trimmed : trimmed.slice(0, eq);
  // Gradle lockfile format: group:artifact:version=config1,config2,...
  const configs = eq === -1
    ? []
    : trimmed.slice(eq + 1).split(',').map((s) => s.trim()).filter(Boolean);
  const parts = left.split(':');
  if (parts.length < 3) return null;
  const group = parts[0].trim();
  const artifact = parts[1].trim();
  const version = parts.slice(2).join(':').trim();
  if (!group || !artifact || !version) return null;
  return { group, artifact, version, coordinate: `${group}:${artifact}`, configs };
}

// Coarse "scope class" of a Gradle configuration. Two versions of one
// coordinate are a *real* skew only if they can co-resolve — i.e. both appear
// on configs of the SAME scope class. Versions confined to disjoint scope
// classes (e.g. an annotationProcessor-only build vs the runtime build, or the
// PMD tool classpath vs product runtime) never share a resolution and
// are benign cross-scope differences, not drift. The lockfile already records
// the config list after `=`, so this needs no extra data.
function scopeClassOf(config) {
  const c = config.toLowerCase();
  if (c.includes('annotationprocessor')) return 'processor';
  // Build-tool classpaths that never ship in a product or test artifact. The legacy
  // rewrite scope remains classified for historical lockfiles.
  if (c === 'rewrite' || c.startsWith('spotless') || c.startsWith('pmd') ||
      c.startsWith('checkstyle') || c.startsWith('errorprone') ||
      c.startsWith('jacoco') || c.startsWith('kotlin') ||
      c.startsWith('detekt') || c.startsWith('ktlint')) {
    return 'tool';
  }
  // compile/runtime/test/testFixtures/integration/… — conservatively treated
  // as co-resolvable ("main") so genuine runtime↔test skew is never suppressed.
  return 'main';
}

// A coordinate is real skew only if some single scope class carries ≥2 distinct
// versions. If every scope class maps to exactly one version, the differences
// are cross-scope (benign). `versionConfigs` is Map(version -> Set(config)).
function isRealSkew(versionConfigs) {
  const scopeVersions = new Map(); // scopeClass -> Set(version)
  for (const [version, configs] of versionConfigs) {
    for (const cfg of configs) {
      const sc = scopeClassOf(cfg);
      if (!scopeVersions.has(sc)) scopeVersions.set(sc, new Set());
      scopeVersions.get(sc).add(version);
    }
    // A version with no recorded configs can't be reasoned about — treat as
    // main-scope so it never silently hides a real skew.
    if (configs.size === 0) {
      if (!scopeVersions.has('main')) scopeVersions.set('main', new Set());
      scopeVersions.get('main').add(version);
    }
  }
  for (const versions of scopeVersions.values()) {
    if (versions.size > 1) return true;
  }
  return false;
}

async function analyzeLockfiles(root) {
  const lockfiles = await listLockfiles(root);
  const global = new Map(); // coordinate -> Set(version)
  const byFile = new Map(); // file -> Map(coordinate, Set(version))
  const versionConfigs = new Map(); // coordinate -> Map(version, Set(config))

  for (const file of lockfiles) {
    const text = await fs.readFile(file, 'utf8');
    const perFile = new Map();
    for (const raw of text.split(/\r?\n/)) {
      const parsed = parseLockLine(raw);
      if (!parsed) continue;
      if (!global.has(parsed.coordinate)) global.set(parsed.coordinate, new Set());
      global.get(parsed.coordinate).add(parsed.version);
      if (!perFile.has(parsed.coordinate)) perFile.set(parsed.coordinate, new Set());
      perFile.get(parsed.coordinate).add(parsed.version);
      if (!versionConfigs.has(parsed.coordinate)) versionConfigs.set(parsed.coordinate, new Map());
      const vc = versionConfigs.get(parsed.coordinate);
      if (!vc.has(parsed.version)) vc.set(parsed.version, new Set());
      for (const cfg of parsed.configs) vc.get(parsed.version).add(cfg);
    }
    byFile.set(file, perFile);
  }

  const duplicateCoordinates = [...global.entries()]
    .filter(([, versions]) => versions.size > 1)
    .map(([coordinate, versions]) => {
      const vc = versionConfigs.get(coordinate) ?? new Map();
      const crossScope = !isRealSkew(vc);
      const configsByVersion = {};
      for (const [v, cfgs] of vc) configsByVersion[v] = [...cfgs].sort();
      return {
        coordinate,
        versions: [...versions].sort(),
        versionCount: versions.size,
        group: coordinate.split(':')[0] ?? '',
        crossScope,
        configsByVersion,
      };
    })
    .sort((a, b) => b.versionCount - a.versionCount || a.coordinate.localeCompare(b.coordinate));

  const filesWithSkew = [...byFile.entries()]
    .map(([file, map]) => {
      let duplicateCount = 0;
      for (const versions of map.values()) {
        if (versions.size > 1) duplicateCount += 1;
      }
      return {
        file,
        duplicateCoordinateCount: duplicateCount,
      };
    })
    .sort(
      (a, b) => b.duplicateCoordinateCount - a.duplicateCoordinateCount || a.file.localeCompare(b.file),
    );

  return {
    lockfiles,
    duplicateCoordinates,
    filesWithSkew,
  };
}

function summarizeFamilies(duplicateCoordinates, families) {
  return families.map((prefix) => {
    const hits = duplicateCoordinates.filter((row) => row.group.startsWith(prefix));
    return {
      family: prefix,
      duplicateCoordinateCount: hits.length,
      coordinates: hits.map((row) => row.coordinate),
      versions: [...new Set(hits.flatMap((row) => row.versions))].sort(),
    };
  });
}

async function writeJson(outPath, obj) {
  const abs = path.resolve(outPath);
  await fs.mkdir(path.dirname(abs), { recursive: true });
  await fs.writeFile(abs, `${JSON.stringify(obj, null, 2)}\n`, 'utf8');
}

function relativeFile(root, file) {
  const rel = path.relative(root, file);
  return rel && !rel.startsWith('..') ? rel : file;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const root = path.resolve(opts.root);
  const analysis = await analyzeLockfiles(root);
  const familySummary = summarizeFamilies(analysis.duplicateCoordinates, opts.families);
  const allowDuplicateCoordSet = new Set(opts.allowDuplicateCoords);
  // A duplicate coordinate is "unexpected" only when it is a REAL (same-scope)
  // skew that is not explicitly allowlisted. Cross-scope duplicates (versions
  // confined to disjoint scope classes — processor / tool / main) never
  // co-resolve, so they auto-suppress without needing an allowlist entry.
  const unexpectedDuplicateCoordinates = analysis.duplicateCoordinates.filter(
    (row) => !allowDuplicateCoordSet.has(row.coordinate) && !row.crossScope,
  );
  const benignCrossScopeCoordinates = analysis.duplicateCoordinates.filter(
    (row) => row.crossScope && !allowDuplicateCoordSet.has(row.coordinate),
  );

  const report = {
    schema: 'lock-skew-report.v1',
    generatedAt: new Date().toISOString(),
    root,
    lockfileCount: analysis.lockfiles.length,
    duplicateCoordinateCount: analysis.duplicateCoordinates.length,
    topFiles: analysis.filesWithSkew.slice(0, opts.topFiles).map((row) => ({
      file: relativeFile(root, row.file),
      duplicateCoordinateCount: row.duplicateCoordinateCount,
    })),
    topCoordinates: analysis.duplicateCoordinates.slice(0, opts.topCoords).map((row) => ({
      coordinate: row.coordinate,
      versions: row.versions,
      versionCount: row.versionCount,
    })),
    allowDuplicateCoordinates: [...allowDuplicateCoordSet].sort(),
    unexpectedDuplicateCoordinateCount: unexpectedDuplicateCoordinates.length,
    unexpectedTopCoordinates: unexpectedDuplicateCoordinates
      .slice(0, opts.topCoords)
      .map((row) => ({
        coordinate: row.coordinate,
        versions: row.versions,
        versionCount: row.versionCount,
        configsByVersion: row.configsByVersion,
      })),
    // Benign cross-scope duplicates (auto-suppressed): versions live on
    // disjoint scope classes and never co-resolve. Surfaced for transparency.
    benignCrossScopeCoordinates: benignCrossScopeCoordinates.map((row) => ({
      coordinate: row.coordinate,
      versions: row.versions,
      configsByVersion: row.configsByVersion,
    })),
    families: familySummary,
  };

  // eslint-disable-next-line no-console
  console.log(`lockfiles=${report.lockfileCount}`);
  // eslint-disable-next-line no-console
  console.log(`duplicate_coords=${report.duplicateCoordinateCount}`);
  // eslint-disable-next-line no-console
  console.log(`unexpected_duplicate_coords=${report.unexpectedDuplicateCoordinateCount}`);
  // eslint-disable-next-line no-console
  console.log('top_files:');
  for (const row of report.topFiles) {
    // eslint-disable-next-line no-console
    console.log(`  ${row.duplicateCoordinateCount} :: ${row.file}`);
  }
  // eslint-disable-next-line no-console
  console.log('family_summary:');
  for (const family of report.families) {
    // eslint-disable-next-line no-console
    console.log(
      `  ${family.family} -> duplicate_coords=${family.duplicateCoordinateCount} versions=${family.versions.join(',')}`,
    );
  }

  if (opts.outJson) {
    await writeJson(opts.outJson, report);
    // eslint-disable-next-line no-console
    console.log(`json_report=${path.resolve(opts.outJson)}`);
  }

  if (opts.failOnFamilySkew) {
    const hasFamilySkew = report.families.some((f) => f.duplicateCoordinateCount > 0);
    if (hasFamilySkew) {
      // eslint-disable-next-line no-console
      console.error('Lock skew detected for selected families.');
      process.exit(1);
    }
  }
  if (opts.failOnUnexpectedSkew && report.unexpectedDuplicateCoordinateCount > 0) {
    // eslint-disable-next-line no-console
    console.error('Lock skew detected outside allowlisted duplicate coordinates.');
    process.exit(1);
  }
}

main().catch((err) => {
  // eslint-disable-next-line no-console
  console.error(err?.stack || String(err));
  process.exit(1);
});
