/**
 * Rust #[cfg(test)] detection for the test-intent gate (tempdoc 966 D1).
 *
 * The shell crate keeps most of its tests inline: `#[cfg(test)] mod tests { ... }` at the bottom of
 * a production file, plus one `#[cfg(test)] #[path = "..."] mod x;` that loads a whole file. File
 * level is still the unit, but for a production file the flagged CONTENT is the concatenation of its
 * test-only items, so a production edit in the same file is not a test change while any edit inside
 * a test item is.
 *
 * A test item is any item carrying `#[cfg(...)]` whose predicate mentions `test`, `#[test]`, or a
 * path attribute ending in `::test` (e.g. `#[tokio::test]`). A file whose first attribute is
 * `#![cfg(test)]`, or that a test-only `mod` declaration loads, is test code as a whole.
 *
 * The scanner blanks comments, strings, raw strings and char literals before matching brackets.
 * Anything it cannot close (an unterminated string or comment, unbalanced brackets) is a PARSE
 * FAILURE, which the gate flags rather than passes.
 */

import { posix as path } from 'node:path';

export class RustScanError extends Error {}

const IDENT = /[A-Za-z0-9_]/;

/** Replace comments, string and char literals with spaces, keeping offsets and newlines. */
export function sanitizeRust(src) {
  const out = src.split('');
  const n = src.length;
  const blank = (from, to) => {
    for (let k = from; k < to; k++) if (out[k] !== '\n' && out[k] !== '\r') out[k] = ' ';
  };
  let i = 0;
  while (i < n) {
    const c = src[i];
    const prevIdent = i > 0 && IDENT.test(src[i - 1]);
    if (c === '/' && src[i + 1] === '/') {
      const end = src.indexOf('\n', i);
      const stop = end === -1 ? n : end;
      blank(i, stop);
      i = stop;
      continue;
    }
    if (c === '/' && src[i + 1] === '*') {
      let depth = 1;
      let j = i + 2;
      while (j < n && depth > 0) {
        if (src[j] === '/' && src[j + 1] === '*') { depth++; j += 2; continue; }
        if (src[j] === '*' && src[j + 1] === '/') { depth--; j += 2; continue; }
        j++;
      }
      if (depth > 0) throw new RustScanError(`unterminated block comment at offset ${i}`);
      blank(i, j);
      i = j;
      continue;
    }
    if (!prevIdent) {
      const raw = /^(?:b|c)?r(#*)"/.exec(src.slice(i, i + 260));
      if (raw) {
        const close = '"' + raw[1];
        const start = i + raw[0].length;
        const end = src.indexOf(close, start);
        if (end === -1) throw new RustScanError(`unterminated raw string at offset ${i}`);
        blank(i, end + close.length);
        i = end + close.length;
        continue;
      }
      if (c === '"' || ((c === 'b' || c === 'c') && src[i + 1] === '"')) {
        let j = c === '"' ? i + 1 : i + 2;
        while (j < n && src[j] !== '"') j += src[j] === '\\' ? 2 : 1;
        if (j >= n) throw new RustScanError(`unterminated string at offset ${i}`);
        blank(i, j + 1);
        i = j + 1;
        continue;
      }
      if (c === 'b' && src[i + 1] === "'") {
        const end = charLiteralEnd(src, i + 1);
        if (end === -1) throw new RustScanError(`unterminated byte literal at offset ${i}`);
        blank(i, end + 1);
        i = end + 1;
        continue;
      }
    }
    if (c === "'") {
      const end = charLiteralEnd(src, i);
      if (end !== -1) {
        blank(i, end + 1);
        i = end + 1;
        continue;
      }
      // A lifetime or label: leave it.
    }
    i++;
  }
  return out.join('');
}

/** End offset (index of the closing quote) of a char literal starting at `i`, or -1 for a lifetime. */
function charLiteralEnd(src, i) {
  if (src[i + 1] === '\\') {
    const end = src.indexOf("'", i + 2);
    return end !== -1 && end - i <= 12 ? end : -1;
  }
  const cp = src.codePointAt(i + 1);
  if (cp === undefined) return -1;
  const width = cp > 0xffff ? 2 : 1;
  return src[i + 1 + width] === "'" ? i + 1 + width : -1;
}

/** Offset just past the bracket that closes the one at `open`. */
function matchBracket(s, open) {
  const pairs = { '(': ')', '[': ']', '{': '}' };
  const stack = [pairs[s[open]]];
  for (let j = open + 1; j < s.length; j++) {
    const ch = s[j];
    if (pairs[ch]) stack.push(pairs[ch]);
    else if (ch === ')' || ch === ']' || ch === '}') {
      if (stack.pop() !== ch) throw new RustScanError(`mismatched '${ch}' at offset ${j}`);
      if (stack.length === 0) return j + 1;
    }
  }
  throw new RustScanError(`unclosed '${s[open]}' at offset ${open}`);
}

function isTestAttribute(content) {
  const c = content.replace(/\s+/g, ' ').trim();
  if (/^cfg ?\(/.test(c) && /\btest\b/.test(c)) return true;
  return /^(?:[A-Za-z_]\w* ?:: ?)*test ?(\(.*\))?$/.test(c);
}

/** Offset just past the item that starts at `from` (after its attributes). */
function itemEnd(s, from) {
  let j = from;
  for (;;) {
    while (j < s.length && /\s/.test(s[j])) j++;
    if (s[j] === '#' && s[j + 1] === '[') {
      j = matchBracket(s, j + 1);
      continue;
    }
    break;
  }
  for (; j < s.length; j++) {
    const ch = s[j];
    if (ch === ';') return j + 1;
    if (ch === '{') return matchBracket(s, j);
    if (ch === '(' || ch === '[') j = matchBracket(s, j) - 1;
    else if (ch === ')' || ch === ']' || ch === '}') {
      throw new RustScanError(`item starting at ${from} closes an enclosing bracket at ${j}`);
    }
  }
  throw new RustScanError(`item starting at ${from} never ends`);
}

/**
 * @param {string} src
 * @returns {{wholeFile: boolean, items: Array<{start: number, end: number, text: string}>, text: string}}
 * @throws {RustScanError} when the source cannot be scanned (the caller flags it)
 */
export function extractTestItems(src) {
  const s = sanitizeRust(src);
  // Brackets must balance over the whole file, or nothing below is trustworthy.
  for (let j = 0, depth = 0; j < s.length; j++) {
    if ('([{'.includes(s[j])) depth++;
    else if (')]}'.includes(s[j])) {
      depth--;
      if (depth < 0) throw new RustScanError(`unbalanced bracket at offset ${j}`);
    }
    if (j === s.length - 1 && depth !== 0) throw new RustScanError('unbalanced brackets at end of file');
  }
  const inner = /#!\[\s*cfg\s*\(([^\]]*)\]/.exec(s);
  if (inner && /\btest\b/.test(inner[1])) {
    return { wholeFile: true, items: [{ start: 0, end: src.length, text: src }], text: src };
  }
  const items = [];
  let covered = -1;
  const re = /#\[/g;
  let m;
  while ((m = re.exec(s)) !== null) {
    const start = m.index;
    if (start < covered) continue;
    const close = matchBracket(s, start + 1);
    const content = s.slice(start + 2, close - 1);
    if (!isTestAttribute(content)) continue;
    const end = itemEnd(s, close);
    items.push({ start, end, text: src.slice(start, end) });
    covered = end;
    re.lastIndex = end;
  }
  return { wholeFile: false, items, text: items.map((it) => it.text).join('\n') };
}

/**
 * Files a test-only `mod name;` declaration in `rel` loads (both candidate locations when no
 * #[path] is given; the caller keeps the ones that exist).
 */
export function testModuleFiles(rel, src) {
  let extracted;
  try {
    extracted = extractTestItems(src);
  } catch {
    return [];
  }
  if (extracted.wholeFile) return [];
  const out = [];
  const dir = path.dirname(rel);
  const base = path.basename(rel);
  const modRs = ['lib.rs', 'main.rs', 'mod.rs'].includes(base);
  for (const item of extracted.items) {
    const sanitized = sanitizeRust(item.text);
    const decl = /\bmod\s+([A-Za-z_]\w*)\s*;\s*$/.exec(sanitized);
    if (!decl) continue;
    const name = decl[1];
    const pathAttr = /#\[\s*path\s*=\s*"([^"]+)"\s*\]/.exec(item.text);
    if (pathAttr) {
      out.push(path.normalize(path.join(dir, pathAttr[1])));
      continue;
    }
    const childDir = modRs ? dir : path.join(dir, base.replace(/\.rs$/, ''));
    out.push(path.join(childDir, `${name}.rs`), path.join(childDir, name, 'mod.rs'));
  }
  return out;
}
