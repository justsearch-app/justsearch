// Lane F F-3: prove the content gate fails on a divergence, independent of Git/MCP.
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { SHARED_SKILL_SENTENCES } from './check-codex-agent-parity.mjs';

const sentences = Object.entries(Object.groupBy(SHARED_SKILL_SENTENCES, ([skill]) => skill))
  .map(([skill, rows]) => [skill, rows.map(([, sentence]) => sentence).join(' ')]);
const checker = join(dirname(fileURLToPath(import.meta.url)), 'check-codex-agent-parity.mjs');
const root = mkdtempSync(join(process.env.JUSTSEARCH_TEST_TMP ?? tmpdir(), 'skill-content-parity-'));
const run = () => spawnSync(process.execPath, [checker, '--skill-content-only', '--root', root], {
  encoding: 'utf8', windowsHide: true,
});
try {
  for (const tree of ['.agents', '.claude']) {
    for (const [skill, text] of sentences) {
      const dir = join(root, tree, 'skills', skill);
      mkdirSync(dir, { recursive: true });
      // Wrapping is irrelevant; the semantic sentence is the contract.
      writeFileSync(join(dir, 'SKILL.md'), text.replaceAll(' ', '\n'), 'utf8');
    }
  }
  const green = run();
  assert.equal(green.status, 0, green.stderr);
  for (const tree of ['.agents', '.claude']) {
    const file = join(root, tree, 'skills', 'jseval', 'SKILL.md');
    writeFileSync(file, 'Obsolete separate-process description.', 'utf8');
    const red = run();
    assert.equal(red.status, 1, red.stderr);
    assert.match(red.stderr, /missing shared current content/);
    assert.ok(red.stderr.includes(`${tree}/skills/jseval/SKILL.md`));
    console.log(`RED confirmed: divergence in ${tree} exits 1 with the missing sentence`);
    writeFileSync(file, sentences.find(([skill]) => skill === 'jseval')[1], 'utf8');
  }
  assert.equal(run().status, 0);
  console.log('content parity self-test: PASS (green/red/green, both directions)');
} finally {
  rmSync(root, { recursive: true, force: true });
}
