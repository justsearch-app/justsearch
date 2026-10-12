// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 3a.1.9 §A.7 — Path-hash resolver helper.
 *
 * Core hashed rows hydrate their display labels during mount and streamed updates through
 * the token-gated observation read. That read uses the existing scoped IndexingController
 * resolver (ADR-0028). Other declared resolver operations still use OperationClient.
 * Results are memoized for this browser session; unavailable answers remain retryable.
 */

import { OperationClient, type OperationInvocationSuccess } from '../operations/OperationClient.js';
import { getResource } from '../../api/registry/ResourceCatalogClient.js';
import { authorizedFetch } from '../api/authorizedFetch.js';

/** In-memory cache. Cleared on app reload. */
const cache: Map<string, string | null> = new Map();
const inflight: Map<string, Promise<string | null>> = new Map();

interface ResolvePathOptions {
  /** Optional API base override (defaults to window.location.origin). */
  apiBase?: string;
  /** Optional fetch override (for tests). */
  fetchImpl?: typeof fetch;
}

/**
 * Resolves a row's primary-key value (path hash) to the path string the
 * worker has on record. Returns null when:
 *  - the Resource isn't in the FE catalog,
 *  - the Resource's `privacy.resolver` is null,
 *  - the resolver Operation reports `found: false`,
 *  - the Operation invocation throws.
 *
 * Memoized per `(resourceId, primaryKey)` for the session.
 *
 * @param resourceId Resource catalog id (e.g., `core.indexing-jobs`)
 * @param primaryKey value of the row's primary-key field (e.g., the
 *   sha256 hex `pathHash`)
 */
export async function resolvePathLazy(
  resourceId: string,
  primaryKey: string,
  options: ResolvePathOptions = {},
): Promise<string | null> {
  const cacheKey = `${resourceId}::${primaryKey}`;
  const cached = cache.get(cacheKey);
  if (cached !== undefined) return cached;
  const pending = inflight.get(cacheKey);
  if (pending) return pending;

  const resource = getResource(resourceId);
  if (!resource) {
    cache.set(cacheKey, null);
    return null;
  }
  if (resource.privacy.pathPolicy !== 'HASHED_REQUIRES_RESOLVER') {
    // The caller asked for resolution on a non-hashed resource;
    // probably bug, but return null defensively.
    cache.set(cacheKey, null);
    return null;
  }
  const resolverId = resource.privacy.resolver;
  if (!resolverId) {
    cache.set(cacheKey, null);
    return null;
  }

  const apiBase =
    options.apiBase ??
    (typeof globalThis !== 'undefined' &&
    (globalThis as { location?: { origin?: string } }).location?.origin
      ? (globalThis as { location: { origin: string } }).location.origin
      : '');

  const client = new OperationClient({
    apiBase,
    fetchImpl: options.fetchImpl,
  });

  // Resolver argument key matches the primary-key field name (slice
  // 445 convention: `pathHash`). Future Resources with a different
  // primary-key name pass that name through here.
  const argName = resource.primaryKey;
  if (!argName) {
    cache.set(cacheKey, null);
    return null;
  }

  // Tempdoc 804 §B9 (round-10 F8): only a DEFINITIVE answer is cached. A failed invocation (the
  // round's 401 on the mutating operations route, or any transport hiccup) used to be memoized as a
  // permanent `null`, so every folder row rendered its hash for the rest of the session even after
  // the cause cleared — a transient failure became a sticky one. An error now leaves the cache
  // empty so the next caller retries; a resolver that answers `found: false` still caches null.
  // Core row labels hydrate during mount and streamed updates. Keep that read off the
  // deliberate operation invocation route, with the same scoped, token-gated resolver.
  const resolution = resolverId === 'core.resolve-path-hash' && argName === 'pathHash'
    ? (options.fetchImpl ?? authorizedFetch)(`${apiBase}/api/library/path-label`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ pathHash: primaryKey }),
      }).then(async (response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<Record<string, unknown>>;
      })
    : client.invoke(resolverId, { args: { [argName]: primaryKey } })
        .then((success: OperationInvocationSuccess) => success.structuredData);
  const promise: Promise<string | null> = resolution
    .then((data) => {
      const path = data && data['found'] === true ? data['path'] : null;
      const resolved = typeof path === 'string' ? path : null;
      cache.set(cacheKey, resolved); // definitive: the resolver answered
      return resolved;
    })
    .catch(() => null) // NOT cached — retryable
    .finally(() => {
      inflight.delete(cacheKey);
    });

  inflight.set(cacheKey, promise);
  return promise;
}

/**
 * "C:\a\b\report.pdf" → "b/report.pdf" — a resolved path at ROW altitude: the file plus its parent
 * folder for context, never the full absolute path. Lives beside the resolver because every caller
 * that resolves a hash for display shares this reason to change (tempdoc 812 D4 gave it its second
 * consumer: the Activity ledger's per-document rows, alongside the Tasks tray).
 */
export function friendlyPathName(path: string): string {
  const parts = path
    .replace(/\\/g, '/')
    .replace(/\/+$/, '')
    .split('/')
    .filter(Boolean);
  if (parts.length === 0) return path;
  const file = parts[parts.length - 1]!;
  const parent = parts.length >= 2 ? parts[parts.length - 2] : '';
  return parent ? `${parent}/${file}` : file;
}

/** Test-only: clear all cached resolutions. */
export function __resetForTest(): void {
  cache.clear();
  inflight.clear();
}

/** Test-only: pre-populate a resolution. */
export function __seedForTest(
  resourceId: string,
  primaryKey: string,
  path: string | null,
): void {
  cache.set(`${resourceId}::${primaryKey}`, path);
}
