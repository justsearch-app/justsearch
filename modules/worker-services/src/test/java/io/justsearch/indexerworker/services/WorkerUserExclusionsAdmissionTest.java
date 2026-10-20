/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerUserExclusionsAdmissionTest {
  @TempDir Path root;

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.EnumSource(WorkerScanOps.ScanMode.class)
  void EX1AllScanModesUseSavedPolicyAndEN2KeepOtherFiles(WorkerScanOps.ScanMode mode) throws Exception {
    Files.writeString(root.resolve("private.txt"), "private");
    Path keep = Files.writeString(root.resolve("public.txt"), "public");
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = store("[\"private.txt\"]");
    try {
      List<Path> admitted = new ArrayList<>();
      JobQueue queue = queue(admitted);
      new WorkerScanOps(queue).scan(new WorkerScanOps.ScanRequest(
          root, null, mode, List.of()), progress -> {});
      assertEquals(List.of(keep), admitted, "EX1: saved policy on " + mode);
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void EX1CandidateReconcileHonoursUserExclusions() throws Exception {
    Files.writeString(root.resolve("private.txt"), "private");
    Path keep = Files.writeString(root.resolve("public.txt"), "public");
    ReadPathOps reads = mock(ReadPathOps.class);
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenReturn(new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0L));
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = store("[\"private.txt\"]");
    try {
      var ops = new SyncDirectoryOps(reads, null, null, null,
          IndexingPacing.unthrottled(), null);
      try (var difference = ops.discoverCandidateDifference(root.toString(), true, () -> false)) {
        assertEquals(List.of(keep), difference.additions(),
            "EX1: per-folder reconciliation cannot admit excluded files");
        assertEquals(List.of(), difference.deletions());
      }
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void EX4RootLevelAndNestedGlobMembershipAgree() throws Exception {
    Files.writeString(root.resolve("private.txt"), "private");
    Path nested = Files.createDirectory(root.resolve("nested"));
    Files.writeString(nested.resolve("private.txt"), "private");
    Path keep = Files.writeString(root.resolve("public.txt"), "public");
    List<Path> admitted = new ArrayList<>();
    new WorkerScanOps(queue(admitted)).scan(new WorkerScanOps.ScanRequest(
        root, null, WorkerScanOps.ScanMode.INITIAL, List.of("**/private.txt")), p -> {});
    assertEquals(List.of(keep), admitted,
        "EX4: **/ matches at root as well as nested, as Preview/Apply do");
  }

  @Test
  void EX1LiveCreatesChangesAndMovesUseSavedPolicy() throws Exception {
    Path file = Files.writeString(root.resolve("private.txt"), "private");
    Path keep = Files.writeString(root.resolve("public.txt"), "public");
    List<Path> admitted = new ArrayList<>();
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = store("[\"private.txt\"]");
    try (var watcher = new WorkerMethvinWatcher(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.watcher(),
        queue(admitted), null, path -> {})) {
      var subscription = new RootWatcherRegistry.Subscription(
          root, "docs", new Object(), RootIdentity.capture(root));
      watcher.dispatchEvent(subscription, WorkerMethvinWatcher.Kind.CREATE, file);
      watcher.dispatchEvent(subscription, WorkerMethvinWatcher.Kind.MODIFY, file);
      watcher.dispatchEvent(subscription, WorkerMethvinWatcher.Kind.CREATE, keep);
      assertEquals(List.of(keep), admitted,
          "EX1: create/modify and rename/move destination upserts obey saved policy");
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void EN3NoPatternsPreserveEveryScanMode() throws Exception {
    Path first = Files.writeString(root.resolve("first.txt"), "first");
    Path nested = Files.createDirectory(root.resolve("nested"));
    Path second = Files.writeString(nested.resolve("second.txt"), "second");
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = store("[]");
    try {
      for (var mode : WorkerScanOps.ScanMode.values()) {
        List<Path> admitted = new ArrayList<>();
        new WorkerScanOps(queue(admitted)).scan(new WorkerScanOps.ScanRequest(
            root, null, mode, List.of()), p -> {});
        assertEquals(java.util.Set.of(first, second), java.util.Set.copyOf(admitted));
      }
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  private static ConfigStore store(String patterns) {
    var installed = new ConfigStore(ResolvedConfig.builder()
        .putDefault("justsearch.ui.exclude_patterns", patterns).build());
    ConfigStore.setGlobal(installed);
    return installed;
  }

  @SuppressWarnings("unchecked")
  private static JobQueue queue(List<Path> admitted) {
    JobQueue queue = mock(JobQueue.class);
    when(queue.enqueueEntries(any(), nullable(String.class))).thenAnswer(call -> {
      List<JobQueue.EnqueueEntry> entries = call.getArgument(0);
      entries.forEach(entry -> admitted.add(entry.path()));
      return entries.size();
    });
    when(queue.enqueueEntries(any(), nullable(String.class), nullable(String.class))).thenAnswer(call -> {
      List<JobQueue.EnqueueEntry> entries = call.getArgument(0);
      entries.forEach(entry -> admitted.add(entry.path()));
      return entries.size();
    });
    return queue;
  }
}
