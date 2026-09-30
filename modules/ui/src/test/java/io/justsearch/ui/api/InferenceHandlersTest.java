package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.ModeTransitionOutcome;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("InferenceHandlers unit tests")
final class InferenceHandlersTest {

  @Nested
  @DisplayName("computeHardwareTier")
  class ComputeHardwareTierTests {

    @Test
    @DisplayName("returns cpu_only when VRAM undetected and inference offline")
    void cpuOnlyWhenNoVramAndOffline() {
      assertEquals("cpu_only", InferenceHandlers.computeHardwareTier(-1, false, false));
    }

    @Test
    @DisplayName("returns gpu_unknown when VRAM undetected but inference available")
    void gpuUnknownWhenNoVramButAvailable() {
      assertEquals("gpu_unknown", InferenceHandlers.computeHardwareTier(-1, true, false));
    }

    @Test
    @DisplayName("returns gpu_unknown when VRAM undetected but inference starting")
    void gpuUnknownWhenNoVramButStarting() {
      assertEquals("gpu_unknown", InferenceHandlers.computeHardwareTier(-1, false, true));
    }

    @Test
    @DisplayName("returns gpu_12gb_plus for 12+ GB VRAM")
    void gpu12gbPlus() {
      // 12 GB = 12_884_901_888 bytes (above the ~10.7 GiB threshold)
      assertEquals("gpu_12gb_plus", InferenceHandlers.computeHardwareTier(12_884_901_888L, false, false));
    }

    @Test
    @DisplayName("returns gpu_8gb for 8 GB VRAM")
    void gpu8gb() {
      // 8 GB = 8_589_934_592 bytes (above ~7.0 GiB threshold, below ~10.7 GiB)
      assertEquals("gpu_8gb", InferenceHandlers.computeHardwareTier(8_589_934_592L, false, false));
    }

    @Test
    @DisplayName("returns gpu_lt_8gb for 4 GB VRAM")
    void gpuLt8gbFor4gb() {
      // 4 GB = 4_294_967_296 bytes (above ~3.3 GiB threshold, below ~7.0 GiB)
      assertEquals("gpu_lt_8gb", InferenceHandlers.computeHardwareTier(4_294_967_296L, false, false));
    }

    @Test
    @DisplayName("returns gpu_lt_8gb for under-4GB VRAM")
    void gpuLt8gbForUnder4gb() {
      // 2 GB = below 4 GB threshold but above 0
      assertEquals("gpu_lt_8gb", InferenceHandlers.computeHardwareTier(2_147_483_648L, false, false));
    }

    @Test
    @DisplayName("returns gpu_lt_8gb for zero VRAM")
    void gpuLt8gbForZeroVram() {
      assertEquals("gpu_lt_8gb", InferenceHandlers.computeHardwareTier(0, false, false));
    }

    @Test
    @DisplayName("VRAM tier is independent of online/starting flags")
    void vramTierIgnoresOnlineFlags() {
      // When VRAM is detected (>= 0), the tier is based on VRAM alone
      assertEquals("gpu_12gb_plus", InferenceHandlers.computeHardwareTier(12_884_901_888L, true, true));
      assertEquals("gpu_8gb", InferenceHandlers.computeHardwareTier(8_589_934_592L, true, false));
      assertEquals("gpu_lt_8gb", InferenceHandlers.computeHardwareTier(4_294_967_296L, false, true));
    }
  }

  /**
   * Tempdoc 804 §B6: {@code POST /api/inference/mode} answers with the transition's outcome. The
   * intent write is asynchronous, so the live {@code mode} in the payload can still be the previous
   * one — round 10 measured {@code {"success":true,"mode":"indexing"}} for a request to go ONLINE
   * and had no way to tell that apart from a completed switch.
   */
  @Nested
  @DisplayName("POST /api/inference/mode response shape")
  class SetInferenceModeResponseShape {

    @Test
    @DisplayName("live mode still lagging => state=recorded, requested echoed")
    void reportsRecordedWhileEngineHasNotConverged() {
      Map<String, Object> payload = invokeSetMode("online", "indexing");

      assertEquals(Boolean.TRUE, payload.get("success"), "the intent write itself succeeded");
      assertEquals("online", payload.get("requested"));
      assertEquals("indexing", payload.get("mode"), "mode stays the LIVE mode");
      assertEquals(
          "recorded",
          payload.get("state"),
          "a live mode that differs from the requested one must not read as a completed switch");
    }

    @Test
    @DisplayName("live mode already at target => state=converged")
    void reportsConvergedWhenLiveModeMatches() {
      Map<String, Object> payload = invokeSetMode("online", "online");

      assertEquals("online", payload.get("requested"));
      assertEquals("online", payload.get("mode"));
      assertEquals("converged", payload.get("state"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeSetMode(String requested, String liveMode) {
      Context ctx = mock(Context.class);
      when(ctx.status(any(int.class))).thenReturn(ctx);
      when(ctx.json(any())).thenReturn(ctx);
      when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("mode", requested));

      InferenceHandlers handlers =
          new InferenceHandlers(
              null, null, null, null, null, null, null, new StubBrainRuntime(liveMode));
      handlers.handleSetInferenceMode(ctx);

      ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
      verify(ctx).json(captor.capture());
      return captor.getValue();
    }
  }

  /** Returns a fixed live mode so the outcome's converged/recorded branch is deterministic. */
  private static final class StubBrainRuntime implements BrainRuntimeService {
    private final String liveMode;

    StubBrainRuntime(String liveMode) {
      this.liveMode = liveMode;
    }

    @Override
    public String reloadInference() {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public ModeTransitionOutcome switchInferenceMode(String mode) {
      return ModeTransitionOutcome.of(mode, liveMode);
    }

    @Override
    public java.util.concurrent.CompletionStage<io.justsearch.app.api.OfflineProcessingOutcome>
        triggerOfflineProcessing(io.justsearch.core.context.EngineContext context,
            java.util.function.Consumer<io.justsearch.app.api.OfflineProcessingOutcome> progress) {
      throw new UnsupportedOperationException("not used by this test");
    }
  }
}
