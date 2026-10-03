import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { extname, join, relative, resolve } from 'node:path';

const WALK_EXCLUDES = new Set(['build', 'node_modules', 'dist', '.git', '.gradle', 'target', 'tmp']);
const SOURCE_EXTENSIONS = new Set(['.java', '.rs']);

// Write-creating idioms. Two forms need their MODE inspected rather than their name, because both
// are read-capable: `new RandomAccessFile(f, "r")` and `FileChannel.open(p)` (READ is the default)
// are the SQLite header preflight and the diagnostics log tail. Matching them by name alone flagged
// those read-only sites, so each is matched together with a write-mode argument instead of being
// dropped entirely — the previous compromise, which cost the gate every argfile and marker write
// that went through them.
const JAVA_MUTATION = new RegExp(
  [
    String.raw`\b(?:Files\.(?:write|writeString|newOutputStream|newBufferedWriter|createFile|createDirectories|createDirectory|move|copy|delete|deleteIfExists)|AtomicFileWrites\.(?:replace|replaceUtf8)|DriverManager\.getConnection|RrdDb|RrdDef)\b`,
    String.raw`\bnew\s+(?:FileOutputStream|FileWriter|PrintWriter|ObjectOutputStream)\b`,
    String.raw`\bnew\s+RandomAccessFile\s*\([^;]*"r[wsd]+"`,
    String.raw`\bFileChannel\.open\s*\([^;]*StandardOpenOption\.(?:WRITE|CREATE|CREATE_NEW|APPEND|TRUNCATE_EXISTING)`,
    String.raw`\bFiles\.newByteChannel\s*\([^;]*StandardOpenOption\.(?:WRITE|CREATE|CREATE_NEW|APPEND)`,
  ].join('|'),
  's',
);
const RUST_MUTATION =
  /\b(?:std::fs|fs)::(?:write|rename|copy|remove_file|remove_dir_all|create_dir_all)\b|\bOpenOptions::new\b/;

/**
 * Remove comments, preserving literals by default for mode-bearing write calls.
 * Pass stripLiterals for declaration checks that must ignore example code in literals.
 *
 * This is the fix for the defect that motivated the rewrite. Discovery used to require a "durable
 * anchor" word (`dataDir`, `telemetry`, `StoreCatalog`, …) to appear anywhere in the file TEXT,
 * comments included. `ExtractionSandboxFactory` was therefore discovered because a javadoc sentence
 * happened to contain one, while `ExtractionSandboxCommand` — which writes a real argfile — was
 * invisible because its prose did not. Discovery keyed on English.
 *
 * Comments go; literals stay, because the two mode-bearing idioms above are only distinguishable
 * from their read-only twins by a literal argument (`"rw"` vs `"r"`). The residual cost is that a
 * write call named inside a string (an error message, a docs URL) still counts — a false positive,
 * which this gate prefers to a gap.
 *
 * Deliberately not a parser: line comments, block comments, text blocks, string and char literals
 * with backslash escapes. That is the whole grammar these regexes need.
 *
 * @param {string} source
 * @param {{stripLiterals?: boolean}} options
 * @returns {string}
 */
export function stripJavaComments(source, { stripLiterals = false } = {}) {
  let out = '';
  let i = 0;
  const n = source.length;
  while (i < n) {
    const c = source[i];
    if (c === '/' && source[i + 1] === '/') {
      while (i < n && source[i] !== '\n') i++;
    } else if (c === '/' && source[i + 1] === '*') {
      i += 2;
      while (i < n && !(source[i] === '*' && source[i + 1] === '/')) i++;
      i += 2;
    } else if (c === '"' && source[i + 1] === '"' && source[i + 2] === '"') {
      const start = i;
      i += 3;
      while (i < n && !(source[i] === '"' && source[i + 1] === '"' && source[i + 2] === '"')) {
        if (source[i] === '\\') i += 2;
        else i++;
      }
      i = Math.min(n, i + 3);
      out += stripLiterals ? ' ' : source.slice(start, i);
    } else if (c === '"' || c === "'") {
      const quote = c;
      const start = i;
      i++;
      while (i < n && source[i] !== quote) {
        if (source[i] === '\\') i += 2;
        else i++;
      }
      i++;
      out += stripLiterals ? ' ' : source.slice(start, Math.min(n, i));
    } else {
      out += c;
      i++;
    }
  }
  return out;
}

/** Rust equivalent. Nested block comments are legal in Rust, so the depth counter is not optional. */
export function stripRustComments(source) {
  let out = '';
  let i = 0;
  const n = source.length;
  while (i < n) {
    const c = source[i];
    if (c === '/' && source[i + 1] === '/') {
      while (i < n && source[i] !== '\n') i++;
    } else if (c === '/' && source[i + 1] === '*') {
      i += 2;
      let depth = 1;
      while (i < n && depth > 0) {
        if (source[i] === '/' && source[i + 1] === '*') { depth++; i += 2; }
        else if (source[i] === '*' && source[i + 1] === '/') { depth--; i += 2; }
        else i++;
      }
    } else if (c === '"') {
      const start = i;
      i++;
      while (i < n && source[i] !== '"') {
        if (source[i] === '\\') i += 2;
        else i++;
      }
      i++;
      out += source.slice(start, Math.min(n, i));
    } else {
      out += c;
      i++;
    }
  }
  return out;
}

/**
 * Classify a production source as a persistence write site.
 *
 * Discovery is by WRITE CALL, not by vocabulary: any mutation idiom above appearing in code makes
 * the file a write site the durable-state register must account for — either by naming it in some
 * row's `implementationSources`, or by classifying it in `nonDurableWriteSites` with a reason.
 *
 * Fail-closed on purpose. This over-discovers: benchmarks, SSOT generators and the agent's file
 * tools all write to disk and none of them hold durable state. Each costs one honest line in the
 * register saying so, which is cheap. The alternative — a discovery rule that quietly skips files —
 * buys silence at the price of not knowing what writes to disk, and the retired durable-anchor
 * condition was exactly that quiet skip, decided by reading English.
 */
export function isPersistenceWriteSource(sourcePath, source) {
  const normalized = norm(sourcePath);
  const extension = extname(normalized);
  if (!SOURCE_EXTENSIONS.has(extension)) return false;
  if (
    extension === '.java'
      ? !normalized.includes('/src/main/')
      : !normalized.includes('/src-tauri/src/')
  ) {
    return false;
  }
  return extension === '.rs'
    ? RUST_MUTATION.test(stripRustComments(source))
    : JAVA_MUTATION.test(stripJavaComments(source));
}

export function scanPersistenceWriteSites(root) {
  const modules = resolve(root, 'modules');
  if (!existsSync(modules)) return [];
  const out = [];
  for (const absolute of walk(modules)) {
    const rel = norm(relative(root, absolute));
    const source = readFileSync(absolute, 'utf8');
    if (isPersistenceWriteSource(rel, source)) out.push(rel);
  }
  return out.sort();
}

function walk(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (WALK_EXCLUDES.has(entry.name)) continue;
    const absolute = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(absolute));
    else if (SOURCE_EXTENSIONS.has(extname(entry.name))) out.push(absolute);
  }
  return out;
}

function norm(value) {
  return String(value ?? '').replace(/\\/g, '/');
}
