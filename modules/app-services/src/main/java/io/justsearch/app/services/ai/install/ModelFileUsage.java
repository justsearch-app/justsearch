/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.services.worker.OnnxModelStatus;
import io.justsearch.app.services.worker.WorkerFeatureCache;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What the runtime owners report about a removable part's files, read at each owner's own state
 * (design D4, fourth decision 2a): Best match first is unloaded only when the Worker reports no
 * live query-role reranker session; the answer model is unloaded only when no managed or user-run
 * answer server runs, and no self-test, picture-reading or install hold is open. Anything that
 * cannot be confirmed refuses.
 */
public final class ModelFileUsage {
  private ModelFileUsage() {}

  /**
   * Builds the probe over the live owners.
   *
   * @param worker the Worker's last reported ONNX sessions; empty until it has answered
   * @param answerServer the answer runtime, or {@code null} when this build has none
   * @param externalServer whether a user-run answer server is configured or in use
   * @param holds the chat controller's holds, or {@code null} when it has none
   */
  public static Function<String, Optional<String>> probe(WorkerFeatureCache worker,
      Supplier<ChatActuator> answerServer, BooleanSupplier externalServer,
      ChatHolds holds) {
    return packageId -> switch (packageId) {
      case "reranker" -> reranker(worker == null ? List.of() : worker.getOnnxModels());
      case "chat" -> answers(answerServer == null ? null : answerServer.get(), externalServer, holds);
      default -> Optional.of("These files can't be removed here");
    };
  }

  static Optional<String> reranker(List<OnnxModelStatus> sessions) {
    if (sessions == null || sessions.isEmpty()) {
      return Optional.of("Search is still starting - try again in a moment");
    }
    for (OnnxModelStatus status : sessions) {
      if ("reranker".equals(status.modelName())) {
        return status.sessionActive()
            ? Optional.of("Best match first is still loaded - try again in a moment")
            : Optional.empty();
      }
    }
    return Optional.of("Search hasn't reported Best match first yet - try again in a moment");
  }

  static Optional<String> answers(ChatActuator server, BooleanSupplier externalServer,
      ChatHolds holds) {
    if (externalServer != null && externalServer.getAsBoolean()) {
      return Optional.of("An answer server you run yourself is configured");
    }
    if (holds != null) {
      for (ChatHolds.OpenHold hold : holds.openHolds()) {
        return Optional.of(switch (hold.kind()) {
          case VDU -> "Picture reading is still using the answer model - try again in a moment";
          case SELF_TEST -> "The answer model is being tested - try again when it finishes";
          case RUNNING, FREEZE -> "A download or Repair is using the answer model - try again when it finishes";
        });
      }
    }
    if (server != null && server.serverRunning()) {
      return Optional.of("The answer model is still loaded - try again in a moment");
    }
    return Optional.empty();
  }
}
