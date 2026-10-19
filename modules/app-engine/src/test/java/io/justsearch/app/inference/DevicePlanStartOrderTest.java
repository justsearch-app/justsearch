/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.engine.DevicePlanTestSupport;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArenaPlan;
import io.justsearch.ort.GpuCallPacker;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Spec E revision 4, check E-S9a (S9 on P12 facts, step E7b). The plan the Engine applies depends on
 * the device facts, the models and the user's settings only. Chat activated first or the encoders
 * composed first give the same profile, encoder caps, chat window ({@code -c}), projector placement
 * ({@code --no-mmproj-offload}), A_run, late-chunking limit and packed call shapes. A forced ladder
 * step-down changes only the launch window, never the profile or the caps, and five chat toggles
 * change nothing.
 */
final class DevicePlanStartOrderTest {

  private static final Path EXE = Path.of("bin", "llama-server.exe");
  private static final Path MODEL = Path.of("models", "Qwen_Qwen3.5-9B-Q4_K_M.gguf");
  private static final Path MMPROJ = Path.of("models", "mmproj-Qwen_Qwen3.5-9B-f16.gguf");
  private static final double P12_DEVICE_MIB = 12_282;

  /** Everything the plan decides, as the chat launch and the encoder sessions read it. */
  private record Applied(
      String profile,
      Map<EncoderRole, Integer> capsMb,
      String window,
      boolean projectorOnCpu,
      double activationPerRunMiB,
      int lateMaxTokens,
      List<Integer> shapes) {}

  @AfterEach
  void restore() throws Exception {
    DevicePlanTestSupport.uninstall();
    ConfigStoreRebuilder.rememberAutoDetected(Map.of());
  }

  @Test
  void chatFirstAndEncodersFirstApplyTheSamePlanAndChatTogglesChangeNothing() throws Exception {
    // Order 1: chat activates (its settings write and launch) before the encoders compose.
    ConfigStore chatFirst = bootP12();
    UiSettings activated = chatActivated();
    ConfigStoreRebuilder.rebuild(chatFirst, activated);
    List<String> chatFirstArgv = launch(chatFirst.get());
    Applied first = applied(chatFirst.get(), chatFirstArgv);

    // Order 2: the encoders compose on the boot configuration, then chat activates.
    ConfigStore encodersFirst = bootP12();
    Applied encodersView = applied(encodersFirst.get(), launch(encodersFirst.get()));
    ConfigStoreRebuilder.rebuild(encodersFirst, activated);
    Applied second = applied(encodersFirst.get(), launch(encodersFirst.get()));

    Applied expected = new Applied(
        "P12",
        Map.of(EncoderRole.EMBEDDING, 2_466, EncoderRole.SPLADE, 2_342, EncoderRole.NER, 2_125,
            EncoderRole.RERANKER, 2_468),
        "32768",
        true,
        1_867,
        5_632,
        // embedding at 2,048 / up to 1,678 / 1,679, SPLADE at 512, rerank of 50 chunks
        List.of(5, 8, 7, 2, 32, 18));
    assertEquals(expected, first, "chat activated first");
    assertEquals(expected, encodersView, "encoders composed first");
    assertEquals(expected, second, "chat activated after the encoders composed");

    // A forced ladder step-down: llama-server refuses the top rung and the launch steps to 16k.
    ContextWindowPolicy.Plan planned = window(chatFirst.get());
    ContextWindowPolicy.Plan stepped = new ContextWindowPolicy.Plan(
        planned.ladder().subList(1, planned.ladder().size()),
        ContextWindowPolicy.steppedFrom(planned.ladder().getFirst()), null);
    List<String> steppedArgv = LlamaServerOps.buildLaunchCommand(
        inference(chatFirst.get()), chatFirst.get(), 99, stepped);
    assertEquals("16384", steppedArgv.get(steppedArgv.indexOf("-c") + 1));
    ConfigStoreRebuilder.rebuild(chatFirst, activated);
    Applied afterStepDown = applied(chatFirst.get(), chatFirstArgv);
    assertEquals(expected, afterStepDown, "a step-down leaves the profile and the caps unchanged");

    // Five chat toggles: chat off and on again, each a settings write and a rebuild.
    UiSettings toggled = chatActivated();
    for (int toggle = 1; toggle <= 5; toggle++) {
      toggled.setChatEnabled(toggle % 2 == 0);
      ConfigStoreRebuilder.rebuild(chatFirst, toggled);
      assertEquals(expected, applied(chatFirst.get(), launch(chatFirst.get())),
          "chat toggle " + toggle);
    }
  }

  /** A boot on P12 facts: the probe's GPU layers, then the Engine's planner, then the rebuild. */
  private static ConfigStore bootP12() throws Exception {
    DevicePlanTestSupport.uninstall();
    ConfigStoreRebuilder.rememberAutoDetected(Map.of(
        "justsearch.gpu.layers", "99",
        "justsearch.context.size", "32768"));
    UiSettings settings = new UiSettings();
    ConfigStore store = new ConfigStore(ConfigStoreRebuilder.prepare(settings));
    DevicePlanTestSupport.install(store, DevicePlanTestSupport.device(P12_DEVICE_MIB),
        DevicePlanTestSupport.DEFAULT_WEIGHTS, Set.of(ChatModelProfile.STANDARD));
    ConfigStoreRebuilder.rebuild(store, settings);
    return store;
  }

  /** Chat activation's settings write: the managed model's path. */
  private static UiSettings chatActivated() {
    UiSettings settings = new UiSettings();
    settings.setChatEnabled(true);
    settings.setLlmModelPath(MODEL.toString());
    return settings;
  }

  private static Applied applied(ResolvedConfig config, List<String> argv) throws Exception {
    Map<EncoderRole, Integer> caps = Map.of(
        EncoderRole.EMBEDDING, config.ai().embedding().gpuMemMb(),
        EncoderRole.SPLADE, config.ai().splade().gpuMemMb(),
        EncoderRole.NER, config.ai().ner().gpuMemMb(),
        EncoderRole.RERANKER, config.ai().reranker().gpuMemMb());
    String profile = DevicePlanTestSupport.statusProfileOf(config).orElse("no plan applied");
    Optional<GpuArenaPlan> published = DevicePlanTestSupport.publishedPlan(0);
    double activation = published.flatMap(GpuArenaPlan::budget)
        .map(GpuArenaPlan.Budget::activationPerRunMiB).orElse(-1.0);
    List<Integer> shapes = new ArrayList<>();
    int lateMax = -1;
    if (published.isPresent()) {
      GpuArenaPlan embedding = DevicePlanTestSupport.asSessionReadsIt(
          published.get(), EncoderRole.EMBEDDING, caps.get(EncoderRole.EMBEDDING));
      GpuArenaPlan splade = DevicePlanTestSupport.asSessionReadsIt(
          published.get(), EncoderRole.SPLADE, caps.get(EncoderRole.SPLADE));
      GpuArenaPlan reranker = DevicePlanTestSupport.asSessionReadsIt(
          published.get(), EncoderRole.RERANKER, caps.get(EncoderRole.RERANKER));
      lateMax = GpuCallPacker.lateChunkingMaxTokens(Optional.of(embedding), 8_192);
      for (int length : new int[] {2_048, 1_678, 1_679}) {
        shapes.add(GpuCallPacker.itemsPerCall(
            Optional.of(embedding), EncoderRole.EMBEDDING, 8, 20, i -> length));
      }
      shapes.add(GpuCallPacker.itemsPerCall(
          Optional.of(splade), EncoderRole.SPLADE, 4, 9, i -> 512));
      int remaining = 50;
      while (remaining > 0) {
        int items = GpuCallPacker.itemsPerCall(Optional.of(reranker), EncoderRole.RERANKER,
            Integer.MAX_VALUE, remaining, i -> 512, l -> l, DevicePlanStartOrderTest::rerankBucket);
        shapes.add(items);
        remaining -= items;
      }
    }
    return new Applied(profile, caps, argv.get(argv.indexOf("-c") + 1),
        argv.contains("--no-mmproj-offload"), activation, lateMax, shapes);
  }

  private static int rerankBucket(int n) {
    for (int bucket : new int[] {4, 8, 16, 24, 32, 48, 64}) {
      if (n <= bucket) return bucket;
    }
    return n;
  }

  private static List<String> launch(ResolvedConfig config) throws Exception {
    List<String> argv =
        LlamaServerOps.buildLaunchCommand(inference(config), config, 99, window(config));
    assertTrue(argv.contains("--mmproj"), argv.toString());
    return argv;
  }

  private static InferenceConfig inference(ResolvedConfig config) {
    return new InferenceConfig(EXE, MODEL, MMPROJ, 8082, config.ai().contextSize(), 99, false);
  }

  /** The launch's window plan, as LlamaServerOps plans it for a GPU-backed start. */
  private static ContextWindowPolicy.Plan window(ResolvedConfig config) throws Exception {
    Method plan = LlamaServerOps.class.getDeclaredMethod(
        "planContextWindow", ResolvedConfig.class, boolean.class, Long.class);
    plan.setAccessible(true);
    return (ContextWindowPolicy.Plan) plan.invoke(null, config, true, null);
  }
}
