/**
 * SSOT catalog-sync enforcer — discipline-gate kernel gate kind.
 *
 * Mechanizes the "SSOT catalog drift" caveat (docs/explanation/04-storage-engine.md): the root SSOT
 * catalog files and their classpath copies under
 * `modules/adapters-lucene/src/main/resources/SSOT/catalogs/` must stay
 * identical, or production (which loads the classpath copy) silently drops
 * fields. The advisory `ssot-hint` PostToolUse hook warned about this at ~70%
 * adherence; this gate enforces it at ~100%.
 *
 * Mirrors are declared in `gates/ssot-catalog-sync/mirrors.json` (the gate's
 * `baseline.path`):
 *   { "mirrors": [ { "id": "fields", "root": "SSOT/catalogs/fields.v1.json",
 *                    "copy": "modules/.../fields.v1.json", "kind": "json" } ] }
 *
 * `kind: "json"` compares parsed structure (order-insensitive); `kind: "text"`
 * compares with normalized line endings (the synonyms CRLF/LF nuance logged in
 * observations.md). Verdicts come from `truth-table.mjs`; baseline-tampering
 * (a mirror silently removed from the config) is caught against the baseline ref.
 */

import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

import {
  SSOT_SYNC_CLASSIFICATIONS,
  SSOT_SYNC_REQUIRE_JUSTIFICATION,
  indexClassificationsByMirror,
} from './classifications.mjs';
import { SSOT_SYNC_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import { verdictForMirror, verdictForMirrorRemoval } from './truth-table.mjs';
import { loadChangesets } from '../../lib/changeset-loader.mjs';
import { readFileAtRef } from '../../lib/git-utils.mjs';

export async function enforceSsotCatalogSync(options) {
  const { repoRoot, gate, baselineRef, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;

  let mirrors;
  try {
    mirrors = loadMirrors(resolve(sourceRoot, gate.baseline.path));
  } catch (e) {
    return failGate(`${gate.baseline.path} failed to parse: ${e.message}. Fix the JSON.`, 'ssot-catalog-sync/malformed-mirrors', gate);
  }

  const declarations = gate.changesetsDir
    ? loadChangesets({
        repoRoot: sourceRoot,
        changesetsDir: gate.changesetsDir,
        baselineRef,
        allowedClassifications: SSOT_SYNC_CLASSIFICATIONS,
        classificationField: 'classification',
        requireJustificationFor: SSOT_SYNC_REQUIRE_JUSTIFICATION,
        fixtureMode,
      })
    : [];
  const classByMirror = indexClassificationsByMirror(declarations);
  const liveIds = new Set(mirrors.map((m) => m.id));

  const findings = [];
  let verdict = 'pass';

  for (const m of mirrors) {
    const rootAbs = resolve(sourceRoot, m.root);
    const copyAbs = resolve(sourceRoot, m.copy);
    const rootExists = existsSync(rootAbs);
    const copyExists = existsSync(copyAbs);
    const equal = rootExists && copyExists && filesEqual(rootAbs, copyAbs, m.kind);
    const classification = classByMirror.get(m.id) ?? null;

    const v = verdictForMirror({ id: m.id, rootExists, copyExists, equal, classification });
    if (v.status === 'fail') verdict = 'fail';
    if (v.ruleId !== 'ssot-catalog-sync/in-sync') {
      findings.push({ ruleId: v.ruleId, level: v.status === 'fail' ? 'error' : 'note', message: v.reason, uri: m.root });
    }
  }

  // Baseline-tampering: a mirror present at the baseline ref but gone from live config.
  const priorMirrors = readPriorMirrors({ fixtureMode, fixtureRoot, repoRoot, baselineRef, baselinePath: gate.baseline.path });
  if (priorMirrors) {
    for (const prior of priorMirrors) {
      if (!liveIds.has(prior.id)) {
        const v = verdictForMirrorRemoval({ id: prior.id, classification: classByMirror.get(prior.id) ?? null });
        if (v.status === 'fail') verdict = 'fail';
        findings.push({ ruleId: v.ruleId, level: v.status === 'fail' ? 'error' : 'note', message: v.reason, uri: gate.baseline.path });
      }
    }
  }

  return {
    toolName: 'justsearch-ssot-catalog-sync',
    toolVersion: '0.1.0',
    findings,
    verdict,
    ruleDescriptions: SSOT_SYNC_RULE_DESCRIPTIONS,
  };
}

function failGate(message, ruleId, gate) {
  return {
    toolName: 'justsearch-ssot-catalog-sync',
    toolVersion: '0.1.0',
    findings: [{ ruleId, level: 'error', message, uri: gate.baseline.path }],
    verdict: 'fail',
    ruleDescriptions: SSOT_SYNC_RULE_DESCRIPTIONS,
  };
}

function loadMirrors(p) {
  if (!existsSync(p)) return [];
  const parsed = JSON.parse(readFileSync(p, 'utf8'));
  return Array.isArray(parsed.mirrors) ? parsed.mirrors : [];
}

function readPriorMirrors({ fixtureMode, fixtureRoot, repoRoot, baselineRef, baselinePath }) {
  let content = null;
  if (fixtureMode) {
    if (!fixtureRoot) return null;
    const f = resolve(fixtureRoot, '_baseline', baselinePath);
    if (!existsSync(f)) return null;
    content = readFileSync(f, 'utf8');
  } else {
    if (!baselineRef) return null;
    content = readFileAtRef(baselineRef, baselinePath, repoRoot);
    if (content === null) return null;
  }
  try {
    const parsed = JSON.parse(content);
    return Array.isArray(parsed.mirrors) ? parsed.mirrors : [];
  } catch {
    return null;
  }
}

/** Compare two files per kind. json → parsed deep-equal; text → CRLF-normalized. */
function filesEqual(rootAbs, copyAbs, kind) {
  let a, b;
  try {
    a = readFileSync(rootAbs, 'utf8');
    b = readFileSync(copyAbs, 'utf8');
  } catch {
    return false;
  }
  if (kind === 'json') {
    try {
      return stableStringify(JSON.parse(a)) === stableStringify(JSON.parse(b));
    } catch {
      // One side is malformed JSON — fall back to text compare so the drift surfaces.
      return normalizeText(a) === normalizeText(b);
    }
  }
  return normalizeText(a) === normalizeText(b);
}

/** Normalize line endings (CRLF→LF) and trailing whitespace for text compare. */
function normalizeText(s) {
  return s.replace(/\r\n/g, '\n').replace(/[ \t]+$/gm, '').replace(/\n+$/, '\n');
}

/** Deterministic JSON serialization with recursively sorted object keys. */
function stableStringify(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return '[' + value.map(stableStringify).join(',') + ']';
  const keys = Object.keys(value).sort();
  return '{' + keys.map((k) => JSON.stringify(k) + ':' + stableStringify(value[k])).join(',') + '}';
}
