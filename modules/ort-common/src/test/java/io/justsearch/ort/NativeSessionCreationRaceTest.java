/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ai.onnxruntime.OrtSession;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Session readiness can disappear between selection and CPU owner entry. Permission still wins. */
final class NativeSessionCreationRaceTest {
  @Test
  void losingTheReadyCpuSessionCannotReenableConstruction() throws Exception {
    var previous = mock(OrtSession.class);
    var replacement = mock(OrtSession.class);
    var gone = new AtomicBoolean();
    try (var _ = mockConstruction(OrtSession.SessionOptions.class);
        var cache = mockStatic(OnnxSessionCache.class);
        var handle = NativeSessionHandle.builder("cpu-selection-race", Path.of("missing", "model.onnx"))
            .runtime(RuntimePolicy.defaults())
            .policy(ModelSessionPolicy.forFallback(null, null, true, false, 60_000L)).build()) {
      cache.when(() -> OnnxSessionCache.createCachedSession(any(), any(), any(), any())).thenReturn(replacement);
      var field = NativeSessionHandle.class.getDeclaredField("cpuSession"); field.setAccessible(true); field.set(handle, previous);
      var request = new SessionAcquisitionRequest(SessionAcquisitionRequest.Urgency.FOREGROUND,
          System.nanoTime() + TimeUnit.SECONDS.toNanos(5), () -> {
            // The live authority checkpoint models a concurrent lifecycle removal after outer selection.
            boolean enteringCpuOwner = StackWalker.getInstance().walk(frames -> frames.anyMatch(
                frame -> frame.getClassName().equals(NativeSessionHandle.class.getName())
                    && frame.getMethodName().equals("getCpuSession")));
            if (enteringCpuOwner && gone.compareAndSet(false, true)) {
              try { field.set(handle, null); } catch (IllegalAccessException e) { throw new AssertionError(e); }
            }
            return false;
          }, false, false);
      var loading = assertThrows(SessionAcquireDeadlineExceededException.class, () -> {
        try (var ignored = handle.acquire(request)) { }
      }, "losing readiness must report loading, never construct on the request thread");
      assertTrue(gone.get(), "the race must happen inside the CPU owner");
      assertEquals(SessionAcquireDeadlineExceededException.Phase.LOADING, loading.phase());
      cache.verifyNoInteractions();
    }
  }
}
