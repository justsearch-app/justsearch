/**
 * Operation-surface enforcer — tempdoc 550 Thesis III (the anti-fragmentation gate for the
 * operation / indexing-job lifecycle). The sibling of tempdoc 553's execution-surface gate,
 * generalized from "what the search pipeline did" to "what indexing/system operations are
 * queued / running / done".
 *
 * Makes "every surface that reports indexing-job lifecycle state is a DECLARED projection of the
 * one canonical record (the worker jobs table / IndexingJobView), never an independent model" a
 * build-time invariant. This is the recurrence guard for the F-2 / §B.2 drift class (two surfaces
 * reporting pending/running from independent sources). Field/shape conformance stays in the guards
 * the register names (test:RemoteIndexingJobsBridgeTest, test:IndexingJobsChangeStreamTest).
 *
 * Config (registry.v1.json gate.config):
 *   - register: path to governance/operation-surfaces.v1.json (the allowlist + scan config).
 *   - registry: path to governance/registry.v1.json (to resolve gate:<id> guards).
 */

import { readFileSync, existsSync } from 'node:fs';
import { resolve, relative } from 'node:path';

import { OPERATION_SURFACE_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import {
  verdictForUndeclaredSurfaces,
  verdictForOrphanSurfaces,
  verdictForDanglingGuards,
  verdictForProjectionLineage,
  verdictForForbiddenReintroduction,
  verdictForMissingRegister,
} from './truth-table.mjs';
import { statusToSarifLevel } from '../../lib/truth-table-runner.mjs';
import { verdictForVacuousScan } from '../../lib/population-floor.mjs';
import { walk, parseGuards, loadGateIds } from '../../lib/guard-resolver.mjs';

const TOOL = { toolName: 'justsearch-operation-surface', toolVersion: '0.1.0' };

export async function enforceOperationSurface(options) {
  const { repoRoot, gate, fixtureMode = false, fixtureRoot } = options;
  const root = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const cfg = gate.config ?? {};

  const findings = [];
  let verdict = 'pass';
  const push = (v, uri) => {
    if (v.status === 'fail') {
      verdict = 'fail';
      findings.push({ ruleId: v.ruleId, level: statusToSarifLevel(v.status), message: v.reason, uri });
    }
  };

  const registerRel = cfg.register ?? 'governance/operation-surfaces.v1.json';
  const registerAbs = resolve(root, registerRel);
  if (!existsSync(registerAbs)) {
    push(verdictForMissingRegister({ path: registerRel }), registerRel);
    return { ...TOOL, findings, verdict, ruleDescriptions: OPERATION_SURFACE_RULE_DESCRIPTIONS };
  }
  const register = JSON.parse(readFileSync(registerAbs, 'utf8'));
  const surfaces = Array.isArray(register.surfaces) ? register.surfaces : [];
  const declared = new Set(surfaces.map((s) => norm(s.path)));
  const scan = register.scan ?? {};

  // --- Check 1: undeclared surfaces (auto-scan Java-main + TS for canonical-type references). ---
  const detected = new Set();
  for (const f of scanJava(root, scan)) detected.add(f);
  for (const f of scanTs(root, scan)) detected.add(f);
  // --- §5 vacuous-pass guard (tempdoc 576): the auto-scan must not have silently collapsed to zero. ---
  push(
    verdictForVacuousScan({
      rulePrefix: 'operation-surface',
      detected: detected.size,
      min: scan.expectedMinPopulation ?? 1,
      what: 'lifecycle-record-referencing files',
    }),
    registerRel,
  );
  const undeclared = [...detected].filter((p) => !declared.has(p)).sort();
  push(verdictForUndeclaredSurfaces({ undeclared }), registerRel);

  // --- Check 2: orphan surfaces (registered path no longer exists). ---
  const orphans = surfaces
    .filter((s) => existsSync(resolve(root, s.path)) === false)
    .map((s) => s.path)
    .sort();
  push(verdictForOrphanSurfaces({ orphans }), registerRel);

  // --- Check 3: dangling guards (gate:<id> / test:<Name> must resolve). ---
  const gateIds = loadGateIds(root, cfg.registry ?? 'governance/registry.v1.json');
  const dangling = [];
  for (const s of surfaces) {
    for (const token of parseGuards(s.guard)) {
      if (token.kind === 'gate' && !gateIds.has(token.value)) {
        dangling.push(`${s.id}: gate:${token.value} (no such gate)`);
      } else if (token.kind === 'test' && !testFileExists(root, token.value)) {
        dangling.push(`${s.id}: test:${token.value} (no matching test file)`);
      }
    }
  }
  push(verdictForDanglingGuards({ dangling: dangling.sort() }), registerRel);

  // --- Check 4: projection lineage (every surface names a resolvable semantic source). ---
  const ids = new Set(surfaces.map((s) => s.id).filter(Boolean));
  const missingLineage = [];
  const danglingLineage = [];
  for (const s of surfaces) {
    const c = s.consumesProjection;
    if (typeof c !== 'string' || c.trim() === '') {
      missingLineage.push(s.id);
      continue;
    }
    if (c !== 'canonical-record' && c !== 'self' && !ids.has(c)) {
      danglingLineage.push(`${s.id}: consumesProjection "${c}" (no such surface id)`);
    }
  }
  push(
    verdictForProjectionLineage({ missing: missingLineage.sort(), dangling: danglingLineage.sort() }),
    registerRel,
  );

  // --- Check 5: forbidden second-authority reintroduction (tempdoc 561 P-C / §11). The import-scan
  // cannot see a new-vocabulary write-store; named patterns catch the exact fork class §11 removed. ---
  const forbidden = Array.isArray(register.forbiddenReintroduction)
    ? register.forbiddenReintroduction
    : [];
  const violations = [];
  if (forbidden.length > 0) {
    const allFiles = [
      ...walk(resolve(root, 'modules'), (f) => f.endsWith('.java') || f.endsWith('.ts')),
    ].map((abs) => norm(relative(root, abs)));
    for (const rule of forbidden) {
      const re = new RegExp(rule.pattern);
      const allow = new Set((rule.allow ?? []).map(norm));
      for (const rel of allFiles) {
        if (re.test(rel) && !allow.has(rel)) {
          violations.push(`${rel} matches forbidden "${rule.pattern}" — canonical authority: ${rule.canonical}`);
        }
      }
    }
  }
  push(verdictForForbiddenReintroduction({ violations: violations.sort() }), registerRel);

  return { ...TOOL, findings, verdict, ruleDescriptions: OPERATION_SURFACE_RULE_DESCRIPTIONS };
}
function norm(p) {
  return String(p ?? '').replace(/\\/g, '/');
}

/** Java-main files whose source contains an `import <pattern>;` of the canonical type. */
function scanJava(root, scan) {
  const patterns = Array.isArray(scan.javaImportPatterns) ? scan.javaImportPatterns : [];
  const roots = Array.isArray(scan.javaMainRoots) ? scan.javaMainRoots : [];
  const include = scan.javaInclude ?? '/src/main/java/';
  if (patterns.length === 0) return [];
  const out = [];
  for (const r of roots) {
    for (const abs of walk(resolve(root, r), (f) => f.endsWith('.java'))) {
      const rel = norm(relative(root, abs));
      if (!rel.includes(include.replace(/\\/g, '/'))) continue;
      const src = readFileSync(abs, 'utf8');
      if (patterns.some((p) => src.includes(`import ${p};`) || src.includes(`import ${p}.`))) {
        out.push(rel);
      }
    }
  }
  return out;
}

/** TS files (non-test) under the configured roots that reference the canonical type as an identifier. */
function scanTs(root, scan) {
  const roots = Array.isArray(scan.tsRoots) ? scan.tsRoots : [];
  const excl = Array.isArray(scan.tsExcludeSuffixes) ? scan.tsExcludeSuffixes : [];
  const pat = scan.tsRefPattern ?? 'IndexingJobView';
  const re = new RegExp(`\\b${pat}\\b`);
  const out = [];
  for (const r of roots) {
    for (const abs of walk(resolve(root, r), (f) => f.endsWith('.ts') || f.endsWith('.tsx'))) {
      const rel = norm(relative(root, abs));
      if (excl.some((s) => rel.endsWith(s))) continue;
      if (re.test(readFileSync(abs, 'utf8'))) out.push(rel);
    }
  }
  return out;
}

/**
 * True if some *<name>* test file exists under modules/ or scripts/. Recognizes Java/Python
 * (backend surface guards) AND TS/TSX (FE surface guards — e.g. a `test:Foo.test.ts` naming a
 * Vitest spec), so an FE projection surface can name a real regression guard like any other.
 */
function testFileExists(root, name) {
  const isTestFile = (f) =>
    f.endsWith('.java') ||
    f.endsWith('.py') ||
    f.endsWith('.test.ts') ||
    f.endsWith('.test.tsx');
  for (const r of ['modules', 'scripts']) {
    for (const abs of walk(resolve(root, r), isTestFile)) {
      const base = abs.replace(/\\/g, '/').split('/').pop();
      if (base.includes(name)) return true;
    }
  }
  return false;
}
