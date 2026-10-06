/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The Engine job as the Engine gets it (Spec F §1.2). Installing it puts the calling process in a
 * kill-on-close job for good, so every check runs in a fresh probe JVM, never in the test JVM. The
 * probe reaches {@code EngineContainment} by name, so it reports {@code ABSENT} on a build without
 * it instead of failing to compile.
 */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
@Timeout(60)
final class WindowsEngineJobTest {
  /** JOBOBJECT_LIMIT_KILL_ON_JOB_CLOSE, the only limit the Engine job may carry. */
  private static final String KILL_ON_JOB_CLOSE_HEX = "2000";

  /** The probe joins the job and reports what Windows says about the job it is now in. */
  @Test
  void bootJoinsAKillOnCloseJobWithExactlyThatLimit() throws Exception {
    Map<String, String> probe = runProbe();
    assertEquals("CONTAINED", probe.get("status"), probe.toString());
    assertEquals("", probe.get("reason"), "no reason when contained");
    assertEquals("1", probe.get("inJob"), "the probe process is in a job after install");
    assertEquals(KILL_ON_JOB_CLOSE_HEX, probe.get("limitFlags"),
        "the innermost job limits are exactly KILL_ON_JOB_CLOSE: no breakaway, no"
            + " DIE_ON_UNHANDLED_EXCEPTION");
    assertEquals("true", probe.get("idempotent"), "a second install returns the first result");
    assertEquals("true", probe.get("currentIsInstalled"), "current() is what install returned");
    assertEquals("UNAVAILABLE not_installed", probe.get("beforeInstall"),
        "current() before install names that nothing was installed");
  }

  private static Map<String, String> runProbe() throws Exception {
    String java = ProcessHandle.current().info().command().orElse("java");
    Process process = new ProcessBuilder(List.of(java, "--enable-native-access=ALL-UNNAMED",
        "-cp", System.getProperty("java.class.path"), Probe.class.getName()))
        .redirectErrorStream(true).start();
    String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(45, TimeUnit.SECONDS), "probe finishes");
    assertEquals(0, process.exitValue(), out);
    Map<String, String> values = new LinkedHashMap<>();
    for (String line : out.split("\\R")) {
      int eq = line.indexOf('=');
      if (line.startsWith("probe.") && eq > 0) values.put(line.substring(6, eq), line.substring(eq + 1));
    }
    return values;
  }

  /** Runs in its own JVM: installs the Engine job and reports on it. */
  public static final class Probe {
    public static void main(String[] args) throws Throwable {
      Class<?> containment;
      try {
        containment = Class.forName("io.justsearch.app.api.runtime.containment.EngineContainment");
      } catch (ClassNotFoundException absent) {
        System.out.println("probe.status=ABSENT");
        return;
      }
      Method current = containment.getMethod("current");
      Method install = containment.getMethod("installAtBoot");
      Object before = current.invoke(null);
      System.out.println("probe.beforeInstall=" + status(before) + " " + reason(before));
      Object installed = install.invoke(null);
      System.out.println("probe.status=" + status(installed));
      System.out.println("probe.reason=" + reason(installed));
      System.out.println("probe.idempotent=" + (install.invoke(null) == installed));
      System.out.println("probe.currentIsInstalled=" + (current.invoke(null) == installed));

      Linker linker = Linker.nativeLinker();
      SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
      MethodHandle query = linker.downcallHandle(kernel.find("QueryInformationJobObject").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      MethodHandle inJob = linker.downcallHandle(kernel.find("IsProcessInJob").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
              ValueLayout.ADDRESS));
      MethodHandle self = linker.downcallHandle(kernel.find("GetCurrentProcess").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.ADDRESS));
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment result = arena.allocate(ValueLayout.JAVA_INT);
        int ok = (int) inJob.invokeExact((MemorySegment) self.invokeExact(), MemorySegment.NULL,
            result);
        System.out.println("probe.inJob=" + (ok == 0 ? "error" : result.get(ValueLayout.JAVA_INT, 0)));
        // A NULL job handle queries the job the caller is in, the innermost one when nested.
        MemorySegment limits = arena.allocate(144, 8);
        int queried = (int) query.invokeExact(MemorySegment.NULL, 9, limits, 144,
            MemorySegment.NULL);
        System.out.println("probe.limitFlags=" + (queried == 0 ? "error"
            : Integer.toHexString(limits.get(ValueLayout.JAVA_INT, 16))));
      }
    }

    private static Object status(Object containment) throws ReflectiveOperationException {
      return containment.getClass().getMethod("status").invoke(containment);
    }

    private static String reason(Object containment) throws ReflectiveOperationException {
      return ((Optional<?>) containment.getClass().getMethod("unavailableReason")
          .invoke(containment)).map(String::valueOf).orElse("");
    }
  }
}
