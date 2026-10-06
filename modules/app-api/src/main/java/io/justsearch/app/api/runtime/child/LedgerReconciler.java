/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ledger.ExitLedgerReader;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerReadResult;
import io.justsearch.app.api.runtime.ledger.LedgerRecord;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.app.api.runtime.ledger.LedgerTimestamps;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Closes what an earlier Engine left open in {@code children.v1.jsonl} (Spec B §3.1 R6-R8). It runs
 * once at boot, under {@code app.lock} and before this Engine's writer session starts, and it
 * <b>never kills</b>: a child that is still alive is recorded and left alone.
 *
 * <ul>
 *   <li>Open means a {@code child-spawn}, {@code child-start} or {@code stop-intent} without a
 *       {@code child-exit}, {@code owner-died} or {@code child-survived owner_died_alive}.
 *   <li>The OS check compares the recorded pid, creation FILETIME (or JDK start time) and
 *       executable with the live process: GONE → {@code owner-died OWNER_DIED}; ALIVE_MATCH → {@code
 *       child-survived owner_died_alive}; unreadable, or no start record → {@code owner-died
 *       UNKNOWN}.
 * </ul>
 *
 * Each record it writes closes the child, so a second boot writes nothing more.
 */
public final class LedgerReconciler {

  /** The OS identity check (R6-R8). */
  public enum OsCheck {
    GONE,
    ALIVE_MATCH,
    IDENTITY_UNKNOWN
  }

  /** What the ledger says the child was; empties are unknown. */
  public record RecordedIdentity(
      long pid,
      Optional<String> creationFileTime,
      Optional<Instant> processStartedAt,
      String executable) {}

  /** The live-process side of the check; a seam for tests. */
  @FunctionalInterface
  public interface OsIdentity {
    OsCheck check(RecordedIdentity recorded);
  }

  /** What one reconciliation wrote. */
  public record Result(int ownerDied, int unknown, int survived, int skippedLines) {}

  private final Path ledgerDir;
  private final LedgerSink sink;
  private final OsIdentity os;

  public LedgerReconciler(Path ledgerDir, LedgerSink sink, OsIdentity os) {
    this.ledgerDir = Objects.requireNonNull(ledgerDir, "ledgerDir");
    this.sink = Objects.requireNonNull(sink, "sink");
    this.os = Objects.requireNonNull(os, "os");
  }

  /** Reads both generations and closes every open child. Never throws for I/O. */
  public Result reconcile() {
    LedgerReadResult read;
    try {
      read = ExitLedgerReader.read(ledgerDir, LedgerFamily.CHILDREN);
    } catch (IOException | RuntimeException unreadable) {
      return new Result(0, 0, 0, 0);
    }
    Map<String, Open> open = new LinkedHashMap<>();
    for (LedgerRecord record : read.records()) {
      Optional<String> childId = record.text("childId");
      if (childId.isEmpty()) continue;
      switch (record.type()) {
        case CHILD_SPAWN -> open.computeIfAbsent(childId.get(), id -> new Open(id)).spawn = record;
        case CHILD_START -> open.computeIfAbsent(childId.get(), id -> new Open(id)).start = record;
        case STOP_INTENT -> open.computeIfAbsent(childId.get(), id -> new Open(id)).intent = record;
        case CHILD_EXIT, OWNER_DIED -> open.computeIfAbsent(childId.get(), id -> new Open(id))
            .closed = true;
        case CHILD_SURVIVED -> {
          if ("owner_died_alive".equals(record.text("basis").orElse(null))) {
            open.computeIfAbsent(childId.get(), id -> new Open(id)).closed = true;
          }
        }
        default -> {}
      }
    }
    int ownerDied = 0;
    int unknown = 0;
    int survived = 0;
    for (Open child : open.values()) {
      if (child.closed || (child.spawn == null && child.start == null && child.intent == null)) {
        continue;
      }
      String basis = child.intent != null ? "OPEN_INTENT"
          : child.start != null ? "OPEN_START" : "OPEN_SPAWN";
      OsCheck check = child.start == null ? OsCheck.IDENTITY_UNKNOWN : checkQuietly(child.start);
      if (check == OsCheck.ALIVE_MATCH) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("childId", child.childId);
        fields.put("intentId", child.intent == null ? null : child.intent.text("intentId").orElse(null));
        fields.put("attempts", 1);
        fields.put("basis", "owner_died_alive");
        sink.append(LedgerRecordType.CHILD_SURVIVED, fields);
        survived++;
        continue;
      }
      ExitClass exitClass = check == OsCheck.GONE ? ExitClass.OWNER_DIED : ExitClass.UNKNOWN;
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("childId", child.childId);
      fields.put("previousOwner", previousOwner(child));
      fields.put("basis", basis);
      fields.put("osCheck", check);
      fields.put("exitClass", exitClass);
      sink.append(LedgerRecordType.OWNER_DIED, fields);
      if (exitClass == ExitClass.OWNER_DIED) ownerDied++;
      else unknown++;
    }
    return new Result(ownerDied, unknown, survived, read.skippedLines());
  }

  private OsCheck checkQuietly(LedgerRecord start) {
    long pid = start.field("pid").asLong(0);
    if (pid <= 0) return OsCheck.IDENTITY_UNKNOWN;
    RecordedIdentity recorded = new RecordedIdentity(pid,
        start.text("creationFileTime").filter(value -> value.matches("[1-9][0-9]{0,19}")),
        start.text("processStartedAt").flatMap(LedgerTimestamps::known),
        start.text("executable").orElse(""));
    try {
      return Objects.requireNonNull(os.check(recorded), "check");
    } catch (RuntimeException unreadable) {
      return OsCheck.IDENTITY_UNKNOWN;
    }
  }

  /** The owner named at spawn; else the writer of the oldest open record, otherwise unknown. */
  private static Map<String, Object> previousOwner(Open child) {
    if (child.spawn != null) {
      JsonNode owner = child.spawn.field("owner");
      long pid = owner.path("pid").asLong(0);
      JsonNode instance = owner.path("instanceId");
      if (pid > 0 && instance.isString() && !instance.asString().isEmpty()) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("pid", pid);
        JsonNode creation = owner.path("creationFileTime");
        fields.put("creationFileTime", creation.isString() ? creation.asString() : null);
        fields.put("instanceId", instance.asString());
        return fields;
      }
    }
    LedgerRecord first = child.spawn != null ? child.spawn
        : child.start != null ? child.start : child.intent;
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("pid", first.writer().pid());
    fields.put("creationFileTime", null);
    fields.put("instanceId", "unknown");
    return fields;
  }

  /**
   * The production check: the pid is gone or reused (GONE), or the same process (ALIVE_MATCH, by
   * creation FILETIME on Windows, by the JDK start time at millisecond precision elsewhere, and the
   * executable), or something could not be read (IDENTITY_UNKNOWN).
   */
  public static OsIdentity osIdentity(ProcessTerminator terminator) {
    Objects.requireNonNull(terminator, "terminator");
    return recorded -> {
      Optional<ProcessHandle> live = ProcessHandle.of(recorded.pid());
      if (live.isEmpty() || !live.get().isAlive()) return OsCheck.GONE;
      ProcessHandle.Info info = live.get().info();
      Optional<String> liveCreation = recorded.creationFileTime().isPresent()
          ? terminator.creationFileTime(recorded.pid()) : Optional.empty();
      boolean sameStart;
      if (recorded.creationFileTime().isPresent() && liveCreation.isPresent()) {
        sameStart = recorded.creationFileTime().get().equals(liveCreation.get());
      } else {
        Optional<Instant> liveStart = info.startInstant();
        if (recorded.processStartedAt().isEmpty() || liveStart.isEmpty()) {
          return OsCheck.IDENTITY_UNKNOWN;
        }
        sameStart = recorded.processStartedAt().get().truncatedTo(ChronoUnit.MILLIS)
            .equals(liveStart.get().truncatedTo(ChronoUnit.MILLIS));
      }
      if (!sameStart) return OsCheck.GONE; // the pid now names another process
      Optional<String> command = info.command().filter(value -> !value.isBlank());
      if (command.isEmpty() || recorded.executable().isBlank()) return OsCheck.IDENTITY_UNKNOWN;
      String liveExecutable = ManagedChild.normalizePath(Path.of(command.get()));
      String recordedExecutable = ManagedChild.normalizePath(Path.of(recorded.executable()));
      return liveExecutable.equals(recordedExecutable) ? OsCheck.ALIVE_MATCH : OsCheck.GONE;
    };
  }

  private static final class Open {
    final String childId;
    LedgerRecord spawn;
    LedgerRecord start;
    LedgerRecord intent;
    boolean closed;

    Open(String childId) {
      this.childId = childId;
    }
  }
}
