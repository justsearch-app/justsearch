#!/usr/bin/env node
/**
 * The generated-file regen set — tempdoc 930 chunk F.
 *
 * Seven `check-*-regen.mjs` scripts existed, each ~25 lines of the identical body: spawn one
 * `scripts/codegen/gen-*.mjs --check` and propagate its exit code. Seven files, seven CI steps,
 * seven npm scripts and seven CLAUDE.md pre-merge rows for one property — a generated file must
 * match its source. Tempdoc 930 §19.3 F9 counted them; this is the fold.
 *
 * The generator remains the authority: this runner adds no drift logic of its own, it only
 * enumerates and dispatches, and a drift is reported in the generator's own words (`stdio:
 * 'inherit'`). It fails on the FIRST drift rather than collecting all of them, because the fix for
 * every entry is the same one command and a second failure adds nothing to the first.
 *
 *   node scripts/ci/regen-all.mjs --check   # exit non-zero on the first generated file that drifted
 *   node scripts/ci/regen-all.mjs           # regenerate every generated file in place
 *   node scripts/ci/regen-all.mjs --list    # print the set and exit
 *   node scripts/ci/regen-all.mjs --check --only notices     # one entry
 *   node scripts/ci/regen-all.mjs --check --except notices   # everything else
 *
 * `--only` / `--except` exist for ONE reason: `notices` reads produced license reports
 * (`gradlew generateLicenseReport` + `license-checker`), so it belongs in the CI lane that builds
 * them, while the other seven are hermetic. They are a lane selector, not a skip switch — an
 * absent input still fails loudly (tempdoc 742: a precondition-less enforcement mechanism must
 * fail, not degrade to a pass), and an id that matches no generator aborts rather than being
 * silently dropped.
 *
 * NOT in this set, deliberately:
 *   - `scripts/ci/check-reference-client-openapi-regen.mjs` — its check is a Gradle task
 *     (`:modules:ui:generateReferenceClientOpenApiSnapshot`), not a node generator. Folding it in
 *     would make this runner require a JVM, so it stays a step of its own.
 *   - `scripts/ci/check-shape-handler-live.mjs --live` — an oracle against a RUNNING backend, not a
 *     regen check. The static half of that script is `gen-shape-handlers` below.
 */

import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

/**
 * The regen set. `script` is relative to the repo root; `args` are extra flags the generator needs
 * in BOTH modes. Adding a generator here is the whole wiring — no new CI step, npm script or
 * pre-merge row.
 */
export const GENERATORS = [
  {
    id: 'api-client',
    script: 'scripts/codegen/gen-api-client.mjs',
    args: [],
    source: 'modules/ui-web/src/api/generated/route-manifest.snapshot.json',
    tempdoc: 583,
  },
  {
    id: 'field-constants',
    script: 'scripts/codegen/gen-field-constants.mjs',
    args: [],
    source: 'SSOT/catalogs/fields.v1.json',
    tempdoc: 594,
  },
  {
    id: 'liveness-constants',
    script: 'scripts/codegen/gen-liveness-constants.mjs',
    args: [],
    source: 'governance/observed-happening.v1.json',
    tempdoc: 575,
  },
  {
    id: 'wire-schema-types',
    script: 'scripts/codegen/gen-wire-schema-types.mjs',
    args: [],
    source: 'SSOT/schemas/*.v1.json',
    tempdoc: 564,
  },
  {
    id: 'notices',
    script: 'scripts/codegen/gen-notices.mjs',
    args: [],
    source: 'the model registry + the jk1/npm license reports',
    tempdoc: 632,
  },
  {
    id: 'shape-handlers',
    script: 'scripts/codegen/gen-shape-handlers.mjs',
    args: [],
    source: 'the bundled ConversationShape fixture',
    tempdoc: 491,
  },
];

/**
 * Run the set. Injectable `run` + `generators` are the test seam.
 *
 * @returns {{status:number, failed:string|null}} `status` is the first non-zero child status.
 */
export function runRegenSet({
  check = true,
  generators = GENERATORS,
  run = spawnSync,
  repoRoot = REPO_ROOT,
} = {}) {
  for (const gen of generators) {
    const args = [join(repoRoot, gen.script), ...(gen.args ?? [])];
    if (check) args.push('--check');
    const result = run(process.execPath, args, { cwd: repoRoot, stdio: 'inherit' });
    const status = result.error ? 1 : (result.status ?? 1);
    if (status !== 0) {
      console.error(
        `\nregen-all: \`${gen.id}\` is out of date. Regenerate it and commit the result:\n` +
          `  node ${gen.script}\n` +
          `(source: ${gen.source})`,
      );
      return { status, failed: gen.id };
    }
  }
  return { status: 0, failed: null };
}

/** Read `--only a,b` / `--only=a,b` (repeatable) into a list of ids. */
export function readIdList(argv, flag) {
  const ids = [];
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === flag) ids.push(...(argv[i + 1] ?? '').split(','));
    else if (argv[i].startsWith(`${flag}=`)) ids.push(...argv[i].slice(flag.length + 1).split(','));
  }
  return ids.map((s) => s.trim()).filter(Boolean);
}

/** Apply `--only` / `--except`. An id that resolves to no generator is an error, never a no-op. */
export function selectGenerators(argv, generators = GENERATORS) {
  const only = readIdList(argv, '--only');
  const except = readIdList(argv, '--except');
  const known = new Set(generators.map((g) => g.id));
  for (const id of [...only, ...except]) {
    if (!known.has(id)) throw new Error(`regen-all: no generator with id \`${id}\``);
  }
  return generators.filter(
    (g) => (only.length === 0 || only.includes(g.id)) && !except.includes(g.id),
  );
}

function main(argv) {
  if (argv.includes('--list')) {
    for (const gen of GENERATORS) console.log(`${gen.id}\t${gen.script}`);
    return 0;
  }
  const check = argv.includes('--check');
  let generators;
  try {
    generators = selectGenerators(argv);
  } catch (e) {
    console.error(e.message);
    return 2;
  }
  const { status, failed } = runRegenSet({ check, generators });
  if (status === 0) {
    console.log(
      check
        ? `regen-all: OK — all ${generators.length} generated file sets match their sources.`
        : `regen-all: regenerated ${generators.length} generated file sets.`,
    );
  } else {
    console.error(`regen-all: FAILED at \`${failed}\`.`);
  }
  return status;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exit(main(process.argv.slice(2)));
}
