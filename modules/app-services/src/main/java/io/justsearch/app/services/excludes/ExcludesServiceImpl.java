/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.excludes;

import io.justsearch.core.context.EngineContext;
import io.justsearch.app.api.ExcludesService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.indexing.ExcludeGlobs;
import io.justsearch.configuration.resolved.ConfigStore;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Walks the resolved exclusion policy on disk; all index deletion is delegated to the Worker. */
public final class ExcludesServiceImpl implements ExcludesService {
  private static final int MAX_WALK_ENTRIES = 500_000;
  private final Supplier<IndexingService> indexingServiceSupplier;
  private final int maxWalkEntries;

  public ExcludesServiceImpl(Supplier<IndexingService> indexingServiceSupplier) {
    this(indexingServiceSupplier, MAX_WALK_ENTRIES);
  }

  ExcludesServiceImpl(Supplier<IndexingService> indexingServiceSupplier, int maxWalkEntries) {
    this.indexingServiceSupplier = Objects.requireNonNull(indexingServiceSupplier, "indexingServiceSupplier");
    if (maxWalkEntries < 1) throw new IllegalArgumentException("Walk budget must be positive");
    this.maxWalkEntries = maxWalkEntries;
  }

  @Override
  public ExcludesPolicy effectivePolicy() {
    return capturePolicy().projection();
  }

  private record PolicySnapshot(ExcludesPolicy projection, ExcludeGlobs matcher) {}

  private PolicySnapshot capturePolicy() {
    ConfigStore store = ConfigStore.globalOrNull();
    if (store == null) return new PolicySnapshot(
        new ExcludesPolicy("default", null, List.of()), ExcludeGlobs.empty());
    var config = store.get();
    var source = config.resolution("justsearch.ui.exclude_patterns");
    ExcludeGlobs matcher = ExcludeGlobs.fromRawJsonArray(config.ui().excludePatterns());
    return new PolicySnapshot(new ExcludesPolicy(source == null ? "unknown" : source.sourceName(),
        source == null ? null : source.sourceDetail(),
        matcher.patterns()), matcher);
  }

  @Override
  public ExcludesResult applyExcludes(boolean dryRun, EngineContext engineContext) throws Exception {
    IndexingService indexing = indexingServiceSupplier.get();
    // Read patterns and winning-source trace from the same resolved snapshot.
    PolicySnapshot snapshot = capturePolicy();
    ExcludesPolicy policy = snapshot.projection();
    ExcludeGlobs excludes = snapshot.matcher();
    List<String> effectivePatterns = excludes.patterns();
    if (excludes.isEmpty()) {
      return new ExcludesResult(dryRun, 0, 0, 0, 0, 0, List.of(), false,
          "No exclude patterns configured", 0, List.of(), policy);
    }

    int[] perPatternCount = new int[effectivePatterns.size()];
    int rootsProcessed = 0;
    AtomicInteger deletedByPathJobs = new AtomicInteger();
    AtomicInteger deletedById = new AtomicInteger();
    AtomicInteger confirmedRemovals = new AtomicInteger();
    AtomicInteger matchedFiles = new AtomicInteger();
    AtomicInteger visited = new AtomicInteger();
    List<ExcludesResult.UncheckedScope> unchecked = new ArrayList<>();

    List<IndexingService.WatchedRoot> watched = indexing.getWatchedRoots(engineContext);
    for (IndexingService.WatchedRoot root : watched) {
      if (root == null || root.path() == null) {
        unchecked.add(new ExcludesResult.UncheckedScope("Unknown watched folder", "not_checked", "Watched path unavailable"));
        continue;
      }
      Path rootPath = root.path().toAbsolutePath().normalize();
      if (visited.get() > maxWalkEntries) {
        unchecked.add(scope(rootPath, "not_checked", "Entry cap reached; folder not checked"));
        continue;
      }
      if (!Files.isDirectory(rootPath)) {
        unchecked.add(scope(rootPath, "not_checked", "Watched folder is offline or missing, or is no longer a folder"));
        continue;
      }
      rootsProcessed++;
      // A failed prefix covers its unattempted descendants too. Retry the entire scope next run.
      List<Path> attemptedPrefixes = new ArrayList<>();
      try {
        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
          private boolean withinBudget() {
            if (visited.incrementAndGet() <= maxWalkEntries) return true;
            unchecked.add(scope(rootPath, "not_checked", "Entry cap reached; remaining entries in this folder not checked"));
            return false;
          }

          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (!withinBudget()) return FileVisitResult.TERMINATE;
            if (!Files.isReadable(dir)) {
              unchecked.add(scope(dir, "not_checked", "Unreadable folder"));
              return FileVisitResult.SKIP_SUBTREE;
            }
            if (!dir.equals(rootPath) && excludes.isExcludedDirectory(rootPath, dir) && !dryRun
                && attemptedPrefixes.stream().noneMatch(dir::startsWith)) {
              attemptedPrefixes.add(dir);
              try {
                var outcome = indexing.deleteDocsByPathPrefixWithOutcome(dir, engineContext);
                if (outcome != null && outcome.confirmed()) {
                  confirmedRemovals.incrementAndGet();
                  deletedByPathJobs.addAndGet(outcome.deletedJobs());
                } else {
                  unchecked.add(scope(dir, "not_removed", outcome == null ? "Worker deletion outcome unavailable" : outcome.error()));
                }
              } catch (RuntimeException failure) {
                unchecked.add(scope(dir, "not_removed", failureReason(failure)));
              }
            }
            // Walk descendants to count disk file membership; a prefix deletion counts one request.
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (!withinBudget()) return FileVisitResult.TERMINATE;
            if (!Files.isReadable(file)) {
              unchecked.add(scope(file, "not_checked", "Unreadable file"));
              return FileVisitResult.CONTINUE;
            }
            if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
            int idx = excludes.matchingPatternIndex(rootPath, file);
            if (idx >= 0) {
              matchedFiles.incrementAndGet();
              perPatternCount[idx]++;
              if (!dryRun && attemptedPrefixes.stream().noneMatch(file::startsWith)) {
                try {
                  var outcome = indexing.deleteDocByIdWithOutcome(file.toAbsolutePath().toString(), engineContext);
                  if (outcome != null && outcome.confirmed()) {
                    deletedById.incrementAndGet();
                    confirmedRemovals.incrementAndGet();
                  } else {
                    unchecked.add(scope(file, "not_removed", outcome == null ? "Worker deletion outcome unavailable" : outcome.error()));
                  }
                } catch (RuntimeException failure) {
                  unchecked.add(scope(file, "not_removed", failureReason(failure)));
                }
              }
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException failure) {
            unchecked.add(scope(file, "not_checked", "Unreadable or unavailable entry"));
            return withinBudget() ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException failure) {
            if (failure != null) unchecked.add(scope(dir, "not_checked", "Folder could not be fully read"));
            return FileVisitResult.CONTINUE;
          }
        });
      } catch (IOException | RuntimeException failure) {
        unchecked.add(scope(rootPath, "not_checked", "Folder walk incomplete: " + failureReason(failure)));
      }
    }

    List<String> indexedMatches = new ArrayList<>();
    // Read one immutable ownership view before issuing any deletion from its matches.
    var indexedSeen = new java.util.HashSet<String>();
    boolean[] collecting = {true};
    try {
      long unknown = indexing.forEachIndexedSource(id -> {
        synchronized (indexedSeen) {
          if (!collecting[0]) return;
          if (visited.incrementAndGet() > maxWalkEntries) {
            throw new IllegalStateException("Entry cap reached; remaining indexed sources not checked");
          }
          Path file;
          try { file = Path.of(id); }
          catch (java.nio.file.InvalidPathException nonFile) { return; }
          if (!file.isAbsolute()) return;
          file = file.normalize();
          Path source = file;
          if (watched.stream().filter(root -> root != null && root.path() != null)
              .anyMatch(root -> source.startsWith(root.path().toAbsolutePath().normalize()))) return;
          if (!indexedSeen.add(id)) return;
          int idx = excludes.matchingPatternIndex(file, file);
          if (idx < 0) return;
          matchedFiles.incrementAndGet();
          perPatternCount[idx]++;
          indexedMatches.add(id);
        }
      }, engineContext);
      if (unknown > 0) unchecked.add(new ExcludesResult.UncheckedScope(
          "Indexed sources", "not_checked", "Some indexed rows have no readable source identity"));
    } catch (RuntimeException failure) {
      unchecked.add(new ExcludesResult.UncheckedScope("Indexed sources", "not_checked", failureReason(failure)));
    } finally {
      // A timed-out in-process read can still exit later; freeze its local report before removal.
      synchronized (indexedSeen) { collecting[0] = false; }
    }

    if (!dryRun) for (String id : indexedMatches) {
      try {
        var outcome = indexing.deleteDocByIdWithOutcome(id, engineContext);
        if (outcome != null && outcome.confirmed()) {
          deletedById.incrementAndGet();
          confirmedRemovals.incrementAndGet();
        } else unchecked.add(scope(Path.of(id), "not_removed", outcome == null
            ? "Worker deletion outcome unavailable" : outcome.error()));
      } catch (RuntimeException failure) {
        unchecked.add(scope(Path.of(id), "not_removed", failureReason(failure)));
      }
    }

    List<ExcludesResult.PatternMatch> perPattern = new ArrayList<>(effectivePatterns.size());
    for (int i = 0; i < effectivePatterns.size(); i++) {
      perPattern.add(new ExcludesResult.PatternMatch(effectivePatterns.get(i), perPatternCount[i]));
    }
    return new ExcludesResult(dryRun, effectivePatterns.size(), rootsProcessed, deletedByPathJobs.get(),
        deletedById.get(), matchedFiles.get(), perPattern, visited.get() > maxWalkEntries,
        "Matches count watched disk files and indexed ad-hoc sources; confirmed removals count Worker-confirmed file or folder deletion requests.",
        confirmedRemovals.get(), unchecked, policy);
  }

  private static ExcludesResult.UncheckedScope scope(Path path, String status, String reason) {
    return new ExcludesResult.UncheckedScope(path.toString(), status,
        reason == null || reason.isBlank() ? "Deletion outcome unavailable" : reason);
  }

  private static String failureReason(Exception failure) {
    return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
  }
}
