/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.justsearch.ui.api.ActivityRole;
import io.justsearch.ui.api.IndexingController;

public final class IndexingRoutes {
  private IndexingRoutes() {}

  public static void register(Javalin app, IndexingController indexingController) {
    app.get("/api/indexing/roots", indexingController::handleListRoots, ActivityRole.OBSERVATION);
    app.get("/api/indexing/suggested-roots", indexingController::handleSuggestedRoots, ActivityRole.USER_ACTION);
    app.post("/api/indexing/roots", indexingController::handleAddRoot, ActivityRole.USER_ACTION);
    app.delete("/api/indexing/roots", indexingController::handleRemoveRoot, ActivityRole.USER_ACTION);
    // Tempdoc 811 (C-2a) — removal route for collection-tagged ad-hoc ingests, which no
    // watched-root-prefix prune can reach.
    app.delete("/api/indexing/collections", indexingController::handleDeleteCollection, ActivityRole.USER_ACTION);
    app.post("/api/indexing/excludes/apply", indexingController::handleApplyExcludes, ActivityRole.USER_ACTION);
    app.post("/api/indexing/migration/cutover", indexingController::handleMigrationCutover, ActivityRole.USER_ACTION);
    app.post("/api/indexing/migration/rollback", indexingController::handleMigrationRollback, ActivityRole.USER_ACTION);
    app.post("/api/indexing/migration/pause", indexingController::handleMigrationPause, ActivityRole.USER_ACTION);
    app.post("/api/indexing/migration/resume", indexingController::handleMigrationResume, ActivityRole.USER_ACTION);
    app.post("/api/indexing/gc", indexingController::handleIndexGc, ActivityRole.USER_ACTION);
    // Tempdoc 931 §E item 10 — purge deleted-but-unmerged documents so paired evaluation arms
    // query indexes with equal merge state.
    app.post("/api/indexing/settle", indexingController::handleSettleIndex, ActivityRole.USER_ACTION);
    app.get("/api/indexing/failed-jobs", indexingController::handleListFailedJobs, ActivityRole.OBSERVATION);
    app.delete("/api/indexing/failed-jobs", indexingController::handleClearFailedJobs, ActivityRole.USER_ACTION);
    // Slice 3a.1.9 §B.B.D Stream A — substrate-shaped failed-jobs endpoint
    // for the core.failed-indexing-jobs Resource (TABULAR × ONE_SHOT).
    // Returns IndexingJobView[] with hashed paths.
    app.get(
        "/api/indexing-jobs/failed", indexingController::handleListFailedJobsSubstrate, ActivityRole.OBSERVATION);
    // Tempdoc 599 §16/B1 — folder-scoped failed jobs (per-folder "failed files" drill-down).
    app.get(
        "/api/indexing-jobs/failed/by-prefix",
        indexingController::handleListFailedJobsByPathPrefix, ActivityRole.OBSERVATION);
    // Slice 449 phase 7a — substrate-shaped indexed-roots endpoint for the
    // core.indexed-roots Resource (TABULAR × ONE_SHOT). Returns
    // IndexedRootView[] with hashed paths.
    app.get(
        "/api/indexing-roots/substrate", indexingController::handleListRootsSubstrate, ActivityRole.OBSERVATION);
    // Tempdoc 599 §9.4 — add-time validation/preview (Head-only filesystem + watched-roots check).
    app.post("/api/indexing-roots/preview", indexingController::handlePreviewRoot, ActivityRole.USER_ACTION);
    // Tempdoc 410 §12 — privacy-safe ingestion ledger read APIs.
    app.get(
        "/api/diagnostics/ingestion/recent", indexingController::handleRecentIngestionEvents, ActivityRole.USER_ACTION);
    app.get(
        "/api/diagnostics/ingestion/summary",
        indexingController::handleIngestionOutcomeSummary, ActivityRole.OBSERVATION);
    // ADR-0028 / tempdoc 419 T5.3 — scoped reverse path-hash lookup. The ONLY HTTP entry
    // point allowed to call IndexingService.resolvePathHash. Diagnostic export endpoints above
    // MUST NOT call it (enforced by ArchUnit pin LibraryResolveHashOnlyCallerPin in T5.4).
    app.post("/api/library/resolve-hash", indexingController::handleResolvePathHash, ActivityRole.USER_ACTION);
  }
}
