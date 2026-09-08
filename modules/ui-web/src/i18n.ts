// SPDX-License-Identifier: Apache-2.0
// Tempdoc 742: the Lingui bootstrap (locale detection, catalog activation,
// `src/locales/**` .po/.mjs catalogs) was removed — a verified clean
// extraction found zero t()/msg()/Trans usage anywhere in src/ (the Lit
// shell-v0 rewrite never adopted lingui macros). This file now only boots
// the backend-served message catalogs (error/resource/surface/health-event/
// operation/workflow), which were always locale-agnostic (`/en`-only,
// tempdoc 434) and independent of the lingui runtime.
import { resolveApiEndpoint } from "./api/http";
import { bootErrorCatalog } from "./i18n/errorCatalog";
import { bootResourceCatalog, bootSurfaceCatalog, bootHealthEventsCatalog, bootOperationMessageCatalog, bootWorkflowCatalog } from "./i18n/resourceCatalog";
// Slice 3a.1.9 §B.B.B D3: registry catalog boot moved here from
// HealthView so any future <jf-resource-view> mount works regardless
// of route. Renamed bootResourceCatalog → bootResourceRegistry (D4)
// to disambiguate from the i18n companion above.
import { bootResourceRegistry } from "./api/registry/ResourceCatalogClient";
import { bootOperationRegistry } from "./api/registry/OperationCatalogClient";
// Slice 448 phase 5: DiagnosticChannel — fourth registry primitive.
import { bootDiagnosticChannelRegistry } from "./api/registry/DiagnosticChannelCatalogClient";
// Slice 449 phase 5: Surface Manifest — second Manifest tier alongside Plugin.
import { bootSurfaceRegistry } from "./api/registry/SurfaceCatalogClient";
import { bootConversationShapeRegistry } from "./api/registry/ConversationShapeCatalogClient";
// Tempdoc 941 — the backend-ready re-attempt (see `watchForBackendReady` below).
import { subscribeAiState } from "./shell-v0/state/aiStateStore";
// Tempdoc 511 — aggregate-substrate core strategy registration. Runs
// synchronously at module load; no fetch needed (strategies are
// compiled-in). Side-effect import ensures registration happens
// before any <jf-operation> mount.
import { bootstrapAggregateSubstrate } from "./shell-v0/aggregate-substrate/bootstrap";
bootstrapAggregateSubstrate();

/**
 * Fetch every backend-served catalog. Each `boot*` is idempotent — it returns immediately once
 * that catalog's fetch has actually been ANSWERED — so this whole list is safe to call again
 * (tempdoc 941). A re-run re-requests only what is still missing.
 *
 * Slice 3a.1.4b: extends the boot sequence with the registry-resource catalog so
 * HealthLitView can resolve MetricRef.label keys without dragging Lingui into Lit.
 */
export function bootMessageCatalogs(baseUrl: string): Promise<void> {
  return Promise.all([
    bootErrorCatalog(baseUrl),
    bootResourceCatalog(baseUrl),
    bootSurfaceCatalog(baseUrl),
    bootHealthEventsCatalog(baseUrl),
    // Slice 3a.1.9 §B.B.B D3: registry catalog boot at app
    // startup so any <jf-resource-view> mount on any route
    // resolves catalog entries (was lazy-bound in HealthView
    // only).
    bootResourceRegistry(baseUrl),
    bootOperationRegistry(baseUrl),
    // Slice 448 phase 5: fourth primitive's catalog (DiagnosticChannel).
    bootDiagnosticChannelRegistry(baseUrl),
    // Slice 449 phase 5: Surface Manifest catalog. Manifests are the
    // second tier alongside primitives — they compose primitives into
    // chrome affordances. V1 ships one entry: core.library-surface.
    bootSurfaceRegistry(baseUrl),
    // Slice 491 §9.D Phase E (C0): ConversationShape catalog — the
    // Manifest tier for LLM-output flows. V1 ships 6 shapes (agent,
    // navigate-chat, ask, summarize, batch-summarize,
    // hierarchical-summarize). Consumed by <jf-chat-shape-mount>.
    bootConversationShapeRegistry(baseUrl),
    bootOperationMessageCatalog(baseUrl),
    // Tempdoc 565 §27.4: workflow picker authored-label catalog, so
    // present({kind:'workflow', labelKey}) resolves the authored label
    // instead of the humanizeId fallback.
    bootWorkflowCatalog(baseUrl),
  ]).then(() => undefined);
}

/**
 * Tempdoc 941 — re-attempt the catalog boot when the shell observes the backend answering.
 *
 * The boot above fires once, at module evaluation, and races the Head: the shell reloads itself
 * the instant the Rust side reports a new backend instance (`main.jsx`'s
 * `installBackendRestartBridge` → `window.location.reload()`), so the document routinely loads
 * against a Head that is up enough to publish a manifest but not yet answering
 * `/api/messages/**` or `/api/registry/**`. Losing that race used to be terminal for the whole
 * session: raw keys (`settings.group.general` rendered as its own name), an empty surface rail,
 * an unresolvable operation label — until the user reloaded by hand.
 *
 * The readiness signal is the shell's EXISTING one. `aiStateStore` polls `/api/status` and
 * projects contact reachability into `AiState.snapshotLive` (807 A.3) with the last successful
 * snapshot in `AiState.status`; "we have a status snapshot AND it is a live observation" is the
 * app's own definition of the backend answering right now. Subscribing costs no extra request —
 * this rides the poll the shell already runs, and each catalog's own idempotence means a
 * still-satisfied edge does no work at all.
 *
 * The re-attempt fires on the FALSE→TRUE edge only, so a steady-state healthy poll is one
 * boolean comparison per tick, and a backend that goes away and comes back gets another attempt.
 */
function watchForBackendReady(baseUrl: string): void {
  let wasReady = false;
  subscribeAiState((state) => {
    const ready = state.status !== null && state.snapshotLive;
    if (ready && !wasReady) {
      void bootMessageCatalogs(baseUrl).catch((err) => {
        console.debug("[i18n] backend-ready catalog re-attempt failed", err);
      });
    }
    wasReady = ready;
  });
}

// Background-fetch the backend message catalogs. Runs async; UI mounts immediately
// without waiting. Until each catalog arrives, lookups fall back to the raw key
// or wire message per tempdoc 434 §3.
if (typeof window !== "undefined") {
  resolveApiEndpoint()
    .then((endpoint) => {
      if (endpoint.baseUrl) {
        watchForBackendReady(endpoint.baseUrl);
        return bootMessageCatalogs(endpoint.baseUrl);
      }
      return undefined;
    })
    .catch((err) => {
      console.debug("[i18n] message catalog boot fetch failed", err);
    });
}
