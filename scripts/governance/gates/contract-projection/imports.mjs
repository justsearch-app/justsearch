/**
 * Import-specifier detection for the contract-projection gate (tempdoc 884 review S5).
 *
 * WHY THIS EXISTS. Checks 5 and 6 of the enforcer decide "this file consumes the generated
 * module" — check 5 to verify a declared consumer really imports it, check 6 to catch an
 * UNdeclared one. Both used a raw `text.includes('schema-types/<base>')` over whole file text,
 * so any mention counted: `AgentSessionController.ts:217` names the generated file's path in a
 * doc comment and was flagged as an undeclared consumer, which was then "fixed" by registering
 * a file that imports nothing — a false entry in the register, produced by the matcher.
 *
 * The fix is to anchor on an actual import, and the direction of error matters in both
 * directions: a false POSITIVE puts fiction in the register; a false NEGATIVE silently weakens
 * the gate's whole job (an undeclared consumer sails through). So this module recognises every
 * import form the codebase actually uses rather than one regex over the common case:
 *
 *   import type { X } from '…/schema-types/foo.js'      (installComponents.ts:35)
 *   import { x } from './generated/schema-types/foo'    (schemas.ts:18 — no extension)
 *   import {\n  A,\n  B,\n} from '…/schema-types/foo'   (api/types/registry.ts:44-48)
 *   export type { X } from './schema-types/foo.js'      (api/generated/index.ts:14 — re-export)
 *   import '…/schema-types/foo.js'                      (side-effect)
 *   await import('…/schema-types/foo.js')               (dynamic)
 *
 * There was no shared helper to reuse: the only import regex in the kernel is
 * `RE_IMPORT_FROM` in gates/contribution-surface/enforcer.mjs:219, which is module-private,
 * handles neither side-effect nor dynamic imports, and does not strip comments — the exact
 * false-positive class this fixes. It is left alone (that gate is not this lane's to change).
 */

/**
 * Strip line and block comments while respecting string/template literals.
 *
 * Comment-stripping is what makes a doc comment naming a module path stop counting. It is done
 * with a scanner rather than a regex because `text.replace(/\/\/.*$/gm, '')` would eat the tail
 * of any line holding a `'https://…'` literal — and eating a line can delete a real import,
 * which is the false-negative direction. Two deliberate details:
 *   - newlines inside block comments are preserved, so line-anchored matching still works;
 *   - an unterminated `'`/`"` is closed at end-of-line (a JS string cannot span lines), which
 *     bounds the damage from a regex literal like /['"]/ to its own line instead of swallowing
 *     the rest of the file.
 */
export function stripComments(src) {
  let out = '';
  let quote = null;
  for (let i = 0; i < src.length;) {
    const c = src[i];
    const next = src[i + 1];
    if (quote) {
      if (c === '\\') { out += c + (next ?? ''); i += 2; continue; }
      if (c === quote) quote = null;
      else if (c === '\n' && quote !== '`') quote = null;
      out += c; i += 1; continue;
    }
    if (c === '/' && next === '/') {
      while (i < src.length && src[i] !== '\n') i += 1;
      continue;
    }
    if (c === '/' && next === '*') {
      i += 2;
      while (i < src.length && !(src[i] === '*' && src[i + 1] === '/')) {
        if (src[i] === '\n') out += '\n';
        i += 1;
      }
      i += 2; continue;
    }
    if (c === '"' || c === "'" || c === '`') { quote = c; out += c; i += 1; continue; }
    out += c; i += 1;
  }
  return out;
}

// A clause may span lines (a multi-line `{ … }` block) but never crosses a quote or a `;`,
// so `[^'"`;]` keeps one statement's `import` from binding to a later statement's `from`.
const RE_STATIC_FROM = /(?:^|[\n;}])[ \t]*(?:import|export)\b[^'"`;]*?\bfrom[ \t\r\n]*(['"])([^'"]+)\1/g;
const RE_SIDE_EFFECT = /(?:^|[\n;}])[ \t]*import[ \t\r\n]*(['"])([^'"]+)\1/g;
const RE_DYNAMIC = /\bimport[ \t\r\n]*\([ \t\r\n]*(['"])([^'"]+)\1/g;

/**
 * Every module specifier this source actually imports (static, re-export, side-effect, dynamic).
 * A specifier named only in a comment or a plain string is not one.
 */
export function importSpecifiers(text) {
  const src = stripComments(String(text ?? ''));
  const specs = new Set();
  for (const re of [RE_STATIC_FROM, RE_SIDE_EFFECT, RE_DYNAMIC]) {
    re.lastIndex = 0;
    let m;
    while ((m = re.exec(src)) !== null) specs.add(m[2]);
  }
  return [...specs];
}

function escapeRe(s) {
  return String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Does one specifier resolve to the generated module `schema-types/<moduleBase>`?
 *
 * End-anchored, with the extension optional: TS/ESM source imports the `.ts` module through a
 * `.js` specifier, and some call sites omit the extension entirely. Anchoring also stops a
 * prefix collision from laundering one record as another (`schema-types/resource` must not
 * match `schema-types/resource-usage`).
 */
export function matchesGeneratedModule(specifier, moduleBase) {
  if (!moduleBase) return false;
  const re = new RegExp(`(?:^|/)schema-types/${escapeRe(moduleBase)}(?:\\.(?:m|c)?[jt]sx?)?$`);
  return re.test(String(specifier ?? ''));
}

/** Convenience: does this source import the generated module `schema-types/<moduleBase>`? */
export function importsGeneratedModule(text, moduleBase) {
  return importSpecifiers(text).some((s) => matchesGeneratedModule(s, moduleBase));
}

/** A named barrel import counts only when that barrel re-exports the name from this record. */
export function importsGeneratedRecord(text, moduleBase, barrelText) {
  if (importsGeneratedModule(text, moduleBase)) return true;
  const exported = new Set();
  const reExport = /\bexport\s+(?:type\s+)?\{([^}]+)\}\s+from\s+(['"])([^'"]+)\2/g;
  let match;
  while ((match = reExport.exec(barrelText)) !== null) {
    if (!new RegExp(`^\\./${escapeRe(moduleBase)}(?:\\.(?:m|c)?[jt]sx?)?$`).test(match[3])) continue;
    for (const name of match[1].split(',')) {
      const exportedName = name.trim().replace(/^type\s+/, '').split(/\s+as\s+/).at(-1);
      if (exportedName) exported.add(exportedName);
    }
  }
  if (exported.size === 0) return false;
  const source = stripComments(String(text ?? ''));
  const namedImport = /(?:^|[\n;}])[ \t]*import\s+(?:type\s+)?\{([^}]+)\}\s+from\s+(['"])([^'"]+)\2/g;
  while ((match = namedImport.exec(source)) !== null) {
    if (!matchesGeneratedModule(match[3], 'index')) continue;
    for (const name of match[1].split(',')) {
      const importedName = name.trim().replace(/^type\s+/, '').split(/\s+as\s+/)[0];
      if (exported.has(importedName)) return true;
    }
  }
  return false;
}
