/**
 * Contract-projection enforcer — tempdoc 564 facet 4e (the meta-substrate gate).
 *
 * The keystone of the 564 design on the 530 kernel: it makes "every migrated wire record is the
 * single generated projection, never drifts, and has no hand copy" a unified discipline-gate
 * invariant rather than a scatter of bespoke standalone tests. Like execution-surface it is a
 * META-COORDINATOR — it delegates to the proven contract-projection checks (the regen drift check
 * and the single-authority mandate check) and adds a coverage check, then projects their results
 * into the kernel's verdict/finding shape.
 *
 * No `selfTestFixturesDir` is declared (the checks scan the real repo, as befits a meta-gate), so
 * the runner skips self-test for this gate; the truth-table.mjs sibling still satisfies the kernel's
 * shape discipline.
 */

import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { relative, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

import { CONTRACT_PROJECTION_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import { importsGeneratedRecord } from './imports.mjs';
import {
  verdictForSchemaTypesDrift,
  verdictForDuplicateWireType,
  verdictForGeneratedCoverage,
  verdictForRegisterCoherence,
  verdictForDeclaredConsumers,
  verdictForUndeclaredConsumers,
} from './truth-table.mjs';
import { statusToSarifLevel } from '../../lib/truth-table-runner.mjs';

const TOOL = { toolName: 'justsearch-contract-projection', toolVersion: '0.1.0' };

const GEN_SCRIPT = 'scripts/codegen/gen-wire-schema-types.mjs';
const AUTHORITY_SCRIPT = 'scripts/ci/check-wire-type-single-authority.mjs';
const REGISTER = 'governance/contract-surfaces.v1.json';

function norm(p) {
  return p.split(sep).join('/');
}

function listFiles(dir, excludeNames, acc) {
  for (const entry of readdirSync(dir)) {
    const p = resolve(dir, entry);
    const st = statSync(p);
    if (st.isDirectory()) {
      if (excludeNames.has(entry)) continue;
      listFiles(p, excludeNames, acc);
    } else if (/\.tsx?$/.test(p) && !/\.test\.tsx?$/.test(p)) {
      acc.push(p);
    }
  }
  return acc;
}

export async function enforceContractProjection(options) {
  const { repoRoot } = options;
  const findings = [];
  let verdict = 'pass';
  const push = (v, uri) => {
    if (v.status === 'fail') {
      verdict = 'fail';
      findings.push({ ruleId: v.ruleId, level: statusToSarifLevel(v.status), message: v.reason, uri });
    }
  };

  // --- Check 1: generated schema-types are fresh (no drift / no hand-edit). ---
  const regen = spawnSync('node', [resolve(repoRoot, GEN_SCRIPT), '--check'], {
    cwd: repoRoot,
    encoding: 'utf8',
  });
  push(
    verdictForSchemaTypesDrift({
      ok: regen.status === 0,
      detail: (regen.stdout || '') + (regen.stderr || ''),
    }),
    GEN_SCRIPT,
  );

  // --- Check 2: no hand-authored copy of a migrated wire type (the mandate). ---
  const authority = spawnSync('node', [resolve(repoRoot, AUTHORITY_SCRIPT)], {
    cwd: repoRoot,
    encoding: 'utf8',
  });
  push(
    verdictForDuplicateWireType({
      ok: authority.status === 0,
      detail: (authority.stdout || '') + (authority.stderr || ''),
    }),
    AUTHORITY_SCRIPT,
  );

  // --- Check 3: every declared wire record has a source schema + a generated file. ---
  const mod = await import(pathToFileURL(resolve(repoRoot, GEN_SCRIPT)).href);
  const missing = [];
  for (const target of mod.TARGETS) {
    const schemaAbs = resolve(repoRoot, target.schema);
    const outAbs = resolve(mod.OUTPUT_DIR, target.outFile);
    if (!existsSync(schemaAbs)) missing.push(`${target.rootName} (source schema ${target.schema})`);
    if (!existsSync(outAbs)) missing.push(`${target.rootName} (generated ${target.outFile})`);
  }
  push(verdictForGeneratedCoverage({ missing }), GEN_SCRIPT);

  // --- The 4e register checks: the FE-contract as a declared 530 surface. ---
  const registerAbs = resolve(repoRoot, REGISTER);
  if (existsSync(registerAbs)) {
    const register = JSON.parse(readFileSync(registerAbs, 'utf8'));
    const records = Array.isArray(register.records) ? register.records : [];
    const generatedDir = resolve(repoRoot, register.generatedDir ?? 'modules/ui-web/src/api/generated/schema-types');
    const barrelPath = resolve(generatedDir, 'index.ts');
    const barrelText = existsSync(barrelPath) ? readFileSync(barrelPath, 'utf8') : '';

    // Check 4: register ↔ TARGETS coherence (same record set).
    const registerNames = new Set(records.map((r) => r.name));
    const targetNames = new Set(mod.TARGETS.map((t) => t.rootName));
    push(
      verdictForRegisterCoherence({
        onlyInRegister: [...registerNames].filter((n) => !targetNames.has(n)).sort(),
        onlyInTargets: [...targetNames].filter((n) => !registerNames.has(n)).sort(),
      }),
      REGISTER,
    );

    // Check 5: declared consumers exist + import the record's generated module.
    const brokenConsumers = [];
    const declaredByModule = new Map(); // outFile base (no ext) → Set of consumer rel-paths
    for (const rec of records) {
      const moduleBase = String(rec.outFile || '').replace(/\.ts$/, '');
      declaredByModule.set(moduleBase, new Set((rec.consumers || []).map(norm)));
      for (const consumer of rec.consumers || []) {
        const consumerAbs = resolve(repoRoot, consumer);
        if (!existsSync(consumerAbs)) {
          brokenConsumers.push(`${rec.name} → ${consumer} (missing)`);
          continue;
        }
        const text = readFileSync(consumerAbs, 'utf8');
        // An IMPORT, not a mention: a declared consumer that only names the module in a doc
        // comment is a false register entry, and this is what says so (884 review S5).
        if (!importsGeneratedRecord(text, moduleBase, barrelText)) {
          brokenConsumers.push(`${rec.name} → ${consumer} (no import of schema-types/${moduleBase})`);
        }
      }
    }
    push(verdictForDeclaredConsumers({ brokenConsumers }), REGISTER);

    // Check 6: every FE file importing a generated wire module is a declared consumer of it.
    const scan = register.scan ?? {};
    const roots = scan.roots ?? ['modules/ui-web/src'];
    const excludeNames = new Set(['generated', '__fixtures__', 'node_modules', 'build', 'dist']);
    const undeclared = [];
    for (const root of roots) {
      const rootAbs = resolve(repoRoot, root);
      if (!existsSync(rootAbs)) continue;
      for (const fileAbs of listFiles(rootAbs, excludeNames, [])) {
        const rel = norm(relative(repoRoot, fileAbs));
        const sourceText = readFileSync(fileAbs, 'utf8');
        for (const [moduleBase, consumers] of declaredByModule) {
          if (importsGeneratedRecord(sourceText, moduleBase, barrelText) && !consumers.has(rel)) {
            undeclared.push(`${rel} (imports schema-types/${moduleBase})`);
          }
        }
      }
    }
    push(verdictForUndeclaredConsumers({ undeclared: [...new Set(undeclared)].sort() }), REGISTER);
  }

  return { ...TOOL, findings, verdict, ruleDescriptions: CONTRACT_PROJECTION_RULE_DESCRIPTIONS };
}
