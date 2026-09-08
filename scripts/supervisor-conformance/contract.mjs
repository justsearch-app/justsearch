/**
 * The supervisor conformance contract (design 7.1 "Conformance", lane F stage B item B7).
 *
 * This module holds NO cases of its own. Every case is a row of
 * `governance/supervision-contract.v1.json`'s `engine` entry, which is also what
 * `modules/shell/src-tauri/src/supervisor.rs` embeds with `include_str!` for its own decision-table
 * tests. That is the whole point: "one contract, two implementations" is only true while both are
 * reading one list, and a second list that agrees today is a fork with a delay fuse.
 *
 * What this module DOES own:
 *   - selecting the cases each half runs, and refusing a case an adapter silently skipped;
 *   - checking, before any case runs, that the register's exit table still agrees with the Java
 *     source that produces those integers. The harness would otherwise be able to pass against a
 *     table that no longer describes the Engine.
 */

import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const HERE = path.dirname(fileURLToPath(import.meta.url));

const supervisor = require(path.join(HERE, '..', 'dev', 'lib', 'engine-supervisor.cjs'));

export const {
  ACTIONS,
  STATES,
  HARNESS_FLAG,
  OVERRIDE_ENV,
  decide,
  classifyExit,
  describeExit,
  loadPolicy,
  engineRow,
  resolveRepoRoot,
} = supervisor;

export const repoRoot = resolveRepoRoot(HERE);

const ENGINE_EXIT_SOURCE = path.join(
  repoRoot,
  'modules',
  'app-engine',
  'src',
  'main',
  'java',
  'io',
  'justsearch',
  'app',
  'engine',
  'EngineExit.java',
);

/**
 * Read the exit table out of `EngineExit.java` — the class that produces the integers — as text.
 *
 * Text, not a compiled dependency, because the two things that read this table are Node and Rust
 * and neither can load a JVM class. `EngineSupervisionPolicyTest` asserts the same agreement from
 * inside the JVM with reflection; this one exists so the harness refuses to run on drift without
 * waiting for a Gradle build, which is the difference between catching it in 200 ms and catching it
 * in four minutes.
 */
export function readEngineExitSource(source = ENGINE_EXIT_SOURCE) {
  const text = fs.readFileSync(source, 'utf8');
  const codes = new Map();
  const constantRe = /public\s+static\s+final\s+int\s+([A-Z_0-9]+)\s*=\s*(-?\d+)\s*;/g;
  for (let m = constantRe.exec(text); m; m = constantRe.exec(text)) {
    codes.set(m[1], Number(m[2]));
  }
  const classes = new Map();
  let fallback = null;
  const armRe = /case\s+([A-Z_0-9,\s]+?)\s*->\s*ExitClass\.([A-Z_]+)\s*;/g;
  for (let m = armRe.exec(text); m; m = armRe.exec(text)) {
    for (const name of m[1].split(',').map((s) => s.trim()).filter(Boolean)) {
      classes.set(name, m[2]);
    }
  }
  const defaultRe = /default\s*->\s*ExitClass\.([A-Z_]+)\s*;/;
  const dm = defaultRe.exec(text);
  if (dm) fallback = dm[1];
  return { codes, classes, fallback };
}

/**
 * Fail loudly if the register's projection of EngineExit has drifted from EngineExit itself.
 * Returns the list of problems (empty when they agree) rather than throwing, so a caller can print
 * every disagreement at once instead of one per run.
 */
export function checkExitTableAgreement(row = engineRow(), source = ENGINE_EXIT_SOURCE) {
  const problems = [];
  let parsed;
  try {
    parsed = readEngineExitSource(source);
  } catch (err) {
    return [`cannot read ${source}: ${err.message}`];
  }
  if (parsed.codes.size === 0) {
    return [`parsed no exit constants out of ${source} — the parser, not the table, is broken`];
  }
  const declared = new Map();
  for (const entry of row.exitCodes ?? []) {
    declared.set(entry.name, entry);
    const code = parsed.codes.get(entry.name);
    if (code === undefined) {
      problems.push(`register declares EngineExit.${entry.name}, which the Java source does not`);
      continue;
    }
    if (code !== entry.code) {
      problems.push(`EngineExit.${entry.name} is ${code} in Java, ${entry.code} in the register`);
    }
    const cls = parsed.classes.get(entry.name) ?? parsed.fallback;
    if (cls !== entry.class) {
      problems.push(`EngineExit.${entry.name} classifies ${cls} in Java, ${entry.class} in the register`);
    }
  }
  for (const name of parsed.codes.keys()) {
    if (!declared.has(name)) {
      problems.push(
        `EngineExit declares ${name}, which the register does not carry — both supervisors read the`
          + ' register, so an unlisted code is one neither of them can name',
      );
    }
  }
  if (parsed.fallback && parsed.fallback !== row.unknownExitClass) {
    problems.push(
      `EngineExit's default arm is ${parsed.fallback}, the register's unknownExitClass is ${row.unknownExitClass}`,
    );
  }
  return problems;
}

/** Every declared conformance case, in register order. */
export function allCases(row = engineRow()) {
  const cases = row.conformanceCases ?? [];
  if (cases.length === 0) {
    throw new Error('the engine row declares no conformanceCases — an empty harness is a green lie');
  }
  return cases;
}

/** Cases whose pure decision the two `decide` implementations must agree on. */
export function decisionCases(row = engineRow()) {
  return allCases(row).filter((c) => (c.drives ?? []).includes('decision'));
}

/**
 * Cases an adapter must drive against a REAL child.
 *
 * A case is here or it is not; there is no per-adapter skip list, because "one contract" stops
 * being true the moment one implementation is allowed to answer N/A. `run.mjs` fails the harness on
 * a case an adapter did not report a result for, rather than printing `skipped` and exiting 0.
 */
export function actuatorCases(row = engineRow()) {
  return allCases(row).filter((c) => (c.drives ?? []).includes('actuator'));
}

/**
 * The fake engine's per-incarnation plan for a case — shared by BOTH adapters.
 *
 * Shared, and not copied into each adapter, for the same reason the case list is: two adapters with
 * their own plans are two contracts that agree until someone edits one. What each adapter owns is
 * how its implementation is started and observed; what the case means is the register's, and this
 * is the last piece of that meaning that could not be expressed as data.
 *
 * `exitAfterMs` on the faulting incarnation is generous (1500 ms) because of a real constraint
 * rather than padding: both supervisors have to reach `running` before the fault, or the case is
 * exercising the START path and not the supervisor.
 */
export function enginePlanFor(testCase) {
  const engine = testCase.engine ?? {};
  const fault = { ...engine };
  if (fault.exitAfterMs === undefined) fault.exitAfterMs = 1500;
  if (fault.hangAfterMs === undefined && String(fault.mode).startsWith('hang')) fault.hangAfterMs = 1200;
  // A slower request-file cadence on the Engine side widens the window the SUPERVISOR has to
  // observe an out-of-band request in. It is a fixture cadence, not a subject one: the supervisor
  // still has to read the file itself, and the real Engine's watcher interval is item B3's.
  const honour = { mode: 'honour', requestPollMs: 500 };
  switch (testCase.id) {
    case 'clean-exit-0-stops':
    case 'requested-upgrade-stops':
      return [{ ...fault, requestPollMs: 500 }];
    case 'budget-exhausted-after-max-attempts':
      // Sticky last entry: every incarnation crashes, so the budget is what has to stop it.
      return [{ ...fault, exitAfterMs: 1500 }];
    case 'non-transient-2-exhausts-at-once':
      // Incarnation 1 must reach `running` for the death to be supervised at all, so the
      // non-transient exit is incarnation 2's. What the case asserts is that it gives up THERE,
      // with budget still unspent, rather than spending the remaining attempts on it.
      return [{ mode: 'crash', exitCode: 1, exitAfterMs: 1500 }, { ...fault, exitAfterMs: 200 }];
    case 'requested-restart-is-not-counted':
      return [{ mode: 'honour', requestPollMs: 500 }, honour];
    case 'start-deadline-stops-a-partial-boot':
      return [{ mode: 'crash', exitCode: 1, exitAfterMs: 1500 }, { ...fault, requestPollMs: 500 }];
    default:
      return [fault, honour];
  }
}

/** The policy a case runs under: the loaded policy plus the case's own explicit override, if any. */
export function policyForCase(testCase, policy) {
  if (!testCase.policyOverride) return policy;
  return { ...policy, ...testCase.policyOverride };
}

/**
 * Compare a decision against a case's `expect` block.
 *
 * Only the keys the case DECLARES are compared. A case that says nothing about `cooldownMs` is
 * making no claim about it, and inventing one here would turn the register's silence into an
 * assertion the register's author never wrote.
 */
export function diffAgainstExpectation(actual, expect) {
  const problems = [];
  for (const [key, want] of Object.entries(expect)) {
    const got = actual[key];
    if (got !== want) {
      problems.push(`${key}: expected ${JSON.stringify(want)}, got ${JSON.stringify(got)}`);
    }
  }
  return problems;
}

/** Run every decision case through the JS `decide` and return per-case problems. */
export function runDecisionTable({ policy, row = engineRow() } = {}) {
  const results = [];
  for (const testCase of decisionCases(row)) {
    const casePolicy = policyForCase(testCase, policy);
    let problems;
    try {
      problems = diffAgainstExpectation(decide(testCase.observation, casePolicy), testCase.expect);
    } catch (err) {
      problems = [`decide threw: ${err.message}`];
    }
    results.push({ id: testCase.id, problems });
  }
  return results;
}

export const ENGINE_EXIT_SOURCE_PATH = ENGINE_EXIT_SOURCE;
export const FAKE_ENGINE = path.join(HERE, 'fake-engine.mjs');
