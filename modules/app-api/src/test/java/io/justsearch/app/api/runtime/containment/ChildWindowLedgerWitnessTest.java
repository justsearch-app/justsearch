/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.LedgerSink;
import io.justsearch.app.api.runtime.child.OwnedHelper;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.ExitLedgerWriter;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.app.api.runtime.ledger.LedgerWriterId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Task b6d536 b101: a window the extraction child opens in its own JVM reaches the Engine job's
 * witness through the shared children ledger. A real Engine job and children ledger; a child JVM
 * given the Engine's helper-ledger argument records its window there and launches a real process
 * of the window's image inside it. The witness records that exit REQUESTED with the window's reason.
 */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
@Timeout(90)
final class ChildWindowLedgerWitnessTest {
  static final String REASON = "child window probe during extraction";

  @Test void aWindowOpenedInAChildJvmReachesTheEngineWitness() throws Exception {
    Path ledger = Files.createTempDirectory("b101-child-window");
    Process probe = java(Probe.class, ledger.toString()).redirectErrorStream(true).start();
    String out = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    System.out.println(out);
    assertTrue(probe.waitFor(75, TimeUnit.SECONDS), out);
    assertEquals(0, probe.exitValue(), out);
    assertTrue(out.contains("ledger.window.start.owner=child"), "the child records its window in the shared ledger: " + out);
    assertTrue(out.contains("native.creator=child"), "the launched process's native parent is the child: " + out);
    assertTrue(out.contains("witness=REQUESTED|" + REASON + "|owner window"),
        "the witness attributes the child's launch to the child's window: " + out);
  }

  static ProcessBuilder java(Class<?> main, String... args) {
    List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), main.getName()));
    command.addAll(List.of(args));
    return new ProcessBuilder(command);
  }

  /** The Engine: its job, its witness and its children ledger; it launches one child JVM. */
  public static final class Probe {
    public static void main(String[] args) throws Exception {
      EngineContainment job = EngineContainment.installAtBoot();
      if (job.status() != EngineContainment.Status.CONTAINED) throw new AssertionError(job);
      ProcessTerminator os = ProcessTerminator.forCurrentOs();
      Path dir = Path.of(args[0]).toAbsolutePath();
      ProcessHandle self = ProcessHandle.current();
      ExitLedgerWriter writer = ExitLedgerWriter.rotating(dir, LedgerFamily.CHILDREN,
          LedgerWriterId.currentProcess(LedgerWriterId.Role.ENGINE, self.info().startInstant().orElse(Instant.now())),
          Clock.systemUTC());
      writer.startSession();
      List<Map<String, Object>> records = new CopyOnWriteArrayList<>();
      LedgerSink ledger = LedgerSink.of(writer);
      LedgerSink sink = new LedgerSink() {
        @Override public boolean append(LedgerRecordType type, Map<String, ?> fields) {
          note(type, fields);
          return ledger.append(type, fields);
        }

        @Override public boolean appendUnforced(LedgerRecordType type, Map<String, ?> fields) {
          note(type, fields);
          return ledger.appendUnforced(type, fields);
        }

        private void note(LedgerRecordType type, Map<String, ?> fields) {
          Map<String, Object> record = new java.util.LinkedHashMap<>(fields);
          record.put("type", type.token());
          records.add(record);
        }
      };
      EngineIdentity engine = new EngineIdentity(self.pid(), os.creationFileTime(self.pid()), "child-window");
      job.recordProcesses(sink, engine);
      OwnedHelper.recordTo(sink, engine, dir); // as DefaultChildControl.openForEngine does
      List<String> childArgs = new ArrayList<>(OwnedHelper.childArguments());
      Process child = java(Child.class, childArgs.toArray(String[]::new)).redirectErrorStream(true).start();
      String childBirth = os.creationFileTime(child.pid()).orElseThrow();
      String childOut = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      System.out.print(childOut);
      if (!child.waitFor(30, TimeUnit.SECONDS) || child.exitValue() != 0) throw new AssertionError(childOut);
      long pid = Long.parseLong(childOut.lines().filter(s -> s.startsWith("launched.pid="))
          .findFirst().orElseThrow().substring("launched.pid=".length()));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline && exit(records, pid) == null) Thread.sleep(25);
      job.finishProcessRecording(Duration.ofSeconds(3));
      String shared = Files.readString(LedgerFamily.CHILDREN.current(dir), StandardCharsets.UTF_8);
      boolean childWindow = shared.lines().anyMatch(line -> line.contains("\"helper-window-start\"")
          && line.contains("\"pid\":" + child.pid()) && line.contains(REASON));
      Map<String, Object> start = records.stream().filter(r -> "job-process-start".equals(r.get("type"))
          && Long.valueOf(pid).equals(r.get("pid"))).findFirst().orElse(Map.of());
      boolean nativeCreator = Long.valueOf(child.pid()).equals(start.get("parentPid"))
          && childBirth.equals(start.get("parentCreationFileTime"));
      Map<String, Object> exit = exit(records, pid);
      System.out.println("ledger.window.start.owner=" + (childWindow ? "child" : "absent"));
      System.out.println("native.creator=" + (nativeCreator ? "child" : "other " + start));
      System.out.println("witness=" + (exit == null ? "missing"
          : exit.get("exitClass") + "|" + exit.get("reason") + "|" + exit.get("intentBasis")));
      shared.lines().forEach(line -> System.out.println("ledger=" + line));
    }

    private static Map<String, Object> exit(List<Map<String, Object>> records, long pid) {
      return records.stream().filter(r -> "job-process-exit".equals(r.get("type"))
          && Long.valueOf(pid).equals(r.get("pid"))).findFirst().orElse(null);
    }
  }

  /** The extraction child: records into the Engine's ledger and launches inside its own window. */
  public static final class Child {
    public static void main(String[] args) throws Exception {
      OwnedHelper.recordChild(args);
      var window = OwnedHelper.sharedWindow("ping.exe", REASON, Duration.ofSeconds(30));
      try (var _ = window.acquire()) {
        // Not an owned helper: only the window can attribute it. It waits about a second, so
        // the witness holds it while it runs.
        Process launched = new ProcessBuilder("ping.exe", "-n", "2", "127.0.0.1")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        System.out.println("launched.pid=" + launched.pid());
        if (!launched.waitFor(20, TimeUnit.SECONDS)) throw new AssertionError("ping did not finish");
      }
      window.close();
    }
  }
}
