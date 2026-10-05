#!/usr/bin/env node
/**
 * modality-contract gate — tempdoc 574 §22.D (Move 4, the prevention rung for modality).
 *
 * A COMPLETE modal binds the full modality contract: native `<dialog>.showModal()` gives the browser
 * focus-trap + background `inert` + Top Layer; `ModalityController` (`primitives/modality.ts`) adds the
 * scroll-lock + focus-restore the native dialog does NOT. §22.D's de-risk established `.showModal()` as
 * the clean, precise signal of "this is a modal" — all 5 modals (Confirm / Elicit / MacroDryRun /
 * EffectAuditLog / Authorization) call it AND compose `ModalityController`, while the center-slot
 * backdrops (IndexingOverlay / DragOverlay) do NOT call it, so this gate's rule does not reach them
 * (unlike the noisy `slot="center"` signal).
 *
 * **Tempdoc 864 review F2 — that non-reach stopped meaning "needs nothing".** Since 864 the
 * `ModalityController` count is ALSO the app's keyboard-ownership authority (`modalOwnsFocus`), so a
 * `role="dialog" aria-modal="true"` layer that skips it lets `j`/`k` and the modifier-less bindings
 * drive the surface underneath it. `IndexingOverlay` therefore composes the controller now (entering
 * on connect — its host renders it only while the overlay is up) WITHOUT calling `showModal()`: a
 * shape this gate is deliberately blind to, and rightly so, since the rule below is about half-wiring
 * a NATIVE dialog, not about who may join the authority. `DragOverlay` stays out on purpose — it is a
 * drop-target affordance during a pointer drag, not a keyboard-owning layer.
 *
 * This gate makes a HALF-WIRED modal — one that traps stacking but
 * leaks scroll-lock / focus-restore (the §16 S9 class) — unrepresentable: any file calling `.showModal()`
 * MUST also compose a `ModalityController`.
 *
 * Self-contained: coverage is the whole shell-v0 tree, NO enumerated allowlist (the §19.5 ideal — the
 * contract is enforced by construction, not a list someone must maintain). Comments are stripped so prose
 * naming the symbols never trips it.
 *
 * Honest scope (same ceiling as the run-renderers family): a modal that hand-rolls its own scroll-lock +
 * focus-restore WITHOUT `ModalityController` is grep-invisible and slips — register + DISCIPLINE, not
 * absolute prevention. Lighter scripts/ci tier; wired as a ci.yml step + the pre-merge checks table (docs/reference/contributing/pre-merge-checks.md).
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { stripComments as stripCommentsShared } from '../lib/strip-comments.mjs';

const ROOT = 'modules/ui-web/src/shell-v0';

// Was a 2-step chain (block comments, then line comments) with no ://-guard on the line-comment
// step — vulnerable to the same cross-pass reconstitution as the other ~20 stripComments copies
// (tempdoc 698), and additionally mis-truncated content containing a URL. The shared helper fixes
// both in one pass.
const stripComments = (s) => stripCommentsShared(s, { withHtml: false });

/**
 * Pure detection: whole-tree scan — any file that calls `.showModal()` without
 * composing a `ModalityController` is a half-wired modal. Rooted (`root`) so the
 * 530 kernel can scan a self-test fixture tree. Shared by the CLI and
 * `scripts/governance/gates/modality-contract/`.
 */
export function detect({ root = '.' } = {}) {
  const treeRoot = resolve(root, ROOT);
  const violations = [];
  (function walk(dir) {
    let entries;
    try {
      entries = readdirSync(dir);
    } catch {
      return;
    }
    for (const e of entries) {
      const p = join(dir, e).replace(/\\/g, '/');
      const s = statSync(p);
      if (s.isDirectory()) walk(p);
      else if (p.endsWith('.ts') && !p.endsWith('.test.ts')) {
        const code = stripComments(readFileSync(p, 'utf8'));
        if (/\.showModal\(/.test(code) && !/\bModalityController\b/.test(code)) {
          const rel = p.split('modules/ui-web/src/')[1] ?? p;
          violations.push({
            file: `modules/ui-web/src/${rel}`,
            rule: 'half-wired-modal',
            message:
              `${rel}: calls .showModal() but does not compose a ModalityController — a native <dialog> traps ` +
              `focus + stacking but does NOT lock background scroll or restore focus. Bind the FULL modal ` +
              `contract: \`private readonly modality = new ModalityController(this)\` + modality.enter()/` +
              `exit() around showModal()/close() (574 Move 4).`,
          });
        }
      }
    }
  })(treeRoot);
  return { violations };
}

// ── CLI (back-compat) ─────────────────────────────────────────────────────────
if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const { violations } = detect({});
  if (violations.length) {
    console.error(
      `modality-contract gate FAIL (${violations.length}):\n  ${violations.map((v) => v.message).join('\n  ')}`,
    );
    process.exit(1);
  }
  console.log(
    'modality-contract gate OK — every .showModal() composes a ModalityController (the full scroll-lock + ' +
      'focus-restore + native-dialog modal contract; 574 Move 4).',
  );
}
