/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.containment.WindowsEngineJob;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

@EnabledOnOs({OS.WINDOWS, OS.LINUX})
final class ChatLaunchCompatibilityTest {
  @TempDir Path directory;

  @Test
  void chatLaunchPreservesPipesArgumentsEnvironmentAndWorkingDirectory() throws Exception {
    Path cwd = Files.createDirectory(directory.resolve("native directory ö"));
    String argument = "ö with spaces and trailing\\";
    try (var control = DefaultChildControl.unrecorded(ManagedChildRegistry.noop())) {
      var command = new ArrayList<>(List.of(
          Path.of(System.getProperty("java.home"), "bin", io.justsearch.configuration.PlatformPaths.isWindows() ? "java.exe" : "java").toString(),
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
    return spec(ChildKind.LLAMA_SERVER, argv, cwd, env);
  }

  private static ChildSpec spec(ChildKind kind, List<String> argv, Path cwd, Map<String, String> env) {
    return new ChildSpec(kind, argv, cwd, env, new ChildSpec.Stdio.Pipes(), false,
        OptionalLong.empty(), Optional.empty(), ChildSpec.CooperativeStop.NONE, "launch-preservation");
  }

  /** Arguments whose command-line form differs between quoting rules. */
  private static final List<String> AWKWARD = List.of("plain", "with space", "trail\\", "a\"b",
      "\"pre quoted\"", "", "tab\there", "x\\\"y", "spaced trail\\", "100%", "=equals",
      "comma,semi;colon");

  /**
   * A {@code .cmd} launcher (as a wrapped llama-server is) in a directory with a space sees the
   * same {@code %1} and {@code %*} whether the JDK starts it (a non-chat kind) or the contained chat
   * launch does: the same quoting, run by Windows through {@code cmd.exe} the same way.
   */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void aCmdLauncherSeesTheSameArgumentsAsUnderTheJdk() throws Exception {
    Path script = Files.createDirectory(directory.resolve("launcher dir")).resolve("echo-args.cmd");
    Files.writeString(script, "@echo off\r\necho ONE=[%1]\r\necho ALL=[%*]\r\n", StandardCharsets.UTF_8);
    List<List<String>> cases = new ArrayList<>();
    cases.add(AWKWARD);
    for (String argument : AWKWARD) cases.add(List.of(argument, "next"));
    try (var control = DefaultChildControl.unrecorded(ManagedChildRegistry.noop())) {
      for (List<String> arguments : cases) {
        List<String> command = new ArrayList<>(List.of(script.toString()));
        command.addAll(arguments);
        String jdk = output(control, ChildKind.EXTRACTION, command);
        String contained = output(control, ChildKind.LLAMA_SERVER, command);
        assertTrue(jdk.contains("ONE=["), "the JDK launch ran the script: " + jdk);
        assertEquals(jdk, contained, "a .cmd sees what it sees under the JDK for " + arguments);
      }
    }
  }

  /** An executable receives every awkward argument exactly as under the JDK launch. */
  @Test
  void embeddedQuotesReachAnExecutableAsUnderTheJdk() throws Exception {
    List<String> command = new ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", io.justsearch.configuration.PlatformPaths.isWindows() ? "java.exe" : "java").toString(),
        "-Dstdout.encoding=UTF-8", "-cp",
        Path.of(Args.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
        Args.class.getName()));
    command.addAll(AWKWARD);
    try (var control = DefaultChildControl.unrecorded(ManagedChildRegistry.noop())) {
      String jdk = output(control, ChildKind.EXTRACTION, command);
      String contained = output(control, ChildKind.LLAMA_SERVER, command);
      assertTrue(jdk.contains("ARGC="), jdk);
      assertEquals(jdk, contained, "an executable sees what it sees under the JDK");
    }
  }

  private static String output(DefaultChildControl control, ChildKind kind, List<String> command)
      throws Exception {
    ChildHandle child = control.start(spec(kind, command, null, Map.of()));
    child.stdin().close();
    String out = new String(child.stdout().readAllBytes(), StandardCharsets.UTF_8);
    child.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);
    return out;
  }

  /**
   * A failure after the suspended chat root exists but before it is resumed (its registration
   * fails) leaves no process behind, as the JDK launch of the same command leaves none.
   */
  @Test
  void aFailureBeforeResumeLeavesNoProcessBehind() throws Exception {
    List<String> idle = io.justsearch.configuration.PlatformPaths.isWindows() ? List.of(
        Path.of(System.getenv("SystemRoot"), "System32", "ping.exe").toString(), "-t", "127.0.0.1")
        : List.of("/bin/sleep", "600");
    for (ChildKind kind : List.of(ChildKind.EXTRACTION, ChildKind.LLAMA_SERVER)) {
      AtomicReference<ManagedChild> registered = new AtomicReference<>();
      ManagedChildRegistry failing = new ManagedChildRegistry() {
        @Override public List<ManagedChild> snapshot() {
          return List.of();
        }

        @Override public void register(ManagedChild child) throws java.io.IOException {
          registered.set(child);
          throw new java.io.IOException("injected registration failure");
        }

        @Override public void remove(String childId) {
          // nothing was registered
        }
      };
      try (var control = DefaultChildControl.unrecorded(failing)) {
        assertThrows(java.io.IOException.class, () -> control.start(spec(kind, idle, null, Map.of())),
            kind + ": a start whose registration fails is refused");
      }
      ManagedChild created = registered.get();
      assertTrue(created != null, kind + ": the process was created before the injected failure");
      assertTrue(gone(created.pid(), created.startedAt()), kind + ": no process left behind");
    }
  }

  /**
   * The contained launch fails while joining its job, after the suspended process exists: that
   * process is ended through its creation handle and nothing is left behind.
   */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void aFailureJoiningTheJobLeavesNoProcessBehind() throws Exception {
    Class<?> kernel = Class.forName(WindowsEngineJob.class.getName() + "$Kernel");
    AtomicReference<ProcessHandle> created = new AtomicReference<>();
    AtomicReference<java.time.Instant> birth = new AtomicReference<>();
    var builder = new ProcessBuilder(
        Path.of(System.getenv("SystemRoot"), "System32", "ping.exe").toString(), "-t", "127.0.0.1");
    try (var _ = Mockito.mockStatic(kernel, invocation -> {
      Object[] raw = invocation.getRawArguments();
      if (raw.length == 2 && "AssignProcessToJobObject".equals(raw[0])) {
        throw new java.io.IOException("injected job assignment failure");
      }
      Object result = invocation.callRealMethod();
      if (raw.length == 2 && "CreateProcessW".equals(raw[0])) {
        Object[] arguments = (Object[]) raw[1];
        var information = (java.lang.foreign.MemorySegment) arguments[arguments.length - 1];
        long pid = Integer.toUnsignedLong(information.get(java.lang.foreign.ValueLayout.JAVA_INT, 16));
        // Our own suspended creation: its identity is read while our handle keeps it existing.
        ProcessHandle.of(pid).ifPresent(process -> {
          created.set(process);
          birth.set(process.info().startInstant().orElse(null));
        });
      }
      return result;
    })) {
      var failure = assertThrows(java.io.IOException.class, () -> WindowsEngineJob.createChat(builder));
      assertTrue(String.valueOf(failure.getMessage()).contains("injected")
          || String.valueOf(failure.getCause()).contains("injected"), failure.toString());
    }
    assertTrue(created.get() != null, "the suspended process was created");
    assertTrue(birth.get() != null, "the suspended process has a creation instant");
    assertTrue(gone(created.get().pid(), birth.get().toString()), "no suspended process is left behind");
  }

  private static boolean gone(long pid, String startedAt) throws Exception {
    Optional<ProcessHandle> same = ProcessHandle.of(pid).filter(p -> p.info().startInstant()
        .map(birth -> birth.toString().equals(startedAt)).orElse(false));
    if (same.isPresent()) {
      try {
        same.get().onExit().get(5, TimeUnit.SECONDS);
      } catch (java.util.concurrent.TimeoutException stillRunning) {
        return false;
      }
    }
    return true;
  }

  /** Prints each argument it receives, bracketed, for comparison. */
  public static final class Args {
    private Args() {}
    public static void main(String[] args) {
      System.out.println("ARGC=" + args.length);
      for (String argument : args) System.out.println("[" + argument + "]");
    }
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
