#!/usr/bin/env node
/**
 * surface-composition gate — tempdoc 571 §11 / 578 (the host/member composition one-home integrity),
 * extended by tempdoc 852 S0 with the FE↔Java surface-parity leg.
 *
 * LEG 1 — composition (571 §11 / 578). A host surface declares the member surfaces it presents inside
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
 * LEG 2 — FE↔Java parity (tempdoc 852 §0.0, S0). A surface declared in BOTH files must agree on
 * `audience` and `placement`. The hole this closes is specific and it is the one the Search v3 promotion
 * walks through: the `interaction-surface` gate parses `CoreSurfaceCatalog.java` ONLY, and
 * `check-window-cutover` reads `CorePlugin.ts` ONLY. Neither can see a ONE-SIDED flip, so an implementer
 * who flips audience in just one file ships two USER/RAIL interaction windows (or a window the FE shows
 * and the wire does not) while every gate stays green — and satisfies the cutover forcing function while
 * doing it. This gate already loaded both files for leg 1's dangling-member resolution, so the parity
 * check is a leg on existing plumbing rather than a new gate. It ships four slices BEFORE the flip it
 * protects, because a safety introduced in the same commit that needs it has never been observed working.
 *
 * Both sources are comment-stripped before matching: a commented-out or merely discussed registration
 * must not count as a declaration (same technique, and the same reason, as check-window-cutover.mjs).
 *
 * Lighter scripts/ci tier; wired in the `ui-web-gates` recipe (governance/consult-register.v1.json).
 * With zero hosts declared leg 1 passes trivially (ready for the first host).
 *
 * Run: `node scripts/ci/check-surface-composition.mjs` (from the repo root).
 */
import { readFileSync } from 'node:fs';
import { expandCoreSurfaceMetadata, readCorePluginDeclarations } from '../lib/core-surface-declarations.mjs';

export const REGISTER = 'governance/surface-composition.v1.json';

/**
 * Pre-existing FE↔Java disagreements that predate this leg (tempdoc 852 S0), recorded rather than
 * silently tolerated. Each entry PINS the exact pair of values observed when the leg landed, so:
 *
 *   - observed pair === pinned pair  → WARN (a known, unresolved drift, not a new one)
 *   - observed pair !== pinned pair  → FAIL (either it was fixed and this entry is now stale and must be
 *                                      deleted, or it drifted FURTHER and that is new drift)
 *
 * The ledger therefore cannot be used to wave through a new one-sided flip, and it retires itself the
 * moment the underlying disagreement is settled. `core.search-v3-surface` and `core.unified-chat-surface`
 * are deliberately NOT here — those are the ids S0 exists to protect.
 */
export const KNOWN_PARITY_DRIFT = [];

/** Strip `//` and block comments so a commented-out declaration cannot count as one. */
export function stripComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/[^\n]*/g, '$1');
}

/**
 * Parse `new Surface(...)` declarations out of CoreSurfaceCatalog.java into
 * `{ id, audience, placement, members }`. `.withMembers(List.of(...))` is chained after the constructor
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
    const audience = seg.match(/Audience\.(\w+)/)?.[1] ?? null;
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
    surfaces.push({ id, audience, placement, members });
  }
  return surfaces;
}

/**
 * Parse the FE surface contributions out of CorePlugin.ts into `id -> { audience, placement }`.
 * Each contribution's fields are read from the object literal that carries the id, bounded by that
 * entry's own terminator, so a neighbouring contribution's audience cannot be mistaken for this one's
 * (the bounding technique check-window-cutover.mjs uses for the same file).
 */
export function parseCorePluginSurfaces(pluginSource, generatedSource) {
  const src = expandCoreSurfaceMetadata(pluginSource, generatedSource);
  const byId = new Map();
  for (const part of src.split(/\bid:\s*/).slice(1)) {
    const id = part.match(/^['"]([^'"]+)['"]/)?.[1];
    if (!id) continue;
    const end = /\n\s*\},/.exec(part);
    const block = end === null ? part : part.slice(0, end.index);
    byId.set(id, {
      audience: [...block.matchAll(/audience:\s*['"]([A-Z]+)['"]/g)].at(-1)?.[1] ?? null,
      placement: [...block.matchAll(/placement:\s*['"]([A-Z]+)['"]/g)].at(-1)?.[1] ?? null,
    });
  }
  return byId;
}

const CATALOG_HINT = 'CoreSurfaceCatalog.java';

/**
 * LEG 2 — every surface declared in BOTH files must agree on `audience` and `placement`.
 * A field is compared only when both sides declare it: an absent declaration is not a disagreement,
 * and neither declaration form makes either field optional in practice.
 */
export function checkParity(javaSurfaces, corePluginSurfaces, paths, ledger = KNOWN_PARITY_DRIFT) {
  const failures = [];
  const warnings = [];
  const fePath = paths.corePlugin;
  const javaPath = paths.surfaceCatalog;
  const ledgerById = new Map(ledger.map((e) => [e.id, e]));
  const seenLedgerIds = new Set();
  let compared = 0;

  const rationale =
    'A surface declared in both files must agree: a ONE-SIDED flip ships a broken or duplicated USER '
    + 'window while every other gate stays green — the `interaction-surface` gate reads only '
    + `${CATALOG_HINT} and check-window-cutover reads only CorePlugin.ts, so neither can see it `
    + '(tempdoc 852 S0 — the pre-flip safety for the Search v3 promotion). Move BOTH declarations in '
    + 'the same commit.';

  for (const s of javaSurfaces) {
    const fe = corePluginSurfaces.get(s.id);
    if (!fe) continue; // Java-only surface: no second declaration to disagree with. Out of scope.
    compared += 1;
    const disagreements = [];
    for (const field of ['audience', 'placement']) {
      const feValue = fe[field];
      const javaValue = s[field];
      if (feValue === null || javaValue === null) continue;
      if (feValue !== javaValue) disagreements.push({ field, feValue, javaValue });
    }

    const pinned = ledgerById.get(s.id);
    if (pinned) {
      seenLedgerIds.add(s.id);
      const matchesPin =
        fe.audience === pinned.corePlugin.audience
        && fe.placement === pinned.corePlugin.placement
        && s.audience === pinned.surfaceCatalog.audience
        && s.placement === pinned.surfaceCatalog.placement;
      if (matchesPin) {
        if (disagreements.length > 0) {
          warnings.push(
            `${s.id} still disagrees across its two declarations (`
              + disagreements
                .map((d) => `${d.field}: '${d.feValue}' in ${fePath} vs '${d.javaValue}' in ${javaPath}`)
                .join('; ')
              + `). This is a RECORDED pre-existing drift, not a new one — ${pinned.note} It warns here `
              + 'so it stays visible; settle it and delete the KNOWN_PARITY_DRIFT entry.',
          );
        }
        continue;
      }
      failures.push(
        `${s.id} has a stale KNOWN_PARITY_DRIFT entry: the recorded pair was `
          + `${fePath} ${pinned.corePlugin.audience}/${pinned.corePlugin.placement} vs `
          + `${javaPath} ${pinned.surfaceCatalog.audience}/${pinned.surfaceCatalog.placement}, but the `
          + `declarations now read ${fe.audience}/${fe.placement} vs ${s.audience}/${s.placement}. `
          + (disagreements.length === 0
            ? 'The disagreement is settled — DELETE the entry; an exemption outliving its reason is '
              + 'false authority.'
            : 'The disagreement CHANGED, which is new drift and not what the entry exempts. '
              + rationale),
      );
      continue;
    }

    for (const d of disagreements) {
      failures.push(
        `${s.id} declares ${d.field} '${d.feValue}' in ${fePath} but '${d.javaValue}' in ${javaPath}. `
          + rationale,
      );
    }
  }

  for (const entry of ledger) {
    if (seenLedgerIds.has(entry.id)) continue;
    failures.push(
      `KNOWN_PARITY_DRIFT names '${entry.id}', which is no longer declared in both ${fePath} and `
        + `${javaPath}. DELETE the entry — an exemption for a pair that no longer exists is residue.`,
    );
  }

  return { failures, warnings, compared };
}

/** LEG 1 — the host/member one-home integrity checks (571 §11 / 578), unchanged. */
export function checkComposition(javaSurfaces, corePluginIds) {
  // Merged id set (Java catalog ∪ CorePlugin contributions) — the dangling-member resolution authority.
  // Tempdoc 578 Option A: composition spans declaration sources, so a Java host may present a member
  // declared only in the FE contributions (e.g. core.memory-surface).
  const allIds = new Set([...javaSurfaces.map((s) => s.id), ...corePluginIds]);
  // RAIL set is Java-only (the only placement this leg treats as authoritative for the one-home rule);
  // a CorePlugin-only member is governed by runtime membership-exclusion, not a static RAIL assertion.
  // (Leg 2 is what now stops the two sources from disagreeing about placement in the first place.)
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

/** Both legs over already-read sources. Returns `{ failures, warnings, hostCount, memberCount, compared }`. */
export function run({ javaSource, pluginSource, generatedSource, paths, ledger = KNOWN_PARITY_DRIFT }) {
  const javaSurfaces = parseJavaSurfaces(javaSource);
  const corePluginSurfaces = pluginSource === null ? new Map() : parseCorePluginSurfaces(pluginSource, generatedSource);
  const composition = checkComposition(javaSurfaces, corePluginSurfaces.keys());
  const parity =
    pluginSource === null
      ? { failures: [], warnings: [], compared: 0 }
      : checkParity(javaSurfaces, corePluginSurfaces, paths, ledger);
  return {
    failures: [...composition.failures, ...parity.failures],
    warnings: parity.warnings,
    hostCount: composition.hostCount,
    memberCount: composition.memberCount,
    compared: parity.compared,
  };
}

function main() {
  const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));
  const paths = { surfaceCatalog: reg.scan.surfaceCatalog, corePlugin: reg.scan.corePlugin };
  const javaSource = readFileSync(paths.surfaceCatalog, 'utf8');
  const pluginSource = paths.corePlugin ? readCorePluginDeclarations(paths.corePlugin) : null;

  const { failures, warnings, hostCount, memberCount, compared } = run({ javaSource, pluginSource, paths });

  for (const w of warnings) console.warn('surface-composition gate WARN:\n  - ' + w);
  if (failures.length > 0) {
    console.error('✗ surface-composition gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'));
    process.exit(1);
  }
  console.log(
    `✓ surface-composition gate OK — ${hostCount} host(s), ${memberCount} member(s); every member has ` +
      `exactly one home (its host, off the rail, deep-link-routable). Referential-integrity over the ` +
      `declared host/member relationship (571 §11 / 578). FE↔Java parity: ${compared} surface(s) ` +
      `declared in both files checked for audience + placement agreement, ${warnings.length} recorded ` +
      `pre-existing disagreement(s) still open (852 S0).`,
  );
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1].replace(/\\/g, '/')}`).href) {
  main();
}
