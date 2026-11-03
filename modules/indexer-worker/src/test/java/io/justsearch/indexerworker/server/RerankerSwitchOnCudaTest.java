/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.status.operating.ProfileSource;
import io.justsearch.app.services.gpu.DevicePlanState;
import io.justsearch.app.services.gpu.GpuProfileResolver;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.ort.DeviceArenaPlans;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArenaPlan;
import io.justsearch.ort.OrtRunRecorder;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Switching Best match first on with a CUDA reranker when the published device-memory plan has no
 * reranker budget (live failure on 8613d76e02: "Prepared CUDA query session did not realize
 * CUDA"). The switch-on preparation warms under the candidate's plan once memory admission passed,
 * and a refused or abandoned one puts the published plan back.
 */
final class RerankerSwitchOnCudaTest {
  private static final String MODEL_PATH = "justsearch.rerank.model_path";
  private final DevicePlanState state = DevicePlanState.isolated();

  @AfterEach
  void withdraw() {
    state.publish(GpuProfileResolver.Resolution.unresolved());
    DeviceArenaPlans.withdraw(0);
  }

  @Test
  void switchingOnRealizesCudaUnderTheStagedCandidatePlan(@TempDir Path dir) throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L)) {
      Path models = f.selection.reranker().model().path().getParent();
      GpuArenaPlan offPlan = publishOff(dir, models);
      var on = resolution(dir, models, "true");
      try (var composition = f.composition()) {
        var realized = cudaSession(f, composition, true);
        var prepared = assertDoesNotThrow(() -> f.server.prepareQueryRoleSettings(switchOn(f),
            saved(dir, models, "true"), Set.of("rerankerEnabled"), disabled(),
            weights -> state.stage(on)),
            "switching on is not refused for a CUDA session that cannot realize CUDA");

        assertTrue(realized.get(), "the warm-up ran on the CUDA session");
        assertTrue(DeviceArenaPlans.current(0).orElseThrow().role(EncoderRole.RERANKER).isPresent(),
            "until the commit the candidate plan stays in effect for the prepared session");
        assertEquals(QueryRoleSelection.State.SELECTED, prepared.selection().reranker().state());
        assertEquals(f.selection.reranker(), prepared.selection().reranker(),
            "the retained files are selected");
        prepared.abort();
        assertEquals(Optional.of(offPlan), DeviceArenaPlans.current(0),
            "an abandoned switch-on puts the published plan back");
      }
    }
  }

  @Test
  void aRefusedSwitchOnPutsThePublishedPlanBack(@TempDir Path dir) throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L)) {
      Path models = f.selection.reranker().model().path().getParent();
      GpuArenaPlan offPlan = publishOff(dir, models);
      var on = resolution(dir, models, "true");
      var staged = new java.util.concurrent.atomic.AtomicReference<Optional<GpuArenaPlan>>();
      try (var composition = f.composition()) {
        cudaSession(f, composition, false);
        var refused = assertThrows(io.justsearch.app.api.settings.SettingsCommitOwner.Refused.class,
            () -> f.server.prepareQueryRoleSettings(switchOn(f), saved(dir, models, "true"),
                Set.of("rerankerEnabled"), disabled(), weights -> {
                  Runnable restore = state.stage(on);
                  staged.set(DeviceArenaPlans.current(0));
                  return restore;
                }));
        assertEquals("COMPONENT_PREPARATION_REQUIRED",
            refused.response().errorCode().orElseThrow());
      }
      assertEquals(on.plan(), staged.get(), "the preparation ran under the candidate plan");
      assertEquals(Optional.of(offPlan), DeviceArenaPlans.current(0),
          "the refused switch-on leaves the published plan in effect");
      f.assertA();
    }
  }

  /** Publishes the plan of the configuration saved while off; it has no reranker budget. */
  private GpuArenaPlan publishOff(Path dir, Path models) {
    state.publish(resolution(dir, models, "false"));
    GpuArenaPlan plan = DeviceArenaPlans.current(0).orElseThrow();
    assertTrue(plan.planned(), "a 12 GB device runs a planned profile");
    assertTrue(plan.role(EncoderRole.RERANKER).isEmpty(), "no published reranker budget");
    return plan;
  }

  /**
   * A planned-profile CUDA handle as NativeSessionHandle is one: it reads its device's current plan
   * for every call, and a call without a reranker budget runs on the CPU lease. With
   * {@code deviceWorks} false the GPU lease never realizes CUDA either.
   */
  private static AtomicBoolean cudaSession(KnowledgeServerQuerySettingsOwnerTest.QueryFixture f,
      org.mockito.MockedStatic<InferenceCompositionRoot> composition, boolean deviceWorks)
      throws Exception {
    var surface = f.freshSurface(false);
    SessionHandle sessions = surface.reranker().orElseThrow().sessions();
    var nativeSession = sessions.acquire(null).session();
    var realized = new AtomicBoolean();
    when(sessions.requiresGpuPacking()).thenReturn(true);
    when(sessions.arenaPlan()).thenAnswer(ignored -> DeviceArenaPlans.current(0)
        .map(plan -> plan.withRoleCapAtMost(EncoderRole.RERANKER, 4096)));
    when(sessions.acquire(any())).thenAnswer(ignored -> {
      realized.set(deviceWorks);
      return new SessionHandle.Lease(nativeSession, null, () -> {}, !deviceWorks,
          OrtRunRecorder.NOOP);
    });
    when(sessions.acquireCpu(any())).thenReturn(
        new SessionHandle.Lease(nativeSession, null, () -> {}, true, OrtRunRecorder.NOOP));
    when(sessions.isGpuAvailable()).thenAnswer(ignored -> realized.get());
    composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(),
        any(), any())).thenReturn(surface);
    return realized;
  }

  private static UiSettings switchOn(KnowledgeServerQuerySettingsOwnerTest.QueryFixture f) {
    UiSettings candidate = new UiSettings();
    candidate.setRerankerEnabled(true);
    candidate.setRerankerRestoreSelection(f.selection.reranker());
    return candidate;
  }

  private static QueryRoleSelection disabled() {
    return new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.disabled());
  }

  private static GpuProfileResolver.Resolution resolution(Path dir, Path models, String enabled) {
    GpuArenaPlan plan = GpuArenaPlan.resolve(new GpuArenaPlan.Facts(OptionalDouble.of(12_282),
        InferenceCompositionRoot.gpuRoleWeightsMiB(saved(dir, models, enabled)), Set.of(),
        Map.of(), GpuArenaPlan.ExternalChat.NONE, 1), GpuArenaPlan.Settings.none());
    return new GpuProfileResolver.Resolution(Optional.of(plan), ProfileSource.MEASURED, 0);
  }

  private static ResolvedConfig saved(Path dir, Path models, String enabled) {
    return new ResolvedConfigBuilder().putDefault("justsearch.data.dir", dir.toString())
        .putSettings("justsearch.rerank.enabled", enabled)
        .putSettings(MODEL_PATH, models.toString()).build();
  }
}
