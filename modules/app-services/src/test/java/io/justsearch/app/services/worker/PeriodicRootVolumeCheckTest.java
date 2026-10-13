/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.SyncDirectoryResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

/**
 * Task t-20261013-ef3c41, round 6, S8 — the periodic sync walks one watched root per cycle, so a
 * drive letter that starts holding another volume just after its root's turn would read indexed
 * for up to {@code roots × 60 s}. Each cycle asks every root's volume, and a root the Worker
 * refuses reads unavailable within that cycle while walking stays one root per cycle.
 *
 * <p>The periodic cycle is the product's own scheduled task, captured from its scheduler and run
 * by the test instead of waiting a minute. The Worker answers the way the real one does on the
 * same calls: a sync of a root on another volume is refused with the root-level reason, and its
 * volume check says so; the real Worker's answer on a real other volume is covered by the
 * Worker's and the Engine's SUBST tests.
 */
final class PeriodicRootVolumeCheckTest {
  private static final String ROOT_UNAVAILABLE_REASON = "ROOT_NOT_DIRECTORY";

  @TempDir Path tempDir;

  @Test
  void aSwapOnARootWhoseTurnJustPassedReadsUnavailableWithinOneCycle() throws Exception {
    Map<Path, Instant> watched = new ConcurrentHashMap<>();
    var state = new WatchedRootsState(
        watched, new WatchedRootsStore(tempDir.resolve("watched_roots.json"), null));
    for (String name : List.of("letters", "photos", "music")) {
      Path root = Files.createDirectories(tempDir.resolve(name)).toAbsolutePath().normalize();
      state.register(root, "documents", false);
      state.markIndexed(root);
    }
    Set<Path> onAnotherVolume = ConcurrentHashMap.newKeySet();
    List<Path> walked = new CopyOnWriteArrayList<>();
    IngestServiceCalls worker =
        mock(IngestServiceCalls.class, call -> answer(call, onAnotherVolume, walked));
    IngestRpcExecutor rpc = new IngestRpcExecutor() {
      @Override
      public <T> T execute(String operation, KnowledgeClient.RpcDeadlineCategory category,
          Function<IngestServiceCalls, T> call, EngineContext engineContext) {
        return call.apply(worker);
      }
    };
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    var sync = new SyncOps(executorsWith(scheduler), rpc, watched,
        (root, unverified) -> {}, (root, count) -> {}, state);
    sync.startPeriodicSync();
    ArgumentCaptor<Runnable> cycle = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).scheduleAtFixedRate(cycle.capture(), anyLong(), anyLong(), any());

    cycle.getValue().run();
    assertEquals(1, walked.size(), "precondition: one root is walked per cycle");
    Path justPassed = walked.getFirst();
    assertNull(state.getWalkError(justPassed), "precondition: the walked root reads indexed");
    Instant indexedAt = watched.get(justPassed);
    onAnotherVolume.add(justPassed);
    walked.clear();

    cycle.getValue().run();

    assertEquals(1, walked.size(), "walking stays one root per cycle, the swapped root not among them");
    assertNotEquals(justPassed, walked.getFirst(), "the next cycle walks the next root");
    assertEquals(ROOT_UNAVAILABLE_REASON, state.getWalkError(justPassed),
        "a root whose turn just passed reads unavailable within one cycle of the swap");
    assertEquals(indexedAt, watched.get(justPassed), "its last-indexed time is kept");
    sync.stopPeriodicSync();
  }

  /** The Worker's answers: a root on another volume is refused, every other root is synced. */
  private static Object answer(InvocationOnMock call, Set<Path> onAnotherVolume, List<Path> walked) {
    switch (call.getMethod().getName()) {
      case "syncDirectory" -> {
        SyncDirectoryRequest request = call.getArgument(0);
        Path root = Path.of(request.getRootPath()).toAbsolutePath().normalize();
        walked.add(root);
        if (onAnotherVolume.contains(root)) {
          return SyncDirectoryResponse.newBuilder().setError(ROOT_UNAVAILABLE_REASON
              + ": the folder at this path is not on the volume it was first seen on").build();
        }
        return SyncDirectoryResponse.newBuilder().build();
      }
      case "refusesRootVolume" -> {
        String rootPath = call.getArgument(0);
        return onAnotherVolume.contains(Path.of(rootPath).toAbsolutePath().normalize());
      }
      case "toString" -> {
        return "the Worker";
      }
      default -> throw new AssertionError("unexpected Worker call: " + call.getMethod());
    }
  }

  /** An executor registry whose scheduler hands the periodic task to the test. */
  private static EngineExecutorRegistry executorsWith(ScheduledExecutorService scheduler) {
    EngineExecutorRegistry executors = mock(EngineExecutorRegistry.class);
    EngineExecutorRegistry.Registration registration =
        mock(EngineExecutorRegistry.Registration.class);
    when(executors.limits(any())).thenReturn(new EngineExecutorRegistry.Limits(1, 16));
    when(executors.register(any())).thenReturn(registration);
    when(registration.openScheduled(any())).thenReturn(scheduler);
    return executors;
  }
}
