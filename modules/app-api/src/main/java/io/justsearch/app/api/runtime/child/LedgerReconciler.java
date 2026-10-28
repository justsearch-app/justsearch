/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ledger.ExitLedgerReader;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerReadResult;
import io.justsearch.app.api.runtime.ledger.LedgerRecord;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.app.api.runtime.ledger.LedgerTimestamps;
import io.justsearch.app.api.runtime.ledger.LedgerWriterId;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
 *       {@code child-exit} or {@code owner-died}.
 *   <li>The OS check compares the recorded pid, creation FILETIME (or JDK start time) and
 *       executable with the live process: GONE → {@code owner-died OWNER_DIED}; ALIVE_MATCH → {@code
 *       child-survived owner_died_alive}; unreadable, or no start record → {@code owner-died
 *       UNKNOWN}.
 * </ul>
 *
 * A surviving child stays open; its identity is carried forward until its exit is confirmed.
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
      String executable,
      Optional<LinuxProcessIdentity> processIdentity) {
    public RecordedIdentity(long pid, Optional<String> creationFileTime,
        Optional<Instant> processStartedAt, String executable) {
      this(pid, creationFileTime, processStartedAt, executable, Optional.empty());
    }
  }

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
    Map<String, Open> open = openChildren(read.records());
    int ownerDied = 0;
    int unknown = 0;
    int survived = 0;
    reconcileHelpers(read);
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

  private static Map<String, Open> openChildren(List<LedgerRecord> records) {
    Map<String, Open> open = new LinkedHashMap<>();
    for (LedgerRecord record : records) {
      Optional<String> childId = record.text("childId");
      if (childId.isEmpty()) continue;
      switch (record.type()) {
        case CHILD_SPAWN -> open.computeIfAbsent(childId.get(), id -> new Open(id)).spawn = record;
        case CHILD_START -> open.computeIfAbsent(childId.get(), id -> new Open(id)).start = record;
        case STOP_INTENT -> open.computeIfAbsent(childId.get(), id -> new Open(id)).intent = record;
        case CHILD_EXIT, OWNER_DIED -> open.computeIfAbsent(childId.get(), id -> new Open(id))
            .closed = true;
        case CHILD_SURVIVED -> open.computeIfAbsent(childId.get(), id -> new Open(id)).survived = record;
        default -> {}
      }
    }
    return open;
  }

  /** Original receipts for surviving children, copied unchanged at rotation to retain ownership. */
  public static List<LedgerRecord> openChildRecords(List<LedgerRecord> records) {
    var open = openChildren(records);
    // Keep every distinct original receipt, including conflicting starts: cleanup must still
    // refuse ambiguous ownership. Copies from earlier rotations carry the same envelope.
    return records.stream().filter(record -> record.text("childId")
        .map(id -> !open.get(id).closed && open.get(id).survived != null).orElse(false))
        .filter(record -> record.type() == LedgerRecordType.CHILD_SPAWN
            || record.type() == LedgerRecordType.CHILD_START
            || record.type() == LedgerRecordType.STOP_INTENT
            || record.type() == LedgerRecordType.CHILD_SURVIVED)
        .filter(record -> record.type() != LedgerRecordType.CHILD_SURVIVED
            || record.equals(open.get(record.text("childId").orElseThrow()).survived))
        .distinct().toList();
  }

  /** Recover ledger-only starts before registration, including survivors carried across rotation. */
  public static List<ManagedChild> predecessorChildren(Path dataDir) throws IOException {
    List<ManagedChild> result = new ArrayList<>();
    for (Open child : openChildren(ExitLedgerReader.read(
        LedgerFamily.directory(dataDir), LedgerFamily.CHILDREN).records()).values()) {
      if (child.closed || child.start == null || child.spawn == null) continue;
      String kind = child.spawn.text("kind").orElse("");
      if (!kind.equals("EXTRACTION") && !kind.equals("LLAMA_SERVER")
          && !kind.equals("LLAMA_SELF_TEST")) continue;
      var start = child.start.text("processStartedAt").flatMap(LedgerTimestamps::known);
      String executable = child.start.text("executable").orElse("");
      long childPid = child.start.field("pid").asLong(0);
      if (childPid <= 0 || start.isEmpty() || executable.isBlank()) continue;
      Path executablePath;
      try {
        executablePath = Path.of(executable);
      } catch (java.nio.file.InvalidPathException invalid) {
        continue; // An invalid ledger path never grants process ownership.
      }
      if (!executablePath.isAbsolute()) continue;
      result.add(new ManagedChild("ledger-" + child.childId,
          kind.equals("EXTRACTION") ? ManagedChild.Kind.EXTRACTION : ManagedChild.Kind.LLAMA_SERVER,
          childPid, start.get().toString(), ManagedChild.normalizePath(executablePath),
          null, null, null, child.start.text("argvHash").orElse(null),
          LinuxProcessIdentity.fromJson(child.start.field("processIdentity")).orElse(null)));
    }
    return List.copyOf(result);
  }

  /** A successor owns receipts its crashed predecessor could no longer write. */
  private void reconcileHelpers(LedgerReadResult read) {
    LedgerReadResult engines;
    try { engines = ExitLedgerReader.read(ledgerDir, LedgerFamily.ENGINE); }
    catch (IOException | RuntimeException unreadable) { return; }
    Map<String, LedgerRecord> starts = new LinkedHashMap<>();
    java.util.Set<String> closed = new java.util.HashSet<>();
    for (LedgerRecord record : read.records()) {
      String id = record.text("witnessId").orElse("");
      if (record.type() == LedgerRecordType.JOB_PROCESS_START) starts.put(id, record);
      if (record.type() == LedgerRecordType.JOB_PROCESS_EXIT) closed.add(id);
    }
    for (var entry : starts.entrySet()) {
      LedgerRecord start = entry.getValue();
      if (entry.getKey().isEmpty() || closed.contains(entry.getKey())) continue;
      JsonNode owner = start.field("owner");
      long ownerPid = owner.path("pid").asLong(0);
      String ownerBirth = owner.path("creationFileTime").asString("");
      boolean engineConsole = start.field("consoleClient").asBoolean(false)
          && start.text("executable").orElse("").toLowerCase(java.util.Locale.ROOT).endsWith("\\conhost.exe")
          && start.field("parentPid").asLong(0) == ownerPid
          && start.text("parentCreationFileTime").orElse("").equals(ownerBirth)
          && ownerBirth.matches("[1-9][0-9]{0,19}")
          && start.text("creationFileTime").filter(value -> value.matches("[1-9][0-9]{0,19}"))
              .map(value -> new java.math.BigInteger(value).compareTo(new java.math.BigInteger(ownerBirth)) >= 0)
              .orElse(false);
      if (!start.field("contained").asBoolean(false) && !engineConsole) continue;
      if (ownerPid <= 0 || start.writer().pid() != ownerPid || start.field("pid").asLong(0) == ownerPid) continue;
      var matchingEngine = engines.records().stream().filter(engine ->
          engine.type() == LedgerRecordType.ENGINE_START
          && engine.field("pid").asLong(0) == ownerPid
          && sameEngineBirth(engine, ownerBirth)
          && engine.field("incarnation").equals(start.field("incarnation"))).findFirst();
      if (matchingEngine.isEmpty()) continue;
      var engineExit = engines.records().stream().filter(exit -> exit.type() == LedgerRecordType.ENGINE_EXIT
          && exit.field("incarnation").equals(matchingEngine.get().field("incarnation")))
          .reduce((previous, last) -> last);
      if (engineExit.isEmpty()) continue;
      boolean crashed = "UNEXPECTED".equals(engineExit.get().text("exitClass").orElse(""));
      boolean intendedConsole = engineConsole && "REQUESTED".equals(engineExit.get().text("exitClass").orElse(""));
      if (!crashed && !intendedConsole) continue;
      OsCheck check = checkQuietly(start);
      if (check == OsCheck.ALIVE_MATCH) continue;
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("witnessId", entry.getKey());
      fields.put("pid", start.field("pid").asLong(0));
      fields.put("creationFileTime", start.text("creationFileTime").orElse(null));
      fields.put("owner", Map.of("pid", ownerPid, "creationFileTime", ownerBirth,
          "instanceId", owner.path("instanceId").asString("unknown")));
      fields.put("incarnation", Map.of("session", start.field("incarnation").path("session").asString(""),
          "n", start.field("incarnation").path("n").asInt(0)));
      fields.put("reconciled", true);
      fields.put("cause", crashed ? "ENGINE_CRASH" : "ENGINE_EXIT");
      fields.put("osCheck", check);
      fields.put("exitClass", check != OsCheck.GONE ? ExitClass.UNKNOWN
          : intendedConsole ? ExitClass.REQUESTED : ExitClass.OWNER_DIED);
      String goneReason = intendedConsole ? engineExit.get().text("reason").orElse("Engine requested exit")
          : engineConsole ? "Engine crash ended its console host; final exit time unknown"
          : "Engine crash interrupted owner; contained helper gone; final exit time unknown";
      fields.put("reason", check == OsCheck.GONE ? goneReason
          : "Engine ended; helper identity lookup failed; exit and time unknown");
      fields.put("code", null);
      fields.put("exitedAt", null);
      fields.put("observedAt", Instant.now());
      fields.put("forced", false);
      sink.append(LedgerRecordType.JOB_PROCESS_EXIT, fields);
    }
  }

  /** Dev host starts use CIM's microsecond birth clock; native creator proofs stay exact. */
  private static boolean sameEngineBirth(LedgerRecord engine, String nativeBirth) {
    String recorded = engine.text("creationFileTime").orElse("");
    if (!recorded.matches("[1-9][0-9]{0,19}") || !nativeBirth.matches("[1-9][0-9]{0,19}")) return false;
    if (engine.writer().role() != LedgerWriterId.Role.DEV_RUNNER
        && engine.writer().role() != LedgerWriterId.Role.DEV_CLI) return recorded.equals(nativeBirth);
    var tick = java.math.BigInteger.TEN;
    return new java.math.BigInteger(recorded).divide(tick)
        .equals(new java.math.BigInteger(nativeBirth).divide(tick));
  }

  private OsCheck checkQuietly(LedgerRecord start) {
    long pid = start.field("pid").asLong(0);
    if (pid <= 0) return OsCheck.IDENTITY_UNKNOWN;
    RecordedIdentity recorded = new RecordedIdentity(pid,
        start.text("creationFileTime").filter(value -> value.matches("[1-9][0-9]{0,19}")),
        start.text("processStartedAt").flatMap(LedgerTimestamps::known),
        start.text("executable").orElse(""), LinuxProcessIdentity.fromJson(start.field("processIdentity")));
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
        if (owner.has("processIdentity")) fields.put("processIdentity", owner.path("processIdentity"));
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
      if (terminator instanceof LinuxProcessTerminator) {
        var live = LinuxProcessIdentity.observe(recorded.pid());
        if (live.presence() == ProcessTerminator.IdentityPresence.GONE) return OsCheck.GONE;
        if (live.identity().isEmpty() || recorded.processIdentity().isEmpty()) return OsCheck.IDENTITY_UNKNOWN;
        if (!live.identity().equals(recorded.processIdentity())) return OsCheck.GONE;
        if (live.executable().isEmpty() || recorded.executable().isBlank()) return OsCheck.IDENTITY_UNKNOWN;
        return ManagedChild.normalizePath(Path.of(live.executable().get()))
            .equals(ManagedChild.normalizePath(Path.of(recorded.executable()))) ? OsCheck.ALIVE_MATCH : OsCheck.GONE;
      }
      ProcessTerminator.IdentityPresence presence = terminator.identityPresence(recorded.pid());
      if (presence == ProcessTerminator.IdentityPresence.UNKNOWN) return OsCheck.IDENTITY_UNKNOWN;
      if (presence == ProcessTerminator.IdentityPresence.GONE) return OsCheck.GONE;
      Optional<ProcessHandle> live = ProcessHandle.of(recorded.pid());
      if (live.isEmpty()) return OsCheck.IDENTITY_UNKNOWN;
      if (!live.get().isAlive()) return OsCheck.GONE;
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
    LedgerRecord survived;
    boolean closed;

    Open(String childId) {
      this.childId = childId;
    }
  }
}
