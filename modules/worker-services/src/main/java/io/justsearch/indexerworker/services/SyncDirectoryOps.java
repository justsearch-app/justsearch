/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.IngestResponses.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.PruneOps;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.SyncDirectoryResponse;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sync-directory orchestration helper for {@link WorkerIngestService}.
 *
 * <p>Contains the prune-walk-commit pipeline: root validation, orphan pruning,
 * indexed-path scanning, disk walk with enqueue, and terminal-state handling.
 * Extracted to reduce the size of the service class.
 */
final class SyncDirectoryOps {
  private static final Logger log = LoggerFactory.getLogger(SyncDirectoryOps.class);

  private static final int SYNC_PRUNE_THROTTLE_BATCH_SIZE = 100;
  private static final int SYNC_WALK_THROTTLE_EVERY_N_FILES = 100;
  private static final int SYNC_ENQUEUE_BATCH_SIZE = 2_000;

  /**
   * Guardrail: max indexed paths for force=false "missing file" detection. Above this the
   * delete-detection scan is too expensive to run inline, so it is skipped — but tempdoc 626 §Axis-B
   * makes that skip NON-silent: the response now carries {@code delete_detection_unverified} so the
   * Head surfaces a per-root "couldn't verify" state rather than a false "✓ indexed" (the
   * unacceptable state being skip + look-healthy). Raising/streaming this cap is a future option; the
   * load-bearing fix is that the skip is observable, not that the cap is gone.
   */
  private static final int MAX_INDEXED_PATHS_FOR_MISSING_SCAN = 200_000;
  private static final int MAX_INDEXED_PATH_CHARS_FOR_MISSING_SCAN = 16 * 1024 * 1024;

  private static final boolean HAS_DOS_ATTRIBUTES =
      java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("dos");

  /** FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS — set on OneDrive cloud-only placeholders. */
  private static final int RECALL_ON_DATA_ACCESS = 0x00400000;

  private final ReadPathOps readPathOps;
  private final PruneOps pruneOps;
  private final CommitOps commitOps;
  private final JobQueue jobQueue;
  private final IndexingPacing indexingPacing;
  private final CloudPlaceholderRecorder cloudPlaceholderRecorder;
  private final ConfirmedDeletionMarker deletionMarker;
  private final int pathScanCap;
  private final int pathCharCap;

  SyncDirectoryOps(
      ReadPathOps readPathOps,
      PruneOps pruneOps,
      CommitOps commitOps,
      JobQueue jobQueue,
      IndexingPacing indexingPacing) {
    this(readPathOps, pruneOps, commitOps, jobQueue, indexingPacing, null);
  }

  SyncDirectoryOps(
      ReadPathOps readPathOps,
      PruneOps pruneOps,
      CommitOps commitOps,
      JobQueue jobQueue,
      IndexingPacing indexingPacing,
      ConfirmedDeletionMarker deletionMarker) {
    this(readPathOps, pruneOps, commitOps, jobQueue, indexingPacing, deletionMarker,
        MAX_INDEXED_PATHS_FOR_MISSING_SCAN);
  }

  SyncDirectoryOps(
      ReadPathOps readPathOps,
      PruneOps pruneOps,
      CommitOps commitOps,
      JobQueue jobQueue,
      IndexingPacing indexingPacing,
      ConfirmedDeletionMarker deletionMarker,
      int pathScanCap) {
    this(
        readPathOps, pruneOps, commitOps, jobQueue, indexingPacing, deletionMarker,
        pathScanCap, MAX_INDEXED_PATH_CHARS_FOR_MISSING_SCAN);
  }

  SyncDirectoryOps(
      ReadPathOps readPathOps,
      PruneOps pruneOps,
      CommitOps commitOps,
      JobQueue jobQueue,
      IndexingPacing indexingPacing,
      ConfirmedDeletionMarker deletionMarker,
      int pathScanCap,
      int pathCharCap) {
    if (pathScanCap <= 0) throw new IllegalArgumentException("pathScanCap must be positive");
    if (pathCharCap <= 0) throw new IllegalArgumentException("pathCharCap must be positive");
    this.readPathOps = readPathOps;
    this.pruneOps = pruneOps;
    this.commitOps = commitOps;
    this.jobQueue = jobQueue;
    this.indexingPacing = indexingPacing;
    this.cloudPlaceholderRecorder = jobQueue == null ? null : new CloudPlaceholderRecorder(jobQueue);
    this.deletionMarker =
        deletionMarker != null
            ? deletionMarker
            : new ConfirmedDeletionMarker(
                io.justsearch.indexerworker.identity.DocumentIdentityStore.UNAVAILABLE);
    this.pathScanCap = pathScanCap;
    this.pathCharCap = pathCharCap;
  }

  /** A complete, bounded serving-index/disk difference. No queue or Lucene mutation occurs here. */
  record RootDifference(
      List<Path> additions,
      List<String> deletions,
      RootIdentity rootIdentity,
      RootIdentityLease identityLease)
      implements AutoCloseable {
    void requireCurrentRootIdentity() throws IOException {
      identityLease.requireCurrent();
    }

    @Override
    public void close() throws IOException {
      identityLease.close();
    }
  }

  /**
   * Discovers a root difference for candidate-scoped admission.
   *
   * <p>Unlike ordinary maintenance sync, every incomplete observation throws. The caller cannot
   * certify candidate replay from an unreadable index, an inaccessible walk member, interruption,
   * or a capped result. Deletions come only from a separate, confirmed-absence check over A's
   * indexed paths, so a cloud placeholder or excluded subtree is never mistaken for a deletion.
   * Force mode admits every eligible disk file while retaining the same strict A scan and deletion
   * proof.
   */
  RootDifference discoverCandidateDifference(
      String rootPath, boolean force, java.util.function.BooleanSupplier cancelled)
      throws IOException {
    checkCancelled(cancelled);
    Path root;
    try {
      root = Path.of(rootPath).toAbsolutePath().normalize();
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid reconciliation root", invalid);
    }
    RootIdentityLease identityLease = RootIdentity.hold(root);
    try {
      identityLease.requireCurrent();
      var budget = new PathBudget(pathCharCap);
      Set<String> indexedPaths = getIndexedPathsUnderPrefixStrict(root.toString(), budget, cancelled);
      Set<String> seenDiskPaths = new HashSet<>();
      List<Path> eligibleFiles = new ArrayList<>();
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
              checkCancelled(cancelled);
              if (!Files.isReadable(dir)) {
                throw new IOException("Reconciliation directory is unreadable: " + dir);
              }
              if (!dir.equals(root)) {
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (IngestionSkipPolicy.isSkippedDirectoryName(name)) {
                  return FileVisitResult.SKIP_SUBTREE;
                }
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              checkCancelled(cancelled);
              if (attrs.isSymbolicLink()) {
                throw new IOException("Reconciliation does not follow symbolic links: " + file);
              }
              if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
              String normalized = PathNormalizer.normalizeKey(file);
              if (!seenDiskPaths.add(normalized)) return FileVisitResult.CONTINUE;
              budget.retain(normalized.length());
              requireBelowCap(seenDiskPaths.size(), "disk");
              if (!Files.isReadable(file)) {
                throw new IOException("Reconciliation file is unreadable: " + file);
              }
              if (!IngestionSkipPolicy.shouldSkip(file) && !isCloudPlaceholder(file)) {
                // Conservatively account for the Path's spelling and component storage as well
                // as the separately retained normalized disk key.
                budget.retain(2L * file.toString().length());
                eligibleFiles.add(file.toAbsolutePath().normalize());
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure)
                throws IOException {
              checkCancelled(cancelled);
              throw new IOException("Reconciliation walk could not read: " + file, failure);
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure)
                throws IOException {
              if (failure != null) {
                throw new IOException("Reconciliation walk was incomplete at: " + dir, failure);
              }
              checkCancelled(cancelled);
              return FileVisitResult.CONTINUE;
            }
          });

      List<Path> additions = new ArrayList<>();
      for (Path file : eligibleFiles) {
        checkCancelled(cancelled);
        if (force || !indexedPaths.contains(PathNormalizer.normalizeKey(file))) additions.add(file);
      }
      additions.sort(
          (first, second) -> {
            checkCancelledUnchecked(cancelled);
            return PathNormalizer.normalizeKey(first).compareTo(PathNormalizer.normalizeKey(second));
          });
      List<String> deletions = confirmedAbsentPaths(indexedPaths, cancelled);
      checkCancelled(cancelled);
      identityLease.requireCurrent();
      RootDifference difference =
          new RootDifference(
              List.copyOf(additions), deletions, identityLease.identity(), identityLease);
      return difference;
    } catch (IOException | RuntimeException | Error failure) {
      try {
        identityLease.close();
      } catch (IOException cleanup) {
        if (failure instanceof java.io.UncheckedIOException unchecked) {
          unchecked.getCause().addSuppressed(cleanup);
        } else {
          failure.addSuppressed(cleanup);
        }
      }
      if (failure instanceof java.io.UncheckedIOException unchecked) throw unchecked.getCause();
      throw failure;
    }
  }

  private static List<String> confirmedAbsentPaths(
      Set<String> indexedPaths, java.util.function.BooleanSupplier cancelled) throws IOException {
    List<String> absent = new ArrayList<>();
    for (String indexedPath : indexedPaths) {
      checkCancelled(cancelled);
      Path path;
      try {
        path = Path.of(indexedPath);
      } catch (RuntimeException invalid) {
        throw new IOException("Serving index contains an invalid path", invalid);
      }
      if (Files.notExists(path)) {
        absent.add(indexedPath);
      } else if (!Files.exists(path)) {
        throw new IOException("Could not establish indexed path presence: " + indexedPath);
      }
    }
    absent.sort(
        (first, second) -> {
          checkCancelledUnchecked(cancelled);
          return first.compareTo(second);
        });
    checkCancelled(cancelled);
    return List.copyOf(absent);
  }

  private Set<String> getIndexedPathsUnderPrefixStrict(
      String prefix, PathBudget budget,
      java.util.function.BooleanSupplier cancelled) throws IOException {
    if (readPathOps == null) throw new IOException("Serving index read path is unavailable");
    Set<String> paths = new LinkedHashSet<>();
    Set<String> cursors = new HashSet<>();
    try {
      String normalizedPrefix = PathNormalizer.normalizePathPrefix(prefix);
      var prefixQuery = new org.apache.lucene.search.PrefixQuery(
          new org.apache.lucene.index.Term(SchemaFields.PATH, normalizedPrefix));
      var query = new org.apache.lucene.search.BooleanQuery.Builder()
          .add(prefixQuery, org.apache.lucene.search.BooleanClause.Occur.MUST)
          .add(new org.apache.lucene.search.TermQuery(
                  new org.apache.lucene.index.Term(SchemaFields.IS_CHUNK, "true")),
              org.apache.lucene.search.BooleanClause.Occur.MUST_NOT)
          .build();
      Path root = Path.of(prefix).toAbsolutePath().normalize();
      String normalizedRootPrefix = PathNormalizer.normalizePathPrefix(root.toString());
      String cursor = null;
      do {
        checkCancelled(cancelled);
        var result = readPathOps.search(query, 256,
            Set.of(SchemaFields.DOC_ID, SchemaFields.PATH, SchemaFields.PROJECTION_SOURCE_ID),
            LuceneRuntimeTypes.RuntimeSearchSort.PATH_ASC, cursor);
        if (result == null) throw new IOException("Serving index path scan returned no result");
        if (result.totalHits() > pathScanCap) {
          throw new IOException("Serving index path scan exceeded cap " + pathScanCap);
        }
        for (var hit : result.hits()) {
          checkCancelled(cancelled);
          // Projection source id is intentionally stored-only, so it cannot participate in the
          // Lucene query. Project it and exclude those legal no-file records before interpreting
          // DOC_ID as a filesystem path.
          if (hit.fields().get(SchemaFields.PROJECTION_SOURCE_ID) != null) {
            continue;
          }
          if (hit.docId() == null || hit.docId().isBlank()) {
            throw new IOException("Serving index path scan returned a missing document id");
          }
          String storedPath = hit.fields().get(SchemaFields.PATH);
          String canonical = canonicalFileDocumentId(hit.docId(), root, normalizedRootPrefix);
          if (!canonical.equals(storedPath)) {
            throw new IOException("Serving file document path metadata does not match its id");
          }
          if (paths.add(canonical)) budget.retain(canonical.length());
          requireBelowCap(paths.size(), "index");
        }
        cursor = result.nextCursor();
        if (cursor != null && !cursor.isBlank() && !cursors.add(cursor)) {
          throw new IOException("Serving index path scan repeated its cursor");
        }
        if (cursor != null && !cursor.isBlank()) {
          requireBelowCap(cursors.size(), "cursor");
          budget.retain(cursor.length());
        }
      } while (cursor != null && !cursor.isBlank());
      checkCancelled(cancelled);
      return paths;
    } catch (IOException incomplete) {
      throw incomplete;
    } catch (RuntimeException incomplete) {
      throw new IOException("Serving index path scan failed", incomplete);
    }
  }

  private static String canonicalFileDocumentId(
      String documentId, Path root, String normalizedRootPrefix) throws IOException {
    Path path;
    try {
      path = Path.of(documentId);
    } catch (RuntimeException invalid) {
      throw new IOException("Serving file document has an invalid path id", invalid);
    }
    String canonical = PathNormalizer.normalizeKey(path);
    if (!path.isAbsolute()
        || !canonical.equals(documentId)
        || !path.toAbsolutePath().normalize().startsWith(root)
        || !canonical.startsWith(normalizedRootPrefix)) {
      throw new IOException("Serving file document id is not canonical under the root");
    }
    return canonical;
  }

  private void requireBelowCap(int size, String source) throws IOException {
    if (size >= pathScanCap) {
      throw new IOException("Reconciliation " + source + " path scan reached cap " + pathScanCap);
    }
  }

  private static void checkInterrupted() throws IOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new IOException("Reconciliation walk was interrupted");
    }
  }

  static void checkCancelled(java.util.function.BooleanSupplier cancelled) throws IOException {
    checkInterrupted();
    if (cancelled.getAsBoolean()) throw new IOException("Reconciliation was cancelled");
  }

  private static void checkCancelledUnchecked(java.util.function.BooleanSupplier cancelled) {
    try {
      checkCancelled(cancelled);
    } catch (IOException incomplete) {
      throw new java.io.UncheckedIOException(incomplete);
    }
  }

  private static final class PathBudget {
    private final long maxChars;
    private long retainedChars;

    PathBudget(long maxChars) {
      this.maxChars = maxChars;
    }

    void retain(long chars) throws IOException {
      if (chars > maxChars - retainedChars) {
        throw new IOException("Reconciliation retained path characters exceeded cap " + maxChars);
      }
      retainedChars += chars;
    }
  }

  /**
   * Executes the full sync-directory pipeline: validate root, prune orphans, walk disk,
   * enqueue missing files, commit, and return the terminal response.
   *
   * <p>Lane F stage A item A3: every outcome here — including an invalid root, a prune abort, a
   * skipped delete-detection scan and an interrupted walk — is carried in the response, never as a
   * transport status. So the conversion is a pure return-instead-of-onNext and this class throws
   * no {@link WorkerServiceException}. The phase helpers below return the terminal response, or
   * {@code null} for "no terminal state, continue".
   */
  SyncDirectoryResponse execute(String rootPath, boolean force,
      JobQueue.EnqueueProvenance provenance, java.util.function.BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean()) return syncDirectoryErrorResponse("Cancelled");
    Path root = resolveSyncRoot(rootPath);
    if (root == null) {
      return syncDirectoryErrorResponse("Root path does not exist or is not a directory");
    }

    int filesAdded = 0;
    int filesDeleted = 0;

    try {
      // STEP 1: Prune orphans (reuse existing logic with throttle + abort)
      int pruneResult = pruneOrphansForSync(rootPath, force, cancelled);

      SyncDirectoryResponse pruneAbort = handleSyncPruneAbortIfNeeded(pruneResult, force);
      if (pruneAbort != null) {
        return pruneAbort;
      }
      filesDeleted = Math.max(0, pruneResult);

      // STEP 2: For force=true (OVERFLOW/burst), we do not attempt to compute the full indexed
      // set (can be large). We enqueue all disk files and rely on IndexingLoop's "unchanged"
      // fast-path.
      if (cancelled.getAsBoolean()) return syncDirectoryErrorResponse(filesDeleted, 0, "Cancelled");
      Set<String> indexedPaths = indexedPathsForSync(rootPath, force, cancelled);
      if (cancelled.getAsBoolean()) return syncDirectoryErrorResponse(filesDeleted, 0, "Cancelled");
      SyncDirectoryResponse scanSkip =
          handleSyncIndexedPathsScanSkipIfNeeded(rootPath, force, indexedPaths, filesDeleted);
      if (scanSkip != null) {
        return scanSkip;
      }
      if (!force) {
        log.debug("syncDirectory: found {} indexed paths under {}", indexedPaths.size(), rootPath);
      }

      // STEP 3: Walk disk and find missing files
      SyncWalkPhaseResult walk =
          walkAndEnqueueMissingFiles(root, force, indexedPaths, provenance, cancelled);
      filesAdded = walk.filesAdded();

      if (cancelled.getAsBoolean()) {
        return syncDirectoryErrorResponse(filesDeleted, filesAdded, "Cancelled");
      }

      SyncDirectoryResponse walkTerminal =
          handleSyncWalkTerminalState(walk, filesDeleted, filesAdded);
      if (walkTerminal != null) {
        return walkTerminal;
      }

      // Commit after deletions
      if (filesDeleted > 0 && commitOps != null) {
        commitOps.commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.SYNC_PRUNE);
      }

      log.info(
          "syncDirectory complete for {}: {} deleted, {} added",
          rootPath,
          filesDeleted,
          filesAdded);
      return syncDirectoryResultResponse(filesDeleted, filesAdded);

    } catch (Exception e) {
      log.error("syncDirectory failed for {}", rootPath, e);
      return syncDirectoryErrorResponse(filesDeleted, filesAdded, e.getMessage());
    }
  }

  // ==================== Phase helpers ====================

  private SyncDirectoryResponse handleSyncPruneAbortIfNeeded(int pruneResult, boolean force) {
    if (pruneResult < 0 && !force) {
      log.info("syncDirectory aborted during prune phase (user activity)");
      return syncDirectorySkippedResponse();
    }
    return null;
  }

  private SyncDirectoryResponse handleSyncIndexedPathsScanSkipIfNeeded(
      String rootPath, boolean force, Set<String> indexedPaths, int filesDeleted) {
    if (force || indexedPaths != null) {
      return null;
    }
    log.warn(
        "syncDirectory: skipping missing-file detection for {} "
            + "(indexed path budget exceeded; still pruned {} orphans); marking delete-detection UNVERIFIED",
        rootPath,
        filesDeleted);
    // Tempdoc 626 §Axis-B/C — surface the skip instead of returning a silent skipped/healthy result.
    return syncDirectoryDeleteUnverifiedResponse(filesDeleted, 0);
  }

  private SyncDirectoryResponse handleSyncWalkTerminalState(
      SyncWalkPhaseResult walk, int filesDeleted, int filesAdded) {
    if (walk.walkInterrupted()) {
      Thread.currentThread().interrupt();
      return syncDirectoryErrorResponse(filesDeleted, filesAdded, "Interrupted");
    }
    return null;
  }

  /** The validated sync root, or {@code null} when it does not exist or is not a directory. */
  private Path resolveSyncRoot(String rootPath) {
    Path root = Path.of(rootPath);
    if (!Files.exists(root) || !Files.isDirectory(root)) {
      log.warn("syncDirectory: root does not exist or is not a directory: {}", rootPath);
      return null;
    }
    return root;
  }

  private int pruneOrphansForSync(String rootPath, boolean force,
      java.util.function.BooleanSupplier cancelled) {
    if (pruneOps == null) {
      return 0;
    }
    // Tempdoc 599 §16/A1 (data-loss guard) — if the watched root itself is gone (unmounted /
    // disconnected drive), DO NOT prune: every file under it would read as "missing" and its whole
    // index would be silently deleted. Skip; a later sync re-prunes once the root is back. (Worker
    // background thread, so the existence check's latency on a dead mount is tolerable.)
    if (!Files.isDirectory(Path.of(rootPath))) {
      log.warn(
          "pruneOrphansForSync: root unavailable (likely unmounted), skipping prune to avoid"
              + " deleting the folder's index: {}",
          rootPath);
      return 0;
    }
    // Tempdoc 885 item 3: the throttle callback the prune already invokes every N documents is now
    // the pacing tick. It always returns false — under a duty cycle the prune is slowed, never
    // abandoned half-done as it was when user activity aborted it.
    // Tempdoc 931 §C.6 — a confirmed deletion point: the prune removes a document precisely because
    // its backing file no longer exists on disk (PruneOps' own Files.exists check), and the guard
    // above has already refused to run at all when the whole root is unavailable.
    return pruneOps.pruneByPathPrefix(
        rootPath,
        () -> cancelled.getAsBoolean() || (!force && indexingPacing.paceAndContinue()),
        SYNC_PRUNE_THROTTLE_BATCH_SIZE,
        deletionMarker::markIfAbsent);
  }

  private Set<String> indexedPathsForSync(String rootPath, boolean force,
      java.util.function.BooleanSupplier cancelled) {
    if (force) {
      return null;
    }
    return getIndexedPathsUnderPrefix(rootPath, cancelled);
  }

  // ==================== Walk logic ====================

  record SyncWalkPhaseResult(int filesAdded, boolean walkInterrupted) {}

  @SuppressWarnings("PMD.CognitiveComplexity")
  private SyncWalkPhaseResult walkAndEnqueueMissingFiles(
      Path root, boolean force, Set<String> indexedPaths,
      JobQueue.EnqueueProvenance provenance, java.util.function.BooleanSupplier cancelled)
      throws IOException {
    // Preserve globally deterministic enqueue order without retaining the root in shared heap.
    // The spool maintains its ordering index on disk while the walk streams one path at a time.
    try (var spool = new ReconciliationSpool()) {
      int[] counters = {0}; // [0]=fileCount (for throttle)
      boolean[] walkInterrupted = {false};
      final Set<String> indexedPathsFinal = indexedPaths;

      Files.walkFileTree(
          root,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              if (stopped()) return FileVisitResult.TERMINATE;
              if (dir.equals(root)) return FileVisitResult.CONTINUE;
              String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
              if (IngestionSkipPolicy.isSkippedDirectoryName(name)) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
              if (stopped()) return FileVisitResult.TERMINATE;
              if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
              if (!Files.isReadable(file)) return FileVisitResult.CONTINUE;
              if (IngestionSkipPolicy.shouldSkipWithinRoot(file, root)) {
                return FileVisitResult.CONTINUE;
              }
              if (isCloudPlaceholder(file)) {
                recordCloudPlaceholderObservation(file, provenance);
                return FileVisitResult.CONTINUE;
              }

              counters[0]++;

              // Throttle: check abort + yield periodically.
              if (counters[0] % SYNC_WALK_THROTTLE_EVERY_N_FILES == 0) {
                if (Thread.interrupted()) {
                  log.info("syncDirectory interrupted after {} files", counters[0]);
                  walkInterrupted[0] = true;
                  return FileVisitResult.TERMINATE;
                }
                // Tempdoc 885 item 3: the walk yields to foreground load instead of terminating on
                // it. The 1 ms courtesy sleep stays for the uncontended case.
                if (!force) {
                  indexingPacing.pace();
                }
                try {
                  Thread.sleep(1);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  walkInterrupted[0] = true;
                  return FileVisitResult.TERMINATE;
                }
              }

              String normalizedPath =
                  PathNormalizer.normalizePath(file.toAbsolutePath().toString());
              if (force
                  || (indexedPathsFinal != null && !indexedPathsFinal.contains(normalizedPath))) {
                // 813 Slice B: the walk already holds the size — no extra stat.
                if (stopped()) return FileVisitResult.TERMINATE;
                spool.add(file, attrs.size());
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
              if (stopped()) return FileVisitResult.TERMINATE;
              log.debug(
                  "Skipping inaccessible path: {} ({})",
                  file,
                  exc != null ? exc.getMessage() : "unknown");
              return FileVisitResult.CONTINUE;
            }

            private boolean stopped() {
              if (Thread.currentThread().isInterrupted()) walkInterrupted[0] = true;
              return walkInterrupted[0] || cancelled.getAsBoolean();
            }
          });

      int filesAdded = spool.drain(SYNC_ENQUEUE_BATCH_SIZE, provenance,
          () -> cancelled.getAsBoolean() || walkInterrupted[0]
              || Thread.currentThread().isInterrupted(), entries -> {
            // Recheck scoped policy at admission while retaining the spool's bounded batches.
            var admitted = entries.stream()
                .filter(entry -> !IngestionSkipPolicy.shouldSkipWithinRoot(entry.path(), root))
                .map(entry -> entry.withinRoot(root))
                .toList();
            return admitted.isEmpty() ? 0 : jobQueue.enqueueEntries(admitted);
          });
      walkInterrupted[0] |= Thread.currentThread().isInterrupted();
      if (!walkInterrupted[0] && !cancelled.getAsBoolean() && filesAdded > 0) {
        log.info("syncDirectory: enqueued {} missing files for indexing", filesAdded);
      }
      return new SyncWalkPhaseResult(filesAdded, walkInterrupted[0]);
    }
  }

  // ==================== Index path query ====================

  /**
   * Returns all indexed document paths under the given prefix.
   *
   * <p>Used by syncDirectory to determine which files are missing from the index. Uses
   * field-based filtering (is_chunk != true) to exclude chunk documents rather than string
   * matching on doc_id, which can misclassify legitimate paths.
   */
  private Set<String> getIndexedPathsUnderPrefix(String prefix,
      java.util.function.BooleanSupplier cancelled) {
    Set<String> paths = new HashSet<>();
    if (readPathOps == null) {
      return paths;
    }

    try {
      String normalizedPrefix = PathNormalizer.normalizePath(prefix);

      var prefixQuery =
          new org.apache.lucene.search.PrefixQuery(
              new org.apache.lucene.index.Term(SchemaFields.PATH, normalizedPrefix));
      var query =
          new org.apache.lucene.search.BooleanQuery.Builder()
              .add(prefixQuery, org.apache.lucene.search.BooleanClause.Occur.MUST)
              .add(
                  new org.apache.lucene.search.TermQuery(
                      new org.apache.lucene.index.Term(SchemaFields.IS_CHUNK, "true")),
                  org.apache.lucene.search.BooleanClause.Occur.MUST_NOT)
              .build();

      String cursor = null;
      final int batchSize = 256;
      long pathChars = 0;
      while (true) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return null;
        var result =
            readPathOps.search(
                query,
                batchSize,
                Set.of(SchemaFields.DOC_ID),
                LuceneRuntimeTypes.RuntimeSearchSort.PATH_ASC,
                cursor);

        for (var hit : result.hits()) {
          if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return null;
          String path = hit.docId();
          if (path != null) {
            if (paths.add(path)) pathChars += path.length();
            if (paths.size() >= MAX_INDEXED_PATHS_FOR_MISSING_SCAN
                || pathChars >= MAX_INDEXED_PATH_CHARS_FOR_MISSING_SCAN) {
              return null;
            }
          }
        }

        cursor = result.nextCursor();
        if (cursor == null || cursor.isBlank()) {
          break;
        }
      }
    } catch (Exception e) {
      log.warn("Failed to get indexed paths for prefix {}", prefix, e);
    }

    return paths;
  }

  // ==================== Platform helper ====================

  /**
   * Detects OneDrive Files-on-Demand cloud-only placeholder files. Reading these triggers a
   * network download; skip them during indexing.
   */
  static boolean isCloudPlaceholder(Path file) {
    if (!HAS_DOS_ATTRIBUTES) return false;
    try {
      int attrs = (int) Files.getAttribute(file, "dos:attributes");
      return (attrs & RECALL_ON_DATA_ACCESS) != 0;
    } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
      // A file whose Windows cloud-placeholder attribute cannot be read is not a placeholder.
      // On some non-Windows JDKs the default FS advertises a "dos" view (so HAS_DOS_ATTRIBUTES
      // is true) yet rejects the raw "attributes" name with IllegalArgumentException
      // ('attributes' not recognized) — treat that as "not a placeholder", same as the other
      // unreadable cases. Keeps the worker's scan cross-platform (tempdoc 668). No-op on Windows,
      // where the attribute is always readable.
      return false;
    }
  }

  /** Delegates the observation and its admission attribution to the shared recorder. */
  void recordCloudPlaceholderObservation(
      Path file, JobQueue.EnqueueProvenance provenance) {
    if (cloudPlaceholderRecorder == null) {
      return;
    }
    if (provenance == null) {
      cloudPlaceholderRecorder.record(file);
    } else {
      cloudPlaceholderRecorder.record(file, null, provenance);
    }
  }
}
