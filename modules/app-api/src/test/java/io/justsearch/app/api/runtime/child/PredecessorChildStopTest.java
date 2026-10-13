/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ledger.*;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the boot stop transaction with a held-handle double; never signals a real process. */
class PredecessorChildStopTest {
  @TempDir Path data;
  private final ProcessHandle process = ProcessHandle.current();
  private final Instant start = process.info().startInstant().orElseThrow();
  private final String birth = Long.toString((start.toEpochMilli() + 11_644_473_600_000L) * 10_000);
  private final String executable = ManagedChild.normalizePath(Path.of(process.info().command().orElseThrow()));
  private final ManagedChild child = new ManagedChild("fixture", ManagedChild.Kind.EXTRACTION,
      process.pid(), start.toString(), executable, "stdio", null, null, "a".repeat(64));
  private Optional<String> heldExecutable = Optional.of(executable);
  private int stops;
  private boolean closed;
  private boolean jdkIdentity;

  private ProcessTerminator terminator(String heldBirth) {
    return new ProcessTerminator() {
      public OsProcess open(Process p) { throw new AssertionError("no spawn expected"); }
      public OsProcess open(ProcessHandle p) {
        assertEquals(process.pid(), p.pid());
        return new OsProcess() {
          public Optional<String> creationFileTime() { return Optional.ofNullable(heldBirth); }
          public Optional<String> executable() { return heldExecutable; }
          public boolean matchesJdkIdentity(ManagedChild expected) { return jdkIdentity && child.equals(expected); }
          public TerminateResult terminate(int code) { stops++; return TerminateResult.OK; }
          public boolean awaitExit(Duration max) { return stops > 0; }
          public OptionalInt exitCode() { return OptionalInt.of(TerminateCodes.JS_CHILD_STOP); }
          public Optional<Instant> exitedAt() { return Optional.empty(); }
          public void close() { closed = true; }
        };
      }
      public int ourCode() { return TerminateCodes.JS_CHILD_STOP; }
      public boolean codeDistinctive() { return true; }
      public Optional<String> creationFileTime(long pid) { return Optional.of(birth); }
    };
  }

  private ExitLedgerWriter writer() {
    return ExitLedgerWriter.rotating(LedgerFamily.directory(data), LedgerFamily.CHILDREN,
        LedgerWriterId.currentProcess(LedgerWriterId.Role.ENGINE, Instant.now()), Clock.systemUTC());
  }

  private PredecessorChildStop stop(ExitLedgerWriter writer, String heldBirth) {
    return new PredecessorChildStop(LedgerSink.of(writer), terminator(heldBirth), Clock.systemUTC(),
        new EngineIdentity(process.pid(), Optional.of(birth), "successor"));
  }

  @Test void manifestOnlyFallbackRejectsDifferentHeldBirth() throws Exception {
    var writer = writer();
    assertFalse(stop(writer, Long.toString(Long.parseLong(birth) + 10_000)).stop(data, child),
        "a replacement handle cannot authorize the manifest's stop");
    assertEquals(0, stops);
    assertTrue(closed);
    assertTrue(ExitLedgerReader.read(LedgerFamily.directory(data), LedgerFamily.CHILDREN).records().isEmpty(),
        "refusal must not fabricate ownership or intent");
  }

  @Test void manifestOnlyFallbackRejectsUnreadableHeldBirth() throws Exception {
    assertFalse(stop(writer(), null).stop(data, child));
    assertEquals(0, stops);
    assertTrue(closed);
  }

  @Test void manifestOnlyFallbackRejectsDifferentHeldExecutable() throws Exception {
    heldExecutable = Optional.of(Path.of("replacement.exe").toAbsolutePath().toString());
    assertFalse(stop(writer(), birth).stop(data, child));
    assertEquals(0, stops);
    assertTrue(closed);
  }

  @Test void unknownOwnerDeathDoesNotDisableIdentityProvenCleanup() throws Exception {
    var writer = writer();
    writer.append(LedgerRecordType.CHILD_START, Map.of("childId", "fixture", "pid", process.pid(),
        "creationFileTime", birth, "executable", executable, "processStartedAt", start));
    var reconciliation = new LedgerReconciler(LedgerFamily.directory(data), LedgerSink.of(writer),
        ignored -> LedgerReconciler.OsCheck.IDENTITY_UNKNOWN).reconcile();
    assertEquals(1, reconciliation.unknown(), "the boot accounting pass could not inspect the identity");
    assertTrue(stop(writer, birth).stop(data, child), "fresh held identity allows cleanup after an unknown observation");
    assertEquals(1, stops);
    var records = ExitLedgerReader.read(LedgerFamily.directory(data), LedgerFamily.CHILDREN).records();
    assertEquals(1, records.stream().filter(r -> r.type() == LedgerRecordType.STOP_INTENT).count());
    assertEquals(1, records.stream().filter(r -> r.type() == LedgerRecordType.CHILD_EXIT).count());
    stop(writer, birth).stop(data, child);
    assertEquals(1, stops, "confirmed terminal receipt prevents a duplicate stop");
    assertEquals(1, ExitLedgerReader.read(LedgerFamily.directory(data), LedgerFamily.CHILDREN).records()
        .stream().filter(r -> r.type() == LedgerRecordType.CHILD_EXIT).count(), "no duplicate terminal receipt");
  }

  @Test void manifestOnlyMatchingHeldIdentityRecordsStop() throws Exception {
    String nativeBirth;
    var held = ProcessTerminator.forCurrentOs().open(process);
    try {
      nativeBirth = held.creationFileTime().orElse(null);
      jdkIdentity = nativeBirth == null;
      // Compatibility only: the before-state has no held executable accessor. On the candidate,
      // use the real Windows handle query; termination below remains a harmless recording double.
      try {
        var image = (Optional<?>) ProcessTerminator.OsProcess.class.getMethod("executable").invoke(held);
        heldExecutable = image.map(Object::toString);
      } catch (NoSuchMethodException before) { /* use the original JDK identity in the before-state */ }
    } finally { held.close(); }
    assertTrue(stop(writer(), nativeBirth).stop(data, child));
    assertEquals(1, stops);
    assertTrue(closed);
  }

  @Test void jdkBirthCheckedFallbackRemainsEligible() throws Exception {
    jdkIdentity = true;
    assertTrue(stop(writer(), null).stop(data, child));
    assertEquals(1, stops);
  }
}
