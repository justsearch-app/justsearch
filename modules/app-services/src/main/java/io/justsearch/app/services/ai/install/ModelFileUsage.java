/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.services.worker.WorkerHost;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What the runtime owners report about a removable part's files, read at each owner's own state
 * (design D4, fourth decision 2a; repair rounds 2 and 4): Best match first is unloaded only when
 * the index half's query owners, asked at the call, say none of them holds a reranker session (the
 * serving view's, a retired view issued work has not released, forked leases included, or one
 * being prepared); a cached health observation is never trusted for this. The answer model is
 * unloaded only when no process this Engine started may still hold it (a managed llama-server not seen to exit, also
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
   * @param queryOwners the index half's query owners on Best match first, asked at each call
   * @param answerRuntime the answer runtime, or {@code null} when this build has none
   * @param holds the chat controller's holds, or {@code null} when it has none
   */
  public static Function<String, Optional<String>> probe(Supplier<WorkerHost.RerankerUse> queryOwners,
      InferenceLifecycleManager answerRuntime, ChatHolds holds) {
    return probe(queryOwners,
        answerRuntime == null ? Optional::empty : answerRuntime::answerFilesInUse,
        () -> answerRuntime != null && answerRuntime.isUsingExternalLlamaServer(), holds);
  }

  /**
   * Builds the probe over the live owners.
   *
   * @param answerProcesses why a process this Engine started may still hold the answer model
   * @param externalServer whether a user-run answer server is configured or in use
   */
  static Function<String, Optional<String>> probe(Supplier<WorkerHost.RerankerUse> queryOwners,
      Supplier<Optional<String>> answerProcesses, BooleanSupplier externalServer, ChatHolds holds) {
    return packageId -> switch (packageId) {
      case "reranker" -> reranker(queryOwners);
      case "chat" -> answers(answerProcesses, externalServer, holds);
      default -> Optional.of("These files can't be removed here");
    };
  }

  static Optional<String> reranker(Supplier<WorkerHost.RerankerUse> queryOwners) {
    WorkerHost.RerankerUse use;
    try {
      use = queryOwners == null ? null : queryOwners.get();
    } catch (RuntimeException unknown) {
      return Optional.of("JustSearch can't confirm nothing uses these files yet - try again in a moment");
    }
    if (use == null) return Optional.of("Search is still starting - try again in a moment");
    return switch (use) {
      case FREE -> Optional.empty();
      case HELD -> Optional.of("Best match first is still loaded - try again in a moment");
      case BUSY -> Optional.of("Search is changing its models - try again in a moment");
      case UNKNOWN -> Optional.of("Search is still starting - try again in a moment");
    };
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
