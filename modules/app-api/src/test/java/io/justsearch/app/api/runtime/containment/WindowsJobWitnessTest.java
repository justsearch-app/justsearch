/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.LedgerSink;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Real Windows job/handle evidence, in a disposable JVM. All children exit themselves. */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
@Timeout(120)
final class WindowsJobWitnessTest {
  @Test void recordsExactBirthAndExitCodeAndNeverClaimsAnOutsideChild() throws Exception {
    Process probe = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
        Probe.class.getName()).redirectErrorStream(true).start();
    String out = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    out.lines().filter(line -> line.startsWith("witness.probe.identity=")
        || line.startsWith("witness.child.identity=")).forEach(System.out::println);
    assertTrue(probe.waitFor(90, TimeUnit.SECONDS), out);
    assertEquals(0, probe.exitValue(), out);
    assertTrue(out.contains("witness.normal=true"), "NEW and EXIT carry exact held-handle PID/birth/code: " + out);
    assertTrue(out.contains("witness.crash=true"), "nonzero exit during the owner's await remains unexpected: " + out);
    assertTrue(out.contains("witness.outside=true"), "pre-job child has no witness: " + out);
    assertTrue(out.contains("witness.host=true"), "console hosts are witnessed too: " + out);
    assertTrue(out.contains("witness.drain=true"), "writer drains and freezes before owner lock release: " + out);
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      ProcessTerminator os = ProcessTerminator.forCurrentOs();
      System.out.println("witness.probe.identity=" + ProcessHandle.current().pid() + " FILETIME:"
          + os.creationFileTime(ProcessHandle.current().pid()).orElseThrow());
      Process outside = child(0, 1200);
      String outsideBirth = os.creationFileTime(outside.pid()).orElseThrow();
      System.out.println("witness.child.identity=" + outside.pid() + " FILETIME:" + outsideBirth);
      EngineContainment job = EngineContainment.installAtBoot();
      if (job.status() != EngineContainment.Status.CONTAINED) throw new AssertionError(job);
      List<Map<String, Object>> records = new CopyOnWriteArrayList<>();
      LedgerSink sink = (type, fields) -> {
        Map<String, Object> record = new java.util.LinkedHashMap<>(fields);
        record.put("type", type.token());
        records.add(record);
        return true;
      };
      // Reflection keeps this exact test compilable on e546e9e54: absence fails assertions,
      // rather than compilation or method linkage.
      try {
        EngineContainment.class.getMethod("recordProcesses", LedgerSink.class, EngineIdentity.class)
            .invoke(job, sink, new EngineIdentity(ProcessHandle.current().pid(),
                os.creationFileTime(ProcessHandle.current().pid()), "witness-test"));
      } catch (NoSuchMethodException before) { /* No witnesses on the before-state. */ }
      Process normal = child(0, 500);
      String normalBirth = os.creationFileTime(normal.pid()).orElseThrow();
      System.out.println("witness.child.identity=" + normal.pid() + " FILETIME:" + normalBirth);
      Process crash = child(17, 500);
      String crashBirth = os.creationFileTime(crash.pid()).orElseThrow();
      System.out.println("witness.child.identity=" + crash.pid() + " FILETIME:" + crashBirth);
      for (Process p : List.of(outside, normal, crash)) {
        if (!p.waitFor(15, TimeUnit.SECONDS)) throw new AssertionError("child did not exit: " + p.pid());
      }
      // Each process and the writer advance independently: a crash receipt is no barrier
      // for the normal child's receipt or the console host's start record.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (System.nanoTime() < deadline
          && (!matches(records, normal.pid(), normalBirth, 0, "UNEXPECTED")
              || !matches(records, crash.pid(), crashBirth, 17, "UNEXPECTED")
              || !hasConsoleWitness(records))) {
        Thread.sleep(25);
      }
      System.out.println("witness.normal=" + matches(records, normal.pid(), normalBirth, 0, "UNEXPECTED"));
      System.out.println("witness.crash=" + matches(records, crash.pid(), crashBirth, 17, "UNEXPECTED"));
      System.out.println("witness.outside=" + records.stream().noneMatch(r ->
          Long.valueOf(outside.pid()).equals(r.get("pid")) && outsideBirth.equals(r.get("creationFileTime"))));
      System.out.println("witness.host=" + hasConsoleWitness(records));
      boolean drain = false;
      try {
        EngineContainment.class.getMethod("finishProcessRecording", java.time.Duration.class)
            .invoke(job, java.time.Duration.ofSeconds(2));
        int count = records.size();
        Process afterFence = child(0, 100);
        String afterBirth = os.creationFileTime(afterFence.pid()).orElseThrow();
        System.out.println("witness.child.identity=" + afterFence.pid() + " FILETIME:" + afterBirth);
        if (!afterFence.waitFor(15, TimeUnit.SECONDS)) throw new AssertionError("after-fence child " + afterBirth);
        Thread.sleep(75);
        drain = count > 0 && count == records.size();
      } catch (NoSuchMethodException before) { /* Before-state has no fence. */ }
      System.out.println("witness.drain=" + drain);
      for (Map<String, Object> r : records) System.out.println("witness.record=" + r);
    }

    private static Process child(int code, int milliseconds) throws Exception {
      return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
          "Start-Sleep -Milliseconds " + milliseconds + "; exit " + code)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start();
    }

    private static boolean hasConsoleWitness(List<Map<String, Object>> records) {
      return records.stream().anyMatch(r -> "job-process-start".equals(r.get("type"))
          && String.valueOf(r.get("executable")).endsWith("conhost.exe")
          && r.get("creationFileTime") != null);
    }

    private static boolean matches(List<Map<String, Object>> records, long pid, String birth,
        int code, String classification) {
      List<Map<String, Object>> own = records.stream().filter(r -> Long.valueOf(pid).equals(r.get("pid"))
          && birth.equals(r.get("creationFileTime"))).toList();
      return own.stream().filter(r -> "job-process-start".equals(r.get("type")) && Boolean.TRUE.equals(r.get("contained"))).count() == 1
          && own.stream().filter(r -> "job-process-exit".equals(r.get("type"))
              && Integer.valueOf(code).equals(r.get("code")) && classification.equals(r.get("exitClass"))).count() == 1;
    }
  }
}
