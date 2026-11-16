/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.services.worker.EngineRefusals;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.scheduling.IndexingHold;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@code core.indexing-read-now}: read the files the reading rules (hours, battery) hold back, once,
 * without changing the rules. The receipt says the permission was installed or joined, never that
 * files were read. The release lives in Engine memory; a keyed replay returns the recorded receipt
 * and never installs it again.
 */
public final class ReadWaitingFilesHandler implements OperationHandler {
  private final Supplier<KnowledgeClient> clients;

  public ReadWaitingFilesHandler(Supplier<KnowledgeClient> clients) {
    this.clients = Objects.requireNonNull(clients, "clients");
  }

  @Override public OperationResult execute(String argumentsJson, EngineContext context) {
    KnowledgeClient client = clients.get();
    IndexingHold hold = client == null ? null : client.indexingHold();
    if (hold == null) {
      return OperationResult.failure("Reading is unavailable right now", "WORKER_UNAVAILABLE", Map.of(), true);
    }
    // Ready file jobs are read outside the hold's monitor; the hold is checked again on install.
    boolean filesWaiting;
    if (hold.activeRelease() != null || !holdsByRulesAlone(hold.snapshot())) {
      filesWaiting = false;
    } else {
      try {
        filesWaiting = client.getStatus(context).getMigration().getPendingReadyJobsCount() > 0;
      } catch (RuntimeException failure) {
        EngineRefusals.rethrow(failure);
        return OperationResult.failure("Couldn't check the waiting files", "WORKER_UNAVAILABLE", Map.of(), true);
      }
    }
    var result = hold.readWaitingNow(filesWaiting);
    return switch (result.outcome()) {
      case STARTED -> OperationResult.success("Reading the waiting files", Map.of("outcome", "started"));
      case JOINED -> OperationResult.success("Already reading the waiting files", Map.of("outcome", "joined"));
      case NOTHING_WAITING -> OperationResult.success("Nothing is waiting to be read",
          Map.of("outcome", "nothing-waiting"));
      case NOT_HELD -> OperationResult.success("Reading rules are not holding files back",
          Map.of("outcome", "not-held", "reason", notHeldReason(result.reason())));
    };
  }

  private static boolean holdsByRulesAlone(IndexingHold.Snapshot snapshot) {
    return !snapshot.reasons().isEmpty() && !snapshot.reasons().contains("USER_PAUSED")
        && !snapshot.reasons().contains("SETTINGS_RECOVERY");
  }

  private static String notHeldReason(String reason) {
    if ("USER_PAUSED".equals(reason)) return "paused";
    if ("SETTINGS_RECOVERY".equals(reason)) return "recovery";
    return "not-held";
  }
}
