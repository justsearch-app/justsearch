#!/usr/bin/env node
/**
 * modal-arbitration gate — tempdoc 574 §22.G (Move 4, the by-construction prevention rung for modals).
 *
 * The FULL modal contract (native `<dialog>.showModal()/close()` → focus-trap + inert + Top Layer, PLUS
 * scroll-lock + focus-restore) is bound BY CONSTRUCTION: every modal host composes the ONE `ModalController`
 * primitive (declared in `governance/modals.v1.json`), whose `open()`/`close()` fire the whole contract
 * atomically. Because a governed adopter routes its open/close through the controller, a half-wired modal
 * does not exist to drift ("generation, not grep, is the anti-drift" — the same shape as
 * check-composition-surfaces / check-transient-arbitration). This closes the hole in §22.D's
 * `check-modality-contract`, which could check that a modal COMPOSES `ModalityController` but not that
 * `enter()`/`exit()` were actually CALLED.
 *
 * POSITIVE COVERAGE (not a scan): the gate locks the catalog — every `adopters` entry MUST compose the
 * primitive symbol. A NEW modal is a discovery step (add the row + compose `ModalController`, review-gated).
 * Comments are stripped so a doc-comment naming the symbol is not a "use". Lighter scripts/ci tier; wired
 * as a ci.yml step + the pre-merge checks table (docs/reference/contributing/pre-merge-checks.md). (`check-modality-contract` stays as the backstop.)
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { stripComments } from '../lib/strip-comments.mjs';

const REGISTER = 'governance/modals.v1.json';

const norm = (p) => p.replace(/\\/g, '/');

/**
 * Pure detection: every `adopters` entry MUST compose the one ModalController
 * symbol. Rooted (`root`) so the 530 kernel can scan a self-test fixture tree.
 * Shared by the CLI and `scripts/governance/gates/modal-arbitration/`.
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
          `${f}: declared a modal-contract adopter but does NOT compose ${reg.primitive.symbol} — route its ` +
          `open/close through the controller (574 §22.G Move 4); the registered modal must bind the FULL ` +
          `contract (showModal + scroll-lock + focus-restore) BY CONSTRUCTION, not via manual enter/exit pairs.`,
      });
    }
  }
  return { violations, count: reg.adopters.length, symbol: reg.primitive.symbol };
}

// ── CLI (back-compat) ─────────────────────────────────────────────────────────
if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const { violations, count, symbol } = detect({});
  if (violations.length > 0) {
    console.error('✗ modal-arbitration gate FAILED:\n' + violations.map((x) => '  - ' + x.message).join('\n'));
    process.exit(1);
  }
  console.log(
    `✓ modal-arbitration gate OK — ${count} registered modal host(s) all compose the ONE ` +
      `${symbol} (the full modal contract by construction; 574 §22.G). Positive-coverage catalog.`,
  );
}
