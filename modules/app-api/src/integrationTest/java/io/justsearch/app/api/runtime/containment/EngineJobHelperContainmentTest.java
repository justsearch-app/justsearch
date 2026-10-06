/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Spec B T23 (containment part): a holder JVM that boots like the Engine (Engine job first, then
 * a console grandchild with its own console host) is killed with {@code taskkill /F}; within 3
 * seconds nothing it started is alive, console hosts included.
 *
 * <p>The holder reaches {@code EngineContainment.installAtBoot} by name, so this class runs
 * unchanged against a build without it, where the grandchild outlives the holder.
 */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
@Timeout(90)
final class EngineJobHelperContainmentTest {
  private static final long SURVIVOR_BOUND_MS = 3_000;

  @Test
  void killedEngineLeavesNoHelperOrConsoleHost() throws Exception {
    String java = ProcessHandle.current().info().command().orElse("java");
    Process holder = new ProcessBuilder(List.of(java, "--enable-native-access=ALL-UNNAMED",
        "-cp", System.getProperty("java.class.path"), Holder.class.getName()))
        .redirectErrorStream(true).start();
    List<ProcessHandle> started = new ArrayList<>();
    try {
      var lines = new BufferedReader(
          new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8));
      String containment = "";
      long grandchild = -1;
      for (String line; (line = lines.readLine()) != null; ) {
        if (line.startsWith("CONTAINMENT ")) containment = line.substring(12);
        if (line.startsWith("GRANDCHILD ")) grandchild = Long.parseLong(line.substring(11));
        if (line.equals("READY")) break;
      }
      assertTrue(grandchild > 0, "the holder started its grandchild");
      ProcessHandle ping = ProcessHandle.of(grandchild).orElseThrow();
      started.add(ping);
      // Everything below the holder: the grandchild, its console host and the holder's own.
      holder.toHandle().descendants().forEach(started::add);
      assertTrue(started.stream().anyMatch(h -> isConsoleHost(h) && isChildOf(h, ping)),
          "the grandchild has a console host to account for: " + describe(started));

      Process kill = new ProcessBuilder("taskkill", "/F", "/PID", Long.toString(holder.pid()))
          .redirectErrorStream(true).start();
      assertTrue(kill.waitFor(30, TimeUnit.SECONDS));
      assertTrue(holder.waitFor(10, TimeUnit.SECONDS), "the holder died");

      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SURVIVOR_BOUND_MS);
      while (started.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      List<ProcessHandle> survivors = started.stream().filter(ProcessHandle::isAlive).toList();
      assertEquals(List.of(), survivors.stream().map(EngineJobHelperContainmentTest::name).toList(),
          "nothing the killed Engine started is alive " + SURVIVOR_BOUND_MS + " ms later");
      assertEquals("CONTAINED", containment, "the holder booted contained");
    } finally {
      holder.destroyForcibly();
      for (ProcessHandle h : started) {
        if (h.isAlive()) h.destroyForcibly();
      }
    }
    assertFalse(holder.isAlive());
  }

  private static boolean isConsoleHost(ProcessHandle h) {
    return name(h).equalsIgnoreCase("conhost.exe");
  }

  private static boolean isChildOf(ProcessHandle h, ProcessHandle parent) {
    return h.parent().map(p -> p.pid() == parent.pid()).orElse(false);
  }

  private static String name(ProcessHandle h) {
    String command = h.info().command().orElse("?");
    return command.substring(Math.max(command.lastIndexOf('\\'), command.lastIndexOf('/')) + 1);
  }

  private static String describe(List<ProcessHandle> handles) {
    return handles.stream().map(h -> name(h) + ":" + h.pid()).toList().toString();
  }

  /** Boots like the Engine: containment first, then a console grandchild; then waits to die. */
  public static final class Holder {
    public static void main(String[] args) throws Exception {
      String containment;
      try {
        Object installed = Class.forName(
                "io.justsearch.app.api.runtime.containment.EngineContainment")
            .getMethod("installAtBoot").invoke(null);
        containment = String.valueOf(installed.getClass().getMethod("status").invoke(installed));
      } catch (ReflectiveOperationException absent) {
        containment = "ABSENT";
      }
      System.out.println("CONTAINMENT " + containment);
      Process ping = new ProcessBuilder("ping", "-n", "600", "127.0.0.1")
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();
      System.out.println("GRANDCHILD " + ping.pid());
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (ping.toHandle().children().noneMatch(Holder::isConhost) && System.nanoTime() < until) {
        Thread.sleep(20);
      }
      System.out.println("READY");
      System.out.flush();
      Thread.sleep(120_000);
    }

    private static boolean isConhost(ProcessHandle h) {
      return h.info().command().map(c -> c.toLowerCase(java.util.Locale.ROOT)
          .endsWith("conhost.exe")).orElse(false);
    }
  }
}
