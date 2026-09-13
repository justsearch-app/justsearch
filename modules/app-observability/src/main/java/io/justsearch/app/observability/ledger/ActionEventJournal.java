/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import io.justsearch.configuration.PlatformPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 812 D1 — the durable audit journal for actor events.
 *
 * <p>{@link ActionEventStore} is a process-lifetime ring: the audit trail it holds resets on every
 * Head restart, and its index-first eviction protects actor rows only while index rows remain to
 * sacrifice. 812's principle — <i>an audit record's lifetime must be a stated guarantee, not a side
 * effect of ring pressure</i> — makes the three consequential kinds durable on disk:
 * {@code GRANT}, {@code GATE}, {@code OPERATION}. {@code NAVIGATION}, {@code EFFECT} and
 * {@code INDEX} stay ring-only ephemera (operational telemetry, not audit).
 *
 * <p><b>This is a write-behind copy, not a second authority.</b> Tempdoc 550:468 rejected
 * re-sourcing the rail from the ledger and that rejection stands: the ring stays the hot unified
 * feed and the per-kind stores stay authoritative for their own consumers. The journal exists so an
 * audit read after a restart is not empty.
 *
 * <p><b>Format.</b> One JSON object per line (JSONL), UTF-8. A line IS the event's wire row — the
 * exact {@link ActionLedgerProjection#toWireRow} shape the snapshot endpoint and the live stream
 * already emit — so there is no second schema to keep in sync; {@link
 * ActionLedgerProjection#fromWireRow} is its inverse.
 *
 * <p><b>Bounds.</b> Growth is capped by file rotation: the active file rotates at
 * {@link #MAX_GENERATION_BYTES} (4 MB) and at most {@link #MAX_GENERATIONS} (8) generations are
 * kept — the active {@code action-ledger.jsonl} plus {@code action-ledger.1.jsonl} …
 * {@code action-ledger.7.jsonl}, oldest dropped first. Worst case on disk is therefore ~32 MB.
 *
 * <p><b>Read and deduplication bounds.</b> Requests read the newest
 * {@link #TAIL_CAPACITY} (500) events from memory. Opening the journal reads its retained
 * generations once, deriving an id set per generation as well as that tail. Deduplication
 * therefore covers retained files even when an id has left the read tail. Rotation updates
 * those derived sets; no additional persistent identity registry is created. An uncertain
 * write/rotation failure invalidates the index so a later append reconstructs it first.
 *
 * <p><b>Synchronous by design (812 critical-analysis (b)).</b> Every {@link #append} writes and
 * closes through to the file before returning. Buffering or handing the write to another thread
 * would reopen exactly the defect this journal closes: a Head killed with an unflushed buffer loses
 * the records the restart is supposed to recover. Actor events are the consequential ~7% of ledger
 * traffic (grant/gate/operation, not the per-document index flood), so the write rate this costs is
 * a few lines per user action.
 */
public final class ActionEventJournal {

  private static final Logger log = LoggerFactory.getLogger(ActionEventJournal.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** The audit directory under the data dir. Deliberately NOT {@code runtime/} — see class doc. */
  public static final String AUDIT_DIR_NAME = "audit";

  /** The active generation's file name; rotated generations are {@code action-ledger.<n>.jsonl}. */
  static final String ACTIVE_FILE_NAME = "action-ledger.jsonl";

  /** Rotate the active file once it would exceed this size. */
  static final long MAX_GENERATION_BYTES = 4L * 1024 * 1024;

  /** Total generations kept (the active file plus {@code MAX_GENERATIONS - 1} rotated ones). */
  static final int MAX_GENERATIONS = 8;

  /** How many of the newest events the in-memory tail mirror holds (the read bound). */
  public static final int TAIL_CAPACITY = 500;

  /**
   * The kinds whose lifetime is a stated guarantee. The others are ring-only ephemera — an
   * {@code INDEX} row per document would turn a 5k-document ingest into 5k journal lines for
   * telemetry the indexing-jobs Resource already owns authoritatively.
   */
  private static final Set<ActionEvent.ActionEventKind> DURABLE_KINDS =
      Collections.unmodifiableSet(
          EnumSet.of(
              ActionEvent.ActionEventKind.GRANT,
              ActionEvent.ActionEventKind.GATE,
              ActionEvent.ActionEventKind.OPERATION));

  /** {@code null} ⇒ disabled (in-memory / read-only wiring); no file is ever created. */
  private final Path auditDir;

  private final long maxGenerationBytes;

  // Newest-last mirror of the journal's newest TAIL_CAPACITY events. Guarded by `this`.
  private final Deque<ActionEvent> tailMirror = new ArrayDeque<>();
  // Derived from the retained files, never another durable identity authority. The read tail
  // alone cannot deduplicate a retained event that is older than its 500-entry window.
  private final Map<Integer, Set<String>> retainedIds = new java.util.HashMap<>();
  private boolean retainedIndexReady;

  private ActionEventJournal(Path auditDir, long maxGenerationBytes) {
    this.auditDir = auditDir;
    this.maxGenerationBytes = maxGenerationBytes;
    if (auditDir != null) {
      try {
        seedRetainedState();
      } catch (IOException | RuntimeException failure) {
        log.warn("Action-ledger audit journal could not be read from {}", auditDir, failure);
      }
    }
  }

  /** A journal that writes nothing and serves an empty tail — test / read-only-mode wiring. */
  public static ActionEventJournal disabled() {
    return new ActionEventJournal(null, MAX_GENERATION_BYTES);
  }

  /** A journal rooted at {@code <dataDir>/audit} (the production location). */
  public static ActionEventJournal persistent() {
    return at(PlatformPaths.resolveDataDir().resolve(AUDIT_DIR_NAME));
  }

  /**
   * A journal rooted at an explicit audit directory (alternate data dirs, tests), rotating at the
   * default {@link #MAX_GENERATION_BYTES}.
   */
  public static ActionEventJournal at(Path auditDir) {
    return at(auditDir, MAX_GENERATION_BYTES);
  }

  /**
   * A journal rooted at an explicit audit directory with an explicit rotation threshold. The size
   * bound is a construction parameter rather than a hidden constant so the rotation behaviour is
   * exercisable at a threshold that does not require writing the production 4 MB per generation.
   */
  static ActionEventJournal at(Path auditDir, long maxGenerationBytes) {
    return new ActionEventJournal(auditDir, maxGenerationBytes);
  }

  /** True when this kind's lifetime is a durable guarantee rather than ring residency. */
  public static boolean isDurableKind(ActionEvent.ActionEventKind kind) {
    return DURABLE_KINDS.contains(kind);
  }

  /** True when this journal writes to disk (false for {@link #disabled()}). */
  public boolean isEnabled() {
    return auditDir != null;
  }

  /** The active generation's path. Exposed for tests and for the store register's owned paths. */
  Path activeFile() {
    return auditDir == null ? null : auditDir.resolve(ACTIVE_FILE_NAME);
  }

  /**
   * Append one event if its kind is durable. Synchronous: the line is on disk when this returns.
   * Returns true only if this event is now (or already was) retained by the durable sink.
   * Disabled/non-durable inputs return false. An IO failure is logged and returns false; it
   * cannot change the action that produced the event. Callers may retry the same id.
   */
  public synchronized boolean append(ActionEvent event) {
    if (event == null || !isDurableKind(event.kind()) || auditDir == null) {
      return false;
    }
    try {
      if (!retainedIndexReady) seedRetainedState();
      if (isRetained(event.id())) return true;
      byte[] line =
          (MAPPER.writeValueAsString(ActionLedgerProjection.toWireRow(event)) + "\n")
              .getBytes(StandardCharsets.UTF_8);
      Files.createDirectories(auditDir);
      rotateIfNeeded(line.length);
      preserveLineBoundary();
      Files.write(
          activeFile(), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      retainedIds.computeIfAbsent(0, ignored -> new java.util.HashSet<>()).add(event.id());
      rememberInTail(event);
      return true;
    } catch (IOException | RuntimeException e) {
      // Rotation or append may have changed the files before failing. The next attempt must
      // reconstruct its index instead of treating that uncertain state as an acknowledged id.
      retainedIndexReady = false;
      log.warn("Action-ledger audit journal append failed for event {}", event.id(), e);
      return false;
    }
  }

  private boolean isRetained(String id) {
    return retainedIds.values().stream().anyMatch(ids -> ids.contains(id));
  }

  /** Keep a killed writer's trailing fragment separate from the next complete record. */
  private void preserveLineBoundary() throws IOException {
    Path active = activeFile();
    if (!Files.exists(active)) return;
    boolean needsNewline;
    try (var channel = Files.newByteChannel(active, StandardOpenOption.READ)) {
      long size = channel.size();
      if (size == 0) return;
      var lastByte = java.nio.ByteBuffer.allocate(1);
      channel.position(size - 1);
      if (channel.read(lastByte) != 1) throw new IOException("Cannot read journal line boundary");
      needsNewline = lastByte.array()[0] != '\n';
    }
    if (needsNewline) Files.write(active, new byte[] {'\n'}, StandardOpenOption.APPEND);
  }

  /**
   * The newest {@code max} journaled events, oldest-first — served from the in-memory mirror, so
   * this never reads the files (see the read-bound note in the class doc).
   */
  public synchronized List<ActionEvent> tail(int max) {
    if (max <= 0) {
      return List.of();
    }
    List<ActionEvent> all = new ArrayList<>(tailMirror);
    if (all.size() <= max) {
      return all;
    }
    return new ArrayList<>(all.subList(all.size() - max, all.size()));
  }

  private void rememberInTail(ActionEvent event) {
    tailMirror.addLast(event);
    while (tailMirror.size() > TAIL_CAPACITY) {
      tailMirror.removeFirst();
    }
  }

  /**
   * Rotate when the next line would push the active file past {@link #MAX_GENERATION_BYTES}:
   * the oldest generation is dropped, every other shifts up one, and the active file becomes
   * generation 1. Checked before the write so a line is never split across generations.
   */
  private void rotateIfNeeded(int incomingBytes) throws IOException {
    Path active = activeFile();
    if (!Files.exists(active)) {
      return;
    }
    long size = Files.size(active);
    if (size == 0 || size + incomingBytes <= maxGenerationBytes) {
      return;
    }
    Files.deleteIfExists(generation(MAX_GENERATIONS - 1));
    for (int n = MAX_GENERATIONS - 2; n >= 1; n--) {
      Path from = generation(n);
      if (Files.exists(from)) {
        Files.move(from, generation(n + 1), StandardCopyOption.REPLACE_EXISTING);
      }
    }
    Files.move(active, generation(1), StandardCopyOption.REPLACE_EXISTING);
    retainedIds.remove(MAX_GENERATIONS - 1);
    for (int n = MAX_GENERATIONS - 2; n >= 0; n--) {
      var ids = retainedIds.remove(n);
      if (ids != null) retainedIds.put(n + 1, ids);
    }
    tailMirror.removeIf(event -> !isRetained(event.id()));
  }

  /** Rotated generation {@code n} ({@code 1} = most recently rotated). */
  Path generation(int n) {
    return auditDir.resolve("action-ledger." + n + ".jsonl");
  }

  /**
   * Derive retained-id sets and the newest read tail from the existing bounded file generations.
   * Publish the reconstructed state only after every generation was read successfully.
   */
  private void seedRetainedState() throws IOException {
    List<ActionEvent> newestFirst = new ArrayList<>();
    Map<Integer, Set<String>> loadedIds = new java.util.HashMap<>();
    Set<String> seen = new java.util.HashSet<>();
    for (int n = 0; n < MAX_GENERATIONS; n++) {
      Set<String> ids = new java.util.HashSet<>();
      readGenerationBackwards(n == 0 ? activeFile() : generation(n), newestFirst, ids, seen);
      loadedIds.put(n, ids);
    }
    retainedIds.clear();
    retainedIds.putAll(loadedIds);
    tailMirror.clear();
    for (int i = newestFirst.size() - 1; i >= 0; i--) {
      tailMirror.addLast(newestFirst.get(i));
    }
    retainedIndexReady = true;
  }

  /** Read every retained id, while collecting only the newest bounded event tail. */
  private void readGenerationBackwards(Path file, List<ActionEvent> newestFirst,
      Set<String> ids, Set<String> seen) throws IOException {
    if (file == null || !Files.exists(file)) {
      return;
    }
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    for (int i = lines.size() - 1; i >= 0; i--) {
      parseLine(lines.get(i)).ifPresent(event -> {
        ids.add(event.id());
        if (seen.add(event.id()) && newestFirst.size() < TAIL_CAPACITY) newestFirst.add(event);
      });
    }
  }

  private java.util.Optional<ActionEvent> parseLine(String line) {
    if (line == null || line.isBlank()) {
      return java.util.Optional.empty();
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> row = MAPPER.readValue(line, Map.class);
      return ActionLedgerProjection.fromWireRow(row);
    } catch (RuntimeException e) {
      // A torn final line (killed mid-append) or a row from a future schema: skip it rather than
      // fail the whole read — the rest of the audit trail is still recoverable, which is the point.
      log.warn("Skipping unreadable action-ledger journal line", e);
      return java.util.Optional.empty();
    }
  }
}
