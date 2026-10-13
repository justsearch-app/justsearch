/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyMap;

import ai.onnxruntime.OrtSession;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class NativeRunAbandonmentReasonTest {
  @Test
  void pendingAbandonmentSaysNativeWorkNeverEntered() throws Exception {
    var model = Path.of("missing", "model.onnx");
    var gpu = mock(OrtSession.class);
    var dispatch = new CountDownLatch(1);
    var dispatched = new AtomicReference<Thread>();
    try (var handle = NativeSessionHandle.builder("abandon-reason-test", model)
        .shouldUseGpu(() -> true).runtime(RuntimePolicy.defaults())
        .policy(ModelSessionPolicy.forFallback(new GpuSessionConfig(0, 512L * 1024 * 1024), null, true, false, 60_000L)).build()) {
      set(handle, "gpuSession", gpu); set(handle, "gpuSessionAttempted", true); set(handle, "gpuAvailable", true);
      var request = new SessionAcquisitionRequest(SessionAcquisitionRequest.Urgency.FOREGROUND,
          System.nanoTime() + TimeUnit.SECONDS.toNanos(5), () -> false, false, true);
      try (var lease = handle.acquire(request)) {
        var missed = assertThrows(SessionAcquireDeadlineExceededException.class,
            () -> DetachableRun.runWithin(lease, Map.of(), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100), action -> {
              var thread = new Thread(() -> {
                try { dispatch.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                action.run();
              });
              dispatched.set(thread); return thread;
            }));
        assertEquals(SessionAcquireDeadlineExceededException.Phase.RUN, missed.phase());
        assertTrue(missed.getMessage().contains("abandoned before native entry"), missed.getMessage());
        assertFalse(missed.getMessage().contains("continues detached"), missed.getMessage());
      } finally {
        dispatch.countDown();
        if (dispatched.get() != null) dispatched.get().join(5_000);
      }
      verify(gpu, never()).run(anyMap());
    }
  }
  private static void set(Object owner, String name, Object value) throws Exception {
    var field = NativeSessionHandle.class.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
  }
}
