/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.LedgerSink;
import io.justsearch.app.api.runtime.child.OwnedHelper;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Task b6d536 b101, preservation: a window is its owner's. While the Engine holds a window for an
 * image, a child JVM with no window of its own launches a real process of that image; the native
 * creator of that process is the child, not the window's owner, so the Engine job's witness
 * records its exit UNEXPECTED with no recorded intent, as b599d4d7e does.
 */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
@Timeout(90)
final class WindowOwnerIdentityTest {
  @Test void aChildWithoutAWindowCannotBorrowTheEngineWindow() throws Exception {
    Process probe = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
        Probe.class.getName()).redirectErrorStream(true).start();
    String out = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    System.out.println(out);
    assertTrue(probe.waitFor(75, TimeUnit.SECONDS), out);
    assertEquals(0, probe.exitValue(), out);
    assertTrue(out.contains("engine.window=open"), out);
    assertTrue(out.contains("native.creator=child"), "the launched process's native parent is the child: " + out);
    assertTrue(out.contains("witness=UNEXPECTED|"),
        "a window attributes only launches by its owner; this one had no recorded intent: " + out);
  }

  /** The Engine: its job and witness, and a window for ping.exe held while its child runs. */
  public static final class Probe {
    public static void main(String[] args) throws Exception {
      EngineContainment job = EngineContainment.installAtBoot();
      if (job.status() != EngineContainment.Status.CONTAINED) throw new AssertionError(job);
      ProcessTerminator os = ProcessTerminator.forCurrentOs();
      long self = ProcessHandle.current().pid();
      List<Map<String, Object>> records = new CopyOnWriteArrayList<>();
      LedgerSink sink = (type, fields) -> {
        Map<String, Object> record = new java.util.LinkedHashMap<>(fields);
        record.put("type", type.token());
        records.add(record);
        return true;
      };
      EngineIdentity engine = new EngineIdentity(self, os.creationFileTime(self), "window-owner");
      job.recordProcesses(sink, engine);
      OwnedHelper.recordTo(sink, engine);
      Process child;
      String childBirth;
      String childOut;
      try (var _ = OwnedHelper.window("ping.exe", "Engine's own ping window", Duration.ofSeconds(60))) {
        System.out.println("engine.window=" + (records.stream()
            .anyMatch(r -> "helper-window-start".equals(r.get("type"))) ? "open" : "absent"));
        child = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
            "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
            Child.class.getName()).redirectErrorStream(true).start();
        childBirth = os.creationFileTime(child.pid()).orElseThrow();
        childOut = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        System.out.print(childOut);
        if (!child.waitFor(30, TimeUnit.SECONDS) || child.exitValue() != 0) throw new AssertionError(childOut);
        long pid = Long.parseLong(childOut.lines().filter(s -> s.startsWith("launched.pid="))
            .findFirst().orElseThrow().substring("launched.pid=".length()));
        // Resolve while the Engine's window is still open: only the owner identity can decide.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && exit(records, pid) == null) Thread.sleep(25);
        Map<String, Object> start = records.stream().filter(r -> "job-process-start".equals(r.get("type"))
            && Long.valueOf(pid).equals(r.get("pid"))).findFirst().orElse(Map.of());
        boolean nativeCreator = Long.valueOf(child.pid()).equals(start.get("parentPid"))
            && childBirth.equals(start.get("parentCreationFileTime"));
        System.out.println("native.creator=" + (nativeCreator ? "child" : "other " + start));
        Map<String, Object> exit = exit(records, pid);
        System.out.println("witness=" + (exit == null ? "missing" : exit.get("exitClass") + "|" + exit.get("reason")));
      }
      job.finishProcessRecording(Duration.ofSeconds(3));
      records.forEach(r -> System.out.println("record=" + r));
    }

    private static Map<String, Object> exit(List<Map<String, Object>> records, long pid) {
      return records.stream().filter(r -> "job-process-exit".equals(r.get("type"))
          && Long.valueOf(pid).equals(r.get("pid"))).findFirst().orElse(null);
    }
  }

  /** A child JVM with no window and no recorder of its own. */
  public static final class Child {
    public static void main(String[] args) throws Exception {
      Process launched = new ProcessBuilder("ping.exe", "-n", "2", "127.0.0.1")
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      System.out.println("launched.pid=" + launched.pid());
      if (!launched.waitFor(20, TimeUnit.SECONDS)) throw new AssertionError("ping did not finish");
    }
  }
}
