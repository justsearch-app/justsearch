/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.excludes;

import io.justsearch.core.context.EngineContext;

import io.justsearch.app.api.ExcludesService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.indexing.ExcludeGlobs;
import io.justsearch.configuration.resolved.ConfigStore;
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

/**
 * Production implementation of {@link ExcludesService}, extracted from
 * {@code IndexingController.applyExcludes} as part of tempdoc 519 §9 Step 3.
 *
 * <p>Walks every watched root. For each match: counts always; deletes only when
 * {@code dryRun=false}. Worker-delegated deletion (deleteDocsByPathPrefix / deleteDocById)
 * honors the "Head never touches Lucene" invariant.
 *
 * <p>Patterns come from the RESOLVED config ({@code rc.ui().excludePatterns()}), which carries the
 * user's settings.json list at ordinal 300 and an operator's {@code -D} / env var at 500 / 400.
 * Tempdoc 883 decision 4 slice 2: this used to read the sysprop that SettingsController promoted
 * the settings list into, which reported a GUI value as an operator override.
 */
public final class ExcludesServiceImpl implements ExcludesService {
private static final int MAX_WALK_FILES = 500_000;

  private final Supplier<IndexingService> indexingServiceSupplier;

  public ExcludesServiceImpl(Supplier<IndexingService> indexingServiceSupplier) {
    this.indexingServiceSupplier =
        Objects.requireNonNull(indexingServiceSupplier, "indexingServiceSupplier");
  }

  @Override
  public ExcludesResult applyExcludes(boolean dryRun, EngineContext engineContext) throws Exception {
    IndexingService indexing = indexingServiceSupplier.get();
    // globalOrNull, not global(): this is an HTTP handler so the store is set in production, but
    // failing the request with IllegalStateException would be a worse answer than "no excludes".
    ConfigStore store = ConfigStore.globalOrNull();
    ExcludeGlobs excludes =
        ExcludeGlobs.fromRawJsonArray(store == null ? "" : store.get().ui().excludePatterns());
    List<String> expandedPatterns = excludes.patterns();

    if (excludes.isEmpty()) {
      return new ExcludesResult(
          dryRun, 0, 0, 0, 0, 0, List.of(), false, "No exclude patterns configured");
    }

    int[] perPatternCount = new int[expandedPatterns.size()];
    int rootsProcessed = 0;
    AtomicInteger deletedByPathJobs = new AtomicInteger();
    AtomicInteger deletedById = new AtomicInteger();
    AtomicInteger matchedFiles = new AtomicInteger();
    AtomicInteger visited = new AtomicInteger();

    for (IndexingService.WatchedRoot root : indexing.getWatchedRoots(engineContext)) {
      if (root == null || root.path() == null) continue;
      Path rootPath = root.path().toAbsolutePath().normalize();
      if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
        continue;
      }
      rootsProcessed++;

      try {
        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (dir == null) return FileVisitResult.CONTINUE;
            if (visited.incrementAndGet() > MAX_WALK_FILES) return FileVisitResult.TERMINATE;
            if (dir.equals(rootPath)) return FileVisitResult.CONTINUE;
            if (excludes.isExcludedDirectory(rootPath, dir)) {
              matchedFiles.incrementAndGet();
              int idx = excludes.matchingDirectoryPatternIndex(rootPath, dir);
              if (idx >= 0) {
                perPatternCount[idx]++;
              }
              if (!dryRun) {
                deletedByPathJobs.addAndGet(indexing.deleteDocsByPathPrefix(dir, engineContext));
              }
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (file == null) return FileVisitResult.CONTINUE;
            if (visited.incrementAndGet() > MAX_WALK_FILES) return FileVisitResult.TERMINATE;
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) return FileVisitResult.CONTINUE;
            int idx = excludes.matchingPatternIndex(rootPath, file);
            if (idx >= 0) {
              matchedFiles.incrementAndGet();
              perPatternCount[idx]++;
              if (!dryRun) {
                boolean ok = indexing.deleteDocById(file.toAbsolutePath().toString(), engineContext);
                if (ok) {
                  deletedById.incrementAndGet();
                }
              }
            }
            return FileVisitResult.CONTINUE;
          }
        });
      } catch (Exception ignored) {
        // Best-effort: continue with other roots.
      }
    }

    List<ExcludesResult.PatternMatch> perPattern = new ArrayList<>(expandedPatterns.size());
    for (int i = 0; i < expandedPatterns.size(); i++) {
      perPattern.add(new ExcludesResult.PatternMatch(expandedPatterns.get(i), perPatternCount[i]));
    }
    return new ExcludesResult(
        dryRun,
        expandedPatterns.size(),
        rootsProcessed,
        deletedByPathJobs.get(),
        deletedById.get(),
        matchedFiles.get(),
        perPattern,
        visited.get() > MAX_WALK_FILES,
        null);
  }
}
