/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Feeds {@link ForegroundLoad} from the Engine's in-process port adapter (lane F stage A item A4).
 *
 * <p>{@code ForegroundLoad} (tempdoc 885 item 3) is the gauge of how many user-waiting calls are
 * executing right now. It paces indexing ({@code IndexingPacing}) and gates the reopen-on-demand
 * seam ({@code KnowledgeServer} passes {@code () -> foregroundLoad.inFlight() > 0} to
 * {@code LuceneRuntimeBuilder.withForegroundActive}). Today its <b>only</b> producer is
 * {@code ForegroundLoadInterceptor}, a gRPC {@code ServerInterceptor}; item A9 deletes that
 * interceptor together with the gRPC server, and this gate is what keeps the gauge fed once the
 * ports are direct calls.
 *
 * <p><b>Why here and not in {@code ui} or {@code app-services}.</b> {@code ForegroundLoad} lives in
 * {@code io.justsearch.indexerworker.loop.pacing}, which ArchUnit rule 6b (item A2) closes to
 * everything outside {@code io.justsearch.app.engine..}, {@code io.justsearch.indexerworker..} and
 * {@code io.justsearch.adapters..}. The composition root is therefore the only place above the
 * worker half that may touch the gauge, which is also exactly where the nine calls the interceptor
 * covers will pass through at A6.
 *
 * <p><b>One instance, not two.</b> The gauge is process-scoped and owned by {@code KnowledgeServer}
 * (field at {@code KnowledgeServer.java:166}), which hands it to {@code IndexingPacing}
 * ({@code :1120}); {@code IndexingPacing.foregroundLoad()} is the accessor the root reads it back
 * through. This class deliberately has no no-arg constructor: a gate holding its own fresh
 * {@code ForegroundLoad} would be a second gauge that nothing paces off, which is the silent
 * failure mode {@code KnowledgeServer.java:160-165} already warns about for per-appServices gauges.
 *
 * <p><b>Not wired yet, on purpose.</b> A4 builds the gate and its drift test; item <b>A6</b> — the
 * in-process {@code SearchPort} adapter over {@code WorkerAppServices} — is what calls it. Until
 * then the interceptor remains the live producer and {@link #foregroundOperations()} is pinned
 * against {@code ForegroundLoadInterceptor.foregroundMethods()} by
 * {@code ForegroundLoadGateTest}, so a rename or an added RPC cannot silently desynchronise the two
 * producers during the transition.
 *
 * <p>Thread-safe and reentrancy-safe: the gate holds no state of its own, and every increment is
 * paired with exactly one decrement in a {@code finally}, so a normal return, a thrown exception,
 * an {@link java.util.concurrent.CancellationException} and an {@link Error} all balance the gauge.
 */
public final class ForegroundLoadGate {

  /**
   * The nine foreground operations, mirrored from {@code ForegroundLoadInterceptor} as bare RPC
   * method names (the interceptor keys on full gRPC method names, which do not survive the wire
   * deletion).
   *
   * <p>The two deliberate exclusions carry over unchanged: {@code IndexStatus} (an
   * {@code IngestService} poll the Head runs on a timer — counting an observer as foreground is the
   * defect tempdoc 885 item 3 removed) and {@code SearchService/ListAllDocumentIds} (paged by the
   * background GPL job coordinator, not by a person waiting for an answer).
   */
  private static final Set<String> FOREGROUND_OPERATIONS =
      Set.of(
          "Search",
          "Suggest",
          "FetchDocuments",
          "FetchDocumentSlice",
          "RetrieveContext",
          "MatchCitations",
          "ListFolders",
          "ListFolderFiles",
          "Rerank");

  private final ForegroundLoad load;

  /**
   * @param load the process-wide gauge — the same instance the indexing loop paces off, never a
   *     fresh one
   */
  public ForegroundLoadGate(ForegroundLoad load) {
    this.load = Objects.requireNonNull(load, "load");
  }

  /** The exact set of operation names that count as foreground. */
  public static Set<String> foregroundOperations() {
    return FOREGROUND_OPERATIONS;
  }

  /** Whether {@code operation} is one of the nine the user waits on. */
  public static boolean isForeground(String operation) {
    return FOREGROUND_OPERATIONS.contains(operation);
  }

  /**
   * Runs {@code body} as {@code operation}, counting it against the gauge when the operation is
   * foreground and passing it straight through when it is not.
   *
   * @param operation the port operation name (see {@link #foregroundOperations()})
   * @param body the call to execute
   * @return whatever {@code body} returns
   */
  public <T> T call(String operation, Supplier<T> body) {
    if (!isForeground(operation)) {
      return body.get();
    }
    load.started();
    try {
      return body.get();
    } finally {
      load.finished();
    }
  }

  /** Void form of {@link #call(String, Supplier)}. */
  public void run(String operation, Runnable body) {
    if (!isForeground(operation)) {
      body.run();
      return;
    }
    load.started();
    try {
      body.run();
    } finally {
      load.finished();
    }
  }
}
