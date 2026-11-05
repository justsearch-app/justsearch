/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.app.inference.InferenceLifecycleManager;
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
 * (design D4, fourth decision 2a; repair round 2): Best match first is unloaded only when the
 * Worker reports no live query-role reranker session; the answer model is unloaded only when no
 * process this Engine started may still hold it (a managed llama-server not seen to exit, also
 * after a failed stop, or whose descendants are not confirmed ended; a self-test candidate running
 * or outliving its test), no picture-reading, self-test or install hold is open, and no answer
 * server the user runs is configured or in use. The answer server's mode is never trusted for
 * this: a failed stop leaves it offline while the process lives. Anything that cannot be confirmed
 * refuses.
 */
public final class ModelFileUsage {
  private ModelFileUsage() {}

  /**
   * The probe as the service composition binds it over the answer runtime.
   *
   * @param worker the Worker's last reported ONNX sessions; empty until it has answered
   * @param answerRuntime the answer runtime, or {@code null} when this build has none
   * @param holds the chat controller's holds, or {@code null} when it has none
   */
  public static Function<String, Optional<String>> probe(WorkerFeatureCache worker,
      InferenceLifecycleManager answerRuntime, ChatHolds holds) {
    return probe(worker,
        answerRuntime == null ? Optional::empty : answerRuntime::answerFilesInUse,
        () -> answerRuntime != null && answerRuntime.isUsingExternalLlamaServer(), holds);
  }

  /**
   * Builds the probe over the live owners.
   *
   * @param answerProcesses why a process this Engine started may still hold the answer model
   * @param externalServer whether a user-run answer server is configured or in use
   */
  static Function<String, Optional<String>> probe(WorkerFeatureCache worker,
      Supplier<Optional<String>> answerProcesses, BooleanSupplier externalServer, ChatHolds holds) {
    return packageId -> switch (packageId) {
      case "reranker" -> reranker(worker == null ? List.of() : worker.getOnnxModels());
      case "chat" -> answers(answerProcesses, externalServer, holds);
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

  static Optional<String> answers(Supplier<Optional<String>> answerProcesses,
      BooleanSupplier externalServer, ChatHolds holds) {
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
    try {
      Optional<String> processes = answerProcesses == null ? Optional.empty() : answerProcesses.get();
      return processes == null ? Optional.empty() : processes;
    } catch (RuntimeException unknown) {
      return Optional.of("JustSearch can't confirm nothing uses these files yet - try again in a moment");
    }
  }
}
