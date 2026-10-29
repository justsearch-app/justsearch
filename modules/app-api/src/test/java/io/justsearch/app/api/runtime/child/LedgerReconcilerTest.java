/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ledger.ExitLedgerReader;
import io.justsearch.app.api.runtime.ledger.ExitLedgerWriter;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerRecord;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.app.api.runtime.ledger.LedgerWriterId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Spec B §8 T10: the next owner closes what an earlier Engine left open, and never kills. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
final class LedgerReconcilerTest {
  private static final Map<String, Object> OWNER_A =
      Map.of("pid", 4242L, "creationFileTime", "134044416020000000", "instanceId", "inst-a");

  @TempDir Path dataDir;
  private final List<Process> processes = new ArrayList<>();

  @AfterEach
  void killFakes() {
    for (Process process : processes) process.destroyForcibly();
  }

  @Test
  @DisplayName("T10: gone → OWNER_DIED; alive and matching → child-survived, untouched;"
      + " start unreadable → UNKNOWN; a second boot carries the survivor")
  void closesOpenRecordsWithoutKilling() throws Exception {
    Path ledgerDir = LedgerFamily.directory(dataDir);
    ProcessTerminator terminator = ProcessTerminator.forCurrentOs();
    Process dead = fake("exit:0:0");
    assertTrue(dead.waitFor(60, TimeUnit.SECONDS));
    Process alive = fake("sleep");
    Process unreadable = fake("sleep");

    // Incarnation A: five children, as its control wrote them, then A dies.
    ExitLedgerWriter a = writer(ledgerDir, 4242L, "2026-10-06T08:00:00Z");
    spawnAndStart(a, "c-gone", dead.pid(), terminator.creationFileTime(dead.pid()).orElse(null),
        null, "C:/gone/java.exe");
    spawnAndStart(a, "c-alive", alive.pid(),
        terminator.creationFileTime(alive.pid()).orElse(null),
        alive.info().startInstant().orElseThrow(), executable(alive));
    spawnAndStart(a, "c-unreadable", unreadable.pid(), null, null, executable(unreadable));
    spawnAndStart(a, "c-intent", dead.pid(), null,
        dead.info().startInstant().orElse(Instant.parse("2026-10-06T08:00:01Z")),
        "C:/gone/java.exe");
    a.append(LedgerRecordType.STOP_INTENT, fields("childId", "c-intent", "intentId", "s-1",
        "reason", "QUIT", "detail", null, "mode", "NOW", "requestedAt", Instant.now(),
        "requestedBy", "engine-shutdown"));
    spawnAndStart(a, "c-closed", dead.pid(), null, null, "C:/gone/java.exe");
    a.append(LedgerRecordType.CHILD_EXIT, exit("c-closed"));
    a.append(LedgerRecordType.CHILD_SPAWN, spawn("c-spawn-only"));

    // Incarnation B boots: reconcile under app.lock, before its session starts.
    ExitLedgerWriter b = writer(ledgerDir, 5252L, "2026-10-06T09:00:00Z");
    LedgerReconciler.Result first =
        new LedgerReconciler(ledgerDir, LedgerSink.of(b), LedgerReconciler.osIdentity(terminator))
            .reconcile();

    assertEquals(new LedgerReconciler.Result(2, 2, 1, 0), first);
    Map<String, LedgerRecord> byChild = new LinkedHashMap<>();
    for (LedgerRecord r : read(ledgerDir)) {
      if (r.writer().pid() == 5252L) byChild.put(r.text("childId").orElseThrow(), r);
    }
    assertEquals(List.of("c-gone", "c-alive", "c-unreadable", "c-intent", "c-spawn-only"),
        List.copyOf(byChild.keySet()));
    assertOwnerDied(byChild.get("c-gone"), "OPEN_START", "GONE", "OWNER_DIED");
    assertEquals(4242L, byChild.get("c-gone").field("previousOwner").path("pid").asLong());
    assertEquals("inst-a",
        byChild.get("c-gone").field("previousOwner").path("instanceId").asString());
    LedgerRecord survived = byChild.get("c-alive");
    assertEquals(LedgerRecordType.CHILD_SURVIVED, survived.type());
    assertEquals(Optional.of("owner_died_alive"), survived.text("basis"));
    assertTrue(alive.isAlive(), "the reconciler never kills");
    assertOwnerDied(byChild.get("c-unreadable"), "OPEN_START", "IDENTITY_UNKNOWN", "UNKNOWN");
    assertTrue(unreadable.isAlive());
    assertOwnerDied(byChild.get("c-intent"), "OPEN_INTENT", "GONE", "OWNER_DIED");
    assertOwnerDied(byChild.get("c-spawn-only"), "OPEN_SPAWN", "IDENTITY_UNKNOWN", "UNKNOWN");

    // Incarnation C: only the live survivor stays open and is carried forward.
    long lines = Files.readAllLines(LedgerFamily.CHILDREN.current(ledgerDir)).size();
    ExitLedgerWriter c = writer(ledgerDir, 6262L, "2026-10-06T10:00:00Z");
    LedgerReconciler.Result second =
        new LedgerReconciler(ledgerDir, LedgerSink.of(c), LedgerReconciler.osIdentity(terminator))
            .reconcile();
    assertEquals(new LedgerReconciler.Result(0, 0, 1, 0), second);
    assertEquals(lines + 1, Files.readAllLines(LedgerFamily.CHILDREN.current(ledgerDir)).size());
    assertTrue(alive.isAlive());

    for (String line : Files.readAllLines(LedgerFamily.CHILDREN.current(ledgerDir))) {
      var errors = ChildControlHarness.schema()
          .validate(new tools.jackson.databind.ObjectMapper().readTree(line));
      assertTrue(errors.isEmpty(), () -> line + " -> " + errors);
    }
  }

  @Test
  @DisplayName("T10: a reused PID (same pid, other start) is GONE, not the recorded child")
  void reusedPidIsGone() throws Exception {
    Process live = fake("sleep");
    LedgerReconciler.OsIdentity os =
        LedgerReconciler.osIdentity(ProcessTerminator.forCurrentOs());
    Instant start = live.info().startInstant().orElseThrow();

    assertEquals(LedgerReconciler.OsCheck.GONE, os.check(new LedgerReconciler.RecordedIdentity(
        live.pid(), Optional.empty(), Optional.of(start.minusSeconds(60)), executable(live),
        LinuxProcessIdentity.read(live.pid()).map(identity -> new LinuxProcessIdentity(identity.kind(), identity.pid(), identity.bootId(),
            new java.math.BigInteger(identity.startTicks()).add(java.math.BigInteger.ONE).toString())))));
    assertEquals(LedgerReconciler.OsCheck.ALIVE_MATCH, os.check(
        new LedgerReconciler.RecordedIdentity(
            live.pid(), Optional.empty(), Optional.of(start), executable(live), LinuxProcessIdentity.read(live.pid()))));
    if (java.io.File.separatorChar == '\\') {
      // On Windows the creation FILETIME decides, ahead of the JDK start time.
      assertEquals(LedgerReconciler.OsCheck.GONE, os.check(
          new LedgerReconciler.RecordedIdentity(live.pid(), Optional.of("100000000000000000"),
              Optional.of(start), executable(live))));
    }
  }

  private static void assertOwnerDied(
      LedgerRecord record, String basis, String osCheck, String exitClass) {
    assertEquals(LedgerRecordType.OWNER_DIED, record.type());
    assertEquals(Optional.of(basis), record.text("basis"));
    assertEquals(Optional.of(osCheck), record.text("osCheck"));
    assertEquals(Optional.of(exitClass), record.text("exitClass"));
  }

  private Process fake(String mode) throws IOException {
    Process process = new ProcessBuilder(FakeChild.argv(mode)).start();
    processes.add(process);
    return process;
  }

  private static String executable(Process process) {
    return ManagedChild.normalizePath(Path.of(process.info().command().orElseThrow()));
  }

  private static List<LedgerRecord> read(Path ledgerDir) throws IOException {
    return ExitLedgerReader.read(ledgerDir, LedgerFamily.CHILDREN).records();
  }

  private static ExitLedgerWriter writer(Path dir, long pid, String startedAt) {
    return ExitLedgerWriter.rotating(dir, LedgerFamily.CHILDREN,
        new LedgerWriterId(LedgerWriterId.Role.ENGINE, pid, Instant.parse(startedAt)),
        Clock.systemUTC());
  }

  private static void spawnAndStart(ExitLedgerWriter writer, String childId, long pid,
      String creationFileTime, Instant startedAt, String executable) {
    writer.append(LedgerRecordType.CHILD_SPAWN, spawn(childId));
    writer.append(LedgerRecordType.CHILD_START, fields("childId", childId, "pid", pid,
        "processStartedAt", startedAt, "creationFileTime", creationFileTime,
        "executable", executable, "argvHash", "a".repeat(64), "contained", null,
        "gpuDevice", null, "processIdentity", startedAt != null && io.justsearch.configuration.PlatformPaths.isLinux()
            ? LinuxProcessIdentity.read(pid) : Optional.empty()));
  }

  private static Map<String, Object> spawn(String childId) {
    return fields("childId", childId, "kind", "EXTRACTION", "label", "fake", "owner", OWNER_A,
        "gpu", false);
  }

  private static Map<String, Object> exit(String childId) {
    return fields("childId", childId, "intentId", null, "exitClass", "UNEXPECTED",
        "reason", null, "detail", null, "mode", null, "code", 0, "codeName", "OK",
        "codeDistinctive", true, "exitedAt", null, "observedAt", Instant.now(),
        "terminateCalledAt", null, "terminateResult", "NOT_CALLED", "cooperativeAt", null,
        "forced", false, "phase", "RUNNING");
  }

  private static Map<String, Object> fields(Object... pairs) {
    Map<String, Object> fields = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) fields.put((String) pairs[i], pairs[i + 1]);
    return fields;
  }
}
