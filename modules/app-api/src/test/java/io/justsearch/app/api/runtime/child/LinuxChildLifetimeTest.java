/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.containment.EngineContainment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

@EnabledOnOs(OS.LINUX)
@Timeout(20)
final class LinuxChildLifetimeTest {
  @TempDir Path directory;

  @Test void statUsesField22AfterTheFinalCommParenthesis() throws Exception {
    String[] fields = new String[24];
    java.util.Arrays.fill(fields, "0");
    fields[0] = "S";
    fields[19] = "12345678901234567";
    var stat = LinuxProcessIdentity.parseStat("123 (comm ) with spaces\nand )) " + String.join(" ", fields));
    assertEquals('S', stat.state());
    assertEquals("12345678901234567", stat.ticks());
    assertThrows(java.io.IOException.class, () -> LinuxProcessIdentity.parseStat("invalid"));
  }

  @Test void bootAndStartsHaveVersionedExactIdentitiesAndNoFiletime() throws Exception {
    var identity = LinuxProcessIdentity.read(ProcessHandle.current().pid()).orElseThrow();
    assertEquals("linux-proc-v1", identity.kind());
    assertEquals(Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).strip(), identity.bootId());
    assertEquals(identity, new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(identity), LinuxProcessIdentity.class));
    assertTrue(ProcessTerminator.forCurrentOs().creationFileTime(identity.pid()).isEmpty());
    var malformed = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(Map.of(
        "kind", identity.kind(), "pid", identity.pid(), "bootId", identity.bootId(), "startTicks", 123)));
    assertTrue(LinuxProcessIdentity.fromJson(malformed).isEmpty(), "start ticks must remain a decimal string");
  }

  @Test void parentMismatchNeverRunsTheWorkloadAndExecErrorsAre125() throws Exception {
    Path marker = directory.resolve("should-not-exist");
    Process mismatch = new ProcessBuilder(BoundChildProcess.helper().toString(), "exec", "1", "--",
        "/usr/bin/touch", marker.toString()).start();
    assertTrue(mismatch.waitFor(2, TimeUnit.SECONDS));
    assertEquals(125, mismatch.exitValue());
    assertFalse(Files.exists(marker));
    assertThrows(java.io.IOException.class, () -> BoundChildProcess.start(new ProcessBuilder("/no/such/justsearch-program")));
    // A failed task must not retire the only thread that owns existing and later children.
    Process next = BoundChildProcess.start(new ProcessBuilder("/bin/true"));
    assertTrue(next.waitFor(2, TimeUnit.SECONDS));
    assertEquals(0, next.exitValue());
    Process workload125 = BoundChildProcess.start(new ProcessBuilder(FakeChild.argv("exit:125:50")));
    assertTrue(workload125.waitFor(2, TimeUnit.SECONDS));
    assertEquals(125, workload125.exitValue(), "an observed workload's exit code is preserved");
  }

  @Test void unrelatedLaunchingThreadExitsAndInterruptDoesNotLoseTheSpawn() throws Exception {
    AtomicReference<Process> child = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    Thread caller = Thread.ofPlatform().start(() -> {
      Thread.currentThread().interrupt();
      try {
        child.set(BoundChildProcess.start(new ProcessBuilder("/bin/sleep", "60")));
        assertTrue(Thread.currentThread().isInterrupted());
      } catch (Throwable failure) { error.set(failure); }
    });
    caller.join();
    if (error.get() != null) throw new AssertionError(error.get());
    Process process = child.get();
    try {
      Thread.sleep(100);
      assertTrue(process.isAlive(), "caller thread exit must not deliver PDEATHSIG");
      assertEquals(Optional.of(true), BoundChildProcess.contains(process.toHandle()));
    } finally { process.destroyForcibly(); assertTrue(process.waitFor(2, TimeUnit.SECONDS)); }
  }

  @Test void completedBoundChildKeepsItsBirthOnTheOriginalSpawnObject() throws Exception {
    Process process = BoundChildProcess.start(new ProcessBuilder(FakeChild.argv("exit:0:100")));
    var original = LinuxProcessIdentity.read(process.pid()).orElseThrow();
    assertTrue(process.waitFor(5, TimeUnit.SECONDS));
    var held = ProcessTerminator.forCurrentOs().open(process);
    try {
      assertEquals(Optional.of(original), held.processIdentity(),
          "reaping must not erase the original birth before a caller acquires its receipt");
      assertEquals(0, held.exitCode().orElseThrow());
    } finally { held.close(); }
  }

  @Test void badBootOrStartNeverSignalsForeignProcessButExactTupleDoes() throws Exception {
    Process decoy = new ProcessBuilder("/bin/sleep", "60").start();
    var identity = LinuxProcessIdentity.read(decoy.pid()).orElseThrow();
    try {
      assertEquals(3, new LinuxProcessIdentity(identity.kind(), identity.pid(),
          "00000000-0000-0000-0000-000000000000", identity.startTicks()).signal("KILL"));
      assertEquals(3, new LinuxProcessIdentity(identity.kind(), identity.pid(), identity.bootId(),
          new java.math.BigInteger(identity.startTicks()).add(java.math.BigInteger.ONE).toString()).signal("KILL"));
      assertTrue(decoy.isAlive());
      assertEquals(0, identity.signal("0"));
      assertTrue(decoy.isAlive());
      assertEquals(0, identity.signal("KILL"));
      assertTrue(decoy.waitFor(2, TimeUnit.SECONDS));
      assertEquals(3, identity.signal("KILL"));
    } finally { decoy.destroyForcibly(); assertTrue(decoy.waitFor(2, TimeUnit.SECONDS)); }
  }

  @Test void legacyBirthNeverAuthorizesRecordedNonchildTermination() throws Exception {
    Process decoy = new ProcessBuilder("/bin/sleep", "60").start();
    try {
      var terminator = ProcessTerminator.forCurrentOs();
      var observed = terminator.open(decoy.toHandle());
      assertEquals(TerminateResult.Status.FAILED, observed.terminate(137).status());
      var legacy = new LedgerReconciler.RecordedIdentity(decoy.pid(), Optional.empty(),
          decoy.info().startInstant(), decoy.info().command().orElseThrow());
      assertEquals(LedgerReconciler.OsCheck.IDENTITY_UNKNOWN, LedgerReconciler.osIdentity(terminator).check(legacy));
      assertTrue(decoy.isAlive());
    } finally { decoy.destroyForcibly(); assertTrue(decoy.waitFor(2, TimeUnit.SECONDS)); }
  }

  @Test void engineSelfIntentCarriesItsLinuxBirthWithoutChangingTheWindowsRecord() throws Exception {
    var self = LinuxProcessIdentity.read(ProcessHandle.current().pid()).orElseThrow();
    var owner = new io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity(
        self.pid(), Optional.empty(), "linux-self-intent");
    var writer = new io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter(directory, owner,
        io.justsearch.app.api.runtime.ledger.LedgerWriterId.currentProcess(
            io.justsearch.app.api.runtime.ledger.LedgerWriterId.Role.ENGINE, java.time.Instant.now()),
        java.time.Clock.systemUTC());
    assertTrue(writer.record(StopReason.QUIT, Optional.empty(), 0));
    var record = io.justsearch.app.api.runtime.ledger.ExitLedgerReader.read(
        io.justsearch.app.api.runtime.ledger.LedgerFamily.directory(directory),
        io.justsearch.app.api.runtime.ledger.LedgerFamily.ENGINE).records().getFirst();
    assertEquals(Optional.of(self), LinuxProcessIdentity.fromJson(record.field("engine").path("processIdentity")));
    assertTrue(record.field("engine").path("creationFileTime").isNull());
  }

  @Test void finiteHelperReceiptsKeepExecutableFingerprintAndExactBirth() throws Exception {
    var field = OwnedHelper.class.getDeclaredField("recorder");
    field.setAccessible(true);
    Object previous = field.get(null);
    List<Map<String, Object>> rows = new java.util.concurrent.CopyOnWriteArrayList<>();
    OwnedHelper.recordTo((type, fields) -> {
      var row = new java.util.LinkedHashMap<String, Object>(fields);
      row.put("type", type.token()); rows.add(row); return true;
    }, new io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity(
        ProcessHandle.current().pid(), Optional.empty(), "linux-helper-receipt"));
    Process helper = null;
    try {
      helper = OwnedHelper.start(new ProcessBuilder("/bin/sleep", "60"), "Linux finite helper");
      var identity = LinuxProcessIdentity.read(helper.pid()).orElseThrow();
      var start = rows.stream().filter(row -> "helper-start".equals(row.get("type"))).findFirst().orElseThrow();
      var json = new ObjectMapper().valueToTree(start);
      assertEquals(Optional.of(identity), LinuxProcessIdentity.fromJson(json.path("processIdentity")));
      assertEquals(Files.readSymbolicLink(Path.of("/proc", Long.toString(helper.pid()), "exe")).toString(), json.path("executable").asString());
      assertEquals(DefaultChildControl.argvHash(List.of("/bin/sleep", "60")), json.path("argvHash").asString());
      helper.destroyForcibly();
      assertTrue(helper.waitFor(2, TimeUnit.SECONDS));
      var exit = rows.stream().filter(row -> "helper-exit".equals(row.get("type"))).findFirst().orElseThrow();
      assertEquals("REQUESTED", exit.get("exitClass"));
      assertEquals(Optional.of(identity), LinuxProcessIdentity.fromJson(new ObjectMapper().valueToTree(exit).path("processIdentity")));
    } finally {
      if (helper != null) { helper.destroyForcibly(); assertTrue(helper.waitFor(2, TimeUnit.SECONDS)); }
      field.set(null, previous);
    }
  }

  @Test void wrongBootReusedPidAndLegacyRecordsCannotProduceAStoppedVerdict() throws Exception {
    Process decoy = new ProcessBuilder("/bin/sleep", "60").start();
    try (var control = DefaultChildControl.of((type, fields) -> true,
        ProcessTerminator.forCurrentOs(), ManagedChildRegistry.noop())) {
      var saved = ManagedChild.fromProcess(decoy, ManagedChild.Kind.EXTRACTION, "stdio", null, null, null);
      var tuple = saved.processIdentity();
      for (LinuxProcessIdentity wrong : List.of(
          new LinuxProcessIdentity(tuple.kind(), tuple.pid(), "00000000-0000-0000-0000-000000000000", tuple.startTicks()),
          new LinuxProcessIdentity(tuple.kind(), tuple.pid(), tuple.bootId(),
              new java.math.BigInteger(tuple.startTicks()).add(java.math.BigInteger.ONE).toString()))) {
        var invalid = new ManagedChild(saved.id(), saved.kind(), saved.pid(), saved.startedAt(),
            saved.executable(), saved.endpoint(), null, null, null, wrong);
        assertFalse(control.stopPredecessor(directory, invalid));
        assertTrue(decoy.isAlive());
      }
      var legacy = new ManagedChild(saved.id(), saved.kind(), saved.pid(), saved.startedAt(),
          saved.executable(), saved.endpoint(), null, null, null);
      assertFalse(control.stopPredecessor(directory, legacy));
      assertTrue(decoy.isAlive());
    } finally { decoy.destroyForcibly(); assertTrue(decoy.waitFor(2, TimeUnit.SECONDS)); }
  }

  @Test void childManifestAndLedgerKeepTheTupleAndSavedIdentityStopsThroughPidfd() throws Exception {
    List<Map<String, ?>> records = new java.util.concurrent.CopyOnWriteArrayList<>();
    try (var control = DefaultChildControl.of((type, fields) -> { records.add(fields); return true; },
        ProcessTerminator.forCurrentOs(), ManagedChildRegistry.noop())) {
      ChildHandle held = control.start(ChildControlHarness.spec("sleep"));
      try {
        var start = records.stream().filter(row -> row.containsKey("executable")).findFirst().orElseThrow();
        var json = new ObjectMapper().valueToTree(start);
        var identity = LinuxProcessIdentity.fromJson(json.path("processIdentity")).orElseThrow();
        assertEquals(held.pid(), identity.pid());
        assertTrue(json.path("creationFileTime").isNull());
      } finally { ChildControlHarness.await(control.stop(held, StopRequest.of(StopReason.QUIT, StopMode.NOW, "test"))); }
    }
    Process foreign = new ProcessBuilder("/bin/sleep", "60").start();
    try {
      ManagedChild manifest = ManagedChild.fromProcess(foreign, ManagedChild.Kind.EXTRACTION, "stdio", null, null, null);
      var roundtrip = new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(manifest), ManagedChild.class);
      assertEquals(manifest, roundtrip);
      assertNotNull(roundtrip.processIdentity());
      var nativeHandle = ProcessTerminator.forCurrentOs().open(foreign.toHandle(), roundtrip.processIdentity());
      assertEquals(TerminateResult.OK, nativeHandle.terminate(137));
      assertTrue(nativeHandle.awaitExit(Duration.ofSeconds(2)));
      assertTrue(nativeHandle.exitCode().isEmpty());
      assertTrue(nativeHandle.exitedAt().isEmpty());
    } finally { foreign.destroyForcibly(); assertTrue(foreign.waitFor(2, TimeUnit.SECONDS)); }
  }
}
