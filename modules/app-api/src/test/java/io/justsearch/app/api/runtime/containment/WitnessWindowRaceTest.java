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
import org.junit.jupiter.api.io.TempDir;

/**
 * Task b6d536 b101: a launch inside a valid owner window, with the window owner as its native
 * creator, is REQUESTED with the window's reason at once. The witness never waits on the owner's
 * exit receipt, here delayed 4.5 s in publication, and never fixes the exit as UNEXPECTED meanwhile.
 */
final class WitnessWindowRaceTest {
  @TempDir Path dir;

  @Test void localHelperExitReceiptDelayDoesNotOverrideItsLiveWindow() throws Exception {
    Process p = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
        Probe.class.getName(), dir.toAbsolutePath().toString()).redirectErrorStream(true).start();
    String birth = ProcessTerminator.forCurrentOs().creationFileTime(p.pid()).orElseThrow();
    System.out.println("probe identity=" + p.pid() + "/" + birth);
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    System.out.print(out);
    assertTrue(p.waitFor(30, TimeUnit.SECONDS), out);
    assertEquals(0, p.exitValue(), out);
    assertFalse(p.isAlive(), "exact original Process probe has ended");
    assertTrue(out.contains("window.open.through.witness=true"), out);
    assertTrue(out.contains("helper.final=REQUESTED"), out);
    assertTrue(out.contains("witness.final=REQUESTED"), out);
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
      var owner = new EngineSelfIntentWriter.EngineIdentity(self.pid(), os.creationFileTime(self.pid()), "window-race");
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
            .invoke(null, "tesseract.exe", "OCR valid live window", Duration.ofMinutes(1));
        shared = (AutoCloseable) window;
        lease = (AutoCloseable) window.getClass().getMethod("acquire").invoke(window);
      } catch (NoSuchMethodException beforeApi) {
        shared = OwnedHelper.window("tesseract.exe", "OCR valid live window", Duration.ofMinutes(1));
        lease = () -> {};
      }
      try (var ignored = shared; var unused = lease) {
        Process helper = OwnedHelper.start(new ProcessBuilder("F:/scoop/apps/tesseract/current/tesseract.exe", "--version")
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
        // This await happens after the native witness's final class is fixed. It may wait for the delayed sink.
        helper.waitFor();
        var receipt = row(rows, "helper-exit", helperPid);
        System.out.println("helper.final=" + (receipt == null ? "missing" : receipt.get("exitClass")));
        System.out.println("helper.receipt=" + receipt);
        System.out.println("witness.final=" + (witnessed == null ? "missing" : witnessed.get("exitClass")));
        System.out.println("witness.receipt=" + witnessed);
        System.out.println("exact.helper.ended=" + !helper.isAlive());
      }
      job.finishProcessRecording(Duration.ofSeconds(3));
    }
    private static Map<String,Object> row(List<Map<String,Object>> rows, String type, long pid) {
      return rows.stream().filter(r -> type.equals(r.get("type")) && Long.valueOf(pid).equals(r.get("pid")))
          .findFirst().orElse(null);
    }
  }
}
