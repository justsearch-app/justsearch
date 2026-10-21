// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 3a.1.9 §A.6a — schema fetcher.
 *
 * `Resource.schema` declares a documentation URL of the form
 * `https://ssot.justsearch/v1/schemas/<name>.v1.json`. This module
 * rewrites the URL to the same-origin endpoint
 * `/api/schemas/<name>.v1.json` (slice 3a.1.9 §A.6a) and fetches
 * it with ETag-aware caching.
 *
 * Cache: in-memory Map keyed on resolved schema URL. Schemas are
 * stable per-build; per-session caching is sufficient. No
 * localStorage persistence (smaller value than catalog
 * persistence; schemas reload at app boot).
 *
 * Returns a parsed JSON-Schema object on success, `null` on any
 * failure path (network error, 404, malformed JSON). Consumers
 * fall back to schema-blind rendering when null.
 */

import { authorizedFetch } from '../../shell-v0/api/authorizedFetch.js';
import type { JsonSchema } from '@jsonforms/core';

const CANONICAL_PREFIX = 'https://ssot.justsearch/v1/schemas/';

const cache: Map<string, JsonSchema> = new Map();
const inflight: Map<string, Promise<JsonSchema | null>> = new Map();

/**
 * Rewrite a canonical Resource.schema URL to the same-origin
 * `/api/schemas/<name>` path. Inputs:
 *  - `https://ssot.justsearch/v1/schemas/<name>.v1.json` →
 *    `/api/schemas/<name>.v1.json` (the canonical case).
 *  - `/api/schemas/<name>.v1.json` (already same-origin) →
 *    returned unchanged.
 *  - Anything else → returned unchanged (caller decides whether
 *    to fetch).
 */
export function rewriteSchemaUrl(schemaUrl: string): string {
  if (schemaUrl.startsWith(CANONICAL_PREFIX)) {
    const name = schemaUrl.substring(CANONICAL_PREFIX.length);
    return `/api/schemas/${name}`;
  }
  return schemaUrl;
}

/**
 * Fetches a schema by Resource.schema URL. Memoizes per-session.
 * Concurrent calls for the same URL share one inflight promise.
 *
 * @param schemaUrl from `Resource.schema`. Canonical URLs are
 *   rewritten to same-origin via {@link rewriteSchemaUrl}.
 * @returns the parsed JSON Schema, or `null` on any failure.
 */
export async function fetchSchema(
  schemaUrl: string,
  fetchImpl?: typeof fetch,
): Promise<JsonSchema | null> {
  const url = rewriteSchemaUrl(schemaUrl);
  const cached = cache.get(url);
  if (cached) return cached;
  const pending = inflight.get(url);
  if (pending) return pending;

  const promise = (async () => {
    try {
      // External schema documents do not receive the Engine credential.
      const fetcher = fetchImpl ?? (url.startsWith('/api/schemas/') ? authorizedFetch : fetch);
      const response = await fetcher(url);
      if (!response.ok) {
        return null;
      }
      const body = (await response.json()) as JsonSchema;
      cache.set(url, body);
      return body;
    } catch (err) {
      console.debug(`[schemaFetcher] fetch ${url} failed`, err);
      return null;
    } finally {
      inflight.delete(url);
    }
  })();
  inflight.set(url, promise);
  return promise;
}

/** Test-only: reset caches. */
export function __resetForTest(): void {
  cache.clear();
  inflight.clear();
}

/** Test-only: pre-populate the cache. */
export function __seedForTest(schemaUrl: string, schema: JsonSchema): void {
  cache.set(rewriteSchemaUrl(schemaUrl), schema);
}
