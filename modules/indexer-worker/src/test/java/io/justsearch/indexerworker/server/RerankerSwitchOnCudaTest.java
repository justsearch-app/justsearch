/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Switching Best match first on with a CUDA reranker (live failure on 8613d76e02: "Prepared CUDA
 * query session did not realize CUDA"). The device-memory plan published while the switch is off
 * is the one the warm-up of the On preparation runs under.
 */
final class RerankerSwitchOnCudaTest {
  private static final String MODEL_PATH = "justsearch.rerank.model_path";

  @Test
  void thePlanWhileOffKeepsTheRerankerBudgetOfThePlanWhileOn(@TempDir Path dir) throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L)) {
      Path models = f.selection.reranker().model().path().getParent();
      var on = InferenceCompositionRoot.gpuRoleWeightsMiB(saved(dir, models, "true"));
      assertTrue(on.containsKey(EncoderRole.RERANKER), "the fixture's CUDA variant is planned");
      assertEquals(on, InferenceCompositionRoot.gpuRoleWeightsMiB(saved(dir, models, "false")));
    }
  }

  @Test
  void switchingOnRealizesCudaUnderThePlanPublishedWhileOff(@TempDir Path dir) throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L)) {
      Path models = f.selection.reranker().model().path().getParent();
      // What DeviceArenaPlans holds while off: the published configuration's own plan.
      GpuArenaPlan publishedWhileOff = GpuArenaPlan.resolve(new GpuArenaPlan.Facts(
          OptionalDouble.of(12_282),
          InferenceCompositionRoot.gpuRoleWeightsMiB(saved(dir, models, "false")),
          Set.of(), Map.of(), GpuArenaPlan.ExternalChat.NONE, 1), GpuArenaPlan.Settings.none());
      assertTrue(publishedWhileOff.planned(), "a 12 GB device runs a planned profile");
      ResolvedConfig desired = saved(dir, models, "true");
      try (var composition = f.composition()) {
        var surface = f.freshSurface(false);
        SessionHandle sessions = surface.reranker().orElseThrow().sessions();
        var nativeSession = sessions.acquire(null).session();
        var realized = new AtomicBoolean();
        // A planned-profile CUDA handle: GPU work needs a role budget in the current plan.
        when(sessions.requiresGpuPacking()).thenReturn(true);
        when(sessions.arenaPlan()).thenReturn(Optional.of(publishedWhileOff));
        when(sessions.acquire(any())).thenAnswer(ignored -> {
          realized.set(true);
          return new SessionHandle.Lease(nativeSession, null, () -> {}, false, OrtRunRecorder.NOOP);
        });
        when(sessions.acquireCpu(any())).thenReturn(
            new SessionHandle.Lease(nativeSession, null, () -> {}, true, OrtRunRecorder.NOOP));
        when(sessions.isGpuAvailable()).thenAnswer(ignored -> realized.get());
        composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(),
            any(), any())).thenReturn(surface);
        UiSettings candidate = new UiSettings();
        candidate.setRerankerModelPath(models.toString());
        candidate.setRerankerEnabled(true);
        candidate.setRerankerRestoreSelection(f.selection.reranker());

        var prepared = assertDoesNotThrow(() -> f.server.prepareQueryRoleSettings(candidate,
            desired, Set.of("rerankerEnabled", MODEL_PATH), f.selection),
            "switching on is not refused for a CUDA session that cannot realize CUDA");

        assertTrue(realized.get(), "the warm-up ran on the CUDA session");
        assertEquals(QueryRoleSelection.State.SELECTED, prepared.selection().reranker().state());
        assertEquals(f.selection.reranker().model(), prepared.selection().reranker().model());
        prepared.abort();
      }
    }
  }

  private static ResolvedConfig saved(Path dir, Path models, String enabled) {
    return new ResolvedConfigBuilder().putDefault("justsearch.data.dir", dir.toString())
        .putSettings("justsearch.rerank.enabled", enabled)
        .putSettings(MODEL_PATH, models.toString()).build();
  }
}
