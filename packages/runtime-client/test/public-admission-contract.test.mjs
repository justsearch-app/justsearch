/* SPDX-License-Identifier: Apache-2.0 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

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
    { $ref: 'runtime-ready-response.v1.json' }, { $ref: 'api-error-response.v1.json' },
  ]);
  assert.deepEqual(sdk.components.schemas['runtime-ready-unavailable-response'].oneOf,
    canonical.oneOf.map(branch => ({ $ref: componentRef(branch.$ref) })));
  for (const filename of canonical.oneOf.map(branch => branch.$ref)) {
    const source = read('../../../SSOT/schemas/' + filename);
    delete source.$schema;
    delete source.$id;
    assert.deepEqual(sdk.components.schemas[filename.replace(/\.v\d+\.json$/, '')], source);
  }
  assert.equal(sdk.paths['/api/runtime/ready'].get.responses['200']
    .content['application/json'].schema.$ref, '#/components/schemas/runtime-ready-response');
});
