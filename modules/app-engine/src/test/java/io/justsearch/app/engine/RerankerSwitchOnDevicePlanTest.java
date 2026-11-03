/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.gpu.DevicePlanState;
import io.justsearch.app.services.gpu.GpuProfileResolver;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.ort.DeviceArenaPlans;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArenaPlan;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Best match first switched on from a published device-memory plan without a reranker budget (it
 * booted off, or its files were missing until a consented download). A planned CUDA session sends
 * a call without a role budget to the CPU, so the switch-on preparation must warm its reranker
 * under the candidate's plan, and a refused preparation must leave the published plan in effect.
 * On 8613d76e02 this preparation was refused on a real CUDA GPU ("Prepared CUDA query session did
 * not realize CUDA").
 */
final class RerankerSwitchOnDevicePlanTest {
  private DevicePlanState state;

  @AfterEach
  void restore() {
    ConfigStoreRebuilder.rememberDevicePlanner(null);
    if (state != null) state.publish(GpuProfileResolver.Resolution.unresolved());
    DeviceArenaPlans.withdraw(0);
  }

  @Test
  void switchOnPreparesUnderTheCandidatePlanAndARefusalKeepsThePublishedPlan() throws Exception {
    ConfigStoreRebuilder.DevicePlanner planner = config -> GpuProfileResolver.resolve(config,
        DevicePlanTestSupport.device(12_282), weights(Boolean.TRUE.equals(
            config.ai().reranker().enabled())), Set.of());
    ConfigStoreRebuilder.rememberDevicePlanner(planner);
    UiSettings off = new UiSettings();
    off.setRerankerEnabled(false);
    UiSettings on = new UiSettings();
    on.setRerankerEnabled(true);
    ResolvedConfig published = ConfigStoreRebuilder.prepare(off);
    ResolvedConfig desired = ConfigStoreRebuilder.prepare(on);
    state = DevicePlanState.isolated();
    state.publish(ConfigStoreRebuilder.planOf(published).orElseThrow());
    GpuArenaPlan offPlan = DeviceArenaPlans.current(0).orElseThrow();
    assertTrue(offPlan.planned(), "a 12 GB device runs a planned profile");
    assertTrue(offPlan.role(EncoderRole.RERANKER).isEmpty(),
        "coverage starts from a published plan without a reranker budget");

    var root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
        30_000L, 5_000);
    root.followDevicePlan(state);
    var duringPreparation = new AtomicReference<Optional<GpuArenaPlan>>();
    // The Worker's preparation, reduced to what its warm-up reads: the device's current plan once
    // memory admission passed (where KnowledgeServer stages the candidate plan), then a refusal
    // that abandons the preparation (where it restores the published plan).
    KnowledgeServer server = mock(KnowledgeServer.class, call -> {
      if (!"prepareQueryRoleSettings".equals(call.getMethod().getName())) return null;
      Runnable restore = () -> {};
      if (call.getArguments().length == 5) {
        restore = call.<Supplier<Runnable>>getArgument(4).get();
      }
      duringPreparation.set(DeviceArenaPlans.current(0));
      restore.run();
      throw new IllegalStateException("Query model preparation refused");
    });
    var field = EngineRoot.class.getDeclaredField("server");
    field.setAccessible(true);
    field.set(root, server);
    var owner = root.queryRoleSettingsOwner(() -> new QueryRoleSelection(
        QueryRoleSelection.Role.disabled(), QueryRoleSelection.Role.disabled()));

    assertThrows(IllegalStateException.class,
        () -> owner.prepare(on, desired, Set.of("rerankerEnabled")));

    GpuArenaPlan warmedUnder = duringPreparation.get().orElseThrow();
    assertTrue(warmedUnder.role(EncoderRole.RERANKER).isPresent(),
        "the switch-on warm-up runs under a plan with a reranker budget");
    assertEquals(ConfigStoreRebuilder.planOf(desired).orElseThrow().plan().orElseThrow(),
        warmedUnder, "the plan is the candidate configuration's own");
    assertEquals(Optional.of(offPlan), DeviceArenaPlans.current(0),
        "the refused switch-on leaves the published plan in effect");
    assertFalse(DeviceArenaPlans.current(0).orElseThrow().role(EncoderRole.RERANKER).isPresent());
  }

  private static Map<EncoderRole, Double> weights(boolean reranker) {
    var weights = new EnumMap<EncoderRole, Double>(DevicePlanTestSupport.DEFAULT_WEIGHTS);
    if (!reranker) weights.remove(EncoderRole.RERANKER);
    return weights;
  }
}
