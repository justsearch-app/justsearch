/**
 * Tempdoc 583 §D.3d — unit tests for the typed FE API-client codegen.
 *
 * `renderClient(manifest)` is a pure projection (manifest object → TS source), so the generation law
 * is tested with a fixture, no live backend and no filesystem.
 *
 * Run with: `node scripts/codegen/gen-api-client.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  canonicalizeJsonMaps,
  captureFromLive,
  renderClient,
  validateLiveHealth,
  validateLivePair,
} from './gen-api-client.mjs';

let passed = 0;
const failures = [];

function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

const FIXTURE = {
  schemaVersion: '1.0',
  count: 3,
  routeDigest: `sha256:${'a'.repeat(64)}`,
  routes: [
    { method: 'POST', path: '/api/knowledge/search', cohort: 'knowledge', requiredCapabilities: ['WORKER'] },
    { method: 'GET', path: '/api/status', cohort: 'observability', requiredCapabilities: [] },
    { method: 'POST', path: '/api/chat/agent', cohort: 'agent', requiredCapabilities: ['WORKER', 'INFERENCE'] },
  ],
};

run('emits a typed route table keyed by "<METHOD> <path>" with capabilities', () => {
  const ts = renderClient(FIXTURE);
  assert.match(ts, /export const API_ROUTES = \{/);
  assert.match(ts, /"GET \/api\/status": \{ method: "GET", path: "\/api\/status", cohort: "observability", requiredCapabilities: \[\] \}/);
  assert.match(ts, /"POST \/api\/chat\/agent":.*requiredCapabilities: \["WORKER", "INFERENCE"\]/);
  assert.match(ts, /export type ApiRouteKey = keyof typeof API_ROUTES/);
  assert.match(ts, /export function apiPath\(key: ApiRouteKey\): string/);
  assert.match(ts, /satisfies Record<string, ApiRoute>/);
});

run('routes are sorted by key (deterministic output)', () => {
  const ts = renderClient(FIXTURE);
  const iAgent = ts.indexOf('"POST /api/chat/agent"');
  const iSearch = ts.indexOf('"POST /api/knowledge/search"');
  const iStatus = ts.indexOf('"GET /api/status"');
  assert.ok(iStatus < iAgent && iAgent < iSearch, 'keys appear in sorted order');
});

run('a duplicate method+path is de-duplicated, not emitted twice', () => {
  const dup = { schemaVersion: '1.0', routes: [...FIXTURE.routes, FIXTURE.routes[1]] };
  const ts = renderClient(dup);
  const occurrences = ts.split('"GET /api/status":').length - 1;
  assert.equal(occurrences, 1, 'GET /api/status appears exactly once');
});

run('an empty manifest still produces a valid (empty) table', () => {
  const ts = renderClient({ schemaVersion: '1.0', routes: [] });
  assert.match(ts, /export const API_ROUTES = \{\n\n\} as const/);
  assert.match(ts, /export type ApiRouteKey = keyof typeof API_ROUTES/);
});

const DIGEST = `sha256:${'a'.repeat(64)}`;
const OPENAPI_FIXTURE = {
  openapi: '3.1.0',
  'x-justsearch-surface': {
    classification: 'reference-client-structural-inventory',
    runtimeContract: false,
    schemaScope: 'structural-partial',
  },
  'x-justsearch-route-source': { routeCount: 3, routeDigest: DIGEST },
  paths: {
    '/api/knowledge/search': { post: {} },
    '/api/status': { get: {} },
    '/api/chat/agent': { post: {} },
  },
};

run('live pair validation accepts matching classified route identities and metadata', () => {
  assert.deepEqual(validateLivePair(FIXTURE, OPENAPI_FIXTURE), {
    routeCount: 3,
    routeDigest: DIGEST,
  });
});

run('live OpenAPI capture canonicalizes nested map keys without reordering arrays', () => {
  assert.deepEqual(canonicalizeJsonMaps({ z: { b: 2, a: 1 }, a: [{ y: 2, x: 1 }] }), {
    a: [{ x: 1, y: 2 }],
    z: { a: 1, b: 2 },
  });
});

run('live pair validation normalizes Javalin wildcards without rendering OpenAPI', () => {
  const manifest = {
    count: 1,
    routeDigest: DIGEST,
    routes: [{ method: 'OPTIONS', path: '/*', cohort: 'other', requiredCapabilities: [] }],
  };
  const openApi = {
    ...OPENAPI_FIXTURE,
    'x-justsearch-route-source': { routeCount: 1, routeDigest: DIGEST },
    paths: { '/{wildcard}': { options: {} } },
  };
  assert.equal(validateLivePair(manifest, openApi).routeDigest, DIGEST);
});

run('live pair validation rejects a missing operation before snapshots are written', () => {
  assert.throws(
    () => validateLivePair(FIXTURE, { ...OPENAPI_FIXTURE, paths: {} }),
    /identities differ/,
  );
});

run('live pair validation rejects an artifact that overclaims Runtime Contract status', () => {
  const overclaim = {
    ...OPENAPI_FIXTURE,
    'x-justsearch-surface': {
      classification: 'public-contract',
      runtimeContract: true,
    },
  };
  assert.throws(() => validateLivePair(FIXTURE, overclaim), /not classified/);
});

run('live pair validation rejects a descriptor metadata digest mismatch', () => {
  const changedDescriptorView = {
    ...FIXTURE,
    routeDigest: `sha256:${'b'.repeat(64)}`,
    routes: FIXTURE.routes.map((route, index) =>
      index === 0 ? { ...route, cohort: 'changed-cohort' } : route,
    ),
  };
  assert.throws(
    () => validateLivePair(changedDescriptorView, OPENAPI_FIXTURE),
    /descriptor count or digest/,
  );
});

run('live health validation accepts only HTTP 200 READY or DEGRADED', () => {
  assert.deepEqual(validateLiveHealth(200, { lifecycle: { state: 'LIFECYCLE_STATE_READY' } }), {
    statusCode: 200,
    lifecycleState: 'READY',
  });
  assert.deepEqual(validateLiveHealth(200, { lifecycle: { state: 'DEGRADED' } }), {
    statusCode: 200,
    lifecycleState: 'DEGRADED',
  });
  assert.throws(
    () => validateLiveHealth(200, { lifecycle: { state: 'LIFECYCLE_STATE_STARTING' } }),
    /lifecycle state/,
  );
  assert.throws(
    () => validateLiveHealth(503, { lifecycle: { state: 'LIFECYCLE_STATE_READY' } }),
    /HTTP 503/,
  );
});

async function captureWritesTheValidatedPair() {
  const temporary = mkdtempSync(join(tmpdir(), 'justsearch-api-capture-'));
  const routePath = join(temporary, 'routes.json');
  const openApiPath = join(temporary, 'openapi.json');
  const server = createServer((request, response) => {
    response.setHeader('content-type', 'application/json');
    if (request.url === '/api/meta/routes') {
      response.end(JSON.stringify(FIXTURE));
    } else if (request.url === '/api/meta/openapi.json') {
      response.end(JSON.stringify(OPENAPI_FIXTURE));
    } else if (request.url === '/api/health') {
      response.end(JSON.stringify({ lifecycle: { state: 'LIFECYCLE_STATE_READY' } }));
    } else {
      response.statusCode = 404;
      response.end('{}');
    }
  });
  try {
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    assert.equal(typeof address, 'object');
    await captureFromLive(`http://127.0.0.1:${address.port}`, { routePath, openApiPath });
    const capturedRoutes = JSON.parse(readFileSync(routePath, 'utf8'));
    const capturedOpenApi = JSON.parse(readFileSync(openApiPath, 'utf8'));
    const capturedOpenApiText = readFileSync(openApiPath, 'utf8');
    assert.equal(capturedRoutes.routeDigest, DIGEST);
    assert.equal(
      capturedOpenApi['x-justsearch-surface'].classification,
      'reference-client-structural-inventory',
    );
    assert.ok(
      capturedOpenApiText.indexOf('"paths"') < capturedOpenApiText.indexOf('"x-justsearch-route-source"'),
      'captured OpenAPI maps use the same key ordering as the offline Java exporter',
    );
    passed += 1;
  } catch (error) {
    failures.push(`capture writes both validated live snapshots: ${error.message}`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
    rmSync(temporary, { recursive: true, force: true });
  }
}

await captureWritesTheValidatedPair();

async function captureRejectsBeforeWritingWhenHealthIsNotReady() {
  const temporary = mkdtempSync(join(tmpdir(), 'justsearch-api-capture-not-ready-'));
  const routePath = join(temporary, 'routes.json');
  const openApiPath = join(temporary, 'openapi.json');
  const server = createServer((request, response) => {
    response.setHeader('content-type', 'application/json');
    if (request.url === '/api/health') {
      response.end(JSON.stringify({ lifecycle: { state: 'LIFECYCLE_STATE_STARTING' } }));
    } else if (request.url === '/api/meta/routes') {
      response.end(JSON.stringify(FIXTURE));
    } else if (request.url === '/api/meta/openapi.json') {
      response.end(JSON.stringify(OPENAPI_FIXTURE));
    } else {
      response.statusCode = 404;
      response.end('{}');
    }
  });
  try {
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    assert.equal(typeof address, 'object');
    await assert.rejects(
      captureFromLive(`http://127.0.0.1:${address.port}`, { routePath, openApiPath }),
      /lifecycle state/,
    );
    assert.equal(existsSync(routePath), false, 'route snapshot must not be written');
    assert.equal(existsSync(openApiPath), false, 'OpenAPI snapshot must not be written');
    passed += 1;
  } catch (error) {
    failures.push(`capture refuses a non-ready health response: ${error.message}`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
    rmSync(temporary, { recursive: true, force: true });
  }
}

await captureRejectsBeforeWritingWhenHealthIsNotReady();

if (failures.length > 0) {
  console.error(`gen-api-client.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`gen-api-client.test: all ${passed} checks passed`);
