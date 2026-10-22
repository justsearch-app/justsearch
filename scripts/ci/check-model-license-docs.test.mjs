import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { injectLegalDocTable, loadModels } from '../codegen/gen-notices.mjs';

test('the legal documentation retains its registry-projected model license table', () => {
  const text = readFileSync(new URL('../../docs/reference/legal/ai-runtime-and-model-redistribution.md', import.meta.url), 'utf8');
  assert.equal(injectLegalDocTable(loadModels(), text), text);
});
