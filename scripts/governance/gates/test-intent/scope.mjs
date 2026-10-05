/**
 * test-intent scope — tempdoc 966 D1, first increment (file-level, conservative).
 *
 * Decides, per repo-relative path, whether a changed file carries expected outcomes of the PRODUCT
 * test suite. Three kinds are flagged:
 *
 *   test-code         product test code under modules/: every Java test source set (anything under
 *                     src/ that is not src/main in a JVM module), modules/test-support/src/**, the
 *                     app-api TCK (modules/app-api-tck/src/**), frontend *.test.* / *.spec.* files
 *                     (the *-lockdown.test.* files the default vitest run excludes included),
 *                     src/mocks/**, src/__test-setup__/**, and Rust test files under
 *                     modules/shell/src-tauri (tests/**, and files a #[cfg(test)] mod declaration
 *                     loads). Inline #[cfg(test)] items in a Rust production file are handled by
 *                     rust-tests.mjs, not here.
 *   test-data         test-owned data: __fixtures__/** and __snapshots__/** under modules/ (test
 *                     resources, golden and truth files inside a Java test source set are already
 *                     test-code by location and keep that kind).
 *   watched-baseline  the baselines that let an expectation leave CI without touching a test.
 *
 * NOT flagged, deliberately (D1): production code, contract documents and the governance registers
 * that tests read by path — they are sources or are governed by their own gates. Script and gate
 * self-tests under scripts/ and the jseval suite are governance tooling, not product, and are out of
 * scope; the gate's output says so.
 */

export const OUT_OF_SCOPE_STATEMENT =
  'Out of scope by design (tempdoc 966 D1): script and gate self-tests under scripts/ and the jseval ' +
  'suite (governance tooling, not product tests).';

/** Watched baselines whose whole content is the flagged item. */
export const WATCHED_BASELINE_FILES = Object.freeze({
  'scripts/ci/suppression-ratchet-baseline.v1.json': 'suppression-ratchet baseline',
  'gates/test-efficacy/strength-baseline.v1.json': 'test-efficacy strength baseline',
  'gates/dead-code/baseline.txt': 'Knip dead-code baseline (frontend)',
  'scripts/ci/test-evidence-policy.v1.json': 'test-evidence policy',
  'scripts/ci/stress-suite-policy.v1.json': 'stress-suite policy',
});

/** The logic-seam register is watched only in its `law` / `targetTests` fields. */
export const LOGIC_SEAMS_PATH = 'governance/logic-seams.v1.json';
export const LOGIC_SEAMS_ITEM = `${LOGIC_SEAMS_PATH}#law-targetTests`;

/**
 * Items that an existing test-efficacy changeset can account for (D1 "Only a test-efficacy changeset
 * is a record with a source"). Every other watched baseline needs a full entry.
 */
export const TEST_EFFICACY_REFERABLE_ITEMS = Object.freeze(new Set([
  'gates/test-efficacy/strength-baseline.v1.json',
  LOGIC_SEAMS_ITEM,
]));

export const RUST_CRATE_ROOT = 'modules/shell/src-tauri';

/** Frontend modules whose src/ is not a Gradle source-set layout. */
const NON_JVM_MODULES = new Set(['ui-web', 'shell']);

const FRONTEND_TEST_FILE = /\.(test|spec)\.(js|jsx|ts|tsx|mjs|cjs|mts|cts)$/;

/**
 * @param {string} rel repo-relative path with forward slashes
 * @returns {{kind: 'test-code'|'test-data'|'watched-baseline', reason: string} | null}
 */
export function classifyPath(rel) {
  const p = rel.replaceAll('\\', '/');

  if (p in WATCHED_BASELINE_FILES) {
    return { kind: 'watched-baseline', reason: WATCHED_BASELINE_FILES[p] };
  }
  const archunit = /^modules\/[^/]+\/archunit_store\//.test(p);
  if (archunit) return { kind: 'watched-baseline', reason: 'ArchUnit freeze store' };

  if (!p.startsWith('modules/')) return null;
  const parts = p.split('/');
  const module = parts[1];

  // Test-owned data anywhere under modules/.
  if (parts.includes('__fixtures__')) return { kind: 'test-data', reason: '__fixtures__ data' };
  if (parts.includes('__snapshots__')) return { kind: 'test-data', reason: 'snapshot (golden) file' };

  if (module === 'test-support' && parts[2] === 'src') {
    return { kind: 'test-code', reason: 'modules/test-support (test infrastructure under src/main)' };
  }
  if (module === 'app-api-tck' && parts[2] === 'src') {
    return { kind: 'test-code', reason: 'app-api TCK' };
  }

  // JVM test source sets: modules/<m>/src/<set>/... with <set> != main.
  if (!NON_JVM_MODULES.has(module) && parts[2] === 'src' && parts.length > 4) {
    const set = parts[3];
    if (set !== 'main') {
      return { kind: 'test-code', reason: `JVM test source set '${set}'` };
    }
  }

  // Frontend.
  const base = parts[parts.length - 1];
  if (FRONTEND_TEST_FILE.test(base)) {
    return {
      kind: 'test-code',
      reason: /-lockdown\.test\./.test(base) ? 'frontend lockdown test' : 'frontend test file',
    };
  }
  if (parts[2] === 'src' && parts[3] === 'mocks' && parts.length > 4) {
    return { kind: 'test-code', reason: 'frontend mocks (src/mocks)' };
  }
  if (parts[2] === 'src' && parts[3] === '__test-setup__' && parts.length > 4) {
    return { kind: 'test-code', reason: 'frontend test setup (src/__test-setup__)' };
  }

  // Rust integration tests (whole-file); test-module files are resolved by rust-tests.mjs.
  if (p.startsWith(`${RUST_CRATE_ROOT}/tests/`) && p.endsWith('.rs')) {
    return { kind: 'test-code', reason: 'Rust integration test' };
  }
  return null;
}

/** True for a Rust source of the shell crate that may carry inline #[cfg(test)] items. */
export function isRustSource(rel) {
  return rel.startsWith(`${RUST_CRATE_ROOT}/`) && rel.endsWith('.rs') && !rel.startsWith(`${RUST_CRATE_ROOT}/target/`);
}

/** Paths that look like tests but are out of scope by design (reported, never flagged). */
export function isOutOfScopeTestLike(rel) {
  const p = rel.replaceAll('\\', '/');
  if (!p.startsWith('scripts/')) return false;
  return /\.test\.(mjs|js|cjs|ts)$/.test(p) || /(^|\/)test_[^/]+\.py$/.test(p) || /(^|\/)_fixtures\//.test(p)
    || p.startsWith('scripts/jseval/');
}
