/**
 * Contribution-surface enforcer — tempdoc 560 §5 (the prevention keystone that locks in §4c).
 * Sibling of operation-surface / execution-surface, but BARREL-CENTRIC: instead of an import-graph
 * fan-out, it scans the ONE registry FE type barrel (modules/ui-web/src/api/types/registry.ts) for
 * shape + import purity, plus the RegistryController for grandfather coverage.
 *
 * Makes "the registry barrel is a re-export/derivation surface over the GENERATED wire types — never
 * a second shape authority" a build-time invariant: a NEW hand-authored `interface X {}` /
 * `type X = { … }` (the §4c hand-mirror drift class), or a barrel import from a non-generated module
 * (a laundered authority), fails the build. Detection is regex/line-scan over the
 * block-comment-stripped barrel — no TypeScript-compiler dependency, so the gate runs with node
 * builtins like its siblings. The first-token-of-RHS rule distinguishes a legit
 * `type Resource = Omit<ResourceWire,…> & {…}` derivation (RHS starts with `Omit`) from an illegal
 * `type X = { … }` (RHS starts with `{`).
 *
 * HONEST LIMIT (mirrors operation-surface): a hand shape laundered through a path that LOOKS generated,
 * or a deeply-obfuscated alias chain, is residue for review — declared in the register note.
 *
 * Config (registry.v1.json gate.config):
 *   - register: path to governance/contribution-surfaces.v1.json (surfaces + allowlist + scan).
 *   - registry: path to governance/registry.v1.json (to resolve gate:<id> guards).
 */

import { readFileSync, existsSync } from 'node:fs';
import { resolve, relative } from 'node:path';

import { CONTRIBUTION_SURFACE_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import {
  verdictForBarrelPurity,
  verdictForBarrelImportPurity,
  verdictForAllowlistOrphans,
  verdictForSurfaceCoherence,
  verdictForGrandfatherCoverage,
  verdictForGrandfatherDrift,
  verdictForDanglingGuards,
  verdictForProjectionLineage,
  verdictForMissingRegister,
} from './truth-table.mjs';
import { statusToSarifLevel } from '../../lib/truth-table-runner.mjs';
import { verdictForVacuousScan } from '../../lib/population-floor.mjs';
import { walk, parseGuards, loadGateIds } from '../../lib/guard-resolver.mjs';

const TOOL = { toolName: 'justsearch-contribution-surface', toolVersion: '0.1.0' };

export async function enforceContributionSurface(options) {
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

  const registerRel = cfg.register ?? 'governance/contribution-surfaces.v1.json';
  const registerAbs = resolve(root, registerRel);
  if (!existsSync(registerAbs)) {
    push(verdictForMissingRegister({ path: registerRel }), registerRel);
    return { ...TOOL, findings, verdict, ruleDescriptions: CONTRIBUTION_SURFACE_RULE_DESCRIPTIONS };
  }
  const register = JSON.parse(readFileSync(registerAbs, 'utf8'));
  const scan = register.scan ?? {};
  const surfaces = Array.isArray(register.surfaces) ? register.surfaces : [];
  const grandfathered = Array.isArray(register.grandfatheredPending)
    ? register.grandfatheredPending
    : [];
  const allowlist = Array.isArray(register.registryAllowlist) ? register.registryAllowlist : [];
  const allowSet = new Set(allowlist.map((a) => a.name).filter(Boolean));

  // The registry FE type barrels (registry.ts + diagnostic.ts + future sibling barrels) are governed
  // identically. `scan.barrel` may be a single path (string, back-compat) or a list.
  const barrelRels = Array.isArray(scan.barrel)
    ? scan.barrel
    : [scan.barrel ?? 'modules/ui-web/src/api/types/registry.ts'];
  const barrels = barrelRels.map((rel) => {
    const abs = resolve(root, rel);
    const src = existsSync(abs) ? readFileSync(abs, 'utf8') : '';
    return { rel, src, noComments: stripBlockComments(src) };
  });
  // --- §5 vacuous-pass guard (tempdoc 576): the barrel source the purity scan inspects must not have
  // silently collapsed to empty (every configured barrel renamed/moved → src '' → the purity scan
  // passes vacuously). The allowlist-orphan check below also fires on a collapse when the allowlist is
  // non-empty; this floor closes the residual (empty-allowlist) case uniformly. ---
  push(
    verdictForVacuousScan({
      rulePrefix: 'contribution-surface',
      detected: barrels.filter((b) => b.noComments.trim().length > 0).length,
      min: scan.expectedMinPopulation ?? 1,
      what: 'non-empty registry type barrels',
    }),
    barrelRels.join(', '),
  );

  const referencedInAnyBarrel = (name) =>
    barrels.some((b) => new RegExp(`\\b${escapeRe(name)}\\b`).test(b.noComments));
  const declaredInAnyBarrel = (name) => barrels.some((b) => declaresName(b.noComments, name));
  const barrelTag = (rel) => norm(rel).split('/').pop();

  // --- Check 1 (KEYSTONE): registry-barrel-purity. A hand-authored wire-object shape (a bare
  // `interface X {}` or `type X = { … }`) not on the allowlist is a second shape authority. The
  // first-token-of-RHS rule (`= {` vs `= Omit<…>`) keeps the legit Omit<Wire>&{…} derivations clean. ---
  const purityViolations = [];
  for (const b of barrels) {
    const tag = barrelTag(b.rel);
    for (const [name] of matchAll(b.noComments, RE_EXPORT_INTERFACE)) {
      if (!allowSet.has(name)) {
        purityViolations.push(`${tag}: interface ${name} (hand-authored object — not Wire-derived, not allowlisted)`);
      }
    }
    for (const [name] of matchAll(b.noComments, RE_EXPORT_TYPE_OBJECT)) {
      if (!allowSet.has(name)) {
        purityViolations.push(`${tag}: type ${name} = { … } (bare object-literal alias — derive from a generated wire instead)`);
      }
    }
  }
  push(verdictForBarrelPurity({ violations: purityViolations.sort() }), barrelRels.join(', '));

  // --- Check 2: registry-barrel-import-purity. A barrel may only import from the generated projection,
  // zod, or a sibling registry barrel (the latter allowed via scan.barrelImportAllow, e.g. './registry'). ---
  const importAllow = Array.isArray(scan.barrelImportAllow)
    ? scan.barrelImportAllow
    : ['../generated/', 'zod'];
  const importViolations = [];
  for (const b of barrels) {
    const tag = barrelTag(b.rel);
    for (const [mod] of matchAll(b.src, RE_IMPORT_FROM)) {
      if (!importAllow.some((p) => mod === p || mod.includes(p))) {
        importViolations.push(`${tag}: import from "${mod}" (not a generated / zod / sibling-barrel module)`);
      }
    }
  }
  push(verdictForBarrelImportPurity({ violations: importViolations.sort() }), barrelRels.join(', '));

  // --- Check 3: allowlist-orphan. Every allowlist name must be declared in some barrel. ---
  const orphans = [...allowSet].filter((name) => !declaredInAnyBarrel(name)).sort();
  push(verdictForAllowlistOrphans({ orphans }), registerRel);

  // --- Check 4: surface-projection-coherence. Each generated surface's module must exist + its
  // wireType must be referenced by the barrel (the projection it claims actually backs the FE). ---
  const coherenceProblems = [];
  for (const s of surfaces) {
    if (s.projection !== 'generated' && s.projection !== 'generated-nested') continue;
    if (s.path && !existsSync(resolve(root, s.path))) {
      coherenceProblems.push(`${s.id}: generatedModule ${s.path} missing`);
    }
    if (s.wireType && !referencedInAnyBarrel(s.wireType)) {
      coherenceProblems.push(`${s.id}: no barrel references wireType ${s.wireType}`);
    }
  }
  push(verdictForSurfaceCoherence({ problems: coherenceProblems.sort() }), registerRel);

  // --- Check 5: grandfather-coverage. Each grandfathered servedRaw handler must still exist. ---
  const controllerRel = scan.registryController ?? 'modules/ui/src/main/java/io/justsearch/ui/api/RegistryController.java';
  const controllerAbs = resolve(root, controllerRel);
  const controllerSrc = existsSync(controllerAbs) ? readFileSync(controllerAbs, 'utf8') : '';
  const missingHandlers = grandfathered
    // Require the call/declaration syntax `handler(` — a bare comment mention must not mask removal.
    .filter((g) => g.servedRaw && !controllerSrc.includes(`${g.servedRaw}(`))
    .map((g) => `${g.id}: ${g.servedRaw} (handler absent in ${relative(root, controllerAbs).replace(/\\/g, '/')})`)
    .sort();
  push(verdictForGrandfatherCoverage({ missing: missingHandlers }), controllerRel);

  // --- Check 6: grandfather-drift. A grandfathered primitive that GAINED a generated module must be
  // promoted to surfaces[] (it is now projected — the register must say so). ---
  const generatedDir = scan.generatedDir ?? 'modules/ui-web/src/api/generated/schema-types';
  const promoted = grandfathered
    .filter((g) => g.potentialModule && existsSync(resolve(root, generatedDir, `${g.potentialModule}.ts`)))
    .map((g) => `${g.id} (gained ${generatedDir}/${g.potentialModule}.ts)`)
    .sort();
  push(verdictForGrandfatherDrift({ promoted }), registerRel);

  // --- Check 7: dangling guards (gate:<id> / test:<Name> on a surface must resolve). ---
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

  // --- Check 8: projection lineage (every surface declares a resolvable semantic source). ---
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

  return { ...TOOL, findings, verdict, ruleDescriptions: CONTRIBUTION_SURFACE_RULE_DESCRIPTIONS };
}

// ── barrel scan regexes (run on the block-comment-stripped source, line-anchored) ──
// `export interface <Name>` — interfaces are always object bodies; a hand-authored wire shape.
const RE_EXPORT_INTERFACE = /(?:^|\n)[ \t]*export[ \t]+interface[ \t]+(\w+)/g;
// `export type <Name> = {` — a bare object-literal alias (the RHS's first token is `{`). A
// derivation like `= Omit<Wire,…> & {…}` does NOT match (the RHS starts with `Omit`, not `{`).
const RE_EXPORT_TYPE_OBJECT = /(?:^|\n)[ \t]*export[ \t]+type[ \t]+(\w+)[ \t]*=[ \t\r\n]*\{/g;
// `import … from '<module>'` (single- or multi-line). Re-exports (`export { x }` with no `from`) skip.
const RE_IMPORT_FROM = /(?:^|\n)[ \t]*import[\s\S]*?from[ \t]*['"]([^'"]+)['"]/g;

/** Remove block comments (`/* … *\/`, incl. JSDoc) so a doc-comment `export interface` can't false-trip. */
function stripBlockComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '');
}

/** True if the barrel declares `export (interface|type|const) <name>`. */
function declaresName(src, name) {
  return new RegExp(`(?:^|\\n)[ \\t]*export[ \\t]+(interface|type|const)[ \\t]+${escapeRe(name)}\\b`).test(src);
}

function* matchAll(src, re) {
  re.lastIndex = 0;
  let m;
  while ((m = re.exec(src)) !== null) yield m.slice(1);
}

function escapeRe(s) {
  return String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function norm(p) {
  return String(p ?? '').replace(/\\/g, '/');
}

/** True if some *<name>* test file (Java/Python/TS spec) exists under modules/ or scripts/. */
function testFileExists(root, name) {
  const isTestFile = (f) =>
    f.endsWith('.java') || f.endsWith('.py') || f.endsWith('.test.ts') || f.endsWith('.test.tsx');
  for (const r of ['modules', 'scripts']) {
    for (const abs of walk(resolve(root, r), isTestFile)) {
      const base = norm(abs).split('/').pop();
      if (base.includes(name)) return true;
    }
  }
  return false;
}
