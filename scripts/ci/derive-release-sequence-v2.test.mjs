import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { deriveReleaseSequence } from './derive-release-sequence.mjs';

test('published producer v2 bytes advance the global v1 history; drafts do not', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'justsearch-v2-sequence-'));
  try {
    const script = process.env.JUSTSEARCH_TEST_FIXTURE_SCRIPT
      ?? fileURLToPath(new URL('../release/updater-v2-fixture.mjs', import.meta.url));
    execFileSync(process.execPath, [script, directory]);
    const bytes = await readFile(path.join(directory, 'release.v2.json'), 'utf8');
    const releases = [
      { tag_name: 'v0.3.0', assets: [{ name: 'release.v1.json', bytes: JSON.stringify({ sequence: 41 }) }] },
      { tag_name: 'v1.2.3', assets: [{ name: 'release.v2.json', bytes }] },
      { tag_name: 'v9.9.9', draft: true, assets: [{ name: 'release.v2.json', bytes: JSON.stringify({ sequence: 999 }) }] },
    ];
    const fetchAsset = async asset => asset.bytes;
    const result = await deriveReleaseSequence({ releases, fetchAsset });
    assert.equal(result.sequence, 43, 'published v2 sequence 42 must advance history beyond v1 sequence 41');
    await assert.rejects(deriveReleaseSequence({ releases: [{
      ...releases[1], assets: [releases[1].assets[0], releases[1].assets[0]],
    }], fetchAsset }), /Conflicting duplicate/);
    await assert.rejects(deriveReleaseSequence({ releases: [...releases, {
      tag_name: 'v1.2.4', assets: [{ name: 'release.v2.json', bytes }],
    }], fetchAsset }), /Conflicting published sequence/);
  } finally { await rm(directory, { recursive:true, force:true }); }
});
