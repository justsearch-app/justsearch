#!/usr/bin/env node
/**
 * ui-baseline-schemas gate — make the UI baselines' `$schema` pointers load-bearing.
 *
 * `governance/ui-a11y-baseline.v1.json` declared `"$schema": "./ui-a11y-baseline.schema.json"` from
 * the day it landed, and that file did not exist — so the register was unvalidated while LOOKING
 * validated. Its sibling `ui-proportion-baseline.schema.json` even described itself as "mirrors
 * ui-a11y-baseline.schema.json's shape", citing a file nobody could open. A pointer that resolves to
 * nothing is worse than no pointer: it reads as an assurance, and every editor that would have
 * flagged a malformed row silently stops.
 *
 * So for each register below the gate asserts: the pointer RESOLVES, the schema COMPILES, and the
 * document VALIDATES. Plus one uniqueness rule draft-07 cannot express — the a11y baseline's rows
 * are keyed by `uiShotStep` (several rows share a `surface` on purpose, e.g. chat's eight capture
 * states), and two rows for one step would give the register two disagreeing answers to "what is
 * accepted here".
 *
 * SCOPE IS BY EXPLICIT ROW, mirroring the proportion register's own adoption doctrine. The same
 * defect class exists elsewhere in `governance/` — `declaration-kinds.v1.json` and
 * `sandbox-defect-classes.v1.json` name schemas that are not files at all, `design-reference.v1.json`
 * points at a missing sibling, and `registry.v1.json` does not validate against the schema it DOES
 * name. Those are pre-existing and belong to their own authorities; they are
 * logged to the observations inbox rather than swept into this gate, because making them green means
 * changing registers this gate has no business editing. Adding a register here is the adoption step.
 *
 * Usage: node scripts/ci/check-ui-baseline-schemas.mjs
 */
import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import Ajv from 'ajv';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

/** The registers whose `$schema` pointer this gate holds to its word. */
export const ENFORCED = ['ui-a11y-baseline.v1.json', 'ui-proportion-baseline.v1.json'];

/** Exported for the unit test: run the whole check over a governance dir, returning failure strings. */
export function checkUiBaselineSchemas(govDir = resolve(REPO_ROOT, 'governance'), registers = ENFORCED) {
  const failures = [];

  for (const register of registers) {
    const path = join(govDir, register);
    if (!existsSync(path)) {
      failures.push(`governance/${register}: enforced register is missing`);
      continue;
    }
    let doc;
    try {
      doc = JSON.parse(readFileSync(path, 'utf8'));
    } catch (e) {
      failures.push(`governance/${register}: not valid JSON — ${e.message}`);
      continue;
    }

    const pointer = doc?.$schema;
    if (typeof pointer !== 'string' || pointer.length === 0) {
      failures.push(
        `governance/${register}: no $schema pointer — this register is enforced, so its shape must ` +
          `be written down somewhere a machine reads.`,
      );
      continue;
    }
    if (/^https?:\/\//.test(pointer)) {
      failures.push(
        `governance/${register}: $schema "${pointer}" is remote — this gate validates offline, so ` +
          `point at a sibling file in governance/.`,
      );
      continue;
    }

    const schemaPath = resolve(govDir, pointer);
    if (!existsSync(schemaPath)) {
      failures.push(
        `governance/${register}: $schema "${pointer}" resolves to no file — the register LOOKS ` +
          `validated and is not. Write the schema, or drop the pointer.`,
      );
      continue;
    }

    let schema;
    try {
      schema = JSON.parse(readFileSync(schemaPath, 'utf8'));
    } catch (e) {
      failures.push(`${pointer}: not valid JSON — ${e.message}`);
      continue;
    }

    // strict:false — these are draft-07 schemas and ajv's strict mode nags about keywords they use
    // legitimately. What is being checked here is DOCUMENT conformance, not schema-authoring style.
    const ajv = new Ajv({ allErrors: true, strict: false });
    let validate;
    try {
      validate = ajv.compile(schema);
    } catch (e) {
      failures.push(`${pointer}: does not compile — ${e.message}`);
      continue;
    }
    if (!validate(doc)) {
      for (const err of validate.errors ?? []) {
        failures.push(
          `governance/${register}: ${err.instancePath || '/'} ${err.message}` +
            (err.params && Object.keys(err.params).length ? ` ${JSON.stringify(err.params)}` : ''),
        );
      }
    }
  }

  // Draft-07 cannot say "unique by uiShotStep". Two rows for one step = two accepted rule sets for
  // one capture, and whichever the consumer reads first silently wins.
  const a11yPath = join(govDir, 'ui-a11y-baseline.v1.json');
  if (registers.includes('ui-a11y-baseline.v1.json') && existsSync(a11yPath)) {
    let surfaces = [];
    try {
      surfaces = JSON.parse(readFileSync(a11yPath, 'utf8')).surfaces ?? [];
    } catch {
      /* the parse failure is already reported above */
    }
    const seen = new Map();
    for (const s of surfaces) {
      if (seen.has(s.uiShotStep)) {
        failures.push(
          `governance/ui-a11y-baseline.v1.json: duplicate uiShotStep "${s.uiShotStep}" (surfaces ` +
            `"${seen.get(s.uiShotStep)}" and "${s.surface}") — a step has ONE accepted rule set.`,
        );
      }
      seen.set(s.uiShotStep, s.surface);
    }
  }

  return failures;
}

// pathToFileURL, not a hand-built `file://` string: on Windows the two forms differ (drive letter,
// triple slash) and a mismatched comparison makes this script exit 0 having checked nothing.
if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const failures = checkUiBaselineSchemas();
  if (failures.length > 0) {
    console.error(`ui-baseline-schemas FAIL: ${failures.length} finding(s):`);
    for (const f of failures) console.error('  ' + f);
    process.exit(1);
  }
  console.log(
    `ui-baseline-schemas OK — ${ENFORCED.length} register(s) resolve their $schema and validate against it.`,
  );
}
