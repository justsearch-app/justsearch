/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.runtime.child.ProcessIntents;
import io.justsearch.app.api.runtime.child.ProcessIntents.Resolution;
import io.justsearch.app.api.runtime.ledger.*;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the witness's existing elapsed-time seam, without sleeps or scheduler ordering. Only
 * accepted resolutions are appended, through the real writer and reader: a written class is final.
 * Reflection keeps exactly the same check executable on 8043f327f, without a production overlay.
 */
final class ConsoleRegistrationRaceTest {
  private static final AtomicLong PIDS = new AtomicLong(8_100_000);
  private static final String BIRTH = "134365786013302991";
  private static final Instant EXIT = Instant.parse("2026-10-15T22:56:41.362779Z");
  private static final int CRASH = (int) 0xC0000005L;
  @TempDir Path dir;

  @Test void consoleWaitsForClientRegistrationBeforeWritingItsFinalClass() throws Exception {
    long client = PIDS.incrementAndGet(), host = PIDS.incrementAndGet();
    Object child = held("tesseract.exe", null, null);
    Object console = held("conhost.exe", client, BIRTH);
    var writer = writer();

    // The native exit wins the race against both registration and the awaited owner receipt.
    record(writer, client, resolve(client, child, 0, 0), EXIT);
    record(writer, host, resolve(host, console, 0, 0), EXIT.plusMillis(3));
    ProcessIntents.owned(client, BIRTH);
    ProcessIntents.outcome(client, BIRTH, "REQUESTED", "Tesseract: awaited completion");
    record(writer, client, resolve(client, child, 0, 40_000_000), EXIT);
    if (exits(host).isEmpty()) {
      record(writer, host, resolve(host, console, 0, 40_000_000), EXIT.plusMillis(3));
    }

    assertEquals("REQUESTED", onlyExit(client).text("exitClass").orElseThrow());
    assertEquals(onlyExit(client).text("exitClass"), onlyExit(host).text("exitClass"),
        "the durable console record must follow the client's final REQUESTED class");
    assertEquals(onlyExit(client).text("reason"), onlyExit(host).text("reason"));
  }

  @Test void consoleCannotPublishWhileRegisteredClientStillAwaitsItsOwner() throws Exception {
    long client = PIDS.incrementAndGet(), host = PIDS.incrementAndGet();
    Object child = held("tesseract.exe", null, null);
    Object console = held("conhost.exe", client, BIRTH);
    var writer = writer();
    record(writer, client, resolve(client, child, 0, 0), EXIT);
    ProcessIntents.owned(client, BIRTH);
    record(writer, host, resolve(host, console, 0, 0), EXIT.plusMillis(3));
    assertNull(resolve(client, child, 0, WindowsJobWitness.REGISTRATION_WAIT_NANOS));
    // Registration is no longer the reason to wait. The owner's existing bounded wait applies.
    assertNull(resolve(host, console, 0, WindowsJobWitness.REGISTRATION_WAIT_NANOS));
    ProcessIntents.outcome(client, BIRTH, "REQUESTED", "Tesseract: awaited completion");
    record(writer, client, resolve(client, child, 0, WindowsJobWitness.OWNER_WAIT_NANOS - 1), EXIT);
    if (exits(host).isEmpty()) {
      record(writer, host, resolve(host, console, 0, WindowsJobWitness.OWNER_WAIT_NANOS - 1), EXIT.plusMillis(3));
    }
    assertEquals("REQUESTED", onlyExit(client).text("exitClass").orElseThrow());
    assertEquals("REQUESTED", onlyExit(host).text("exitClass").orElseThrow());
  }

  @Test void sharedOwnerReceiptBeforeConsoleRetryStillSuppliesItsFinalClass() throws Exception {
    long creator = PIDS.incrementAndGet(), client = PIDS.incrementAndGet(), host = PIDS.incrementAndGet();
    String creatorBirth = "134365786003302991";
    Path receipts = dir.resolve("shared-owner");
    ProcessIntents.readWindowsFrom(receipts);
    try {
      Object child = held("tesseract.exe", creator, creatorBirth);
      Object console = held("conhost.exe", client, BIRTH);
      var writer = writer();
      record(writer, client, resolve(client, child, 0, 0), EXIT);
      // Another JVM's receipt is now durable, before the console is classified. It has not yet
      // been read by the client's next witness sweep; a cached tentative class cannot win.
      var owner = ExitLedgerWriter.rotating(receipts, LedgerFamily.CHILDREN,
          new LedgerWriterId(LedgerWriterId.Role.ENGINE, creator, EXIT.minusSeconds(1)), Clock.systemUTC());
      assertTrue(owner.append(LedgerRecordType.HELPER_EXIT, Map.of(
          "pid", client, "creationFileTime", BIRTH, "creatorPid", creator,
          "creatorCreationFileTime", creatorBirth, "exitClass", "REQUESTED",
          "reason", "Tesseract: awaited language availability")));
      record(writer, host, resolve(host, console, 0, 15_000_000), EXIT.plusMillis(3));
      record(writer, client, resolve(client, child, 0, 40_000_000), EXIT);
      if (exits(host).isEmpty()) {
        record(writer, host, resolve(host, console, 0, 40_000_000), EXIT.plusMillis(3));
      }
      assertEquals("REQUESTED", onlyExit(client).text("exitClass").orElseThrow());
      assertEquals(onlyExit(client).text("exitClass"), onlyExit(host).text("exitClass"),
          "the shared owner's receipt must reach the console through the final client class");
      assertEquals(onlyExit(client).text("reason"), onlyExit(host).text("reason"));
    } finally {
      ProcessIntents.readWindowsFrom(null);
    }
  }

  @Test void noIntentClientAndConsoleRemainUnexpectedAtTheExistingRegistrationBound() throws Exception {
    long client = PIDS.incrementAndGet(), host = PIDS.incrementAndGet();
    Object child = held("unknown.exe", null, null);
    Object console = held("conhost.exe", client, BIRTH);
    assertNull(resolve(client, child, 0, 0));
    long bound = WindowsJobWitness.REGISTRATION_WAIT_NANOS;
    var writer = writer();
    record(writer, client, resolve(client, child, 0, bound), EXIT);
    record(writer, host, resolve(host, console, 0, bound), EXIT.plusMillis(3));
    assertEquals("UNEXPECTED", onlyExit(client).text("exitClass").orElseThrow());
    assertEquals("UNEXPECTED", onlyExit(host).text("exitClass").orElseThrow());
  }

  @Test void realCrashDuringAnIntendedStopStillMakesTheConsoleUnexpected() throws Exception {
    long client = PIDS.incrementAndGet(), host = PIDS.incrementAndGet();
    ProcessIntents.owned(client, BIRTH);
    ProcessIntents.intent(client, BIRTH, "QUIT");
    var writer = writer();
    record(writer, client, resolve(client, held("helper.exe", null, null), CRASH,
        WindowsJobWitness.OWNER_WAIT_NANOS), EXIT);
    record(writer, host, resolve(host, held("conhost.exe", client, BIRTH), 0,
        WindowsJobWitness.OWNER_WAIT_NANOS), EXIT.plusMillis(3));
    assertEquals("UNEXPECTED", onlyExit(client).text("exitClass").orElseThrow());
    assertEquals("UNEXPECTED", onlyExit(host).text("exitClass").orElseThrow());
    assertTrue(onlyExit(host).text("reason").orElseThrow().contains("crash exit code"));
  }

  @Test void consoleCrashIsUnexpectedEvenWhenItsClientCompletedNormally() throws Exception {
    long client = PIDS.incrementAndGet(), host = PIDS.incrementAndGet();
    ProcessIntents.outcome(client, BIRTH, "REQUESTED", "awaited completion");
    var writer = writer();
    record(writer, client, resolve(client, held("helper.exe", null, null), 0, 0), EXIT);
    record(writer, host, resolve(host, held("conhost.exe", client, BIRTH), CRASH, 0), EXIT.plusMillis(3));
    assertEquals("REQUESTED", onlyExit(client).text("exitClass").orElseThrow());
    assertEquals("UNEXPECTED", onlyExit(host).text("exitClass").orElseThrow());
  }

  @Test void lateOwnerReceiptCannotEraseTheClientsFinalNativeCrashClass() throws Exception {
    long client = PIDS.incrementAndGet(), host = PIDS.incrementAndGet();
    ProcessIntents.owned(client, BIRTH);
    ProcessIntents.intent(client, BIRTH, "QUIT");
    var writer = writer();
    record(writer, client, resolve(client, held("helper.exe", null, null), CRASH,
        WindowsJobWitness.OWNER_WAIT_NANOS), EXIT);
    ProcessIntents.outcome(client, BIRTH, "REQUESTED", "QUIT");
    record(writer, host, resolve(host, held("conhost.exe", client, BIRTH), 0, 0), EXIT.plusMillis(3));
    assertEquals("UNEXPECTED", onlyExit(client).text("exitClass").orElseThrow());
    assertEquals("UNEXPECTED", onlyExit(host).text("exitClass").orElseThrow(),
        "the native crash class is final even if an owner later publishes an intended outcome");
  }

  @Test void consoleVisitedFirstAtTheOwnerDeadlineCannotHideItsClientsCrash() throws Exception {
    long client = PIDS.incrementAndGet(), host = PIDS.incrementAndGet();
    ProcessIntents.owned(client, BIRTH);
    ProcessIntents.intent(client, BIRTH, "QUIT");
    Object child = held("helper.exe", null, null);
    Object console = held("conhost.exe", client, BIRTH);
    var writer = writer();
    record(writer, client, resolve(client, child, CRASH, 0), EXIT);
    record(writer, host, resolve(host, console, 0, 0), EXIT.plusMillis(3));
    // A map sweep may visit the host first when both existing owner deadlines have elapsed.
    if (exits(host).isEmpty()) {
      record(writer, host, resolve(host, console, 0, WindowsJobWitness.OWNER_WAIT_NANOS), EXIT.plusMillis(3));
    }
    if (exits(client).isEmpty()) {
      record(writer, client, resolve(client, child, CRASH, WindowsJobWitness.OWNER_WAIT_NANOS), EXIT);
    }
    assertEquals("UNEXPECTED", onlyExit(client).text("exitClass").orElseThrow());
    assertEquals("UNEXPECTED", onlyExit(host).text("exitClass").orElseThrow(),
        "a console visited first cannot turn a native crash into an intended stop");
  }

  @Test void unknownNativeExitTimeRemainsUnknownInTheLedger() throws Exception {
    long client = PIDS.incrementAndGet();
    Method instant = WindowsJobWitness.class.getDeclaredMethod("instantOf", long.class);
    instant.setAccessible(true);
    Instant unknown = (Instant) instant.invoke(null, 0L);
    assertNull(unknown, "a failed native time query must not fabricate an exit instant");
    record(writer(), client, resolve(client, held("helper.exe", null, null), 0,
        WindowsJobWitness.REGISTRATION_WAIT_NANOS), unknown);
    assertTrue(onlyExit(client).field("exitedAt").isNull());
    assertEquals(Optional.empty(),
        LedgerTimestamps.known(onlyExit(client).text("exitedAt").orElse(null)),
        "an unknown native exit instant still reads as unknown");
  }

  private ExitLedgerWriter writer() {
    return ExitLedgerWriter.rotating(dir, LedgerFamily.CHILDREN,
        LedgerWriterId.currentProcess(LedgerWriterId.Role.ENGINE, EXIT), Clock.systemUTC());
  }

  private void record(ExitLedgerWriter writer, long pid, Resolution resolution, Instant exitedAt) {
    if (resolution == null) return; // The production writer likewise retains deferred exits.
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("witnessId", "race-" + pid);
    fields.put("pid", pid);
    fields.put("creationFileTime", BIRTH);
    fields.put("exitClass", resolution.exitClass());
    fields.put("reason", resolution.reason());
    fields.put("intentBasis", resolution.basis());
    fields.put("exitedAt", exitedAt);
    assertTrue(writer.append(LedgerRecordType.JOB_PROCESS_EXIT, fields));
  }

  private List<LedgerRecord> exits(long pid) throws Exception {
    return ExitLedgerReader.read(dir, LedgerFamily.CHILDREN).records().stream()
        .filter(r -> r.field("pid").asLong() == pid).toList();
  }

  private LedgerRecord onlyExit(long pid) throws Exception {
    var rows = exits(pid);
    assertEquals(1, rows.size(), "every accepted exit is written once, without corrections");
    return rows.getFirst();
  }

  private static Object held(String executable, Long parentPid, String parentBirth) throws Exception {
    Class<?> type = Class.forName(WindowsJobWitness.class.getName() + "$Held");
    Constructor<?> constructor = type.getDeclaredConstructor(String.class, MemorySegment.class,
        String.class, String.class, boolean.class, Long.class, String.class);
    constructor.setAccessible(true);
    return constructor.newInstance("race", MemorySegment.NULL, BIRTH, "C:\\test\\" + executable,
        false, parentPid, parentBirth);
  }

  private static Resolution resolve(long pid, Object held, Integer code, long elapsedNanos) throws Exception {
    Method method = WindowsJobWitness.class.getDeclaredMethod("resolve", long.class, held.getClass(),
        Integer.class, Instant.class, long.class);
    method.setAccessible(true);
    return (Resolution) method.invoke(null, pid, held, code, EXIT, elapsedNanos);
  }
}
