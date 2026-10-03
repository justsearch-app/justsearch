// SPDX-License-Identifier: Apache-2.0
/**
 * inferencePoll — shared `/api/inference/status` polling primitive
 * (slice 460). Multiple Lit consumers (Health surface, IndexingOverlay
 * host, StatusDeck) subscribe to a single shared poller; the poller
 * makes one fetch per interval and fans out to all subscribers.
 *
 * Lifecycle: first subscriber starts the poller; last unsubscriber
 * stops it. Initial fetch is eager (no wait for first interval tick).
 */

import { authorizedFetch } from '../api/authorizedFetch.js';

export interface InferenceSnapshot {
  // Tempdoc 663 Stage 2 — matches the backend's Mode enum (app-api Mode.java) 1:1. Previously an
  // untyped `string`, which let 'transitioning' (a real backend value) escape the FE's type system;
  // `aiStateStore.computeRuntime()` silently collapsed it to 'unknown'.
  mode?: 'online' | 'indexing' | 'transitioning' | 'offline';
  available?: boolean;
  starting?: boolean;
  embeddingQueueSize?: number;
  vduQueueSize?: number;
  llmContextTokens?: number | null;
  configuredContextTokens?: number | null;
  /**
   * Tempdoc 883 decision 1 — the DERIVED window this installation's engine was launched with, and
   * why (`reason`: `top-rung` | `override` | `stepped-from:<planned rung>`). Absent when this
   * process launched no server. INTENT; `llmContextTokens` above is the OBSERVATION and stays
   * authoritative (ADR-0047). Shape mirrors the generated
   * `api/generated/schema-types/inference-status-response.ts` `contextWindow` block.
   */
  contextWindow?: {
    rung?: number;
    reason?: string | null;
    slots?: number;
    kvType?: string | null;
    freeVramBytes?: number | null;
  } | null;
  tier?: string | null;
  activeModelId?: string | null;
  // Tempdoc 586 §3 dedup — these are already on the /api/inference/status wire
  // (Tempdoc 518 Appendix F W3.3 / W3.1); `fetchOnce` casts the raw JSON, so
  // declaring them here just exposes already-present values to subscribers
  // (e.g. BrainSurface, which previously ran its own second poll for them).
  /** Monotonic generation counter, increments per transition. */
  generation?: number;
  /** Ms duration of the most recent successful startup; -1 if none. */
  lastStartupDurationMs?: number;
  gpu?: {
    cudaAvailable?: boolean;
    totalVramBytes?: number | null;
    vramDescription?: string;
  } | null;
}

type Listener = (snapshot: InferenceSnapshot | null) => void;

const listeners = new Set<Listener>();
let timer: number | null = null;
let controller: AbortController | null = null;
let inFlight: Promise<void> | null = null;
let lastSnapshot: InferenceSnapshot | null = null;
let apiBase = '';

const INTERVAL_MS = 5000;

function fetchOnce(): Promise<void> {
  if (!controller) return Promise.resolve();
  if (inFlight) return inFlight;
  const current = controller;
  inFlight = readSnapshot(current).finally(() => {
    if (controller === current) inFlight = null;
  });
  return inFlight;
}

async function readSnapshot(current: AbortController): Promise<void> {
  try {
    const res = await authorizedFetch((apiBase || '') + '/api/inference/status', { signal: current.signal });
    if (controller !== current) return;
    if (!res.ok) {
      publish(current, null);
      return;
    }
    const data = (await res.json()) as InferenceSnapshot;
    if (controller !== current) return;
    lastSnapshot = data;
    publish(current, data);
  } catch {
    publish(current, null);
  }
}

function publish(current: AbortController, snapshot: InferenceSnapshot | null): void {
  for (const listener of listeners) {
    if (controller !== current) return;
    listener(snapshot);
  }
}

function ensureRunning(): void {
  if (controller) return;
  controller = new AbortController();
  void fetchOnce();
  timer = window.setInterval(() => void fetchOnce(), INTERVAL_MS);
}

function stop(): void {
  const obsolete = controller;
  controller = null;
  inFlight = null;
  obsolete?.abort();
  if (timer !== null) {
    window.clearInterval(timer);
    timer = null;
  }
  lastSnapshot = null;
}

/**
 * Subscribe to inference status. Returns an unsubscribe function.
 * The first call after `setApiBase()` starts the poller; the last
 * unsubscribe stops it.
 */
export function subscribeInference(listener: Listener): () => void {
  listeners.add(listener);
  if (lastSnapshot !== null) listener(lastSnapshot);
  ensureRunning();
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) stop();
  };
}

/** Set the API base for the shared poller. Idempotent. */
export function setInferenceApiBase(base: string): void {
  if (apiBase !== base) {
    apiBase = base;
    // If the poller is running, restart so it picks up the new base
    // (rare; mostly a one-time startup call from chrome boot).
    if (controller) {
      stop();
      ensureRunning();
    }
  }
}

/** Test-only reset. */
export function __resetInferencePollForTest(): void {
  stop();
  listeners.clear();
  apiBase = '';
}
