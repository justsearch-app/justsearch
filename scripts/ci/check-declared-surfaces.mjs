#!/usr/bin/env node
/**
 * declared-surfaces gate — tempdoc 569 §16 #1 (the gate keeping the inversion inverted).
 *
 * 569's central claim: "the prevention is the register/gate whose coverage projects from the
 * authority's catalog." §14/§15 made four surfaces (Settings, Library, Help, Health) declaration-
 * default under ONE built-in declaration (CORE_DECLARED): each renders its region(s) through the
 * DeclaredSurface engine (`activeBodyFor(regionId)` -> `<jf-declared-surface>`) instead of hand-
 * painted Lit. Without a gate, that inversion is a SNAPSHOT — nothing stops a future agent reverting
 * `HealthSurface.renderConnection` to hand-Lit, or forking the `metric-card` logic into a second
 * renderer (the exact two-authority drift 569 exists to foreclose). This gate locks the CATALOG's
 * positive coverage (`governance/declared-surfaces.v1.json`):
 *
 *   (a) every declared region's surface actually mounts the engine for that region — it imports
 *       `activeBodyFor`, references its `regionConst`, and mounts `<jf-declared-surface>`. A
 *       regression that reverts a surface to pure hand-Lit drops the mount and FAILS here.
 *   (b) every `x-ui-renderer` hint CORE_DECLARED's bodies reference resolves to EXACTLY ONE
 *       registered renderer (`registerXUiRenderer('<hint>', '<tag>')`) — a duplicate/forked renderer
 *       for a declared hint FAILS.
 *
 * HONEST SCOPE (mirrors composition-surfaces §13.3 / 565 §12.10 — POSITIVE-COVERAGE catalog, not a
 * scan). The gate asserts the engine path is PRESENT. It deliberately does NOT try to detect "is a
 * second hand-rolled authority co-existing alongside the mount" — the allowed degrade-never-fail
 * fallback render is syntactically indistinguishable from a forbidden fork, so a blanket scan is
 * infeasible (the same lesson as the §3.B status-tone gate). The teeth are positive: rip out the
 * mount and the gate bites. Honest ceiling: an import-invisible re-model slips — early-warning
 * register, the accepted norm for every presentation gate. A NEW declaration-default region/renderer
 * is a discovery step (add the row + route it through the engine), review-gated. Lighter scripts/ci
 * tier; wired as a ci.yml step + the pre-merge checks table (docs/reference/contributing/pre-merge-checks.md).
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { stripComments } from '../lib/strip-comments.mjs';

const REGISTER = 'governance/declared-surfaces.v1.json';
const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));

const norm = (p) => p.replace(/\\/g, '/');

const reEscape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

// Collect every non-test .ts file under a root (recursive).
const collectTs = (root) => {
  const out = [];
  const walk = (dir) => {
    for (const ent of readdirSync(dir)) {
      const p = join(dir, ent);
      const st = statSync(p);
      if (st.isDirectory()) {
        if (ent === 'node_modules' || ent === 'generated') continue;
        walk(p);
      } else if (/\.ts$/.test(ent) && !/\.(test|spec)\.ts$/.test(ent)) {
        out.push(norm(p));
      }
    }
  };
  walk(root);
  return out;
};

const failures = [];

// (a) Positive coverage: every declared region's surface mounts the engine for that region.
const ENGINE = reg.engine.activeBodyForSymbol;
const MOUNT = reg.engine.mountTag;
for (const region of reg.regions) {
  const f = norm(region.surface);
  const src = stripComments(readFileSync(f, 'utf8'));
  const missing = [];
  if (!new RegExp(`\\b${reEscape(ENGINE)}\\b`).test(src)) missing.push(`imports ${ENGINE}`);
  if (!new RegExp(`\\b${reEscape(region.regionConst)}\\b`).test(src))
    missing.push(`references ${region.regionConst}`);
  if (!src.includes(`<${MOUNT}`)) missing.push(`mounts <${MOUNT}>`);
  if (missing.length > 0) {
    failures.push(
      `${f}: declared the declaration-default authority for region "${region.id}" but does NOT ` +
        `[${missing.join(', ')}] — the region must render through the engine ` +
        `(${ENGINE}("${region.id}") -> <${MOUNT}>), 569 §16 #1(a). A surface reverted to hand-Lit ` +
        `drops the engine mount; re-route it through the engine or remove the register row.`,
    );
  }
}

// (b) Positive coverage: every declared x-ui-renderer hint resolves to EXACTLY ONE renderer.
const rendererFiles = collectTs(reg.scan.renderersRoot);
for (const r of reg.renderers) {
  const re = new RegExp(`registerXUiRenderer\\(\\s*['"]${reEscape(r.hint)}['"]`, 'g');
  const hits = [];
  for (const f of rendererFiles) {
    const src = stripComments(readFileSync(f, 'utf8'));
    const count = (src.match(re) || []).length;
    for (let i = 0; i < count; i++) hits.push(f);
  }
  if (hits.length === 0) {
    failures.push(
      `hint "${r.hint}": declared in CORE_DECLARED but NO registerXUiRenderer('${r.hint}', ...) ` +
        `found under ${reg.scan.renderersRoot} — register it (expected ${r.module} -> '${r.tag}'), 569 §16 #1(b).`,
    );
  } else if (hits.length > 1) {
    failures.push(
      `hint "${r.hint}": registered ${hits.length} times (${hits.join(', ')}) — a declared hint must ` +
        `resolve to EXACTLY ONE renderer (no fork), 569 §16 #1(b).`,
    );
  } else {
    // exactly one — confirm it is the declared module + tag
    const src = stripComments(readFileSync(norm(r.module), 'utf8'));
    if (!new RegExp(`registerXUiRenderer\\(\\s*['"]${reEscape(r.hint)}['"]\\s*,\\s*['"]${reEscape(r.tag)}['"]`).test(src)) {
      failures.push(
        `hint "${r.hint}": the sole registration is not ${r.module} -> '${r.tag}' as declared ` +
          `(found at ${hits[0]}) — align the register with the renderer, 569 §16 #1(b).`,
      );
    }
  }
}

if (failures.length > 0) {
  console.error(
    '✗ declared-surfaces gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'),
  );
  process.exit(1);
}
console.log(
  `✓ declared-surfaces gate OK — ${reg.regions.length} declaration-default region(s) all mount the ` +
    `one presentation engine (${ENGINE} -> <${MOUNT}>), and ${reg.renderers.length} declared ` +
    `x-ui-renderer hint(s) each resolve to exactly one registered renderer. Positive-coverage ` +
    `catalog; the anti-drift teeth are positive (a surface reverted to hand-Lit drops the mount and ` +
    `fails); a new declaration-default region/renderer is a discovery-step (569 §16 #1).`,
);
