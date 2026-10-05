/**
 * ADR premise probes — tempdoc 884 §Design decision 1/2.
 *
 * An ADR's load-bearing premise is a claim about the code ("the Head never touches
 * Lucene", "there are 6 MCP tools", "this flag does not exist"). A probe is the cheapest
 * mechanical restatement of that claim that fails when the code drifts away from it.
 * The register is `governance/adr-probes.v1.json`; this module evaluates one probe.
 *
 * Kinds, in the contract's preference order (884 §2):
 *   1. `test`       — a named test/ArchUnit rule already pins the premise. Verifies the
 *                     file exists and still declares the named member.
 *      `gate`       — an existing kernel gate id (`governance/registry.v1.json`) or a
 *                     `scripts/ci/<name>.mjs` check owns the premise.
 *   2. `grep-absent`  / `grep-present` — the symbol/flag/file must (not) exist.
 *   3. `json-path`  — a value in a JSON register is the premise.
 *      `file-set`   — every file in a directory is registered or a reasoned exception.
 *   4. `any-of`     — the premise holds for any of several reasons (`alternatives`, each an
 *                     entry of any kind above). For a premise that survives a planned
 *                     migration; never as a way to give a failing probe a second chance.
 *
 * Counts (`expect`) are permitted only where the premise IS the count (ADR-0015); they
 * are never a general growth ratchet.
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve, join, extname } from 'node:path';

export const PROBE_KINDS = new Set([
  'test', 'gate', 'grep-absent', 'grep-present', 'json-path', 'file-set', 'any-of',
]);

/**
 * How deep `any-of` alternatives may nest before the engine refuses. A premise that needs
 * five levels of disjunction is not a premise; the bound also makes a self-referential
 * register entry terminate with a legible failure instead of blowing the stack.
 */
const MAX_ANY_OF_DEPTH = 4;

const SKIP_DIRS = new Set(['.git', 'node_modules', 'build', 'tmp', '.gradle', 'dist']);

function collectFiles(root, rel, include, out) {
  const abs = resolve(root, rel);
  let st;
  try { st = statSync(abs); } catch { return out; }
  if (st.isFile()) { out.push(abs); return out; }
  if (!st.isDirectory()) return out;
  for (const e of readdirSync(abs, { withFileTypes: true })) {
    if (SKIP_DIRS.has(e.name)) continue;
    const childRel = `${rel}/${e.name}`;
    if (e.isDirectory()) collectFiles(root, childRel, include, out);
    else if (e.isFile() && (!include || include.includes(extname(e.name)))) {
      out.push(join(abs, e.name));
    }
  }
  return out;
}

function countMatches(root, probe) {
  // A `pattern` is author-supplied text in a JSON register, so a typo is a SyntaxError from
  // `new RegExp`. Compiling it once up front and reporting it as a probe failure keeps that typo
  // from throwing out of the enforcer and taking down every other gate in the same run.mjs
  // invocation — the same fail-loudly-here rather than crash-everything treatment already given
  // to unparseable ADR frontmatter and an unparseable register (tempdoc 884 §C.2/§D.7).
  let re;
  try {
    re = new RegExp(probe.pattern, 'g');
  } catch (e) {
    return { patternError: e.message, total: 0, hits: [], scanned: 0 };
  }
  const files = [];
  for (const p of probe.paths ?? []) collectFiles(root, p, probe.include ?? null, files);
  let total = 0;
  const hits = [];
  for (const f of files) {
    let content;
    try { content = readFileSync(f, 'utf8'); } catch { continue; }
    re.lastIndex = 0;
    const m = content.match(re);
    if (m && m.length > 0) {
      total += m.length;
      hits.push(`${f.replace(root, '').replaceAll('\\', '/').replace(/^\//, '')} (${m.length})`);
    }
  }
  return { total, hits, scanned: files.length };
}

/** Resolve a JSON-Pointer-ish `/a/b/0` path against a parsed document. */
function resolvePointer(doc, pointer) {
  if (!pointer || pointer === '/' || pointer === '') return doc;
  let cur = doc;
  for (const rawSeg of pointer.replace(/^\//, '').split('/')) {
    const seg = rawSeg.replaceAll('~1', '/').replaceAll('~0', '~');
    if (cur === null || cur === undefined) return undefined;
    cur = Array.isArray(cur) ? cur[Number(seg)] : cur[seg];
  }
  return cur;
}

function evaluateTest(root, probe) {
  const file = probe.file;
  if (!file || !existsSync(resolve(root, file))) {
    return { ok: false, detail: `named test file '${file}' does not exist` };
  }
  const member = probe.symbol ?? (probe.test?.includes('#') ? probe.test.split('#')[1] : null);
  if (!member) return { ok: true, detail: `test file '${file}' exists` };
  const content = readFileSync(resolve(root, file), 'utf8');
  if (!content.includes(member)) {
    return { ok: false, detail: `'${file}' no longer declares '${member}'` };
  }
  return { ok: true, detail: `'${file}' still declares '${member}'` };
}

/**
 * Where a `scripts/ci` check has to be *invoked* from for its existence to mean anything:
 * the public pre-merge checks table (which is where a contributor is told to run it) or a workflow.
 * A check nobody invokes is a layer that is dead regardless of its quality (tempdoc 745).
 */
const SCRIPT_INVOCATION_SITES = ['docs/reference/contributing/pre-merge-checks.md', '.github/workflows'];

function evaluateGate(root, probe) {
  if (probe.script) {
    if (!existsSync(resolve(root, probe.script))) {
      return { ok: false, detail: `check '${probe.script}' no longer exists` };
    }
    const name = probe.script.split('/').pop().replace(/\.mjs$/, '');
    const sites = [];
    for (const site of probe.invokedFrom ?? SCRIPT_INVOCATION_SITES) {
      const files = collectFiles(root, site, null, []);
      for (const f of files) {
        let content;
        try { content = readFileSync(f, 'utf8'); } catch { continue; }
        if (content.includes(name)) {
          sites.push(f.replace(root, '').replaceAll('\\', '/').replace(/^\//, ''));
          break;
        }
      }
    }
    if (sites.length === 0) {
      return {
        ok: false,
        detail: `check '${probe.script}' exists but nothing invokes it — not in the root `
          + `pre-merge table and not in any .github/workflows file, so it cannot notice drift`,
      };
    }
    return { ok: true, detail: `check '${probe.script}' exists and is invoked from ${sites.join(', ')}` };
  }
  const registryPath = resolve(root, probe.registry ?? 'governance/registry.v1.json');
  if (!existsSync(registryPath)) {
    return { ok: false, detail: `gate registry '${probe.registry ?? 'governance/registry.v1.json'}' not found` };
  }
  const registry = JSON.parse(readFileSync(registryPath, 'utf8'));
  const ids = (registry.gates ?? []).map((g) => g.id);
  return ids.includes(probe.gate)
    ? { ok: true, detail: `kernel gate '${probe.gate}' is registered` }
    : { ok: false, detail: `kernel gate '${probe.gate}' is no longer registered` };
}

function evaluateGrep(root, probe, mustBePresent) {
  const { total, hits, scanned, patternError } = countMatches(root, probe);
  if (patternError) {
    return { ok: false, detail: `probe pattern /${probe.pattern}/ is not a valid regular expression: ${patternError}` };
  }
  if (scanned === 0) {
    return { ok: false, detail: `probe paths matched no files: ${(probe.paths ?? []).join(', ')}` };
  }
  if (typeof probe.expect === 'number') {
    return total === probe.expect
      ? { ok: true, detail: `${total} match(es) for /${probe.pattern}/, as declared` }
      : { ok: false, detail: `expected ${probe.expect} match(es) for /${probe.pattern}/, found ${total}${hits.length ? ` in ${hits.join('; ')}` : ''}` };
  }
  if (mustBePresent) {
    return total > 0
      ? { ok: true, detail: `${total} match(es) for /${probe.pattern}/` }
      : { ok: false, detail: `no match for /${probe.pattern}/ under ${(probe.paths ?? []).join(', ')}` };
  }
  return total === 0
    ? { ok: true, detail: `/${probe.pattern}/ is absent from ${(probe.paths ?? []).join(', ')}` }
    : { ok: false, detail: `/${probe.pattern}/ now appears ${total} time(s): ${hits.join('; ')}` };
}

function evaluateJsonPath(root, probe) {
  const abs = resolve(root, probe.file);
  if (!existsSync(abs)) return { ok: false, detail: `'${probe.file}' does not exist` };
  let doc;
  try { doc = JSON.parse(readFileSync(abs, 'utf8')); } catch (e) {
    return { ok: false, detail: `'${probe.file}' is not parseable JSON: ${e.message}` };
  }
  const value = resolvePointer(doc, probe.pointer);
  const expect = probe.expect ?? {};
  if (Object.hasOwn(expect, 'count')) {
    const actual = Array.isArray(value) ? value.length : undefined;
    return actual === expect.count
      ? { ok: true, detail: `${probe.file}${probe.pointer} has ${actual} entr(ies), as declared` }
      : { ok: false, detail: `${probe.file}${probe.pointer} has ${actual ?? 'no array'} entr(ies), declared ${expect.count}` };
  }
  if (Object.hasOwn(expect, 'equals')) {
    return value === expect.equals
      ? { ok: true, detail: `${probe.file}${probe.pointer} === ${JSON.stringify(expect.equals)}` }
      : { ok: false, detail: `${probe.file}${probe.pointer} is ${JSON.stringify(value)}, declared ${JSON.stringify(expect.equals)}` };
  }
  if (Object.hasOwn(expect, 'contains')) {
    const hay = JSON.stringify(value ?? null);
    return hay.includes(expect.contains)
      ? { ok: true, detail: `${probe.file}${probe.pointer} contains ${JSON.stringify(expect.contains)}` }
      : { ok: false, detail: `${probe.file}${probe.pointer} no longer contains ${JSON.stringify(expect.contains)}` };
  }
  return { ok: false, detail: `json-path probe declares no expect {count|equals|contains}` };
}

function evaluateFileSet(root, probe) {
  const dirAbs = resolve(root, probe.dir);
  if (!existsSync(dirAbs)) return { ok: false, detail: `'${probe.dir}' does not exist` };
  const ext = probe.extension ?? '.ts';
  const skip = probe.excludePathContains ?? [];
  const all = collectFiles(root, probe.dir, [ext], []);
  const present = all
    .map((f) => f.replace(root, '').replaceAll('\\', '/').replace(/^\//, ''))
    .filter((f) => !skip.some((s) => f.includes(s)));

  let registeredText = '';
  if (probe.registeredIn && existsSync(resolve(root, probe.registeredIn))) {
    registeredText = readFileSync(resolve(root, probe.registeredIn), 'utf8');
  }
  const exceptions = new Set((probe.exceptions ?? []).map((x) => x.file));

  // Self-declared mirrors are the detectable population: a file that says in its own header
  // that it hand-mirrors a backend type. Scanning every .ts under the tree and demanding each
  // be "registered" would need ~1000 exceptions; scanning for the marker keeps the probe honest
  // over the whole tree (tempdoc 884 review B2). Known mirrors that carry no marker are
  // declared in `exceptions` with a reason and `unmarked: true`, which is what makes them
  // visible at all.
  const marker = probe.mirrorMarker ? new RegExp(probe.mirrorMarker, 'i') : null;
  const flagged = [];
  for (const rel of marker ? present : []) {
    let content;
    try { content = readFileSync(resolve(root, rel), 'utf8'); } catch { continue; }
    if (marker.test(content)) flagged.push(rel);
  }

  // Match the full repo-relative path, never the basename: a basename match would call
  // a new hand-mirror "registered" because some unrelated entry shares its filename.
  const unaccounted = flagged.filter((f) => !exceptions.has(f) && !registeredText.includes(f));
  if (unaccounted.length > 0) {
    return {
      ok: false,
      detail: `${unaccounted.join(', ')} under '${probe.dir}' self-declares a hand-written mirror `
        + `but is neither registered in '${probe.registeredIn}' nor a declared exception in `
        + `governance/adr-probes.v1.json`,
    };
  }
  // An exception goes stale two ways, and only the first used to be noticed (884 review S6).
  //
  //  1. the file is gone — the entry can never be satisfied again;
  //  2. the file is still there but no longer self-declares a mirror. That is the SUCCESS case
  //     this probe drives toward (surface.ts, migrated in 884 PR 2), and leaving the entry
  //     standing turns the register into a record of things that used to be true — the drift
  //     the register exists to prevent. Detecting deletion only made the handshake mechanical
  //     for `rm` and honour-system for everything else.
  //
  // `unmarked: true` opts an entry out of (2), and is REQUIRED to express the case the register
  // already carries: `api/domains/indexing.ts` and `api/schemas.ts` are declared BECAUSE they are
  // mirrors that carry no marker, so "has no marker" is their permanent, correct state. Nothing
  // in `{file, reason}` distinguished that from "stopped being a mirror" — the prose reason is
  // not machine-readable — so the flag is the smallest honest way to tell the two apart. Without
  // the flag the default is strict: an exception is expected to be marked, because an exception
  // exists to account for something the marker scan flags.
  const stale = [];
  for (const ex of probe.exceptions ?? []) {
    if (!present.includes(ex.file)) {
      stale.push(`${ex.file} (the file no longer exists)`);
      continue;
    }
    if (marker && ex.unmarked !== true && !flagged.includes(ex.file)) {
      stale.push(`${ex.file} (it still exists but no longer self-declares a mirror)`);
    }
  }
  if (stale.length > 0) {
    return {
      ok: false,
      detail: `stale declared exception(s): ${stale.join(', ')} — drop them from the 'exceptions' `
        + `list in governance/adr-probes.v1.json. If a listed file is a hand-written mirror that `
        + `carries no marker by design, declare that with '"unmarked": true' on its entry rather `
        + `than leaving it to look like a marked one.`,
    };
  }
  return {
    ok: true,
    detail: `${present.length} file(s) scanned under '${probe.dir}', ${flagged.length} self-declared mirror(s), `
      + `${exceptions.size} declared exception(s); none unaccounted for`,
  };
}

/**
 * A premise that holds if ANY of several mechanical restatements holds.
 *
 * The motivating case is a premise that is true for two different reasons across a planned
 * migration: ADR-0007's entity boost is "retired or off", true today because the boost default
 * is 0.0 and true after the fields are deleted. Expressing that as one probe would either go
 * red the day the migration lands or be loosened to something that cannot fail.
 *
 * A failure names every alternative and why each failed — a disjunction that only said "no
 * alternative holds" would tell the reader nothing about which half of the premise broke.
 */
function evaluateAnyOf(root, probe, depth) {
  const alternatives = probe.alternatives;
  if (!Array.isArray(alternatives)) {
    return { ok: false, detail: `any-of probe declares no 'alternatives' array` };
  }
  if (alternatives.length === 0) {
    return {
      ok: false,
      detail: `any-of probe declares an empty 'alternatives' array — a probe with no claim is not a probe`,
    };
  }
  if (depth > MAX_ANY_OF_DEPTH) {
    return {
      ok: false,
      detail: `any-of nesting exceeded ${MAX_ANY_OF_DEPTH} levels — the register entry is cyclic or over-nested`,
    };
  }
  const why = [];
  for (const [i, alt] of alternatives.entries()) {
    const label = `alternative ${i + 1}/${alternatives.length} (${alt?.kind ?? 'no kind'})`;
    const r = evaluateProbe(alt ?? {}, root, depth + 1);
    if (r.ok) return { ok: true, detail: `${label} holds: ${r.detail}` };
    why.push(`${label}: ${r.detail}`);
  }
  return { ok: false, detail: `no alternative holds — ${why.join('; ')}` };
}

/**
 * Evaluate one probe against a source tree.
 *
 * @param {object} probe   a `governance/adr-probes.v1.json` entry
 * @param {string} root    the tree to evaluate against (repo root, or a fixture root)
 * @param {number} depth   `any-of` recursion depth; callers leave this at 0
 * @returns {{ok: boolean, detail: string}}
 */
export function evaluateProbe(probe, root, depth = 0) {
  if (!PROBE_KINDS.has(probe.kind)) {
    return { ok: false, detail: `unknown probe kind '${probe.kind}'` };
  }
  switch (probe.kind) {
    case 'test': return evaluateTest(root, probe);
    case 'gate': return evaluateGate(root, probe);
    case 'grep-present': return evaluateGrep(root, probe, true);
    case 'grep-absent': return evaluateGrep(root, probe, false);
    case 'json-path': return evaluateJsonPath(root, probe);
    case 'file-set': return evaluateFileSet(root, probe);
    case 'any-of': return evaluateAnyOf(root, probe, depth);
    default: return { ok: false, detail: `unhandled probe kind '${probe.kind}'` };
  }
}

/**
 * Load the probe register. Returns `{version, probes}` with probes indexed by id,
 * or `null` when the register is absent (a tree with no probes is not a failure —
 * the `no-probe` rule is what notices that).
 */
export function loadProbeRegister(root, registerPath = 'governance/adr-probes.v1.json') {
  const abs = resolve(root, registerPath);
  if (!existsSync(abs)) return null;
  let doc;
  try {
    doc = JSON.parse(readFileSync(abs, 'utf8'));
  } catch (e) {
    // A broken register must fail the gate, not crash the whole kernel run.
    return { probes: [], byId: new Map(), registerPath, parseError: e.message };
  }
  const byId = new Map();
  for (const p of doc.probes ?? []) byId.set(p.id, p);
  return { ...doc, byId, registerPath };
}
