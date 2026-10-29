/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.ExitLedgerReader;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerRecord;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** The boot fallback's owner records its own stop, without adopting the child for use. */
final class PredecessorChildStop {
  private static final Duration WAIT = Duration.ofSeconds(5);
  private final LedgerSink sink;
  private final ProcessTerminator terminator;
  private final Clock clock;
  private final EngineIdentity owner;

  PredecessorChildStop(LedgerSink sink, ProcessTerminator terminator, Clock clock, EngineIdentity owner) {
    this.sink = sink;
    this.terminator = terminator;
    this.clock = clock;
    this.owner = owner;
  }

  boolean stop(Path dataDir, ManagedChild child) throws IOException {
    boolean linux = terminator instanceof LinuxProcessTerminator;
    if (linux) {
      if (child.processIdentity() == null) return false; // Legacy precision never authorizes a Linux kill.
      if (LinuxProcessIdentity.currentBootId().filter(child.processIdentity().bootId()::equals).isEmpty()) return false;
      var observed = LinuxProcessIdentity.observe(child.pid());
      if (observed.presence() == ProcessTerminator.IdentityPresence.UNKNOWN) return false;
      if (observed.presence() == ProcessTerminator.IdentityPresence.PRESENT
          && (observed.identity().filter(child.processIdentity()::equals).isEmpty()
              || observed.executable().map(Path::of).map(ManagedChild::normalizePath)
                  .filter(child.executable()::equals).isEmpty())) return false;
    }
    List<LedgerRecord> records;
    try {
      records = ExitLedgerReader.read(LedgerFamily.directory(dataDir), LedgerFamily.CHILDREN).records();
    } catch (IOException unreadable) {
      records = List.of(); // ledger failure degrades accounting; it does not disable cleanup
    }
    List<LedgerRecord> matches = new ArrayList<>();
    for (LedgerRecord r : records.stream().distinct().toList()) {
      if (r.type() != LedgerRecordType.CHILD_START || r.field("pid").asLong() != child.pid()) continue;
      if (!child.executable().equals(ManagedChild.normalizePath(
          Path.of(r.text("executable").orElse(""))))) continue;
      Optional<String> birth = r.text("creationFileTime");
      boolean sameStart = linux
          ? LinuxProcessIdentity.fromJson(r.field("processIdentity")).filter(child.processIdentity()::equals).isPresent()
          : birth.isPresent()
          ? Long.parseLong(birth.get()) / 10_000L - 11_644_473_600_000L
              == Instant.parse(child.startedAt()).toEpochMilli()
          : r.text("processStartedAt").map(Instant::parse)
              .map(i -> i.equals(Instant.parse(child.startedAt()))).orElse(false);
      if (sameStart) matches.add(r);
    }
    // The manifest is coarser than the native identity. Never choose between ambiguous births.
    if (matches.size() > 1) return false;
    LedgerRecord start = matches.isEmpty() ? null : matches.getFirst();
    String childId = start == null ? "fallback-" + child.id() : start.text("childId").orElseThrow();
    Optional<ProcessHandle> live = ProcessHandle.of(child.pid()).filter(ProcessHandle::isAlive);
    if (linux && live.isEmpty()
        && LinuxProcessIdentity.observe(child.pid()).presence() != ProcessTerminator.IdentityPresence.GONE) return false;
    if (records.stream().anyMatch(r -> (r.type() == LedgerRecordType.CHILD_EXIT
        || (r.type() == LedgerRecordType.OWNER_DIED && r.text("exitClass").filter("UNKNOWN"::equals).isEmpty()))
        && r.text("childId").filter(childId::equals).isPresent())) return live.isEmpty();
    if (live.isPresent() && child.identityOf(live.get()) != ManagedChild.IdentityMatch.MATCH) return false;
    ProcessTerminator.OsProcess os = null;
    if (live.isPresent()) {
      try {
        os = linux
            ? terminator.open(live.get(), child.processIdentity()) : terminator.open(live.get());
      } catch (IOException unavailable) {
        if (live.get().isAlive()) throw unavailable;
        // It ended between the identity check and OpenProcess. No code or exit time is known.
      }
      // The held native handle, not CIM or the manifest's rounded time, authorizes the kill.
      if (os != null && !(linux
          ? os.processIdentity().filter(child.processIdentity()::equals).isPresent()
              && os.executable().map(Path::of).map(ManagedChild::normalizePath).filter(child.executable()::equals).isPresent()
          : matchesHeldIdentity(child, start, os))) {
        os.close();
        return false;
      }
    }
    try {
      if (start == null) {
        // Legacy manifest-only children get their own cleanup identity; never forge an old spawn.
        Object recordedOwner = owner;
        if (linux) recordedOwner = fields("pid", owner.pid(), "creationFileTime", owner.creationFileTime(),
            "instanceId", owner.instanceId(), "processIdentity", LinuxProcessIdentity.read(owner.pid()));
        sink.append(LedgerRecordType.CHILD_SPAWN, fields("childId", childId, "kind", child.kind(),
            "label", "predecessor-fallback", "owner", recordedOwner, "gpu", false));
        Map<String, Object> startFields = fields("childId", childId, "pid", child.pid(),
            "processStartedAt", Instant.parse(child.startedAt()), "creationFileTime",
            os == null ? Optional.empty() : os.creationFileTime(), "executable", child.executable(),
            "argvHash", child.realizedArgvHash() != null && child.realizedArgvHash().matches("[0-9a-f]{64}")
                ? child.realizedArgvHash() : DefaultChildControl.argvHash(List.of(child.executable())), "contained", false,
            "gpuDevice", null);
        if (linux) startFields.put("processIdentity", child.processIdentity());
        sink.append(LedgerRecordType.CHILD_START, startFields);
      }
      String intentId = "s-" + UUID.randomUUID();
      sink.append(LedgerRecordType.STOP_INTENT, fields("childId", childId, "intentId", intentId,
          "reason", StopReason.CRASH_RECOVERY, "detail", null, "mode", StopMode.NOW,
          "requestedAt", clock.instant(), "requestedBy", "predecessor-fallback"));
      Instant calledAt = null;
      TerminateResult result = TerminateResult.NOT_CALLED;
      boolean exited = os == null || os.awaitExit(Duration.ZERO);
      for (int attempt = 0; !exited && attempt < 2; attempt++) {
        if (calledAt == null) calledAt = clock.instant();
        TerminateResult next = os.terminate(terminator.ourCode());
        if (result.status() != TerminateResult.Status.OK) result = next;
        exited = os.awaitExit(WAIT);
      }
      if (!exited) {
        sink.append(LedgerRecordType.CHILD_SURVIVED, fields("childId", childId, "intentId", intentId,
            "attempts", 2, "basis", "terminate_timeout"));
        return false; // alive is not an exit; preserve the manifest for another cleanup attempt
      }
      OptionalInt code = os == null ? OptionalInt.empty() : os.exitCode();
      ExitClass exitClass = DefaultChildControl.classify(true, code, result, false,
          Set.of(), terminator.ourCode());
      sink.append(LedgerRecordType.CHILD_EXIT, fields("childId", childId, "intentId", intentId,
          "exitClass", exitClass, "reason", StopReason.CRASH_RECOVERY, "detail", null,
          "mode", StopMode.NOW, "code", code, "codeName",
          code.isPresent() ? DefaultChildControl.codeName(code.getAsInt()) : null,
          "codeDistinctive", terminator.codeDistinctive(), "exitedAt",
          os == null ? Optional.empty() : os.exitedAt(), "observedAt", clock.instant(),
          "terminateCalledAt", calledAt, "terminateResult", result.wireValue(), "cooperativeAt", null,
          "forced", exitClass == ExitClass.REQUESTED, "phase", ExitOutcome.Phase.STOPPING));
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("predecessor cleanup interrupted", interrupted);
    } finally {
      if (os != null) os.close();
    }
  }

  private static boolean matchesHeldIdentity(ManagedChild child, LedgerRecord start, ProcessTerminator.OsProcess os) {
    Optional<String> expected = start == null ? Optional.empty() : start.text("creationFileTime");
    if (expected.isPresent()) return expected.equals(os.creationFileTime());
    if (os.creationFileTime().isEmpty()) return os.matchesJdkIdentity(child);
    // Legacy manifests record milliseconds. The held native birth AND executable authorize
    // this fallback; the earlier PID lookup is insufficient across an OpenProcess race.
    try {
      return os.creationFileTime().map(Long::parseLong)
          .map(birth -> birth / 10_000L - 11_644_473_600_000L == Instant.parse(child.startedAt()).toEpochMilli())
          .orElse(false)
          && os.executable().map(Path::of).map(ManagedChild::normalizePath).filter(child.executable()::equals).isPresent();
    } catch (RuntimeException unreadable) {
      return false;
    }
  }

  private static Map<String, Object> fields(Object... pairs) {
    Map<String, Object> fields = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) fields.put((String) pairs[i], pairs[i + 1]);
    return fields;
  }
}
