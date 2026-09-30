import assert from 'node:assert/strict';
import path from 'node:path';
import test from 'node:test';
import { verifyCitationIdentityMessages } from './bulk-fault-scenario.mjs';

const a = { modelPath: path.resolve('private-a/model.onnx'),
  sha256: 'a'.repeat(64), tokenizerSha256: 'b'.repeat(64) };
const b = { ...a, modelPath: path.resolve('private-b/model.onnx') };
const expected = [a, b, a, b];
const requireThat = (condition, message) => assert.ok(condition, message);
const record = identity => JSON.stringify({ message:
  `Citation scorer settings selected: model=${identity.modelPath}, sha256=${identity.sha256}, tokenizerSha256=${identity.tokenizerSha256}` });
const log = identities => identities.map(record).join('\n');

test('actual settings-selected owners certify exact A B A B with full model and tokenizer hashes', () => {
  assert.deepEqual(verifyCitationIdentityMessages(log(expected), expected, requireThat),
    { verified: true });
});

test('missing current-run composition remains incomplete', () => {
  assert.equal(verifyCitationIdentityMessages(log([a, b, a]), expected, requireThat), null);
  assert.equal(verifyCitationIdentityMessages('', expected, requireThat), null);
});

test('extra owner composition is rejected rather than compressed', () => {
  assert.throws(() => verifyCitationIdentityMessages(log([a, a, b, a, b]), expected, requireThat),
    /differs from exact/);
});

test('wrong owner order, path, model hash, and tokenizer hash are each rejected', () => {
  for (const identities of [[a, a, b, b],
    [a, { ...b, modelPath: a.modelPath }, a, b],
    [a, { ...b, sha256: 'c'.repeat(64) }, a, b],
    [a, { ...b, tokenizerSha256: 'c'.repeat(64) }, a, b]]) {
    assert.throws(() => verifyCitationIdentityMessages(log(identities), expected, requireThat),
      /differs from exact/);
  }
});

test('truncated hashes, relative paths, and malformed JSON cannot certify owner identity', () => {
  for (const contents of [log([{ ...a, sha256: 'a'.repeat(16) + '...' }, b, a, b]),
    log([{ ...a, tokenizerSha256: 'b'.repeat(16) + '...' }, b, a, b]),
    log([{ ...a, modelPath: 'relative/model.onnx' }, b, a, b]),
    'not-json Citation scorer settings selected: model=x']) {
    assert.throws(() => verifyCitationIdentityMessages(contents, expected, requireThat),
      /not an exact|not valid Engine log JSON/);
  }
});

test('legacy truncated consumer records remain rejected beside valid current identities', () => {
  const legacy = JSON.stringify({ message: 'Citation scorer wired: model=x sha256=' +
    'a'.repeat(16) + '...' });
  assert.throws(() => verifyCitationIdentityMessages(legacy + '\n' + log(expected), expected,
    requireThat), /legacy truncated/);
});
