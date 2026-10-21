/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The append-only writer of one ledger family for one writer session (ADR-0051).
 *
 * <ul>
 *   <li>Each record is one append of the whole line, then {@code FileChannel.force(false)}, before
 *       the caller acts. {@link #appendUnforced} skips the force for a record whose loss readers
 *       reconstruct from earlier forced records. The file is opened per append, so a rename between appends is safe.
 *   <li>A write failure never stops the caller: {@link #append} returns {@code false}, the writer
 *       turns {@link #healthy() unhealthy}, and a {@code ledger-gap} record is appended before the
 *       next record that can be written.
 *   <li>A rotating writer (the family's single rotator) rotates at session start when the file is
 *       over {@link ExitLedger#SESSION_ROTATE_BYTES}, and before an append that would take it past
 *       {@link ExitLedger#HARD_CAP_BYTES}. A rotation failure keeps appending to the current file and
 *       records a {@code ROTATE_FAILED} gap. An append-only writer never rotates.
 *   <li>A torn last line (a write cut short, by this or any other writer) is closed with a newline
 *       at session start and in the same write as any later record, so every record this writer
 *       reports durable starts on its own line and parses.
 * </ul>
 *
 * <p>{@code seq} starts at 1 per session and is spent on every attempted record, so a lost record
 * leaves a hole a reader can see. Appends are serialized by this writer's lock.
 */
public final class ExitLedgerWriter {

  /** Archives the current file, retaining older evidence within the family retention bound. A seam for tests of rotation failure. */
  @FunctionalInterface
  interface Rotation {
    void rotate(Path current, Path rotated) throws IOException;
  }

  static final Rotation DELETE_AND_RENAME =
      (current, rotated) -> {
        LedgerRetention.rotate(current, rotated, LedgerRetention.RETAIN_BYTES);
      };

  private static final Set<String> ENVELOPE = Set.of("schemaVersion", "type", "seq", "at", "writer");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Path ledgerDir;
  private final LedgerFamily family;
  private final LedgerWriterId writer;
  private final boolean rotates;
  private final Clock clock;
  private final Rotation rotation;
  private final long sessionRotateBytes;
  private final long hardCapBytes;

  private final Map<LedgerGapReason, PendingGap> pendingGaps = new EnumMap<>(LedgerGapReason.class);
  /** A failed force not yet followed by a successful one: unforced records may not be durable. */
  private volatile PendingGap durabilityGap;
  private boolean durabilityGapRecorded;
  private long seq;
  private volatile boolean healthy = true;

  ExitLedgerWriter(
      Path ledgerDir,
      LedgerFamily family,
      LedgerWriterId writer,
      boolean rotates,
      Clock clock,
      Rotation rotation,
      long sessionRotateBytes,
      long hardCapBytes) {
    this.ledgerDir = Objects.requireNonNull(ledgerDir, "ledgerDir");
    this.family = Objects.requireNonNull(family, "family");
    this.writer = Objects.requireNonNull(writer, "writer");
    this.rotates = rotates;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.rotation = Objects.requireNonNull(rotation, "rotation");
    if (sessionRotateBytes <= 0 || hardCapBytes < sessionRotateBytes) {
      throw new IllegalArgumentException("0 < sessionRotateBytes <= hardCapBytes");
    }
    this.sessionRotateBytes = sessionRotateBytes;
    this.hardCapBytes = hardCapBytes;
  }

  /** The family's single rotating writer (the Engine for children, the supervisor for engine). */
  public static ExitLedgerWriter rotating(
      Path ledgerDir, LedgerFamily family, LedgerWriterId writer, Clock clock) {
    return new ExitLedgerWriter(ledgerDir, family, writer, true, clock, DELETE_AND_RENAME,
        ExitLedger.SESSION_ROTATE_BYTES, ExitLedger.HARD_CAP_BYTES);
  }

  /** A writer that appends but never rotates (the Engine's self-intent records in engine). */
  public static ExitLedgerWriter appendOnly(
      Path ledgerDir, LedgerFamily family, LedgerWriterId writer, Clock clock) {
    return new ExitLedgerWriter(ledgerDir, family, writer, false, clock, DELETE_AND_RENAME,
        ExitLedger.SESSION_ROTATE_BYTES, ExitLedger.HARD_CAP_BYTES);
  }

  public LedgerFamily family() {
    return family;
  }

  /**
   * Starts the writer session, after the owner has reconciled what the last session left open:
   * closes a torn tail and, for a rotating writer, rotates a file over the session size. Never
   * throws; a failure is recorded as a gap or retried on the next append.
   */
  public synchronized void startSession() {
    Path current = family.current(ledgerDir);
    repairTornTail(current);
    if (!rotates) return;
    try {
      if (Files.exists(current) && Files.size(current) > sessionRotateBytes) {
        rotate(current, clock.instant());
      }
    } catch (IOException e) {
      noteGap(LedgerGapReason.ROTATE_FAILED, clock.instant());
    }
  }

  /**
   * Appends one record and forces it to disk.
   *
   * @param fields the type-specific fields, in order; values may be strings, numbers, booleans,
   *     enums, {@link Instant}s, optionals, maps and lists, and {@code null}
   * @return whether the record is durable; {@code false} means it was lost and a gap is pending
   * @throws IllegalArgumentException if the record is of another family, names an envelope field,
   *     or exceeds {@link ExitLedger#MAX_RECORD_BYTES} (caller bugs, not I/O conditions)
   */
  public boolean append(LedgerRecordType type, Map<String, ?> fields) {
    return append(type, fields, true);
  }

  /**
   * Appends one record without forcing it: the line reaches the operating system before this
   * returns, so it survives this process ending, but not necessarily a host crash. For records
   * whose loss a reader reconstructs from earlier forced records (a helper window's end).
   */
  public boolean appendUnforced(LedgerRecordType type, Map<String, ?> fields) {
    return append(type, fields, false);
  }

  private synchronized boolean append(LedgerRecordType type, Map<String, ?> fields, boolean force) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(fields, "fields");
    if (!type.belongsTo(family)) {
      throw new IllegalArgumentException(type.token() + " is not a " + family + " record");
    }
    for (String key : fields.keySet()) {
      if (ENVELOPE.contains(key)) throw new IllegalArgumentException("reserved field: " + key);
    }
    Instant now = clock.instant();
    // Size and cap are decided on the record as it will be written; seq is assigned after rotation
    // so a gap that rotation itself creates still precedes the record in seq order.
    byte[] probe = encode(type, fields, now, seq + pendingGaps.size() + 2);
    if (probe.length > ExitLedger.MAX_RECORD_BYTES) {
      throw new IllegalArgumentException(
          type.token() + " record is " + probe.length + " bytes; the limit is "
              + ExitLedger.MAX_RECORD_BYTES);
    }
    Path current = family.current(ledgerDir);
    if (rotates) rotateIfOverCap(current, probe.length, now);

    List<Line> lines = new ArrayList<>();
    for (Map.Entry<LedgerGapReason, PendingGap> gap : pendingGaps.entrySet()) {
      Map<String, Object> gapFields = new LinkedHashMap<>();
      gapFields.put("reason", gap.getKey().name());
      gapFields.put("count", gap.getValue().count());
      gapFields.put("since", gap.getValue().since());
      lines.add(new Line(gap.getKey(), encode(LedgerRecordType.LEDGER_GAP, gapFields, now, ++seq)));
    }
    if (durabilityGap != null && !durabilityGapRecorded) {
      lines.add(new Line(LedgerGapReason.FORCE_FAILED,
          encode(LedgerRecordType.LEDGER_GAP, durabilityGapFields(null), now, ++seq)));
    }
    lines.add(new Line(null, encode(type, fields, now, ++seq)));

    // Another writer may have left a torn line since the last append; close it in the same write.
    if (endsTorn(current)) lines.set(0, lines.get(0).afterNewline());
    for (Line line : lines) {
      try {
        writeLine(current, line.bytes(), force);
      } catch (IOException e) {
        noteGap(LedgerGapReason.WRITE_FAILED, now);
        healthy = false;
        ExitLedgerHealth.failed(this, ledgerDir, now, e);
        return false;
      }
      if (line.gap() == LedgerGapReason.FORCE_FAILED) durabilityGapRecorded = true;
      else if (line.gap() != null) pendingGaps.remove(line.gap());
    }
    healthy = true;
    ExitLedgerHealth.recovered(this);
    return true;
  }

  /**
   * Forces what {@link #appendUnforced} wrote to disk: one force of the current file, and of the
   * newest archive in case a rotation moved unforced lines there. A group of unforced records
   * costs one force instead of one each.
   *
   * <p>A failed force opens a named durability gap: a {@code ledger-gap} record with reason {@code
   * FORCE_FAILED}, written as soon as a write succeeds, and the writer is not {@link #healthy()}.
   * The gap stays open, and the caller keeps its records pending, until a later force succeeds;
   * then a forced {@code FORCE_FAILED} record with {@code clearedAt} closes it.
   */
  public synchronized boolean flush() {
    boolean durable = true;
    for (Path file : List.of(family.current(ledgerDir), family.rotated(ledgerDir))) {
      try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
        channel.force(false);
      } catch (NoSuchFileException absent) {
        // nothing written there
      } catch (IOException e) {
        durable = false;
      }
    }
    Instant now = clock.instant();
    if (!durable) {
      durabilityGap = durabilityGap == null ? new PendingGap(1, LedgerTimestamps.format(now))
          : new PendingGap(durabilityGap.count() + 1, durabilityGap.since());
      if (!durabilityGapRecorded) durabilityGapRecorded = writeDurabilityGap(now, null);
    } else if (durabilityGap != null && writeDurabilityGap(now, now)) {
      durabilityGap = null;
      durabilityGapRecorded = false;
    }
    return durable;
  }

  /**
   * Writes the durability gap's record: unforced while it is open (the force just failed), forced
   * when {@code clearedAt} closes it, so the close is written only when it is itself durable. An
   * open record that cannot be written now is written before the next record.
   */
  private boolean writeDurabilityGap(Instant now, Instant clearedAt) {
    Path current = family.current(ledgerDir);
    Line line = new Line(null, encode(LedgerRecordType.LEDGER_GAP, durabilityGapFields(clearedAt), now, ++seq));
    if (endsTorn(current)) line = line.afterNewline();
    try {
      writeLine(current, line.bytes(), clearedAt != null);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private Map<String, Object> durabilityGapFields(Instant clearedAt) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("reason", LedgerGapReason.FORCE_FAILED.name());
    fields.put("count", durabilityGap.count());
    fields.put("since", durabilityGap.since());
    if (clearedAt != null) fields.put("clearedAt", LedgerTimestamps.format(clearedAt));
    return fields;
  }

  /**
   * Whether the last write succeeded, no gap is waiting to be written and no durability gap is
   * open.
   */
  public boolean healthy() {
    return healthy && durabilityGap == null;
  }

  /**
   * Forces a blank delimiter to the actual ledger file to check reservation storage. Blank lines
   * are ignored by every v1 reader: this proves write readiness without publishing an exit intent.
   * A successful probe does not clear gaps; the next real record must durably flush them first.
   */
  public synchronized boolean probeWritable() {
    try {
      writeLine(family.current(ledgerDir), new byte[] {'\n'}, true);
      return true;
    } catch (IOException failure) {
      Instant now = clock.instant();
      noteGap(LedgerGapReason.WRITE_FAILED, now);
      healthy = false;
      ExitLedgerHealth.failed(this, ledgerDir, now, failure);
      return false;
    }
  }

  private void rotateIfOverCap(Path current, int recordBytes, Instant now) {
    long size;
    try {
      size = Files.size(current);
    } catch (NoSuchFileException e) {
      return;
    } catch (IOException e) {
      return; // the append itself will fail and say so
    }
    long gapBytes = 256L * pendingGaps.size();
    if (size + gapBytes + recordBytes > hardCapBytes) rotate(current, now);
  }

  private void rotate(Path current, Instant now) {
    try {
      rotation.rotate(current, family.rotated(ledgerDir));
    } catch (IOException | RuntimeException e) {
      // Keep appending to the oversized file rather than lose records; say so in the ledger.
      noteGap(LedgerGapReason.ROTATE_FAILED, now);
    }
  }

  private void repairTornTail(Path current) {
    if (!endsTorn(current)) return;
    try {
      writeLine(current, new byte[] {'\n'}, true);
    } catch (IOException e) {
      healthy = false; // the next append closes it in the same write as its record
    }
  }

  /**
   * Whether the file ends without a newline. An unreadable file counts as torn: the extra newline
   * is an empty line a reader skips, while a missing one would merge the record into a torn line.
   */
  private static boolean endsTorn(Path file) {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      long size = channel.size();
      if (size == 0) return false;
      ByteBuffer last = ByteBuffer.allocate(1);
      channel.read(last, size - 1);
      return last.get(0) != '\n';
    } catch (NoSuchFileException e) {
      return false;
    } catch (IOException e) {
      return true;
    }
  }

  private void writeLine(Path file, byte[] bytes, boolean force) throws IOException {
    Path runtime = ledgerDir.toAbsolutePath().getParent();
    if (ledgerDir.getFileName().toString().equals("exit-ledger")
        && runtime != null && runtime.getFileName() != null
        && runtime.getFileName().toString().equals("runtime")) {
      Files.createDirectories(runtime.getParent());
      io.justsearch.configuration.persistence.OwnerOnlyDirectory.ensureOwnerOnlyDirectory(runtime);
    }
    Files.createDirectories(ledgerDir);
    try (FileChannel channel =
        FileChannel.open(
            file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
      if (force) channel.force(false);
    }
  }

  private void noteGap(LedgerGapReason reason, Instant now) {
    pendingGaps.merge(reason, new PendingGap(1, LedgerTimestamps.format(now)),
        (old, added) -> new PendingGap(old.count() + 1, old.since()));
  }

  private byte[] encode(LedgerRecordType type, Map<String, ?> fields, Instant at, long recordSeq) {
    ObjectNode node = JSON.createObjectNode();
    node.put("schemaVersion", ExitLedger.SCHEMA_VERSION);
    node.put("type", type.token());
    node.put("seq", recordSeq);
    node.put("at", LedgerTimestamps.format(at));
    ObjectNode writerNode = node.putObject("writer");
    writerNode.put("role", writer.role().token());
    writerNode.put("pid", writer.pid());
    writerNode.put("startedAt", LedgerTimestamps.format(writer.startedAt()));
    if (writer.processIdentity() != null)
      writerNode.set("processIdentity", JSON.valueToTree(writer.processIdentity()));
    for (Map.Entry<String, ?> field : fields.entrySet()) {
      node.set(field.getKey(), JSON.valueToTree(wire(field.getValue())));
    }
    return (JSON.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  /** Converts the value shapes the record API accepts to plain JSON-ready values. */
  private static Object wire(Object value) {
    if (value instanceof Instant instant) return LedgerTimestamps.format(instant);
    if (value instanceof Optional<?> optional) return optional.map(ExitLedgerWriter::wire).orElse(null);
    if (value instanceof OptionalInt optional) return optional.isPresent() ? optional.getAsInt() : null;
    if (value instanceof OptionalLong optional) {
      return optional.isPresent() ? optional.getAsLong() : null;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach((k, v) -> out.put(String.valueOf(k), wire(v)));
      return out;
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(ExitLedgerWriter::wire).toList();
    }
    return value;
  }

  private record PendingGap(long count, String since) {}

  /** One encoded line; {@code gap} names the pending gap it writes, or null for the record. */
  private static final class Line {
    private final LedgerGapReason gap;
    private final byte[] bytes;

    Line(LedgerGapReason gap, byte[] bytes) {
      this.gap = gap;
      this.bytes = bytes;
    }

    LedgerGapReason gap() {
      return gap;
    }

    byte[] bytes() {
      return bytes;
    }

    /** This line with a newline in front, closing a torn line before it. */
    Line afterNewline() {
      byte[] prefixed = new byte[bytes.length + 1];
      prefixed[0] = '\n';
      System.arraycopy(bytes, 0, prefixed, 1, bytes.length);
      return new Line(gap, prefixed);
    }
  }
}
