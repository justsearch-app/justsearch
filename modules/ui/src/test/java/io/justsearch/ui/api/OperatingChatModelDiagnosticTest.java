/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.chat.ChatState;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.LifecycleCounters;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.operating.DegradationCode;
import io.justsearch.app.api.status.operating.OperatingStateView;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.gpu.CudaDevice;
import io.justsearch.app.services.gpu.DeviceFacts;
import io.justsearch.app.services.gpu.GpuProfileResolver;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.core.component.ComponentState;
import io.justsearch.ort.EncoderRole;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Saved chat paths reach operating status through the real settings rebuild and device planner. */
final class OperatingChatModelDiagnosticTest {
  private static final String OBSERVED = "2026-10-14T10:15:02Z";
  private static final Map<EncoderRole, Double> WEIGHTS = Map.of(
      EncoderRole.EMBEDDING, 598.3, EncoderRole.SPLADE, 474.2,
      EncoderRole.NER, 257.2, EncoderRole.RERANKER, 600.3);

  @TempDir Path models;
  private Object previousAutoDetected;
  private Object previousPlanner;

  @BeforeEach
  void capturePlanner() throws Exception {
    previousAutoDetected = field("autoDetected").get(null);
    previousPlanner = field("devicePlanner").get(null);
  }

  @AfterEach
  void restorePlanner() throws Exception {
    field("autoDetected").set(null, previousAutoDetected);
    field("devicePlanner").set(null, previousPlanner);
  }

  @Test
  void missingSavedChatIsUnavailableAndNamesItsMissingFile() {
    Path missing = models.resolve("missing-saved-chat.gguf");
    var view = assemble(rebuild(missing), ComponentState.ABSENT, null, "Down");

    assertEquals(ChatState.FAILED, view.chat().state(),
        "an absent saved chat model must report unavailable chat rather than OFF");
    assertEquals("MODEL_LOAD_FAILED", view.chat().reason());
    assertNull(view.chat().reasonCode(), "the reason code remains the generative component's own");
    assertTrue(view.degradations().stream().anyMatch(degradation ->
        degradation.cause().contains("chat_model_missing:")
            && degradation.cause().contains(missing.getFileName().toString())),
        "the availability diagnostic must identify the missing saved chat file");
    assertFalse(view.degradations().stream().anyMatch(degradation ->
        degradation.cause().contains("encoder_memory")),
        "a missing chat file must not be described as an encoder memory shortage");
  }

  @Test
  void unmeasuredSavedChatExplainsItsPlacementWithoutAnEncoderShortage() throws Exception {
    Path custom = Files.writeString(models.resolve("custom-small-chat.gguf"), "custom fixture");
    var plan = rebuild(custom);
    var view = assemble(plan, ComponentState.ABSENT, null, "Down");

    assertTrue(view.degradations().stream().anyMatch(degradation ->
        degradation.code().token().equals("chat_model_unmeasured")
            && degradation.cause().contains("chat_model_unmeasured")),
        "an extrapolated chat allocation must have a visible placement diagnostic");
    assertFalse(view.degradations().stream().anyMatch(degradation ->
        degradation.code() == DegradationCode.USER_SETTING_UNMET
            && degradation.cause().contains("chat_model_unmeasured")),
        "an unmeasured allocation is diagnostic, never an unmet user setting");
    assertFalse(view.degradations().stream().anyMatch(degradation ->
        degradation.cause().contains("encoder_memory")),
        "the small custom model leaves enough memory for the encoder plan");
  }

  @Test
  void pendingIdentityHasItsOwnStatusDiagnostic() {
    var plan = new GpuProfileResolver.Resolution(Optional.empty(), null, -1, false,
        io.justsearch.app.api.status.operating.Profile.UNRESOLVED,
        java.util.Set.of("chat_identity_pending"));
    var view = assemble(plan, ComponentState.ABSENT, null, "Down");
    assertTrue(view.degradations().stream().anyMatch(degradation ->
        degradation.code().token().equals("chat_identity_pending")
            && degradation.cause().equals("chat_identity_pending")),
        "status must name chat_identity_pending while verification is running");
    assertFalse(view.degradations().stream().anyMatch(degradation ->
        degradation.code() == DegradationCode.USER_SETTING_UNMET),
        "identity verification does not make a user setting unmet");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void visionOffDuringVerificationNamesPendingIdentity(boolean vision) throws Exception {
    var plan = new GpuProfileResolver.Resolution(Optional.empty(), null, -1, false,
        io.justsearch.app.api.status.operating.Profile.UNRESOLVED,
        java.util.Set.of("chat_identity_pending"));
    var online = org.mockito.Mockito.mock(io.justsearch.app.api.OnlineAiService.class,
        org.mockito.Mockito.withSettings().extraInterfaces(
            io.justsearch.app.api.OnlineAiRuntimeIntrospection.class));
    org.mockito.Mockito.when(((io.justsearch.app.api.OnlineAiRuntimeIntrospection) online)
        .hasVisionCapability()).thenReturn(vision);
    try (var components = new StatusComponentFixture()) {
      var graph = components.capabilities();
      var handler = new StatusLifecycleHandler(online,
          org.mockito.Mockito.mock(io.justsearch.agent.api.AgentService.class), () -> null,
          null, null, null, Instant.parse(OBSERVED), () -> "OK", null, null, null,
          graph.worker(), graph.inference());
      components.attach(handler);
      handler.setDevicePlanSupplier(() -> plan);
      handler.sampleAndBuildStatusSnapshot();
      var overlay = StatusLifecycleHandler.class.getDeclaredMethod("overlayVduCapability",
          io.justsearch.app.api.status.WorkerOperationalView.class);
      overlay.setAccessible(true);
      var worker = io.justsearch.app.api.status.WorkerOperationalViewBuilder.builder()
          .core(new io.justsearch.app.api.status.CoreIndexView(true, 0, 0, "IDLE", 0, 0)).build();
      var observed = (io.justsearch.app.api.status.WorkerOperationalView) overlay.invoke(handler, worker);
      assertEquals(vision ? null : "chat_identity_pending",
          observed.visualExtraction() == null ? null : observed.visualExtraction().vduBlockedReason(),
          "vision off during identity verification must name chat_identity_pending");
    }
  }

  @Test
  void missingCandidateDoesNotReplaceTheReadyServersObservedState() {
    var view = assemble(rebuild(models.resolve("missing-next-chat.gguf")), ComponentState.READY,
        "inference.context_reduced", "Healthy");

    assertEquals(ChatState.READY, view.chat().state(), "the existing serving chat remains ready");
    assertNull(view.chat().reason());
    assertEquals("inference.context_reduced", view.chat().reasonCode());
    assertEquals(OBSERVED, view.chat().since());
  }

  private GpuProfileResolver.Resolution rebuild(Path model) {
    ConfigStoreRebuilder.rememberAutoDetected(Map.of(
        EnvRegistry.MODELS_DIR.configKey(), models.toString(),
        EnvRegistry.GPU_ENABLED.configKey(), "true", EnvRegistry.GPU_LAYERS.configKey(), "99"));
    CudaDevice device = new CudaDevice(0, Optional.empty(), Optional.empty(), Optional.empty(),
        12_282L << 20);
    DeviceFacts facts = new DeviceFacts(DeviceFacts.ProbeStatus.RAN_OK, 0, 12_080,
        List.of(device), Optional.of(device));
    ConfigStoreRebuilder.rememberDevicePlanner(GpuProfileResolver.planner(() -> facts,
        ignored -> WEIGHTS));
    var settings = new UiSettings();
    settings.setLlmModelPath(model.toAbsolutePath().toString());
    settings.setGpuLayers(99);
    var store = new ConfigStore(ResolvedConfig.builder().build());
    ConfigStoreRebuilder.rebuild(store, settings);
    assertEquals(model.toAbsolutePath(), store.get().ai().llmModelPath(),
        "the saved path reaches the published configuration");
    return ConfigStoreRebuilder.planOf(store.get()).orElseThrow();
  }

  private static OperatingStateView assemble(GpuProfileResolver.Resolution plan,
      ComponentState generativeState, String reason, String engineState) {
    var ready = new LifecycleSnapshotV2.Component(ComponentState.READY, null, OBSERVED);
    var generative = new LifecycleSnapshotV2.Component(generativeState, reason, OBSERVED);
    var lifecycle = new LifecycleSnapshotV2(LifecycleSnapshotV2.SCHEMA_VERSION, OBSERVED,
        new LifecycleSnapshotV2.Lifecycle(LifecycleState.LIFECYCLE_STATE_READY, null, null),
        new LifecycleSnapshotV2.Components(ready, ready, ready, generative));
    var inference = new InferenceRuntimeView("ONLINE", null, false, null,
        new LifecycleCounters(0L, 0L, 0L), true, engineState, "", "", "");
    var assembler = new OperatingStateAssembler();
    assembler.setDevicePlan(() -> plan);
    return assembler.assemble(lifecycle, inference, PowerStatusView.unknown(), null, 0L, false,
        2_000L, Instant.parse(OBSERVED), null);
  }

  private static Field field(String name) throws Exception {
    Field field = ConfigStoreRebuilder.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }
}
