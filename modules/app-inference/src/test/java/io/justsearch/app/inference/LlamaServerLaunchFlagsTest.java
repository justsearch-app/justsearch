/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the EXACT llama-server launch command (tempdoc 883 decisions 1 and 2).
 *
 * <p>Asserted as an exact ordered list, not by {@code contains}, because these flags interact:
 * the fold's [R1] measurement is that {@code -np} WITHOUT {@code -kvu} halves the window a request
 * gets while {@code /props} still reports the full one, and [R4] is that {@code --fit} (default on)
 * maximizes rather than fits. A test that only checked each flag was present would pass on exactly
 * the launch line that produces the silent halving.
 */
@DisplayName("llama-server launch command")
final class LlamaServerLaunchFlagsTest {

  private static final Path EXE = Path.of("bin", "llama-server.exe");
  private static final Path MODEL = Path.of("models", "chat.gguf");

  private static ResolvedConfig config(int slots, String kvType, boolean thinking) {
    ResolvedConfigBuilder b = ResolvedConfig.builder();
    b.putDefault("justsearch.llm.slots", String.valueOf(slots));
    b.putDefault("justsearch.llm.kv_type", kvType);
    b.putDefault("justsearch.llm.use_thinking", String.valueOf(thinking));
    b.putDefault("justsearch.llm.reasoning_budget", "512");
    return b.build();
  }

  private static ResolvedConfig config(
      int contextSetting, int slots, String kvType, boolean thinking, int reasoningBudget) {
    ResolvedConfigBuilder b = ResolvedConfig.builder();
    b.putDefault("justsearch.context.size", String.valueOf(contextSetting));
    b.putDefault("justsearch.llm.slots", String.valueOf(slots));
    b.putDefault("justsearch.llm.kv_type", kvType);
    b.putDefault("justsearch.llm.use_thinking", String.valueOf(thinking));
    b.putDefault("justsearch.llm.reasoning_budget", String.valueOf(reasoningBudget));
    return b.build();
  }

  private static InferenceConfig inference(boolean vduMode) {
    return new InferenceConfig(EXE, MODEL, null, 8082, 32768, 99, vduMode);
  }

  /** Declared identity covers every applied launch input but the port; argv stays diagnostic. */
  @Test
  void declaredConfigurationIdentity() {
    InferenceConfig base = inference(false);
    ResolvedConfig applied = config(0, 2, "q8_0", true, 512);
    String hash = ManagedLlamaConfigIdentity.declaredHash(base, applied, 99);

    assertEquals(hash, ManagedLlamaConfigIdentity.declaredHash(base, applied, 99));
    assertNotEquals(hash, ManagedLlamaConfigIdentity.declaredHash(
        new InferenceConfig(Path.of("other.exe"), MODEL, null, 8082, 32768, 99, false), applied, 99));
    assertNotEquals(hash, ManagedLlamaConfigIdentity.declaredHash(
        new InferenceConfig(EXE, Path.of("other.gguf"), null, 8082, 32768, 99, false), applied, 99));
    assertNotEquals(hash, ManagedLlamaConfigIdentity.declaredHash(
        new InferenceConfig(EXE, MODEL, Path.of("vision.gguf"), 8082, 32768, 99, false), applied, 99));
    // CH2: every managed launch binds its own fresh port, so the port is not part of the identity.
    assertEquals(hash, ManagedLlamaConfigIdentity.declaredHash(
        new InferenceConfig(EXE, MODEL, null, 8083, 32768, 99, false), applied, 99),
        "the configured port must not change the declared identity");
    assertNotEquals(hash, ManagedLlamaConfigIdentity.declaredHash(base, applied, 0));
    assertNotEquals(hash, ManagedLlamaConfigIdentity.declaredHash(inference(true), applied, 99));
    assertNotEquals(hash, ManagedLlamaConfigIdentity.declaredHash(
        base, config(16384, 2, "q8_0", true, 512), 99));
    assertNotEquals(hash, ManagedLlamaConfigIdentity.declaredHash(
        base, config(0, 1, "q8_0", true, 512), 99));
    assertNotEquals(hash, ManagedLlamaConfigIdentity.declaredHash(
        base, config(0, 2, "q4_0", true, 512), 99));
    assertNotEquals(hash, ManagedLlamaConfigIdentity.declaredHash(
        base, config(0, 2, "q8_0", false, 0), 99));

    assertNotEquals(
        ManagedLlamaConfigIdentity.realizedArgvHash(List.of("llama", "-c", "32768")),
        ManagedLlamaConfigIdentity.realizedArgvHash(List.of("llama", "-c", "16384")));
  }

  @Test
  @DisplayName("the full GPU argv, in order, including -fit off")
  void fullGpuLaunchCommand() {
    List<String> command =
        LlamaServerOps.buildLaunchCommand(
            inference(false), config(2, "q8_0", true), 99, ContextWindowPolicy.auto(true, null));

    assertEquals(
        List.of(
            EXE.toString(),
            "-m",
            MODEL.toString(),
            "--cache-ram",
            "1024",
            "--jinja",
            "--reasoning-format",
            "deepseek",
            "--reasoning-budget",
            "512",
            "--host",
            "127.0.0.1",
            "--metrics",
            "--port",
            "8082",
            "-c",
            "32768",
            "-ngl",
            "99",
            // Spec E step E6 (S10): one device, the pinned one, never the default layer split.
            "--device",
            "CUDA0",
            "-sm",
            "none",
            "-np",
            "2",
            "-kvu",
            "-ctk",
            "q8_0",
            "-ctv",
            "q8_0",
            "-fa",
            "on",
            "-fit",
            "off"),
        command);
  }

  /** Spec E E7b: the projector on the CPU adds --no-mmproj-offload after --mmproj, nothing else. */
  @Test
  void projectorOnTheCpuAddsOneFlagAfterTheProjector() {
    Path mmproj = Path.of("models", "mmproj.gguf");
    InferenceConfig withProjector = new InferenceConfig(EXE, MODEL, mmproj, 8082, 32768, 99, false);
    List<String> onGpu =
        LlamaServerOps.buildLaunchCommand(
            withProjector, config(2, "q8_0", true), 99, ContextWindowPolicy.auto(true, null));
    ResolvedConfigBuilder b = ResolvedConfig.builder();
    b.putDefault("justsearch.llm.slots", "2");
    b.putDefault("justsearch.llm.kv_type", "q8_0");
    b.putDefault("justsearch.llm.use_thinking", "true");
    b.putDefault("justsearch.llm.reasoning_budget", "512");
    b.contributeAutoDetected(java.util.Map.of("justsearch.mmproj.offload", "false"));
    ResolvedConfig projectorOnCpu = b.build();
    List<String> onCpu =
        LlamaServerOps.buildLaunchCommand(
            withProjector, projectorOnCpu, 99, ContextWindowPolicy.auto(true, null));

    assertEquals(
        List.of(EXE.toString(), "-m", MODEL.toString(), "--mmproj", mmproj.toString(),
            "--cache-ram", "1024", "--jinja"),
        onGpu.subList(0, 8),
        "the default places the projector where llama-server does: no flag");
    assertTrue(!onGpu.contains("--no-mmproj-offload"), onGpu.toString());
    List<String> expected = new java.util.ArrayList<>(onGpu);
    expected.add(5, "--no-mmproj-offload");
    assertEquals(expected, onCpu, "one flag, right after the projector, and nothing else changes");

    // Without a projector there is nothing to place: the line is the record oracle's.
    assertEquals(
        LlamaServerOps.buildLaunchCommand(
            inference(false), config(2, "q8_0", true), 99, ContextWindowPolicy.auto(true, null)),
        LlamaServerOps.buildLaunchCommand(
            inference(false), projectorOnCpu, 99, ContextWindowPolicy.auto(true, null)));

    // The placement is part of the declared identity, so a changed placement relaunches chat;
    // with the projector on the GPU the identity is the one recorded before the plan existed.
    assertNotEquals(
        ManagedLlamaConfigIdentity.declaredHash(withProjector, config(2, "q8_0", true), 99),
        ManagedLlamaConfigIdentity.declaredHash(withProjector, projectorOnCpu, 99));
    assertEquals(
        ManagedLlamaConfigIdentity.declaredHash(inference(false), config(2, "q8_0", true), 99),
        ManagedLlamaConfigIdentity.declaredHash(inference(false), projectorOnCpu, 99));
  }

  /** Spec E E7b: a planned rung tops the derived ladder; without a plan the ladder is today's. */
  @Test
  void aPlannedRungTopsTheDerivedLadder() {
    ResolvedConfigBuilder planned = ResolvedConfig.builder();
    planned.contributeAutoDetected(java.util.Map.of(
        "justsearch.context.size", "8192", "justsearch.gpu.memory_plan", "p8"));
    ResolvedConfigBuilder unplanned = ResolvedConfig.builder();
    unplanned.contributeAutoDetected(java.util.Map.of("justsearch.context.size", "8192"));

    assertEquals(List.of(8192, 4096), planWindow(planned.build()).ladder());
    assertEquals(List.of(32768, 16384, 8192, 4096), planWindow(unplanned.build()).ladder(),
        "without a plan the 150 value is today's derived window, not a cap");
  }

  private static ContextWindowPolicy.Plan planWindow(ResolvedConfig rc) {
    try {
      var method = LlamaServerOps.class.getDeclaredMethod(
          "planContextWindow", ResolvedConfig.class, boolean.class, Long.class);
      method.setAccessible(true);
      return (ContextWindowPolicy.Plan) method.invoke(null, rc, true, null);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  @DisplayName("the memory-plan block is present and complete — dropping it must fail")
  void memoryPlanBlockIsCarriedByTheLaunchCommand() {
    List<String> command =
        LlamaServerOps.buildLaunchCommand(
            inference(false), config(2, "q8_0", true), 99, ContextWindowPolicy.auto(true, null));
    List<String> block = LlamaServerOps.memoryPlanFlags(2, "q8_0");

    int at = java.util.Collections.indexOfSubList(command, block);
    assertTrue(
        at >= 0,
        "the whole -np/-kvu/-ctk/-ctv/-fa/-fit block must appear contiguously in the launch"
            + " command; deleting the addAll that carries it leaves the engine choosing its own"
            + " slot count, an f16 KV cache and a memory-adjusting --fit: "
            + command);
    assertEquals(command.size(), at + block.size(), "the block is the tail of the command");
  }

  @Test
  @DisplayName("the CPU argv carries the CPU rung and -ngl 0, same flag set")
  void cpuLaunchCommand() {
    List<String> command =
        LlamaServerOps.buildLaunchCommand(
            inference(false), config(2, "q8_0", true), 0, ContextWindowPolicy.auto(false, null));

    assertEquals(
        List.of("-c", "8192", "-ngl", "0", "-np", "2", "-kvu", "-ctk", "q8_0", "-ctv", "q8_0",
            "-fa", "on", "-fit", "off"),
        command.subList(command.indexOf("-c"), command.size()));
  }

  @Test
  @DisplayName("VDU pins one slot and disables the prompt cache, without a duplicate -np")
  void vduLaunchCommand() {
    List<String> command =
        LlamaServerOps.buildLaunchCommand(
            inference(true), config(2, "q8_0", true), 99, ContextWindowPolicy.auto(true, null));

    assertEquals(
        1,
        command.stream().filter("-np"::equals).count(),
        "VDU used to add its own -np 1 before the common one; two -np flags is a launch whose slot"
            + " count depends on llama-server's argument precedence: "
            + command);
    assertEquals("1", command.get(command.indexOf("-np") + 1));
    assertEquals("0", command.get(command.indexOf("--cache-ram") + 1));
  }

  @Test
  @DisplayName("thinking off drops the reasoning flags and nothing else")
  void thinkingOffLaunchCommand() {
    List<String> command =
        LlamaServerOps.buildLaunchCommand(
            inference(false), config(2, "q8_0", false), 99, ContextWindowPolicy.auto(true, null));

    assertTrue(!command.contains("--reasoning-format"), command.toString());
    assertTrue(!command.contains("--reasoning-budget"), command.toString());
    List<String> block = LlamaServerOps.memoryPlanFlags(2, "q8_0");
    assertEquals(block, command.subList(command.size() - block.size(), command.size()));
  }

  @Test
  @DisplayName("an override rung reaches -c unchanged")
  void overrideRungReachesTheCommand() {
    List<String> command =
        LlamaServerOps.buildLaunchCommand(
            inference(false), config(2, "q8_0", true), 99, ContextWindowPolicy.override(12345, null));

    assertEquals("12345", command.get(command.indexOf("-c") + 1));
  }

  @Test
  @DisplayName("-fit is off: the ladder needs an unfittable -c to abort, not be absorbed")
  void fitIsExplicitlyOff() {
    List<String> flags = LlamaServerOps.memoryPlanFlags(2, "q8_0");

    assertEquals(
        "off",
        flags.get(flags.indexOf("-fit") + 1),
        "b8571 defaults --fit on; the step-down premise is that a rung which does not fit produces"
            + " a hard abort, so a memory-adjusting heuristic must not be running beside it");
  }

  @Test
  @DisplayName("-kvu is always emitted alongside an explicit -np")
  void kvUnifiedAccompaniesExplicitSlots() {
    for (int slots : new int[] {1, 2, 4}) {
      List<String> flags = LlamaServerOps.memoryPlanFlags(slots, "f16");
      assertTrue(flags.contains("-np"), "slots are always explicit");
      assertTrue(
          flags.indexOf("-kvu") > flags.indexOf("-np"),
          "an explicit -np disables llama-server's automatic kv_unified, so -kvu must follow it"
              + " (fold [R1]: without it, -c 32768 -np 2 gives n_ctx_seq 16384 while /props still"
              + " reports 32768)");
    }
  }

  @Test
  @DisplayName("flash attention is 'on', never 'auto' — a quantized V-cache aborts without it")
  void flashAttentionIsExplicit() {
    List<String> flags = LlamaServerOps.memoryPlanFlags(2, "q8_0");
    assertEquals("on", flags.get(flags.indexOf("-fa") + 1));
  }

  @Test
  @DisplayName("K and V cache types are the same type, from one key")
  void cacheTypesAgree() {
    List<String> flags = LlamaServerOps.memoryPlanFlags(2, "q4_0");
    assertEquals("q4_0", flags.get(flags.indexOf("-ctk") + 1));
    assertEquals("q4_0", flags.get(flags.indexOf("-ctv") + 1));
  }

  @Test
  @DisplayName("window flags carry the ladder rung and the offload layers, in that order")
  void windowFlags() {
    assertEquals(List.of("-c", "32768", "-ngl", "99"), LlamaServerOps.windowFlags(32768, 99));
    assertEquals(List.of("-c", "8192", "-ngl", "0"), LlamaServerOps.windowFlags(8192, 0));
  }
}
