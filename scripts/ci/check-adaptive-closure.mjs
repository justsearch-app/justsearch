#!/usr/bin/env node
/**
 * adaptive-closure gate — tempdoc 559 Part II, Authority VI (Adaptivity).
 *
 * An item-bearing chrome region must overflow its declared-priority items into a
 * "…" affordance, not clip them. The status bar is the FIRST such region, but the
 * gate's coverage is NOT hardcoded to it — it projects from the adaptive-region
 * register (`governance/adaptive-regions.v1.json`), so a second region is covered
 * the moment its row is added (548 §5.2: coverage from a catalog). For each
 * registered region this gate enforces the single adaptive authority:
 *
 *  (1) **The item catalog declares the overflow order.** The region's item type
 *      (e.g. `StatusBarItem`) must carry the declared `priorityField` — the
 *      projection an overflow policy reads.
 *  (2) **The region uses the adaptive primitive.** The renderer must compose
 *      `OverflowController` (the register's `primitive`) — so it overflows by
 *      priority instead of laying items in a fixed, clippable row.
 *  (3) **No naked clip.** The renderer's `:host` must NOT carry `overflow:
 *      hidden` (the silent ≤720px clip the adaptive primitive replaces).
 *
 * Tempdoc 565 §19 — the FILL half. Beyond the OVERFLOW regions above, the register declares
 * `densityElements`: elements that render at MULTIPLE SCALES and must project their REPRESENTATION from
 * their measured box (below the legible threshold a glyph collapses to a dot, so an illegible-at-scale
 * glyph is unrepresentable). For each it enforces: (D1) the density primitive exists
 * (`class DensityController`); (D2) the element composes it (so it self-adapts rather than hand-fixing
 * one representation that fails small).
 *
 * HONEST LIMIT (§5): the register IS the catalog of adaptive regions / density elements; a NEW
 * item-bearing chrome region OR multi-scale element must be ADDED to it (and built from the primitive)
 * — that addition is the discovery step. Lighter scripts/ci tier; wired as a
 * ci.yml step + the pre-merge checks table (docs/reference/contributing/pre-merge-checks.md).
 */
import { readFileSync } from 'node:fs';

const REGISTER = 'governance/adaptive-regions.v1.json';

const failures = [];
const read = (f) => {
  try {
    return readFileSync(f, 'utf8');
  } catch {
    failures.push(`adaptive-closure: cannot read ${f}`);
    return '';
  }
};

let register;
try {
  register = JSON.parse(readFileSync(REGISTER, 'utf8'));
} catch (e) {
  console.error(`✗ adaptive-closure gate FAILED — cannot read/parse ${REGISTER}: ${e.message}`);
  process.exit(1);
}

const primitiveSrc = read(register.primitive);
// The primitive the renderers must compose (file declares `class OverflowController`).
if (!/class OverflowController\b/.test(primitiveSrc)) {
  failures.push(
    `adaptive-closure: the adaptive primitive (OverflowController in ${register.primitive}) is missing (559 Authority VI).`,
  );
}

const regions = Array.isArray(register.regions) ? register.regions : [];
if (regions.length === 0) {
  failures.push(`adaptive-closure: ${REGISTER} declares no adaptive regions (559 Authority VI).`);
}

for (const region of regions) {
  const { id, registryFile, registryType, priorityField, rendererFile } = region;
  const registry = read(registryFile);
  const renderer = read(rendererFile);

  // (1) the item catalog declares the overflow order (priority field on the item type).
  const priorityRe = new RegExp(
    `interface ${registryType}\\b[\\s\\S]*?\\b${priorityField}\\b\\s*:`,
  );
  if (!priorityRe.test(registry)) {
    failures.push(
      `adaptive-closure[${id}]: ${registryType} (${registryFile}) must declare a \`${priorityField}\` ` +
        `field — the overflow order the adaptive bar projects from (559 Authority VI).`,
    );
  }

  // (2) the region's renderer composes the adaptive primitive.
  if (!/OverflowController/.test(renderer)) {
    failures.push(
      `adaptive-closure[${id}]: ${rendererFile} must compose the OverflowController adaptive primitive — ` +
        `the region overflows its lowest-priority items into "…" instead of clipping (559 Authority VI).`,
    );
  }

  // (3) no naked clip on the renderer's :host.
  const hostBlock = (renderer.match(/:host\s*\{[\s\S]*?\}/) || [''])[0];
  if (/overflow:\s*hidden/.test(hostBlock)) {
    failures.push(
      `adaptive-closure[${id}]: ${rendererFile} \`:host\` carries \`overflow: hidden\` — that silently clips ` +
        `items at narrow widths; let the adaptive primitive overflow them into "…" (559 Authority VI).`,
    );
  }
}

// Tempdoc 565 §19 — the FILL half: density-adaptive elements project their representation from their
// measured box, via the DensityController sibling of OverflowController.
const densityElements = Array.isArray(register.densityElements) ? register.densityElements : [];
if (register.densityPrimitive || densityElements.length > 0) {
  const densitySrc = read(register.densityPrimitive);
  // (D1) the density primitive the elements must compose (file declares `class DensityController`).
  if (!/class DensityController\b/.test(densitySrc)) {
    failures.push(
      `adaptive-closure: the density primitive (DensityController in ${register.densityPrimitive}) is ` +
        `missing — the FILL half of the Adaptivity authority (565 §19).`,
    );
  }
  for (const el of densityElements) {
    const { id, elementFile } = el;
    const elementSrc = read(elementFile);
    // (D2) the multi-scale element composes the density primitive (self-adapts its representation).
    if (!/DensityController/.test(elementSrc)) {
      failures.push(
        `adaptive-closure[density:${id}]: ${elementFile} must compose the DensityController density ` +
          `primitive — it renders at multiple scales and must project its representation from the ` +
          `measured box (below the legible threshold a glyph collapses to a dot), not hand-fix one ` +
          `representation that fails small (565 §19).`,
      );
    }
  }
}

if (failures.length > 0) {
  console.error('✗ adaptive-closure gate FAILED:\n' + failures.map((f) => '  - ' + f).join('\n'));
  process.exit(1);
}
console.log(
  `✓ adaptive-closure gate OK — ${regions.length} registered adaptive region(s); each item catalog declares ` +
    `its priority field, each renderer composes the adaptive primitive, and none naked-clips (559 Authority VI). ` +
    `${densityElements.length} density element(s) compose the DensityController FILL-half primitive (565 §19).`,
);
