package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.SyncDirectoryResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 626 §Axis-C — a force=false reconcile's delete-detection outcome must update the per-root
 * verification state: a cap-skipped scan marks UNVERIFIED, a clean full sync clears it, and a
 * transient user-activity skip leaves the prior state untouched (it is not a verification result).
 */
final class SyncOpsReconcileVerificationTest {

  private final Path root = Path.of("/watched/docs").toAbsolutePath().normalize();

  /** Records (root, unverified) recorder calls. */
  private record Call(Path root, boolean unverified) {}

  private SyncOps syncOpsReturning(SyncDirectoryResponse response, List<Call> sink) {
    IngestRpcExecutor rpc =
        new IngestRpcExecutor() {
          @Override
          @SuppressWarnings("unchecked")
          public <T> T execute(
              String operation,
              KnowledgeClient.RpcDeadlineCategory category,
              java.util.function.Function<IngestServiceCalls, T> rpcFn,
              io.justsearch.core.context.EngineContext engineContext) {
            return (T) response; // canned; ignores the call function
          }
        };
    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    return new SyncOps(
        new io.justsearch.core.execution.TestEngineExecutors(),
        rpc,
        watchedRoots,
        (r, unv) -> sink.add(new Call(r, unv)));
  }

  @Test
  void capSkippedReconcileMarksUnverified() {
    List<Call> calls = new CopyOnWriteArrayList<>();
    SyncDirectoryResponse resp =
        SyncDirectoryResponse.newBuilder().setSkipped(true).setDeleteDetectionUnverified(true).build();
    syncOpsReturning(resp, calls).syncDirectory(root.toString(), /* force= */ false, io.justsearch.app.services.TestEngineContexts.durableInternal());
    assertEquals(1, calls.size(), "must record exactly one verification outcome");
    assertEquals(root, calls.get(0).root());
    assertTrue(calls.get(0).unverified(), "cap-skipped reconcile must mark UNVERIFIED");
  }

  @Test
  void cleanReconcileClearsUnverified() {
    List<Call> calls = new CopyOnWriteArrayList<>();
    SyncDirectoryResponse resp =
        SyncDirectoryResponse.newBuilder().setFilesAdded(2).setFilesDeleted(1).build();
    syncOpsReturning(resp, calls).syncDirectory(root.toString(), /* force= */ false, io.justsearch.app.services.TestEngineContexts.durableInternal());
    assertEquals(1, calls.size());
    assertEquals(false, calls.get(0).unverified(), "a clean full sync must clear UNVERIFIED");
  }

  @Test
  void userActivitySkipDoesNotTouchVerificationState() {
    List<Call> calls = new CopyOnWriteArrayList<>();
    // skipped=true but delete_detection_unverified=false → a transient user-activity yield.
    SyncDirectoryResponse resp = SyncDirectoryResponse.newBuilder().setSkipped(true).build();
    syncOpsReturning(resp, calls).syncDirectory(root.toString(), /* force= */ false, io.justsearch.app.services.TestEngineContexts.durableInternal());
    assertTrue(calls.isEmpty(), "a user-activity skip must NOT update verification state");
  }

  @Test
  void forcedReconcileRecordsVerified() {
    // Tempdoc 626 §Recency (Move C) — a force=true reconcile re-prunes orphans + re-walks the whole
    // root (a full re-converge), so a non-skipped success IS a verification: it clears UNVERIFIED and
    // (via WatchedRootsState) stamps lastVerifiedAt. This is what makes the scoped "Verify this folder"
    // recovery (core.reconcile-root) refresh the per-root state.
    List<Call> calls = new CopyOnWriteArrayList<>();
    SyncDirectoryResponse resp = SyncDirectoryResponse.newBuilder().setFilesAdded(5).build();
    syncOpsReturning(resp, calls).syncDirectory(root.toString(), /* force= */ true, io.justsearch.app.services.TestEngineContexts.durableInternal());
    assertEquals(1, calls.size(), "a force=true success must record a verification result");
    assertEquals(false, calls.get(0).unverified(), "force=true re-converge clears UNVERIFIED");
  }

  @Test
  void forcedReconcileSkippedDoesNotRecord() {
    // Defensive: a force=true reconcile that still reports skipped (e.g. walk aborted) is not a
    // verification result and must leave the prior state untouched.
    List<Call> calls = new CopyOnWriteArrayList<>();
    SyncDirectoryResponse resp = SyncDirectoryResponse.newBuilder().setSkipped(true).build();
    syncOpsReturning(resp, calls).syncDirectory(root.toString(), /* force= */ true, io.justsearch.app.services.TestEngineContexts.durableInternal());
    assertTrue(calls.isEmpty(), "a skipped force=true reconcile must NOT record a verification");
  }
}
