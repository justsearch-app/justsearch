#!/usr/bin/env node
/**
 * A PYTHONPATH that names scripts/jseval must also name scripts/jsharness.
 *
 * jseval runs jsharness code (readiness, process registers). Callers that built the Python
 * path themselves put only scripts/jseval on it three times, so `import jsharness` failed
 * standalone or ran another checkout's copy. Callers now take the path from
 * scripts/lib/python-path.cjs (shell: scripts/lib/python-path.sh); this check fails any
 * tracked script, workflow or documented command that sets PYTHONPATH to scripts/jseval
 * without scripts/jsharness or the helper. The check reads the value of each assignment -- one
 * shell word, quoted string or expression, not what follows it on the line, so a later
 * `python -c "from jsharness ..."` does not hide a jseval-only value. It sees literal paths
 * on that line, not values a program assembles across lines, which is what the shared helper
 * is for.
 *
 * History and evidence (tempdocs, evidence/history folders, numbered run folders under
 * scripts/jseval) record what was run then and are not rewritten.
 *
 * Usage: node scripts/ci/check-pythonpath.mjs [checkout-root]   (default: the cwd's checkout)
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const HISTORY = [
  /^docs\/tempdocs\//,
  /(^|\/)(evidence|history)\//,
  /^scripts\/jseval\/\d[^/]*\//,
  // A stage record of the local checks that were run for that stage.
  /^docs\/design\/lane-f-engine-jvm\/stages\/E\.md$/,
];
const SELF = new Set(['scripts/ci/check-pythonpath.mjs', 'scripts/ci/check-pythonpath.test.mjs']);
// An assignment in any of the forms used here: shell/cmd/PowerShell `PYTHONPATH=`, YAML and
// JavaScript `PYTHONPATH:`, Python `env["PYTHONPATH"] =`.
const ASSIGNMENT = /PYTHONPATH['"]?\]?\s*[=:]/g;
const JSEVAL_DIR = /scripts[\\/]+jseval(?![\w.-])|['"]scripts['"]\s*[,/]\s*['"]jseval['"]/;
const BOTH_OR_HELPER = /jsharness|python-path\.cjs|pythonPath\(|js_pythonpath/;

const CLOSE = { '(': ')', '[': ']', '{': '}' };

/**
 * The value assigned at `start`: up to the closing quote when the assignment itself is quoted
 * (cmd `set "PYTHONPATH=..."`), else one shell word, quoted string or bracketed expression --
 * through `$(...)`, `${...}` and `a + b` -- ending at whitespace, `,`, a `;` before whitespace,
 * a backtick or an unmatched closing bracket outside quotes and brackets.
 */
export function assignedValue(line, start, enclosingQuote) {
  if (enclosingQuote) {
    const end = line.indexOf(enclosingQuote, start);
    return line.slice(start, end < 0 ? undefined : end);
  }
  let i = start;
  while (i < line.length && /\s/.test(line[i])) i++;
  const begin = i, closers = [];
  let quote = null;
  for (; i < line.length; i++) {
    const c = line[i];
    if (quote) { if (c === quote && line[i - 1] !== '\\') quote = null; continue; }
    if (c === '"' || c === "'") { quote = c; continue; }
    if (CLOSE[c]) { closers.push(CLOSE[c]); continue; }
    if (closers.length) { if (c === closers.at(-1)) closers.pop(); continue; }
    if (/[)\]},`]/.test(c) || (c === ';' && /^;(\s|$)/.test(line.slice(i)))) break; // `a;b` is a Windows list
    if (/\s/.test(c)) {
      const next = line.slice(i).match(/^\s*\+\s*/); // an expression joined with `+` goes on
      if (!next) break;
      i += next[0].length - 1;
    }
  }
  return line.slice(begin, i);
}

export function violations(file, text) {
  const found = [];
  text.split(/\r?\n/).forEach((line, index) => {
    for (const match of line.matchAll(ASSIGNMENT)) {
      const before = line[match.index - 1];
      const enclosingQuote = (before === '"' || before === "'") && !/['"]/.test(match[0]) ? before : null;
      const value = assignedValue(line, match.index + match[0].length, enclosingQuote);
      if (JSEVAL_DIR.test(value) && !BOTH_OR_HELPER.test(value)) {
        found.push(`${file}:${index + 1}: ${line.trim()}`);
        break;
      }
    }
  });
  return found;
}

function main() {
  const root = execFileSync('git', ['-C', path.resolve(process.argv[2] ?? '.'), 'rev-parse', '--show-toplevel'],
    { encoding: 'utf8' }).trim();
  const files = execFileSync('git', ['-C', root, 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], { encoding: 'utf8' })
    .split('\0').filter(file => file && !SELF.has(file) && !HISTORY.some(rule => rule.test(file)));
  const found = [];
  for (const file of files) {
    let buffer;
    try { buffer = fs.readFileSync(path.join(root, file)); } catch { continue; } // deleted in the work tree
    if (buffer.includes(0)) continue; // binary
    found.push(...violations(file, buffer.toString('utf8')));
  }
  if (found.length) {
    console.error(`PYTHONPATH names scripts/jseval without scripts/jsharness (${found.length}):`);
    for (const entry of found) console.error(`  ${entry}`);
    console.error('Take the value from scripts/lib/python-path.cjs (shell: scripts/lib/python-path.sh).');
    process.exit(1);
  }
  console.log(`PYTHONPATH check: ${files.length} files, none names scripts/jseval without scripts/jsharness.`);
}

if (import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main();
