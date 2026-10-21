#!/usr/bin/env node
/**
 * Guardrail: block bare-form `log.<level>(e.getMessage())` patterns.
 *
 * Rationale:
 * - The bare form discards the exception's stacktrace and fails to record causal context.
 *   Pass the exception as the last logger argument: `log.error("msg", e)` or
 *   `log.error("msg: {}", arg, e)`.
 * - Originally enforced by the OpenRewrite recipe
 *   `org.openrewrite.java.logging.slf4j.CompleteExceptionLogging` via
 *   `./gradlew.bat rewriteDryRun --no-configuration-cache ...` in CI. That step
 *   took ~83s per CI run because OpenRewrite is incompatible with Gradle's
 *   configuration cache (it explicitly opts out via
 *   `notCompatibleWithConfigurationCache` in `AbstractRewriteTask`). This script
 *   checks the bare form only and runs in ~1s. Its scope is narrower than the
 *   recipe's, which also rewrites parameterized and concatenated `e.getMessage()`
 *   arguments and bare `getLocalizedMessage()` calls.
 *
 * Scope (in scope / out of scope):
 *   IN  : log.error(e.getMessage())                    — bare form, exception lost
 *   OUT : log.error("text", e)                         — exception passed correctly
 *   OUT : log.error("text {}", e.getMessage())         — parameterized form
 *   OUT : log.error("text: " + e.getMessage())         — concatenated
 *
 * Usage:
 *   node scripts/ci/check-slf4j-bare-message-logging.mjs --mode gate
 *   node scripts/ci/check-slf4j-bare-message-logging.mjs --mode warn
 *   node scripts/ci/check-slf4j-bare-message-logging.mjs --json
 */
/* eslint-disable no-console */

import fsp from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '..', '..');

function toPosix(p) {
  return String(p).split(path.sep).join('/');
}

function parseArgs(argv) {
  const out = {
    mode: 'gate',
    root: 'modules',
    json: false,
  };

  const args = [...argv];
  const consumeNext = new Set();
  const takeValue = (i) => {
    const token = args[i];
    const eq = token.indexOf('=');
    if (eq !== -1) return token.slice(eq + 1);
    return args[i + 1] ?? null;
  };

  for (let i = 0; i < args.length; i += 1) {
    if (consumeNext.has(i)) continue;
    const token = args[i];
    if (token === '-h' || token === '--help') {
      out.mode = 'help';
      continue;
    }
    if (!token.startsWith('--')) continue;

    const key = token.split('=')[0];
    const inline = token.includes('=');
    const value = takeValue(i);
    if (!inline && key !== '--gate' && key !== '--warn' && key !== '--json') {
      consumeNext.add(i + 1);
    }

    switch (key) {
      case '--mode':
        out.mode = String(value || '').toLowerCase();
        break;
      case '--gate':
        out.mode = 'gate';
        break;
      case '--warn':
        out.mode = 'warn';
        break;
      case '--root':
        out.root = String(value || '');
        break;
      case '--json':
        out.json = true;
        break;
      default:
        throw new Error(`Unknown flag: ${key}`);
    }
  }

  if (out.mode === 'help') return out;
  if (!['gate', 'warn'].includes(out.mode)) {
    throw new Error(`Invalid --mode: ${out.mode} (expected gate|warn)`);
  }
  if (!out.root) throw new Error('Missing --root');
  return out;
}

function printUsageAndExit(code = 1) {
  console.error(
    [
      'Usage: node scripts/ci/check-slf4j-bare-message-logging.mjs [options]',
      '',
      'Options:',
      '  --mode gate|warn         Default: gate',
      '  --root <dir>             Default: modules',
      '  --json                   JSON output (single object)',
      '',
      'Fails in gate mode when any bare-form log.<level>(<expr>.getMessage())',
      'pattern is found in *.java sources. Pass the exception as the last logger',
      'argument instead: log.error("msg", e) or log.error("msg: {}", arg, e).',
    ].join('\n'),
  );
  process.exit(code);
}

const SKIP_DIR_NAMES = new Set([
  'build',
  '.gradle',
  '.idea',
  'node_modules',
  'tmp',
]);

async function listJavaFilesRecursive(dirAbs) {
  const out = [];
  const entries = await fsp.readdir(dirAbs, { withFileTypes: true });
  for (const entry of entries) {
    const full = path.join(dirAbs, entry.name);
    if (entry.isDirectory()) {
      if (SKIP_DIR_NAMES.has(entry.name)) continue;
      // eslint-disable-next-line no-await-in-loop
      out.push(...(await listJavaFilesRecursive(full)));
      continue;
    }
    if (!entry.isFile()) continue;
    if (!entry.name.endsWith('.java')) continue;
    out.push(full);
  }
  return out;
}

function lineColFromIndex(text, index) {
  let line = 1;
  let col = 1;
  for (let i = 0; i < index; i += 1) {
    if (text.charCodeAt(i) === 10) {
      line += 1;
      col = 1;
    } else {
      col += 1;
    }
  }
  return { line, col };
}

// Strip Java comments, string literals, char literals, and text blocks while
// preserving line/column offsets (replace each non-newline character of a
// stripped span with a space). This prevents false positives from Javadoc
// example snippets, commented-out code, and string-literal content.
//
// Not a full Java parser — handles the common cases. Edge cases:
//   - `\\n` inside a string is treated as escape-then-newline (the regex won't
//     run anyway because the regex requires `log.X(...)` shape).
//   - Backtick/triple-quoted strings: `"""` text blocks handled; no other
//     Java string forms exist.
function stripCommentsAndStrings(src) {
  const out = [];
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];
    const nx = i + 1 < n ? src[i + 1] : '';
    // Block comment / Javadoc
    if (c === '/' && nx === '*') {
      const end = src.indexOf('*/', i + 2);
      const stop = end === -1 ? n : end + 2;
      for (let k = i; k < stop; k += 1) out.push(src.charCodeAt(k) === 10 ? '\n' : ' ');
      i = stop;
      continue;
    }
    // Line comment
    if (c === '/' && nx === '/') {
      const nl = src.indexOf('\n', i);
      const stop = nl === -1 ? n : nl;
      for (let k = i; k < stop; k += 1) out.push(' ');
      i = stop;
      continue;
    }
    // Text block """..."""
    if (c === '"' && nx === '"' && i + 2 < n && src[i + 2] === '"') {
      const end = src.indexOf('"""', i + 3);
      const stop = end === -1 ? n : end + 3;
      for (let k = i; k < stop; k += 1) out.push(src.charCodeAt(k) === 10 ? '\n' : ' ');
      i = stop;
      continue;
    }
    // String literal
    if (c === '"') {
      out.push(c);
      i += 1;
      while (i < n) {
        const sc = src[i];
        if (sc === '\\' && i + 1 < n) {
          out.push(' ', ' ');
          i += 2;
          continue;
        }
        if (sc === '"') {
          out.push('"');
          i += 1;
          break;
        }
        if (sc === '\n') {
          // unterminated string at EOL — treat as ended
          out.push('\n');
          i += 1;
          break;
        }
        out.push(' ');
        i += 1;
      }
      continue;
    }
    // Char literal
    if (c === "'") {
      out.push(c);
      i += 1;
      while (i < n) {
        const sc = src[i];
        if (sc === '\\' && i + 1 < n) {
          out.push(' ', ' ');
          i += 2;
          continue;
        }
        if (sc === "'") {
          out.push("'");
          i += 1;
          break;
        }
        if (sc === '\n') {
          out.push('\n');
          i += 1;
          break;
        }
        out.push(' ');
        i += 1;
      }
      continue;
    }
    out.push(c);
    i += 1;
  }
  return out.join('');
}

// Regex notes:
// - Multiline-tolerant: `\s` in JS regex includes `\n`, so
//   `log.error(\n  e.getMessage()\n)` is also caught.
// - Identifier before `.getMessage()`: `[A-Za-z_$][\w$]*` — covers `e`, `ex`,
//   `cause`, etc. Does not match chained calls (`getCause().getMessage()`).
// - Sole argument: the closing `)` immediately after `.getMessage()` (with
//   optional whitespace) ensures only the bare form is matched, not
//   parameterized or concatenated forms.
const BARE_FORM_RE = /\b(?:log|LOG|logger|LOGGER|DEFAULT_LOG|root|schedulerLogger|jetty|javalin|ioJustsearch|lucene|sqlite|captured|wiringLogger|ringLogger|gplLogger)\s*\.\s*(error|warn|info|debug|trace)\s*\(\s*[A-Za-z_$][\w$]*\s*\.\s*getMessage\s*\(\s*\)\s*\)/g;

function findBareFormViolations(text) {
  const stripped = stripCommentsAndStrings(text);
  const matches = [];
  const re = new RegExp(BARE_FORM_RE.source, 'g');
  // eslint-disable-next-line no-constant-condition
  while (true) {
    const m = re.exec(stripped);
    if (!m) break;
    // Reach back into the original text for the snippet (more useful diagnostic).
    const raw = text.substr(m.index, m[0].length);
    matches.push({ raw, level: m[1], index: m.index });
  }
  return matches;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.mode === 'help') printUsageAndExit(0);

  const rootAbs = path.isAbsolute(opts.root) ? opts.root : path.resolve(repoRoot, opts.root);
  const filesAbs = await listJavaFilesRecursive(rootAbs);

  const violations = [];
  for (const fileAbs of filesAbs) {
    // eslint-disable-next-line no-await-in-loop
    const text = await fsp.readFile(fileAbs, 'utf8');
    const found = findBareFormViolations(text);
    for (const m of found) {
      const { line, col } = lineColFromIndex(text, m.index);
      violations.push({
        file: toPosix(path.relative(repoRoot, fileAbs)),
        line,
        col,
        level: m.level,
        snippet: m.raw,
      });
    }
  }

  if (opts.json) {
    console.log(JSON.stringify({
      mode: opts.mode,
      root: toPosix(path.relative(repoRoot, rootAbs)),
      filesScanned: filesAbs.length,
      violations,
      passed: violations.length === 0,
    }, null, 2));
  } else if (violations.length === 0) {
    console.log(`SLF4J bare-message-logging check: 0 violations across ${filesAbs.length} .java files in ${toPosix(path.relative(repoRoot, rootAbs))}/`);
  } else {
    console.error(`SLF4J bare-message-logging violations (${violations.length}):`);
    for (const v of violations) {
      console.error(`  ${v.file}:${v.line}: ${v.snippet}`);
    }
    console.error('');
    console.error('Fix: pass the exception as the last logger argument.');
    console.error('  Wrong:  log.error(e.getMessage());');
    console.error('  Right:  log.error("msg", e);');
    console.error('  Right:  log.error("msg: {}", contextArg, e);');
  }

  if (violations.length > 0 && opts.mode === 'gate') {
    process.exit(1);
  }
}

main().catch((err) => {
  console.error(`check-slf4j-bare-message-logging failed: ${err && err.message ? err.message : err}`);
  process.exit(2);
});
