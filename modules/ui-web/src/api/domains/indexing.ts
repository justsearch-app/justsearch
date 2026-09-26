// SPDX-License-Identifier: Apache-2.0
/**
 * Indexing domain API - Index management and roots
 */

import { request } from '../http';
import { parseWireContract } from '../schemas';
import { failedJobsResponseSchema } from '../generated/schema-types/failed-jobs-response.js';

// ============================================
// Types
// ============================================


// ============================================
// API Functions
// ============================================


/**
 * Adds a root folder to the index.
 */
export async function addRoot(
  baseUrl: string,
  path: string,
  collection = 'default',
  signal?: AbortSignal
): Promise<void> {
  // Watched roots (persistent indexing).
  // This is the canonical Library flow: Head stores the watched root and the Worker indexes via gRPC.
  await request(baseUrl, '/api/indexing/roots', {
    method: 'POST',
    body: { path, collection },
    signal,
  });
}

/**
 * Removes a root folder from the index.
 */
export async function removeRoot(
  baseUrl: string,
  path: string,
  collection = 'default',
  signal?: AbortSignal
): Promise<void> {
  await request(baseUrl, '/api/indexing/roots', {
    method: 'DELETE',
    body: { path, collection },
    signal,
  });
}

/**
 * Reindexes watched roots incrementally.
 * @param force If true, bypasses unchanged-file extraction checks; embedding mismatch requires a full-generation rebuild.
 */
export async function reindex(baseUrl: string, force?: boolean, signal?: AbortSignal): Promise<void> {
  const path = force ? '/api/indexing/reindex?force=true' : '/api/indexing/reindex';
  await request(baseUrl, path, {
    method: 'POST',
    signal,
  });
}

// ============================================
// Suggested Roots
// ============================================

export interface SuggestedRoot {
  label: string;
  path: string;
}

/**
 * Fetches platform-aware folder suggestions for the empty-state CTA.
 * Returns folders that exist on disk and are not already watched.
 */
export async function getSuggestedRoots(
  baseUrl: string,
  signal?: AbortSignal
): Promise<SuggestedRoot[]> {
  const data = await request<{ suggestions: SuggestedRoot[] }>(
    baseUrl,
    '/api/indexing/suggested-roots',
    { signal }
  );
  return data.suggestions ?? [];
}

// ============================================
// Excludes (explicit cleanup)
// ============================================

export interface PatternMatch {
  pattern: string;
  matches: number;
}

export interface ApplyExcludesResponse {
  status: string;
  message?: string;
  dryRun?: boolean;
  patterns: number;
  rootsProcessed: number;
  deletedByPathJobs: number;
  deletedById: number;
  matchedFiles?: number;
  perPattern?: PatternMatch[];
  capped?: boolean;
}

/**
 * Applies exclude patterns by deleting already-indexed documents matching the configured globs.
 * Explicit, user-triggered cleanup (not automatic).
 */
export async function applyExcludes(baseUrl: string, signal?: AbortSignal): Promise<ApplyExcludesResponse> {
  return request<ApplyExcludesResponse>(baseUrl, '/api/indexing/excludes/apply', {
    method: 'POST',
    signal,
  });
}

/** Dry-run: counts matched files per pattern without deleting. */
export async function previewExcludes(baseUrl: string, signal?: AbortSignal): Promise<ApplyExcludesResponse> {
  return request<ApplyExcludesResponse>(baseUrl, '/api/indexing/excludes/apply?dryRun=true', {
    method: 'POST',
    signal,
  });
}

/**
 * Starts a blue/green index migration (full rebuild).
 * The current index stays active for reads until the new generation completes.
 */
export async function startMigration(
  baseUrl: string,
  reason: string,
  signal?: AbortSignal
): Promise<void> {
  await request(baseUrl, '/api/indexing/migration/start', {
    method: 'POST',
    body: { reason },
    signal,
    retries: 1,
  });
}

// ============================================
// Failed Jobs
// ============================================

export interface FailedJob {
  path: string;
  errorMessage: string;
  attempts: number;
  lastUpdatedMs: number;
  collection: string;
}

/**
 * Lists permanently failed indexing jobs (state=FAILED).
 */
export async function listFailedJobs(
  baseUrl: string,
  limit = 100,
  signal?: AbortSignal
): Promise<FailedJob[]> {
  const raw = await request<unknown>(
    baseUrl,
    `/api/indexing/failed-jobs?limit=${limit}`,
    { signal }
  );
  // Tempdoc 564 Phase 5: validate the raw wire against the generated schema at the parse boundary
  // (non-fail-open — logs `[WireContract]` on drift), replacing the prior unchecked cast. The
  // ergonomic `FailedJob` item shape matches the generated record's; null/absent jobs → [].
  const parsed = parseWireContract(
    failedJobsResponseSchema,
    raw,
    `GET /api/indexing/failed-jobs?limit=${limit}`
  );
  return (parsed.jobs ?? []) as FailedJob[];
}

/**
 * Clears all permanently failed jobs from the queue.
 */
export async function clearFailedJobs(
  baseUrl: string,
  signal?: AbortSignal
): Promise<number> {
  const data = await request<{ deletedCount: number }>(
    baseUrl,
    '/api/indexing/failed-jobs',
    { method: 'DELETE', signal }
  );
  return data.deletedCount ?? 0;
}
