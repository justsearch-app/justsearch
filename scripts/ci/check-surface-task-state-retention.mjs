#!/usr/bin/env node
/**
 * surface-task-state-retention gate — tempdoc 609 Phase 6 (the M1 teeth).
 *
 * 609 found a surface destroying its OWN recoverable task state as a navigation side effect
 * (`SearchSurface.disconnectedCallback` called `setQuery('')`, wiping the shared search snapshot;
 * `UnifiedChatView.connectedCallback` called `resetUnifiedChatState()`, wiping the draft). The fix
 * moved clearing to the explicit user-intent handlers. This gate makes the defect class
 * UNREPRESENTABLE: a declared recoverable-store destroyer call inside a view's lifecycle callback
 * (`connectedCallback` / `disconnectedCallback`) fails the build. Clearing recoverable task state must
 * be intent-driven (a click / New-chat handler), never a connect/disconnect side effect.
 *
 * Method-scoped, not file-scoped: the same destroyer is LEGAL in an explicit handler (e.g. the search
 * box's clear button calls `setQuery('')`; New chat calls `resetUnifiedChatState()`), so the gate only
 * inspects the bodies of the declared `lifecycleMethods`. The register
 * (`governance/surface-task-state.v1.json`) also carries the consumed durability declaration.
 *
 * SYMMETRIC second check (settle-coverage): instance-retention (chrome/Shell.ts) retains each surface's
 * element instance across navigation, so ALL component @state survives — correct for recoverable task
 * state but WRONG for TRANSIENT @state (in-flight flags, errors, partial buffers, transient feedback) the
 * working rule says must RESET on hide. This check flags any reactive @state field whose name matches a
 * `transientStatePatterns` entry and is NOT reset in the surface's `settleTransients()` body (the JfElement
 * settle seam), unless `<Surface>.<field>` is in `settleAllowlist`. Together the two checks are the
 * symmetric pair: don't-destroy-recoverable AND do-settle-transient, so a stale spinner can't survive a
 * tab switch any more than a draft can be wiped by one.
 *
 * Lighter scripts/ci tier; wired as a ci.yml step + the pre-merge checks table
 * (docs/reference/contributing/pre-merge-checks.md).
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { basename, join } from 'node:path';
import { stripComments as stripCommentsShared } from '../lib/strip-comments.mjs';

const REGISTER = 'governance/surface-task-state.v1.json';
const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));

const norm = (p) => p.replace(/\\/g, '/');
const stripComments = (s) => stripCommentsShared(s, { withHtml: false });

/** Extract the body (between matching braces) of a method named `name`, or null if absent. */
function methodBody(src, name) {
  const sig = new RegExp(`\\b${name}\\s*\\([^)]*\\)\\s*(?::[^={]+)?\\{`);
  const m = sig.exec(src);
  if (!m) return null;
  let depth = 0;
  const start = m.index + m[0].length; // just after the opening brace
  for (let i = start; i < src.length; i++) {
    const c = src[i];
    if (c === '{') depth++;
    else if (c === '}') {
      if (depth === 0) return src.slice(start, i);
      depth--;
    }
  }
  return src.slice(start); // unbalanced — return the rest (will still be scanned)
}

const walk = (dir, acc) => {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, acc);
    else if (/\.(ts|tsx)$/.test(name) && !/\.test\.(ts|tsx)$/.test(name)) acc.push(norm(p));
  }
  return acc;
};

// Tempdoc 609 settle-coverage — a transient @state field is "settled" iff its name matches a transient
// pattern AND it is reset in the surface's settleTransients() body (or allow-listed).
const settle = reg.transientStatePatterns ?? { exactNames: [], suffixes: [] };
const isTransientName = (name) =>
  (settle.exactNames ?? []).includes(name) ||
  (settle.suffixes ?? []).some((suf) => name.endsWith(suf));
const allowSet = new Set((reg.settleAllowlist ?? []).map((a) => a.field));

// Settle-coverage applies ONLY to STAGE-RETAINED surfaces — those whose element tag is a catalog
// `mountTag`. Shape-views (viewFactoryRegistry) + other non-stage-mounted sub-views are destroyed on
// navigation, so they carry no instance-retention settle obligation.
const retainedTags = new Set(
  reg.retainedSurfaceManifest
    ? [...readFileSync(reg.retainedSurfaceManifest, 'utf8').matchAll(/mountTag:\s*'([^']+)'/g)].map(
        (m) => m[1],
      )
    : [],
);
const definedTag = (src) => src.match(/customElements\.define\(\s*'([^']+)'/)?.[1] ?? null;

const failures = [];
for (const f of walk(reg.scope, [])) {
  const src = stripComments(readFileSync(f, 'utf8'));

  // (1) destroyer check — clearing recoverable state in a lifecycle callback is forbidden (M1 teeth).
  for (const method of reg.lifecycleMethods) {
    const body = methodBody(src, method);
    if (body === null) continue;
    for (const d of reg.forbiddenDestroyers) {
      if (body.includes(d.pattern)) {
        failures.push(
          `${f}: \`${d.pattern}\` inside \`${method}\` destroys recoverable task state (${d.store}) as a ` +
            `navigation side effect — the 609 M1 defect. ${d.why} Move it to the explicit user-intent ` +
            `handler (the clear button / New chat), never a lifecycle callback.`,
        );
      }
    }
  }

  // (2) settle-coverage check — under instance-retention, transient @state must be reset on hide.
  //     Scoped to stage-retained surfaces (catalog mountTag); shape-views / sub-views are exempt.
  const tag = definedTag(src);
  if (tag === null || !retainedTags.has(tag)) continue;
  const surface = basename(f).replace(/\.tsx?$/, '');
  const settleBody = methodBody(src, 'settleTransients') ?? '';
  // §R (S1) — fields declared in `static transientState = { … }` are reset by the JfElement default
  // settleTransients (the declarative path), so a declared key satisfies the obligation too.
  const declaredTransient = new Set();
  const tsMatch = src.match(/static\s+(?:override\s+)?(?:readonly\s+)?transientState\s*=\s*\{([\s\S]*?)\}/);
  if (tsMatch) for (const km of tsMatch[1].matchAll(/(\w+)\s*:/g)) declaredTransient.add(km[1]);
  // Reactive @state fields are declared `name: { ... state: true ... }` in `static properties`.
  for (const m of src.matchAll(/(\w+)\s*:\s*\{[^}]*\bstate\s*:\s*true\b[^}]*\}/g)) {
    const field = m[1];
    if (!isTransientName(field)) continue; // only transient-named @state is required to settle
    if (allowSet.has(`${surface}.${field}`)) continue; // declared exception (recoverable / settled elsewhere)
    if (declaredTransient.has(field)) continue; // §R S1 — declarative reset via static transientState
    if (!new RegExp(`this\\.${field}\\b\\s*=`).test(settleBody)) {
      failures.push(
        `${f}: transient @state \`${field}\` is not reset in \`settleTransients()\` — under instance-` +
          `retention it would survive navigation (a stale spinner / error / feedback), violating the ` +
          `working-rule "reset by default". Reset \`this.${field}\` in settleTransients(), declare it in ` +
          `\`static transientState = { ${field}: <clearedValue> }\` (the §R S1 declarative path), or add ` +
          `"${surface}.${field}" to settleAllowlist in ${REGISTER} with a reason if it is recoverable / ` +
          `settled elsewhere.`,
      );
    }
  }
}

if (failures.length > 0) {
  console.error(
    '✗ surface-task-state-retention gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'),
  );
  process.exit(1);
}
console.log(
  `✓ surface-task-state-retention gate OK — no recoverable-store destroyer in any view lifecycle ` +
    `callback, AND every transient @state field is reset in settleTransients() (or allow-listed), under ` +
    `${reg.scope}: clearing stays intent-driven (M1) and transients settle on hide (no stale UI survives).`,
);
