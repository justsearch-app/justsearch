package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.ModeTransitionOutcome;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.lifecycle.ReasonRetainingComponentHandle;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

@DisplayName("InferenceHandlers unit tests")
final class InferenceHandlersTest {

  @TempDir Path temp;

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
      when(ctx.path()).thenReturn("/api/inference/mode");

      InferenceHandlers handlers =
          new InferenceHandlers(
              null, null, null, null, null, null, null, new StubBrainRuntime(liveMode));
      handlers.handleSetInferenceMode(ctx);

      ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
      verify(ctx).json(captor.capture());
      return captor.getValue();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void modeWriteForwardsRequestIdentityAndKeyAndReturnsWitness() throws Exception {
    var context = io.justsearch.app.services.intent.EngineProvenance.internal("mode-wire-test",
        io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,
        io.justsearch.core.context.EngineContext.Urgency.FOREGROUND);
    var key = io.justsearch.app.api.operations.OperationKeys.generate(Clock.systemUTC());
    var service = mock(BrainRuntimeService.class);
    when(service.switchInferenceMode("online", context, key))
        .thenReturn(ModeTransitionOutcome.of("online", "indexing").withReceipt(key, 7L));
    Context ctx = mock(Context.class);
    when(ctx.json(any())).thenReturn(ctx);
    when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(context);
    when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("mode", "online", "idempotencyKey", key));
    new InferenceHandlers(null, null, null, null, null, null, null, service).handleSetInferenceMode(ctx);
    var captor = ArgumentCaptor.forClass(Map.class);
    verify(ctx).json(captor.capture());
    assertEquals(key, captor.getValue().get("operationKey"));
    assertEquals(7L, captor.getValue().get("acceptedRevision"));
    assertEquals("recorded", captor.getValue().get("state"));
    verify(service).switchInferenceMode("online", context, key);
  }

  @Test
  void settingsRefusalUsesTheRestErrorContract() throws Exception {
    var failure = io.justsearch.agent.api.registry.OperationResult.failure(
        "Settings refused", "SETTINGS_READ_ONLY", Map.of(), false);
    assertModeFailure(new io.justsearch.app.api.settings.SettingsCommitOwner.Refused(failure),
        409, "SETTINGS_READ_ONLY", "POLICY", false);
  }

  @Test
  void acceptedRefusalPreservesQueryIdentityOnTheWire() throws Exception {
    String key = io.justsearch.app.api.operations.OperationKeys.generate(Clock.systemUTC());
    var failure = new io.justsearch.agent.api.registry.OperationResult(false, "Settings changed",
        java.util.Optional.empty(), Map.of("operationKey", key, "operationRecordId", 23L),
        java.util.Optional.of("VERSION_CONFLICT"), Map.of(), java.util.Optional.of(false));
    var payload = assertModeFailure(new io.justsearch.app.api.settings.SettingsCommitOwner.Refused(failure),
        409, "VERSION_CONFLICT", "VALIDATION", false);
    assertEquals(key, payload.get("operationKey"));
    assertEquals(23L, payload.get("operationRecordId"));
  }

  @Test
  void storageRefusalsUseTheRestErrorContract() throws Exception {
    for (var code : io.justsearch.app.api.operations.OperationStoreException.Code.values()) {
      boolean capacity = code == io.justsearch.app.api.operations.OperationStoreException.Code.OPERATIONS_CAPACITY;
      boolean storage = code == io.justsearch.app.api.operations.OperationStoreException.Code.STORAGE_FAILED
          || code == io.justsearch.app.api.operations.OperationStoreException.Code.CHILD_ACCEPTANCE_REFUSED;
      boolean invalid = code == io.justsearch.app.api.operations.OperationStoreException.Code.INVALID_OPERATION_KEY;
      String publicCode = switch (code) {
        case INVALID_OPERATION_KEY -> "OPERATION_KEY_INVALID";
        case OPERATION_EXPIRED -> "OPERATION_KEY_EXPIRED";
        case STORAGE_FAILED -> "OPERATION_STORAGE_FAILED";
        default -> code.name();
      };
      assertModeFailure(new io.justsearch.app.api.operations.OperationStoreException(code, null),
          capacity ? 503 : storage ? 500 : invalid ? 400 : 409,
          publicCode,
          capacity ? "TRANSIENT" : storage ? "PERMANENT" : "VALIDATION", capacity);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void rawModeFailureWithUnsetIntentKeepsTheOriginalWireError() {
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY);
    assertNull(settings.inspect().settings().getChatEnabled());
    try (var components = TestEngineComponents.fourComponents()) {
      var handle = new ReasonRetainingComponentHandle(components.handle("generative"));
      var online = mock(io.justsearch.app.api.OnlineAiService.class);
      org.mockito.Mockito.doThrow(new IllegalStateException(new ModeTransitionException(
          ModeTransitionException.Reason.EXECUTABLE_NOT_FOUND, "missing runtime")))
          .when(online).switchToOnlineMode();
      Context ctx = mock(Context.class);
      when(ctx.path()).thenReturn("/api/inference/mode");
      when(ctx.status(any(int.class))).thenReturn(ctx);
      when(ctx.json(any())).thenReturn(ctx);
      when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("mode", "online"));
      new InferenceHandlers(online, null, null, null, settings, null, handle, null)
          .handleSetInferenceMode(ctx);
      verify(ctx).status(500);
      var payload = ArgumentCaptor.forClass(Map.class);
      verify(ctx).json(payload.capture());
      assertEquals("MODE_SWITCH_FAILED", payload.getValue().get("errorCode"));
      assertEquals(ComponentState.ABSENT, handle.snapshot().state());
    }
  }

  @Test
  void rawModeFailureCannotOverwriteAcceptedDisableReenableIntent() throws Exception {
    UiSettingsStore settings = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, temp.resolve("settings.json"));
    UiSettings initial = new UiSettings();
    initial.setChatEnabled(true);
    settings.replacePrepared(settings.prepare(
        initial, new io.justsearch.app.api.settings.SettingsWitness(0, null)));
    var config = new ConfigStore(ConfigStoreRebuilder.prepare(initial));
    try (var operations = new SqliteOperationStore(temp.resolve("operations.db"));
        var components = TestEngineComponents.fourComponents()) {
      var owner = new SettingsCommitCoordinator(
          settings,
          config,
          () -> { throw new AssertionError("Unexpected settings restart"); },
          candidate -> io.justsearch.agent.api.registry.OperationResult.success("committed"));
      var runner = new OperationAttemptRunnerImpl(
          operations,
          Clock.systemUTC(),
          Set.of(
              io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
              io.justsearch.agent.api.registry.OperationKind.RECONFIGURE),
          owner);
      var spec = new RuntimeSpecStore(settings, runner);
      var handle = new ReasonRetainingComponentHandle(components.handle("generative"));
      handle.transition(ComponentState.STARTING,
          LifecycleReasonCode.INFERENCE_STARTING.code(), "request in flight");
      var online = mock(io.justsearch.app.api.OnlineAiService.class);
      when(online.getCurrentMode()).thenReturn("offline");
      org.mockito.Mockito.doAnswer(ignored -> {
        spec.setChatEnabled(false);
        handle.transition(ComponentState.ABSENT,
            LifecycleReasonCode.INFERENCE_DEACTIVATED.code(), "newer disabled intent");
        spec.setChatEnabled(true);
        handle.transition(ComponentState.STARTING,
            LifecycleReasonCode.INFERENCE_STARTING.code(), "newer activation intent");
        throw new IllegalStateException(new ModeTransitionException(
            ModeTransitionException.Reason.EXECUTABLE_NOT_FOUND, "missing runtime"));
      }).when(online).switchToOnlineMode();
      Context ctx = mock(Context.class);
      when(ctx.path()).thenReturn("/api/inference/mode");
      when(ctx.status(any(int.class))).thenReturn(ctx);
      when(ctx.json(any())).thenReturn(ctx);
      when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("mode", "online"));

      new InferenceHandlers(
          online, null, null, null, settings, null, handle, null).handleSetInferenceMode(ctx);

      assertEquals(2L, settings.inspect().witness().acceptedRevision());
      assertEquals(ComponentState.STARTING, handle.snapshot().state());
      assertEquals(LifecycleReasonCode.INFERENCE_STARTING.code(),
          handle.snapshot().reasonCode(), "old request failure is fenced by accepted settings ABA");
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> assertModeFailure(RuntimeException failure, int status, String code,
      String classification, boolean retryable) throws Exception {
    var service = mock(BrainRuntimeService.class);
    when(service.switchInferenceMode(any(), any(), any())).thenThrow(failure);
    Context ctx = mock(Context.class);
    when(ctx.path()).thenReturn("/api/inference/mode");
    when(ctx.status(any(int.class))).thenReturn(ctx);
    when(ctx.json(any())).thenReturn(ctx);
    when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("mode", "online"));
    new InferenceHandlers(null, null, null, null, null, null, null, service).handleSetInferenceMode(ctx);
    verify(ctx).status(status);
    var captor = ArgumentCaptor.forClass(Map.class);
    verify(ctx).json(captor.capture());
    assertTrue(captor.getValue().get("error") instanceof String);
    assertEquals(code, captor.getValue().get("errorCode"));
    assertEquals(classification, captor.getValue().get("errorClass"));
    assertEquals(retryable, captor.getValue().get("retryable"));
    return captor.getValue();
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
    public ModeTransitionOutcome switchInferenceMode(String mode,
        io.justsearch.core.context.EngineContext context, String idempotencyKey) {
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
