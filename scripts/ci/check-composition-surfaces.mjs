#!/usr/bin/env node
/**
 * composition-surfaces gate — tempdoc 565 §13 (the spatial-composition tier's anti-drift).
 *
 * A multi-zone layout (a CSS grid with 2+ top-level tracks composing distinct content zones — the
 * agent window's trace-spine | reading | evidence frame) is GENERATED from a declared zone-set by the
 * ONE Composition primitive (`composeGridStyles`, declared in `governance/composition-surfaces.v1.json`).
 * Because a governed adopter routes its grid through the primitive, the hand-authored grid for that
 * content does not exist to drift (564 "Gate is the floor, not the mechanism" — generation, not grep,
 * is the anti-drift). This gate is the EARLY-WARNING that a NEW file hand-authors a multi-track
 * `grid-template-columns` WITHOUT composing the primitive — register it (route it through
 * composeGridStyles) only with review.
 *
 * HONEST SCOPE (§13.3 / §12.10 — POSITIVE-COVERAGE catalog, not a scan). A multi-zone composition
 * FRAME (the agent window's trace-spine | reading | evidence) is syntactically indistinguishable from
 * a bespoke leaf grid (a label+value pair, a card grid, a toolbar) — those are zone CONTENTS, NOT the
 * governed frame (the 559 §8 cut), and the ui-web tree has many of them, all legitimate. So a blanket
 * "flag every multi-track grid" scan is infeasible (the same lesson as the §3.B status-tone gate). The
 * anti-drift teeth are in GENERATION (a governed adopter routes its frame through composeGridStyles, so
 * the hand-authored grid for that content does not exist to drift). This gate locks the CATALOG's
 * positive coverage: every declared `adopters` entry must actually compose the primitive. A NEW
 * governed multi-zone composition is a discovery step — add it to `adopters` + route it through the
 * primitive — review-gated, not scan-gated. Lighter scripts/ci tier; wired as a ci.yml step +
 * the pre-merge checks table (docs/reference/contributing/pre-merge-checks.md).
 */
import { readFileSync } from 'node:fs';
import { stripComments } from '../lib/strip-comments.mjs';

const REGISTER = 'governance/composition-surfaces.v1.json';
const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));

const norm = (p) => p.replace(/\\/g, '/');
const SYMBOL = new RegExp(`\\b${reg.primitive.symbol}\\b`);

const failures = [];

// Positive coverage: every registered governed multi-zone composition routes through the generator.
for (const adopter of reg.adopters) {
  const f = norm(adopter);
  const src = stripComments(readFileSync(f, 'utf8'));
  if (!SYMBOL.test(src)) {
    failures.push(
      `${f}: declared a composition-surfaces adopter but does NOT compose ${reg.primitive.symbol} — ` +
        `route its multi-zone frame through the generator (565 §13.2); the registered governed ` +
        `composition must be GENERATED, not hand-authored.`,
    );
  }
}

if (failures.length > 0) {
  console.error(
    '✗ composition-surfaces gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'),
  );
  process.exit(1);
}
console.log(
  `✓ composition-surfaces gate OK — ${reg.adopters.length} registered governed multi-zone ` +
    `composition(s) all route through the one Composition primitive (${reg.primitive.symbol}). ` +
    `Positive-coverage catalog; the anti-drift teeth are in GENERATION (a new governed composition ` +
    `is a discovery-step + the §13.6 measured UX-audit discipline).`,
);
