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
 * Task b6d536 b101: a job-process exit that follows a recorded owner intent carries that intent's
 * class and reason; only an exit with no recorded intent is UNEXPECTED, and its reason says so.
 * Real Windows job and processes in a disposable JVM; the same assertions run on b599d4d7e.
 */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
@Timeout(90)
final class JobWitnessIntentTest {
  @Test void jobProcessExitsCarryTheOwnersIntent() throws Exception {
    Process probe = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
        Probe.class.getName()).redirectErrorStream(true).start();
    String out = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    System.out.println(out);
    assertTrue(probe.waitFor(75, TimeUnit.SECONDS), out);
    assertEquals(0, probe.exitValue(), out);
    assertTrue(out.contains("stopped=REQUESTED|witness intent probe: test stop"),
        "an exit after a stop intent is REQUESTED with that intent's reason: " + out);
    assertTrue(out.contains("awaited=REQUESTED|awaited probe: awaited completion"),
        "an exit the owner awaited is REQUESTED with the owner's reason: " + out);
    assertTrue(out.contains("unowned=UNEXPECTED|no recorded intent"),
        "only an exit with no recorded intent is UNEXPECTED, and its reason says so: " + out);
    assertTrue(out.contains("hosts.unexpected=0"), "console hosts follow their client's intent: " + out);
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      ProcessTerminator os = ProcessTerminator.forCurrentOs();
      EngineContainment job = EngineContainment.installAtBoot();
      if (job.status() != EngineContainment.Status.CONTAINED) throw new AssertionError(job);
      List<Map<String, Object>> records = new CopyOnWriteArrayList<>();
      LedgerSink sink = (type, fields) -> {
        Map<String, Object> record = new java.util.LinkedHashMap<>(fields);
        record.put("type", type.token());
        records.add(record);
        return true;
      };
      EngineIdentity self = new EngineIdentity(ProcessHandle.current().pid(),
          os.creationFileTime(ProcessHandle.current().pid()), "witness-intent");
      job.recordProcesses(sink, self);
      OwnedHelper.recordTo(sink, self);

      Process stopped = OwnedHelper.start(shell("Start-Sleep -Seconds 30"), "witness intent probe");
      Thread.sleep(1_000);
      OwnedHelper.terminate(stopped, "test stop");
      if (!stopped.waitFor(15, TimeUnit.SECONDS)) throw new AssertionError("stopped helper survived");
      Process awaited = OwnedHelper.start(shell("exit 3"), "awaited probe");
      if (!awaited.waitFor(15, TimeUnit.SECONDS)) throw new AssertionError("awaited helper survived");
      Process unowned = shell("exit 0").start();
      if (!unowned.waitFor(15, TimeUnit.SECONDS)) throw new AssertionError("unowned child survived");

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (System.nanoTime() < deadline && (exit(records, stopped) == null
          || exit(records, awaited) == null || exit(records, unowned) == null)) {
        Thread.sleep(50);
      }
      Thread.sleep(3_000); // console hosts end after their clients
      job.finishProcessRecording(Duration.ofSeconds(5));
      System.out.println("stopped=" + describe(exit(records, stopped)));
      System.out.println("awaited=" + describe(exit(records, awaited)));
      System.out.println("unowned=" + describe(exit(records, unowned)));
      List<Long> clients = List.of(stopped.pid(), awaited.pid(), unowned.pid());
      List<Object> hostIds = records.stream().filter(r -> "job-process-start".equals(r.get("type"))
          && String.valueOf(r.get("executable")).toLowerCase(java.util.Locale.ROOT).endsWith("conhost.exe")
          && r.get("parentPid") instanceof Long parent && clients.subList(0, 2).contains(parent))
          .map(r -> r.get("witnessId")).toList();
      long unexpectedHosts = records.stream().filter(r -> "job-process-exit".equals(r.get("type"))
          && hostIds.contains(r.get("witnessId")) && !"REQUESTED".equals(r.get("exitClass"))).count();
      System.out.println("hosts.owned=" + hostIds.size());
      System.out.println("hosts.unexpected=" + unexpectedHosts);
      records.forEach(r -> System.out.println("record=" + r));
    }

    private static ProcessBuilder shell(String command) {
      return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
    }

    private static Map<String, Object> exit(List<Map<String, Object>> records, Process p) {
      return records.stream().filter(r -> "job-process-exit".equals(r.get("type"))
          && Long.valueOf(p.pid()).equals(r.get("pid"))).findFirst().orElse(null);
    }

    private static String describe(Map<String, Object> exit) {
      return exit == null ? "missing" : exit.get("exitClass") + "|" + exit.get("reason");
    }
  }
}
