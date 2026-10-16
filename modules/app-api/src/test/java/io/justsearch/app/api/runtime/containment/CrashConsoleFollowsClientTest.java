/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.app.api.runtime.child.*;
import io.justsearch.app.api.runtime.ledger.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task b6d536 b101: a helper that crashes inside a valid owner window is UNEXPECTED, and so is its
 * real console host, which follows the client's final class. The witness applies the crash code
 * before it keeps the client's class, so the host never reads a preliminary REQUESTED, here while
 * the owner's exit receipt is delayed 4.5 s in publication. From the round-4 verifier's
 * crash-console preservation check; it passes on b599d4d7e.
 */
@Tag("windows")
@EnabledOnOs(value = OS.WINDOWS, disabledReason = "Requires a Windows job witness and real console host")
final class CrashConsoleFollowsClientTest {
  @TempDir Path dir;

  @Test void crashClientDoesNotLeaveRequestedConsoleIntent() throws Exception {
    Process p = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
        Probe.class.getName(), dir.toAbsolutePath().toString()).redirectErrorStream(true).start();
    String birth = ProcessTerminator.forCurrentOs().creationFileTime(p.pid()).orElseThrow();
    System.out.println("probe identity=" + p.pid() + "/" + birth);
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    System.out.print(out);
    assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the probe ended in time: " + out);
    assertEquals(0, p.exitValue(), "the probe ran to its end: " + out);
    assertFalse(p.isAlive(), "exact original Process probe has ended");
    assertTrue(out.contains("window.open.through.witness=true"), "the helper exit is witnessed inside the window: " + out);
    assertTrue(out.contains("helper.final=UNEXPECTED"), "the owner records the crashing helper UNEXPECTED: " + out);
    assertTrue(out.contains("witness.final=UNEXPECTED"), "the witness records the crashing helper UNEXPECTED: " + out);
    assertTrue(out.contains("actual.conhost.final=UNEXPECTED"),
        "the real console host follows its crashing client, UNEXPECTED: " + out);
    assertTrue(out.contains("console.final=UNEXPECTED") || out.contains("console.final=unavailable-before-API"),
        "the console resolution follows the client's final class: " + out);
  }

  public static final class Crash {
    public static void main(String[] args) throws Exception { Thread.sleep(400); System.exit((int)0xC0000005L); }
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      var job = EngineContainment.installAtBoot();
      if (job.status() != EngineContainment.Status.CONTAINED) throw new AssertionError(job);
      ProcessHandle self = ProcessHandle.current();
      var os = ProcessTerminator.forCurrentOs();
      var writer = ExitLedgerWriter.rotating(Path.of(args[0]), LedgerFamily.CHILDREN,
          LedgerWriterId.currentProcess(LedgerWriterId.Role.ENGINE, self.info().startInstant().orElseThrow()),
          Clock.systemUTC());
      writer.startSession();
      var owner = new EngineSelfIntentWriter.EngineIdentity(self.pid(), os.creationFileTime(self.pid()), "crash-console");
      List<Map<String,Object>> rows = new CopyOnWriteArrayList<>();
      LedgerSink sink = (type, fields) -> {
          if (type == LedgerRecordType.HELPER_EXIT) {
            System.out.println("helper.exit.publication.delayed=true");
            try { Thread.sleep(4500); } catch (InterruptedException e) { throw new RuntimeException(e); }
          }
          boolean ok = writer.append(type, fields);
          var row = new LinkedHashMap<String,Object>(fields); row.put("type",type.token()); rows.add(row);
          return ok;
      };
      job.recordProcesses(sink, owner);
      OwnedHelper.recordTo(sink, owner);
      AutoCloseable shared;
      AutoCloseable lease;
      try {
        Object window = OwnedHelper.class.getMethod("sharedWindow", String.class, String.class, Duration.class)
            .invoke(null, "java.exe", "OCR valid live window", Duration.ofMinutes(1));
        shared = (AutoCloseable) window;
        lease = (AutoCloseable) window.getClass().getMethod("acquire").invoke(window);
      } catch (NoSuchMethodException beforeApi) {
        shared = OwnedHelper.window("java.exe", "OCR valid live window", Duration.ofMinutes(1));
        lease = () -> {};
      }
      try (var ignored = shared; var unused = lease) {
        Process helper = OwnedHelper.start(new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-cp", System.getProperty("java.class.path"), Crash.class.getName())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD), "OCR");
        long helperPid = helper.pid();
        String helperBirth = os.creationFileTime(helperPid).orElseThrow();
        System.out.println("helper identity=" + helperPid + "/" + helperBirth);
        helper.toHandle().onExit().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && row(rows, "job-process-exit", helperPid) == null) Thread.sleep(20);
        var witnessed = row(rows, "job-process-exit", helperPid);
        var nativeStart = row(rows, "job-process-start", helperPid);
        System.out.println("native.start=" + nativeStart);
        boolean exactCreator = nativeStart != null && Long.valueOf(self.pid()).equals(nativeStart.get("parentPid"))
            && os.creationFileTime(self.pid()).orElseThrow().equals(nativeStart.get("parentCreationFileTime"));
        System.out.println("native.creator.exact=" + exactCreator);
        if (!exactCreator) throw new AssertionError("native creator capture is required to isolate the owner/window precedence race");
        System.out.println("window.open.through.witness=true");
        try {
          Class<?> registry=Class.forName("io.justsearch.app.api.runtime.child.ProcessIntents");
          var console=(Optional<?>)registry.getMethod("resolveConsole",long.class,String.class,boolean.class).invoke(null,helperPid,helperBirth,true);
          Object value=console.orElseThrow();
          System.out.println("console.final=" + value.getClass().getMethod("exitClass").invoke(value));
        } catch (ClassNotFoundException beforeApi) { System.out.println("console.final=unavailable-before-API"); }
        // This await happens after the native witness's final class is fixed. It may wait for the delayed sink.
        helper.waitFor();
        var receipt = row(rows, "helper-exit", helperPid);
        System.out.println("helper.final=" + (receipt == null ? "missing" : receipt.get("exitClass")));
        System.out.println("helper.receipt=" + receipt);
        System.out.println("witness.final=" + (witnessed == null ? "missing" : witnessed.get("exitClass")));
        System.out.println("witness.receipt=" + witnessed);
        System.out.println("exact.helper.ended=" + !helper.isAlive());
        var hostStart = rows.stream().filter(r -> "job-process-start".equals(r.get("type")) && Long.valueOf(helperPid).equals(r.get("parentPid")) && helperBirth.equals(r.get("parentCreationFileTime")) && String.valueOf(r.get("executable")).toLowerCase().endsWith("conhost.exe")).findFirst().orElse(null);
        System.out.println("actual.conhost.native.start=" + hostStart);
        if (hostStart != null) {
          long hostPid = ((Number)hostStart.get("pid")).longValue();
          long hostDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (row(rows, "job-process-exit", hostPid) == null && System.nanoTime() < hostDeadline) Thread.sleep(20);
          var hostExit = row(rows,"job-process-exit",hostPid);
          System.out.println("actual.conhost.native.exit=" + hostExit);
          System.out.println("actual.conhost.final=" + (hostExit == null ? "missing" : hostExit.get("exitClass")));
        }
        System.out.println("actual.conhost.starts=" + rows.stream().filter(r -> "job-process-start".equals(r.get("type")) && String.valueOf(r.get("executable")).toLowerCase().endsWith("conhost.exe")).count());
      }
      job.finishProcessRecording(Duration.ofSeconds(3));
    }
    private static Map<String,Object> row(List<Map<String,Object>> rows, String type, long pid) {
      return rows.stream().filter(r -> type.equals(r.get("type")) && Long.valueOf(pid).equals(r.get("pid")))
          .findFirst().orElse(null);
    }
  }
}
