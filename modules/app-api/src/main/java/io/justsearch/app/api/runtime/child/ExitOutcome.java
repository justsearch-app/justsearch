/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The classified end of one held child, as recorded in its {@code child-exit} record.
 *
 * @param exitedAt the OS exit time; empty when it is unknown
 * @param phase the lifecycle phase the child was in when it exited
 * @param ledgerDurable false when a ledger write for this child failed and a gap was recorded
 */
public record ExitOutcome(
    String childId,
    ChildKind kind,
    ExitClass exitClass,
    Optional<StopReason> reason,
    Optional<StopDetail> detail,
    Optional<StopMode> mode,
    OptionalInt code,
    Optional<Instant> exitedAt,
    Instant observedAt,
    Optional<Instant> requestedAt,
    Optional<Instant> terminateCalledAt,
    TerminateResult terminateResult,
    boolean forced,
    Phase phase,
    boolean ledgerDurable) {

  public ExitOutcome {
    Objects.requireNonNull(childId, "childId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(exitClass, "exitClass");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(detail, "detail");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(exitedAt, "exitedAt");
    Objects.requireNonNull(observedAt, "observedAt");
    Objects.requireNonNull(requestedAt, "requestedAt");
    Objects.requireNonNull(terminateCalledAt, "terminateCalledAt");
    Objects.requireNonNull(terminateResult, "terminateResult");
    Objects.requireNonNull(phase, "phase");
    if (exitClass == ExitClass.REQUESTED && reason.isEmpty()) {
      throw new IllegalArgumentException("a REQUESTED exit names the reason it was requested for");
    }
    if (terminateCalledAt.isPresent() == (terminateResult.status() == TerminateResult.Status.NOT_CALLED)) {
      throw new IllegalArgumentException(
          "terminateCalledAt is present exactly when a terminate call was made");
    }
  }

  /** The lifecycle phase a child was in when it exited. */
  public enum Phase {
    STARTING,
    RUNNING,
    STOPPING
  }
}
