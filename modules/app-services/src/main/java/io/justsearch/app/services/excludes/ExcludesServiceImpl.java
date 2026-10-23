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
    ConfigStore store = ConfigStore.globalOrNull();
    if (store == null) return new ExcludesPolicy("default", null, List.of());
    var config = store.get();
    var source = config.resolution("justsearch.ui.exclude_patterns");
    return new ExcludesPolicy(source == null ? "unknown" : source.sourceName(),
        source == null ? null : source.sourceDetail(),
        ExcludeGlobs.fromRawJsonArray(config.ui().excludePatterns()).patterns());
  }

  @Override
  public ExcludesResult applyExcludes(boolean dryRun, EngineContext engineContext) throws Exception {
    IndexingService indexing = indexingServiceSupplier.get();
    // Read patterns and winning-source trace from the same resolved snapshot.
    ExcludesPolicy policy = effectivePolicy();
    ExcludeGlobs excludes = ExcludeGlobs.fromPatterns(policy.patterns());
    List<String> effectivePatterns = policy.patterns();
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

    for (IndexingService.WatchedRoot root : indexing.getWatchedRoots(engineContext)) {
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

    List<ExcludesResult.PatternMatch> perPattern = new ArrayList<>(effectivePatterns.size());
    for (int i = 0; i < effectivePatterns.size(); i++) {
      perPattern.add(new ExcludesResult.PatternMatch(effectivePatterns.get(i), perPatternCount[i]));
    }
    return new ExcludesResult(dryRun, effectivePatterns.size(), rootsProcessed, deletedByPathJobs.get(),
        deletedById.get(), matchedFiles.get(), perPattern, visited.get() > maxWalkEntries,
        "Disk matches count files; confirmed removals count Worker-confirmed file or folder deletion requests.",
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
