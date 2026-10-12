/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Actual Windows processes; reflection lets the same assertions run on the before-state. */
@EnabledOnOs(OS.WINDOWS)
final class OwnedHelperReceiptTest {
  private static final List<Map<String, Object>> RECORDS = new ArrayList<>();
  private static final java.util.concurrent.atomic.AtomicReference<Process> CRASH_WAIT =
      new java.util.concurrent.atomic.AtomicReference<>();

  private static Process start(String command, String purpose) throws Exception {
    ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
        "-Command", command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD);
    LedgerSink sink = (type, fields) -> {
      synchronized (RECORDS) {
        Map<String, Object> record = new LinkedHashMap<>(fields);
        record.put("type", type.token()); RECORDS.add(record);
      }
      // Hold the durable-intent boundary until the real child crashes, before termination acts.
      if (type.token().equals("helper-intent") && String.valueOf(fields.get("reason"))
          .contains("crash during intended stop")) {
        try { CRASH_WAIT.get().waitFor(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      }
      return true;
    };
    try {
      Class<?> helper = Class.forName("io.justsearch.app.api.runtime.child.OwnedHelper");
      helper.getMethod("recordTo", LedgerSink.class, EngineIdentity.class).invoke(null, sink,
          new EngineIdentity(ProcessHandle.current().pid(), ProcessTerminator.forCurrentOs()
              .creationFileTime(ProcessHandle.current().pid()), "helper-test"));
      return (Process) helper.getMethod("start", ProcessBuilder.class, String.class).invoke(null, builder, purpose);
    } catch (ClassNotFoundException before) { return builder.start(); }
  }

  private static Map<String, Object> exit(Process p) {
    synchronized (RECORDS) {
      return RECORDS.stream().filter(r -> "helper-exit".equals(r.get("type"))
          && Long.valueOf(p.pid()).equals(r.get("pid"))).reduce((a,b) -> b).orElse(Map.of());
    }
  }

  @Test void awaitedNonzeroProbeHasItsPurpose() throws Exception {
    Process p = start("exit 1", "Tesseract availability probe");
    String birth = ProcessTerminator.forCurrentOs().creationFileTime(p.pid()).orElseThrow();
    assertTrue(p.waitFor(10, TimeUnit.SECONDS));
    assertEquals(1, p.exitValue());
    assertEquals("REQUESTED", exit(p).get("exitClass"), "handled code 1 is a normal probe outcome");
    assertEquals(birth, exit(p).get("creationFileTime"));
    assertTrue(String.valueOf(exit(p).get("reason")).contains("Tesseract availability"));
  }

  @Test void interruptionWritesIntentBeforeNativeTermination() throws Exception {
    Process p = start("Start-Sleep -Seconds 2", "disk encryption probe");
    String birth = ProcessTerminator.forCurrentOs().creationFileTime(p.pid()).orElseThrow();
    Thread.currentThread().interrupt();
    try { assertThrows(InterruptedException.class, () -> p.waitFor(10, TimeUnit.SECONDS)); }
    finally { Thread.interrupted(); }
    p.destroyForcibly(); // DiskEncryptionProbe's catch/finally repeats cancellation during native exit.
    // The before-state process ends naturally; the repair ends its held identity.
    assertTrue(p.waitFor(10, TimeUnit.SECONDS));
    assertEquals("REQUESTED", exit(p).get("exitClass"));
    assertEquals(birth, exit(p).get("creationFileTime"));
    assertTrue(String.valueOf(exit(p).get("reason")).contains("interrupted"));
    synchronized (RECORDS) {
      var own = RECORDS.stream().filter(r -> Long.valueOf(p.pid()).equals(r.get("pid"))).toList();
      assertTrue(own.stream().anyMatch(r -> "helper-intent".equals(r.get("type"))));
      assertEquals("helper-start", own.getFirst().get("type"));
      assertEquals("helper-intent", own.get(1).get("type"));
    }
  }

  @Test void awaitedCrashStatusRemainsUnexpected() throws Exception {
    Process p = start("[Environment]::Exit(-1073741819)", "Tesseract availability probe");
    String birth = ProcessTerminator.forCurrentOs().creationFileTime(p.pid()).orElseThrow();
    assertTrue(p.waitFor(10, TimeUnit.SECONDS));
    assertEquals(-1073741819, p.exitValue());
    assertEquals("UNEXPECTED", exit(p).get("exitClass"));
    assertEquals(birth, exit(p).get("creationFileTime"));
  }

  @Test void realCrashAfterIntentBeforeTerminateStaysUnexpected() throws Exception {
    Process p = start("Start-Sleep -Milliseconds 200; [Environment]::Exit(-1073741819)",
        "crash during intended stop");
    String birth = ProcessTerminator.forCurrentOs().creationFileTime(p.pid()).orElseThrow();
    CRASH_WAIT.set(p);
    try { p.destroyForcibly(); assertTrue(p.waitFor(15, TimeUnit.SECONDS)); }
    finally { CRASH_WAIT.set(null); }
    assertEquals(-1073741819, p.exitValue(), "the child's actual crash wins the stop race");
    assertEquals("UNEXPECTED", exit(p).get("exitClass"));
    assertEquals(birth, exit(p).get("creationFileTime"));
  }

  @Test void cooperativeInputCloseRecordsIntentBeforeEof() throws Exception {
    Process p = start("[Console]::In.ReadToEnd() | Out-Null; exit 0", "MCP stdio transport");
    String birth = ProcessTerminator.forCurrentOs().creationFileTime(p.pid()).orElseThrow();
    try {
      Class.forName("io.justsearch.app.api.runtime.child.OwnedHelper")
          .getMethod("expectInputClose", Process.class, String.class).invoke(null, p, "transport close");
    } catch (ClassNotFoundException before) { /* assertion fails for absent intent/receipt */ }
    p.getOutputStream().close();
    assertTrue(p.waitFor(15, TimeUnit.SECONDS));
    assertEquals("REQUESTED", exit(p).get("exitClass"));
    assertEquals(birth, exit(p).get("creationFileTime"));
    assertTrue(String.valueOf(exit(p).get("reason")).contains("transport close"));
  }
}
