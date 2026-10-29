import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import test from 'node:test';

const yaml = createRequire(import.meta.url)('js-yaml');
const read = path => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8');
const producer = yaml.load(read('.github/workflows/build-linux-gpu-packs.yml'));

test('S13: Linux GPU packs have a manual, read-only Ubuntu producer and an advisory signal', () => {
  assert.deepEqual(Object.keys(producer.on), ['workflow_dispatch']);
  assert.deepEqual(producer.permissions, { contents: 'read' });
  assert.equal(producer.jobs.build['runs-on'], 'ubuntu-24.04');
  assert.equal(producer.jobs.build.environment, 'release-signing');
  assert.match(producer.jobs.build.if, /github.ref == 'refs\/heads\/main'/);
  assert.equal(producer.concurrency['cancel-in-progress'], false);
  const policy = JSON.parse(read('scripts/ci/workflow-signal-policy.v1.json')).workflows
    .find(w => w.path === '.github/workflows/build-linux-gpu-packs.yml');
  assert.equal(policy.advisory, true);
  assert.equal(policy.blocking, false);
});

test('S13: draft creation cannot promote or overwrite assets and the cross-repo token stays scoped', () => {
  const steps = producer.jobs.build.steps;
  const uploads = steps.filter(s => /gh release/.test(s.run ?? ''));
  assert.equal(uploads.length, 1);
  const upload = uploads[0];
  assert.match(upload.run, /gh release create/);
  assert.match(upload.run, /--repo eliasjustus\/justsearch-releases --draft --latest=false/);
  assert.doesNotMatch(upload.run, /--clobber|gh release (edit|upload|delete)|--draft=false/);
  assert.equal(upload.env.GH_TOKEN, '${{ secrets.JUSTSEARCH_RELEASES_PACK_TOKEN }}');
  assert.doesNotMatch(JSON.stringify(producer.env ?? {}), /secrets/);
  assert.doesNotMatch(JSON.stringify(producer.jobs.build.env ?? {}), /secrets/);
  assert.equal(steps.find(s => s.uses?.startsWith('actions/checkout')).with['persist-credentials'], false);
  for (const step of steps) {
    if (/secrets\./.test(JSON.stringify(step))) {
      assert.match(step.name, /credential|draft/);
    }
    assert.doesNotMatch(step.run ?? '', /\$\{\{\s*inputs\./, 'dispatch inputs must enter the shell through env');
  }
});

test('S13: candidate payloads leave only through the private draft', () => {
  for (const step of producer.jobs.build.steps) {
    if (step.uses?.startsWith('actions/upload-artifact')) {
      assert.equal(step.with.path, '${{ runner.temp }}/linux-gpu/build-io.txt');
      assert.doesNotMatch(step.with.path, /dist|zip|registry|verified-inputs/);
    }
  }
});
