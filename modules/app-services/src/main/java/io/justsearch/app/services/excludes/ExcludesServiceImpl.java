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

  /** Versioned upgrade removal uses index ownership, so missing disks cannot hide old sources.
   * The caller supplies the existing history submission path for legacy untagged transcripts. */
  public boolean removeProtectedSources(Path dataDirectory, EngineContext context,
      java.util.function.Consumer<Path> reconcileHistory) throws IOException {
    var version = io.justsearch.configuration.ProtectedContentPolicy.VERSION;
    Path marker = dataDirectory.resolve(".protected-content-version");
    Files.createDirectories(dataDirectory);
    try (var abandoned = Files.newDirectoryStream(dataDirectory, ".protected-content-*.scan")) {
      for (Path scan : abandoned) Files.deleteIfExists(scan);
    }
    if (Files.exists(marker) && version.equals(Files.readString(marker).trim())) return true;
    var indexing = Objects.requireNonNull(indexingServiceSupplier.get(), "Indexing service unavailable");
    var store = ConfigStore.globalOrNull();
    var help = store == null ? null : store.get().collections().bundledHelp();
    var policy = new io.justsearch.configuration.ProtectedContentPolicy(dataDirectory,
        help == null ? dataDirectory.resolve("help") : help.path());
    var roots = indexing.getWatchedRoots(context);
    long removed = 0, failed = 0;
    // Finish the reader/Worker call before issuing deletes. Spooling bounds heap independently of
    // corpus size and avoids recursive calls into the same bounded Worker executor. Sort on disk
    // so parents and chunks have adjacent identities even when the reader emits them far apart.
    long unchecked;
    try (var spool = new SourceSpool(dataDirectory)) {
      unchecked = indexing.forEachIndexedSourceWithCollection(source -> {
        try { spool.add(source); }
        catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
      }, context);
      var scan = spool.finish();
      if (scan != null) try (var in = SourceSpool.open(scan.path())) {
        String lastPath = null;
        String lastCollection = null;
        for (long row = 0; row < scan.rows(); row++) {
          if (Thread.currentThread().isInterrupted()) throw new IOException("Protected removal interrupted");
          String path = in.readUTF();
          String collection = in.readBoolean() ? in.readUTF() : null;
          if (path.equals(lastPath) && Objects.equals(collection, lastCollection)) continue;
          lastPath = path; lastCollection = collection;
          int result = removeProtectedSource(indexing, policy, roots, path, collection,
              dataDirectory, context, reconcileHistory);
          if (result > 0) removed++;
          else if (result < 0) failed++;
        }
      }
    }
    org.slf4j.LoggerFactory.getLogger(ExcludesServiceImpl.class).info(
        "Protected content upgrade: {} confirmed source removals, {} failed, {} unchecked",
        removed, failed, unchecked);
    if (unchecked != 0 || failed != 0 || Thread.currentThread().isInterrupted()) return false;
    Files.createDirectories(dataDirectory);
    Path pending = marker.resolveSibling(marker.getFileName() + ".tmp");
    Files.writeString(pending, version);
    try { Files.move(pending, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
    catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
      Files.move(pending, marker, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    return true;
  }

  /** External merge sort: at most one fixed batch and two merge heads are held in heap.
   * Binary carry keeps at most 64 run identities, even for a long-sized source count. */
  private static final class SourceSpool implements java.io.Closeable {
    private static final int BATCH_SIZE = 4096;
    private static final java.util.Comparator<IndexingService.IndexedSource> ORDER =
        java.util.Comparator.comparing(IndexingService.IndexedSource::path)
            .thenComparing(IndexingService.IndexedSource::collection,
                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));
    private record Run(Path path, long rows) {}
    private final Path directory;
    private final List<IndexingService.IndexedSource> batch = new ArrayList<>(BATCH_SIZE);
    private final Run[] runs = new Run[Long.SIZE];
    private final java.util.Set<Path> files = new java.util.HashSet<>();

    SourceSpool(Path directory) { this.directory = directory; }

    void add(IndexingService.IndexedSource source) throws IOException {
      batch.add(source);
      if (batch.size() == BATCH_SIZE) flush();
    }

    private Path create() throws IOException {
      Path path = Files.createTempFile(directory, ".protected-content-", ".scan");
      files.add(path);
      return path;
    }

    private void flush() throws IOException {
      if (batch.isEmpty()) return;
      batch.sort(ORDER);
      Run run = new Run(create(), batch.size());
      try (var out = output(run.path())) {
        for (var source : batch) write(out, source);
      }
      batch.clear();
      int level = 0;
      while (runs[level] != null) {
        run = merge(runs[level], run);
        runs[level++] = null;
      }
      runs[level] = run;
    }

    Run finish() throws IOException {
      flush();
      Run result = null;
      for (Run run : runs) {
        if (run != null) result = result == null ? run : merge(result, run);
      }
      return result;
    }

    private Run merge(Run left, Run right) throws IOException {
      Run merged = new Run(create(), left.rows() + right.rows());
      try (var a = open(left.path()); var b = open(right.path()); var out = output(merged.path())) {
        long remainingA = left.rows(), remainingB = right.rows();
        var headA = remainingA > 0 ? read(a) : null;
        var headB = remainingB > 0 ? read(b) : null;
        while (headA != null || headB != null) {
          if (Thread.currentThread().isInterrupted()) throw new IOException("Protected removal interrupted");
          if (headB == null || (headA != null && ORDER.compare(headA, headB) <= 0)) {
            write(out, headA);
            headA = --remainingA > 0 ? read(a) : null;
          } else {
            write(out, headB);
            headB = --remainingB > 0 ? read(b) : null;
          }
        }
      }
      Files.delete(left.path()); files.remove(left.path());
      Files.delete(right.path()); files.remove(right.path());
      return merged;
    }

    static java.io.DataInputStream open(Path path) throws IOException {
      return new java.io.DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(path)));
    }

    private static java.io.DataOutputStream output(Path path) throws IOException {
      return new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(path)));
    }

    private static IndexingService.IndexedSource read(java.io.DataInputStream in) throws IOException {
      return new IndexingService.IndexedSource(in.readUTF(), in.readBoolean() ? in.readUTF() : null);
    }

    private static void write(java.io.DataOutputStream out, IndexingService.IndexedSource source) throws IOException {
      out.writeUTF(source.path());
      out.writeBoolean(source.collection() != null);
      if (source.collection() != null) out.writeUTF(source.collection());
    }

    @Override public void close() throws IOException {
      IOException failure = null;
      for (Path path : files) {
        try { Files.deleteIfExists(path); }
        catch (IOException problem) {
          if (failure == null) failure = problem;
          else failure.addSuppressed(problem);
        }
      }
      if (failure != null) throw failure;
    }
  }

  private static int removeProtectedSource(IndexingService indexing,
      io.justsearch.configuration.ProtectedContentPolicy policy,
      List<IndexingService.WatchedRoot> roots, String path, String collection, Path dataDirectory,
      EngineContext context, java.util.function.Consumer<Path> reconcileHistory) {
    Path file;
    try { file = Path.of(path).normalize(); }
    catch (RuntimeException invalid) { return -1; }
    if (!file.isAbsolute()) return 0; // No-file projections have no filesystem admission policy.
    Path boundary = roots.stream().filter(root -> root != null && root.path() != null
            && (!io.justsearch.configuration.InternalCollections.isReserved(collection)
                || Objects.equals(normalizeCollection(root.collection()), normalizeCollection(collection)))
            && file.startsWith(root.path().toAbsolutePath().normalize()))
        .map(root -> root.path().toAbsolutePath().normalize())
        .max(java.util.Comparator.comparingInt(Path::getNameCount)).orElse(file);
    var protection = policy.classify(file, boundary, collection);
    if (protection.verdict() == io.justsearch.configuration.ProtectedContentPolicy.Verdict.UNCERTAIN) return -1;
    if (protection.verdict() != io.justsearch.configuration.ProtectedContentPolicy.Verdict.PROTECTED) return 0;
    try {
      Path resolved = protection.source();
      Path history = dataDirectory.toAbsolutePath().normalize().resolve("agent-history");
      boolean transcript = resolved.startsWith(history) && Files.isRegularFile(resolved)
          && resolved.toString().endsWith(".md")
          && !policy.protectedPath(resolved, resolved, io.justsearch.configuration.InternalCollections.AGENT_HISTORY);
      Path pending = resolved.resolveSibling(resolved.getFileName() + ".pending");
      // Use the history indexer's existing durable reconciliation obligation BEFORE deleting.
      // A crash between deletion and resubmission must not strand a healthy transcript.
      if (transcript) Files.writeString(pending, "pending");
      var outcome = indexing.deleteDocByIdWithOutcome(file.toString(), context);
      if (outcome == null || !outcome.confirmed()) return -1;
      if (transcript) {
        reconcileHistory.accept(resolved);
        Files.deleteIfExists(pending);
      }
      return 1;
    } catch (IOException | RuntimeException failure) { return -1; }
  }

  private static String normalizeCollection(String collection) {
    return collection == null || collection.isBlank()
        ? io.justsearch.app.api.knowledge.IngestCollectionPolicy.DEFAULT_COLLECTION : collection;
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
