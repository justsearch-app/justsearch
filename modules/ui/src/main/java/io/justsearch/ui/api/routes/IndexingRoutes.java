/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.justsearch.ui.api.IndexingController;

public final class IndexingRoutes {
  private IndexingRoutes() {}

  public static void register(Javalin app, IndexingController indexingController) {
    app.get("/api/indexing/roots", indexingController::handleListRoots);
    app.get("/api/indexing/suggested-roots", indexingController::handleSuggestedRoots);
    app.post("/api/indexing/roots", indexingController::handleAddRoot);
    app.delete("/api/indexing/roots", indexingController::handleRemoveRoot);
    // Tempdoc 811 (C-2a) — removal route for collection-tagged ad-hoc ingests, which no
    // watched-root-prefix prune can reach.
    app.delete("/api/indexing/collections", indexingController::handleDeleteCollection);
    app.post("/api/indexing/excludes/apply", indexingController::handleApplyExcludes);
    app.post("/api/indexing/migration/cutover", indexingController::handleMigrationCutover);
    app.post("/api/indexing/migration/rollback", indexingController::handleMigrationRollback);
    app.post("/api/indexing/migration/pause", indexingController::handleMigrationPause);
    app.post("/api/indexing/migration/resume", indexingController::handleMigrationResume);
    app.post("/api/indexing/gc", indexingController::handleIndexGc);
    // Tempdoc 931 §E item 10 — purge deleted-but-unmerged documents so paired evaluation arms
    // query indexes with equal merge state.
    app.post("/api/indexing/settle", indexingController::handleSettleIndex);
    app.get("/api/indexing/failed-jobs", indexingController::handleListFailedJobs);
    app.delete("/api/indexing/failed-jobs", indexingController::handleClearFailedJobs);
    // Slice 3a.1.9 §B.B.D Stream A — substrate-shaped failed-jobs endpoint
    // for the core.failed-indexing-jobs Resource (TABULAR × ONE_SHOT).
    // Returns IndexingJobView[] with hashed paths.
    app.get(
        "/api/indexing-jobs/failed", indexingController::handleListFailedJobsSubstrate);
    // Tempdoc 599 §16/B1 — folder-scoped failed jobs (per-folder "failed files" drill-down).
    app.get(
        "/api/indexing-jobs/failed/by-prefix",
        indexingController::handleListFailedJobsByPathPrefix);
    // Slice 449 phase 7a — substrate-shaped indexed-roots endpoint for the
    // core.indexed-roots Resource (TABULAR × ONE_SHOT). Returns
    // IndexedRootView[] with hashed paths.
    app.get(
        "/api/indexing-roots/substrate", indexingController::handleListRootsSubstrate);
    // Tempdoc 599 §9.4 — add-time validation/preview (Head-only filesystem + watched-roots check).
    app.post("/api/indexing-roots/preview", indexingController::handlePreviewRoot);
    // Tempdoc 410 §12 — privacy-safe ingestion ledger read APIs.
    app.get(
        "/api/diagnostics/ingestion/recent", indexingController::handleRecentIngestionEvents);
    app.get(
        "/api/diagnostics/ingestion/summary",
        indexingController::handleIngestionOutcomeSummary);
    // ADR-0028 / tempdoc 419 T5.3 — scoped reverse path-hash lookup. The ONLY HTTP entry
    // point allowed to call IndexingService.resolvePathHash. Diagnostic export endpoints above
    // MUST NOT call it (enforced by ArchUnit pin LibraryResolveHashOnlyCallerPin in T5.4).
    app.post("/api/library/resolve-hash", indexingController::handleResolvePathHash);
  }
}
