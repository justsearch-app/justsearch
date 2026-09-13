/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import java.util.Optional;
import io.justsearch.agent.api.registry.OperationResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed settings collaborator of the attempt runner; never a producer's terminal writer. */
public interface SettingsCommitOwner {
  enum RecoveryReason { UNREADABLE_WITNESS, CONTRADICTORY_WITNESS, MULTIPLE_ARMED_ROWS, PERSISTENCE_DISABLED }

  /** Bounded Health projection of the first unresolved recovery; settings bytes stay in the store. */
  record RecoveryIssue(RecoveryReason reason, Long operationRecordId) {}

  /** Sticky first recovery issue, published outside the apply mutex; no restart loop at boot. */
  java.util.concurrent.CompletionStage<RecoveryIssue> recoveryIssue();

  /** Opaque identity issued by this owner after durable revision validation, before SQL arming. */
  interface Reservation {}

  /** Prebuilt before replacement and published to the runner at the exact commitment boundary. */
  record Receipt(String operationKey, long acceptedRevision, OperationResult response) {
    public Receipt {
      io.justsearch.app.api.operations.OperationKeys.timestampMillis(operationKey);
      if (acceptedRevision <= 0) throw new IllegalArgumentException("Committed revision must be positive");
      Objects.requireNonNull(response, "response");
      if (!response.success()) throw new IllegalArgumentException("Committed settings require a successful result");
      Map<String, Object> data = new java.util.LinkedHashMap<>(response.structuredData());
      if ((data.containsKey("operationKey") && !operationKey.equals(data.get("operationKey")))
          || (data.containsKey("acceptedRevision") && !Long.valueOf(acceptedRevision).equals(data.get("acceptedRevision")))) {
        throw new IllegalArgumentException("Settings result contradicts its commitment witness");
      }
      data.put("operationKey", operationKey);
      data.put("acceptedRevision", acceptedRevision);
      response = new OperationResult(true, response.message(), response.executionId(), data,
          response.errorCode(), response.errorDetails(), response.retryable());
    }

    public Receipt(String operationKey, long acceptedRevision) {
      this(operationKey, acceptedRevision, OperationResult.success("Settings committed"));
    }
  }

  /** Typed precommit refusal. Only its bounded code/execution id is persisted in the row. */
  final class Refused extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final OperationResult response;

    public Refused(OperationResult response) {
      super(Objects.requireNonNull(response, "response").message());
      if (response.success() || !OperationResult.isDurableOutcomeCode(response.errorCode().orElse(null))) {
        throw new IllegalArgumentException("Settings refusal requires a bounded failure code");
      }
      this.response = response;
    }

    public OperationResult response() { return response; }
  }

  /** Issued only by the runner to its fixed owner; never exposed to a handler. */
  interface AttemptControl {
    void committed(Receipt receipt);
    void uncertain();
  }

  /** Compare both persisted witness fields before the runner may arm its numeric SQL marker. */
  Reservation reserve(long id, String key, SettingsWitness expected);

  /** Prepare, replace and publish synchronously; arbitrary notifications run outside owner locks. */
  void apply(Reservation reservation, UiSettings candidate, AttemptControl control);

  /** Called after durable terminal persistence, before the runner publishes completion futures. */
  void releaseAfterTerminal(long id);

  /** Retain any matching fence and request the composition root's ordered restart outside locks. */
  void retainForRestart(long id, Throwable failure);

  /** Private boot input; the runner reads accepted preparation before calling the owner outside SQL locks. */
  record RecoveryInput(OperationRecord row, Optional<OperationStore.Preparation> preparation) {
    public RecoveryInput {
      Objects.requireNonNull(row, "row");
      Objects.requireNonNull(preparation, "preparation");
    }
  }

  /** Inspect the complete open settings set before any per-row reconciliation or producer starts. */
  void inspectRecovery(List<RecoveryInput> rows);

  OperationAttemptRunner.Reconciliation reconcile(OperationRecord row);
}
