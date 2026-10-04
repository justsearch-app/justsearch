/* SPDX-License-Identifier: Apache-2.0 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { readinessUnavailableSchema } from '../../../contracts/runtime/generate-readiness-schema.mjs';

const read = path => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'));
const sdk = read('../openapi/runtime-client.openapi.json');
const manifest = read('../../../modules/ui-web/src/api/generated/route-manifest.snapshot.json');
const inventory = read('../../../modules/ui-web/src/api/generated/reference-client-openapi.snapshot.json');
const componentRef = name => '#/components/schemas/' + name.replace(/\.v\d+\.json$/, '');

test('public admission status mappings agree across committed projections', () => {
  for (const path of [
    '/api/runtime/manifest', '/.well-known/justsearch/manifest.json',
    '/api/runtime/ready', '/api/runtime/live', '/api/status',
  ]) {
    const route = manifest.routes.find(route => route.method === 'GET' && route.path === path);
    for (const [status, schema] of Object.entries({
      429: 'api-error-response.v1.json',
      503: path === '/api/runtime/ready'
        ? 'runtime-ready-unavailable-response.v1.json' : 'api-error-response.v1.json',
    })) {
      assert.equal(route.responseSchemas[status], schema, `${path} ${status} manifest`);
      for (const document of [sdk, inventory]) {
        assert.equal(document.paths[path].get.responses[status]
          .content['application/json'].schema.$ref, componentRef(schema), `${path} ${status}`);
      }
    }
  }
  assert.equal(sdk.paths['/api/health'].get.responses['429'], undefined);
  assert.equal(sdk.paths['/api/health'].get.responses['503']
    .content['application/json'].schema.$ref, '#/components/schemas/lifecycle-snapshot');
  assert.equal(manifest.routeDigest, inventory['x-justsearch-route-source'].routeDigest);
});

test('readiness 503 bundles both canonical bodies while 200 remains a readiness body', () => {
  const name = 'runtime-ready-unavailable-response.v1.json';
  const canonical = read('../../../SSOT/schemas/' + name);
  const served = read('../../../modules/ui/src/main/resources/SSOT/schemas/' + name);
  assert.deepEqual(served, canonical);
  assert.deepEqual(canonical.oneOf, [
    { $ref: '#/$defs/ReadinessResponse' }, { $ref: '#/$defs/ApiErrorResponse' },
  ]);
  assert.deepEqual(sdk.components.schemas['runtime-ready-unavailable-response'].oneOf,
    canonical.oneOf.map(branch => ({
      $ref: '#/components/schemas/runtime-ready-unavailable-response' + branch.$ref.slice(1),
    })));
  for (const [definition, filename] of Object.entries({
    ReadinessResponse: 'runtime-ready-response.v1.json',
    ApiErrorResponse: 'api-error-response.v1.json',
  })) {
    const source = read('../../../SSOT/schemas/' + filename);
    delete source.$schema;
    delete source.$id;
    assert.deepEqual(canonical.$defs[definition], source);
    assert.deepEqual(sdk.components.schemas['runtime-ready-unavailable-response'].$defs[definition], source);
    assert.deepEqual(sdk.components.schemas[filename.replace(/\.v\d+\.json$/, '')], source);
  }
  assert.equal(sdk.paths['/api/runtime/ready'].get.responses['200']
    .content['application/json'].schema.$ref, '#/components/schemas/runtime-ready-response');
});

test('served readiness union has only resolvable internal references', () => {
  const served = read('../../../modules/ui/src/main/resources/SSOT/schemas/runtime-ready-unavailable-response.v1.json');
  const readiness = read('../../../SSOT/schemas/runtime-ready-response.v1.json');
  const apiError = read('../../../SSOT/schemas/api-error-response.v1.json');
  assert.deepEqual(served, readinessUnavailableSchema(readiness, apiError));
  const visit = (value, nested = false) => {
    if (!value || typeof value !== 'object') return;
    if (value.$ref) {
      assert.ok(value.$ref.startsWith('#/'), 'external schema dependency: ' + value.$ref);
      let target = served;
      for (const segment of value.$ref.slice(2).split('/')) target = target?.[segment];
      assert.ok(target, 'unresolved internal schema dependency: ' + value.$ref);
    }
    if (nested) assert.equal(value.$id, undefined, 'embedded branches must retain root scope');
    for (const child of Object.values(value)) visit(child, true);
  };
  visit(served);
});
