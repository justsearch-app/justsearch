#!/usr/bin/env node
/**
 * readiness-reason-codes gate — tempdoc 600 PART IX/X.
 *
 * The degradation-cause reason vocabulary is ONE closed authority: the producer
 * (`StatusLifecycleHandler`) emits readiness reason codes from the closed
 * `LifecycleReasonCode` enum, and the FE `CAUSE_ROWS` table (`readinessNotice.ts`)
 * words them. Before this gate the two were hand-synced and DRIFTING — an unworded
 * code in a degraded verdict rendered the raw `Degraded: <code>` string to the user
 * (reproduced live for `gpu.saturated`; the Nielsen-#9 "no error codes" violation).
 *
 * This gate makes that unrepresentable by enforcing correspondence against the
 * register `governance/readiness-reason-codes.v1.json`:
 *
 *  - FORWARD (no raw code to users): every `LifecycleReasonCode` member that is NOT
 *    in `noWordingExempt` must have a `CAUSE_ROWS` row. (Exempt = codes that only
 *    ever drive a non-degraded verdict, or live on a non-verdict composite — they
 *    never reach `wordCauses`, so a row would be dead UI; PART X.)
 *  - BACKWARD (no dead/typo rows): every `CAUSE_ROWS` code is a real enum member OR
 *    a declared `feDerived` code (e.g. `no_documents`, 596 §17).
 *  - PRODUCER (no phantoms; tempdoc 837 §5): every enum member is referenced by at
 *    least one Java source under a `modules/<module>/src/main` tree, outside the enum's
 *    own file. See `checkProducers` for why, and for what it deliberately does not prove.
 *
 * Honest limits (as with the sibling gates):
 *  - the producer↔composite mapping is captured by the curated `noWordingExempt`
 *    allow-list, not computed from Java — a wrong future exemption is reviewable,
 *    far better than silent hand-sync;
 *  - a *reference* is not an *emission* (see `checkProducers`).
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const REGISTER = 'governance/readiness-reason-codes.v1.json';

/**
 * Extract `NAME("code.string")` enum members from the LifecycleReasonCode source as
 * `{ name, code }` rows. The FORWARD/BACKWARD directions only need the code; the
 * PRODUCER direction also matches on the enum NAME, so this is the one extraction
 * authority and `extractEnumCodes` is a projection of it.
 */
export function extractEnumRows(javaSrc) {
  const rows = [];
  const seen = new Set();
  // Match enum constants of the shape  IDENT("some.code")  — the only `IDENT("...")` form in this file.
  const re = /\b([A-Z][A-Z0-9_]*)\s*\(\s*"([^"]+)"\s*\)/g;
  let m;
  while ((m = re.exec(javaSrc)) !== null) {
    if (seen.has(m[2])) continue;
    seen.add(m[2]);
    rows.push({ name: m[1], code: m[2] });
  }
  return rows;
}

/** Extract `NAME("code.string")` enum-member code strings from the LifecycleReasonCode source. */
export function extractEnumCodes(javaSrc) {
  return new Set(extractEnumRows(javaSrc).map((r) => r.code));
}

/** Extract `code: '...'` values from the CAUSE_ROWS array of readinessNotice.ts. */
export function extractCauseRowCodes(tsSrc) {
  const start = tsSrc.indexOf('CAUSE_ROWS');
  const slice = start >= 0 ? tsSrc.slice(start) : tsSrc;
  const codes = new Set();
  const re = /\bcode:\s*'([^']+)'/g;
  let m;
  // Stop at the array terminator `];` that closes CAUSE_ROWS.
  const end = slice.indexOf('\n];');
  const region = end >= 0 ? slice.slice(0, end) : slice;
  while ((m = re.exec(region)) !== null) codes.add(m[1]);
  return codes;
}

/** Pure correspondence check. Returns an array of failure strings (empty = pass). */
export function checkCorrespondence({ enumCodes, causeRowCodes, noWordingExempt, feDerived }) {
  const failures = [];
  const exempt = new Set(noWordingExempt);
  const fe = new Set(feDerived);

  // FORWARD — every non-exempt emittable code must be worded.
  for (const code of enumCodes) {
    if (exempt.has(code)) continue;
    if (!causeRowCodes.has(code)) {
      failures.push(
        `forward: reason code \`${code}\` is emittable (LifecycleReasonCode member) but has no ` +
          `CAUSE_ROWS row in readinessNotice.ts — a degraded verdict carrying it would render the raw ` +
          `\`Degraded: ${code}\` string to the user. Add a CAUSE_ROWS row (plain wording + severity, ` +
          `optional remedy), or declare it in ${REGISTER} \`noWordingExempt\` if it never reaches a ` +
          `degraded verdict (with a one-line rationale).`,
      );
    }
  }

  // BACKWARD — every worded code must be a real emittable code or a declared FE-derived one.
  for (const code of causeRowCodes) {
    if (enumCodes.has(code) || fe.has(code)) continue;
    failures.push(
      `backward: CAUSE_ROWS has a row for \`${code}\`, which is neither a LifecycleReasonCode member ` +
        `nor a declared \`feDerived\` code in ${REGISTER} — a dead or mistyped row (the user would never ` +
        `see it, or it shadows a typo). Remove it, fix the code, or declare it FE-derived.`,
    );
  }
  return failures;
}

/**
 * Blank out Java comments (line comments to end-of-line, and block comments) while
 * preserving string and char literals — including text blocks — so that a `//` inside a
 * literal (`"http://…"`) does not swallow the rest of the line.
 *
 * Replacing comment bytes with spaces rather than deleting them keeps offsets stable,
 * which is what makes "strip, then match" safe: nothing new can be spliced together
 * across a removed comment.
 */
export function stripJavaComments(src) {
  const out = Array.from(src);
  const blank = (from, to) => {
    for (let k = from; k < to; k += 1) if (out[k] !== '\n') out[k] = ' ';
  };
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (c === '/' && src[i + 1] === '/') {
      let j = i;
      while (j < src.length && src[j] !== '\n') j += 1;
      blank(i, j);
      i = j;
    } else if (c === '/' && src[i + 1] === '*') {
      let j = src.indexOf('*/', i + 2);
      j = j === -1 ? src.length : j + 2;
      blank(i, j);
      i = j;
    } else if (c === '"' && src.startsWith('"""', i)) {
      let j = src.indexOf('"""', i + 3);
      i = j === -1 ? src.length : j + 3;
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < src.length && src[j] !== c && src[j] !== '\n') {
        j += src[j] === '\\' ? 2 : 1;
      }
      i = j + 1;
    } else {
      i += 1;
    }
  }
  return out.join('');
}

/**
 * PRODUCER (tempdoc 837 §5) — every non-feDerived enum code must be referenced by at least one
 * Java source under a `modules/<module>/src/main` tree, outside the enum's own file, by enum
 * NAME (`LifecycleReasonCode.<NAME>`) or by its quoted code string.
 *
 * Why: a code nothing can emit is a phantom. It cannot degrade anything, its `CAUSE_ROWS`
 * wording is unreachable UI, and it makes the vocabulary lie about what this system can
 * report. Four `ORT_CUDA_*` codes lived here for months, two of them with user-facing
 * wording for a state that could not occur (837 §4).
 *
 * Sources are comment-stripped BEFORE matching. A plain substring scan would count javadoc
 * mentions as producers, and this codebase is dense with them — so a comment-blind check
 * would certify precisely the bug this direction exists to catch.
 *
 * Name-matching is the primary signal (837 §5.2 measured 0 codes as string-literal-only).
 * String-matching is defense in depth for a future producer that emits the literal without
 * ever naming the enum — the shape `TikaOcrRuntime` / `VduCapabilityState` already have on
 * their producing side.
 *
 * SCOPE — this direction catches the ZERO-REFERENCE class (4/4 of the real phantoms). It does
 * NOT distinguish a producer from a consumer; that is now `checkEmissions`, added at the lane-F
 * stage-A checkpoint after this file's own "HONEST LIMIT" note came true. The note used
 * `WORKER_RESTART_EXHAUSTED.code().equals(…)` as its example of a consumer reference that
 * satisfies this check, and `WORKER_RESTART_EXHAUSTED` turned out to be exactly the code with no
 * producer — two passes of a design document disagreed about whether it had one and this gate
 * had no opinion. Both directions run; they answer different questions.
 *
 * @param enumRows      `{ name, code }[]` from `extractEnumRows`
 * @param mainSources   `{ path, text }[]` — raw Java sources; comments stripped here
 * @param feDerived     FE-only codes (the only exemption; `noWordingExempt` deliberately does
 *                      NOT exempt from this direction — the two lists answer different questions)
 */
export function checkProducers({ enumRows, mainSources, feDerived }) {
  const fe = new Set(feDerived);
  const haystacks = mainSources.map((s) => stripJavaComments(s.text));
  const failures = [];

  for (const { name, code } of enumRows) {
    if (fe.has(code)) continue;
    const byName = `LifecycleReasonCode.${name}`;
    const byString = `"${code}"`;
    // `\b` after the name so a longer sibling cannot satisfy a shorter code: without it,
    // a reference to WORKER_LOST_PERMANENTLY would mark WORKER_LOST as produced. Enum names
    // are [A-Z][A-Z0-9_]* so they carry no regex metacharacters and need no escaping.
    const nameRe = new RegExp(`LifecycleReasonCode\\.${name}\\b`);
    const found = haystacks.some((h) => nameRe.test(h) || h.includes(byString));
    if (found) continue;
    failures.push(
      `producer: reason code \`${code}\` (${name}) has NO emit site — no file under ` +
        `modules/*/src/main references \`${byName}\` or the literal ${byString}. A code nothing ` +
        `emits is a phantom: its CAUSE_ROWS wording is unreachable UI and the vocabulary claims a ` +
        `state this system cannot report. PRODUCE it (add the emit site) or DELETE it (enum member ` +
        `+ CAUSE_ROWS row + any ${REGISTER} entry — the full sweep). If it is FE-only, declare it ` +
        `in \`feDerived\` with a one-line rationale.`,
    );
  }
  return failures;
}

/**
 * A mention that is a COMPARISON or a LOG ARGUMENT, not a production of the code.
 *
 * <p>Derived from the shapes actually present in this tree, not invented: `.equals(…)` /
 * `.equalsIgnoreCase(…)` / `Objects.equals(…)` receivers and arguments, `case` labels, and
 * SLF4J-style `log.warn/info/debug/error/trace(…)` arguments. Measuring against these leaves
 * exactly one enum member with no production site, which is the independently-reported answer —
 * a predicate tuned to produce a desired result would not have landed on the same one.
 */
const CONSUMER_BEFORE = [
  /\.equals\s*\(\s*$/,
  /\.equalsIgnoreCase\s*\(\s*$/,
  /Objects\.equals\s*\(\s*$/,
  /\bcase\s+$/,
  /\blog\.(warn|info|debug|error|trace)\s*\([^;]*$/,
];
const CONSUMER_AFTER = [/^\s*\.code\(\)\s*\.equals/, /^\s*\.code\(\)\s*==/, /^\s*\.equals\s*\(/, /^\s*==/];

/**
 * EMISSION — every non-feDerived code must appear at least once in a VALUE position: assigned,
 * returned, or passed as an argument to something other than a comparison or a logger.
 *
 * <p><b>Why this exists.</b> {@link checkProducers} asks whether a code is *mentioned*, which is a
 * strictly weaker question, and this file's own comment said so — it named
 * `WORKER_RESTART_EXHAUSTED.code().equals(…)` as the example of a mention that is not a producer.
 * At the lane-F stage-A checkpoint that example turned out to be the live case: all four of that
 * code's main-source sites are `.equals(...)` comparisons or log arguments
 * (`KnowledgeServerBootstrap.java:444-447, 729-732`, `KnowledgeServerHealthMonitor.java:342-344,
 * 498-500`), nothing transitions to it, and `supervisionActive()` is hard-coded `false`
 * (`KnowledgeServerBootstrap.java:460-462`). Two passes of a design document reached opposite
 * conclusions about whether it had a producer and this gate certified both.
 *
 * <p><b>Honest limits, so nobody reads this as stronger than it is.</b> It is a syntactic
 * heuristic over comment-stripped source, not a dataflow analysis. A code produced only through a
 * helper this predicate cannot see would be misread as emitted (false GREEN), and a genuinely new
 * comparison idiom would be misread as an emission. It cannot prove the emission is *reachable* —
 * only that a value-position use exists. What it does buy is the specific class that occurred:
 * a vocabulary member that every reader treats as producible while nothing produces it.
 *
 * <p>`awaitingProducer` is the deliberate escape hatch: a code whose producer is scheduled but not
 * yet written stays declared, with an owner, so the debt is visible and dated instead of silently
 * indistinguishable from a live code.
 */
export function checkEmissions({ enumRows, mainSources, feDerived, awaitingProducer }) {
  const fe = new Set(feDerived);
  const awaiting = new Map((awaitingProducer ?? []).map((e) => [e.code, e]));
  const haystacks = mainSources.map((s) => stripJavaComments(s.text));
  const failures = [];
  const stillAwaiting = [];

  for (const { name, code } of enumRows) {
    if (fe.has(code)) continue;
    const re = new RegExp(
      `LifecycleReasonCode\\.${name}\\b|"${code.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"`,
      'g',
    );
    let emissions = 0;
    for (const h of haystacks) {
      for (const m of h.matchAll(re)) {
        const before = h.slice(Math.max(0, m.index - 200), m.index);
        const after = h.slice(m.index + m[0].length, m.index + m[0].length + 60);
        const consumer =
          CONSUMER_BEFORE.some((r) => r.test(before)) || CONSUMER_AFTER.some((r) => r.test(after));
        if (!consumer) emissions++;
      }
    }

    const declared = awaiting.get(code);
    if (emissions > 0) {
      if (declared) {
        failures.push(
          `emission: reason code \`${code}\` (${name}) is declared in ${REGISTER} ` +
            `\`awaitingProducer\` (owner: ${declared.owner}) but now HAS a production site. The ` +
            `debt is paid — delete the awaitingProducer entry so the list keeps meaning ` +
            `"nothing produces this yet".`,
        );
      }
      continue;
    }

    if (declared) {
      stillAwaiting.push(`${code} (owner: ${declared.owner})`);
      continue;
    }

    failures.push(
      `emission: reason code \`${code}\` (${name}) is REFERENCED but never PRODUCED — every ` +
        `main-source mention is a comparison (\`.equals\`, \`case\`) or a log argument. A code only ` +
        `ever compared against is one nothing can put the system into: the branches that read it ` +
        `are unreachable, and any document claiming it "has a producer" is wrong. Either add the ` +
        `production site, or declare it in ${REGISTER} \`awaitingProducer\` with an \`owner\` ` +
        `naming who lands the producer — so it is visible debt rather than a silent phantom.`,
    );
  }
  return { failures, stillAwaiting };
}

/**
 * Walk `modules/` for every `.java` file whose path contains a `src/main` segment, skipping
 * `build/` output (generated sources are not authored emit sites) and the enum's own
 * declaration file. Reads the tree directly rather than shelling out to `git grep`, so the
 * check behaves identically in CI and on a dirty worktree.
 */
export function collectMainSources(root = 'modules', excludePath = '') {
  const exclude = excludePath.replace(/\\/g, '/');
  const sources = [];
  const walk = (dir) => {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const p = join(dir, e.name);
      if (e.isDirectory()) {
        if (e.name === 'build' || e.name === 'node_modules' || e.name === '.gradle') continue;
        walk(p);
      } else if (e.isFile() && e.name.endsWith('.java')) {
        const norm = p.replace(/\\/g, '/');
        if (!norm.includes('/src/main/')) continue;
        if (exclude && norm.endsWith(exclude)) continue;
        sources.push({ path: norm, text: readFileSync(p, 'utf8') });
      }
    }
  };
  if (statSyncSafe(root)) walk(root);
  return sources;
}

function statSyncSafe(p) {
  try {
    return statSync(p).isDirectory();
  } catch {
    return false;
  }
}

function main() {
  const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));
  const enumRows = extractEnumRows(readFileSync(reg.producer.file, 'utf8'));
  const enumCodes = new Set(enumRows.map((r) => r.code));
  const causeRowCodes = extractCauseRowCodes(readFileSync(reg.consumer.file, 'utf8'));

  if (enumCodes.size === 0 || causeRowCodes.size === 0) {
    console.error(
      `✗ readiness-reason-codes gate FAILED: could not extract codes ` +
        `(enum=${enumCodes.size}, CAUSE_ROWS=${causeRowCodes.size}) — the producer/consumer seam moved; ` +
        `update ${REGISTER}.`,
    );
    process.exit(1);
  }

  const feDerived = (reg.feDerived ?? []).map((e) => e.code);
  const failures = checkCorrespondence({
    enumCodes,
    causeRowCodes,
    noWordingExempt: (reg.noWordingExempt ?? []).map((e) => e.code),
    feDerived,
  });

  if (failures.length > 0) {
    console.error(
      '✗ readiness-reason-codes gate FAILED (tempdoc 600 PART IX/X):\n' +
        failures.map((x) => '  - ' + x).join('\n'),
    );
    process.exit(1);
  }

  const mainSources = collectMainSources('modules', reg.producer.file);
  if (mainSources.length === 0) {
    console.error(
      `✗ readiness-reason-codes gate FAILED (producer direction, tempdoc 837): found no ` +
        `modules/**/src/main Java sources to scan — the tree layout moved, and an empty corpus ` +
        `would pass this direction vacuously.`,
    );
    process.exit(1);
  }

  const producerFailures = checkProducers({ enumRows, mainSources, feDerived });
  if (producerFailures.length > 0) {
    console.error(
      '✗ readiness-reason-codes gate FAILED (producer direction, tempdoc 837):\n' +
        producerFailures.map((x) => '  - ' + x).join('\n'),
    );
    process.exit(1);
  }

  const { failures: emissionFailures, stillAwaiting } = checkEmissions({
    enumRows,
    mainSources,
    feDerived,
    awaitingProducer: reg.awaitingProducer ?? [],
  });
  if (emissionFailures.length > 0) {
    console.error(
      '✗ readiness-reason-codes gate FAILED (emission direction, lane-F stage-A checkpoint):\n' +
        emissionFailures.map((x) => '  - ' + x).join('\n'),
    );
    process.exit(1);
  }

  const exemptCount = enumRows.filter((r) => feDerived.includes(r.code)).length;
  console.log(
    `✓ readiness-reason-codes gate OK — producer↔CAUSE_ROWS correspond ` +
      `(${enumCodes.size} emittable codes, ${causeRowCodes.size} worded rows); no raw code can reach the ` +
      `degradation banner. Producer direction OK — all ${enumRows.length - exemptCount} codes have ≥1 ` +
      `emit-site reference across ${mainSources.length} modules/**/src/main sources; ` +
      `${exemptCount} exempt.`,
  );
  console.log(
    stillAwaiting.length === 0
      ? `  emission direction OK — every non-exempt code has a production site (not merely a ` +
          `reference), and nothing is awaiting a producer.`
      : `  emission direction OK — every non-exempt code has a production site, except ` +
          `${stillAwaiting.length} declared as awaiting one: ${stillAwaiting.join(', ')}. These are ` +
          `tracked debt, not phantoms; the gate reds if a producer appears and the entry is not removed.`,
  );
}

// Run as CLI only (not when imported by the test). Basename check is robust cross-platform.
if (process.argv[1] && process.argv[1].replace(/\\/g, '/').endsWith('check-readiness-reason-codes.mjs')) {
  main();
}
