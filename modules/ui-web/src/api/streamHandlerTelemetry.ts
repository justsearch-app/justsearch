// SPDX-License-Identifier: Apache-2.0
/**
 * Stream handler-failure telemetry (tempdoc 941) — "which of OUR handlers broke, in prod".
 *
 * A per-event handler that throws inside `consumeShapeStream` is an FE defect, and the stream
 * deliberately survives it: one broken consumer must not cost the reader the rest of an answer
 * that is still arriving. That survival is what makes the failure invisible — the turn renders as
 * though the missing part was never sent (847 F-12), and the only trace was a `console.warn` in a
 * devtools panel nobody has open. A toast tells the READER something is missing; this ring is the
 * other half: it tells whoever is DIAGNOSING which handler, how often, and when.
 *
 * Sink deliberately mirrors its sibling `wireDriftTelemetry.ts` (which mirrors
 * `shell-v0/state/availabilityTelemetry.ts` and `router/resolutionTelemetry.ts`): a localStorage
 * ring, age-filtered, capped, silent on failure. No remote flush (loopback, no users) — it is
 * surfaced through the same `core.export-diagnostics` payload the wire-drift ring rides on
 * (`HealthSurface`). The two are the same shape on purpose: a new FE-defect ring should be a copy
 * of this file with a different key, not a new mechanism.
 *
 * Content is an EVENT NAME plus the error's message — both ours, neither user data. The payload
 * that caused it is deliberately not recorded: it is the model's answer text.
 */

const STORAGE_KEY = 'jf.stream-handler-telemetry';
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_ENTRIES = 200;

export interface StreamHandlerFailureEntry {
  /** The SSE event whose handler threw (`chunk`, `citations`, `done`, …). */
  event: string;
  /** The thrown error's message, truncated — the discriminator between two bugs on one event. */
  message: string;
  timestamp: number;
}

function loadEntries(): StreamHandlerFailureEntry[] {
  try {
    if (typeof localStorage === 'undefined') return [];
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const entries = JSON.parse(raw) as StreamHandlerFailureEntry[];
    if (!Array.isArray(entries)) return [];
    const cutoff = Date.now() - MAX_AGE_MS;
    return entries.filter((e) => e && typeof e.timestamp === 'number' && e.timestamp > cutoff);
  } catch {
    return [];
  }
}

function saveEntries(entries: StreamHandlerFailureEntry[]): void {
  try {
    if (typeof localStorage === 'undefined') return;
    // Cap the ring so a handler that throws on every stream can't grow the key unbounded
    // (keep the most recent).
    const trimmed =
      entries.length > MAX_ENTRIES ? entries.slice(entries.length - MAX_ENTRIES) : entries;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(trimmed));
  } catch {
    // localStorage full or unavailable — silent degradation (telemetry must never break a stream).
  }
}

/**
 * Record one handler failure. Called from `consumeShapeStream`'s catch, once per stream per event
 * name (the same dedupe the toast uses): a `chunk` handler that throws once throws on every token,
 * and 500 identical rows would evict every other diagnostic in the ring while saying nothing the
 * first row did not. Repetition across STREAMS still accumulates, which is the signal that
 * distinguishes a one-off from a systematic break.
 */
export function recordStreamHandlerFailure(event: string, error: unknown): void {
  const message =
    error instanceof Error ? error.message : typeof error === 'string' ? error : String(error);
  const entries = loadEntries();
  entries.push({ event, message: message.slice(0, 300), timestamp: Date.now() });
  saveEntries(entries);
}

/** Read the recorded failures (age-filtered). For diagnostics export. */
export function readStreamHandlerFailures(): StreamHandlerFailureEntry[] {
  return loadEntries();
}

/** Aggregate by event → count (most-broken first) + the last failure timestamp. */
export function summarizeStreamHandlerFailures(): {
  byEvent: { event: string; count: number }[];
  lastTimestamp: number | null;
} {
  const entries = loadEntries();
  const counts = new Map<string, number>();
  let lastTimestamp: number | null = null;
  for (const e of entries) {
    counts.set(e.event, (counts.get(e.event) ?? 0) + 1);
    if (lastTimestamp === null || e.timestamp > lastTimestamp) lastTimestamp = e.timestamp;
  }
  const byEvent = [...counts.entries()]
    .map(([event, count]) => ({ event, count }))
    .sort((a, b) => b.count - a.count);
  return { byEvent, lastTimestamp };
}
