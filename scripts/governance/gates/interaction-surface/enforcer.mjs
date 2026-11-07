/**
 * Interaction-surface enforcer — tempdoc 561 surface tier (the one-interaction-window gate).
 *
 * Makes "there is exactly ONE visible (USER-audience, RAIL/STAGE) interaction surface — the one
 * window — and every core direct-LLM interaction shape routes into it" a build-time invariant. This
 * is the recurrence guard for the 561 surface-tier defect class: a second visible interaction
 * surface (the standalone core.agent-surface alongside core.unified-chat-surface) — two authorities
 * for one concept, neither subordinate (548's class at the surface tier).
 *
 * Authority: CoreConversationShapeCatalog.CORE_USER_INTERACTION_SHAPES (Java) declares the core
 * interaction-shape set ONCE. The gate projects it over CoreSurfaceCatalog.java (the surface ×
 * placement × conversationShapes declarations) and the FE (registerViewFactory call sites). No
 * second copy of the shape list lives in the register.
 *
 * Config (registry.v1.json gate.config):
 *   - register: path to governance/interaction-surfaces.v1.json (scan config + canonical window).
 */

import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { resolve, join } from 'node:path';

import { INTERACTION_SURFACE_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import {
  verdictForMissingRegister,
  verdictForMissingSource,
  verdictForEmptyCoreSet,
  verdictForMirrorDrift,
  verdictForMultipleInteractionSurfaces,
  verdictForCanonicalWindow,
  verdictForUncoveredShapes,
  verdictForSecondInteractionView,
} from './truth-table.mjs';
import { statusToSarifLevel } from '../../lib/truth-table-runner.mjs';
import { verdictForVacuousScan } from '../../lib/population-floor.mjs';

const TOOL = { toolName: 'justsearch-interaction-surface', toolVersion: '0.1.0' };
const WALK_EXCLUDES = new Set(['build', 'node_modules', 'dist', '.git', '.gradle', 'tmp']);

export async function enforceInteractionSurface(options) {
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
  const done = () => ({ ...TOOL, findings, verdict, ruleDescriptions: INTERACTION_SURFACE_RULE_DESCRIPTIONS });

  const registerRel = cfg.register ?? 'governance/interaction-surfaces.v1.json';
  const registerAbs = resolve(root, registerRel);
  if (!existsSync(registerAbs)) {
    push(verdictForMissingRegister({ path: registerRel }), registerRel);
    return done();
  }
  const register = JSON.parse(readFileSync(registerAbs, 'utf8'));
  const scan = register.scan ?? {};
  const canonical = register.canonicalSurface;
  const canonicalMountTag = register.canonicalMountTag;
  const visiblePlacements = new Set(
    Array.isArray(register.visiblePlacements) ? register.visiblePlacements : ['RAIL', 'STAGE'],
  );

  const surfaceCatalogRel = scan.surfaceCatalog;
  const shapeCatalogRel = scan.shapeCatalog;
  const surfaceCatalogAbs = surfaceCatalogRel ? resolve(root, surfaceCatalogRel) : null;
  const shapeCatalogAbs = shapeCatalogRel ? resolve(root, shapeCatalogRel) : null;

  if (!surfaceCatalogAbs || !existsSync(surfaceCatalogAbs)) {
    push(verdictForMissingSource({ kind: 'surface catalog', path: surfaceCatalogRel ?? '(unset)' }), surfaceCatalogRel);
    return done();
  }
  if (!shapeCatalogAbs || !existsSync(shapeCatalogAbs)) {
    push(verdictForMissingSource({ kind: 'shape catalog', path: shapeCatalogRel ?? '(unset)' }), shapeCatalogRel);
    return done();
  }

  // --- Authority: the core interaction-shape set (Java CORE_USER_INTERACTION_SHAPES). ---
  const coreSet = parseCoreInteractionShapes(readFileSync(shapeCatalogAbs, 'utf8'));
  if (coreSet.size === 0) {
    push(verdictForEmptyCoreSet({ shapeCatalogPath: shapeCatalogRel }), shapeCatalogRel);
    return done();
  }
  // --- §5 vacuous-pass floor (tempdoc 576): the parsed core-shape set must not silently shrink below a
  // declared floor (a parser-breaking catalog refactor would otherwise let the coverage checks below
  // pass on a near-empty set). The size===0 hard-stop above is the catastrophic case; this is the
  // partial-collapse floor, via the shared helper. ---
  push(
    verdictForVacuousScan({
      rulePrefix: 'interaction-surface',
      detected: coreSet.size,
      min: scan.expectedMinPopulation ?? 1,
      what: 'core interaction shapes parsed from the shape catalog',
    }),
    shapeCatalogRel,
  );

  // --- Check: FE mirror sync (the value list, not the type). ---
  const feMirrorRel = scan.feMirror;
  const feMirrorAbs = feMirrorRel ? resolve(root, feMirrorRel) : null;
  if (feMirrorAbs && existsSync(feMirrorAbs)) {
    const feSet = parseFeMirror(readFileSync(feMirrorAbs, 'utf8'));
    const onlyInJava = [...coreSet].filter((s) => !feSet.has(s)).sort();
    const onlyInFe = [...feSet].filter((s) => !coreSet.has(s)).sort();
    push(verdictForMirrorDrift({ onlyInJava, onlyInFe }), feMirrorRel);
  }

  // --- Parse the surface declarations: id × audience × placement × conversationShapes. ---
  const surfaces = parseSurfaces(readFileSync(surfaceCatalogAbs, 'utf8'));

  // Visible interaction surfaces: USER audience, a visible placement, consuming ≥1 core shape.
  const visibleInteraction = surfaces
    .filter(
      (s) =>
        s.audience === 'USER' &&
        visiblePlacements.has(s.placement) &&
        s.shapes.some((sh) => coreSet.has(sh)),
    )
    .map((s) => s.id)
    .sort();

  // --- Check: cardinality (THE invariant) — at most one visible interaction surface. ---
  push(verdictForMultipleInteractionSurfaces({ surfaces: visibleInteraction, canonical }), surfaceCatalogRel);

  // --- Check: the visible interaction surface is the canonical one window. ---
  push(verdictForCanonicalWindow({ canonical, visibleSurfaces: visibleInteraction }), surfaceCatalogRel);

  // --- Check: every core shape is consumed by the one window. ---
  const canonicalSurface = surfaces.find((s) => s.id === canonical);
  const canonicalShapes = new Set(canonicalSurface ? canonicalSurface.shapes : []);
  const uncovered = [...coreSet].filter((sh) => !canonicalShapes.has(sh)).sort();
  push(verdictForUncoveredShapes({ uncovered, canonical }), surfaceCatalogRel);

  // --- Check (FE backstop): no core shape registers a view to a non-one-window tag. ---
  const feRoots = Array.isArray(scan.feRoots) ? scan.feRoots : [];
  const excl = Array.isArray(scan.feExcludeSuffixes) ? scan.feExcludeSuffixes : ['.test.ts', '.test.tsx'];
  const violations = scanSecondViews(root, feRoots, excl, coreSet, canonicalMountTag);
  push(verdictForSecondInteractionView({ violations: violations.sort(), canonicalMountTag }), feRoots[0] ?? registerRel);

  return done();
}

/** Parse `CORE_USER_INTERACTION_SHAPES = Set.of("core.a", "core.b", ...)` from the Java catalog. */
function parseCoreInteractionShapes(src) {
  const m = src.match(/CORE_USER_INTERACTION_SHAPES\s*=\s*Set\.of\(([\s\S]*?)\)/);
  if (!m) return new Set();
  return new Set([...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]));
}

/** Parse `CORE_INTERACTION_SHAPES = [ 'core.a', ... ] as const` from the FE mirror. */
function parseFeMirror(src) {
  const m = src.match(/CORE_INTERACTION_SHAPES\s*=\s*\[([\s\S]*?)\]/);
  if (!m) return new Set();
  return new Set([...m[1].matchAll(/['"]([^'"]+)['"]/g)].map((x) => x[1]));
}

/**
 * Parse every `new Surface(...)` declaration into { id, audience, placement, shapes }. Splitting on
 * the `new Surface(` token bounds each declaration to the text before the next one, so the FIRST
 * Audience/Placement/conversationShapes match in each segment belongs to that surface. Identifier
 * args (surface id, shape refs) are resolved to their "core.*" string via the SurfaceRef /
 * ConversationShapeRef constant map built from the same file.
 */
function parseSurfaces(src) {
  const constMap = new Map();
  for (const m of src.matchAll(/(\w+)\s*=\s*new\s+(?:SurfaceRef|ConversationShapeRef)\(\s*"([^"]+)"\s*\)/g)) {
    constMap.set(m[1], m[2]);
  }
  const out = [];
  const segments = src.split(/new\s+Surface\(/).slice(1);
  for (const seg of segments) {
    const idConst = seg.match(/^\s*(\w+)/)?.[1];
    const id = idConst ? constMap.get(idConst) ?? null : null;
    if (!id) continue;
    const audience = seg.match(/Audience\.(\w+)/)?.[1] ?? null;
    const placement = seg.match(/Placement\.(\w+)/)?.[1] ?? null;
    const shapesM = seg.match(/conversationShapes\s*\*\/\s*Set\.of\(([^)]*)\)/);
    const shapes = shapesM
      ? shapesM[1]
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean)
          .map((c) => constMap.get(c))
          .filter(Boolean)
      : [];
    out.push({ id, audience, placement, shapes });
  }
  return out;
}

/**
 * Scan FE roots (non-test .ts/.tsx) for `registerViewFactory('<shape>', <tag>)` calls. A call for a
 * core interaction shape whose RESOLVED tag is not the canonical one-window tag is a second-view
 * violation. The second arg may be a quoted literal or a `jf-*` tag-constant identifier; constants
 * are resolved from a map built across the same FE files. An unresolvable identifier tag is skipped
 * (honest residue — the shape-view-coverage gate already guarantees a registration exists).
 */
function scanSecondViews(root, feRoots, excludeSuffixes, coreSet, canonicalMountTag) {
  const files = [];
  for (const r of feRoots) {
    for (const abs of walk(resolve(root, r), (f) => f.endsWith('.ts') || f.endsWith('.tsx'))) {
      if (excludeSuffixes.some((suf) => abs.endsWith(suf))) continue;
      files.push(abs);
    }
  }
  // Tag-constant map: IDENT = 'jf-...'  (covers `const ONE_WINDOW_MOUNT_TAG = 'jf-unified-chat-view'`).
  const tagConsts = new Map();
  const sources = new Map();
  for (const abs of files) {
    const src = readFileSync(abs, 'utf8');
    sources.set(abs, src);
    for (const m of src.matchAll(/(\w+)\s*[:=]\s*['"](jf-[a-z0-9-]+)['"]/g)) {
      tagConsts.set(m[1], m[2]);
    }
  }
  const violations = [];
  for (const src of sources.values()) {
    for (const m of src.matchAll(/registerViewFactory\(\s*['"]([^'"]+)['"]\s*,\s*([^)]+?)\s*\)/g)) {
      const shape = m[1];
      if (!coreSet.has(shape)) continue;
      const arg = m[2].trim();
      const lit = arg.match(/^['"]([^'"]+)['"]$/);
      const resolved = lit ? lit[1] : tagConsts.get(arg);
      if (resolved !== undefined && resolved !== canonicalMountTag) {
        violations.push(`${shape} → ${resolved} (expected ${canonicalMountTag})`);
      }
    }
  }
  return violations;
}

function walk(dir, pred, accum = []) {
  if (!existsSync(dir)) return accum;
  for (const entry of readdirSync(dir)) {
    if (WALK_EXCLUDES.has(entry)) continue;
    const full = join(dir, entry);
    let st;
    try {
      st = statSync(full);
    } catch {
      continue;
    }
    if (st.isDirectory()) walk(full, pred, accum);
    else if (pred(full)) accum.push(full);
  }
  return accum;
}
