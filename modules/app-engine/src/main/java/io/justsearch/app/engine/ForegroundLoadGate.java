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
 * {@code LuceneRuntimeBuilder.withForegroundActive}). Until item A9 its only producer was
 * {@code ForegroundLoadInterceptor}, a gRPC {@code ServerInterceptor}; A9 deleted that interceptor
 * together with the gRPC server, and this gate is what keeps the gauge fed now that the ports are
 * direct calls.
 *
 * <p><b>Why here and not in {@code ui} or {@code app-services}.</b> {@code ForegroundLoad} lives in
 * {@code io.justsearch.indexerworker.loop.pacing}, which ArchUnit rule 6b (item A2) closes to
 * everything outside {@code io.justsearch.app.engine..}, {@code io.justsearch.indexerworker..} and
 * {@code io.justsearch.adapters..}. The composition root is therefore the only place above the
 * worker half that may touch the gauge, and since item A6 it is also exactly where the nine calls
 * the interceptor used to cover pass through.
 *
 * <p><b>One instance, not two.</b> The gauge is process-scoped and owned by {@code KnowledgeServer}
 * (a {@code private final} field), which hands it to the {@code IndexingPacing} it builds during
 * {@code start()}. The root reads it through {@code KnowledgeServer.foregroundLoad()}, the accessor
 * onto that field — <b>not</b> through {@code IndexingPacing.foregroundLoad()}, which before
 * {@code start()} answers from the {@code IndexingPacing.unthrottled()} placeholder and hands back a
 * fresh gauge nothing paces off. This class deliberately has no no-arg constructor for the same
 * reason: a gate holding its own {@code ForegroundLoad} is the silent failure mode
 * {@code KnowledgeServer} already warns about for per-appServices gauges.
 *
 * <p><b>How it came to be wired.</b> A4 built the gate and its drift test; item <b>A6</b> — the
 * in-process {@code SearchPort} adapter over {@code WorkerAppServices} — is what calls it. Between
 * A4 and A6 the interceptor was still the live producer, so {@link #foregroundOperations()} was
 * pinned against {@code ForegroundLoadInterceptor.foregroundMethods()} by
 * {@code ForegroundLoadGateTest} to keep the two producers from desynchronising during the
 * transition. Item A9 deleted the interceptor and with it that comparison; see
 * {@code ForegroundLoadGateTest} for what guards the set now.
 *
 * <p>Thread-safe and <b>balance-safe</b>: the gate holds no state of its own, and every increment is
 * paired with exactly one decrement in a {@code finally}, so a normal return, a thrown exception,
 * an {@link java.util.concurrent.CancellationException} and an {@link Error} all balance the gauge.
 *
 * <p><b>It is not reentrancy-safe, and must not be.</b> A nested gated call would count the same
 * user-waiting operation twice — a {@code Search} that internally reranks would read as two
 * foreground calls and over-throttle indexing. The gauge is a count of user-waiting operations, not
 * of method entries, so the caller wraps at exactly ONE layer:
 * {@code EngineKnowledgeClient.executeSearchRpc}, which is the boundary the deleted
 * {@code ForegroundLoadInterceptor} sat on. Do not add a second wrap deeper in.
 */
public final class ForegroundLoadGate {

  /**
   * The foreground operations, as the <b>operation labels the ops layer actually passes</b> to
   * {@code SearchRpcExecutor.execute(operation, category, call)} — not as RPC method names.
   *
   * <p><b>This was wrong from A6 until the A6-A9 review, and the gauge was dead the whole time.</b>
   * The set was mirrored from {@code ForegroundLoadInterceptor}, which keyed on gRPC method names
   * ({@code SearchService/Search} -> {@code "Search"}). But the interceptor sat on the transport,
   * where the method name is what it saw; the gate sits on the executor seam, where the label is
   * {@code SearchRpcOps}'s own lower-camel string ({@code "search"}). {@code isForeground} compared
   * PascalCase against lowerCamel, never matched, and every call went through ungated — so from A6
   * (gate wired) through A9 (interceptor deleted) nothing produced the gauge at all and indexing
   * never yielded to a user waiting on a search.
   *
   * <p>Neither the unit test nor the A6 acceptance test could see it: both called the gate directly
   * with the names the gate itself declared. What catches it is asserting that a real
   * {@code client.search(...)} moves {@code ForegroundLoad.startedTotal}, which
   * {@code EngineRootInProcessPortsTest} now does. Reference case: {@code wrong-gate}.
   *
   * <p><b>Ten labels for nine RPCs.</b> {@code searchVector} is a separate ops-layer entry point
   * that issues the same {@code SearchService/Search} RPC ({@code SearchRpcOps.java:95-105}), so
   * the interceptor counted it and the gate must too — mapping it to nothing would have quietly
   * un-counted every vector search.
   *
   * <p>The two deliberate exclusions carry over unchanged: {@code indexStatus} (an ingest poll the
   * Head runs on a timer — counting an observer as foreground is the defect tempdoc 885 item 3
   * removed) and {@code listAllDocumentIds} (paged by the background GPL job coordinator, not by a
   * person waiting for an answer).
   */
  private static final Set<String> FOREGROUND_OPERATIONS =
      Set.of(
          "search",
          "searchVector",
          "suggest",
          "fetchDocuments",
          "fetchDocumentSlice",
          "retrieveContext",
          "matchCitations",
          "listFolders",
          "listFolderFiles",
          "rerank");

  private final ForegroundLoad load;

  /**
   * @param load the process-wide gauge — the same instance the indexing loop paces off, never a
   *     fresh one
   */
  public ForegroundLoadGate(ForegroundLoad load) {
    this.load = Objects.requireNonNull(load, "load");
  }

  /** The exact set of operation labels that count as foreground. */
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
