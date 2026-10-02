/* SPDX-License-Identifier: Apache-2.0 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { readinessUnavailableSchema } from './generate-readiness-schema.mjs';

const read = path => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'));
const readiness = read('../../SSOT/schemas/runtime-ready-response.v1.json');
const apiError = read('../../SSOT/schemas/api-error-response.v1.json');

test('generated readiness union embeds canonical branches without their external identities', () => {
  const union = readinessUnavailableSchema(readiness, apiError);
  for (const [name, original] of Object.entries({ ReadinessResponse: readiness, ApiErrorResponse: apiError })) {
    const expected = { ...original };
    delete expected.$schema;
    delete expected.$id;
    assert.deepEqual(union.$defs[name], expected);
    assert.equal(union.$defs[name].$id, undefined);
  }
  assert.ok(readiness.$id, 'generation must not mutate the source');
  assert.ok(apiError.$id, 'generation must not mutate the source');
});

test('actual served readiness union has resolvable internal branches from the canonical schemas', () => {
  const served = read('../../modules/ui/src/main/resources/SSOT/schemas/runtime-ready-unavailable-response.v1.json');
  assert.deepEqual(served.oneOf, [
    { $ref: '#/$defs/ReadinessResponse' }, { $ref: '#/$defs/ApiErrorResponse' },
  ], 'served bytes must not resolve dependencies under the absolute canonical $id');
  assert.deepEqual(served, readinessUnavailableSchema(readiness, apiError));
});
