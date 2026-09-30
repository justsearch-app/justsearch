import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { findResidue } from './check-lane-f-residue.mjs';

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lane-f-residue-'));
const checker = fileURLToPath(new URL('./check-lane-f-residue.mjs', import.meta.url));
try {
  fs.mkdirSync(path.join(root, 'docs'));
  fs.writeFileSync(path.join(root, 'docs', 'current.md'), 'Worker process owns the index.\n', 'utf8'); // lane F fixture
  fs.writeFileSync(path.join(root, 'docs', 'history.md'), '# Historical architecture\nWorker process owns the index.\n', 'utf8'); // lane F fixture
  const result = spawnSync(process.execPath, [checker, '--root', root, '--paths', 'docs'], { encoding: 'utf8' });
  assert.equal(result.status, 1, 'the unlabelled fixture must make the checker red');
  assert.equal(result.stdout.trim(), 'docs/current.md:1: Worker process owns the index.'); // lane F fixture
  assert.ok(!result.stdout.includes('history.md'), 'the labelled fixture must not be reported');

  const check = (text, expected, file = 'docs/example.md') => assert.equal(findResidue(file, text).length, expected, text);
  check('# Historical\n## Detail\nMMF\n# Current\nMMF', 1); // lane F fixture
  check('MMF unretired\nMMF ahistorical\nMMF plane F', 3); // lane F fixture
  check('MMF Q4', 1); // lane F fixture: model names are not item ids
  check('  # Historical\nMMF', 0); // lane F fixture
  check('```text\n```not-a-close\n# Historical\n```\nMMF', 1); // lane F fixture
  check('# Historical\nMMF', 1, 'scripts/current.py'); // lane F fixture
  for (const word of ['historical', 'retired', 'superseded', 'lane F', 'A11', 'B14', 'D1-2', 'no longer', 'replaced']) check(`MMF ${word}`, 0); // lane F fixture
  check('EngineSupervisionPolicy BrainSupervisionPolicy', 0);
  check('SupervisionPolicy gRPC', 1); // lane F fixture
  check('# Historical\ngRPC\n# Current\ngRPC retired', 0); // lane F fixture
  check('MMF', 0, 'docs/tempdocs/example.md'); // lane F fixture
  check('MMF', 0, 'docs/design/lane-f-engine-jvm/example.md'); // lane F fixture
  check('MMF', 0, 'docs/decisions/0001-old.md'); // lane F fixture
  check('---\ntitle: MMF\n---\nMMF', 1, 'docs/decisions/0002-old.md'); // lane F fixture
  check('MMF', 0, 'scripts/tests/legacy-fixture.txt'); // lane F fixture
  check('MMF', 0, 'src/test/LegacyBehaviorTest.java'); // lane F fixture
  check('MMF', 1, 'docs/testing/legacy-architecture.md'); // lane F fixture
  check('MMF', 1, 'docs/legacy-contest.md'); // lane F fixture
  check('MMF', 1, 'docs/legacy-attestation.md'); // lane F fixture
  check('MMF git show abcdef1:path', 0); // lane F fixture
  check('[{\n"term": "MMF",\n"retiredNote": "old"\n}, {\n"term": "MMF"\n}]', 1, 'governance/example.json'); // lane F fixture
  check('[{"term":"MMF","retiredNote":"old"}]', 0, 'governance/example.json'); // lane F fixture
  check('[{"term":"MMF","retiredNote":"old"},{"term":"MMF"}]', 1, 'governance/example.json'); // lane F fixture
  fs.writeFileSync(path.join(root, 'docs', 'current.md'), 'Retired Worker process owns the index.\n', 'utf8');
  const clean = spawnSync(process.execPath, [checker, '--root', root, '--paths', 'docs'], { encoding: 'utf8' });
  assert.equal(clean.status, 0);
  assert.equal(clean.stdout, '');
  console.log('test-check-lane-f-residue: PASS (unlabelled fixture exits 1; labelled fixtures exit 0)');
} finally {
  fs.rmSync(root, { recursive: true, force: true });
}
