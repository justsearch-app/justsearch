#!/usr/bin/env node
/**
 * search-issuance gate — tempdoc 577 Goal 1 Extension II / partial Move H (the search window's
 * anti-drift teeth, mirroring check-steering-arbitration).
 *
 * Ext II made the search surface issue every query through ONE intent constructor
 * (`buildSearchIntent` in `searchState.ts`) and a single `runSearch` fetch — so the divergent
 * body-shaping paths the 577 §1.8 de-risk found (keystroke POST vs the querySyntax-carrying API
 * client vs pinned-chip restore) cannot silently re-appear. This gate keeps it so:
 *
 *  1. SEAM integrity — the one seam still exists: `searchState.ts` defines `buildSearchIntent`
 *     and issues the `/api/knowledge/search` request. (A rename/delete of the seam fails here, so
 *     the gate cannot be defeated by removing what it guards.)
 *  2. SINGLE issuer — within the surface layer (`shell-v0`), the `/api/knowledge/search`
 *     issuance string appears ONLY in the allowed issuer(s); any other surface file hitting it is a
 *     second issuance site bypassing the seam → fail. Declared non-issuer references (the plugin
 *     read-allowlist in `data.ts`) are allow-listed; comments are stripped so doc mentions do not
 *     match.
 *
 * A NEW issuance site is a discovery step: route it through `buildSearchIntent` (do not add a
 * second body-shaping path). Honest limit: a surface routing through a DIFFERENT named client is
 * import-invisible to this path-substring scan — register + discipline, not absolute (same caveat
 * as the steering / run-renderers gates). The full Move-H coverage (projecting from the
 * search-as-a-mode catalog) lands with Goal 3 / Move A.
 *
 * Lighter scripts/ci tier; wired as a ci.yml step + the pre-merge checks table (docs/reference/contributing/pre-merge-checks.md).
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { stripComments as stripCommentsShared } from '../lib/strip-comments.mjs';

const REGISTER = 'governance/search-issuance.v1.json';
const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));

const norm = (p) => p.replace(/\\/g, '/');

const stripComments = (s) => stripCommentsShared(s, { withHtml: false });

const failures = [];

// 1. SEAM integrity — the one seam exists and issues the request.
{
  const seamFile = norm(reg.seam.file);
  const src = stripComments(readFileSync(seamFile, 'utf8'));
  if (!new RegExp(`\\b${reg.seam.symbol}\\b`).test(src)) {
    failures.push(
      `${seamFile}: the search-issuance seam \`${reg.seam.symbol}\` is missing — the ONE intent ` +
        `constructor (577 Ext II) must exist here; do not remove what the gate guards.`,
    );
  }
  if (!src.includes(reg.issuancePattern)) {
    failures.push(
      `${seamFile}: the seam no longer issues \`${reg.issuancePattern}\` — the single issuance ` +
        `fetch must live with the seam (577 Ext II).`,
    );
  }
}

// 2. SINGLE issuer — within the surface layer, the issuance string only at the allowed issuer(s).
const allowed = new Set([...reg.allowedIssuers, ...reg.allowedReferences].map(norm));
const walk = (dir, acc) => {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, acc);
    else if (/\.(ts|tsx)$/.test(name) && !/\.test\.(ts|tsx)$/.test(name)) acc.push(norm(p));
  }
  return acc;
};
for (const f of walk(reg.scope, [])) {
  if (allowed.has(f)) continue;
  const src = stripComments(readFileSync(f, 'utf8'));
  if (src.includes(reg.issuancePattern)) {
    failures.push(
      `${f}: issues \`${reg.issuancePattern}\` directly — the search surface must construct every ` +
        `query through the ONE \`${reg.seam.symbol}\` seam (${reg.seam.file}); a second body-shaping ` +
        `/ POST site is the divergent-issuance drift 577 Ext II forbids. Route it through the seam, ` +
        `or (if it is a declared non-issuer reference) add it to allowedReferences in ${REGISTER}.`,
    );
  }
}

if (failures.length > 0) {
  console.error('✗ search-issuance gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'));
  process.exit(1);
}
console.log(
  `✓ search-issuance gate OK — the search surface issues \`${reg.issuancePattern}\` through the ONE ` +
    `\`${reg.seam.symbol}\` seam (577 Ext II); no second issuance site in ${reg.scope}.`,
);
