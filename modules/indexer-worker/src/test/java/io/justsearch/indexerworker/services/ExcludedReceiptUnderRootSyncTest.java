/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.indexing.ProjectionSeedSource;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.FileMutationFence;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferSyncRoot;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * After a native pointer commit, a file receipt a later SYNC_ROOT covers is checked as a receipt
 * only, unless the exclusion policy excludes its source: that receipt still needs the policy's
 * omission proof and the committed-boot final owner, as an uncovered receipt does. Runs on the
 * real queue, index, file fence and policy owner, through both committed settlement paths.
 */
@EnabledOnOs(OS.LINUX)
@ExtendWith(NoVolumeIdentityExtension.class)
@Timeout(30)
final class ExcludedReceiptUnderRootSyncTest extends LuceneExecutorTestBase {
  private static final JobQueue.EnqueueProvenance ORIGIN =
      new JobQueue.EnqueueProvenance("system", "MIGRATION_ENUMERATOR");
  private static final IngestionOutcome SKIP = IngestionOutcome.of(IngestionOutcomeClass.SKIPPED_POLICY,
      "SKIPPED_TEMP_OR_SYSTEM", IngestionRetryPolicy.NONE);
  private static final String SOURCE = "empty-owned-source";

  @TempDir Path directory;
  private Path root;
  private Path data;
  private Path base;
  private IndexGenerationManager manager;
  private String generation;
  private SqliteJobQueue queue;
  private RunningRuntime runtime;
  private WorkerIngestService publisher;
  private WorkerMutationAdmission mutation;
  private WatchedRootsState rootsOwner;
  private final ReentrantReadWriteLock publication = new ReentrantReadWriteLock();
  private final Map<NativePolicyReplay, List<ResolvedConfig.FileSource>> attemptRoots =
      new java.util.IdentityHashMap<>();

  @AfterEach
  void close() throws Exception {
    try { if (runtime != null) runtime.close(); }
    finally { if (queue != null) queue.close(); }
  }

  @ParameterizedTest(name = "excludedSourceUnderALaterRootSyncNeedsTheFinalOwner({0})")
  @ValueSource(strings = {"simple", "mixed"})
  void excludedSourceUnderALaterRootSyncNeedsTheFinalOwner(String route) throws Exception {
    open();
    Path file = Files.writeString(root.resolve("private.txt"), "old skipped bytes");
    skipped(file);
    if (route.equals("mixed")) seedEmptyProjectionSource();
    syncRoot();
    commitPointer();
    var journal = queue.listSwitchBufferOpsStrict();
    var store = configuration("[\"private.txt\"]");

    var unowned = policy(store);
    assertFalse(readmit(unowned), "SETUP: the excluded source is not readmitted");
    assertFalse(settle(route, unowned), "a missing final owner cannot ACK an omission under a later root sync");
    assertTrue(unowned.hasOmissions(), "the covered receipt is certified as an omission");
    assertEquals(journal, queue.listSwitchBufferOpsStrict(), "every receipt is kept");

    var owned = policy(store);
    var decisions = new AtomicInteger();
    assertTrue(settleWithFinalOwner(route, store, owned, decisions));
    assertEquals(1, decisions.get());
    assertEquals(List.of(), queue.listSwitchBufferOpsStrict());
    assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()), "the file stays out of search");
    assertEquals("DONE", queue.watcherReconciliationRow(file.toString()).get("state"), "the file is not requeued");
    assertEquals("old skipped bytes", Files.readString(file));
  }

  @ParameterizedTest(name = "allowedSourceUnderALaterRootSyncSettlesWithoutCertification({0})")
  @ValueSource(strings = {"simple", "mixed"})
  void allowedSourceUnderALaterRootSyncSettlesWithoutCertification(String route) throws Exception {
    open();
    Path kept = Files.writeString(root.resolve("kept.txt"), "admitted, then rewalked");
    assertTrue(queue.enqueueAndBufferFileForGeneration(generation,
        JobQueue.EnqueueEntry.stat(kept, ORIGIN).withinRoot(root), null));
    if (route.equals("mixed")) seedEmptyProjectionSource();
    syncRoot();
    commitPointer();
    var policy = policy(configuration("[\"private.txt\"]"));

    assertTrue(settle(route, policy), "no final owner is needed for a source the policy keeps");
    assertFalse(policy.hasOmissions());
    assertEquals(List.of(), queue.listSwitchBufferOpsStrict());
    assertEquals("PENDING", queue.watcherReconciliationRow(kept.toString()).get("state"));
  }

  // ---- fixture: a native migration to B with the committed-boot policy owner ----

  private void open() throws Exception {
    root = Files.createDirectory(directory.resolve("root"));
    data = Files.createDirectories(directory.resolve("data"));
    base = Files.createDirectories(directory.resolve("index"));
    var binding = new java.util.LinkedHashMap<String, Object>();
    binding.put("path", root.toString());
    binding.put("collection", null);
    Files.writeString(data.resolve("watched_roots.json"), JsonMapper.builder().build().writeValueAsString(
        Map.of("schemaVersion", 1, "roots", List.of(binding))));
    rootsOwner = WatchedRootsState.load(data);
    manager = new IndexGenerationManager(base);
    manager.initializeOrLoad();
    generation = manager.startMigration("excluded-receipt-under-root-sync").building_generation();
    manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
    queue = new SqliteJobQueue(directory.resolve("queue.db"));
    queue.open();
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.SOURCE_SHA256, "keyword", true, false, List.of(), null, null, false));
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.PROJECTION_SOURCE_ID, "keyword", true, false, List.of(), null, null, false));
    runtime = withTestExecutors(IndexSchema.fromCatalog(new FieldCatalogDef("excluded-receipt", fields), () -> Map.of(), ignored -> {})
        .atPath(manager.resolveGenerationPathStrict(generation))).open();
    runtime.commitOps().stopCommitTimer();
    var loop = mock(IndexingLoop.class);
    var fence = new FileMutationFence();
    when(loop.fileMutationFence()).thenReturn(fence);
    when(loop.withFileMutationFence(any())).thenAnswer(call -> {
      java.util.function.Supplier<?> action = call.getArgument(0);
      return fence.guard(action);
    });
    publisher = new WorkerIngestService(queue, loop, mock(WorkerSignalBus.class), IndexingPacing.unthrottled(),
        base, runtime.openedIndexPath(), runtime, runtime, null, 0L);
    publisher.setRootWatcherRegistry(new RootWatcherRegistry());
    publisher.useExclusionRoots(rootsOwner::watchedPaths);
    mutation = new WorkerMutationAdmission(runtime);
    publisher.setMutationAdmission(mutation, runtime);
    RootVolumeLedger.under(base).record(root, RootIdentity.capture(root).volume());
    publisher.seedConfiguredRoots(List.of(root));
  }

  /** A UI-excluded source the enumerator admitted for B and the worker skipped by policy. */
  private void skipped(Path file) {
    assertTrue(queue.enqueueAndBufferFileForGeneration(generation,
        JobQueue.EnqueueEntry.stat(file, ORIGIN).withinRoot(root), null));
    var claim = queue.pollPending(1).getFirst();
    assertEquals(file, claim.path());
    assertTrue(queue.markClaimDone(claim, SKIP, null));
  }

  /** A complete empty projection source selects the mixed settlement path. */
  private void seedEmptyProjectionSource() throws Exception {
    KnowledgeServerMigrationOps.seedProjectionSource(queue, generation, new ProjectionSeedSource() {
      @Override public String sourceId() { return SOURCE; }
      @Override public void enumerate(Consumer<AcceptedProjection> sink) {}
    });
  }

  /** The receipt IngestSwitchBufferOps.bufferSyncDirectoryDuringSwitching journals. */
  private void syncRoot() {
    assertTrue(queue.putSyncRootForGeneration(generation, "sync_root:" + root,
        new SwitchBufferSyncRoot(root.toString(), true, null)));
  }

  /** B is committed and the pointer moved; the crash came before the receipts were removed. */
  private void commitPointer() throws Exception {
    runtime.commitOps().commitWithBuildState(LuceneRuntimeTypes.BuildState.COMPLETE, CommitReason.MIGRATION_CUTOVER);
    var state = manager.readStateBestEffort();
    try (var promotion = manager.beginNativePromotion(state.active_generation(), generation)) { promotion.promote(); }
    assertEquals(generation, manager.readStateBestEffort().active_generation());
  }

  private ConfigStore configuration(String patterns) {
    String oldData = System.getProperty("justsearch.data.dir");
    String oldIndex = System.getProperty("justsearch.index.base_path");
    System.setProperty("justsearch.data.dir", data.toString());
    System.setProperty("justsearch.index.base_path", base.toString());
    try {
      return new ConfigStore(ResolvedConfig.builder().contributeBaseSources()
          .putSettings("justsearch.ui.exclude_patterns", patterns).build(), publication);
    } finally {
      if (oldData == null) System.clearProperty("justsearch.data.dir"); else System.setProperty("justsearch.data.dir", oldData);
      if (oldIndex == null) System.clearProperty("justsearch.index.base_path"); else System.setProperty("justsearch.index.base_path", oldIndex);
    }
  }

  /** One committed-boot attempt with its exact frozen declarations. */
  private NativePolicyReplay policy(ConfigStore owner) {
    return rootsOwner.withForgetBindings(official -> {
      owner.publicationLock().readLock().lock();
      try {
        var snapshot = owner.currentSnapshot();
        var roots = List.copyOf(KnowledgeServerMigrationOps.loadMigrationRootDeclarations(data,
            snapshot.collections(), JsonMapper.builder().build(), official));
        var policy = NativePolicyReplay.forCommittedBoot(owner, owner.publicationLock(), queue, runtime,
            publisher, generation, roots, snapshot);
        attemptRoots.put(policy, roots);
        return policy;
      } catch (java.io.IOException unknown) {
        throw new java.io.UncheckedIOException(unknown);
      } finally {
        owner.publicationLock().readLock().unlock();
      }
    });
  }

  private boolean readmit(NativePolicyReplay policy) throws Exception {
    try (var fence = mutation.beginFinalFence(runtime, 1_000)) {
      assertNotNull(fence);
      return KnowledgeServerMigrationOps.readmitNativePolicySkippedAdmissions(
          new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null,
              IndexingPacing.unthrottled(), base, runtime.openedIndexPath(), JsonMapper.builder().build(),
              () -> false, () -> true, LoggerFactory.getLogger(ExcludedReceiptUnderRootSyncTest.class),
              System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5), generation, ignored -> true)
              .withPublisher(publisher).withMigrationRoots(attemptRoots.get(policy)).withNativePolicy(policy));
    }
  }

  private boolean settle(String route, NativePolicyReplay policy) throws Exception {
    try (var fence = mutation.beginFinalFence(runtime, 1_000)) {
      assertNotNull(fence);
      return KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(queue, runtime, generation,
          route.equals("mixed") ? List.of(SOURCE) : List.of(),
          LoggerFactory.getLogger(ExcludedReceiptUnderRootSyncTest.class), policy);
    }
  }

  /** The final owner KnowledgeServer.confirmCommittedNativeAcknowledgement binds, without its server. */
  private boolean settleWithFinalOwner(String route, ConfigStore store, NativePolicyReplay policy,
      AtomicInteger decisions) {
    return rootsOwner.withForgetBindings(official -> {
      policy.bindCommittedAcknowledgement(selected -> {
        boolean accepted = policy.withFinalOwnership(() -> {
          try (var ignored = manager.beginForgetNoCandidate(runtime.openedIndexPath())) {
            store.publicationLock().writeLock().lock();
            try {
              var declarations = KnowledgeServerMigrationOps.loadMigrationRootDeclarations(data,
                  store.currentSnapshot().collections(), JsonMapper.builder().build(), official);
              return policy.matchesAuthority(store, store.publicationLock(), declarations)
                  && policy.reproveCommittedAcknowledgement(selected);
            } finally {
              store.publicationLock().writeLock().unlock();
            }
          } catch (java.io.IOException unknown) {
            return false;
          }
        });
        if (accepted) decisions.incrementAndGet();
        return accepted;
      });
      try {
        return settle(route, policy);
      } catch (Exception failure) {
        throw new java.util.concurrent.CompletionException(failure);
      }
    });
  }
}
