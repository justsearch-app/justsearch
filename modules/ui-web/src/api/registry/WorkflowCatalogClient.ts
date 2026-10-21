// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 565 §26.C — the workflow PICKER's catalog client. Fetches the lean workflow wire the backend
 * projects (`UIWorkflowEmitter` → `/api/registry/workflows`) so the run-window launcher PROJECTS the
 * catalog instead of hardcoding one `WORKFLOW_ID` const (§25.2 — a fork of 561's mode-catalog authority).
 *
 * <p>A picker is a LIGHT consumer (id + i18n keys + node list for a label and a count) — not the
 * fail-closed `parseWireContract` boundary `/api/status` needs — so this parses the envelope's `entries`
 * with a structural guard (the lightweight registry-list pattern, AHA: unify only what shares a reason
 * to change). The backend `UIWorkflowEmitter` remains the ONE picker-wire authority.
 */
import { authorizedFetch } from '../../shell-v0/api/authorizedFetch.js';

/** One workflow as the picker sees it (the lean `/api/registry/workflows` entry). */
export interface WorkflowCatalogEntry {
  readonly id: string;
  readonly presentation: { readonly labelKey: string; readonly descriptionKey: string };
  readonly audience: string;
  readonly nodes: ReadonlyArray<{ readonly nodeId: string; readonly kind: string }>;
}

function isEntry(v: unknown): v is WorkflowCatalogEntry {
  if (typeof v !== 'object' || v === null) return false;
  const e = v as Record<string, unknown>;
  return typeof e.id === 'string' && typeof e.presentation === 'object' && Array.isArray(e.nodes);
}

/**
 * Fetch the workflow catalog. Returns the entries (empty on any failure — the picker degrades to "no
 * workflows" rather than throwing into the run window).
 */
export async function fetchWorkflowCatalog(
  apiBase: string,
  signal?: AbortSignal,
): Promise<WorkflowCatalogEntry[]> {
  try {
    const res = await authorizedFetch(`${apiBase}/api/registry/workflows`, { signal });
    if (!res.ok) return [];
    const body = (await res.json()) as { entries?: unknown };
    const entries = Array.isArray(body.entries) ? body.entries : [];
    return entries.filter(isEntry);
  } catch {
    return [];
  }
}
