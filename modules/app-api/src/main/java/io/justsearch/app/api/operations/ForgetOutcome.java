/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.justsearch.agent.api.registry.Nullable;
import io.justsearch.agent.api.registry.PreciseWire;
import io.justsearch.configuration.excludes.LiteralExclusion;
import java.util.Objects;

/** UI-only current observation. No removal counts, server tokens or internal progress vocabulary. */
public record ForgetOutcome(
    @Nullable String operationKey,
    @Nullable String path,
    @Nullable LiteralExclusion.Scope scope,
    @JsonProperty(required = true) Status status,
    @JsonProperty(required = true) Removal removal,
    @JsonProperty(required = true) KeepOut keepOut,
    @JsonProperty(required = true) Reason reason,
    @JsonProperty(required = true) String explanation) implements PreciseWire {
  public enum Status { COMPLETE, INCOMPLETE, REFUSED, UNKNOWN, SUPERSEDED, WITHDRAWN }
  public enum Removal { CONFIRMED, UNCONFIRMED, UNKNOWN }
  public enum KeepOut { INSTALLED, NOT_REQUIRED, NOT_INSTALLED, RELEASED, UNKNOWN }
  public enum Reason { NONE, INDEX_UNAVAILABLE, REBUILD_ACTIVE, SETTINGS_UNAVAILABLE,
    POLICY_OVERRIDDEN, OLD_WORK, AUTHORITY_CHANGED, REMOVAL_FAILED, OUTCOME_UNCONFIRMED,
    SCOPE_CHANGED, ENTRY_REMOVED, WITHDRAWN, WITHDRAWAL_PENDING }

  public ForgetOutcome {
    if (operationKey != null) OperationKeys.timestampMillis(operationKey);
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(removal, "removal");
    Objects.requireNonNull(keepOut, "keepOut");
    Objects.requireNonNull(reason, "reason");
    if (explanation == null || explanation.isBlank() || explanation.length() > 2048) {
      throw new IllegalArgumentException("Forget outcome requires a bounded explanation");
    }
    if (status == Status.COMPLETE && (path == null || scope == null || removal != Removal.CONFIRMED)) {
      throw new IllegalArgumentException("Forget completion requires confirmed removal");
    }
  }

  @Override public String toString() { return "ForgetOutcome[status=" + status + "]"; }
}
