#!/usr/bin/env node
/**
 * transient-arbitration gate — tempdoc 574 §22.F (Move 4, the by-construction prevention rung).
 *
 * Single-open across the `transient` layer (§16 S10) is arbitrated BY CONSTRUCTION: every transient
 * overlay composes the ONE `TransientController` primitive (declared in `governance/transients.v1.json`),
 * which bundles the `registerTransient` + `closeOthersInLayer` + `unregisterTransient` triad into the host
 * lifecycle. Because a governed adopter routes its single-open through the controller, the hand-rolled
 * manual triad for that overlay does not exist to drift ("generation, not grep, is the anti-drift" — the
 * same shape as check-composition-surfaces).
 *
 * POSITIVE COVERAGE (not a scan): the gate locks the catalog — every `adopters` entry MUST compose the
 * primitive symbol. A NEW transient is a discovery step (add the row + compose `TransientController`,
 * review-gated). `HoverPreviewHost` is deliberately absent (timer-driven, not click-opened — no
 * single-open contention). Comments are stripped so a doc-comment naming the symbol is not a "use".
 * Lighter scripts/ci tier; wired as a ci.yml step + the pre-merge checks table (docs/reference/contributing/pre-merge-checks.md).
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { stripComments } from '../lib/strip-comments.mjs';

const REGISTER = 'governance/transients.v1.json';

const norm = (p) => p.replace(/\\/g, '/');

/**
 * Pure detection: every `adopters` entry MUST compose the one TransientController
 * symbol. Rooted (`root`) so the 530 kernel can scan a self-test fixture tree.
 * Shared by the CLI and `scripts/governance/gates/transient-arbitration/`.
 */
export function detect({ root = '.' } = {}) {
  const reg = JSON.parse(readFileSync(resolve(root, REGISTER), 'utf8'));
  const SYMBOL = new RegExp(`\\b${reg.primitive.symbol}\\b`);
  const violations = [];
  for (const adopter of reg.adopters) {
    const f = norm(adopter);
    const src = stripComments(readFileSync(resolve(root, f), 'utf8'));
    if (!SYMBOL.test(src)) {
      violations.push({
        file: f,
        rule: 'missing-controller',
        message:
          `${f}: declared a transient-arbitration adopter but does NOT compose ${reg.primitive.symbol} — ` +
          `route its single-open through the controller (574 §22.F Move 4); the registered transient must ` +
          `arbitrate BY CONSTRUCTION, not via a hand-rolled register/closeOthers/unregister triad.`,
      });
    }
  }
  return { violations, count: reg.adopters.length, symbol: reg.primitive.symbol };
}

// ── CLI (back-compat) ─────────────────────────────────────────────────────────
if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const { violations, count, symbol } = detect({});
  if (violations.length > 0) {
    console.error('✗ transient-arbitration gate FAILED:\n' + violations.map((x) => '  - ' + x.message).join('\n'));
    process.exit(1);
  }
  console.log(
    `✓ transient-arbitration gate OK — ${count} registered transient overlay(s) all compose ` +
      `the ONE ${symbol} (single-open by construction; 574 §22.F). Positive-coverage catalog.`,
  );
}
