/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationPreparedPayload;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.registry.operations.handlers.ForgetIndexedPathHandler;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.configuration.excludes.ExclusionPatterns;
import io.justsearch.configuration.excludes.LiteralExclusion;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.WatcherRootReconciliation;
import io.justsearch.indexerworker.server.DefaultWorkerAppServices;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** Actual process factory/shared settings owner/SQLite/Lucene; no substitute FORGET response. */
@Timeout(90)
final class EngineForgetInProcessTest {
  private static final Clock CLOCK = Clock.systemUTC();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;

  @Test
  void S55RealWatchedFileOwnerRemovesParentChunksAndJobsKeepsPrecisePolicyAndOriginals() throws Exception {
    Path watched = Files.createDirectory(directory.resolve("watched"));
    Path exact = Files.writeString(watched.resolve("report\\[1]*.txt "), "original physical bytes");
    Path sibling = Files.writeString(watched.resolve("report\\[1]*.txt"), "sibling physical bytes");
    try (var f = new Fixture(directory.resolve("process"), List.of(watched))) {
      f.seed(exact, "private index text"); f.seed(sibling, "public sibling text");
      var result = f.forget(exact, "FILE");
      var outcome = JSON.valueToTree(result.structuredData().get("forget"));
      assertEquals("COMPLETE", outcome.path("status").asString(), result.message());
      assertEquals("CONFIRMED", outcome.path("removal").asString());
      assertEquals("INSTALLED", outcome.path("keepOut").asString());
      assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(key(exact)));
      assertEquals(3, f.runtime.indexCountOps().countByIdAndChunksStrict(key(sibling)));
      assertTrue(f.queue.forgetJobsAbsent(key(exact), false));
      assertEquals("original physical bytes", Files.readString(exact));
      assertEquals("sibling physical bytes", Files.readString(sibling));
      var entries = f.settings.inspect().settings().getExcludePatterns();
      assertEquals(1, entries.size());
      var literal = LiteralExclusion.fromEntry(entries.getFirst());
      assertEquals(exact.toString(), literal.literal().path());
      var policy = io.justsearch.configuration.excludes.ExclusionPatterns.fromRawJsonStrict(
          f.config.get().ui().excludePatterns(), false);
      assertTrue(policy.isExcludedPath(watched, exact));
      assertFalse(policy.isExcludedPath(watched, sibling));
      assertEquals(0, f.root.admission().activeWorkCount());
    }
  }

  @Test
  void S55OutsideFileCompletesWithoutPermanentRuleAndUnrestrictedNewWriterCanPublishAgain() throws Exception {
    Path outside = Files.writeString(directory.resolve("outside.txt"), "source remains");
    try (var f = new Fixture(directory.resolve("process"), List.of())) {
      f.seed(outside, "old indexed text");
      var result = f.forget(outside, "FILE");
      var outcome = JSON.valueToTree(result.structuredData().get("forget"));
      assertEquals("COMPLETE", outcome.path("status").asString(), result.message());
      assertEquals("NOT_REQUIRED", outcome.path("keepOut").asString());
      assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(key(outside)));
      assertTrue(f.settings.inspect().settings().getExcludePatterns().isEmpty());
      String forgetKey = outcome.path("operationKey").asString();
      var withdrawal = f.root.forgetService().withdraw(forgetKey, webview());
      assertEquals("COMPLETE", JSON.valueToTree(withdrawal.structuredData().get("forget")).path("status").asString());
      assertTrue(withdrawal.message().contains("already complete"));
      assertEquals(OperationState.COMPLETE, f.operations.find(forgetKey).orElseThrow().state());
      assertEquals("forget_complete", f.operations.find(forgetKey).orElseThrow().receipt().code());
      // This direct writer proves no lasting keep-out. Accepted intake is checked separately.
      f.seed(outside, "new deliberate index text");
      assertEquals(3, f.runtime.indexCountOps().countByIdAndChunksStrict(key(outside)));
      assertEquals("source remains", Files.readString(outside));
    }
  }

  @Test
  void S55CompletedForgetAllowsReturnedRootReplayWithoutPublishingExcludedSource() throws Exception {
    Path watched = Files.createDirectory(directory.resolve("replay-watched"));
    Path exact = Files.writeString(watched.resolve("report.txt"), "private physical source remains");
    Path sibling = Files.writeString(watched.resolve("report.txt.bak"), "allowed sibling extraction control");
    try (var f = new Fixture(directory.resolve("replay-process"), List.of(watched))) {
      f.seed(exact, "private indexed parent and proper chunks");
      var result = f.forget(exact, "FILE");
      var outcome = JSON.valueToTree(result.structuredData().get("forget"));
      assertTrue(result.success(), "SETUP: the actual prepared Forget must succeed: " + result.message());
      assertEquals("COMPLETE", outcome.path("status").asString(), "SETUP: completed accepted owner");
      assertEquals("CONFIRMED", outcome.path("removal").asString(), "SETUP: confirmed removal");
      assertEquals("INSTALLED", outcome.path("keepOut").asString(), "SETUP: installed keep-out");
      assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(key(exact)));
      assertTrue(f.queue.forgetJobsAbsent(key(exact), false));
      var saved = f.settings.inspect().settings().getExcludePatterns();
      assertEquals(1, saved.size(), "SETUP: one actual owner-installed literal");
      var literal = LiteralExclusion.fromEntry(saved.getFirst());
      assertEquals(exact.toString(), literal.literal().path());
      assertEquals(LiteralExclusion.Scope.FILE, literal.literal().scope());
      assertSame(f.config, ConfigStore.globalOrNull(), "SETUP: actual published policy owner");
      var configuration = f.config.currentSnapshot();
      assertNotNull(configuration, "SETUP: readable effective configuration");
      var policy = ExclusionPatterns.fromRawJsonStrict(configuration.ui().excludePatterns(), false);
      assertTrue(policy.isExcludedPath(watched, exact));
      assertFalse(policy.isExcludedPath(watched, sibling));
      assertTrue(f.loop.isRunning(), "SETUP: the production indexing loop must be running");
      var publisher = ((DefaultWorkerAppServices) f.server.appServices()).ingestService();
      assertNotNull(publisher, "SETUP: actual non-null replay publisher");
      assertTrue(publisher.ownsForgetQueue(f.queue), "SETUP: publisher and queue have the same owner");

      // This direct enqueue is a real-extractor readiness control, not a new accepted INGEST proof.
      String siblingHash = SourceContentHash.sha256(sibling);
      assertEquals(1, f.queue.enqueueEntriesWithExactCollection(List.of(new JobQueue.EnqueueEntry(
          sibling, JobQueue.EnqueueEntry.stat(sibling).sizeBytes(), null, siblingHash, watched)), "documents"));
      long extractionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      boolean extracted = false;
      do {
        f.runtime.commitOps().maybeRefreshBlocking();
        var row = f.queue.watcherReconciliationRow(key(sibling));
        extracted = siblingHash.equals(f.runtime.documentFieldOps().getDocumentFieldOrThrow(
            key(sibling), SchemaFields.SOURCE_SHA256)) && row != null && "DONE".equals(row.get("state"))
            && f.queue.matchesAcceptedFileProjection(key(sibling), row.get("unit_revision"), siblingHash);
        if (!extracted) Thread.sleep(20);
      } while (!extracted && System.nanoTime() < extractionDeadline);
      assertTrue(extracted, "SETUP: sibling must actually extract, publish and commit its current source hash; row="
          + f.queue.watcherReconciliationRow(key(sibling)));
      var siblingBefore = f.queue.watcherReconciliationRow(key(sibling));
      assertEquals(siblingHash, siblingBefore.get("planned_source_sha256"));
      assertEquals("documents", siblingBefore.get("collection"));
      assertEquals(watched.toString(), siblingBefore.get("ingestion_root"));
      assertEquals(0, f.queue.jobStateCountsStrict().pendingCount(), "SETUP: no pending unrelated work");
      assertEquals(0, f.queue.jobStateCountsStrict().processingCount(), "SETUP: no issued unrelated work");
      assertEquals(0, f.queue.jobStateCountsStrict().failedCount(), "SETUP: no failed unrelated work");
      assertNull(f.queue.watcherReconciliationRow(key(exact)), "SETUP: Forget left no target queue row");
      assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty(), "SETUP: no unrelated replay receipts");

      // RootIdentity is package-private; use the existing app-engine test reflection seam, not a new API.
      var identityType = Class.forName("io.justsearch.indexerworker.services.RootIdentity");
      var capture = identityType.getDeclaredMethod("capture", Path.class);
      capture.setAccessible(true);
      var rootIdentity = capture.invoke(null, watched);
      var volume = identityType.getDeclaredMethod("volume");
      volume.setAccessible(true);
      assertNotNull(volume.invoke(rootIdentity), "SETUP: returned-root replay requires real volume evidence");
      var debt = new WatcherRootReconciliation(watched.toString(), rootIdentity.toString(), "documents");
      assertTrue(f.queue.putSwitchBuffer(debt.key(), WatcherRootReconciliation.OPERATION, debt.encode()));
      var journal = f.queue.listSwitchBufferOpsStrict();
      assertEquals(1, journal.size(), "SETUP: one exact root receipt");
      assertTrue(journal.getFirst().acceptedOrder() > 0);
      assertEquals(debt.encode(), journal.getFirst().payload());
      var declarations = List.of(new ResolvedConfig.FileSource(watched, "documents"));
      Path indexBase = (Path) field(f.server, KnowledgeServer.class, "indexBasePath");
      Path activeIndex = (Path) field(f.server, KnowledgeServer.class, "activeIndexPath");
      var signals = (WorkerSignalBus) field(f.server, KnowledgeServer.class, "signalBus");
      long replayDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
      var context = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(f.queue, f.runtime,
          signals, IndexingPacing.unthrottled(), indexBase, activeIndex, JSON, () -> false, () -> true,
          LoggerFactory.getLogger(getClass()), replayDeadline, "green", ignored -> true)
          .withPublisher(publisher).withMigrationRoots(declarations);
      var replay = KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(context);

      // Establish every admissibility condition before the intended convergence assertion.
      assertEquals(journal, f.queue.listSwitchBufferOpsStrict(), "SETUP: preparation retains exact journal");
      assertSame(configuration, f.config.currentSnapshot(), "SETUP: effective policy did not change");
      assertTrue(ExclusionPatterns.fromRawJsonStrict(f.config.currentSnapshot().ui().excludePatterns(), false)
          .isExcludedPath(watched, exact), "SETUP: target is still currently excluded");
      assertEquals(rootIdentity.toString(), capture.invoke(null, watched).toString(), "SETUP: same readable root");
      assertTrue(Files.isRegularFile(exact) && Files.isReadable(exact), "SETUP: source remains present/readable");
      assertEquals("private physical source remains", Files.readString(exact));
      assertEquals("allowed sibling extraction control", Files.readString(sibling));
      assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(key(exact)),
          "forbidden parent/chunks must remain absent");
      assertEquals(siblingHash, f.runtime.documentFieldOps().getDocumentFieldOrThrow(
          key(sibling), SchemaFields.SOURCE_SHA256), "SETUP: allowed current-byte output survives");
      assertEquals(siblingBefore, f.queue.watcherReconciliationRow(key(sibling)),
          "SETUP: independent committed sibling row remains exact");
      assertEquals(0, f.queue.jobStateCountsStrict().pendingCount(), "SETUP: no unsettled queue work");
      assertEquals(0, f.queue.jobStateCountsStrict().processingCount(), "SETUP: no issued queue owner");
      var targetRow = f.queue.watcherReconciliationRow(key(exact));
      if (targetRow != null) {
        assertEquals("DONE", targetRow.get("state"), "SETUP: target has no live foreign claim");
        assertEquals("WATCHER_RECONCILIATION", targetRow.get("transport"));
        assertEquals(watched.toString(), targetRow.get("ingestion_root"));
        assertEquals("documents", targetRow.get("collection"));
      }
      Object refusal = field(publisher, WorkerIngestService.class, "watcherReplayFailure");
      if (replay.isEmpty()) {
        assertEquals("Watcher reconciliation lacks a committed path outcome: " + key(exact), refusal,
            "SETUP: distinguish the hypothesized excluded-path proof refusal from another failure");
      }
      assertTrue(replay.isPresent(), "returned-root replay must converge without publishing the forgotten source; "
          + "refusal=" + refusal + "; targetRow=" + targetRow + "; journal=" + journal);
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(f.queue, replay.orElseThrow()));
      assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty());
      assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(key(exact)));
      assertTrue(f.queue.forgetJobsAbsent(key(exact), false));
      assertEquals(siblingHash, f.runtime.documentFieldOps().getDocumentFieldOrThrow(
          key(sibling), SchemaFields.SOURCE_SHA256));
      assertEquals("private physical source remains", Files.readString(exact));
      assertEquals("allowed sibling extraction control", Files.readString(sibling));
    }
  }

  @Test
  @Timeout(240)
  void R2NonexcludedNativeMigrationControlPublishesCurrentBytesAndReopensSuccessor() throws Exception {
    nativeMigrationOfPresentFile(false);
  }

  @Test
  @Timeout(240)
  void R2CompletedWatchedFileForgetSurvivesLaterNativeMigrationAndSuccessorBoot() throws Exception {
    nativeMigrationOfPresentFile(true);
  }

  @Test
  @Timeout(240)
  void R9HotPublicSaveAfterNativePolicySkipReadmitsCurrentBytesAndKeepsCompletedForgetReceipt() throws Exception {
    nativeMigrationOfPresentFile(true, true);
  }

  private void nativeMigrationOfPresentFile(boolean forgotten) throws Exception {
    nativeMigrationOfPresentFile(forgotten, false);
  }

  private void nativeMigrationOfPresentFile(boolean forgotten, boolean removePolicyAfterSkip) throws Exception {
    Path watched = Files.createDirectory(directory.resolve(forgotten ? "native-forget" : "native-control"));
    Path exact = Files.writeString(watched.resolve("report.txt"), "present native migration target bytes");
    Path sibling = Files.writeString(watched.resolve("report.txt.bak"), "eligible native migration sibling bytes");
    Path data = directory.resolve(forgotten ? "native-forget-process" : "native-control-process");
    String exactHash = SourceContentHash.sha256(exact);
    String siblingHash = SourceContentHash.sha256(sibling);
    String completedForget = null;
    io.justsearch.app.api.operations.OperationRecord completedForgetRecord = null;
    String installedLiteral = null;
    String successor;
    var fixtureConfig = Map.of("justsearch.extraction.sandbox.mode", "in_process");
    try (var f = new Fixture(data, List.of(watched), fixtureConfig)) {
      assertEquals("in_process", f.config.currentSnapshot().extraction().sandboxMode(),
          "SETUP: scoped fixture selects the real in-process extractor before startup");
      System.out.println("R2 component extraction mode=" + f.config.currentSnapshot().extraction().sandboxMode());
      // EngineRoot alone does not run Head's watched-root initialization. Establish its real
      // public live subscription before Forget/START, then let ordinary initial work settle.
      assertEquals(1, f.client.submitBatch(List.of(sibling), TestEngineContexts.FOREGROUND).getAcceptedCount());
      long watchReadyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      boolean watchCorpusReady;
      do {
        f.runtime.commitOps().maybeRefreshBlocking();
        watchCorpusReady = siblingHash.equals(f.runtime.documentFieldOps().getDocumentFieldOrThrow(
            key(sibling), SchemaFields.SOURCE_SHA256)) && f.queue.jobStateCountsStrict().pendingCount() == 0
            && f.queue.jobStateCountsStrict().processingCount() == 0;
        if (!watchCorpusReady) Thread.sleep(20);
      } while (!watchCorpusReady && System.nanoTime() < watchReadyDeadline);
      assertTrue(watchCorpusReady, "SETUP: real ordinary sibling extraction precedes watch initialization");
      var watch = f.client.watchRoot(watched.toString(), "documents", TestEngineContexts.FOREGROUND);
      assertTrue(watch.getWatching(), "SETUP: real live subscription is required: " + watch.getErrorMessage());
      boolean watchSettled;
      do {
        watchSettled = f.queue.jobStateCountsStrict().pendingCount() == 0
            && f.queue.jobStateCountsStrict().processingCount() == 0;
        if (!watchSettled) Thread.sleep(20);
      } while (!watchSettled && System.nanoTime() < watchReadyDeadline);
      assertTrue(watchSettled, "SETUP: ordinary watch initialization must settle before Forget and START");
      assertEquals(0, f.queue.jobStateCountsStrict().failedCount());
      assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty(), "SETUP: watch initialization leaves no candidate/debt");
      f.seed(exact, "indexed private parent with two proper chunks");
      if (forgotten) {
        var response = f.forget(exact, "FILE");
        assertTrue(response.success(), "SETUP: actual accepted Forget must succeed");
        var outcome = JSON.valueToTree(response.structuredData().get("forget"));
        System.out.println("R2 actual public Forget response=" + JSON.writeValueAsString(response));
        assertEquals("COMPLETE", outcome.path("status").asString());
        assertEquals("CONFIRMED", outcome.path("removal").asString());
        assertEquals("INSTALLED", outcome.path("keepOut").asString());
        completedForget = outcome.path("operationKey").asString();
        completedForgetRecord = f.operations.find(completedForget).orElseThrow();
        System.out.println("R2 actual completed operation=" + JSON.writeValueAsString(completedForgetRecord));
        System.out.println("R2 actual accepted settings=" + JSON.writeValueAsString(f.settings.inspect()));
        var entries = f.settings.inspect().settings().getExcludePatterns();
        assertEquals(1, entries.size());
        var literal = LiteralExclusion.fromEntry(entries.getFirst());
        assertEquals(exact.toString(), literal.literal().path());
        assertEquals(LiteralExclusion.Scope.FILE, literal.literal().scope());
        installedLiteral = literal.literal().id();
        assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(key(exact)));
        assertTrue(f.queue.forgetJobsAbsent(key(exact), false));
      }
      var initial = forgotten ? List.of(sibling) : List.of(exact, sibling);
      assertEquals(initial.size(), f.client.submitBatch(initial, TestEngineContexts.FOREGROUND).getAcceptedCount(),
          "SETUP: real ordinary intake must accept the eligible readiness corpus");
      long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      boolean ready;
      do {
        f.runtime.commitOps().maybeRefreshBlocking();
        ready = siblingHash.equals(f.runtime.documentFieldOps().getDocumentFieldOrThrow(
            key(sibling), SchemaFields.SOURCE_SHA256))
            && (forgotten || exactHash.equals(f.runtime.documentFieldOps().getDocumentFieldOrThrow(
                key(exact), SchemaFields.SOURCE_SHA256)))
            && f.queue.jobStateCountsStrict().pendingCount() == 0
            && f.queue.jobStateCountsStrict().processingCount() == 0;
        if (!ready) Thread.sleep(20);
      } while (!ready && System.nanoTime() < readyDeadline);
      assertTrue(ready, "SETUP: actual extractor/writer must commit current eligible source bytes");
      assertTrue(f.loop.isRunning(), "SETUP: initial real writer is running");
      var config = f.config.currentSnapshot();
      assertNotNull(config, "SETUP: readable current effective policy");
      assertEquals("in_process", config.extraction().sandboxMode(),
          "SETUP: settings preparation retains the scoped extraction input");
      var policy = ExclusionPatterns.fromRawJsonStrict(config.ui().excludePatterns(), false);
      assertEquals(forgotten, policy.isExcludedPath(watched, exact));
      assertFalse(policy.isExcludedPath(watched, sibling));
      assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty(), "SETUP: no synthetic or unrelated receipts");

      var admissions = new java.util.concurrent.ConcurrentHashMap<String,
          io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SwitchBufferOp>();
      var latestAdmissions = new java.util.concurrent.ConcurrentHashMap<String,
          io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SwitchBufferOp>();
      var observationFailure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
      Runnable capture = () -> {
        try {
          for (var op : f.queue.listSwitchBufferOpsStrict()) {
            if ("UPSERT".equals(op.op()) && op.generation() != null && !op.generation().isBlank()
                && (("path:" + key(exact)).equals(op.key()) || ("path:" + key(sibling)).equals(op.key()))) {
              admissions.putIfAbsent(op.key(), op);
              latestAdmissions.put(op.key(), op);
            }
          }
        } catch (RuntimeException failure) { observationFailure.compareAndSet(null, failure); }
      };
      // Read-only observation of actual queue commits; no notification is blocked or fabricated.
      try (var subscription = f.queue.indexingJobChangeFeed().orElseThrow().subscribe(ignored -> capture.run())) {
        String predecessor = f.client.getStatus(TestEngineContexts.FOREGROUND).getMigration().getActiveGenerationId();
        var started = f.client.startMigration("forget-native-preservation", TestEngineContexts.FOREGROUND);
        assertTrue(started.accepted(), "SETUP: actual public native migration must be admitted");
        assertFalse(started.restartRequired(), "SETUP: this fixture requires supported live native admission");
        successor = started.buildingGenerationId();
        assertNotNull(successor, "SETUP: actual candidate identity is present");
        assertFalse(successor.isBlank(), "SETUP: actual candidate identity");
        assertNotEquals(predecessor, successor);
        long admittedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        RunningRuntime target = null;
        do {
          var status = f.client.getStatus(TestEngineContexts.FOREGROUND).getMigration();
          Object current = field(f.server, KnowledgeServer.class, "ingestLifecycle");
          var services = f.server.appServices();
          if (successor.equals(status.getBuildingGenerationId())
              && current instanceof RunningRuntime candidate
              && candidate.openedIndexPath() != null
              && successor.equals(candidate.openedIndexPath().getFileName().toString())
              && candidate.isAcceptingWrites()
              && services instanceof DefaultWorkerAppServices producer
              && producer.recordedWriterReady()
              && field(producer.ingestService(), WorkerIngestService.class, "ingestLifecycle") == candidate
              && field(f.server, KnowledgeServer.class, "ingestLifecycle") == candidate
              && f.server.appServices() == producer) {
            target = candidate;
            break;
          }
          Thread.sleep(20);
        } while (System.nanoTime() < admittedDeadline);
        assertNotNull(target, "SETUP: actual candidate writer/publisher must bind within the shared readiness budget");
        assertNotNull(target.openedIndexPath(), "SETUP: actual target path is present");
        assertEquals(successor, target.openedIndexPath().getFileName().toString(), "SETUP: actual target writer");
        assertTrue(target.isAcceptingWrites(), "SETUP: target is writable");
        System.out.println("R2 actual writer/publisher bound generation=" + successor
            + "; writable target=" + target.openedIndexPath());
        Map<String, String> targetRow;
        boolean settled;
        do {
          capture.run();
          target.commitOps().maybeRefreshBlocking();
          targetRow = f.queue.watcherReconciliationRow(key(exact));
          var siblingRow = f.queue.watcherReconciliationRow(key(sibling));
          boolean enumerationDone = (Boolean) field(f.server, KnowledgeServer.class, "migrationEnumeratorDone");
          boolean targetSettled = admissions.containsKey("path:" + key(exact))
              ? targetRow != null && "DONE".equals(targetRow.get("state"))
              : forgotten && enumerationDone && targetRow == null;
          settled = enumerationDone && admissions.containsKey("path:" + key(sibling)) && targetSettled
              && siblingRow != null && "DONE".equals(siblingRow.get("state"))
              && siblingHash.equals(target.documentFieldOps().getDocumentFieldOrThrow(
                  key(sibling), SchemaFields.SOURCE_SHA256));
          if (!settled) Thread.sleep(20);
        } while (!settled && System.nanoTime() < admittedDeadline);
        assertNull(observationFailure.get(), "SETUP: actual queue observation must remain readable");
        assertTrue(settled, "SETUP: actual enumerator receipts and target/sibling writer outcomes are required");
        boolean targetAdmitted = admissions.containsKey("path:" + key(exact));
        assertTrue(forgotten || targetAdmitted, "SETUP: nonexcluded control target must actually be admitted");
        for (var path : targetAdmitted ? List.of(exact, sibling) : List.of(sibling)) {
          var op = admissions.get("path:" + key(path));
          var upsert = io.justsearch.indexerworker.queue.SwitchBufferUpsert.decode(op.payload());
          var row = f.queue.watcherReconciliationRow(key(path));
          assertNotNull(row, "SETUP: actual committed admission row is still readable");
          assertEquals(successor, op.generation());
          assertTrue(op.acceptedOrder() > 0);
          assertNotNull(op.revision(), "SETUP: actual receipt revision is present");
          assertFalse(op.revision().isBlank());
          assertEquals(key(path), upsert.path());
          assertEquals(watched, upsert.ingestionRoot());
          assertEquals("documents", upsert.collection());
          assertNotNull(upsert.provenance(), "SETUP: actual enumerator provenance is present");
          assertEquals("system", upsert.provenance().originator());
          assertEquals("MIGRATION_ENUMERATOR", upsert.provenance().transport());
          assertEquals(path.equals(exact) ? exactHash : siblingHash, upsert.sourceSha256());
          assertEquals(upsert.unitRevision(), row.get("unit_revision"));
          assertEquals(upsert.sourceSha256(), row.get("planned_source_sha256"));
          System.out.println("R2 actual admitted receipt=" + op + "; committed row=" + row);
        }
        if (forgotten) {
          if (targetAdmitted) {
            assertEquals("SKIPPED_POLICY", targetRow.get("last_outcome_class"));
            assertEquals("SKIPPED_TEMP_OR_SYSTEM", targetRow.get("last_reason_code"));
            assertNull(targetRow.get("content_hash"));
          } else {
            System.out.println("R2 enumerator completed without target admission; judge actual convergence");
          }
          assertEquals(0, target.indexCountOps().countByIdAndChunksStrict(key(exact)));
          assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(key(exact)));
        } else {
          assertEquals("SUCCESS_FULL", targetRow.get("last_outcome_class"));
          assertEquals(exactHash, target.documentFieldOps().getDocumentFieldOrThrow(key(exact), SchemaFields.SOURCE_SHA256));
        }
        assertSame(config, f.config.currentSnapshot(), "SETUP: current policy remains unchanged");
        assertEquals(exactHash, SourceContentHash.sha256(exact));
        assertEquals(siblingHash, SourceContentHash.sha256(sibling));
        assertEquals(0, f.queue.jobStateCountsStrict().pendingCount(), "SETUP: no pending unrelated work");
        assertEquals(0, f.queue.jobStateCountsStrict().processingCount(), "SETUP: no issued unrelated work");
        assertEquals(0, f.queue.jobStateCountsStrict().failedCount(), "SETUP: no failed unrelated work");
        if (removePolicyAfterSkip) {
          assertTrue(targetAdmitted, "SETUP: HOT removal must follow a real accepted target receipt");
          assertEquals("SKIPPED_POLICY", targetRow.get("last_outcome_class"));
          assertFalse(f.config.publicationLock().isWriteLockedByCurrentThread(),
              "SETUP: public Save starts outside publication ownership");
          var beforeSave = f.settings.inspect();
          String saveKey = OperationKeys.generate(CLOCK);
          var patch = JSON.readValue(JSON.writeValueAsString(Map.of("ui", Map.of("excludePatterns", List.of()),
              "witness", beforeSave.witness(), "operationKey", saveKey)),
              io.justsearch.app.api.settings.SettingsV2.class);
          var saved = new io.justsearch.app.services.settings.SettingsServiceImpl(f.settings, f.attempts)
              .applyPublic(patch, null, webview());
          assertTrue(saved.response().success(), "SETUP: actual public settings-service Save must commit");
          assertEquals(OperationState.COMPLETE, saved.record().state());
          assertEquals(saveKey, f.settings.inspect().witness().lastCommittedOperationKey());
          assertTrue(f.settings.inspect().settings().getExcludePatterns().isEmpty());
          assertNotSame(config, f.config.currentSnapshot());
          assertFalse(ExclusionPatterns.fromRawJsonStrict(f.config.currentSnapshot().ui().excludePatterns(), false)
              .isExcludedPath(watched, exact));
          assertEquals(completedForgetRecord, f.operations.find(completedForget).orElseThrow());
          System.out.println("R9 actual HOT public Save=" + JSON.writeValueAsString(
              Map.of("response", saved.response(), "record", saved.record())));
        }
        // Publicly requested SWITCHING follows observed enumeration/writer/queue readiness.
        // The monitor still owns replay/drain/certification; automatic timing is not under test.
        assertTrue(f.client.requestCutover(true, TestEngineContexts.FOREGROUND).accepted(),
            "SETUP: public SWITCHING/cutover request must be admitted");
        boolean terminal = false;
        Object refusal = null;
        long completionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        do {
          var status = f.client.getStatus(TestEngineContexts.FOREGROUND).getMigration();
          terminal = "IDLE".equals(status.getMigrationState()) && successor.equals(status.getActiveGenerationId())
              && status.getBuildingGenerationId().isBlank();
          var publisher = ((DefaultWorkerAppServices) f.server.appServices()).ingestService();
          refusal = field(publisher, WorkerIngestService.class, "watcherReplayFailure");
          if (terminal || ("Buffered UPSERT lacks its accepted target projection: " + key(exact)).equals(refusal)) break;
          Thread.sleep(20);
        } while (System.nanoTime() < completionDeadline);
        if (!terminal) {
          assertTrue(forgotten && targetAdmitted
              && ("Buffered UPSERT lacks its accepted target projection: " + key(exact)).equals(refusal),
              "SETUP/INCONCLUSIVE: bounded native migration observation lacks the exact target projection refusal: " + refusal);
          var retained = f.queue.listSwitchBufferOpsStrict().stream()
              .filter(op -> successor.equals(op.generation()) && ("path:" + key(exact)).equals(op.key()))
              .findFirst().orElseThrow(() -> new AssertionError("SETUP: exact refused target receipt disappeared"));
          assertEquals(admissions.get("path:" + key(exact)), retained,
              "SETUP: the observed refusal must retain the exact actual accepted receipt");
          var currentRow = f.queue.watcherReconciliationRow(key(exact));
          assertNotNull(currentRow);
          assertEquals("DONE", currentRow.get("state"));
          assertEquals("SKIPPED_POLICY", currentRow.get("last_outcome_class"));
          assertEquals(io.justsearch.indexerworker.queue.SwitchBufferUpsert.decode(retained.payload()).unitRevision(),
              currentRow.get("unit_revision"));
          assertEquals(0, f.queue.jobStateCountsStrict().pendingCount());
          assertEquals(0, f.queue.jobStateCountsStrict().processingCount());
          assertEquals(0, f.queue.jobStateCountsStrict().failedCount());
          assertEquals(siblingHash, target.documentFieldOps().getDocumentFieldOrThrow(
              key(sibling), SchemaFields.SOURCE_SHA256));
          assertEquals(0, target.indexCountOps().countByIdAndChunksStrict(key(exact)));
          assertEquals(exactHash, SourceContentHash.sha256(exact));
          assertTrue(ExclusionPatterns.fromRawJsonStrict(f.config.currentSnapshot().ui().excludePatterns(), false)
              .isExcludedPath(watched, exact));
        }
        assertTrue(terminal, "R2 actual native migration did not settle at the observed exact target receipt refusal; "
            + "this bounded observation is not a claim of permanent failure: " + refusal);
        if (removePolicyAfterSkip) {
          capture.run();
          var old = admissions.get("path:" + key(exact));
          var fresh = latestAdmissions.get("path:" + key(exact));
          assertNotNull(fresh, "read-only feed must observe the actual replacement before exact cleanup");
          assertNotEquals(old.revision(), fresh.revision());
          assertTrue(fresh.acceptedOrder() > old.acceptedOrder());
          var payload = io.justsearch.indexerworker.queue.SwitchBufferUpsert.decode(fresh.payload());
          var currentRow = f.queue.watcherReconciliationRow(key(exact));
          assertNotNull(currentRow);
          assertNotEquals(io.justsearch.indexerworker.queue.SwitchBufferUpsert.decode(old.payload()).unitRevision(),
              payload.unitRevision());
          assertEquals(payload.unitRevision(), currentRow.get("unit_revision"));
          assertEquals("DONE", currentRow.get("state"));
          assertEquals("SUCCESS_FULL", currentRow.get("last_outcome_class"));
          assertEquals(exactHash, payload.sourceSha256());
          assertEquals(watched, payload.ingestionRoot());
          assertEquals("documents", payload.collection());
          try (var view = f.server.captureServingView()) {
            assertEquals(exactHash, view.ingestRuntime().documentFieldOps().getDocumentFieldOrThrow(
                key(exact), SchemaFields.SOURCE_SHA256));
          }
          assertEquals(OperationState.COMPLETE, f.operations.find(completedForget).orElseThrow().state());
          assertEquals("forget_complete", f.operations.find(completedForget).orElseThrow().receipt().code());
          assertEquals(completedForgetRecord, f.operations.find(completedForget).orElseThrow());
          System.out.println("R9 actual replacement receipt=" + fresh + "; final row=" + currentRow);
        }
        assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty(), "exact replay must be acknowledged after publication");
        var pointer = JSON.readTree(Files.readString(data.resolve("index/state.json")));
        assertEquals(successor, pointer.path("active_generation").asString());
        assertEquals("IDLE", pointer.path("migration_state").asString());
        System.out.println("R2 actual final pointer=" + pointer);
        System.out.println("R2 actual journal after publication=" + f.queue.listSwitchBufferOpsStrict());
        assertTrue(((RunningRuntime) field(f.server, KnowledgeServer.class, "ingestLifecycle")).isAcceptingWrites());
      }
    }
    // Real close/reopen of the same saved settings/operations/generation, never a restart counter.
    try (var reopened = new Fixture(data, List.of(watched), fixtureConfig)) {
      assertEquals("in_process", reopened.config.currentSnapshot().extraction().sandboxMode(),
          "SETUP: actual same-profile reopen retains the explicit component fixture scope");
      var status = reopened.client.getStatus(TestEngineContexts.FOREGROUND).getMigration();
      assertEquals("IDLE", status.getMigrationState());
      assertEquals(successor, status.getActiveGenerationId());
      assertTrue(reopened.runtime.isAcceptingWrites());
      assertEquals(siblingHash, reopened.runtime.documentFieldOps().getDocumentFieldOrThrow(
          key(sibling), SchemaFields.SOURCE_SHA256));
      if (forgotten) {
        assertEquals(OperationState.COMPLETE, reopened.operations.find(completedForget).orElseThrow().state());
        assertEquals("forget_complete", reopened.operations.find(completedForget).orElseThrow().receipt().code());
        assertEquals(completedForgetRecord, reopened.operations.find(completedForget).orElseThrow());
        if (removePolicyAfterSkip) {
          assertTrue(reopened.settings.inspect().settings().getExcludePatterns().isEmpty());
          assertEquals(exactHash, reopened.runtime.documentFieldOps().getDocumentFieldOrThrow(
              key(exact), SchemaFields.SOURCE_SHA256));
        } else {
          var literal = LiteralExclusion.fromEntry(reopened.settings.inspect().settings().getExcludePatterns().getFirst());
          assertEquals(installedLiteral, literal.literal().id());
          assertEquals(0, reopened.runtime.indexCountOps().countByIdAndChunksStrict(key(exact)));
          var retained = reopened.queue.watcherReconciliationRow(key(exact));
          assertTrue(retained == null || "DONE".equals(retained.get("state"))
              && "SKIPPED_POLICY".equals(retained.get("last_outcome_class")),
              "completed migration must leave no live target claim; its new skipped receipt may remain");
        }
      } else {
        assertEquals(exactHash, reopened.runtime.documentFieldOps().getDocumentFieldOrThrow(
            key(exact), SchemaFields.SOURCE_SHA256));
      }
      assertEquals(exactHash, SourceContentHash.sha256(exact));
      assertEquals(siblingHash, SourceContentHash.sha256(sibling));
    }
  }

  static String key(Path path) { return PathNormalizer.normalizeKey(path); }
  static Object field(Object owner, Class<?> type, String name) throws Exception {
    var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
  }
  static EngineContext webview() {
    return EngineProvenance.context(EngineContext.ClientKind.WEBVIEW, "forget-real-engine",
        Optional.empty(), Optional.empty(), TransportTag.BUTTON, EngineContext.Survival.DURABLE,
        EngineContext.Urgency.BACKGROUND);
  }

  static final class Fixture implements AutoCloseable {
    final ConfigStore previousConfig = ConfigStore.globalOrNull();
    final String previousData = System.getProperty("justsearch.data.dir");
    final String previousHome = System.getProperty("user.home");
    final Map<String, String> previousFixtureProperties = new LinkedHashMap<>();
    final ConfigStore config;
    final UiSettingsStore settings;
    final SqliteOperationStore operations;
    final OperationAttemptRunnerImpl attempts;
    final OperationAuthority authority;
    final DefaultEngineProcessResources resources;
    final EngineRoot root;
    final EngineKnowledgeClient client;
    final KnowledgeServer server;
    final RunningRuntime runtime;
    final SqliteJobQueue queue;
    final io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore identities;
    final IndexingLoop loop;
    Fixture(Path data, List<Path> watched) throws Exception {
      this(data, watched, 30_000);
    }
    Fixture(Path data, List<Path> watched, long deadlineMs) throws Exception {
      this(data, watched, deadlineMs, Map.of());
    }
    Fixture(Path data, List<Path> watched, Map<String, String> fixtureConfig) throws Exception {
      this(data, watched, 30_000, fixtureConfig);
    }
    Fixture(Path data, List<Path> watched, long deadlineMs, Map<String, String> fixtureConfig) throws Exception {
      try {
        // A builder default alone is lost when settings preparation rereads base sources.
        // Scope this input to the fixture's existing property save/restore boundary instead.
        for (var entry : fixtureConfig.entrySet()) {
          previousFixtureProperties.put(entry.getKey(), System.getProperty(entry.getKey()));
          System.setProperty(entry.getKey(), entry.getValue());
        }
        Files.createDirectories(data);
        System.setProperty("justsearch.data.dir", data.toString());
        System.setProperty("user.home", data.resolve("home").toString());
        for (var entry : Map.of("justsearch.models.dir", data.resolve("missing-models").toString(),
            "justsearch.index.base_path", data.resolve("index").toString(),
            "justsearch.ssot.path", data.resolve("SSOT").toString()).entrySet()) {
          previousFixtureProperties.put(entry.getKey(), System.getProperty(entry.getKey()));
          System.setProperty(entry.getKey(), entry.getValue());
        }
        EngineTestHarness.publishConfig(data, data.resolve("index"),
            Map.of("justsearch.models.dir", data.resolve("missing-models").toString()));
        config = ConfigStore.global();
        if (!watched.isEmpty()) Files.writeString(data.resolve("watched_roots.json"), JSON.writeValueAsString(Map.of(
            "schemaVersion", 1, "roots", watched.stream().map(path -> Map.of("path", path.toString(), "collection", "documents")).toList())));
        settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, data.resolve("settings.json"));
        config.update(io.justsearch.app.services.config.ConfigStoreRebuilder.prepare(settings.inspect().settings()));
        operations = new SqliteOperationStore(data.resolve("operations.db"));
        var settingsOwner = new SettingsCommitCoordinator(settings, config,
            () -> { throw new AssertionError("Unexpected settings restart"); }, candidate -> OperationResult.success("prepared"));
        attempts = new OperationAttemptRunnerImpl(operations, CLOCK,
            Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS,
                OperationKind.FORGET, OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE),
            settingsOwner, new RecordedIngestPlanResolver());
        authority = OperationAuthority.load(data);
        resources = new DefaultEngineProcessResources(config.publicationLock());
        root = EngineRoot.forProcess(operations, attempts, deadlineMs, 100,
            code -> { throw new AssertionError("Unexpected fatal writer exit " + code); },
            io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
            () -> { throw new AssertionError("Unexpected migration restart"); }, authority, config, resources);
        try {
          client = (EngineKnowledgeClient) root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
          server = (KnowledgeServer) field(root, EngineRoot.class, "server");
          if ("in_process".equals(fixtureConfig.get("justsearch.extraction.sandbox.mode"))) {
            RunningRuntime selectedRuntime = null;
            IndexingLoop selectedLoop = null;
            long startupDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs);
            do {
              // Release every observed view before waiting so the real deferred upgrade can publish.
              try (var view = server.captureServingView()) {
                if (view.ingestRuntime() instanceof RunningRuntime candidate
                    && candidate.isAcceptingWrites()
                    && candidate.openedIndexPath() != null
                    && candidate.openedIndexPath().equals(view.activeGenerationPath())
                    && view.services() instanceof DefaultWorkerAppServices producer
                    && producer.recordedWriterReady()) {
                  selectedRuntime = candidate;
                  selectedLoop = assertInstanceOf(IndexingLoop.class,
                      field(producer, DefaultWorkerAppServices.class, "indexingLoop"),
                      "SETUP: published writer services must own the actual loop");
                }
              }
              if (selectedRuntime == null) Thread.sleep(20);
            } while (selectedRuntime == null && System.nanoTime() < startupDeadline);
            assertNotNull(selectedRuntime, "SETUP: actual published writable startup view must become ready");
            assertNotNull(selectedLoop, "SETUP: actual published startup writer loop must become ready");
            runtime = selectedRuntime;
            queue = (SqliteJobQueue) field(server, KnowledgeServer.class, "jobQueue");
            identities = (io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore)
                field(server, KnowledgeServer.class, "documentIdentityStore");
            loop = selectedLoop;
            System.out.println("R2 actual startup writer/loop ready at=" + runtime.openedIndexPath());
          } else {
            runtime = (RunningRuntime) field(server, KnowledgeServer.class, "ingestLifecycle");
            queue = (SqliteJobQueue) field(server, KnowledgeServer.class, "jobQueue");
            identities = (io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore)
                field(server, KnowledgeServer.class, "documentIdentityStore");
            loop = (IndexingLoop) field(server.appServices(), DefaultWorkerAppServices.class, "indexingLoop");
          }
          runtime.commitOps().stopCommitTimer();
          assertTrue(root.forgetService().hasOwner(), "explicit process factory binds actual shared settings owner");
        } catch (Exception | Error failure) {
          try { close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); } throw failure;
        }
      } catch (Exception | Error failure) {
        // Also restore the optional input if construction fails before close() is usable.
        fixtureConfig.keySet().forEach(name -> {
          String previous = previousFixtureProperties.get(name);
          if (previous == null) System.clearProperty(name); else System.setProperty(name, previous);
        });
        throw failure;
      }
    }
    void seed(Path path, String content) {
      String id = key(path);
      String uid = identities.resolve(io.justsearch.indexerworker.identity.DocumentIdentityStore.pathHash(id),
          System.currentTimeMillis()).docUid();
      String revision = io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(content);
      for (int position = -1; position < 2; position++) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put(SchemaFields.DOC_ID, position < 0 ? id : io.justsearch.indexing.chunking.ChunkIds.newChunkDocId());
        fields.put(SchemaFields.PATH, id); fields.put(SchemaFields.COLLECTION, "documents");
        fields.put(SchemaFields.DOC_UID, position < 0 ? uid : uid + "#" + position);
        fields.put(SchemaFields.CHUNK_TOTAL, 2); fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision);
        if (position < 0) {
          fields.put(SchemaFields.CONTENT, content); fields.put(SchemaFields.CONTENT_SHA256, revision);
        } else {
          int split = content.length() / 2;
          int start = position == 0 ? 0 : split; int end = position == 0 ? split : content.length();
          fields.put(SchemaFields.PARENT_DOC_ID, id); fields.put(SchemaFields.IS_CHUNK, true);
          fields.put(SchemaFields.CHUNK_INDEX, position); fields.put(SchemaFields.CHUNK_START_CHAR, start);
          fields.put(SchemaFields.CHUNK_END_CHAR, end); fields.put(SchemaFields.CHUNK_CONTENT, content.substring(start, end));
        }
        runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      }
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      assertEquals(3, assertDoesNotThrow(() -> runtime.indexCountOps().countByIdAndChunksStrict(id)),
          "fixture must establish actual parent and both owned chunks before the operation");
    }
    OperationResult forget(Path path, String scope) {
      var result = forgetAttempt(path, scope);
      String key = JSON.valueToTree(result.structuredData().get("forget")).path("operationKey").asString();
      assertEquals(OperationState.COMPLETE, operations.find(key).orElseThrow().state(), result.message());
      return result;
    }
    OperationResult forgetAttempt(Path path, String scope) {
      var context = webview();
      var provenance = EngineProvenance.invocation(context, ExecutorTag.UI, CLOCK.instant(), Optional.empty());
      String arguments = JSON.writeValueAsString(Map.of("path", path.toString(), "scope", scope));
      var handler = new ForgetIndexedPathHandler(root::forgetService);
      OperationPreparation prepared = handler.prepare(arguments, provenance, context);
      String key = OperationKeys.generate(CLOCK); UUID nonce = UUID.randomUUID();
      var descriptor = OperationDescriptor.invocation(OperationKind.FORGET, "core.forget-indexed-path", arguments, false);
      var request = new OperationAttemptRunner.Request(key, descriptor, context, provenance);
      String envelope = JSON.writeValueAsString(Map.of("version", 1, "key", key, "nonce", nonce,
          "descriptor", descriptor, "preparation", prepared, "context", context, "executor", ExecutorTag.UI,
          "occurredAt", provenance.occurredAt()));
      var saved = attempts.withPreparation(request, accepted -> attempts.savePreparation(accepted.request(),
          new OperationStore.Preparation(nonce, new OperationPreparedPayload(false, envelope))).orElseThrow());
      var accepted = attempts.acceptPrepared(request, saved.nonce());
      var result = attempts.start(accepted, handle -> handler.executePrepared(prepared, provenance, context, handle));
      return result.response();
    }
    @Override public void close() throws Exception {
      long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (root.admission().activeWorkCount() != 0 && System.nanoTime() < until) Thread.sleep(5);
      try { root.close(); }
      finally {
        try { resources.close(); }
        finally {
          try { operations.close(); }
          finally {
            ConfigStore.restoreGlobal(config, previousConfig);
            if (previousData == null) System.clearProperty("justsearch.data.dir"); else System.setProperty("justsearch.data.dir", previousData);
            if (previousHome == null) System.clearProperty("user.home"); else System.setProperty("user.home", previousHome);
            previousFixtureProperties.forEach((name, value) -> {
              if (value == null) System.clearProperty(name); else System.setProperty(name, value);
            });
          }
        }
      }
    }
  }
}
