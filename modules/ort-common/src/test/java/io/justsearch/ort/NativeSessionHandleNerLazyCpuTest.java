/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.configuration.model.*;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** M3/M3b/M3c/P4: NER uses the handle's existing CPU fallback lifecycle. */
final class NativeSessionHandleNerLazyCpuTest {
  private static final Path MODEL = Path.of("missing", "ner.onnx");

  @Test
  void idleGpuNerIsColdAndFirstFallbackCreatesOneReusableSession() throws Exception {
    OrtSession cpu = mock(OrtSession.class);
    OrtSession.Result entities = mock(OrtSession.Result.class);
    when(cpu.run(anyMap())).thenReturn(entities);
    try (var cache = mockStatic(OnnxSessionCache.class)) {
      cache.when(() -> OnnxSessionCache.createCachedSession(any(), eq(MODEL), any(), any())).thenReturn(cpu);
      try (var handle = assertDoesNotThrow(() -> handle(ExecutionProvider.CUDA))) {
        assertNull(handle.peekCpuSession(), "idle GPU NER must not create its CPU fallback");
        cache.verifyNoInteractions();
        for (int cycle = 0; cycle < 4; cycle++) {
          try (var lease = handle.acquire(bulk())) {
            assertTrue(lease.isCpu());
            assertSame(cpu, lease.session());
            assertSame(entities, lease.run(Map.of()));
          }
          handle.releaseGpu();
        }
        cache.verify(() -> OnnxSessionCache.createCachedSession(any(), eq(MODEL), any(), any()), times(1));
      }
    }
    verify(cpu).close();
  }

  @Test
  void cpuPrimaryIsStillCreatedEagerly() throws Exception {
    OrtSession cpu = mock(OrtSession.class);
    try (var cache = mockStatic(OnnxSessionCache.class)) {
      cache.when(() -> OnnxSessionCache.createCachedSession(any(), eq(MODEL), any(), any())).thenReturn(cpu);
      try (var handle = handle(ExecutionProvider.CPU)) {
        assertSame(cpu, handle.peekCpuSession());
        assertFalse(handle.isGpuAvailable());
      }
      cache.verify(() -> OnnxSessionCache.createCachedSession(any(), eq(MODEL), any(), any()), times(1));
    }
  }

  @Test
  void concurrentFirstFallbacksCreateOneCpuSession() throws Exception {
    OrtSession cpu = mock(OrtSession.class);
    AtomicInteger creations = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(4);
    try (var handle = assertDoesNotThrow(() -> handle(ExecutionProvider.CUDA))) {
      assertNull(handle.peekCpuSession());
      var start = new CountDownLatch(1);
      List<Future<OrtSession>> futures = new ArrayList<>();
      for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> {
        try (var cache = mockStatic(OnnxSessionCache.class)) {
          cache.when(() -> OnnxSessionCache.createCachedSession(any(), eq(MODEL), any(), any()))
              .thenAnswer(call -> { creations.incrementAndGet(); return cpu; });
          assertTrue(start.await(5, TimeUnit.SECONDS));
          try (var lease = handle.acquire(bulk())) { return lease.session(); }
        }
      }));
      start.countDown();
      for (var future : futures) assertSame(cpu, future.get(10, TimeUnit.SECONDS));
      assertEquals(1, creations.get());
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
    verify(cpu).close();
  }

  @Test
  void retirementDuringCreationClosesCandidateWithoutPublishingIt() throws Exception {
    OrtSession cpu = mock(OrtSession.class);
    var creating = new CountDownLatch(1);
    var complete = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try (var handle = assertDoesNotThrow(() -> handle(ExecutionProvider.CUDA))) {
      Thread worker = new Thread(() -> {
        try (var cache = mockStatic(OnnxSessionCache.class)) {
          cache.when(() -> OnnxSessionCache.createCachedSession(any(), eq(MODEL), any(), any()))
              .thenAnswer(call -> {
                creating.countDown();
                assertTrue(complete.await(5, TimeUnit.SECONDS));
                return cpu;
              });
          try (var lease = handle.acquire(bulk())) { fail("retired session was acquired: " + lease.session()); }
        } catch (Throwable thrown) { failure.set(thrown); }
      }, "ner-lazy-creation");
      worker.start();
      try {
        assertTrue(creating.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> handle.retire(Duration.ZERO));
      } finally {
        complete.countDown();
        worker.join(5000);
      }
      assertFalse(worker.isAlive());
      assertInstanceOf(SessionRetiredException.class, failure.get());
      assertNull(handle.peekCpuSession());
    }
    verify(cpu).close();
  }

  @Test
  void creationFailureIsVisibleAndNextRequestRetriesCpuWithoutGpuRetry() throws Exception {
    OrtSession cpu = mock(OrtSession.class);
    try (var cache = mockStatic(OnnxSessionCache.class)) {
      cache.when(() -> OnnxSessionCache.createCachedSession(any(), eq(MODEL), any(), any()))
          .thenThrow(new OrtException("missing external weights")).thenReturn(cpu);
      try (var handle = assertDoesNotThrow(() -> handle(ExecutionProvider.CUDA))) {
        assertThrows(SessionTemporarilyUnavailableException.class, () -> handle.acquire(bulk()));
        assertNull(handle.peekCpuSession());
        try (var lease = handle.acquire(bulk())) { assertSame(cpu, lease.session()); }
      }
      cache.verify(() -> OnnxSessionCache.createCachedSession(any(), eq(MODEL), any(), any()), times(2));
    }
  }

  @Test
  void repeatedChatHandoffsReuseCpuAndRouteBackToGpuUntilGpuCreationFails() throws Exception {
    AtomicBoolean gpuFree = new AtomicBoolean(true);
    OrtSession cpu = mock(OrtSession.class);
    try (var cache = mockStatic(OnnxSessionCache.class)) {
      cache.when(() -> OnnxSessionCache.createCachedSession(any(), eq(MODEL), any(), any())).thenReturn(cpu);
      try (var handle = handle(ExecutionProvider.CUDA, gpuFree)) {
        for (int cycle = 0; cycle < 3; cycle++) {
          OrtSession gpu = mock(OrtSession.class);
          set(handle, "gpuSession", gpu);
          set(handle, "gpuSessionAttempted", true);
          set(handle, "gpuAvailable", true);
          gpuFree.set(true);
          try (var lease = handle.acquire(bulk())) {
            assertFalse(lease.isCpu(), "chat released the GPU");
            assertSame(gpu, lease.session());
          }
          gpuFree.set(false);
          handle.releaseGpu();
          verify(gpu).close();
          try (var lease = handle.acquire(bulk())) {
            assertTrue(lease.isCpu(), "chat holds the GPU");
            assertSame(cpu, lease.session());
          }
        }
        // A failed GPU creation must not be retried merely because its retry interval elapsed.
        gpuFree.set(true);
        set(handle, "gpuSessionAttempted", true);
        set(handle, "gpuAvailable", false);
        set(handle, "gpuFailedAtMs", 1L);
        for (int call = 0; call < 3; call++) {
          try (var lease = handle.acquire(bulk())) { assertSame(cpu, lease.session()); }
        }
        cache.verify(() -> OnnxSessionCache.createCachedSession(any(), eq(MODEL), any(), any()), times(1));
        cache.verify(() -> OnnxSessionCache.createCachedGpuSession(any(), any(), any()), never());
      }
    }
    verify(cpu).close();
  }

  @Test
  void cancellationDuringCpuCreationHandsOutNoLeaseAndRetainsOneOwnedSession() throws Exception {
    AtomicBoolean cancelled = new AtomicBoolean(false);
    OrtSession cpu = mock(OrtSession.class);
    try (var cache = mockStatic(OnnxSessionCache.class)) {
      cache.when(() -> OnnxSessionCache.createCachedSession(any(), eq(MODEL), any(), any()))
          .thenAnswer(call -> { cancelled.set(true); return cpu; });
      try (var handle = assertDoesNotThrow(() -> handle(ExecutionProvider.CUDA))) {
        var request = SessionAcquisitionRequest.within(SessionAcquisitionRequest.Urgency.BACKGROUND,
            Duration.ofSeconds(5), cancelled::get);
        assertThrows(CancellationException.class, () -> handle.acquire(request));
        cancelled.set(false);
        try (var lease = handle.acquire(bulk())) { assertSame(cpu, lease.session()); }
        cache.verify(() -> OnnxSessionCache.createCachedSession(any(), eq(MODEL), any(), any()), times(1));
      }
    }
    verify(cpu).close();
  }

  private static void set(NativeSessionHandle handle, String field, Object value) throws Exception {
    var member = NativeSessionHandle.class.getDeclaredField(field);
    member.setAccessible(true);
    member.set(handle, value);
  }

  private static NativeSessionHandle handle(ExecutionProvider provider) throws OrtException {
    return handle(provider, new AtomicBoolean(false));
  }

  private static NativeSessionHandle handle(ExecutionProvider provider, AtomicBoolean gpuFree) throws OrtException {
    var variant = VariantSelection.optimal(MODEL, ModelPrecision.FP32, provider);
    var policy = ModelSessionPolicyResolver.resolve(EncoderRole.NER,
        TestResolvedConfigHelper.withDefaults(), HardwareProfile.gpuFull(12_000_000_000L), variant);
    assertFalse(policy.lifecycle().gpuRetryEnabled(), "NER must retain its no-GPU-retry policy");
    return NativeSessionHandle.builder("ner-lazy-test", MODEL).gpuModelPath(provider == ExecutionProvider.CUDA ? MODEL : null)
        .shouldUseGpu(gpuFree::get).runtime(RuntimePolicy.defaults()).policy(policy).build();
  }

  private static SessionAcquisitionRequest bulk() {
    return SessionAcquisitionRequest.within(SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofSeconds(10));
  }
}
