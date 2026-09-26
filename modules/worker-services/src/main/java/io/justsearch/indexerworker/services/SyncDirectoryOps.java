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
import java.util.Comparator;
import java.util.HashSet;
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
  SyncDirectoryResponse execute(
      String rootPath, boolean force, JobQueue.EnqueueProvenance provenance) {
    Path root = resolveSyncRoot(rootPath);
    if (root == null) {
      return syncDirectoryErrorResponse("Root path does not exist or is not a directory");
    }

    int filesAdded = 0;
    int filesDeleted = 0;

    try {
      // STEP 1: Prune orphans (reuse existing logic with throttle + abort)
      int pruneResult = pruneOrphansForSync(rootPath, force);

      SyncDirectoryResponse pruneAbort = handleSyncPruneAbortIfNeeded(pruneResult, force);
      if (pruneAbort != null) {
        return pruneAbort;
      }
      filesDeleted = Math.max(0, pruneResult);

      // STEP 2: For force=true (OVERFLOW/burst), we do not attempt to compute the full indexed
      // set (can be large). We enqueue all disk files and rely on IndexingLoop's "unchanged"
      // fast-path.
      Set<String> indexedPaths = indexedPathsForSync(rootPath, force);
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
          walkAndEnqueueMissingFiles(root, force, indexedPaths, provenance);
      filesAdded = walk.filesAdded();

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
            + "(too many indexed paths; still pruned {} orphans) — marking delete-detection UNVERIFIED",
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

  private int pruneOrphansForSync(String rootPath, boolean force) {
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
        force ? () -> false : indexingPacing::paceAndContinue,
        SYNC_PRUNE_THROTTLE_BATCH_SIZE,
        deletionMarker::markIfAbsent);
  }

  private Set<String> indexedPathsForSync(String rootPath, boolean force) {
    if (force) {
      return null;
    }
    return getIndexedPathsUnderPrefix(rootPath);
  }

  // ==================== Walk logic ====================

  record SyncWalkPhaseResult(int filesAdded, boolean walkInterrupted) {}

  @SuppressWarnings("PMD.CognitiveComplexity")
  private SyncWalkPhaseResult walkAndEnqueueMissingFiles(
      Path root, boolean force, Set<String> indexedPaths,
      JobQueue.EnqueueProvenance provenance) throws IOException {
    // 391/E-J-N12: collect the full list first, sort by absolute path, then
    // enqueue in deterministic batches. Filesystem-order enumeration varies
    // across runs of the same unchanged corpus (NTFS MFT state, OS cache),
    // which causes non-deterministic chunk-boundary placement downstream
    // (ner_total observed to vary 5300-7300 across back-to-back scifact runs).
    // Sorting up-front costs O(n log n) and ~200 B per path — ~1 MB extra
    // for 5K files; negligible for benchmarking corpora.
    //
    // Scalability trade-off (tempdoc 393 § 3.4): collect-all-then-sort delays
    // the first enqueue by the walk-complete time, losing pipelining with the
    // indexing loop. At ~5K-10K files this is invisible (<1 s walk). At ~100K
    // files the walk can run 10-30 s and the loop sits idle during it. If a
    // user-facing workload ever pushes past ~50K files per sync, switch to a
    // streaming approach: hash-bucket the path space and sort per-bucket so
    // enqueue can start after the first bucket settles. The current approach
    // is intentional for determinism; this note exists so the next agent
    // doesn't rediscover the trade-off by accident.
    List<JobQueue.EnqueueEntry> collected = new ArrayList<>();
    int[] counters = {0}; // [0]=fileCount (for throttle)
    boolean[] walkInterrupted = {false};
    final Set<String> indexedPathsFinal = indexedPaths;

    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (dir.equals(root)) return FileVisitResult.CONTINUE;
            String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
            if (IngestionSkipPolicy.isSkippedDirectoryName(name)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
            if (!Files.isReadable(file)) return FileVisitResult.CONTINUE;
            if (IngestionSkipPolicy.shouldSkip(file)) return FileVisitResult.CONTINUE;
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
              collected.add(new JobQueue.EnqueueEntry(file, attrs.size(), provenance));
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            log.debug(
                "Skipping inaccessible path: {} ({})",
                file,
                exc != null ? exc.getMessage() : "unknown");
            return FileVisitResult.CONTINUE;
          }
        });

    // Sort once, after the walk, for a stable enqueue order across runs.
    // Uses the normalized absolute path string so the ordering is independent
    // of the Path implementation's natural ordering (which could change
    // between JDKs).
    collected.sort(
        Comparator.comparing(
            e -> PathNormalizer.normalizePath(e.path().toAbsolutePath().toString())));

    int filesAdded = 0;
    List<JobQueue.EnqueueEntry> batch = new ArrayList<>(SYNC_ENQUEUE_BATCH_SIZE);
    for (JobQueue.EnqueueEntry entry : collected) {
      batch.add(entry);
      if (batch.size() >= SYNC_ENQUEUE_BATCH_SIZE) {
        filesAdded += jobQueue.enqueueEntries(batch);
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      filesAdded += jobQueue.enqueueEntries(batch);
    }
    if (!walkInterrupted[0] && filesAdded > 0) {
      log.info("syncDirectory: enqueued {} missing files for indexing", filesAdded);
    }
    return new SyncWalkPhaseResult(filesAdded, walkInterrupted[0]);
  }

  // ==================== Index path query ====================

  /**
   * Returns all indexed document paths under the given prefix.
   *
   * <p>Used by syncDirectory to determine which files are missing from the index. Uses
   * field-based filtering (is_chunk != true) to exclude chunk documents rather than string
   * matching on doc_id, which can misclassify legitimate paths.
   */
  private Set<String> getIndexedPathsUnderPrefix(String prefix) {
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
      final int batchSize = 10_000;
      while (true) {
        var result =
            readPathOps.search(
                query,
                batchSize,
                Set.of(SchemaFields.DOC_ID),
                LuceneRuntimeTypes.RuntimeSearchSort.PATH_ASC,
                cursor);

        for (var hit : result.hits()) {
          String path = hit.docId();
          if (path != null) {
            paths.add(path);
            if (paths.size() >= MAX_INDEXED_PATHS_FOR_MISSING_SCAN) {
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
