/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
final class ChatLaunchCompatibilityTest {
  @TempDir Path directory;

  @Test
  void chatLaunchPreservesPipesArgumentsEnvironmentAndWorkingDirectory() throws Exception {
    Path cwd = Files.createDirectory(directory.resolve("native directory ö"));
    String argument = "ö with spaces and trailing\\";
    try (var control = DefaultChildControl.unrecorded(ManagedChildRegistry.noop())) {
      var command = new ArrayList<>(List.of(
          Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
          "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp",
          Path.of(Echo.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
          Echo.class.getName(), argument));
      ChildHandle child = control.start(spec(command, cwd, Map.of("B97_NATIVE_CHECK", "literal α &= value")));
      child.stdin().write("input α\n".getBytes(StandardCharsets.UTF_8));
      child.stdin().close();
      child.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);
      String out = new String(child.stdout().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(out.contains("ARG=" + argument), out);
      assertTrue(out.contains("ENV=literal α &= value"), out);
      assertTrue(out.contains("CWD=" + cwd.toAbsolutePath()), out);
      assertTrue(out.contains("INPUT=input α"), out);
      assertEquals("ERR", new String(child.stderr().readAllBytes(), StandardCharsets.UTF_8).trim());
      command.add("invalid\0argument");
      try (var invalid = DefaultChildControl.unrecorded(ManagedChildRegistry.noop())) {
        var rejection = assertThrows(java.io.IOException.class,
            () -> invalid.start(spec(command, cwd, Map.of())));
        String reason = rejection.getMessage()
            + (rejection.getCause() == null ? "" : rejection.getCause().getMessage());
        assertTrue(reason.toLowerCase(java.util.Locale.ROOT).contains("nul"), reason);
      }
    }
  }

  private static ChildSpec spec(List<String> argv, Path cwd, Map<String, String> env) {
    return new ChildSpec(ChildKind.LLAMA_SERVER, argv, cwd, env, new ChildSpec.Stdio.Pipes(), false,
        OptionalLong.empty(), Optional.empty(), ChildSpec.CooperativeStop.NONE, "launch-preservation");
  }

  /** Cleanup belongs to the real control, which records PID and birth before resuming this root. */
  public static final class Echo {
    private Echo() {}
    public static void main(String[] args) throws Exception {
      System.out.println("ARG=" + args[0]);
      System.out.println("ENV=" + System.getenv("B97_NATIVE_CHECK"));
      System.out.println("CWD=" + Path.of("").toAbsolutePath());
      System.out.println("INPUT=" + new java.io.BufferedReader(new java.io.InputStreamReader(
          System.in, StandardCharsets.UTF_8)).readLine());
      System.err.println("ERR");
    }
  }
}
