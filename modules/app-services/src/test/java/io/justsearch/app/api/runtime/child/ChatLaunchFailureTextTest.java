/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import java.io.IOException;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A chat launch that cannot run reads as the JDK's launch of the same command reads: logs and status
 * keep the "Cannot run program" shape, the Windows error number and its system text.
 */
@EnabledOnOs(OS.WINDOWS)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
final class ChatLaunchFailureTextTest {
  private static final String STRICT = "jdk.lang.Process.allowAmbiguousCommands";
  @TempDir Path directory;

  @Test
  void aMissingExecutableFailsWithTheJdksText() throws Exception {
    Path missing = directory.resolve("not here").resolve("no-such-server.exe");
    Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
    Path cwd = Files.createDirectory(directory.resolve("working dir"));
    try (var control = DefaultChildControl.unrecorded(ManagedChildRegistry.noop())) {
      for (Path workingDirectory : new Path[] {null, cwd}) {
        List<String> command = List.of(missing.toString(), "--port", "0");
        String jdk = failure(control, ChildKind.EXTRACTION, command, workingDirectory);
        assertTrue(jdk.startsWith("Cannot run program \"" + missing + "\""), jdk);
        assertTrue(jdk.contains("CreateProcess error=2, "), jdk);
        assertEquals(jdk, failure(control, ChildKind.LLAMA_SERVER, command, workingDirectory),
            "the contained launch fails with the JDK's text");
      }
      // A working directory that does not exist is a different Windows error, worded the same way.
      List<String> version = List.of(java.toString(), "-version");
      String jdk = failure(control, ChildKind.EXTRACTION, version, directory.resolve("gone"));
      assertTrue(jdk.contains("(in directory \"" + directory.resolve("gone") + "\")"), jdk);
      assertEquals(jdk, failure(control, ChildKind.LLAMA_SERVER, version, directory.resolve("gone")));
    }
  }

  /**
   * With {@code jdk.lang.Process.allowAmbiguousCommands=false} a {@code .cmd} launcher sees what it
   * sees under the JDK, and an argument the JDK refuses is refused with the JDK's text.
   */
  @Test
  void strictModeLaunchesAndRefusesAsTheJdkDoes() throws Exception {
    Path script = Files.createDirectory(directory.resolve("launcher dir")).resolve("echo-args.cmd");
    Files.writeString(script, "@echo off\r\necho ONE=[%1]\r\necho ALL=[%*]\r\n", StandardCharsets.UTF_8);
    List<String> awkward = List.of("plain", "with space", "trail\\", "a\"b", "\"pre quoted\"", "",
        "tab\there", "x\\\"y", "100%", "a&b", "a|b", "a^b", "a<b");
    String previous = System.getProperty(STRICT);
    System.setProperty(STRICT, "false");
    int refused = 0;
    try (var control = DefaultChildControl.unrecorded(ManagedChildRegistry.noop())) {
      for (String argument : awkward) {
        List<String> command = new ArrayList<>(List.of(script.toString(), argument, "next"));
        String jdk = outcome(control, ChildKind.EXTRACTION, command);
        if (jdk.startsWith("FAILED:")) refused++;
        assertEquals(jdk, outcome(control, ChildKind.LLAMA_SERVER, command),
            "strict mode treats " + argument + " as the JDK does");
      }
    } finally {
      if (previous == null) System.clearProperty(STRICT); else System.setProperty(STRICT, previous);
    }
    assertTrue(refused > 0, "the JDK refused at least one argument in strict mode");
  }

  private static String outcome(DefaultChildControl control, ChildKind kind, List<String> command)
      throws Exception {
    ChildHandle child;
    try {
      child = control.start(spec(kind, command, null));
    } catch (IOException refused) {
      return "FAILED:" + refused.getMessage();
    }
    child.stdin().close();
    String out = new String(child.stdout().readAllBytes(), StandardCharsets.UTF_8);
    child.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);
    return out;
  }

  private static String failure(DefaultChildControl control, ChildKind kind, List<String> command,
      Path cwd) {
    try {
      ChildHandle started = control.start(spec(kind, command, cwd));
      started.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);
      return "STARTED";
    } catch (IOException refused) {
      return refused.getMessage();
    } catch (Exception unexpected) {
      return "UNEXPECTED:" + unexpected;
    }
  }

  private static ChildSpec spec(ChildKind kind, List<String> argv, Path cwd) {
    return new ChildSpec(kind, argv, cwd, Map.of(), new ChildSpec.Stdio.Pipes(), false,
        OptionalLong.empty(), Optional.empty(), ChildSpec.CooperativeStop.NONE, "launch-failure");
  }
}
