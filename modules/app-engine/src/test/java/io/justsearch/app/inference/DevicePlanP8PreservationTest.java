/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.engine.DevicePlanTestSupport;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.ort.GpuArenaPlan;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Spec E revision 4, check E-P8 (preservation, step E7b). On P8 facts without the compact model
 * (8,068 to 11,083 MiB) no plan is applied and today's constants stay: the launch line is today's
 * apart from {@code --no-mmproj-offload} and the pinning flags; every encoder cap is today's (6,144 /
 * 4,096 / 2,048 / 2,048, BGE-M3 3,072); an out-of-memory step-down of the chat window leaves the caps
 * unchanged; no profile refusal is raised and the encoders stay on the GPU.
 */
final class DevicePlanP8PreservationTest {

  private static final Path EXE = Path.of("bin", "llama-server.exe");
  private static final Path MODEL = Path.of("models", "Qwen_Qwen3.5-9B-Q4_K_M.gguf");
  private static final Path MMPROJ = Path.of("models", "mmproj-Qwen_Qwen3.5-9B-f16.gguf");

  @AfterEach
  void restore() throws Exception {
    DevicePlanTestSupport.uninstall();
    ConfigStoreRebuilder.rememberAutoDetected(Map.of());
  }

  @ParameterizedTest
  @ValueSource(doubles = {8_068, 10_240, 11_083})
  void p8WithoutCompactKeepsTodaysLaunchCapsAndPlacement(double deviceMiB) throws Exception {
    UiSettings settings = new UiSettings();
    ConfigStore today = boot(settings, null);
    List<String> todaysLine = launch(today.get(), window(today.get()));
    ConfigStore withPlan = boot(settings, deviceMiB);
    ResolvedConfig config = withPlan.get();

    // (1) Today's launch line, apart from the projector placement and the pinning flags.
    List<String> line = launch(config, window(config));
    assertEquals(withoutPlacementAndPins(todaysLine), withoutPlacementAndPins(line));
    assertFalse(todaysLine.contains("--no-mmproj-offload"), todaysLine.toString());

    // (2) Every encoder cap is today's.
    assertTodaysCaps(config);

    // (3) An out-of-memory step-down of the chat window leaves the caps unchanged.
    ContextWindowPolicy.Plan planned = window(config);
    ContextWindowPolicy.Plan stepped = new ContextWindowPolicy.Plan(
        planned.ladder().subList(1, planned.ladder().size()),
        ContextWindowPolicy.steppedFrom(planned.ladder().getFirst()), null);
    List<String> steppedLine = launch(config, stepped);
    assertEquals("16384", steppedLine.get(steppedLine.indexOf("-c") + 1));
    ConfigStoreRebuilder.rebuild(withPlan, settings);
    assertTodaysCaps(withPlan.get());

    // (4) No refusal: nothing unmet, the profile is P8, and the encoders stay on the GPU.
    Optional<GpuArenaPlan> plan = DevicePlanTestSupport.planOf(withPlan.get());
    plan.ifPresent(p -> {
      assertFalse(p.planned(), "no plan is applied to the 9B on a P8 device");
      assertTrue(p.unmetSettings().isEmpty(), p.unmetSettings().toString());
      p.roles().values().forEach(role -> assertTrue(role.bulkCpuReason().isEmpty()));
    });
    DevicePlanTestSupport.statusProfileOf(withPlan.get())
        .ifPresent(profile -> assertEquals("P8", profile));
    assertTrue(DevicePlanTestSupport.publishedPlan(0).isEmpty(),
        "no plan is published to the encoder sessions");
    // The encoders stay where today places them: the plan moves no role off the GPU.
    ResolvedConfig.Ai ai = withPlan.get().ai();
    ResolvedConfig.Ai todays = today.get().ai();
    assertEquals(todays.embedding().gpuEnabled(), ai.embedding().gpuEnabled(), "embedding");
    assertEquals(todays.splade().gpuEnabled(), ai.splade().gpuEnabled(), "SPLADE");
    assertEquals(todays.ner().gpuEnabled(), ai.ner().gpuEnabled(), "NER");
    assertEquals(todays.reranker().gpuEnabled(), ai.reranker().gpuEnabled(), "reranker");
    assertEquals(todays.bgeM3().gpuEnabled(), ai.bgeM3().gpuEnabled(), "BGE-M3");
  }

  private static void assertTodaysCaps(ResolvedConfig config) {
    assertEquals(6_144, config.ai().embedding().gpuMemMb());
    assertEquals(4_096, config.ai().splade().gpuMemMb());
    assertEquals(2_048, config.ai().ner().gpuMemMb());
    assertEquals(2_048, config.ai().reranker().gpuMemMb());
    assertEquals(3_072, config.ai().bgeM3().gpuMemMb());
  }

  /** A boot with the probe's GPU layers; with {@code deviceMiB}, the Engine's planner over P8 facts. */
  private static ConfigStore boot(UiSettings settings, Double deviceMiB) throws Exception {
    DevicePlanTestSupport.uninstall();
    ConfigStoreRebuilder.rememberAutoDetected(Map.of(
        "justsearch.gpu.layers", "99",
        "justsearch.context.size", "32768"));
    ConfigStore store = new ConfigStore(ConfigStoreRebuilder.prepare(settings));
    if (deviceMiB != null) {
      DevicePlanTestSupport.install(store, DevicePlanTestSupport.device(deviceMiB),
          DevicePlanTestSupport.DEFAULT_WEIGHTS, Set.of(ChatModelProfile.STANDARD));
    }
    ConfigStoreRebuilder.rebuild(store, settings);
    return store;
  }

  private static List<String> withoutPlacementAndPins(List<String> line) {
    List<String> kept = new ArrayList<>(line);
    kept.remove("--no-mmproj-offload");
    int pins = java.util.Collections.indexOfSubList(kept, LlamaServerOps.PINNED_DEVICE_FLAGS);
    if (pins >= 0) {
      kept.subList(pins, pins + LlamaServerOps.PINNED_DEVICE_FLAGS.size()).clear();
    }
    return kept;
  }

  private static List<String> launch(ResolvedConfig config, ContextWindowPolicy.Plan plan) {
    return LlamaServerOps.buildLaunchCommand(
        new InferenceConfig(EXE, MODEL, MMPROJ, 8082, config.ai().contextSize(), 99, false),
        config, 99, plan);
  }

  private static ContextWindowPolicy.Plan window(ResolvedConfig config) throws Exception {
    Method plan = LlamaServerOps.class.getDeclaredMethod(
        "planContextWindow", ResolvedConfig.class, boolean.class, Long.class);
    plan.setAccessible(true);
    return (ContextWindowPolicy.Plan) plan.invoke(null, config, true, null);
  }
}
