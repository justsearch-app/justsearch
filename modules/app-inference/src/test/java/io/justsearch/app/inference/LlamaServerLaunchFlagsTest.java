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

  @Test
  @DisplayName("declared identity covers every applied launch input while argv stays diagnostic")
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
    assertNotEquals(hash, ManagedLlamaConfigIdentity.declaredHash(
        new InferenceConfig(EXE, MODEL, null, 8083, 32768, 99, false), applied, 99));
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
