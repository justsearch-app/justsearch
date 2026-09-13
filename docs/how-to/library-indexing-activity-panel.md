---
title: "Library Indexing Activity Panel — Frontend Implementation Guide"
type: how-to
status: draft
audience: frontend
related: tempdoc 419 (WP1, WP4), tempdoc 410 (privacy contract), ADR-0028 (scoped reverse-path-lookup)
---

# Library Indexing Activity Panel — Frontend Implementation Guide

The Library Folders view now implements the **retained-outcome summary** through
`shell-v0/components/IngestionSummary.ts`. It reads
`GET /api/diagnostics/ingestion/summary?since=0`, groups recorded event counts by
outcome and reason, and exposes loading, empty, refresh and fetch-error states.
`ingestionSummaryPresentation.ts` words the current reason vocabulary; its test
checks coverage against the Java constants. Counts are retained events, not unique
files or a current-readiness verdict. No path hashes are resolved by this panel.

The recent-event drawer, filename resolution and live scan-progress flows described
below remain planned. Their delivery must be verified separately. The historical
nine-row wording example is illustrative; the implementation's complete wording
projection is the maintained consumer.

This document is the frontend implementation guide for the **Library
Indexing Activity panel** (tempdoc 419 / WP1) and the live **scan
progress UI** (WP4). Backend substrate is shipped in slices T1-T6
(tempdoc 419); the
frontend slice can be built on top with no further backend changes
once T1-T6 land.

**Scope of this doc:** what to build, which endpoints to call, which
patterns to reuse, and what the UI must NOT do (privacy contract).

**Out of scope:** the Lit/TypeScript implementation itself, visual
design, copy decisions beyond the rules below.

## Why this exists

Today the Library tab shows watched roots and lets the user trigger a
reindex. It does NOT show what *happened* — which files succeeded,
skipped, or failed; how recent activity went; or what's currently
being scanned. The substrate to answer those questions exists
(diagnostic ledger endpoints, the scan-progress stream, scoped
path-resolution); this panel is where the user finally sees it.

## Endpoints to consume

### WP1 — Activity summary + recent events

Existing endpoints (from tempdoc 410 §12, no backend changes needed):

```http
GET /api/diagnostics/ingestion/summary?since=<epochMs>
  → { rollups: [{ outcomeClass, reasonCode, retryPolicy, count, lastObservedAtMs }], count }

GET /api/diagnostics/ingestion/recent?limit=<N>
  → { events: [IngestionEventView, ...], count }
```

`IngestionEventView` is pinned to exactly 14 fields (see [Drawer rules](#drawer-rules-what-to-show-vs-hide) below).

### WP1 — Resolve hash to filename (T5, ADR-0028)

After T5 ships:

```http
POST /api/library/resolve-hash
  body: { "pathHash": "<64-char hex>" }
  → 200: { found: true, path: "...", lastSeenAtMs: ..., removedAtMs: ... | null }
  → 200: { found: false }
```

**Critical privacy rule:** this endpoint is the **only** place the UI
may call to convert a `pathHash` into a filename. It must NEVER be
called inside an export / copy / share flow. See [Privacy contract](#privacy-contract).

### WP4 — Live scan progress

After T2-T4 ship:

```http
GET /api/scans/{scanId}/progress  (Server-Sent Events stream)
  → events:
      progress { filesWalked, filesAdmitted, filesSkipped, bytesWalked, complete? }
      complete { terminalReasonCode, totalDuration }
      error { message }
```

Closing the SSE connection (e.g., `eventSource.close()`) propagates a
cancel to the index half via the T3 `CancelToken` substrate — that's the
"Cancel scan" affordance.

## Reusable patterns

### Frontend API wrapper

Add new functions to `modules/ui-web/src/api/domains/indexing.ts`
(existing file, mirrors the pattern of `addRoot`, `removeRoot`, `reindex`).
Sketch:

```ts
import { request } from '../http';

export async function getRecentIngestionEvents(
  baseUrl: string,
  limit = 100,
  signal?: AbortSignal,
): Promise<IngestionEventView[]> {
  const data = await request<{ events: IngestionEventView[]; count: number }>(
    baseUrl,
    `/api/diagnostics/ingestion/recent?limit=${limit}`,
    { signal },
  );
  return data.events ?? [];
}

export async function getIngestionSummary(
  baseUrl: string,
  sinceMs = 0,
  signal?: AbortSignal,
): Promise<IngestionRollup[]> {
  const data = await request<{ rollups: IngestionRollup[]; count: number }>(
    baseUrl,
    `/api/diagnostics/ingestion/summary?since=${sinceMs}`,
    { signal },
  );
  return data.rollups ?? [];
}

export async function resolveHash(
  baseUrl: string,
  pathHash: string,
  signal?: AbortSignal,
): Promise<ResolvePathResult> {
  return request<ResolvePathResult>(baseUrl, `/api/library/resolve-hash`, {
    method: 'POST',
    body: { pathHash },
    signal,
  });
}
```

### Zod schemas

Add to `modules/ui-web/src/api/schemas/` (see existing `IndexedRootsResponseSchema`
for the pattern). Define `IngestionEventViewSchema` matching the 14
pinned fields, plus `IngestionRollupSchema` and `ResolvePathResultSchema`.

### SSE consumption

Reuse existing infrastructure (do not invent a new pattern):

- `modules/ui-web/src/api/sse.ts` — `parseSseBufferJson(buffer, callback)`
  is the canonical WHATWG-spec SSE buffer parser.
- `modules/ui-web/src/stores/useAgentStore.ts` — multi-event-type
  subscription pattern. Mirror the structure: `fetch` with
  `Accept: text/event-stream`, accumulate from `ReadableStream`, feed
  to `parseSseBufferJson` line-by-line, dispatch by event name.

For the scan-progress UI:

```ts
const eventSource = new EventSource(
  `${baseUrl}/api/scans/${scanId}/progress`,
);
eventSource.addEventListener('progress', (ev) => {
  const data = JSON.parse((ev as MessageEvent).data);
  // update UI with filesWalked, bytesWalked, etc.
});
eventSource.addEventListener('complete', (ev) => {
  eventSource.close();
});
// User clicks Cancel:
eventSource.close();  // triggers backend cancel via the T3 CancelToken
```

### Host components

Two natural host locations:

- **`modules/ui-web/src/shell-v0/views/LibrarySurface.ts`** — the
  Library tab. Indexing Activity is a logical extension of root
  management. Activity panel can be a collapsible section below the
  roots list, or a tab.
- **`modules/ui-web/src/shell-v0/views/HealthSurface.ts`** — the
  Health tab. If the team prefers diagnostic information here,
  Activity can live alongside the health status display.

Recommendation: **LibraryView**. Operators thinking about "why isn't
my file showing up?" naturally look at the Library, not Health.

## Drawer rules: what to show vs hide

The drawer (per-event detail view) shows fields from `IngestionEventView`,
which is **structurally pinned** to exactly these 14 fields by
`JobQueueTest.ingestionEventViewExportContractIsPinned`. The pin is
the source of truth — if it grows, this doc must update.

| Field | Show in primary view? | Notes |
|---|---|---|
| `id` | No | Internal; useful only for support tickets. |
| `pathHash` | **No** (Detailed mode or support only) | Hash is meaningless to humans. Use the resolver via "Show filename" button. |
| `collection` | Yes | "default", "docs", etc. |
| `outcomeClass` | Yes (with friendly label) | See [reason-code mapping](#reason-code-friendly-labels) below. |
| `reasonCode` | Yes (with friendly label) | Same. |
| `retryPolicy` | Conditional | Only relevant for retryable failures. |
| `diagnosticSummary` | Yes (when present) | Already sanitized; safe to show. |
| `observedAtMs` | Yes (formatted timestamp) | "5 minutes ago" or absolute time. |
| `sourceSizeBytes` | Yes (formatted) | "1.2 MB" |
| `sourceModifiedAtMs` | Yes (formatted timestamp) | |
| `sourceKind` | Yes | "Regular file", "Cloud placeholder", etc. |
| `artifactStatus` | Yes (when present) | "SUCCESS_FULL" → "Indexed in full"; "SUCCESS_PARTIAL" → "Indexed (truncated)"; etc. |
| `policyId` | Detailed mode | "tika-default-v1" |
| `parserId` | Detailed mode | "tika-policy-structured" |

**Show filename affordance:** A button next to each event reading
"Show filename" that calls `resolveHash(pathHash)` and either:
- Displays the resolved filename inline (when `found: true`), or
- Displays "(file no longer under any watched root)" when `found: false`.

The resolver call is the **only** place hashes are converted to
filenames in the UI. See [Privacy contract](#privacy-contract).

## Reason-code friendly labels

Reason codes are stable strings defined in
`modules/worker-core/src/main/java/io/justsearch/indexerworker/ingest/IngestionReasonCodes.java`.
The Java docstrings are the source of truth for user-facing copy.
Mirror them in the frontend as a typed mapping:

```ts
export const REASON_CODE_LABELS: Record<string, { label: string; explanation: string }> = {
  SUCCESS: {
    label: 'Indexed successfully',
    explanation: 'File was indexed without issues.',
  },
  SUCCESS_PARTIAL: {
    label: 'Indexed (truncated)',
    explanation: 'File was indexed, but the parser output was truncated to fit the configured limit.',
  },
  SKIPPED_TEMP_OR_SYSTEM: {
    label: 'Skipped (system file)',
    explanation: 'File matched a system or temp-file pattern and was intentionally skipped.',
  },
  UNCHANGED: {
    label: 'Unchanged',
    explanation: 'File has not been modified since the last index.',
  },
  CLOUD_PLACEHOLDER: {
    label: 'Cloud-only file (skipped)',
    explanation: 'File is a cloud placeholder (e.g., OneDrive Files-on-Demand) not available locally. Reading would trigger network hydration.',
  },
  PARSER_FAILED: {
    label: 'Extraction failed',
    explanation: 'The parser could not extract content from this file.',
  },
  PARSER_TIMEOUT: {
    label: 'Extraction timed out',
    explanation: 'Extraction took longer than the configured budget.',
  },
  INPUT_TOO_LARGE: {
    label: 'File too large',
    explanation: 'File exceeded the configured input size limit.',
  },
  OFFICE_INPUT_TOO_LARGE: {
    label: 'Office file too large',
    explanation: 'Office documents have a stricter size limit due to memory cost during parsing.',
  },
  // ... full set in IngestionReasonCodes.java
};
```

When `IngestionReasonCodes` adds a new constant, add it here too.
There is no automated regen; future tempdocs may surface a typed
schema export to close the gap.

## Privacy contract

The system enforces several privacy rules at the substrate level. The
UI must respect them:

### Hard rules (do not violate)

1. **`pathHash` is the operator-visible identifier.** Never display
   it in primary UI as if it were meaningful — it's a 64-char hex
   string useless to humans.

2. **`POST /api/library/resolve-hash` is the only resolver call.**
   It is enforced by an ArchUnit test on the backend; no other code
   path can return a path. Frontend must mirror this discipline:
   the resolver is called from one component (e.g., `<EventDrawer>`),
   not scattered across components.

3. **Never include resolved paths in any "export," "copy," or "share"
   flow.** If the user clicks "Export activity" or "Copy event details
   to clipboard," the export must use `pathHash` only — never the
   resolved path. The privacy contract permits resolution for
   *display* but forbids it for *export*.

4. **Treat `found: false` as authoritative.** The resolver returns
   `false` when the hash maps to a path no longer under any watched
   root, or when retention has expired. Do not fall back to displaying
   the hash as if it were a filename — display "(file no longer
   accessible)" or equivalent.

### Soft rules (recommended)

- Show "Show filename" as a button that requires explicit click,
  rather than auto-resolving every event on page load. This makes
  the resolution event a deliberate user action (matches the
  contract's intent) and avoids rate-pressuring the resolver.
- Display the timestamp of last-seen + removed-at when available,
  so users understand the lifecycle.
- Cache resolution results in component state for the duration of
  the panel session, but do not persist to localStorage or session
  storage (resolved paths shouldn't leak across browser sessions).

## Live scan progress UI (WP4)

When a user triggers an action that starts a scan (add root, reindex,
ingest), the response includes a `scanId`. The UI:

1. Subscribes to `/api/scans/{scanId}/progress` via EventSource.
2. Displays a progress card with files-walked, files-admitted, bytes-walked.
3. Shows a "Cancel" button that calls `eventSource.close()`.
4. On `complete` event, shows the terminal reason and closes the connection.

Concurrent scans are first-class: the UI can show multiple progress
cards simultaneously, each with its own scanId.

**Do NOT show `currentDirectory` as a literal label** — it's privacy-hashed
in the progress events. If the UI wants to show "currently scanning:
/Users/.../Documents", it must call `resolveHash` on the `currentDirectory`
hash. Same privacy rules apply.

## Verification

The frontend slice is complete when:

- A user adding a watched root can see a progress card for the scan.
- After indexing completes, the Library Activity panel shows rollup
  counts ("23 indexed, 2 skipped because unchanged, 1 extraction failed").
- Clicking an event in the drawer reveals friendly fields without
  exposing the raw hash.
- Clicking "Show filename" resolves the hash and displays the path,
  with a clear "(no longer accessible)" fallback.
- Exporting the activity (if exported anywhere) contains hashes only.

Run the backend integration test (T6 / `IngestionDiagnosticsContractTest`)
to verify the wire-level contract holds: it asserts no path strings
appear in the diagnostic responses and every hash is valid 64-char
SHA-256 hex. If frontend changes break that, the test fails.

## References

- Backend substrate: tempdoc 419 §"Long-term Substrate Decisions for
  Ingestion-Ledger Productization" (slices T1-T6).
- Privacy contract: `docs/explanation/03-knowledge-server.md` §"Ingestion
  Ledger Privacy Contract".
- Contract refinement (the resolver): `docs/decisions/0028-scoped-reverse-path-lookup.md`
  (added by T5.0).
- Field pin: `JobQueueTest.ingestionEventViewExportContractIsPinned`.
- SSE patterns: `SseWriter.java`, `parseSseBufferJson` in `sse.ts`,
  `useAgentStore.ts`.
