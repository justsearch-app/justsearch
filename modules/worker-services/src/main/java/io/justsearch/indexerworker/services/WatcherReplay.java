/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.PathPrefixScope;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.WatcherRootReconciliation;
import io.justsearch.indexerworker.queue.SwitchBufferDelete;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One attempt's held root and per-path outcomes. Nothing here survives a replay attempt. */
public final class WatcherReplay implements AutoCloseable {
  private final WatcherRootReconciliation debt;
  private final RunningRuntime target;
  private final JobQueue queue;
  private final WorkerIngestService publisher;
  private final List<WorkerMutationAdmission.RootUncertainty> uncertainties;
  private final SyncDirectoryOps.RootDifference difference;
  private final BooleanSupplier cancelled;
  // Empty string is a freshly confirmed absence, never an unspecified outcome.
  private final Map<String, String> outcomes = new LinkedHashMap<>();
  private boolean committed;

  public WatcherReplay(WatcherRootReconciliation debt, RunningRuntime target, JobQueue queue,
      WorkerIngestService publisher, IndexingPacing pacing,
      List<ResolvedConfig.FileSource> declarations, List<String> admittedPaths,
      BooleanSupplier cancelled) throws IOException {
    this.debt = debt;
    this.target = Objects.requireNonNull(target);
    this.queue = queue;
    this.publisher = publisher;
    this.cancelled = cancelled;
    uncertainties = publisher == null ? List.of() : publisher.unavailableWatcherRoots();
    Path root = Path.of(debt.root());
    if (declarations == null || declarations.stream().noneMatch(binding ->
        root.equals(binding.path().toAbsolutePath().normalize())
            && Objects.equals(rootOwnerCollection(binding.collection()),
                rootOwnerCollection(debt.collection())))) {
      throw new IOException("Watcher reconciliation root is outside frozen migration declarations");
    }
    if (publisher != null) {
      var returned = RootIdentity.capture(root);
      if (returned.volume() == null || !returned.matchesReceipt(debt.rootIdentity())) {
        throw new IOException("Watcher reconciliation root identity or volume is unproven: " + root);
      }
      // Observe before walking, and before waiting on claims deferred while the old watch was dead.
      // Root uncertainty remains pending until all current path outcomes have committed.
      publisher.resumeReconciledWatcherRoot(debt);
    }
    var scope = new PathPrefixScope(debt.root(), declarations.stream()
        .map(binding -> binding.path().toAbsolutePath().normalize())
        .filter(path -> !path.equals(root) && path.startsWith(root)).map(Path::toString).toList());
    var discovery = new SyncDirectoryOps(target.readPathOps(), null, null, queue, pacing);
    discovery.setPrefixScopeFactory(ignored -> scope);
    difference = discovery.discoverCandidateDifference(debt.root(), true, cancelled);
    try {
      requireIdentity();
      var paths = new LinkedHashSet<String>();
      for (Path file : difference.additions()) paths.add(file.toString());
      paths.addAll(difference.deletions());
      for (String path : admittedPaths) {
        if (!scope.contains(path)) continue;
        if (!Path.of(path).isAbsolute()
            || !Path.of(path).toAbsolutePath().normalize().toString().equals(path)) {
          throw new IOException("Watcher reconciliation admission path is not canonical");
        }
        paths.add(path);
      }
      for (String path : paths) outcomes.put(path, observe(path));
      requireIdentity();
    } catch (IOException | RuntimeException failure) {
      difference.close();
      throw failure;
    }
  }

  public boolean covers(String path, String collection) {
    return outcomes.containsKey(path)
        && Objects.equals(rootOwnerCollection(collection), rootOwnerCollection(debt.collection()));
  }

  /** Candidate survivor names only; callers must verify each selected path before using it. */
  public List<String> presentPaths() throws IOException {
    requireIdentity();
    if (!committed) throw new IOException("Watcher path outcomes are not committed");
    return outcomes.entrySet().stream().filter(entry -> !entry.getValue().isEmpty())
        .map(Map.Entry::getKey).toList();
  }

  public boolean coversDelete(SwitchBufferDelete deletion) throws IOException {
    requireIdentity();
    return deletion.origin() == SwitchBufferDelete.Origin.WATCHER
        && debt.root().equals(deletion.root())
        && difference.rootIdentity().matchesReceipt(deletion.rootIdentity())
        && covers(deletion.path(), deletion.collection());
  }

  /** Apply at the receipt's accepted position, after preceding writers have drained. */
  public void apply() throws IOException {
    requireIdentity();
    target.commitOps().maybeRefreshBlocking();
    for (var outcome : outcomes.entrySet()) {
      String path = outcome.getKey();
      String source = observe(path); // A reappearing source is admitted, never deleted.
      outcome.setValue(source);
      if (source.isEmpty()) {
        try {
          java.util.function.Supplier<Boolean> remove = () -> {
            try {
              requireIdentity();
              if (!observe(path).isEmpty()) throw new IOException("Source returned before deletion");
              target.indexingCoordinator().deleteByIdAndChunks(path);
              if (queue.deleteByExactPath(path) < 0) throw new IOException("Queue deletion failed");
              requireIdentity();
              return true;
            } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
          };
          if (!(publisher == null ? remove.get() : publisher.underFileMutationFence(remove))) {
            throw new IOException("Watcher reconciliation deletion did not apply");
          }
        } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
      } else if (!matches(path, source) || !matchesQueueCollection(Path.of(path))) {
        var entry = new JobQueue.EnqueueEntry(Path.of(path), Files.size(Path.of(path)), null, source)
            .withinRoot(Path.of(debt.root()));
        if (queue.enqueueEntriesWithExactCollection(List.of(entry), rootOwnerCollection(debt.collection())) != 1) {
          throw new IOException("Watcher reconciliation file admission failed: " + path);
        }
      }
    }
    requireIdentity();
  }

  /** The caller has drained the target writer. A complete commit is required before clearing debt. */
  public void commitAndVerify() throws IOException {
    requireIdentity();
    target.commitOps().commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
    target.commitOps().maybeRefreshBlocking();
    for (var outcome : outcomes.entrySet()) {
      if (!outcome.getValue().equals(observe(outcome.getKey()))
          || !matches(outcome.getKey(), outcome.getValue())) {
        throw new IOException("Watcher reconciliation lacks a committed path outcome: " + outcome.getKey());
      }
    }
    requireIdentity();
    committed = true;
    if (publisher != null) {
      for (var exact : uncertainties) {
        if (debt.root().equals(exact.debt().root())
            && Objects.equals(rootOwnerCollection(debt.collection()),
                rootOwnerCollection(exact.debt().collection()))
            && difference.rootIdentity().matchesReceipt(exact.debt().rootIdentity())) {
          publisher.watcherRootReconciled(exact.debt(), List.of(exact));
        }
      }
    }
  }

  /** A later accepted deletion may supersede this outcome; the replay caller judges ordering. */
  public boolean verifies(String path, String collection) throws IOException {
    requireIdentity();
    return committed && covers(path, collection)
        && outcomes.get(path).equals(observe(path)) && matches(path, outcomes.get(path));
  }

  private boolean matches(String path, String source) throws IOException {
    if (source.isEmpty()) return target.indexCountOps().countByIdAndChunksStrict(path) == 0;
    var fields = target.documentFieldOps();
    return source.equals(fields.getDocumentFieldOrThrow(path, SchemaFields.SOURCE_SHA256))
        && Objects.equals(rootOwnerCollection(debt.collection()),
            rootOwnerCollection(fields.getDocumentFieldOrThrow(path, SchemaFields.COLLECTION)));
  }

  private boolean matchesQueueCollection(Path path) {
    String collection = rootOwnerCollection(debt.collection());
    return queue.matchesExpectedCollection(path, collection)
        || collection == null && queue.matchesExpectedCollection(path, IngestCollectionPolicy.DEFAULT_COLLECTION);
  }

  private String observe(String path) throws IOException {
    requireIdentity();
    Path file = Path.of(path);
    if (Files.notExists(file) && io.justsearch.adapters.lucene.runtime.PathPresence
        .absentBeneathListableFolder(file, Path.of(debt.root()), null)
            == io.justsearch.adapters.lucene.runtime.PathPresence.ABSENT) return "";
    if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Watcher reconciliation source is unreadable or not regular: " + path);
    }
    if (io.justsearch.indexerworker.ingest.IngestionSkipPolicy.shouldSkipWithinRoot(
            file, Path.of(debt.root())) || SyncDirectoryOps.isCloudPlaceholder(file)) {
      throw new IOException("Watcher reconciliation cannot certify an ineligible source: " + path);
    }
    return SourceContentHash.sha256(file);
  }

  private void requireIdentity() throws IOException {
    SyncDirectoryOps.checkCancelled(cancelled);
    difference.requireCurrentRootIdentity();
    if (difference.rootIdentity().volume() == null
        || !difference.rootIdentity().matchesReceipt(debt.rootIdentity())) {
      throw new IOException("Watcher reconciliation root identity or volume is unproven: " + debt.root());
    }
  }

  private static String normalizeCollection(String collection) {
    return collection == null || collection.isBlank() ? null : collection;
  }

  /** Authored roots omit the index-default bucket; live watchers name it explicitly. */
  static String rootOwnerCollection(String collection) {
    return IngestCollectionPolicy.DEFAULT_COLLECTION.equals(collection)
        ? null : normalizeCollection(collection);
  }

  @Override public void close() throws IOException { difference.close(); }
}
