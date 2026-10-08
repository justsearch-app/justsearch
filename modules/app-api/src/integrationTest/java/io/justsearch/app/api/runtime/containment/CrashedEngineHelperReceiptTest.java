/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.RuntimeManifest;
import io.justsearch.app.api.runtime.child.*;
import io.justsearch.app.api.runtime.ledger.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** A disposable Engine job crashes with a live helper; its successor writes the missing outcome. */
@EnabledOnOs(OS.WINDOWS)
@Timeout(90)
class CrashedEngineHelperReceiptTest {
  @TempDir Path data;
  @Test void successorRecordsCrashConsequenceAndUnknownExitTime() throws Exception {
    ProcessBuilder builder = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
        Holder.class.getName(), data.toString()).redirectErrorStream(true);
    builder.environment().put("JUSTSEARCH_ENGINE_INCARNATION", "crash-helper/1");
    Process fixture = builder.start();
    var held = ProcessTerminator.forCurrentOs().open(fixture);
    String birth = held.creationFileTime().orElseThrow();
    long helperPid = 0;
    try {
      var output = new BufferedReader(new InputStreamReader(fixture.getInputStream(), StandardCharsets.UTF_8));
      for (String line; (line = output.readLine()) != null;) {
        if (line.startsWith("READY ")) { helperPid = Long.parseLong(line.substring(6)); break; }
      }
      assertTrue(helperPid > 0, "fixture identity " + fixture.pid() + "/" + birth);
      assertEquals(TerminateResult.OK, held.terminate(1));
      assertTrue(fixture.waitFor(10, TimeUnit.SECONDS));
      var writer = ExitLedgerWriter.appendOnly(LedgerFamily.directory(data), LedgerFamily.ENGINE,
          LedgerWriterId.currentProcess(LedgerWriterId.Role.DEV_RUNNER,
              ProcessHandle.current().info().startInstant().orElseThrow()), Clock.systemUTC());
      writer.append(LedgerRecordType.ENGINE_EXIT, Map.of("incarnation", new RuntimeManifest.IncarnationInfo("crash-helper", 1),
          "exitClass", "UNEXPECTED", "code", 1, "observedAt", java.time.Instant.now()));
      // The previous Engine's job has closed; the successor only observes identities.
      try (var next = DefaultChildControl.openForEngine(data, "successor", ManagedChildRegistry.noop(),
          VramProbe.none(), EngineContainment.current())) { }
      long target = helperPid;
      var records = ExitLedgerReader.read(LedgerFamily.directory(data), LedgerFamily.CHILDREN).records();
      var receipt = records.stream().filter(r -> r.type() == LedgerRecordType.JOB_PROCESS_EXIT
          && r.field("pid").asLong(0) == target).findFirst().orElseThrow(() ->
              new AssertionError("witnessed crash casualty has no final owner receipt"));
      assertEquals("OWNER_DIED", receipt.text("exitClass").orElseThrow());
      assertEquals("ENGINE_CRASH", receipt.text("cause").orElseThrow());
      assertTrue(receipt.text("reason").orElseThrow().contains("Engine crash"));
      assertTrue(receipt.field("exitedAt").isNull(), "observation time must not invent an exit time");
      assertTrue(receipt.field("code").isNull());
    } finally {
      if (fixture.isAlive()) held.terminate(1);
      held.close();
    }
  }
  public static class Holder {
    public static void main(String[] args) throws Exception {
      var job = EngineContainment.installAtBoot();
      assertEquals(EngineContainment.Status.CONTAINED, job.status());
      Path data = Path.of(args[0]);
      var engine = ExitLedgerWriter.appendOnly(LedgerFamily.directory(data), LedgerFamily.ENGINE,
          LedgerWriterId.currentProcess(LedgerWriterId.Role.ENGINE,
              ProcessHandle.current().info().startInstant().orElseThrow()), Clock.systemUTC());
      engine.append(LedgerRecordType.ENGINE_START, Map.of("pid", ProcessHandle.current().pid(),
          "creationFileTime", ProcessTerminator.forCurrentOs().creationFileTime(ProcessHandle.current().pid()).orElseThrow(),
          "executable", ProcessHandle.current().info().command().orElseThrow(),
          "incarnation", new RuntimeManifest.IncarnationInfo("crash-helper", 1)));
      try (var control = DefaultChildControl.openForEngine(data, "crash-owner", ManagedChildRegistry.noop(),
          VramProbe.none(), job)) {
        Process helper = OwnedHelper.start(new ProcessBuilder("ping.exe", "-t", "127.0.0.1")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD), "crash fixture helper");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ExitLedgerReader.read(LedgerFamily.directory(data), LedgerFamily.CHILDREN).records().stream()
            .noneMatch(r -> r.type() == LedgerRecordType.JOB_PROCESS_START && r.field("pid").asLong(0) == helper.pid())) {
          if (System.nanoTime() > until) throw new AssertionError("helper not witnessed");
          Thread.sleep(20);
        }
        System.out.println("READY " + helper.pid()); System.out.flush();
        Thread.sleep(Duration.ofSeconds(60));
      }
    }
  }
}
