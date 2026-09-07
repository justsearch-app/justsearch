/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.ExcludesService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.indexing.FailedIndexingJobsResponse;
import io.justsearch.app.api.indexing.FailedJob;
import io.justsearch.app.api.indexing.FailedJobsResponse;
import io.justsearch.app.api.indexing.IndexingJobView;
import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.status.MigrationSource;
import io.justsearch.ipc.KnowledgeServerNotConnectedException;
import io.grpc.StatusRuntimeException;
import io.justsearch.telemetry.Telemetry;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP routing layer for indexing endpoints. Service-impl logic for {@code ExcludesService} lives
 * in {@code io.justsearch.app.services.excludes.ExcludesServiceImpl} (tempdoc 519 §9 Step 3).
 */
public class IndexingController {
  private static final Logger log = LoggerFactory.getLogger(IndexingController.class);
  // Tempdoc 526 F1 (merged from main): capture by supplier, not by value. The 502 refactor's
  // resolved-at-ctor pattern broke eval/headless flow — Worker comes up async after
  // LocalApiServer ctor, so a value-capture pinned the sentinel for the process lifetime.
  // §31's HeadAssembly.workers().indexing() already resolves laterally; calling it on each
  // request makes connectKnowledgeServer's swap observable. ExcludesService is no longer
  // referenced here — §31 §9 Step 3 moved the impl into app-services.
  private final Supplier<IndexingService> indexingServiceSupplier;
  private final ExcludesService excludesService;
  private final Path userHome; // nullable — null means "not available"
  private final Telemetry telemetry;
  // Tempdoc 542 Phase 3: op-lease SPI for migration / bulk-reindex / index-gc REST entry points.
  private final OperationLeaseService leaseService;
  private volatile io.justsearch.app.services.lifecycle.WorkerCapability workerCapability;

  public IndexingController(
      Supplier<IndexingService> indexingServiceSupplier,
      ExcludesService excludesService,
      Path userHome,
      Telemetry telemetry,
      OperationLeaseService leaseService) {
    this.indexingServiceSupplier = indexingServiceSupplier;
    this.excludesService = excludesService;
    this.userHome = userHome;
    this.telemetry = telemetry;
    this.leaseService = java.util.Objects.requireNonNull(leaseService, "leaseService");
  }

  private IndexingService indexingService() {
    return indexingServiceSupplier.get();
  }

  public void setWorkerCapability(io.justsearch.app.services.lifecycle.WorkerCapability cap) {
    this.workerCapability = cap;
  }

  private boolean workerAvailable() {
    var cap = workerCapability;
    return cap != null && cap.available();
  }

  /**
   * Tempdoc 599 Fix 3 — cap for add-time / row file counts. These walks run on the Head request
   * thread (add-preview fires per debounced keystroke), so an unbounded {@code Files.walk} over a
   * huge root (e.g. {@code C:\}) would stall the thread. We stop at this many entries and report
   * the count as "capped" so the UI can show "N+".
   */
  private static final int MAX_COUNT_FILES = 50_000;

  /** Result of a bounded file count: {@code count} regular files, {@code capped} if the limit hit. */
  private record BoundedCount(long count, boolean capped) {}

  /**
   * Counts regular files under {@code root}, terminating early at {@link #MAX_COUNT_FILES}. Mirrors
   * the codebase's idiomatic bounded walk ({@code ExcludesServiceImpl}): a {@link SimpleFileVisitor}
   * with an early {@code TERMINATE}. Returns {@code count = -1, capped = false} on walk error.
   */
  private static BoundedCount countFilesBounded(Path root, int cap) {
    final long[] count = {0L};
    final boolean[] capped = {false};
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (attrs.isRegularFile()) {
                count[0]++;
                if (count[0] >= cap) {
                  capped[0] = true;
                  return FileVisitResult.TERMINATE;
                }
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, java.io.IOException exc) {
              // Skip unreadable entries; a permission error on one file shouldn't abort the count.
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (Exception e) {
      log.warn("Failed to count files under {}", root, e);
      return new BoundedCount(-1L, false);
    }
    return new BoundedCount(count[0], capped[0]);
  }

  public void handleListRoots(Context ctx) {
    try {
      IndexingService indexing = indexingService();
      List<IndexingService.WatchedRoot> roots = indexing.getWatchedRoots();
      boolean includeCounts = Boolean.parseBoolean(ctx.queryParam("counts") != null ? ctx.queryParam("counts") : "false");

      List<Map<String, Object>> payload =
          roots.stream()
              .map(
                  root -> {
                    long count = -1;
                    if (includeCounts) {
                      count = countFilesBounded(root.path(), MAX_COUNT_FILES).count();
                    }
                    // Use HashMap since collection/lastIndexed may be null and Map.of() doesn't allow nulls
                    Map<String, Object> entry = new java.util.HashMap<>();
                    entry.put("collection", root.collection() != null ? root.collection() : "default");
                    entry.put("path", root.path().toString());
                    entry.put("fileCount", count);
                    if (root.lastIndexed() != null) {
                      entry.put("lastIndexed", root.lastIndexed().toString());
                    }
                    return entry;
              })
              .toList();

      ctx.json(Map.of("roots", payload));
    } catch (Exception e) {
      log.error("Failed to list watched roots", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleAddRoot(Context ctx) {
    try {
      Map<String, String> body = ctx.bodyAsClass(Map.class);
      String path = body.get("path");
      String collection = body.get("collection");
      if (path == null || path.isBlank()) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "path is required", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      Path resolved = Path.of(path).toAbsolutePath().normalize();
      if (!Files.isDirectory(resolved)) {
        ctx.status(400)
            .json(
                ApiErrorHandler.toResponse(
                    ApiErrorCode.INVALID_PATH, "Path does not exist or is not a directory: " + resolved, telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      // A watched root's collection tags every document the root's scan admits, so it is subject to
      // the same reserved-name guard as the ad-hoc ingest surfaces — routed through the ONE
      // authority (IngestCollectionPolicy), not a second copy of the rule. Blank/absent stays the
      // pre-existing "index default" shape; only a supplied, non-blank value is validated.
      if (collection != null && !collection.isBlank()) {
        try {
          collection = io.justsearch.app.api.knowledge.IngestCollectionPolicy.normalizeRequested(collection);
        } catch (IllegalArgumentException e) {
          ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
          return;
        }
      }
      IndexingService indexing = indexingService();
      indexing.addWatchedRoot(collection, resolved);
      ctx.status(200).json(Map.of("status", "ok"));
    } catch (Exception e) {
      log.error("Failed to add watched root", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Tempdoc 599 §9.4 — add-time validation/preview. Head-only (filesystem + watched-roots check, no
   * Worker round-trip): given a typed path, report whether it exists, is a directory, its file count,
   * and whether it is already watched — so the Add Folder flow can validate/gate before creating the
   * index job. Informational: a non-existent path returns {@code exists:false}, not a 4xx.
   */
  public void handlePreviewRoot(Context ctx) {
    try {
      Map<String, String> body = ctx.bodyAsClass(Map.class);
      String path = body.get("path");
      if (path == null || path.isBlank()) {
        ctx.status(400)
            .json(
                ApiErrorHandler.toResponse(
                    ApiErrorCode.INVALID_REQUEST,
                    "path is required",
                    telemetry,
                    ApiErrorHandler.routeOf(ctx)));
        return;
      }
      Path resolved = Path.of(path).toAbsolutePath().normalize();
      boolean exists = Files.exists(resolved);
      boolean isDir = Files.isDirectory(resolved);
      long fileCount = -1;
      boolean capped = false;
      if (isDir) {
        // Bounded count (tempdoc 599 Fix 3): this fires per debounced keystroke, so a huge typed
        // root must not walk the whole tree on the request thread.
        BoundedCount bc = countFilesBounded(resolved, MAX_COUNT_FILES);
        fileCount = bc.count();
        capped = bc.capped();
      }
      boolean alreadyWatched = false;
      try {
        alreadyWatched =
            indexingService().getWatchedRoots().stream()
                .anyMatch(
                    r ->
                        r.path() != null
                            && r.path().toAbsolutePath().normalize().equals(resolved));
      } catch (Exception e) {
        log.debug("Preview: watched-roots check failed for {}", resolved, e);
      }
      Map<String, Object> out = new java.util.LinkedHashMap<>();
      out.put("exists", exists);
      out.put("isDir", isDir);
      out.put("fileCount", fileCount);
      out.put("capped", capped);
      out.put("alreadyWatched", alreadyWatched);
      ctx.status(200).json(out);
    } catch (Exception e) {
      log.error("Failed to preview root", e);
      ctx.status(500)
          .json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleRemoveRoot(Context ctx) {
    try {
      Map<String, String> body = ctx.bodyAsClass(Map.class);
      String path = body.get("path");
      String collection = body.get("collection");
      if (path == null || path.isBlank()) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "path is required", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      IndexingService indexing = indexingService();
      int deletedJobs = indexing.removeWatchedRoot(collection, Path.of(path));
      ctx.status(200).json(Map.of(
          "status", "ok",
          "deletedJobs", deletedJobs
      ));
    } catch (Exception e) {
      log.error("Failed to remove watched root", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Tempdoc 811 (C-2a) — the removal route for collection-tagged ad-hoc ingests.
   *
   * <p>{@code DELETE /api/indexing/collections} body {@code {"collection": "mcp-ingest"}}. Before
   * 811 a document ingested through {@code POST /api/knowledge/ingest} or the {@code
   * core.ingest-files} tool from a path under no watched root carried NO collection and no prune
   * path could reach it — {@code removeWatchedRoot} / {@code PruneOps.pruneByPathPrefix} are both
   * watched-root-prefix driven. Deleting by collection is the addressable route the tag creates.
   *
   * <p>Reserved app-internal collections ({@code justsearch-help}, {@code agent-history}) and the
   * untagged {@code default} bucket are refused: the latter would be a whole-index wipe wearing a
   * collection's clothes. Loopback-only, and the session-token before-handler
   * ({@code ApiSecurityFilters.setupSessionTokenEnforcement}) covers DELETE like every other
   * mutation.
   */
  public void handleDeleteCollection(Context ctx) {
    try {
      Map<String, String> body = ctx.bodyAsClass(Map.class);
      String collection = body == null ? null : body.get("collection");
      if (collection == null || collection.isBlank()) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "collection is required", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      String trimmed = collection.trim();
      if (!io.justsearch.app.api.knowledge.IngestCollectionPolicy.isDeletable(trimmed)) {
        ctx.status(400)
            .json(
                ApiErrorHandler.toResponse(
                    ApiErrorCode.INVALID_REQUEST,
                    "collection '"
                        + trimmed
                        + "' cannot be deleted: the app-internal collections ("
                        + String.join(
                            ", ",
                            io.justsearch.app.api.knowledge.IngestCollectionPolicy
                                .reservedCollections()
                                .stream()
                                .sorted()
                                .toList())
                        + ") and the untagged '"
                        + io.justsearch.app.api.knowledge.IngestCollectionPolicy.DEFAULT_COLLECTION
                        + "' bucket are protected",
                    telemetry,
                    ApiErrorHandler.routeOf(ctx)));
        return;
      }
      int deleted = indexingService().deleteDocsByCollection(trimmed);
      ctx.status(200).json(Map.of("status", "ok", "collection", trimmed, "deletedDocs", deleted));
    } catch (StatusRuntimeException e) {
      int http = ApiErrorHandler.mapGrpcToHttp(e.getStatus().getCode());
      ctx.status(http).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (KnowledgeServerNotConnectedException | UnsupportedOperationException e) {
      ctx.status(503)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Knowledge Server not ready", telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to delete collection", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleReindex(Context ctx) {
    try {
      boolean force = Boolean.parseBoolean(ctx.queryParam("force"));
      IndexingService indexing = indexingService();
      indexing.reindexWatchedRoots(force);
      indexing.flush();

      String status = force ? "force reindex triggered" : "reindex triggered";
      ctx.status(200).json(Map.of("status", status, "force", force));
    } catch (StatusRuntimeException e) {
      // Fail closed: don't return 200 when the Worker rejected the request (queue full, unavailable, etc.)
      int http = ApiErrorHandler.mapGrpcToHttp(e.getStatus().getCode());
      ctx.status(http).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (KnowledgeServerNotConnectedException e) {
      ctx.status(503)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Knowledge Server not ready", telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to trigger reindex", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Applies exclude patterns by deleting already-indexed documents whose paths match the configured globs.
   *
   * <p>POST /api/indexing/excludes/apply
   *
   * <p>Notes:
   * - Patterns come from the resolved {@code justsearch.ui.exclude_patterns} (settings.json at
   *   ordinal 300; env var / {@code -D} still win at 400 / 500).
   * - Deletion is delegated to the Worker via gRPC (no Lucene/queue DB IO in Head).
   * - This is explicit, user-triggered cleanup; it does not run automatically in the background.
   */
  public void handleApplyExcludes(Context ctx) {
    try {
      boolean dryRun = Boolean.parseBoolean(ctx.queryParam("dryRun"));
      ExcludesService.ExcludesResult result = excludesService.applyExcludes(dryRun);
      List<Map<String, Object>> perPattern = new ArrayList<>();
      for (ExcludesService.ExcludesResult.PatternMatch pm : result.perPattern()) {
        perPattern.add(Map.of("pattern", pm.pattern(), "matches", pm.matches()));
      }
      // Map.of() supports max 10 entries; use HashMap for 11+
      java.util.HashMap<String, Object> wire = new java.util.HashMap<>();
      wire.put("status", "ok");
      wire.put("dryRun", result.dryRun());
      if (result.message() != null) {
        wire.put("message", result.message());
      }
      wire.put("patterns", result.patterns());
      wire.put("rootsProcessed", result.rootsProcessed());
      wire.put("deletedByPathJobs", result.deletedByPathJobs());
      wire.put("deletedById", result.deletedById());
      wire.put("matchedFiles", result.matchedFiles());
      wire.put("perPattern", perPattern);
      wire.put("capped", result.capped());
      ctx.status(200).json(wire);
    } catch (Exception e) {
      log.error("Failed to apply exclude patterns", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleMigrationStart(Context ctx) {
    Map<String, Object> body;
    try {
      body = ctx.bodyAsClass(Map.class);
    } catch (Exception e) {
      ctx.status(400).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    // Tempdoc 837 §2.3(i) — the one boundary where an OUTSIDE caller writes the migration source into
    // the persisted generation manifest. Everything else that starts a migration passes a vocabulary
    // member already, so this is where the closure is enforced: a recognized member passes through,
    // anything else is coerced to `manual` (a REST-initiated migration with an unrecognized label IS
    // a manual start) rather than being forwarded verbatim into on-disk state that outlives it.
    String requestedReason = body == null ? "" : String.valueOf(body.getOrDefault("reason", ""));
    MigrationSource requestedSource = MigrationSource.fromWire(requestedReason);
    if (requestedSource == MigrationSource.UNKNOWN) {
      if (!requestedReason.isBlank()) {
        log.warn(
            "Migration start requested with an unrecognized reason \"{}\" — recording it as \"{}\"",
            requestedReason,
            MigrationSource.MANUAL.wire());
      }
      requestedSource = MigrationSource.MANUAL;
    }
    String reason = requestedSource.wire();
    // Tempdoc 542 Phase 3 — REST entry for migration: MUST_COMPLETE op-lease registered
    // BEFORE the gRPC dispatches so concurrent takeover gate reads see the lease.
    OperationLeaseHandle handle = leaseService.register(
        "indexing.migration",
        OpCriticality.MUST_COMPLETE,
        1800L,
        Map.of("source", "REST /api/indexing/migration/start", "reason", reason));
    try {
      boolean accepted = indexingService().startMigration(reason);
      if (accepted) {
        // Worker persists MIGRATING before acknowledging, then owns the asynchronous lifetime.
        handle.release(OpLeaseOutcome.SUCCESS);
        ctx.status(202).json(Map.of("status", "migration start requested"));
      } else {
        handle.release(OpLeaseOutcome.FAILURE);
        ctx.status(409).json(Map.of("status", "migration start rejected by worker"));
      }
    } catch (StatusRuntimeException e) {
      handle.release(OpLeaseOutcome.FAILURE);
      int http = ApiErrorHandler.mapGrpcToHttp(e.getStatus().getCode());
      ctx.status(http).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      handle.release(OpLeaseOutcome.FAILURE);
      log.error("Failed to start migration", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleMigrationCutover(Context ctx) {
    try {
      String raw = ctx.queryParam("forceSwitching");
      boolean forceSwitching = Boolean.parseBoolean(raw == null ? "false" : raw);
      boolean accepted = indexingService().requestCutover(forceSwitching);
      if (accepted) {
        ctx.status(202).json(Map.of("status", "cutover requested", "forceSwitching", forceSwitching));
      } else {
        ctx.status(409).json(Map.of("status", "cutover rejected by worker"));
      }
    } catch (StatusRuntimeException e) {
      int http = ApiErrorHandler.mapGrpcToHttp(e.getStatus().getCode());
      ctx.status(http).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to request cutover", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleMigrationRollback(Context ctx) {
    try {
      boolean accepted = indexingService().rollbackMigration();
      if (accepted) {
        ctx.status(202).json(Map.of("status", "rollback requested"));
      } else {
        ctx.status(409).json(Map.of("status", "rollback rejected by worker"));
      }
    } catch (StatusRuntimeException e) {
      int http = ApiErrorHandler.mapGrpcToHttp(e.getStatus().getCode());
      ctx.status(http).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to request rollback", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleMigrationPause(Context ctx) {
    try {
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      String reason = body == null ? "" : String.valueOf(body.getOrDefault("reason", ""));
      boolean accepted = indexingService().pauseMigration(reason);
      if (accepted) {
        ctx.status(202).json(Map.of("status", "migration pause requested"));
      } else {
        ctx.status(409).json(Map.of("status", "migration pause rejected by worker"));
      }
    } catch (StatusRuntimeException e) {
      int http = ApiErrorHandler.mapGrpcToHttp(e.getStatus().getCode());
      ctx.status(http).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to pause migration", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleMigrationResume(Context ctx) {
    try {
      boolean accepted = indexingService().resumeMigration();
      if (accepted) {
        ctx.status(202).json(Map.of("status", "migration resume requested"));
      } else {
        ctx.status(409).json(Map.of("status", "migration resume rejected by worker"));
      }
    } catch (StatusRuntimeException e) {
      int http = ApiErrorHandler.mapGrpcToHttp(e.getStatus().getCode());
      ctx.status(http).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to resume migration", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleIndexGc(Context ctx) {
    try {
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      int keepLatest = 0;
      boolean pruneMarkedOnly = true;
      if (body != null) {
        Object keep = body.get("keepLatest");
        if (keep instanceof Number n) {
          keepLatest = n.intValue();
        } else if (keep != null) {
          try {
            keepLatest = Integer.parseInt(String.valueOf(keep));
          } catch (NumberFormatException expected) { /* invalid input, keep default */ }
        }
        Object pmo = body.get("pruneMarkedOnly");
        if (pmo != null) {
          pruneMarkedOnly = Boolean.parseBoolean(String.valueOf(pmo));
        }
      }
      var outcome = indexingService().runIndexGc(keepLatest, pruneMarkedOnly);
      if (outcome.accepted()) {
        ctx.status(202)
            .json(
                Map.of(
                    "status", "gc requested",
                    "keepLatest", keepLatest,
                    "pruneMarkedOnly", pruneMarkedOnly,
                    "markedCount", outcome.markedCount(),
                    "prunedCount", outcome.prunedCount()));
      } else {
        ctx.status(409)
            .json(Map.of("status", "gc rejected by worker", "error", outcome.error()));
      }
    } catch (StatusRuntimeException e) {
      int http = ApiErrorHandler.mapGrpcToHttp(e.getStatus().getCode());
      ctx.status(http).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to run index GC", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Tempdoc 931 §E item 10 — {@code POST /api/indexing/settle}. Purges deleted-but-unmerged
   * documents from the active index and answers 202 with the before/after document counts, so a
   * paired evaluation can show both arms queried an index with the same merge state. 409 when the
   * worker refuses (no index runtime, migration in flight, upgrade barrier held).
   */
  public void handleSettleIndex(Context ctx) {
    try {
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      boolean expungeDeletesOnly = true;
      int maxSegments = 0;
      if (body != null) {
        Object edo = body.get("expungeDeletesOnly");
        if (edo != null) {
          expungeDeletesOnly = Boolean.parseBoolean(String.valueOf(edo));
        }
        Object seg = body.get("maxSegments");
        if (seg instanceof Number n) {
          maxSegments = n.intValue();
        } else if (seg != null) {
          try {
            maxSegments = Integer.parseInt(String.valueOf(seg));
          } catch (NumberFormatException expected) { /* invalid input, keep default */ }
        }
      }
      var outcome = indexingService().settleIndex(expungeDeletesOnly, Math.max(0, maxSegments));
      if (outcome.accepted()) {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("status", "settle completed");
        response.put("expungeDeletesOnly", expungeDeletesOnly);
        response.put("maxDocBefore", outcome.maxDocBefore());
        response.put("numDocsBefore", outcome.numDocsBefore());
        response.put("maxDocAfter", outcome.maxDocAfter());
        response.put("numDocsAfter", outcome.numDocsAfter());
        response.put("segmentsAfter", outcome.segmentsAfter());
        response.put("elapsedMs", outcome.elapsedMs());
        ctx.status(202).json(response);
      } else {
        ctx.status(409)
            .json(Map.of("status", "settle rejected by worker", "error", outcome.error()));
      }
    } catch (StatusRuntimeException e) {
      int http = ApiErrorHandler.mapGrpcToHttp(e.getStatus().getCode());
      ctx.status(http).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to settle index", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleSuggestedRoots(Context ctx) {
    try {
      if (userHome == null) {
        ctx.json(Map.of("suggestions", List.of()));
        return;
      }

      List<Map<String, String>> candidates =
          List.of(
              Map.of("label", "Documents", "path", userHome.resolve("Documents").toString()),
              Map.of("label", "Desktop", "path", userHome.resolve("Desktop").toString()),
              Map.of("label", "Downloads", "path", userHome.resolve("Downloads").toString()));

      // Filter out non-existent directories and already-watched roots
      List<Path> watchedPaths;
      if (workerAvailable()) {
        watchedPaths =
            indexingService().getWatchedRoots().stream()
                .map(IndexingService.WatchedRoot::path)
                .toList();
      } else {
        watchedPaths = List.of();
      }

      List<Path> watched = watchedPaths;
      List<Map<String, String>> suggestions =
          candidates.stream()
              .filter(
                  c -> {
                    Path p = Path.of(c.get("path"));
                    return Files.isDirectory(p) && watched.stream().noneMatch(w -> w.equals(p));
                  })
              .toList();

      ctx.json(Map.of("suggestions", suggestions));
    } catch (Exception e) {
      log.error("Failed to get suggested roots", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleListFailedJobs(Context ctx) {
    try {
      if (!workerAvailable()) {
        ctx.status(503)
            .json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Indexing service unavailable", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      int limit = 100;
      String limitParam = ctx.queryParam("limit");
      if (limitParam != null) {
        try {
          limit = Integer.parseInt(limitParam);
        } catch (NumberFormatException expected) {
          // invalid query param, keep default
        }
      }
      IndexingService indexing = indexingService();
      // Tempdoc 564 Phase 5: emit the typed wire record (built directly from the typed
      // FailedJobInfo) so the FE validates the surface against a generated schema → Zod projection;
      // the JSON is identical to the prior Map payload.
      List<FailedJob> jobs =
          indexing.listFailedJobs(limit).stream()
              .map(
                  j ->
                      new FailedJob(
                          j.path(), j.errorMessage(), j.attempts(), j.lastUpdatedMs(), j.collection()))
              .toList();
      ctx.json(new FailedJobsResponse(jobs, jobs.size()));
    } catch (Exception e) {
      log.error("Failed to list failed jobs", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Slice 3a.1.9 §B.B.D Stream A — substrate-shaped failed-jobs endpoint.
   *
   * <p>Companion to {@link #handleListFailedJobs} which keeps the legacy
   * {@code {jobs, count}} shape with raw paths for the existing React
   * Health view + the {@code core.clear-failed-jobs} Operation flow.
   *
   * <p>This endpoint serves the same data shaped as
   * {@code {jobs: IndexingJobView[]}} — paths SHA-256 hashed at the
   * boundary so the wire is consumable by {@code <jf-resource-view
   * resource-id="core.failed-indexing-jobs">} (TABULAR ×
   * Privacy.HASHED_REQUIRES_RESOLVER, like its sibling
   * {@code core.indexing-jobs}).
   */
  public void handleListFailedJobsSubstrate(Context ctx) {
    try {
      if (!workerAvailable()) {
        ctx.status(503)
            .json(
                ApiErrorHandler.toResponse(
                    ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Indexing service unavailable",
                    telemetry,
                    ApiErrorHandler.routeOf(ctx)));
        return;
      }
      int limit = 100;
      String limitParam = ctx.queryParam("limit");
      if (limitParam != null) {
        try {
          limit = Integer.parseInt(limitParam);
        } catch (NumberFormatException expected) {
          // invalid query param, keep default
        }
      }
      IndexingService indexing = indexingService();
      List<IndexingJobView> payload =
          indexing.listFailedJobs(limit).stream().map(IndexingController::toJobView).toList();
      ctx.json(new FailedIndexingJobsResponse(payload, payload.size()));
    } catch (Exception e) {
      log.error("Failed to list substrate-shaped failed jobs", e);
      ctx.status(500)
          .json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Tempdoc 599 §16/B1 — the per-folder "failed files" drill-down. Substrate-shaped (hashed paths,
   * mirrors {@link #handleListFailedJobsSubstrate}) but scoped to one watched root. The FE passes the
   * root's {@code pathHash} (it has only the hash, not the raw path, per ADR-0028); the Head maps it
   * back to the raw path by matching {@code sha256Hex(root.path())} over the watched roots, then asks
   * the worker for FAILED jobs under that prefix.
   */
  public void handleListFailedJobsByPathPrefix(Context ctx) {
    try {
      if (!workerAvailable()) {
        ctx.status(503)
            .json(
                ApiErrorHandler.toResponse(
                    ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Indexing service unavailable",
                    telemetry,
                    ApiErrorHandler.routeOf(ctx)));
        return;
      }
      String pathHash = ctx.queryParam("pathHash");
      if (pathHash == null || pathHash.isBlank()) {
        ctx.status(400)
            .json(
                ApiErrorHandler.toResponse(
                    ApiErrorCode.INVALID_REQUEST,
                    "pathHash is required",
                    telemetry,
                    ApiErrorHandler.routeOf(ctx)));
        return;
      }
      int limit = 100;
      String limitParam = ctx.queryParam("limit");
      if (limitParam != null) {
        try {
          limit = Integer.parseInt(limitParam);
        } catch (NumberFormatException expected) {
          // invalid query param, keep default
        }
      }
      IndexingService indexing = indexingService();
      Path rawPath = null;
      for (IndexingService.WatchedRoot root : indexing.getWatchedRoots()) {
        if (root.path() != null && sha256Hex(root.path().toString()).equals(pathHash)) {
          rawPath = root.path();
          break;
        }
      }
      if (rawPath == null) {
        ctx.status(404)
            .json(
                ApiErrorHandler.toResponse(
                    ApiErrorCode.NOT_FOUND,
                    "No watched root matches the given pathHash",
                    telemetry,
                    ApiErrorHandler.routeOf(ctx)));
        return;
      }
      List<IndexingJobView> payload =
          indexing.listFailedJobsByPathPrefix(rawPath, limit).stream()
              .map(IndexingController::toJobView)
              .toList();
      ctx.json(new FailedIndexingJobsResponse(payload, payload.size()));
    } catch (Exception e) {
      log.error("Failed to list folder-scoped failed jobs", e);
      ctx.status(500)
          .json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Slice 449 phase 7a — TABULAR × ONE_SHOT substrate exposure of watched
   * roots, parallel to {@link #handleListFailedJobsSubstrate}. Backs the
   * {@code core.indexed-roots} Resource (per
   * {@link io.justsearch.app.observability.indexing.IndexedRootsResourceCatalog}).
   *
   * <p>Wire shape: {@code {items: IndexedRootView[], count: number}}. Paths
   * are SHA-256 hashed per ADR-0028 + {@code LibraryResolveHashOnlyCallerPin};
   * callers resolve via {@code core.resolve-path-hash} when a user gesture
   * demands the path.
   *
   * <p>Status derivation: a root with {@code walkError} present → "error";
   * a root with {@code lastIndexed} present → "indexed"; otherwise "pending".
   * The "indexing" status (in-flight) lives on {@code core.indexing-jobs}, not
   * here — this Resource describes watched roots, not active indexing work.
   */
  public void handleListRootsSubstrate(Context ctx) {
    try {
      if (!workerAvailable()) {
        ctx.status(503)
            .json(
                ApiErrorHandler.toResponse(
                    ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Indexing service unavailable",
                    telemetry,
                    ApiErrorHandler.routeOf(ctx)));
        return;
      }
      IndexingService indexing = indexingService();
      List<IndexingService.WatchedRoot> roots = indexing.getWatchedRoots();
      boolean includeCounts =
          Boolean.parseBoolean(
              ctx.queryParam("counts") != null ? ctx.queryParam("counts") : "false");

      List<Map<String, Object>> payload =
          roots.stream()
              .map(
                  root -> {
                    // Filesystem file count is the EXPENSIVE leg (a synchronous Files.walk per
                    // root) — kept behind ?counts=true so the live folder-status tick (tempdoc
                    // 599 §9.3) can refresh job counts without re-walking the disk.
                    long count = -1;
                    // Tempdoc 599 §16/A1 — "unavailable" derives from a PATH-MISSING walkError, NOT a
                    // Files stat on this (request) thread: U4 forbids a per-poll existence probe here
                    // (it blocks on dead UNC mounts). RootLifecycleOps re-checks existence on a
                    // background thread and surfaces a synthetic path-missing walkError, so this thread
                    // only reads the cheap in-memory signal. Reused on BOTH the live tick and the full
                    // ?counts=true fetch (the live tick must show "unavailable" too — it otherwise
                    // overwrote a counts=true result back to "indexed" within one tick).
                    String walkError = root.walkError() == null ? "" : root.walkError();
                    boolean rootMissing = isPathMissingError(walkError);
                    if (includeCounts && !rootMissing) {
                      // The EXPENSIVE Files.walk count stays gated behind ?counts=true (U6); bounded
                      // (tempdoc 599 Fix 3) so a huge watched root can't stall the request.
                      count = countFilesBounded(root.path(), MAX_COUNT_FILES).count();
                    }
                    // Per-folder job counts are CHEAP (a SQLite GROUP BY on the worker) — always
                    // returned so the row's truthful state derives from job drain (tempdoc 599 §9.2).
                    IndexingService.JobCounts jobCounts = IndexingService.JobCounts.zero();
                    try {
                      jobCounts = indexing.countJobsByPathPrefix(root.path());
                    } catch (Exception e) {
                      log.warn("Failed to count jobs under {}", root.path(), e);
                    }
                    String lastIndexedIsoTime =
                        root.lastIndexed() == null ? "" : root.lastIndexed().toString();
                    String status;
                    if (!walkError.isEmpty()) {
                      // Tempdoc 599 §16/A1 — a PATH-MISSING walk failure (folder deleted/unmounted, incl.
                      // the background-availability overlay) is the calmer "unavailable" (reconnect/remove),
                      // distinct from a hard "error".
                      status = rootMissing ? "unavailable" : "error";
                    } else if (!lastIndexedIsoTime.isEmpty()) {
                      status = "indexed";
                    } else {
                      status = "pending";
                    }
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("pathHash", sha256Hex(root.path().toString()));
                    m.put(
                        "collection",
                        root.collection() != null ? root.collection() : "default");
                    m.put("fileCount", count);
                    m.put("lastIndexedIsoTime", lastIndexedIsoTime);
                    m.put("status", status);
                    m.put("walkError", walkError);
                    m.put("inFlightCount", jobCounts.inFlight());
                    m.put("failedCount", jobCounts.failed());
                    // Tempdoc 599 Fix 1 — distinguishes "walked, nothing to index" (empty) from
                    // "walk in progress / never walked" (scanning) on the FE seam.
                    m.put("walkCompleted", root.walkCompleted());
                    // Tempdoc 626 §Axis-C — the latest reconcile could not verify index-vs-disk
                    // delete correspondence for this root (cap-skipped scan). The FE surfaces a
                    // per-root "couldn't verify — reindex to be sure" state instead of a false "✓".
                    m.put("deleteDetectionUnverified", root.deleteDetectionUnverified());
                    // Tempdoc 626 §Recency — the last reconcile that CONFIRMED index↔disk correspondence
                    // (distinct from lastIndexedIsoTime, the last write). The FE shows "Verified Xm ago"
                    // so a calm "✓" proves freshness and a cap-skipped root reads as visibly stale.
                    m.put(
                        "lastVerifiedIsoTime",
                        root.lastVerifiedAt() == null ? "" : root.lastVerifiedAt().toString());
                    // Tempdoc 813 §1c — per-root ENRICHMENT coverage. inFlightCount above covers
                    // only the first phase (keyword-searchable); enrichment backfill selects
                    // index-wide by status and carries no per-root job rows, so without these a
                    // 5%-embedded folder and a 100%-embedded one are indistinguishable. Parent
                    // totals exclude chunk docs; the chunk tier is its own denominator.
                    // Each parent stage carries its OWN denominator: a doc without that stage's
                    // status field is outside the stage (post-798), never a permanent deficit.
                    IndexingService.RootCoverage coverage = jobCounts.coverage();
                    m.put("parentDocsTotalEmbedding", coverage.parentDocsTotalEmbedding());
                    m.put("parentDocsSettledEmbedding", coverage.parentDocsSettledEmbedding());
                    m.put("parentDocsTotalSplade", coverage.parentDocsTotalSplade());
                    m.put("parentDocsSettledSplade", coverage.parentDocsSettledSplade());
                    m.put("parentDocsTotalNer", coverage.parentDocsTotalNer());
                    m.put("parentDocsSettledNer", coverage.parentDocsSettledNer());
                    m.put("chunkDocsTotal", coverage.chunkDocsTotal());
                    m.put("chunkDocsSettled", coverage.chunkDocsSettled());
                    return m;
                  })
              .toList();
      ctx.json(Map.of("items", payload, "count", payload.size()));
    } catch (Exception e) {
      log.error("Failed to list substrate-shaped indexed roots", e);
      ctx.status(500)
          .json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Tempdoc 599 §16/A1 — is this walk error a "folder path is gone" (deleted/unmounted) rather than a
   * structural failure? Such roots render as the calmer "unavailable" (reconnect/remove) instead of
   * "error". Matches the worker's {@code ROOT_NOT_DIRECTORY} terminal code + common OS missing-path
   * messages (case-insensitive).
   */
  private static boolean isPathMissingError(String walkError) {
    if (walkError == null) {
      return false;
    }
    String e = walkError.toLowerCase(java.util.Locale.ROOT);
    return e.contains("root_not_directory")
        || e.contains("no such file or directory")
        || e.contains("cannot find the path")
        || e.contains("system cannot find")
        || e.contains("path missing");
  }

  private static String sha256Hex(String value) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /**
   * The ONE projection of a worker-reported failed job onto the {@link IndexingJobView} wire record,
   * shared by {@link #handleListFailedJobsSubstrate} and {@link #handleListFailedJobsByPathPrefix}
   * (tempdoc 911). Both endpoints emitted a byte-identical hand-built map before; two copies of one
   * mapping is how {@code scanId} came to be dropped from both.
   *
   * <p>{@code retryAfterMs} is 0: these rows are terminal (no retry is scheduled), which is exactly
   * what "no backoff deadline" means on this record.
   *
   * <p>{@code scanId} is now the queue's own value, read end to end (tempdoc 911 §F closed): the
   * {@code listFailedJobs}/{@code listFailedJobsByPathPrefix} SELECTs project {@code scan_id}, the
   * proto {@code FailedJob} message carries it as field 7, {@code KnowledgeClient} passes it,
   * and {@code IndexingService.FailedJobInfo} holds it. So {@code ""} here means what {@link
   * IndexingJobView} says it means — single-file ingest, watcher, or a pre-{@code scan_id} row —
   * rather than "this surface never looked".
   *
   * <p>{@code collection} defaults on BLANK, not just on null (tempdoc 941 round 19, F2): proto3
   * has no null, so an untagged worker row arrives here as {@code ""} and the null-only check never
   * fired — the drawer rendered an empty collection for a job that is in fact in {@code default}.
   */
  private static IndexingJobView toJobView(IndexingService.FailedJobInfo j) {
    return new IndexingJobView(
        sha256Hex(j.path() == null ? "" : j.path()),
        j.state() == null || j.state().isBlank() ? IndexingJobView.STATE_FAILED : j.state(),
        j.attempts(),
        j.lastUpdatedMs(),
        j.errorMessage() == null ? "" : j.errorMessage(),
        0L,
        j.collection() == null || j.collection().isBlank() ? "default" : j.collection(),
        j.scanId() == null ? "" : j.scanId());
  }

  public void handleClearFailedJobs(Context ctx) {
    try {
      IndexingService indexing = indexingService();
      int deleted = indexing.clearFailedJobs();
      ctx.json(Map.of("status", "ok", "deletedCount", deleted));
    } catch (Exception e) {
      log.error("Failed to clear failed jobs", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Returns the most recent privacy-safe ingestion ledger events from the Worker (tempdoc 410
   * §12). Path identifiers are SHA-256 hashes only; raw paths never leave the Worker boundary.
   */
  public void handleRecentIngestionEvents(Context ctx) {
    try {
      if (!workerAvailable()) {
        ctx.status(503)
            .json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Indexing service unavailable", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      int limit = 100;
      String limitParam = ctx.queryParam("limit");
      if (limitParam != null) {
        try {
          limit = Integer.parseInt(limitParam);
        } catch (NumberFormatException expected) {
          // invalid query param, keep default
        }
      }
      IndexingService indexing = indexingService();
      List<Map<String, Object>> events = indexing.recentIngestionEvents(limit);
      ctx.json(Map.of("events", events, "count", events.size()));
    } catch (Exception e) {
      log.error("Failed to fetch recent ingestion events", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Returns aggregated ingestion outcome counts since the given epoch ms (tempdoc 410 §12).
   * Query param {@code since} (epoch ms) defaults to 0 (all retained events).
   */
  public void handleIngestionOutcomeSummary(Context ctx) {
    try {
      if (!workerAvailable()) {
        ctx.status(503)
            .json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Indexing service unavailable", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      long sinceMs = 0L;
      String sinceParam = ctx.queryParam("since");
      if (sinceParam != null) {
        try {
          sinceMs = Long.parseLong(sinceParam);
        } catch (NumberFormatException expected) {
          // invalid query param, keep default
        }
      }
      IndexingService indexing = indexingService();
      List<Map<String, Object>> rollups = indexing.ingestionOutcomeSummary(sinceMs);
      ctx.json(Map.of("rollups", rollups, "count", rollups.size()));
    } catch (Exception e) {
      log.error("Failed to fetch ingestion outcome summary", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Scoped reverse path-hash lookup (ADR-0028, tempdoc 419 T5.3). The ONLY handler allowed
   * to call {@code IndexingService.resolvePathHash}. The ArchUnit pin
   * {@code LibraryResolveHashOnlyCallerPin} (T5.4) enforces that no other endpoint
   * transitively reaches the {@code PathResolutionStore}.
   *
   * <p>Request body: {@code {"pathHash": "<64-char hex>"}}.
   *
   * <p>Response: {@code {"found": true|false, "path": "...", "lastSeenAtMs": ...,
   * "removedAtMs": ...}} (path/timestamps only when {@code found=true}).
   */
  public void handleResolvePathHash(Context ctx) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      Object hashValue = body == null ? null : body.get("pathHash");
      if (!(hashValue instanceof String) || ((String) hashValue).isBlank()) {
        ctx.status(400)
            .json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "pathHash is required", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      IndexingService indexing = indexingService();
      Map<String, Object> result = indexing.resolvePathHash((String) hashValue);
      ctx.json(result);
    } catch (Exception e) {
      log.error("Failed to resolve path hash", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  // §31 §9 Step 3: ExcludesService impl now lives in
  // io.justsearch.app.services.excludes.ExcludesServiceImpl. The HTTP handler
  // handleApplyExcludes above delegates to the injected ExcludesService reference
  // (the inline Files.walkFileTree implementation from slice 3a-2-c moved with it).
}
