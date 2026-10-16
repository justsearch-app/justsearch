/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.services.bootstrap.phases.BootstrapHelpers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvaluationModeAgreementTest {
  @TempDir Path dir;

  @Test void environmentEvaluationAgreesAcrossBootstrapDebugAndCache() throws Exception {
    assertEquals("AGREEMENT=EVAL/0/400/400", probe(true, List.of()));
  }

  @Test void booleanAliasAgreesAcrossBootstrapDebugAndCache() throws Exception {
    assertEquals("AGREEMENT=EVAL/0/400/400", probe(false, List.of("-Djustsearch.eval.mode=1")));
  }

  public static final class Probe {
    public static void main(String[] ignored) {
      var debug = new DebugConditionController(mock(ConditionStore.class),
          mock(HealthEventChangeRegistry.class), Source.forProcess("probe", "probe", null), Clock.systemUTC());
      AtomicInteger trip = new AtomicInteger();
      AtomicInteger clear = new AtomicInteger();
      debug.handleTrip(context(trip));
      debug.handleClear(context(clear));
      var inference = new InferenceConfig(Path.of("server.exe"), Path.of("model.gguf"),
          null, 8080, 8192, 99, false, null);
      System.out.println("AGREEMENT=" + BootstrapHelpers.initialRuntimeContext().systemMode()
          + "/" + inference.chatPromptCacheMiB() + "/" + trip + "/" + clear);
    }

    private static Context context(AtomicInteger status) {
      Context ctx = mock(Context.class);
      when(ctx.status(org.mockito.ArgumentMatchers.any(HttpStatus.class))).thenAnswer(call -> {
        status.set(((HttpStatus) call.getArgument(0)).getCode());
        return ctx;
      });
      when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of());
      return ctx;
    }
  }

  private String probe(boolean env, List<String> options) throws Exception {
    Path args = dir.resolve("probe.args");
    Files.writeString(args, String.join("\n", options) + "\n-cp\n\""
        + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"\n"
        + Probe.class.getName() + "\n", StandardCharsets.UTF_8);
    Path output = dir.resolve("probe.log");
    String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
        .contains("windows") ? "java.exe" : "java";
    var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable)
        .toString(), "@" + args).redirectErrorStream(true).redirectOutput(output.toFile());
    builder.environment().remove("JUSTSEARCH_EVAL_MODE");
    builder.environment().remove("JUSTSEARCH_LLM_PROMPT_CACHE_MIB");
    if (env) builder.environment().put("JUSTSEARCH_EVAL_MODE", "true");
    Process child = builder.start();
    var birth = child.info().startInstant().orElseThrow();
    try {
      org.junit.jupiter.api.Assertions.assertTrue(child.waitFor(60, TimeUnit.SECONDS),
          "evaluation probe must finish");
    } finally {
      if (child.isAlive() && child.info().startInstant().filter(birth::equals).isPresent()) {
        child.destroyForcibly();
        child.waitFor(10, TimeUnit.SECONDS);
      }
    }
    String log = Files.readString(output);
    assertEquals(0, child.exitValue(), log);
    return log.lines().filter(line -> line.startsWith("AGREEMENT=")).findFirst().orElse(log);
  }
}
