/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopReason;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The Engine's record that it is about to end itself, written to {@code engine.v1.jsonl} before the
 * ordered close so the supervisor can classify the exit as requested (ADR-0051). Not written for a
 * fatal exit, which stays UNEXPECTED. The Engine never rotates this family; the supervisor does.
 *
 * <p>The Engine also counts its own {@code DEVICE_LOST} records here to limit device-lost restarts,
 * so this is the single writer of that reason.
 */
public final class EngineSelfIntentWriter {

  private final ExitLedgerWriter writer;
  private final EngineIdentity engine;

  public EngineSelfIntentWriter(
      Path dataDir, EngineIdentity engine, LedgerWriterId writerId, Clock clock) {
    this(ExitLedgerWriter.appendOnly(
        LedgerFamily.directory(Objects.requireNonNull(dataDir, "dataDir")), LedgerFamily.ENGINE,
        writerId, clock), engine);
  }

  EngineSelfIntentWriter(ExitLedgerWriter writer, EngineIdentity engine) {
    this.writer = Objects.requireNonNull(writer, "writer");
    this.engine = Objects.requireNonNull(engine, "engine");
    if (writer.family() != LedgerFamily.ENGINE) {
      throw new IllegalArgumentException("self-intent records belong to the engine family");
    }
  }

  /**
   * Records the intent and forces it to disk. Never throws on I/O: the exit proceeds either way.
   *
   * @param reason one of the reasons that end the Engine
   * @param expectedCode the exit code the Engine will exit with
   * @return whether the record is durable
   */
  public boolean record(StopReason reason, Optional<StopDetail> detail, int expectedCode) {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(detail, "detail");
    if (!reason.endsEngine()) {
      throw new IllegalArgumentException(reason + " does not end the Engine");
    }
    Map<String, Object> engineFields = new LinkedHashMap<>();
    engineFields.put("pid", engine.pid());
    engineFields.put("creationFileTime", engine.creationFileTime());
    engineFields.put("instanceId", engine.instanceId());
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("engine", engineFields);
    fields.put("reason", reason);
    fields.put("detail", detail);
    fields.put("expectedCode", expectedCode);
    return writer.append(LedgerRecordType.ENGINE_SELF_INTENT, fields);
  }

  /** Whether every self-intent this incarnation wrote is durable. */
  public boolean healthy() {
    return writer.healthy();
  }

  /**
   * The Engine process as the ledger names it.
   *
   * @param creationFileTime the Windows process creation FILETIME (100 ns ticks) as a decimal
   *     string, because it exceeds the integers JSON readers keep exactly; empty off Windows
   */
  public record EngineIdentity(long pid, Optional<String> creationFileTime, String instanceId) {
    private static final Pattern DECIMAL = Pattern.compile("[1-9][0-9]{0,19}");

    public EngineIdentity {
      Objects.requireNonNull(creationFileTime, "creationFileTime");
      Objects.requireNonNull(instanceId, "instanceId");
      if (pid <= 0) throw new IllegalArgumentException("pid must be positive");
      if (instanceId.isBlank()) throw new IllegalArgumentException("instanceId must not be blank");
      creationFileTime.ifPresent(
          value -> {
            if (!DECIMAL.matcher(value).matches()) {
              throw new IllegalArgumentException("creationFileTime must be a decimal: " + value);
            }
          });
    }
  }
}
