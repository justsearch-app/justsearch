/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.status.operating.Profile;
import io.justsearch.app.api.status.operating.ProfileSource;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.ort.DeviceArenaPlans;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArenaPlan;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Spec E step E7b: the plan the Engine resolves from its device facts, the configuration it applies
 * at the auto-detected ordinal, and P4 (an explicit setting is never changed by the plan; a plan it
 * breaks is reported as unmet).
 */
final class GpuProfileResolverTest {

  private static final Map<EncoderRole, Double> WEIGHTS = Map.of(
      EncoderRole.EMBEDDING, 598.3, EncoderRole.SPLADE, 474.2, EncoderRole.NER, 257.2,
      EncoderRole.RERANKER, 600.3);
  private static final Set<ChatModelProfile> STANDARD = Set.of(ChatModelProfile.STANDARD);
  private static final Set<ChatModelProfile> WITH_COMPACT =
      Set.of(ChatModelProfile.STANDARD, ChatModelProfile.COMPACT);

  @AfterEach
  void restore() {
    ConfigStoreRebuilder.rememberDevicePlanner(null);
    ConfigStoreRebuilder.rememberAutoDetected(Map.of());
    DeviceArenaPlans.withdraw(0);
  }

  @Test
  void p12AppliesTheNineBAt32kWithTheProjectorOnTheCpuAndTheCaps() {
    GpuProfileResolver.Resolution p12 = resolve(config(Map.of()), 12_282, STANDARD);
    assertEquals(Profile.P12, p12.statusProfile());
    assertEquals(ProfileSource.MEASURED, p12.source());
    assertEquals(0, p12.cudaDeviceId());
    assertEquals(Map.of(
            "justsearch.mmproj.offload", "false",
            "justsearch.gpu.memory_plan", "p12",
            "justsearch.embed.gpu_mem_mb", "2466",
            "justsearch.splade.gpu_mem_mb", "2342",
            "justsearch.ner.gpu_mem_mb", "2125",
            "justsearch.rerank.gpu_mem_mb", "2468",
            "justsearch.context.size", "32768"),
        GpuProfileResolver.contributions(p12, true));
    assertFalse(GpuProfileResolver.contributions(p12, false).containsKey("justsearch.context.size"),
        "the pinned rung applies to a GPU-backed chat only");
  }

  @Test
  void p16PlusKeepsTheProjectorOnTheGpuAndP8TakesTheFourBAt8k() {
    Map<String, String> p16 =
        GpuProfileResolver.contributions(resolve(config(Map.of()), 16_376, STANDARD), true);
    assertEquals("p16_plus", p16.get("justsearch.gpu.memory_plan"));
    assertFalse(p16.containsKey("justsearch.mmproj.offload"));
    assertEquals("5434", p16.get("justsearch.embed.gpu_mem_mb"));

    Map<String, String> p8 =
        GpuProfileResolver.contributions(resolve(config(Map.of()), 8_188, WITH_COMPACT), true);
    assertEquals("p8", p8.get("justsearch.gpu.memory_plan"));
    assertEquals("compact", p8.get("justsearch.chat.profile"));
    assertEquals("8192", p8.get("justsearch.context.size"));
    assertEquals("false", p8.get("justsearch.mmproj.offload"));
    assertEquals("1388", p8.get("justsearch.embed.gpu_mem_mb"));
  }

  @Test
  void unplannedGpuKeepsConstantsWhileNoGpuSelectsCpuTier() {
    GpuProfileResolver.Resolution unplanned = resolve(config(Map.of()), 10_240, STANDARD);
    assertEquals(Profile.P8, unplanned.statusProfile());
    assertEquals(Map.of("justsearch.mmproj.offload", "false"),
        GpuProfileResolver.contributions(unplanned, true));

    GpuProfileResolver.Resolution below = resolve(config(Map.of()), 6_144, STANDARD);
    assertEquals(Profile.P8, below.statusProfile(), "below P8 reads as the P8 class");
    assertTrue(below.belowP8(), "and says it is below the P8 boundary");
    assertEquals(Map.of("justsearch.mmproj.offload", "false"),
        GpuProfileResolver.contributions(below, true), "today's constants: no plan applies");
    assertFalse(unplanned.belowP8(), "a P8 device with the 9B is not below the boundary");

    DeviceFacts noDevice = new DeviceFacts(DeviceFacts.ProbeStatus.RAN_NO_DEVICE, 100, 0,
        List.of(), Optional.empty());
    GpuProfileResolver.Resolution noGpu =
        GpuProfileResolver.resolve(config(Map.of()), noDevice, WEIGHTS, STANDARD);
    assertEquals(Profile.NO_GPU, noGpu.statusProfile());
    assertEquals(Map.of("justsearch.gpu.memory_plan", "no_gpu", "justsearch.gpu.enabled", "false",
        "justsearch.gpu.layers", "0",
        "justsearch.mmproj.offload", "false", "justsearch.context.size", "8192"),
        GpuProfileResolver.contributions(noGpu, true));

    GpuProfileResolver.Resolution unresolved =
        GpuProfileResolver.resolve(config(Map.of()), DeviceFacts.notRun(), WEIGHTS, STANDARD);
    assertEquals(Profile.UNRESOLVED, unresolved.statusProfile());
    assertEquals(Map.of(), GpuProfileResolver.contributions(unresolved, true));
  }

  @Test
  void bgeM3IsNeverPlacedOnTheGpuByAPlanUnlessTheUserPutsItThere() {
    Map<EncoderRole, Double> withBge = new HashMap<>(WEIGHTS);
    withBge.remove(EncoderRole.EMBEDDING);
    withBge.put(EncoderRole.BGE_M3, 1_100.0);
    var device = DevicePlanTest.device(16_376);
    var planned = GpuProfileResolver.resolve(config(Map.of()), device, withBge, STANDARD);
    assertEquals("false", GpuProfileResolver.contributions(planned, true)
        .get("justsearch.bgem3.gpu_enabled"));

    var explicit = GpuProfileResolver.resolve(
        config(Map.of("justsearch.bgem3.gpu_enabled", "true")), device, withBge, STANDARD);
    GpuArenaPlan.Role role = explicit.plan().orElseThrow().role(EncoderRole.BGE_M3).orElseThrow();
    assertTrue(role.explicitCap(), "an explicit bgem3.gpu_enabled=true is an explicit cap");
    assertFalse(GpuProfileResolver.contributions(explicit, true)
        .containsKey("justsearch.bgem3.gpu_enabled"));
    assertEquals(ProfileSource.USER_OVERRIDE, explicit.source());
  }

  /** P4: explicit settings outrank every value the plan contributes, and stay as set. */
  @Test
  void explicitSettingsKeepWinningAndABrokenPlanIsReportedUnmet() {
    ConfigStoreRebuilder.rememberAutoDetected(Map.of("justsearch.gpu.layers", "99"));
    ConfigStoreRebuilder.rememberDevicePlanner(cfg -> GpuProfileResolver.resolve(
        cfg, DevicePlanTest.device(12_282), WEIGHTS, STANDARD));
    UiSettings settings = new UiSettings();
    settings.setContextLength(131_072);

    ResolvedConfig prepared = ConfigStoreRebuilder.prepare(settings);

    assertEquals(131_072, prepared.ai().contextSize(), "the explicit window is kept as set");
    GpuProfileResolver.Resolution plan = ConfigStoreRebuilder.planOf(prepared).orElseThrow();
    assertEquals(Profile.P12, plan.statusProfile(), "the profile is kept");
    assertEquals(ProfileSource.USER_OVERRIDE, plan.source());
    assertEquals(Set.of(GpuArenaPlan.UNMET_CONTEXT, GpuArenaPlan.UNMET_ENCODER_MEMORY),
        plan.unmetSettings());
    assertEquals(6_144, prepared.ai().embedding().gpuMemMb(),
        "a plan the setting breaks applies nothing to the encoders: today's caps");
    assertEquals("", prepared.ai().gpuMemoryPlan());

    // A plan that holds applies beside an explicit cap, which it never changes.
    ConfigStoreRebuilder.rememberDevicePlanner(cfg -> GpuProfileResolver.resolve(
        cfg, DevicePlanTest.device(12_282), WEIGHTS, STANDARD));
    System.setProperty("justsearch.splade.gpu_mem_mb", "1500");
    try {
      ResolvedConfig withCap = ConfigStoreRebuilder.prepare(new UiSettings());
      assertEquals(1_500, withCap.ai().splade().gpuMemMb(), "the explicit cap is kept");
      assertEquals(2_466, withCap.ai().embedding().gpuMemMb());
      assertEquals("p12", withCap.ai().gpuMemoryPlan());
      assertTrue(GpuProfileResolver.settings(withCap).roleGpuMemMiB()
          .containsKey(EncoderRole.SPLADE));
      assertFalse(GpuProfileResolver.settings(withCap).roleGpuMemMiB()
          .containsKey(EncoderRole.EMBEDDING), "the plan's own values never read as settings");
    } finally {
      System.clearProperty("justsearch.splade.gpu_mem_mb");
    }
  }

  @Test
  void thePlansOwnContributionIsNotReadBackAsASetting() {
    GpuProfileResolver.Resolution first = resolve(config(Map.of()), 12_282, STANDARD);
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeAutoDetected(GpuProfileResolver.contributions(first, true));
    GpuProfileResolver.Resolution again = resolve(builder.build(), 12_282, STANDARD);
    assertEquals(first, again, "resolving over the applied configuration gives the same plan");
    assertEquals(ProfileSource.MEASURED, again.source());
  }

  @Test
  void aPublishedPlanReachesTheEncodersDeviceAndAnUnplannedOneWithdrawsIt() {
    DevicePlanState state = DevicePlanState.isolated();
    var seen = new java.util.ArrayList<Profile>();
    state.subscribe(r -> seen.add(r.statusProfile()));
    state.publish(resolve(config(Map.of()), 12_282, STANDARD));
    assertTrue(DeviceArenaPlans.current(0).isPresent());
    state.publish(resolve(config(Map.of()), 10_240, STANDARD));
    assertTrue(DeviceArenaPlans.current(0).isEmpty(), "today's constants: no plan for sessions");
    assertEquals(List.of(Profile.UNRESOLVED, Profile.P12, Profile.P8), seen);
  }

  private static GpuProfileResolver.Resolution resolve(
      ResolvedConfig cfg, double deviceMiB, Set<ChatModelProfile> installed) {
    return GpuProfileResolver.resolve(cfg, DevicePlanTest.device(deviceMiB), WEIGHTS, installed);
  }

  private static ResolvedConfig config(Map<String, String> settings) {
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    settings.forEach(builder::putSettings);
    return builder.build();
  }

  @Test
  void theResolverCountsTheUnmeasuredChatFileAlongsideKvAndOverhead(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
    try (var file = new java.io.RandomAccessFile(
        dir.resolve(ChatModelProfile.PADDLE_OCR_VL.modelFile()).toFile(), "rw")) {
      file.setLength(1_000L * 1024 * 1024);
    }
    var cfg = config(Map.of("justsearch.models.dir", dir.toString(),
        "justsearch.chat.profile", ChatModelProfile.PADDLE_OCR_VL.id()));
    var plan = resolve(cfg, 16_376, Set.of(ChatModelProfile.PADDLE_OCR_VL)).plan().orElseThrow();
    assertEquals(2_450, plan.chat().footprintMiB(), 0.001, "file + 1,450 MiB");
    assertTrue(plan.chat().extrapolated());
    assertEquals(16_376 - 1_024 - 2_450 - 512 - 400 - 1_930,
        plan.budget().orElseThrow().activationPerRunMiB(), 0.001);
  }

  /** Device facts for one pinned device. */
  static final class DevicePlanTest {
    static DeviceFacts device(double totalMiB) {
      CudaDevice device = new CudaDevice(0, Optional.empty(), Optional.empty(), Optional.empty(),
          (long) (totalMiB * 1024 * 1024));
      return new DeviceFacts(DeviceFacts.ProbeStatus.RAN_OK, 0, 12_080, List.of(device),
          Optional.of(device));
    }
  }
}
