/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

/**
 * #758 — the periodic cycle lists the watched roots when
 * it begins, then walks one and asks every other root's volume. A root the person removes during
 * the cycle is not asked: asking the Worker about a removed root would record the volume at its
 * path, and the folder added again later would be judged against that.
 *
 * <p>The periodic cycle is the product's own scheduled task, captured from its scheduler and run by
 * the test. The person's removal of a root lands while the cycle's walk is with the Worker.
 */
final class PeriodicCycleRemovedRootTest {
  @TempDir Path tempDir;

  @Test
  void aRootRemovedDuringTheCycleIsNotAskedForItsVolume() throws Exception {
    Map<Path, Instant> watched = new ConcurrentHashMap<>();
    var state = new WatchedRootsState(
        watched, new WatchedRootsStore(tempDir.resolve("watched_roots.json"), null));
    List<Path> roots = new CopyOnWriteArrayList<>();
    for (String name : List.of("letters", "photos", "music")) {
      Path root = Files.createDirectories(tempDir.resolve(name)).toAbsolutePath().normalize();
      state.register(root, "documents", false);
      state.markIndexed(root);
      roots.add(root);
    }
    List<Path> walked = new CopyOnWriteArrayList<>();
    List<Path> asked = new CopyOnWriteArrayList<>();
    Path[] removedDuringCycle = new Path[1];
    IngestServiceCalls worker = mock(IngestServiceCalls.class, call -> answer(
        call, walked, asked, synced -> {
          // The person removes another root while this cycle's walk is with the Worker.
          Path other = roots.stream().filter(r -> !r.equals(synced)).findFirst().orElseThrow();
          removedDuringCycle[0] = other;
          state.removeRootAndPersist(other);
        }));
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

    assertEquals(1, walked.size(), "precondition: one root is walked this cycle");
    assertTrue(removedDuringCycle[0] != null, "precondition: a root was removed during the cycle");
    assertTrue(asked.stream().noneMatch(removedDuringCycle[0]::equals),
        "the root removed during the cycle is not asked for its volume; asked: " + asked);
    assertEquals(1, asked.size(), "the root still watched is asked as before; asked: " + asked);
    sync.stopPeriodicSync();
  }

  /** The Worker's answers; the first sync runs {@code duringWalk} as the person's removal. */
  private static Object answer(InvocationOnMock call, List<Path> walked, List<Path> asked,
      java.util.function.Consumer<Path> duringWalk) {
    switch (call.getMethod().getName()) {
      case "syncDirectory" -> {
        SyncDirectoryRequest request = call.getArgument(0);
        Path root = Path.of(request.getRootPath()).toAbsolutePath().normalize();
        walked.add(root);
        if (walked.size() == 1) {
          duringWalk.accept(root);
        }
        return SyncDirectoryResponse.newBuilder().build();
      }
      case "refusesRootVolume" -> {
        String rootPath = call.getArgument(0);
        asked.add(Path.of(rootPath).toAbsolutePath().normalize());
        return false;
      }
      case "rootVolumeRefusal" -> {
        // Round 8: the same volume check, answered with its reason (none here).
        String rootPath = call.getArgument(0);
        asked.add(Path.of(rootPath).toAbsolutePath().normalize());
        return "";
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
