/* SPDX-License-Identifier: Apache-2.0 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import test from 'node:test';

import {
  assertRuntimeContractCompatible,
  createRuntimeClient,
  SUPPORTED_RUNTIME_CONTRACT_VERSIONS,
} from '../dist/index.js';

const compatibleManifest = {
  runtimeContract: { version: '0.3.0' },
};

test('generated readiness operation preserves the typed lifecycle 503 response', async () => {
  const requests = [];
  const client = await createRuntimeClient({
    baseUrl: 'http://127.0.0.1:33221',
    fetch: async (input, init) => {
      requests.push({ input: input.toString(), init });
      if (input.toString().endsWith('/api/runtime/manifest')) {
        return new Response(JSON.stringify(compatibleManifest), { status: 200 });
      }
      return new Response(
        JSON.stringify({
          ready: false,
          lifecycle: 'LIFECYCLE_STATE_STARTING',
          instanceId: 'instance-1',
        }),
        { status: 503, headers: { 'content-type': 'application/json' } },
      );
    },
  });

  const response = await client.getRuntimeReadiness();

  assert.equal(response.status, 503);
  assert.equal(response.data.ready, false);
  assert.equal(response.data.lifecycle, 'LIFECYCLE_STATE_STARTING');
  assert.equal(requests[1].input, 'http://127.0.0.1:33221/api/runtime/ready');
  assert.equal(requests[1].init.method, 'GET');
  assert.ok(requests.every((request) => request.init.redirect === 'error'));
});

test('separate clients retain their injected transport across concurrent calls', async () => {
  const seen = [];
  const makeFetch = (label) => async (input) => {
    await Promise.resolve();
    seen.push(`${label}:${input}`);
    if (input.toString().endsWith('/api/runtime/manifest')) {
      return new Response(JSON.stringify(compatibleManifest), { status: 200 });
    }
    return new Response(JSON.stringify({ alive: true, pid: null, instanceId: null }), {
      status: 200,
    });
  };
  const [first, second] = await Promise.all([
    createRuntimeClient({ baseUrl: 'http://127.0.0.1:31001', fetch: makeFetch('a') }),
    createRuntimeClient({ baseUrl: 'http://localhost:31002', fetch: makeFetch('b') }),
  ]);

  await Promise.all([first.getRuntimeLiveness(), second.getRuntimeLiveness()]);

  assert.deepEqual(seen.sort(), [
    'a:http://127.0.0.1:31001/api/runtime/live',
    'a:http://127.0.0.1:31001/api/runtime/manifest',
    'b:http://localhost:31002/api/runtime/live',
    'b:http://localhost:31002/api/runtime/manifest',
  ]);
});

test('factory rejects non-loopback or credential-bearing base URLs', async () => {
  await assert.rejects(createRuntimeClient({ baseUrl: 'https://example.com' }), /loopback/);
  await assert.rejects(
    createRuntimeClient({ baseUrl: 'http://user:pass@127.0.0.1:33221' }),
    /credentials/,
  );
  await assert.rejects(
    createRuntimeClient({ baseUrl: 'http://127.0.0.1:33221?token=x' }),
    /query/,
  );
  await assert.rejects(
    createRuntimeClient({ baseUrl: 'http://127.0.0.1:33221/prefix' }),
    /path/,
  );
});

test('factory fails closed before exposing operations for an unsupported runtime contract', async () => {
  const seen = [];
  await assert.rejects(
    createRuntimeClient({
      baseUrl: 'http://127.0.0.1:33221',
      fetch: async (input, init) => {
        seen.push({ input: input.toString(), init });
        return new Response(
          JSON.stringify({ runtimeContract: { version: '9.9.9' } }),
          { status: 200 },
        );
      },
    }),
    /Unsupported JustSearch runtime contract version: 9\.9\.9/,
  );
  assert.equal(seen.length, 1);
  assert.ok(seen[0].input.endsWith('/api/runtime/manifest'));
  assert.equal(seen[0].init.redirect, 'error');
});

test('factory fails closed when the compatibility manifest is unavailable', async () => {
  await assert.rejects(
    createRuntimeClient({
      baseUrl: 'http://127.0.0.1:33221',
      fetch: async () =>
        new Response(JSON.stringify({ error: 'not ready', errorCode: 'SERVICE_UNAVAILABLE' }), {
          status: 503,
        }),
    }),
    /manifest returned HTTP 503/,
  );
});

test('built-in fetch refuses redirects before contacting their destination', async () => {
  let destinationHits = 0;
  const destination = createServer((_request, response) => {
    destinationHits += 1;
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(JSON.stringify(compatibleManifest));
  });
  const redirector = createServer((_request, response) => {
    response.writeHead(302, {
      location: `http://127.0.0.1:${destination.address().port}/api/runtime/manifest`,
    });
    response.end();
  });

  await listen(destination);
  await listen(redirector);
  try {
    await assert.rejects(
      createRuntimeClient({ baseUrl: `http://127.0.0.1:${redirector.address().port}` }),
      /fetch failed/,
    );
    assert.equal(destinationHits, 0);
  } finally {
    await close(redirector);
    await close(destination);
  }
});

test('runtime contract compatibility is explicit and fail-closed', () => {
  const openapi = JSON.parse(
    readFileSync(new URL('../openapi/runtime-client.openapi.json', import.meta.url), 'utf8'),
  );
  assert.deepEqual(
    SUPPORTED_RUNTIME_CONTRACT_VERSIONS,
    openapi['x-justsearch-runtime-contract'].supportedVersions,
  );
  assert.doesNotThrow(() =>
    assertRuntimeContractCompatible({ runtimeContract: { version: '0.3.0' } }),
  );
  assert.throws(() => assertRuntimeContractCompatible({}), /missing/);
  assert.throws(
    () => assertRuntimeContractCompatible({ runtimeContract: { version: '0.4.0' } }),
    /version: 0.4.0/,
  );
});

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
}

function close(server) {
  return new Promise((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()));
  });
}
