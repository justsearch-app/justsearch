/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.OrtSession;
import io.justsearch.app.api.settings.CompositionV2;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.ort.OrtRunRecorder;
import io.justsearch.ort.SessionHandle;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Device-realization regressions for physical query settings preparation. */
final class KnowledgeServerQueryPreparationDeviceTest {
  @Test
  void cudaCandidateIsRealizedBeforeBesideAndInPlacePreparationReturns(@TempDir Path dir)
      throws Exception {
    for (long freeBytes : List.of(2048L, 512L)) {
      try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(
               dir.resolve("mode-" + freeBytes), freeBytes);
          var composition = f.composition()) {
        var candidate = controlledSurface(f, false, null, null, null);
        var restored = controlledSurface(f, false, null, null, null);
        composition.when(() -> InferenceCompositionRoot.composeQueryRoles(
            any(), any(), any(), any(), any())).thenReturn(candidate.surface(), restored.surface());

        var prepared = f.prepare();

        assertTrue(candidate.realized().get(), "CUDA must be realized before B is returned");
        verify(candidate.sessions()).acquire(any());
        assertTrue(candidate.surface().reranker().orElseThrow().sessions().isGpuAvailable());
        prepared.abort();
        if (freeBytes == 512L) {
          assertTrue(restored.realized().get(), "restored CUDA A must be realized before publication");
          f.assertRestored();
        } else {
          f.assertA();
        }
      }
    }
  }

  @Test
  void cudaCandidateCpuFallbackRefusesWithCompositionAndRestoresExactA(@TempDir Path dir)
      throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 512L);
        var composition = f.composition()) {
      var candidateCpu = controlledSurface(f, true, null, null, null);
      var restoredGpu = controlledSurface(f, false, null, null, null);
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(
          any(), any(), any(), any(), any())).thenReturn(candidateCpu.surface(), restoredGpu.surface());

      var refused = assertThrows(SettingsCommitOwner.Refused.class, f::prepare);

      assertEquals("Prepared CUDA query session did not realize CUDA", refused.getCause().getMessage());
      assertInstanceOf(CompositionV2.class,
          refused.response().errorDetails().get("composition"));
      assertEquals("IN_PLACE", ((CompositionV2) refused.response().errorDetails()
          .get("composition")).mode());
      assertTrue(restoredGpu.realized().get());
      f.assertRestored();
    }
  }

  @Test
  void gpuSourceRestoredOnCpuRetainsUnavailableRecoveryWithBothReasons(@TempDir Path dir)
      throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 512L);
        var composition = f.composition()) {
      var candidateCpu = controlledSurface(f, true, null, null, null);
      var restoredCpu = controlledSurface(f, true, null, null, null);
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(
          any(), any(), any(), any(), any())).thenReturn(candidateCpu.surface(), restoredCpu.surface());

      var refused = assertThrows(SettingsCommitOwner.Refused.class, f::prepare);

      assertEquals("Prepared CUDA query session did not realize CUDA", refused.getCause().getMessage());
      assertEquals(1, refused.getCause().getSuppressed().length);
      assertEquals("Prepared CUDA query session did not realize CUDA",
          refused.getCause().getSuppressed()[0].getMessage());
      verify(f.component).transition(eq(ComponentState.UNAVAILABLE),
          eq("component.recovery_failed"), argThat(evidence ->
              occurrences(evidence, "Prepared CUDA query session did not realize CUDA") == 2));
      assertNotNull(recoveryReservation(f.server));
      try (var serving = f.server.captureServingView()) {
        assertSame(f.degraded, serving.services());
      }
    }
  }

  @Test
  void candidateRuntimeAndRestorationLinkageErrorRetainContextAndBothCauses(@TempDir Path dir)
      throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 512L);
        var composition = f.composition()) {
      var candidate = controlledSurface(f, false, null, null,
          new IllegalStateException("B native runtime failed"));
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(
          any(), any(), any(), any(), any())).thenReturn(candidate.surface())
          .thenThrow(new LinkageError("A native linkage failed"));

      var refused = assertThrows(SettingsCommitOwner.Refused.class, f::prepare);

      assertEquals("B native runtime failed", refused.getCause().getMessage());
      assertEquals(1, refused.getCause().getSuppressed().length);
      assertInstanceOf(LinkageError.class, refused.getCause().getSuppressed()[0]);
      assertEquals("A native linkage failed", refused.getCause().getSuppressed()[0].getMessage());
      verify(f.component).transition(eq(ComponentState.UNAVAILABLE),
          eq("component.recovery_failed"), argThat(evidence ->
              evidence.contains("B native runtime failed")
                  && evidence.contains("A native linkage failed")));
      assertNotNull(recoveryReservation(f.server));
      try (var serving = f.server.captureServingView()) {
        assertSame(f.degraded, serving.services());
        assertSame(f.index, serving.encoderSet());
      }
    }
  }

  @Test
  void recoveryRetryErrorCompletesFailedAndRetainsAllThreeReasons(@TempDir Path dir)
      throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 512L);
        var composition = f.composition()) {
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(
          any(), any(), any(), any(), any()))
          .thenThrow(new IllegalStateException("B initial refusal"))
          .thenThrow(new IllegalStateException("A initial restoration refusal"))
          .thenThrow(new LinkageError("A retry linkage failure"));
      assertThrows(SettingsCommitOwner.Refused.class, f::prepare);
      Object retainedRecovery = recoveryReservation(f.server);
      assertNotNull(retainedRecovery);

      var before = f.component.snapshot();
      var failed = new EngineComponentSnapshot.Component(before.spec(), ComponentState.UNAVAILABLE,
          "component.recovery_failed", before.stateSince(), before.stateSinceMonotonicNanos(),
          before.appliedVersion(), before.desiredVersion(), before.lastCompose(), 1,
          "initial B and A refusal");
      var admitted = new EngineComponentSnapshot.Component(before.spec(), ComponentState.STARTING,
          "component.recovering", before.stateSince(), before.stateSinceMonotonicNanos(),
          before.appliedVersion(), before.desiredVersion(), before.lastCompose(), 2,
          "retrying exact A");
      var current = new AtomicReference<>(failed);
      var began = new AtomicBoolean();
      var completedEvidence = new AtomicReference<String>();
      when(f.component.snapshot()).thenAnswer(ignored -> current.get());
      var request = new io.justsearch.core.component.ComponentRecoveryAction.Request() {
        @Override public EngineComponentSnapshot.Component expected() { return failed; }
        @Override public EngineComponentSnapshot.Component current() { return current.get(); }
        @Override public Optional<EngineComponentSnapshot.Component> admitted() {
          return began.get() ? Optional.of(admitted) : Optional.empty();
        }
        @Override public boolean begin() {
          if (!began.compareAndSet(false, true)) return false;
          current.set(admitted);
          return true;
        }
        @Override public Optional<EngineComponentSnapshot.Component> complete(
            EngineComponentSnapshot.Component expectedCurrent, ComponentState state,
            String reasonCode, String evidence) {
          assertSame(admitted, expectedCurrent);
          assertEquals(ComponentState.FAILED, state);
          assertEquals("component.recovery_failed", reasonCode);
          assertTrue(evidence.contains("B initial refusal"));
          assertTrue(evidence.contains("A initial restoration refusal"));
          assertTrue(evidence.contains("A retry linkage failure"));
          completedEvidence.set(evidence);
          var terminal = new EngineComponentSnapshot.Component(admitted.spec(), state, reasonCode,
              admitted.stateSince(), admitted.stateSinceMonotonicNanos(),
              admitted.appliedVersion(), admitted.desiredVersion(), admitted.lastCompose(),
              admitted.recoveryAttempts(), evidence);
          current.set(terminal);
          return Optional.of(terminal);
        }
        @Override public boolean cancelled() { return false; }
      };
      set(f.server, "running", true);
      try {
        var result = f.server.recoverEncoders(request);

        assertEquals(io.justsearch.core.component.ComponentRecoveryAction.Outcome.FAILED,
            result.outcome());
        assertSame(current.get(), result.observation());
        assertNotNull(completedEvidence.get());
        assertSame(retainedRecovery, recoveryReservation(f.server));
        try (var serving = f.server.captureServingView()) {
          assertSame(f.degraded, serving.services());
          assertSame(f.index, serving.encoderSet());
        }
      } finally {
        set(f.server, "running", false);
      }
    }
  }

  @Test
  void realizedCpuSourceAllowsCpuRestoration(@TempDir Path dir) throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 512L, true);
        var composition = f.composition()) {
      var candidateCpu = controlledSurface(f, true, null, null, null);
      var restoredCpu = controlledSurface(f, true, null, null, null);
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(
          any(), any(), any(), any(), any())).thenReturn(candidateCpu.surface(), restoredCpu.surface());

      var refused = assertThrows(SettingsCommitOwner.Refused.class, f::prepare);

      assertEquals("Prepared CUDA query session did not realize CUDA", refused.getCause().getMessage());
      assertTrue(restoredCpu.realized().get());
      assertNull(recoveryReservation(f.server));
      f.assertRestored();
    }
  }

  @Test
  void nonFiniteCudaWarmupRefusesBeforePublicationAndRestoresA(@TempDir Path dir)
      throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 512L);
        var composition = f.composition()) {
      var nonFinite = controlledSurface(f, false, null, null, null, Float.NaN);
      var restored = controlledSurface(f, false, null, null, null);
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(
          any(), any(), any(), any(), any())).thenReturn(nonFinite.surface(), restored.surface());

      var refused = assertThrows(SettingsCommitOwner.Refused.class, f::prepare);

      assertEquals("Prepared query warm-up did not complete within its budget",
          refused.getCause().getMessage());
      assertTrue(nonFinite.realized().get());
      assertTrue(restored.realized().get());
      f.assertRestored();
    }
  }

  @Test
  void timedOutNativeInferenceRetainsQueryOwnerUntilActualExitInBothModes(@TempDir Path dir)
      throws Exception {
    assertTimeout(Duration.ofSeconds(35), () -> {
      for (long freeBytes : List.of(2048L, 512L)) {
        try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(
                 dir.resolve("timeout-" + freeBytes), freeBytes)) {
          f.server = shortBudgetSpy(f.server);
          var entered = new CountDownLatch(1);
          var release = new CountDownLatch(1);
          var blocked = controlledSurface(f, false, entered, release, null);
          var preparation = CompletableFuture.supplyAsync(() -> {
            try (var composition = f.composition()) {
              composition.when(() -> InferenceCompositionRoot.composeQueryRoles(
                  any(), any(), any(), any(), any())).thenReturn(blocked.surface());
              return captureFailure(f::prepare);
            }
          });
          try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(preparation.isDone());
            Throwable failure = preparation.get(13, TimeUnit.SECONDS);
            assertInstanceOf(SettingsCommitOwner.Refused.class, failure);
            assertNotNull(recoveryReservation(f.server));
            verify(blocked.sessions(), never()).close();
            assertFalse(blocked.closed().get());
            assertEquals(io.justsearch.app.api.NativeQuiescence.UNQUIESCED,
                f.server.nativeQuiescence());
            try (var serving = f.server.captureServingView()) {
              assertSame(freeBytes == 512L ? f.degraded : f.producer, serving.services());
            }
          } finally {
            release.countDown();
          }
          assertTrue(blocked.nativeExited().await(2, TimeUnit.SECONDS));
          f.server.close();
          assertTrue(blocked.closed().get(), "shutdown retries cleanup after native actual exit");
        }
      }
    });
  }

  @Test
  void preparationInterruptionPreservesCancellationAndDefersCleanupUntilActualExit(
      @TempDir Path dir) throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 2048L)) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var blocked = controlledSurface(f, false, entered, release, null);
      var failure = new AtomicReference<Throwable>();
      var interruptedAtExit = new AtomicBoolean();
      var preparing = new Thread(() -> {
        try (var composition = f.composition()) {
          composition.when(() -> InferenceCompositionRoot.composeQueryRoles(
              any(), any(), any(), any(), any())).thenReturn(blocked.surface());
          f.prepare();
        }
        catch (Throwable thrown) { failure.set(thrown); }
        finally { interruptedAtExit.set(Thread.currentThread().isInterrupted()); }
      });
      preparing.start();
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        preparing.interrupt();
        preparing.join(2_000);

        assertFalse(preparing.isAlive());
        assertInstanceOf(CancellationException.class, failure.get());
        assertTrue(interruptedAtExit.get());
        verify(blocked.sessions(), never()).close();
        assertNotNull(recoveryReservation(f.server),
            "interrupted cleanup retains the rejected query while native inference still owns it");
        assertEquals(io.justsearch.app.api.NativeQuiescence.UNQUIESCED,
            f.server.nativeQuiescence());
      } finally {
        release.countDown();
        preparing.join(5_000);
      }
      assertTrue(blocked.nativeExited().await(2, TimeUnit.SECONDS));
      f.server.close();
      assertTrue(blocked.closed().get());
    }
  }

  private static KnowledgeServer shortBudgetSpy(KnowledgeServer server) {
    var spied = spy(server);
    doReturn(1_000L).when(spied).queryPreparationWarmupBudgetMillis();
    return spied;
  }

  private static ControlledSurface controlledSurface(
      KnowledgeServerQuerySettingsOwnerTest.QueryFixture fixture, boolean cpu,
      CountDownLatch entered, CountDownLatch release, RuntimeException inferenceFailure)
      throws Exception {
    return controlledSurface(fixture, cpu, entered, release, inferenceFailure, 0.25f);
  }

  private static ControlledSurface controlledSurface(
      KnowledgeServerQuerySettingsOwnerTest.QueryFixture fixture, boolean cpu,
      CountDownLatch entered, CountDownLatch release, RuntimeException inferenceFailure,
      float score) throws Exception {
    InferenceSurface surface = fixture.freshSurface(cpu);
    SessionHandle sessions = surface.reranker().orElseThrow().sessions();
    var realized = new AtomicBoolean();
    var closed = new AtomicBoolean();
    var nativeExited = new CountDownLatch(1);
    var nativeSession = mock(OrtSession.class);
    var nativeResult = mock(OrtSession.Result.class);
    var scores = mock(ai.onnxruntime.OnnxValue.class);
    when(scores.getValue()).thenReturn(new float[][] {{score}});
    when(nativeResult.get(0)).thenReturn(scores);
    when(nativeSession.run(anyMap())).thenAnswer(ignored -> {
      if (entered != null) entered.countDown();
      try {
        if (release != null && !release.await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test native inference was not released");
        }
        if (inferenceFailure != null) throw inferenceFailure;
        return nativeResult;
      } finally {
        nativeExited.countDown();
      }
    });
    when(sessions.acquire(any())).thenAnswer(ignored -> {
      realized.set(true);
      return new SessionHandle.Lease(nativeSession, null, () -> {}, cpu, OrtRunRecorder.NOOP);
    });
    when(sessions.isGpuAvailable()).thenAnswer(ignored -> realized.get() && !cpu);
    doAnswer(ignored -> { closed.set(true); return null; }).when(sessions).close();
    return new ControlledSurface(surface, sessions, realized, closed, nativeExited);
  }

  private static Throwable captureFailure(Runnable action) {
    try {
      action.run();
      return null;
    } catch (Throwable failure) {
      return failure;
    }
  }

  private static Object recoveryReservation(KnowledgeServer server) throws Exception {
    Field field = KnowledgeServer.class.getDeclaredField("encoderRecoveryReservation");
    field.setAccessible(true);
    return field.get(server);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static int occurrences(String value, String needle) {
    int count = 0;
    for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0; offset += needle.length()) {
      count++;
    }
    return count;
  }

  private record ControlledSurface(
      InferenceSurface surface,
      SessionHandle sessions,
      AtomicBoolean realized,
      AtomicBoolean closed,
      CountDownLatch nativeExited) {}
}
