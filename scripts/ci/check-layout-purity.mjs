#!/usr/bin/env node
/**
 * layout-purity gate — tempdoc 559 Authority I (spatial / surface-shell layout).
 *
 * The collapse landed, so this gate now ENFORCES the single authority outright —
 * no ratchet baseline / grandfathering (the prior baseline file is deleted):
 *
 *  (1) **Surface coverage (catalog-projected).** Every `jf-*-surface` element's
 *      layout flows from the one `SurfaceLayout` authority: each surface file
 *      either composes `surfaceLayoutStyles` / `surfaceScrollLayoutStyles`, OR is
 *      a `display: contents` pass-through (its view owns the layout, e.g.
 *      AgentSurface→AgentView). A new surface that hand-rolls `:host` layout fails.
 *
 *  (2) **Overlay placement.** `position: fixed` in component `css\`\`` is allowed
 *      ONLY in the OverlayHost (the slot authority) or in a declared
 *      **positioning-class** — anchored (cursor/trigger-relative popovers),
 *      dialog (native <dialog> top-layer / dynamically-instantiated modals), or
 *      toast (dynamically-instantiated). These are NOT a migration backlog: they
 *      are *kinds of positioning the OverlayHost slots deliberately do not own*
 *      (they self-position relative to a trigger, the dialog top layer, or their
 *      instantiation site). A NEW viewport-docked overlay that free-positions
 *      instead of docking into an OverlayHost slot fails the build.
 *
 * Coverage = the full ui-web component tree (a new file is scanned automatically).
 * Lighter scripts/ci tier; wired as a ci.yml step + the pre-merge checks table (docs/reference/contributing/pre-merge-checks.md).
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname } from 'node:path';
import { stripComments } from '../lib/strip-comments.mjs';

const SRC = 'modules/ui-web/src';
const OWNER = 'shell-v0/chrome/OverlayHost.ts'; // the slot authority
const REGISTER = 'governance/overlay-positioning-classes.v1.json';

// 559 §5.2 — the exemption coverage PROJECTS from a catalog (not a hardcoded list
// here): the positioning-class taxonomy the OverlayHost slots deliberately do NOT
// own (anchored/dialog/toast). A new exempt overlay declares itself in the
// register, not by editing this gate.
const EXEMPT = (() => {
  try {
    const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));
    return new Map((reg.exempt ?? []).map((e) => [e.file, e.class]));
  } catch {
    console.error(`✗ layout-purity gate: cannot read the exemption register ${REGISTER}`);
    process.exit(1);
  }
})();

const files = [];
(function walk(d) {
  for (const e of readdirSync(d)) {
    const p = join(d, e).replace(/\\/g, '/');
    const s = statSync(p);
    if (s.isDirectory()) {
      if (e === 'node_modules' || e === 'generated') continue;
      walk(p);
    } else if (extname(p) === '.ts' && !/\.(test|spec)\.ts$/.test(p)) {
      files.push(p);
    }
  }
})(SRC);

const rel = (p) => p.slice(SRC.length + 1);
const failures = [];

// (1) surface coverage
const SURFACE_DEF = /customElements\.define\(\s*['"`]jf-[a-z0-9-]+-surface['"`]/;
for (const f of files) {
  const src = readFileSync(f, 'utf8');
  if (!SURFACE_DEF.test(src)) continue;
  const composes =
    src.includes('surfaceLayoutStyles') || src.includes('surfaceScrollLayoutStyles');
  const passThrough = /:host\s*\{[^}]*display:\s*contents/.test(src);
  if (!composes && !passThrough) {
    failures.push(
      `${rel(f)}: a jf-*-surface must compose the SurfaceLayout authority ` +
        `(import surfaceLayoutStyles/surfaceScrollLayoutStyles) or be a display:contents pass-through (559 Authority I).`,
    );
  }
}

// (2) overlay placement. Strip comments first so a prose mention of
// "position:fixed" in a doc-comment is not a false positive.
const FIXED = /position:\s*fixed/;
for (const f of files) {
  const r = rel(f);
  if (r === OWNER) continue;
  if (EXEMPT.has(r)) continue;
  if (FIXED.test(stripComments(readFileSync(f, 'utf8')))) {
    failures.push(
      `${r}: free \`position: fixed\` — dock into an OverlayHost slot (559 Authority I), ` +
        `or, if it is a genuinely different positioning class (anchored / dialog / toast), ` +
        `declare it in check-layout-purity.mjs EXEMPT with its class.`,
    );
  }
}

if (failures.length > 0) {
  console.error('✗ layout-purity gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'));
  process.exit(1);
}
console.log(
  `✓ layout-purity gate OK — every jf-*-surface composes SurfaceLayout; ` +
    `no free position:fixed outside the OverlayHost + the ${EXEMPT.size} declared anchored/dialog/toast classes.`,
);
