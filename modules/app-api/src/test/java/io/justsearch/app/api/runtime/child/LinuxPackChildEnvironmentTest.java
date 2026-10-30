/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** S12: a downloaded llama pack takes precedence over a conflicting parent loader environment. */
@EnabledOnOs(OS.LINUX)
@Timeout(60)
final class LinuxPackChildEnvironmentTest {
  @TempDir Path root;

  @Test void managedChatUsesPackLibrariesAndPreservesTheRestOfTheParentEnvironment() throws Exception {
    Path pack = Files.createDirectories(root.resolve("downloaded-pack"));
    Path host = Files.createDirectories(root.resolve("host-cuda"));
    Path rest = Files.createDirectories(root.resolve("other-libraries"));
    Path source = root.resolve("dependency.c");
    for (var entry : Map.of(pack, 42, host, 99).entrySet()) {
      Files.writeString(source, "int fixture_value(void) { return " + entry.getValue() + "; }\n");
      command(List.of("gcc", "-shared", "-fPIC", "-Wl,-soname,libfixture.so.1", "-o",
          entry.getKey().resolve("libfixture.so.1").toString(), source.toString()));
    }
    Path main = root.resolve("main.c");
    Files.writeString(main, "#include <stdio.h>\n#include <stdlib.h>\nint fixture_value(void);"
        + "int main(void) { printf(\"%d\\n%s\\n\", fixture_value(), getenv(\"LD_LIBRARY_PATH\")); }\n");
    Path executable = pack.resolve("llama-server");
    command(List.of("gcc", "-o", executable.toString(), main.toString(), "-L" + pack,
        "-l:libfixture.so.1", "-Wl,--enable-new-dtags,-rpath,$ORIGIN"));
    Files.writeString(pack.resolve("linux-gpu-pack.json"), "{}");
    String inherited = host + ":" + rest;
    for (ChildKind kind : List.of(ChildKind.LLAMA_SERVER, ChildKind.LLAMA_SELF_TEST, ChildKind.EXTRACTION)) {
      assertLaunch(kind, executable, inherited, kind == ChildKind.EXTRACTION ? 99 : 42,
          kind == ChildKind.EXTRACTION ? inherited : pack + ":" + inherited);
    }
    Files.delete(pack.resolve("linux-gpu-pack.json"));
    assertLaunch(ChildKind.LLAMA_SERVER, executable, inherited, 99, inherited);
  }

  private void assertLaunch(ChildKind kind, Path executable, String inherited, int value, String childPath)
      throws Exception {
    var argv = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"), Parent.class.getName(), kind.name(), executable.toString());
    ProcessBuilder parent = new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.INHERIT);
    parent.environment().put("LD_LIBRARY_PATH", inherited);
    Process process = parent.start();
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS), "parent and managed child finish");
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(0, process.exitValue(), output);
      assertEquals(List.of(Integer.toString(value), childPath, "parent=" + inherited), output.lines().toList());
    } finally {
      process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS));
    }
  }

  private static void command(List<String> argv) throws Exception {
    Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    } finally {
      process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS));
    }
  }

  /** A fresh parent JVM allows a real inherited LD_LIBRARY_PATH without mutating the test JVM. */
  public static final class Parent {
    public static void main(String[] args) throws Exception {
      try (var control = DefaultChildControl.unrecorded(ManagedChildRegistry.noop())) {
        var spec = new ChildSpec(ChildKind.valueOf(args[0]), List.of(args[1]), null, Map.of(),
            new ChildSpec.Stdio.Pipes(), false, OptionalLong.empty(), Optional.empty(),
            ChildSpec.CooperativeStop.NONE, "loader-fixture");
        ChildHandle child = control.start(spec);
        System.out.write(child.stdout().readAllBytes());
        child.exit().toCompletableFuture().get(10, TimeUnit.SECONDS);
        System.out.println("parent=" + System.getenv("LD_LIBRARY_PATH"));
      }
    }
  }
}
