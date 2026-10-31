#!/usr/bin/env node
/**
 * surface-composition gate — tempdoc 571 §11 / 578 (host/member one-home integrity).
 *
 * Composition (571 §11 / 578). A host surface declares the member surfaces it presents inside
 * itself (rendered as tabs by `<jf-surface-tabs>`) via `.withMembers(List.of(new SurfaceRef("core.x"), …))`
 * in CoreSurfaceCatalog.java. Membership is the SINGLE home-authority: a member's home is its host, so it
 * is excluded from the rail (Shell) and its deep-link resolves to the host (catalogResolver). This gate
 * forecloses the contradictions that membership-as-an-improvisation used to permit (the original bug:
 * Logs both embedded in Health AND a stray rail icon):
 *
 *   1. a member that is ALSO a RAIL surface           → fail (two homes)
 *   2. a member hosted by TWO hosts                   → fail (ambiguous home)
 *   3. a member ref that resolves to no surface       → fail (dangling)
 *   4. a surface that hosts itself                    → fail
 *
 * This is referential integrity over a DECLARATION, not a derivation — it computes nothing from
 * consumed authority (altitude is the surface-altitude gate's concern; `members` is deliberately NOT an
 * input to SurfaceAltitude.derive, 578 §10 U1).
 *
 * Both sources are comment-stripped before matching: a commented-out or merely discussed registration
 * must not count as a declaration (same technique, and the same reason, as check-window-cutover.mjs).
 *
 * Lighter scripts/ci tier; wired in the `ui-web-gates` recipe (governance/consult-register.v1.json).
 * With zero hosts declared the gate passes trivially (ready for the first host).
 *
 * Run: `node scripts/ci/check-surface-composition.mjs` (from the repo root).
 */
import { readFileSync } from 'node:fs';
import { expandCoreSurfaceMetadata, readCorePluginDeclarations } from '../lib/core-surface-declarations.mjs';

export const REGISTER = 'governance/surface-composition.v1.json';

/** Strip `//` and block comments so a commented-out declaration cannot count as one. */
export function stripComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/[^\n]*/g, '$1');
}

/**
 * Parse `new Surface(...)` declarations out of CoreSurfaceCatalog.java into
 * `{ id, placement, members }`. `.withMembers(List.of(...))` is chained after the constructor
 * closes, so it lives in the same split-segment.
 */
export function parseJavaSurfaces(javaSource) {
  const src = stripComments(javaSource);
  // Constant map: NAME = new SurfaceRef("core.x").
  const refMap = new Map();
  for (const m of src.matchAll(/(\w+)\s*=\s*new\s+SurfaceRef\(\s*"([^"]+)"\s*\)/g)) {
    refMap.set(m[1], m[2]);
  }
  const segments = src.split(/new\s+Surface\(/).slice(1);
  const surfaces = [];
  for (const seg of segments) {
    const idConst = seg.match(/^\s*(\w+)/)?.[1];
    if (!idConst) continue;
    const id = refMap.get(idConst) ?? idConst;
    const placement = seg.match(/Placement\.(\w+)/)?.[1] ?? null;
    let members = [];
    // Extract the balanced `.withMembers(List.of( … ))` region, then pull member refs from it. Balanced
    // extraction (not a non-greedy regex) is required because an inline `new SurfaceRef("…")` member
    // contains its own parens, which a `…?\)\s*\)` match would stop short on.
    const wmIdx = seg.indexOf('.withMembers(');
    if (wmIdx >= 0) {
      let depth = 0;
      let region = '';
      for (let i = seg.indexOf('(', wmIdx); i < seg.length; i++) {
        const ch = seg[i];
        if (ch === '(') depth++;
        else if (ch === ')') {
          depth--;
          if (depth === 0) break;
        }
        region += ch;
      }
      // Inline `new SurfaceRef("core.x")` members → the string; bare `FOO_SURFACE_ID` constants → refMap.
      const seen = new Set();
      for (const m of region.matchAll(/new\s+SurfaceRef\(\s*"([^"]+)"\s*\)/g)) seen.add(m[1]);
      for (const m of region.matchAll(/\b([A-Z][A-Z0-9_]*_SURFACE_ID)\b/g)) {
        const v = refMap.get(m[1]);
        if (v) seen.add(v);
      }
      members = [...seen];
    }
    surfaces.push({ id, placement, members });
  }
  return surfaces;
}

/** Read contributed frontend IDs, including generated metadata, for member resolution. */
export function parseCorePluginIds(pluginSource, generatedSource) {
  const src = expandCoreSurfaceMetadata(pluginSource, generatedSource);
  return new Set([...src.matchAll(/\bid:\s*['"]([^'"]+)['"]/g)].map((m) => m[1]));
}

/** The host/member one-home integrity checks (571 §11 / 578). */
export function checkComposition(javaSurfaces, corePluginIds) {
  // Merged id set (Java catalog ∪ CorePlugin contributions) — the dangling-member resolution authority.
  // Tempdoc 578 Option A: composition spans declaration sources, so a Java host may present a member
  // declared only in the FE contributions (e.g. core.memory-surface).
  const allIds = new Set([...javaSurfaces.map((s) => s.id), ...corePluginIds]);
  // RAIL set is Java-only (the only placement this gate treats as authoritative for the one-home rule);
  // a CorePlugin-only member is governed by runtime membership-exclusion, not a static RAIL assertion.
  const railIds = new Set(javaSurfaces.filter((s) => s.placement === 'RAIL').map((s) => s.id));
  const hostByMember = new Map(); // memberId -> [hostId,…]
  for (const s of javaSurfaces) {
    for (const m of s.members) {
      hostByMember.set(m, [...(hostByMember.get(m) ?? []), s.id]);
    }
  }

  const failures = [];
  for (const s of javaSurfaces) {
    for (const m of s.members) {
      if (m === s.id) {
        failures.push(`${s.id} declares ITSELF as a member — a surface cannot host itself.`);
      }
      if (!allIds.has(m)) {
        failures.push(`${s.id} hosts member '${m}', which resolves to no declared surface (dangling).`);
      }
      if (railIds.has(m)) {
        failures.push(
          `member '${m}' (hosted by ${s.id}) is ALSO a RAIL surface — a member's home is its host. ` +
            `Remove its Placement.RAIL (it stays deep-link routable) so it has exactly one home.`,
        );
      }
    }
  }
  for (const [m, hosts] of hostByMember) {
    if (hosts.length > 1) {
      failures.push(`member '${m}' is hosted by ${hosts.length} hosts (${hosts.join(', ')}) — one home only.`);
    }
  }

  return {
    failures,
    hostCount: javaSurfaces.filter((s) => s.members.length > 0).length,
    memberCount: hostByMember.size,
  };
}

/** Check composition over already-read sources. */
export function run({ javaSource, pluginSource, generatedSource }) {
  const javaSurfaces = parseJavaSurfaces(javaSource);
  const corePluginIds = pluginSource === null ? [] : parseCorePluginIds(pluginSource, generatedSource);
  return checkComposition(javaSurfaces, corePluginIds);
}

function main() {
  const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));
  const paths = { surfaceCatalog: reg.scan.surfaceCatalog, corePlugin: reg.scan.corePlugin };
  const javaSource = readFileSync(paths.surfaceCatalog, 'utf8');
  const pluginSource = paths.corePlugin ? readCorePluginDeclarations(paths.corePlugin) : null;

  const { failures, hostCount, memberCount } = run({ javaSource, pluginSource });

  if (failures.length > 0) {
    console.error('✗ surface-composition gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'));
    process.exit(1);
  }
  console.log(
    `✓ surface-composition gate OK — ${hostCount} host(s), ${memberCount} member(s); every member has ` +
      `exactly one home (its host, off the rail, deep-link-routable). Referential-integrity over the ` +
      `declared host/member relationship (571 §11 / 578).`,
  );
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1].replace(/\\/g, '/')}`).href) {
  main();
}
