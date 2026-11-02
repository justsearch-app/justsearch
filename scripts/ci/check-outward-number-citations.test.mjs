/**
 * Tests for the outward-number citation lint (tempdoc 683): benchmark-shaped numbers in outward
 * files must be citable to the canonical release (release_id in-file, or value present in the
 * release) — fixture-driven, no repo I/O.
 *
 * Run: `node scripts/ci/check-outward-number-citations.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  collectReleaseNumbers,
  matchesReleaseValue,
  findUncitedNumbers,
  listOutwardFiles,
} from './check-outward-number-citations.mjs';

let passed = 0;
const failures = [];
const ok = (label, fn) => {
  try {
    assert.ok(fn(), label);
    passed += 1;
  } catch (e) {
    failures.push(e.message || String(e));
  }
};

const RELEASE = {
  release_id: 'rel-fixture-2026-01-01',
  measured: {
    'beir/scifact': { metrics: { 'nDCG@10': 0.755, 'P@1': 0.62 }, run_metrics: { primary_docs_s: 41.2 } },
  },
  ablations: {
    'mixed/courtlistener-200': [{ metrics: { 'nDCG@10': 0.612 } }],
  },
  external_baselines: {
    'beir/scifact': [{ model: 'BM25', value: 0.665, source_url: 'https://example.test' }],
  },
};
const NUMBERS = collectReleaseNumbers(RELEASE);
const CTX = { releaseId: RELEASE.release_id, releaseNumbers: NUMBERS };

// --- collectReleaseNumbers ---
ok('collects measured metrics + run_metrics + ablations + external baselines', () => {
  return [0.755, 0.62, 41.2, 0.612, 0.665].every((v) => NUMBERS.includes(v));
});

// --- matchesReleaseValue ---
ok('exact decimal matches', () => matchesReleaseValue('0.755', NUMBERS));
ok('2dp rounding of a 3dp release value matches', () => matchesReleaseValue('0.76', NUMBERS));
ok('non-release decimal does not match', () => !matchesReleaseValue('0.999', NUMBERS));
ok('percentage form of a release value matches (75.5%)', () =>
  matchesReleaseValue('75.5', NUMBERS, { percent: true }));
ok('whole-percent rounding matches (76%)', () => matchesReleaseValue('76', NUMBERS, { percent: true }));
ok('non-release percentage does not match', () => !matchesReleaseValue('99', NUMBERS, { percent: true }));

// --- findUncitedNumbers: trip paths ---
ok('trips an nDCG decimal that is in no release value', () => {
  const f = findUncitedNumbers('We hit nDCG@10 = 0.999 on our suite.', CTX);
  return f.length === 1 && f[0].kind === 'nDCG decimal' && f[0].line === 1;
});
ok('trips a corpus-adjacent percentage not in the release', () => {
  const f = findUncitedNumbers('JustSearch beats BM25 on SciFact by 42% end to end.', CTX);
  return f.length === 1 && f[0].kind === 'corpus-adjacent percentage';
});

// --- findUncitedNumbers: pass paths (the deliberately tight scope) ---
ok('release_id anywhere in the file covers every number', () => {
  const text = 'Benchmarks (release rel-fixture-2026-01-01):\nnDCG@10 = 0.999 and SciFact +42%.';
  return findUncitedNumbers(text, CTX).length === 0;
});
ok('an nDCG decimal equal to a release value passes without citation', () => {
  return findUncitedNumbers('nDCG@10: 0.755 on SciFact.', CTX).length === 0;
});
ok('a rounded release value passes (0.76)', () => {
  return findUncitedNumbers('nDCG@10 of 0.76.', CTX).length === 0;
});
ok('a corpus-adjacent percentage equal to a release value passes (76%)', () => {
  return findUncitedNumbers('76% nDCG@10 on SciFact.', CTX).length === 0;
});
ok('a percentage with NO corpus token on the line is out of scope', () => {
  return findUncitedNumbers('Setup takes 15% less time than before.', CTX).length === 0;
});
ok('a bare decimal without an nDCG prefix is out of scope', () => {
  return findUncitedNumbers('| SciFact | 0.999 | great |', CTX).length === 0;
});
ok('code fences are skipped', () => {
  return findUncitedNumbers('```\nnDCG@10 = 0.999\n```\nprose', CTX).length === 0;
});
ok('4-digit numbers before % are out of scope (2-3 digit rule)', () => {
  return findUncitedNumbers('SciFact grew 1200% in size.', CTX).length === 0;
});
ok('years/plain integers near corpus tokens do not trip (need a % sign)', () => {
  return findUncitedNumbers('SciFact (2020) has 5183 docs.', CTX).length === 0;
});

ok('root RESEARCH.md is part of the outward surface', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'outward-files-'));
  try {
    fs.writeFileSync(path.join(root, 'README.md'), '# readme');
    fs.writeFileSync(path.join(root, 'RESEARCH.md'), '# research');
    const names = listOutwardFiles(root).map((file) => path.basename(file)).sort();
    return JSON.stringify(names) === JSON.stringify(['README.md', 'RESEARCH.md']);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

// --- superseded evidence (review 2026-11-02): the archived Windows release vouches for nothing ---
const HERE = path.dirname(fileURLToPath(import.meta.url));
const SUPERSEDED = JSON.parse(
  fs.readFileSync(path.join(HERE, '..', 'jseval', 'releases', '832-rebaseline-2026-08-14.v1.json'), 'utf8'),
);
ok('fixture: the archived 832 release is marked superseded', () => SUPERSEDED.status === 'superseded');
ok('collectReleaseNumbers rejects a superseded release before collecting numbers', () => {
  try {
    collectReleaseNumbers(SUPERSEDED);
    return false;
  } catch (e) {
    return /superseded/.test(e.message);
  }
});
ok('the CLI fails when release.v1.json is superseded, even for its own MIRACL-de 0.857', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'outward-superseded-'));
  try {
    fs.writeFileSync(path.join(root, 'settings.gradle.kts'), '');
    fs.mkdirSync(path.join(root, 'scripts', 'jseval'), { recursive: true });
    fs.writeFileSync(path.join(root, 'scripts', 'jseval', 'release.v1.json'), JSON.stringify(SUPERSEDED));
    fs.writeFileSync(path.join(root, 'README.md'), 'MIRACL-de hybrid nDCG@10 = 0.857.\n');
    const result = spawnSync(process.execPath, [path.join(HERE, 'check-outward-number-citations.mjs')], {
      cwd: root,
      encoding: 'utf8',
    });
    return result.status !== 0 && /superseded/.test(result.stderr);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

// --- summary ---
if (failures.length) {
  console.error(`check-outward-number-citations.test: FAIL (${failures.length})`);
  for (const f of failures) console.error('  - ' + f);
  process.exit(1);
}
console.log(`check-outward-number-citations.test: OK (${passed} assertions)`);
