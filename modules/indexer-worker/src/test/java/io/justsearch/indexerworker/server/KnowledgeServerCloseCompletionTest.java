/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionRetiredException;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Stage-A checkpoint (re-review) — {@code awaitClosed} answers a question that has two answers.
 *
 * <p><b>The bug this test exists because of.</b> The re-review found the "production consumer" added
 * for {@code isRunning()} was vacuous: {@code EngineRoot.close()} warned if {@code s.isRunning()}
 * was still true after {@code s.close()} returned, but {@code close()} sets {@code running = false}
 * in its first line and {@code isRunning()} is {@code running && latch > 0}, so the predicate was
 * constant-false at that point and the warning could never fire. A check that cannot fail was added
 * in the course of removing checks that could not fail.
 *
 * <p>What this test pins is the two-state property: the predicate is FALSE on a server that has not
 * completed a close and TRUE on one that has. That is precisely what the old read lacked, and
 * without both directions asserted a future refactor could quietly restore a constant-valued
 * predicate — which is what happened once already.
 *
 * <p>The partial-failure case is exercised across the real enclosing owners by
 * app-engine's EngineRootTerminalWriterFailureTest: a held Lucene generation times out close,
 * keeps this latch false and the index lock held, then a retry releases both after actual exit.
 */
@DisplayName("KnowledgeServer.awaitClosed — close completion is observable")
final class KnowledgeServerCloseCompletionTest {

  @ParameterizedTest(name = "issued Blue view remains usable at {0}")
  @ValueSource(strings = {
      "migration-before-live-green-open",
      "migration-after-live-green-open",
      "migration-green-drained",
      "migration-before-switching",
      "migration-switching-entered",
      "migration-before-pointer-commit",
      "migration-after-pointer-commit",
      "migration-before-live-activation",
      "migration-after-live-activation"
  })
  @Timeout(120)
  void migrationPauseMatrixDoesNotCloseBlueBeneathAnIssuedQuery(
      String point, @TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    var executors = new io.justsearch.core.execution.TestEngineExecutors();
    var server = new KnowledgeServer(executors,
        WorkerBootFixture.workerConfig(layout.dataDir()), null);
    var barrier = new MigrationTransitionBarrier.Controlled(point);
    server.installMigrationBarrierForTests(barrier);
    var fallback = new java.util.concurrent.atomic.AtomicInteger();
    KnowledgeServer.ServingLease blueQuery = null;
    try {
      server.start();
      server.deferredModelInit.get(30, java.util.concurrent.TimeUnit.SECONDS);
      blueQuery = server.captureServingView();
      var blueRuntime = blueQuery.searchRuntime();
      var compatibleOwner = blueQuery.encoderSet();
      assertNotNull(compatibleOwner, "the actual query view must carry a concrete encoder owner");
      var blueTextSearch = blueRuntime.textQueryOps();
      assertNotNull(blueTextSearch, "the issued query must carry Blue's exact search surface");
      assertEqualsZeroSearch(blueQuery);

      var migration = server.indexGenerationManagerForTests().startFreshMigration(
          "d1-8-acquisition-swap-matrix");
      var liveStart = server.beginUnrecordedBuildingLiveAsync(
          migration.building_generation(), fallback::incrementAndGet);
      assertTrue(barrier.awaitReached(30, java.util.concurrent.TimeUnit.SECONDS),
          "the real migration must reach the selected WP1 point");

      assertSame(blueRuntime, blueQuery.searchRuntime());
      assertSame(blueTextSearch, blueQuery.searchRuntime().textQueryOps());
      assertSame(compatibleOwner, blueQuery.encoderSet());
      assertEqualsZeroSearch(blueQuery);

      boolean publicationWritePoint = point.equals("migration-before-pointer-commit")
          || point.equals("migration-after-pointer-commit")
          || point.equals("migration-before-live-activation")
          || point.equals("migration-after-live-activation");
      java.util.concurrent.Future<KnowledgeServer.ServingLease> queuedCapture = null;
      try (var captureExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        try {
          if (publicationWritePoint) {
            var attempting = new java.util.concurrent.CountDownLatch(1);
            var captureThread = new AtomicReference<Thread>();
            queuedCapture = captureExecutor.submit(() -> {
              captureThread.set(Thread.currentThread());
              attempting.countDown();
              return server.captureServingView();
            });
            assertTrue(attempting.await(2, java.util.concurrent.TimeUnit.SECONDS));
            long blockedDeadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (!queuedCapture.isDone()
                && captureThread.get().getState() != Thread.State.WAITING
                && System.nanoTime() < blockedDeadline) Thread.onSpinWait();
            org.junit.jupiter.api.Assertions.assertEquals(
                Thread.State.WAITING, captureThread.get().getState(),
                "capture must park on the publication read lock held by the WP1 point");
            assertFalse(queuedCapture.isDone(),
                "capture must wait behind the atomic pointer/publication write section");
          } else {
            try (var pausedBlue = server.captureServingView()) {
              assertSame(blueRuntime, pausedBlue.searchRuntime());
              assertSame(blueTextSearch, pausedBlue.searchRuntime().textQueryOps());
              assertSame(compatibleOwner, pausedBlue.encoderSet());
              assertEqualsZeroSearch(pausedBlue);
            }
          }
        } finally {
          // A queued capture at a publication-write point cannot let ExecutorService.close()
          // wait on itself when an assertion above fails.
          barrier.release();
        }

        assertTrue(liveStart.get(30, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(server.awaitMigrationCutoverExitForTests(
            30, java.util.concurrent.TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertEquals(0, fallback.get());
        if (queuedCapture != null) {
          try (var greenAfterWrite = queuedCapture.get(
              5, java.util.concurrent.TimeUnit.SECONDS)) {
            assertNotSame(blueRuntime, greenAfterWrite.searchRuntime());
            assertSame(compatibleOwner, greenAfterWrite.encoderSet());
            assertEqualsZeroSearch(greenAfterWrite);
          }
        }
      } finally {
        // An assertion that capture bypassed the writer must still release the returned lease.
        // Executor close above joins the task after the barrier's failure-safe release.
        if (queuedCapture != null) {
          queuedCapture.get(5, java.util.concurrent.TimeUnit.SECONDS).close();
        }
      }

      // Flow A intentionally reuses one compatible model/settings owner. The generation pairing
      // witness is the exact runtime and query surface: issued Blue remains Blue while a new view
      // resolves Green, and both remain usable until their own request lifetime ends.
      assertSame(blueRuntime, blueQuery.searchRuntime());
      assertSame(blueTextSearch, blueQuery.searchRuntime().textQueryOps());
      assertSame(compatibleOwner, blueQuery.encoderSet());
      assertEqualsZeroSearch(blueQuery);
      try (var greenQuery = server.captureServingView()) {
        assertNotSame(blueRuntime, greenQuery.searchRuntime());
        assertNotSame(blueTextSearch, greenQuery.searchRuntime().textQueryOps());
        assertSame(compatibleOwner, greenQuery.encoderSet());
        assertEqualsZeroSearch(greenQuery);
      }
      assertNotNull(server.indexGenerationManagerForTests().readStateBestEffort()
          .previous_generation(), "issued Blue must retain its predecessor capacity");
      Path bluePath = blueRuntime.openedIndexPath();
      assertTrue(java.nio.file.Files.exists(bluePath));
      blueQuery.close();
      blueQuery = null;
      org.junit.jupiter.api.Assertions.assertNull(
          server.indexGenerationManagerForTests().readStateBestEffort().previous_generation(),
          "release of the earlier view must retry the later source cleanup and retire Blue");
      assertFalse(java.nio.file.Files.exists(bluePath));
    } finally {
      barrier.release();
      if (blueQuery != null) blueQuery.close();
      try {
        server.close();
      } finally {
        executors.close();
      }
    }
  }

  private static void assertEqualsZeroSearch(KnowledgeServer.ServingLease query)
      throws java.io.IOException {
    org.junit.jupiter.api.Assertions.assertEquals(0,
        query.searchRuntime().indexCountOps().countByFieldOrThrow(
            io.justsearch.indexing.SchemaFields.DOC_ID, "d1-8-missing-document"));
  }

  @Test
  void orderedShutdownRetainsRealNativeSessionUntilIssuedLeaseExits(@TempDir Path tempDir)
      throws Exception {
    var discovery = io.justsearch.ort.testing.ModelDirTestResolver.discover(
        "models/onnx/gte-multilingual-base", null, "model.onnx");
    Assumptions.assumeTrue(discovery.modelDir() != null,
        "standard embedding model is unavailable for native lifetime proof");
    io.justsearch.ort.SessionHandle handle = io.justsearch.ort.testing.InferenceCompositionRootTestHelper
        .cpuSessionFor("ordered-shutdown-held-native", discovery.modelDir());
    var surface = new InferenceSurface(java.util.Optional.empty(), java.util.Optional.empty(),
        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
        java.util.Optional.empty(), org.mockito.Mockito.mock(io.justsearch.ort.PolicySnapshot.class),
        java.util.List.of(handle));
    var modelFingerprint = IndexFingerprint.ModelFingerprint.notConfigured();
    var owner = new EncoderSet(surface, new EncoderSet.ModelIdentity(
        modelFingerprint, modelFingerprint, modelFingerprint, false, 768));
    var server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    server.publishServingView(org.mockito.Mockito.mock(WorkerAppServices.class));
    Field servingViewField = KnowledgeServer.class.getDeclaredField("servingView");
    servingViewField.setAccessible(true);
    Object view = servingViewField.get(server);
    var attach = view.getClass().getDeclaredMethod("attachEncoderSet", EncoderSet.class);
    attach.setAccessible(true);
    attach.invoke(view, owner);
    attachQueryOwner(server, view);
    Field initialOwnerField = KnowledgeServer.class.getDeclaredField("initialEncoderSet");
    initialOwnerField.setAccessible(true);
    initialOwnerField.set(server, owner);
    var issuedView = server.captureServingView();
    var nativeRequest = SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.FOREGROUND, java.time.Duration.ofSeconds(2));
    io.justsearch.ort.SessionHandle.Lease issuedNative = handle.acquireCpu(nativeRequest);
    var closeFailure = new AtomicReference<Throwable>();
    Thread closer = new Thread(() -> {
      try { server.close(); }
      catch (Throwable failure) { closeFailure.set(failure); }
    }, "ordered-shutdown-held-native");
    try {
      closer.start();
      long admissionDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      boolean admissionClosed = false;
      while (!admissionClosed && System.nanoTime() < admissionDeadline) {
        try (var ignored = server.captureServingView()) { Thread.onSpinWait(); }
        catch (IllegalStateException expected) { admissionClosed = true; }
      }
      assertTrue(admissionClosed, "shutdown must stop new serving captures");
      assertNotNull(issuedNative.session().getInputNames(),
          "the issued native session must remain usable while its view is held");
      issuedView.close();
      long nativeDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      while (handle.retirementStatus() == io.justsearch.ort.SessionHandle.RetirementStatus.ACTIVE
          && System.nanoTime() < nativeDeadline) Thread.onSpinWait();
      org.junit.jupiter.api.Assertions.assertEquals(
          io.justsearch.ort.SessionHandle.RetirementStatus.RETIRING, handle.retirementStatus());
      assertNotNull(issuedNative.session().getInputNames(),
          "native retirement must wait for the exact issued session");
      assertThrows(SessionRetiredException.class, () -> handle.acquireCpu(nativeRequest));
      issuedNative.close();
      closer.join(2_000);
      assertFalse(closer.isAlive());
      org.junit.jupiter.api.Assertions.assertNull(closeFailure.get(), String.valueOf(closeFailure.get()));
      org.junit.jupiter.api.Assertions.assertEquals(
          io.justsearch.ort.SessionHandle.RetirementStatus.RETIRED, handle.retirementStatus());
    } finally {
      issuedNative.close();
      issuedView.close();
      closer.join(2_000);
      server.close();
    }
  }

  @Test
  void retiringViewRefusesNewCapturesAndWaitsForActualHolder(@TempDir Path tempDir)
      throws Exception {
    var server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    var services = org.mockito.Mockito.mock(WorkerAppServices.class);
    server.publishServingView(services);
    var held = server.captureServingView();
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var retirement = executor.submit(() -> {
        server.retireServingView();
        return null;
      });
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      boolean refused = false;
      while (!refused && System.nanoTime() < deadline) {
        try (var probe = server.captureServingView()) {
          assertSame(services, probe.services());
          Thread.onSpinWait();
        } catch (IllegalStateException expected) {
          refused = true;
        }
      }
      assertTrue(refused, "retirement must close capture admission");
      assertTrue(!retirement.isDone(), "held physical view must delay retirement");
      assertThrows(IllegalStateException.class, server::captureServingView);
      held.close();
      retirement.get(2, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      held.close();
      server.close();
    }
  }

  @Test
  void inPlaceBuildKeepsLexicalCapturesOpenUntilIssuedNativeViewExits(@TempDir Path tempDir)
      throws Exception {
    var server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    var aRuntime = org.mockito.Mockito.mock(
        io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
    var greenRuntime = org.mockito.Mockito.mock(
        io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
    var services = org.mockito.Mockito.mock(DefaultWorkerAppServices.class);
    var lexicalServices = org.mockito.Mockito.mock(WorkerAppServices.class);
    org.mockito.Mockito.when(services.prepareTextOnlyCandidateView(aRuntime))
        .thenReturn(lexicalServices);
    var absent = IndexFingerprint.ModelFingerprint.notConfigured();
    var owner = new EncoderSet(new InferenceSurface(java.util.Optional.empty(),
        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
        java.util.Optional.empty(), java.util.Optional.empty(),
        org.mockito.Mockito.mock(io.justsearch.ort.PolicySnapshot.class), java.util.List.of(),
        new InferenceSurface.ComponentObservation(java.util.Optional.of("configured A"),
            java.util.Set.of(io.justsearch.ort.EncoderRole.EMBEDDING),
            java.util.Set.of(io.justsearch.ort.EncoderRole.EMBEDDING))),
        new EncoderSet.ModelIdentity(absent, absent, absent, false, 768));
    Field searchField = KnowledgeServer.class.getDeclaredField("searchLifecycle");
    Field ingestField = KnowledgeServer.class.getDeclaredField("ingestLifecycle");
    Field initialField = KnowledgeServer.class.getDeclaredField("initialEncoderSet");
    searchField.setAccessible(true);
    ingestField.setAccessible(true);
    initialField.setAccessible(true);
    searchField.set(server, aRuntime);
    ingestField.set(server, greenRuntime);
    server.publishServingView(services);
    var servingField = KnowledgeServer.class.getDeclaredField("servingView");
    servingField.setAccessible(true);
    Object nativeView = servingField.get(server);
    var attach = nativeView.getClass().getDeclaredMethod("attachEncoderSet", EncoderSet.class);
    attach.setAccessible(true);
    attach.invoke(nativeView, owner);
    attachQueryOwner(server, nativeView);
    initialField.set(server, owner);

    var held = server.captureServingView();
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var build = executor.submit(() -> {
        var begin = KnowledgeServer.class.getDeclaredMethod("beginInPlaceCandidateBuild");
        begin.setAccessible(true);
        begin.invoke(server);
        return null;
      });
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      boolean lexicalPublished = false;
      while (!lexicalPublished && System.nanoTime() < deadline) {
        try (var lexical = server.captureServingView()) {
          lexicalPublished = lexical.encoderSet() == null;
          assertTrue(lexical.searchRuntime() == aRuntime);
        }
      }
      assertTrue(lexicalPublished, "new lexical calls must enter while old native work is held");
      assertSame(services, held.services(),
          "issued A keeps its original service bindings");
      assertFalse(owner.isClosed(), "the issued native A view retains its exact owner");
      assertFalse(build.isDone(), "B cannot compose until the old native view exits");
      try (var lexicalHeld = server.captureServingView()) {
        var lexicalRetired = new AtomicReference<>(false);
        lexicalHeld.onRetirement(() -> lexicalRetired.set(true));
        held.close();
        build.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(owner.isClosed());
        org.mockito.Mockito.verify(services).prepareTextOnlyCandidateView(aRuntime);
        org.mockito.Mockito.verify(services).clearProducerModelLease();
        var refuse = KnowledgeServer.class.getDeclaredMethod(
            "recomposeSourceAfterCandidateRefusal", Exception.class);
        refuse.setAccessible(true);
        refuse.invoke(server, new java.io.IOException("candidate refused"));
        Field inPlace = KnowledgeServer.class.getDeclaredField("recordedCandidateInPlace");
        inPlace.setAccessible(true);
        assertFalse(inPlace.getBoolean(server),
            "configured but unavailable A remains lexical after B refuses");
        assertTrue(lexicalRetired.get(), "refusal must notify an issued lexical stream");
      }
    } finally {
      held.close();
      server.close();
    }
  }

  @Test
  void inPlaceBuildWaitsForIssuedNativeLeaseBeforeComposing(@TempDir Path tempDir)
      throws Exception {
    var discovery = io.justsearch.ort.testing.ModelDirTestResolver.discover(
        "models/onnx/gte-multilingual-base", null, "model.onnx");
    Assumptions.assumeTrue(discovery.modelDir() != null,
        "standard embedding model is unavailable for native lifetime proof");
    io.justsearch.ort.SessionHandle handle = io.justsearch.ort.testing.InferenceCompositionRootTestHelper
        .cpuSessionFor("in-place-held-native", discovery.modelDir());
    var surface = new InferenceSurface(java.util.Optional.empty(), java.util.Optional.empty(),
        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
        java.util.Optional.empty(), org.mockito.Mockito.mock(io.justsearch.ort.PolicySnapshot.class),
        java.util.List.of(handle));
    var absent = IndexFingerprint.ModelFingerprint.notConfigured();
    var owner = new EncoderSet(surface, new EncoderSet.ModelIdentity(
        absent, absent, absent, false, 768));
    var server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    var aRuntime = org.mockito.Mockito.mock(
        io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
    var greenRuntime = org.mockito.Mockito.mock(
        io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
    var services = org.mockito.Mockito.mock(DefaultWorkerAppServices.class);
    var lexicalServices = org.mockito.Mockito.mock(WorkerAppServices.class);
    org.mockito.Mockito.when(services.prepareTextOnlyCandidateView(aRuntime))
        .thenReturn(lexicalServices);
    Field searchField = KnowledgeServer.class.getDeclaredField("searchLifecycle");
    Field ingestField = KnowledgeServer.class.getDeclaredField("ingestLifecycle");
    Field initialField = KnowledgeServer.class.getDeclaredField("initialEncoderSet");
    searchField.setAccessible(true);
    ingestField.setAccessible(true);
    initialField.setAccessible(true);
    searchField.set(server, aRuntime);
    ingestField.set(server, greenRuntime);
    server.publishServingView(services);
    var servingField = KnowledgeServer.class.getDeclaredField("servingView");
    servingField.setAccessible(true);
    Object nativeView = servingField.get(server);
    var attach = nativeView.getClass().getDeclaredMethod("attachEncoderSet", EncoderSet.class);
    attach.setAccessible(true);
    attach.invoke(nativeView, owner);
    attachQueryOwner(server, nativeView);
    initialField.set(server, owner);
    var issuedView = server.captureServingView();
    var nativeRequest = SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.FOREGROUND, java.time.Duration.ofSeconds(2));
    io.justsearch.ort.SessionHandle.Lease issuedNative = handle.acquireCpu(nativeRequest);
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var build = executor.submit(() -> {
        var begin = KnowledgeServer.class.getDeclaredMethod("beginInPlaceCandidateBuild");
        begin.setAccessible(true);
        begin.invoke(server);
        return null;
      });
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      boolean lexicalPublished = false;
      while (!lexicalPublished && System.nanoTime() < deadline) {
        try (var lexical = server.captureServingView()) {
          lexicalPublished = lexical.encoderSet() == null;
          assertTrue(lexical.searchRuntime() == aRuntime);
        }
      }
      assertTrue(lexicalPublished, "new captures must retain A text during native retirement");
      issuedView.close();
      deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      while (handle.retirementStatus() == io.justsearch.ort.SessionHandle.RetirementStatus.ACTIVE
          && System.nanoTime() < deadline) Thread.onSpinWait();
      org.junit.jupiter.api.Assertions.assertEquals(
          io.justsearch.ort.SessionHandle.RetirementStatus.RETIRING, handle.retirementStatus());
      assertFalse(build.isDone(), "B composition must wait for the exact native call");
      assertNotNull(issuedNative.session().getInputNames(),
          "the issued native session remains usable while retiring");
      assertThrows(SessionRetiredException.class, () -> handle.acquireCpu(nativeRequest));
      issuedNative.close();
      build.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(owner.isClosed());
      org.junit.jupiter.api.Assertions.assertEquals(
          io.justsearch.ort.SessionHandle.RetirementStatus.RETIRED, handle.retirementStatus());
    } finally {
      issuedNative.close();
      issuedView.close();
      server.close();
    }
  }

  @Test
  void slowIssuedAQueryRestoresUntouchedNativeViewAfterDrainDeadline(@TempDir Path tempDir)
      throws Exception {
    var server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    var aRuntime = org.mockito.Mockito.mock(
        io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
    var greenRuntime = org.mockito.Mockito.mock(
        io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
    var services = org.mockito.Mockito.mock(DefaultWorkerAppServices.class);
    var lexical = org.mockito.Mockito.mock(WorkerAppServices.class);
    org.mockito.Mockito.when(services.prepareTextOnlyCandidateView(aRuntime)).thenReturn(lexical);
    var absent = IndexFingerprint.ModelFingerprint.notConfigured();
    var owner = new EncoderSet(new InferenceSurface(java.util.Optional.empty(),
        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
        java.util.Optional.empty(), java.util.Optional.empty(),
        org.mockito.Mockito.mock(io.justsearch.ort.PolicySnapshot.class), java.util.List.of()),
        new EncoderSet.ModelIdentity(absent, absent, absent, false, 768));
    Field searchField = KnowledgeServer.class.getDeclaredField("searchLifecycle");
    Field ingestField = KnowledgeServer.class.getDeclaredField("ingestLifecycle");
    Field initialField = KnowledgeServer.class.getDeclaredField("initialEncoderSet");
    searchField.setAccessible(true);
    ingestField.setAccessible(true);
    initialField.setAccessible(true);
    searchField.set(server, aRuntime);
    ingestField.set(server, greenRuntime);
    server.publishServingView(services);
    var servingField = KnowledgeServer.class.getDeclaredField("servingView");
    servingField.setAccessible(true);
    Object nativeView = servingField.get(server);
    var attach = nativeView.getClass().getDeclaredMethod("attachEncoderSet", EncoderSet.class);
    attach.setAccessible(true);
    attach.invoke(nativeView, owner);
    attachQueryOwner(server, nativeView);
    initialField.set(server, owner);

    var held = server.captureServingView();
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var build = executor.submit(() -> {
        var begin = KnowledgeServer.class.getDeclaredMethod("beginInPlaceCandidateBuild");
        begin.setAccessible(true);
        begin.invoke(server);
        return null;
      });
      var lexicalRetired = new AtomicReference<>(false);
      KnowledgeServer.ServingLease lexicalHeld = null;
      long lexicalDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      while (lexicalHeld == null && System.nanoTime() < lexicalDeadline) {
        var captured = server.captureServingView();
        if (captured.encoderSet() == null) lexicalHeld = captured;
        else captured.close();
      }
      assertTrue(lexicalHeld != null, "candidate must publish the lexical A view");
      try {
        lexicalHeld.onRetirement(() -> lexicalRetired.set(true));
        assertThrows(java.util.concurrent.ExecutionException.class,
            () -> build.get(8, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(lexicalRetired.get(), "drain refusal must notify issued lexical streams");
      } finally {
        lexicalHeld.close();
      }
      try (var resumed = server.captureServingView()) {
        assertSame(services, resumed.services());
        assertSame(owner, resumed.encoderSet());
      }
      assertFalse(owner.isClosed());
    } finally {
      held.close();
      server.close();
    }
  }

  @Test
  void interruptedRetirementRestoresUndestroyedServingView(@TempDir Path tempDir)
      throws Exception {
    var server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    var services = org.mockito.Mockito.mock(WorkerAppServices.class);
    server.publishServingView(services);
    var held = server.captureServingView();
    var failure = new AtomicReference<Throwable>();
    Thread waiter = new Thread(() -> {
      try { server.retireServingView(); }
      catch (Throwable refused) { failure.set(refused); }
    });
    try {
      waiter.start();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      boolean admissionClosed = false;
      while (!admissionClosed && System.nanoTime() < deadline) {
        try (var ignored = server.captureServingView()) { Thread.onSpinWait(); }
        catch (IllegalStateException expected) { admissionClosed = true; }
      }
      assertTrue(admissionClosed, "retirement must first fence new captures");
      waiter.interrupt();
      waiter.join(2_000);
      assertFalse(waiter.isAlive(), "retirement must finish after interruption");
      assertTrue(failure.get() instanceof java.io.IOException,
          "interrupted holder drain must refuse before destroying A");
      try (var restored = server.captureServingView()) {
        assertSame(services, restored.services());
      }
    } finally {
      waiter.interrupt();
      held.close();
      server.close();
    }
  }

  @BeforeAll
  static void ensureGlobalConfig() {
    if (ConfigStore.globalOrNull() == null) {
      ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder().contributeEnvRegistry().build()));
    }
  }

  @Test
  void failedInitializerWithoutPublishedSurfaceCannotProveNativeQuiescence(@TempDir Path tempDir)
      throws Exception {
    var server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    server.deferredModelInit = CompletableFuture.failedFuture(new IllegalStateException("partial native init"));
    try {
      org.junit.jupiter.api.Assertions.assertEquals(io.justsearch.app.api.NativeQuiescence.UNQUIESCED,
          server.nativeQuiescence());
    } finally {
      server.close();
    }
  }

  @Test
  void failedQueueCloseRetainsServerAndIndexExclusionUntilRetry(@TempDir Path tempDir) throws Exception {
    var server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    var queue = org.mockito.Mockito.mock(io.justsearch.indexerworker.queue.JobQueue.class);
    var queueField = KnowledgeServer.class.getDeclaredField("jobQueue");
    queueField.setAccessible(true);
    queueField.set(server, queue);
    var rootLock = org.mockito.Mockito.mock(io.justsearch.indexerworker.util.IndexRootLock.class);
    var lockField = KnowledgeServer.class.getDeclaredField("indexRootLock");
    lockField.setAccessible(true);
    lockField.set(server, rootLock);
    var failure = new java.io.IOException("queue connection still live");
    org.mockito.Mockito.doThrow(failure).doNothing().when(queue).close();
    try {
      assertSame(failure,
          assertThrows(java.io.IOException.class, server::close));
      assertFalse(server.awaitClosed(0));
      assertSame(queue, queueField.get(server));
      assertSame(rootLock, lockField.get(server));
      org.mockito.Mockito.verify(rootLock, org.mockito.Mockito.never()).close();
    } finally {
      server.close();
    }
    assertTrue(server.awaitClosed(0));
    var order = org.mockito.Mockito.inOrder(queue, rootLock);
    order.verify(queue, org.mockito.Mockito.times(2)).close();
    order.verify(rootLock).close();
  }

  @Test
  void failedIndexLockCloseRetainsOwnerAndShutdownRemainsIncomplete(@TempDir Path tempDir) throws Exception {
    var server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    var rootLock = org.mockito.Mockito.mock(io.justsearch.indexerworker.util.IndexRootLock.class);
    var field = KnowledgeServer.class.getDeclaredField("indexRootLock");
    field.setAccessible(true);
    field.set(server, rootLock);
    var failure = new java.io.UncheckedIOException(new java.io.IOException("native close uncertain"));
    org.mockito.Mockito.doThrow(failure).doNothing().when(rootLock).close();
    try {
      assertSame(failure,
          assertThrows(java.io.UncheckedIOException.class, server::close));
      assertFalse(server.awaitClosed(0));
      assertSame(rootLock, field.get(server));
    } finally {
      server.close();
    }
    assertTrue(server.awaitClosed(0));
    org.junit.jupiter.api.Assertions.assertNull(field.get(server));
    org.mockito.Mockito.verify(rootLock, org.mockito.Mockito.times(2)).close();
  }

  @Test
  void shutdownAttemptsBothFailedServiceOwnersBeforeReportingIncomplete(@TempDir Path tempDir)
      throws Exception {
    KnowledgeServer server = org.mockito.Mockito.spy(new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null));
    var old = org.mockito.Mockito.mock(WorkerAppServices.class);
    var candidate = org.mockito.Mockito.mock(DefaultWorkerAppServices.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    var release = new java.util.concurrent.atomic.AtomicBoolean();
    org.mockito.Mockito.doReturn(candidate).when(server).newAppServices();
    org.mockito.Mockito.doAnswer(call -> {
      if (!release.get()) throw new java.io.IOException("incumbent still live");
      return null;
    }).when(old).close();
    org.mockito.Mockito.doAnswer(call -> {
      if (!release.get()) throw new java.io.IOException("candidate still live");
      return null;
    }).when(candidate).close();
    server.appServices = old;
    var reconstruct = KnowledgeServer.class.getDeclaredMethod("reconstructAppServicesAfterDeferredUpgrade");
    reconstruct.setAccessible(true);
    try {
      assertThrows(java.lang.reflect.InvocationTargetException.class,
          () -> reconstruct.invoke(server));
      var failure = assertThrows(java.io.IOException.class, server::close);
      assertTrue(failure.getCause().getSuppressed().length > 0, "both failures must be retained");
      org.mockito.Mockito.verify(old, org.mockito.Mockito.times(2)).close();
      org.mockito.Mockito.verify(candidate, org.mockito.Mockito.times(2)).close();
      assertFalse(server.awaitClosed(0));
    } finally {
      release.set(true);
      server.close();
    }
    assertTrue(server.awaitClosed(0));
  }

  @Test
  void replacementRetainsFailedIncumbentAndFailedRollbackUntilRetry(@TempDir Path tempDir)
      throws Exception {
    KnowledgeServer server = org.mockito.Mockito.spy(new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null));
    var old = org.mockito.Mockito.mock(WorkerAppServices.class);
    var discarded = org.mockito.Mockito.mock(DefaultWorkerAppServices.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    var replacement = org.mockito.Mockito.mock(DefaultWorkerAppServices.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    org.mockito.Mockito.doReturn(discarded, replacement).when(server).newAppServices();
    org.mockito.Mockito.doThrow(new java.io.IOException("incumbent OCR live"))
        .doNothing().when(old).close();
    org.mockito.Mockito.doThrow(new java.io.IOException("rollback OCR live"))
        .doNothing().when(discarded).close();
    server.appServices = old;
    var reconstruct = KnowledgeServer.class.getDeclaredMethod("reconstructAppServicesAfterDeferredUpgrade");
    reconstruct.setAccessible(true);
    try {
      var failed = assertThrows(java.lang.reflect.InvocationTargetException.class,
          () -> reconstruct.invoke(server));
      assertTrue(failed.getCause() instanceof IllegalStateException);
      assertSame(old, server.appServices());
      org.mockito.Mockito.verify(discarded, org.mockito.Mockito.never()).startIndexingLoop();
      reconstruct.invoke(server);
      assertSame(replacement, server.appServices());
      var order = org.mockito.Mockito.inOrder(discarded, old, replacement);
      order.verify(old).close();
      order.verify(discarded, org.mockito.Mockito.times(2)).close(); // Rollback, then retained retry.
      order.verify(old).close();
      order.verify(replacement).startIndexingLoop();
    } finally {
      server.close();
    }
  }

  @Test
  void applicationServiceFailureRetainsServerUntilRetry(@TempDir Path tempDir) throws Exception {
    KnowledgeServer server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    var services = org.mockito.Mockito.mock(WorkerAppServices.class);
    server.appServices = services;
    org.mockito.Mockito.doThrow(new java.io.IOException("OCR child still alive"))
        .doNothing().when(services).close();
    assertThrows(java.io.IOException.class, server::close);
    assertFalse(server.awaitClosed(0));
    assertSame(services, server.appServices());
    server.close();
    assertTrue(server.awaitClosed(0));
    org.mockito.Mockito.verify(services, org.mockito.Mockito.times(2)).close();
  }

  @Test
  @DisplayName("false before any close, true after one that completes")
  void awaitClosedDistinguishesTheTwoStates(@TempDir Path tempDir) throws Exception {
    KnowledgeServer server =
        new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);

    assertFalse(
        server.awaitClosed(0),
        "a server that has never been closed must NOT report a completed close. If this is true"
            + " here, the latch is being counted down somewhere other than the end of close() and"
            + " the predicate has gone constant again.");

    server.close();

    assertTrue(
        server.awaitClosed(0),
        "after close() returns, the latch must be down — close() counts it down as its last"
            + " statement, so a false here means close() did not reach the end. This is the"
            + " direction EngineRoot.close() warns on.");
  }

  @Test
  @DisplayName("close does not abandon its published deferred-init future after five seconds")
  void closeWaitsPastTheFormerDeferredInitializationTimeout(@TempDir Path tempDir)
      throws Exception {
    KnowledgeServer server =
        new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    server.deferredModelInit = new CompletableFuture<>();
    var init = server.deferredModelInit;

    var closeFailure = new AtomicReference<Throwable>();
    Thread closer =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    server.close();
                  } catch (Throwable failure) {
                    closeFailure.set(failure);
                  }
                });
    try {
      assertFalse(
          server.awaitClosed(5_500),
          "the old five-second timeout must not let close race a still-publishing initializer");
    } finally {
      init.complete(null);
      closer.join(2_000L);
    }
    assertFalse(closer.isAlive());
    assertTrue(server.awaitClosed(0));
    assertTrue(closeFailure.get() == null, String.valueOf(closeFailure.get()));
  }

  @Test
  @DisplayName("an exceptional deferred initializer still permits complete cleanup")
  void exceptionalDeferredModelInitializationStillCloses(@TempDir Path tempDir) throws Exception {
    KnowledgeServer server =
        new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    server.deferredModelInit =
        CompletableFuture.failedFuture(new IllegalStateException("model initialization failed"));

    server.close();

    assertTrue(server.awaitClosed(0));
  }

  @Test
  void refusedNativeRetirementStopsProducersAndRetainsIndexLockUntilRetry(@TempDir Path tempDir)
      throws Exception {
    var server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    var handle = org.mockito.Mockito.mock(io.justsearch.ort.SessionHandle.class);
    var closeCalls = new java.util.concurrent.atomic.AtomicInteger();
    var nativeStatus = new AtomicReference<>(
        io.justsearch.ort.SessionHandle.RetirementStatus.ACTIVE);
    org.mockito.Mockito.doAnswer(call -> {
      nativeStatus.set(closeCalls.incrementAndGet() == 1
          ? io.justsearch.ort.SessionHandle.RetirementStatus.REFUSED
          : io.justsearch.ort.SessionHandle.RetirementStatus.RETIRED);
      return null;
    }).when(handle).close();
    org.mockito.Mockito.when(handle.retirementStatus()).thenAnswer(call -> nativeStatus.get());
    var pendingSurface = new InferenceSurface(java.util.Optional.empty(),
        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
        java.util.Optional.empty(), java.util.Optional.empty(),
        new io.justsearch.ort.PolicySnapshot(io.justsearch.ort.RuntimePolicy.defaults(),
            new java.util.TreeMap<>()), java.util.List.of(handle));
    var pendingSurfaceField = KnowledgeServer.class.getDeclaredField("pendingInitialSurface");
    pendingSurfaceField.setAccessible(true);
    pendingSurfaceField.set(server, pendingSurface);
    var services = org.mockito.Mockito.mock(WorkerAppServices.class);
    server.appServices = services;
    var rootLock = org.mockito.Mockito.mock(io.justsearch.indexerworker.util.IndexRootLock.class);
    var lockField = KnowledgeServer.class.getDeclaredField("indexRootLock");
    lockField.setAccessible(true);
    lockField.set(server, rootLock);

    assertThrows(java.io.IOException.class, server::close);
    assertFalse(server.awaitClosed(0));
    var closeOrder = org.mockito.Mockito.inOrder(services, handle);
    closeOrder.verify(services).close();
    closeOrder.verify(handle).close();
    org.mockito.Mockito.verify(rootLock, org.mockito.Mockito.never()).close();
    org.junit.jupiter.api.Assertions.assertEquals(
        io.justsearch.app.api.NativeQuiescence.UNQUIESCED, server.nativeQuiescence());

    server.close();
    assertTrue(server.awaitClosed(0));
    org.mockito.Mockito.verify(handle, org.mockito.Mockito.times(2)).close();
    org.mockito.Mockito.verify(services, org.mockito.Mockito.times(1)).close();
    org.mockito.Mockito.verify(rootLock).close();
    org.junit.jupiter.api.Assertions.assertEquals(
        io.justsearch.app.api.NativeQuiescence.QUIESCED, server.nativeQuiescence());
  }
  private static void attachQueryOwner(KnowledgeServer server, Object view) throws Exception {
    var querySelection = new io.justsearch.app.api.settings.QueryRoleSelection(
        io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled(),
        io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled());
    var surface = new InferenceSurface(java.util.Optional.empty(), java.util.Optional.empty(),
        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
        java.util.Optional.empty(),
        new io.justsearch.ort.PolicySnapshot(io.justsearch.ort.RuntimePolicy.defaults(),
            new java.util.TreeMap<>()), java.util.List.of(),
        new InferenceSurface.ComponentObservation(java.util.Optional.empty(), java.util.Set.of(),
            java.util.Set.of(), java.util.Optional.of(querySelection)));
    var owner = new QueryRoleSet(surface);
    var attach = view.getClass().getDeclaredMethod("attachQueryRoleSet", QueryRoleSet.class);
    attach.setAccessible(true);
    attach.invoke(view, owner);
    var field = KnowledgeServer.class.getDeclaredField("initialQueryRoleSet");
    field.setAccessible(true);
    field.set(server, owner);
  }
}
