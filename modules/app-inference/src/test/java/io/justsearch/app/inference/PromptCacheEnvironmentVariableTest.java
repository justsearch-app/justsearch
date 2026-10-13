/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The registered override's environment variable reaches the launch bound, with the registry's
 * precedence (system property, then environment variable) and the same refusal of a malformed
 * value. An environment variable is process state, so each case runs a real child JVM whose
 * environment carries it; the child prints the chat launch's {@code --cache-ram} value or the
 * refusal.
 */
final class PromptCacheEnvironmentVariableTest {

  private static final String ENV = "JUSTSEARCH_LLM_PROMPT_CACHE_MIB";
  private static final String PROPERTY = "justsearch.llm.prompt_cache_mib";

  @TempDir Path tmp;

  /** Child entry point: one chat launch line, as the head builds it. */
  public static final class Probe {
    public static void main(String[] args) {
      try {
        InferenceConfig config = new InferenceConfig(Path.of("llama-server.exe"),
            Path.of("model.gguf"), null, 8082, 4096, 0, false);
        List<String> argv = LlamaServerOps.buildLaunchCommand(config,
            io.justsearch.configuration.resolved.ResolvedConfig.builder().build(), 0,
            ContextWindowPolicy.auto(false, null));
        int at = argv.indexOf("--cache-ram");
        System.out.println("CACHE_RAM=" + (at < 0 ? "absent" : argv.get(at + 1)));
      } catch (IllegalArgumentException refused) {
        System.out.println("REFUSED=" + refused.getMessage());
      }
    }
  }

  @Test
  void theEnvironmentVariableSetsTheChatBoundAndThePropertyWinsOverIt() throws Exception {
    assertEquals("CACHE_RAM=256", runChild(Map.of(ENV, "256"), List.of()),
        "the registered environment variable must reach the chat launch");
    assertEquals("CACHE_RAM=512", runChild(Map.of(ENV, "256"), List.of("-D" + PROPERTY + "=512")),
        "the system property outranks the environment variable, as the registry resolves it");
    assertEquals("CACHE_RAM=0",
        runChild(Map.of(ENV, "256"), List.of("-Djustsearch.eval.mode=true")),
        "evaluation mode disables the cache whatever the override");
  }

  @Test
  void evaluationEnvironmentDisablesDefaultCache() throws Exception {
    assertEquals("CACHE_RAM=0", runChild(Map.of("JUSTSEARCH_EVAL_MODE", "true"), List.of()));
  }

  @Test
  void evaluationEnvironmentDisablesOverriddenCache() throws Exception {
    assertEquals("CACHE_RAM=0", runChild(Map.of("JUSTSEARCH_EVAL_MODE", "true", ENV, "256"), List.of()));
  }

  @Test
  void evaluationBooleanAliasDisablesCache() throws Exception {
    assertEquals("CACHE_RAM=0", runChild(Map.of(ENV, "256"), List.of("-Djustsearch.eval.mode=1")));
  }

  @Test
  void explicitFalseEvaluationPropertyWins() throws Exception {
    assertEquals("CACHE_RAM=256", runChild(Map.of("JUSTSEARCH_EVAL_MODE", "true", ENV, "256"),
        List.of("-Djustsearch.eval.mode=false")));
  }

  @Test
  void aMalformedEnvironmentVariableIsRefusedNamingTheVariable() throws Exception {
    String out = runChild(Map.of(ENV, "abc"), List.of());
    assertTrue(out.startsWith("REFUSED="), "a malformed value is refused, not defaulted: " + out);
    assertTrue(out.contains(ENV), "the refusal names the variable it read: " + out);
    assertTrue(out.contains("non-negative integer"), out);
  }

  /** Runs {@link Probe} with the given environment additions and JVM options; returns its line. */
  private String runChild(Map<String, String> env, List<String> jvmOptions) throws Exception {
    Path argFile = Files.createTempFile(tmp, "probe-", ".args");
    StringBuilder args = new StringBuilder();
    for (String option : jvmOptions) args.append(option).append('\n');
    args.append("-cp\n\"")
        .append(System.getProperty("java.class.path").replace("\\", "\\\\"))
        .append("\"\n")
        .append(Probe.class.getName()).append('\n');
    Files.writeString(argFile, args.toString(), StandardCharsets.UTF_8);

    String java = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
            ? "java.exe" : "java").toString();
    List<String> command = new ArrayList<>(List.of(java, "@" + argFile.toAbsolutePath()));
    Path log = Files.createTempFile(tmp, "probe-", ".log");
    ProcessBuilder builder = new ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(log.toFile());
    builder.environment().remove(ENV);
    builder.environment().remove("JUSTSEARCH_EVAL_MODE");
    builder.environment().putAll(env);
    Process child = builder.start();
    var birth = child.info().startInstant().orElseThrow();
    String output;
    try {
      if (!child.waitFor(60, TimeUnit.SECONDS)) {
        throw new IOException("probe JVM did not finish within 60 s");
      }
      output = Files.readString(log, StandardCharsets.UTF_8);
    } finally {
      // Only this test's own child, by its handle; it has normally exited already.
      if (child.isAlive() && child.info().startInstant().filter(birth::equals).isPresent()) {
        child.destroyForcibly();
        child.waitFor(10, TimeUnit.SECONDS);
      }
    }
    assertEquals(0, child.exitValue(), "probe JVM exit; output:\n" + output);
    return output.lines()
        .filter(line -> line.startsWith("CACHE_RAM=") || line.startsWith("REFUSED="))
        .findFirst()
        .orElse("no result line; output:\n" + output);
  }
}
