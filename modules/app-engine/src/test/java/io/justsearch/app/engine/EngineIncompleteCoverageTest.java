/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.services.worker.CancelToken;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.SyncDirectoryResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real worker enumeration, SQLite admission, root state and durable store. No shared stack. */
@EnabledOnOs(OS.LINUX)
@Timeout(45)
final class EngineIncompleteCoverageTest {
  @TempDir Path directory;

  @Test void D1PeriodicCoverageClearsPersistedWarning() throws Exception { recovery("periodic", false, false); }
  @Test void D2VerifyFolderClearsPersistedWarning() throws Exception { recovery("verify", false, false); }
  @ParameterizedTest @ValueSource(booleans = {false, true})
  void D3RecordedReindexClearsPersistedWarning(boolean force) throws Exception { recovery("recorded", force, false); }
  @ParameterizedTest @ValueSource(strings = {"periodic", "verify", "recorded"})
  void D4OneRepairedFileCannotClearButWholeRootCan(String route) throws Exception { recovery(route, true, true); }

  private void recovery(String route, boolean force, boolean repairOne) throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.addIncomplete();
      fixture.assertWarning();
      if (repairOne) {
        fixture.readable(fixture.first);
        fixture.client.submitBatch(List.of(fixture.first), TestEngineContexts.FOREGROUND);
        fixture.recover(route, force);
        fixture.assertWarning();
      }
      fixture.repair();
      fixture.recover(route, force);
      assertNull(fixture.status().walkError(), "complete whole-root coverage must clear the warning without restart");
      assertNotNull(fixture.status().lastIndexed(), "a nonempty repaired root must not appear empty");
      assertTrue(fixture.status().walkCompleted());
      fixture.assertReload(false);
    }
  }

  @Test void D5LegacyReindexRejectsUnverifiedResponseIndependentlyOfSkipped() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.addIncomplete();
      doReturn(SyncDirectoryResponse.newBuilder().setDeleteDetectionUnverified(true).build())
          .when(fixture.service).syncDirectory(any(), any());
      fixture.client.reindexWatchedRoots(false, TestEngineContexts.FOREGROUND);
      fixture.assertWarning();
    }
  }

  @ParameterizedTest @ValueSource(strings = {"skipped", "deferred", "unverified", "failed", "missing", "wrong-volume", "interrupted"})
  void R1NonqualifyingSyncCannotClear(String result) throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.addIncomplete();
      var response = SyncDirectoryResponse.newBuilder();
      switch (result) {
        case "skipped" -> response.setSkipped(true);
        case "deferred" -> response.setDeferredToSwitchBuffer(true);
        case "unverified" -> response.setDeleteDetectionUnverified(true);
        case "failed" -> response.setError("failed");
        case "missing" -> response.setError("ROOT_NOT_DIRECTORY");
        case "wrong-volume" -> response.setError("ROOT_VOLUME_MISMATCH");
        case "interrupted" -> response.setError("Interrupted");
        default -> throw new AssertionError(result);
      }
      doReturn(response.build()).when(fixture.service).syncDirectory(any(), any());
      fixture.client.syncDirectory(fixture.root.toString(), true, TestEngineContexts.FOREGROUND);
      // A root-level refusal may replace the warning with its own error, but cannot certify coverage.
      assertNotNull(fixture.status().walkError());
      assertNull(fixture.status().lastIndexed());
      assertEquals(1, fixture.queue.queueDepth());
    }
  }

  @ParameterizedTest @ValueSource(strings = {"file", "subtree", "extra-exclusion", "policy-changed"})
  void R2RestrictedRecordedScopeCannotClear(String restriction) throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.addIncomplete();
      fixture.repair();
      var path = restriction.equals("file") ? fixture.first : restriction.equals("subtree")
          ? Files.createDirectory(fixture.root.resolve("subtree")) : fixture.root;
      List<String> exclusions = restriction.equals("extra-exclusion") || restriction.equals("policy-changed")
          ? List.of("*.txt") : List.of();
      if (restriction.equals("policy-changed")) fixture.exclusions("[\"*.txt\"]");
      var scope = new RecordedRootPlan.Root(path, "documents", true, restriction.equals("file"), exclusions, List.of());
      if (restriction.equals("policy-changed")) fixture.exclusions("[]");
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, fixture.recorded(scope));
      fixture.assertWarning();
    }
  }

  @Test void R2LiteralPolicyChangedDuringEnumerationCannotClear() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.addIncomplete(); fixture.repair();
      var prepared = new RecordedRootPlan.Root(fixture.root, "documents", true, false,
          KnowledgeClient.captureRecordedExcludePatterns(), List.of());
      var literal = io.justsearch.configuration.excludes.LiteralExclusion.create(fixture.root,
          io.justsearch.configuration.excludes.LiteralExclusion.Scope.FOLDER);
      fixture.exclusions(tools.jackson.databind.json.JsonMapper.builder().build()
          .writeValueAsString(List.of(literal)));
      doAnswer(call -> {
        call.callRealMethod();
        // The live literal excluded the root. Removing it makes this completed walk partial
        // against the current policy, even though the frozen glob list is still identical.
        fixture.exclusions("[]");
        return null;
      }).when(fixture.service).scanRecordedRoot(any(), any(), any());
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, fixture.recorded(prepared));
      fixture.assertWarning();
    }
  }

  @ParameterizedTest @ValueSource(strings = {"periodic", "verify", "recorded", "boot"})
  void R3RepeatedIncompleteObservationInvalidatesOlderCleanCompletion(String producer) throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.addIncomplete();
      fixture.repair();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      if (producer.equals("recorded")) {
        doAnswer(call -> { call.callRealMethod(); entered.countDown(); await(release); return null; })
            .when(fixture.service).scanRecordedRoot(any(), any(), any());
      } else if (producer.equals("boot")) {
        doAnswer(call -> { call.callRealMethod(); entered.countDown(); await(release); return null; })
            .when(fixture.service).scanRoot(any(), any(), any());
      } else {
        doAnswer(call -> { var response = call.callRealMethod(); entered.countDown(); await(release); return response; })
            .when(fixture.service).syncDirectory(any(), any());
      }
      try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        var completion = executor.submit(() -> { fixture.recover(producer, true); return null; });
        try {
          assertTrue(entered.await(10, TimeUnit.SECONDS));
          // Same text, new observation: there is no string change to use as a generation fence.
          var observe = WatchedRootsState.class.getDeclaredMethod("markWalkFailed", Path.class, String.class);
          observe.setAccessible(true);
          observe.invoke(fixture.roots, fixture.root, "INCOMPLETE_COVERAGE");
          release.countDown();
          completion.get(10, TimeUnit.SECONDS);
          fixture.drainWalks();
          fixture.assertWarning();
        } finally { release.countDown(); }
      }
    }
  }

  @Test void R4RemovedRegistrationRejectsAResultAwaitingApplication() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.addIncomplete();
      fixture.repair();
      var field = KnowledgeClient.class.getDeclaredField("syncOps"); field.setAccessible(true);
      var ops = field.get(fixture.client);
      var rpcField = ops.getClass().getDeclaredField("rpc"); rpcField.setAccessible(true);
      var original = (io.justsearch.app.services.worker.IngestRpcExecutor) rpcField.get(ops);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      rpcField.set(ops, new io.justsearch.app.services.worker.IngestRpcExecutor() {
        @Override public <T> T execute(String operation, KnowledgeClient.RpcDeadlineCategory category,
            java.util.function.Function<io.justsearch.app.services.worker.IngestServiceCalls, T> call,
            io.justsearch.core.context.EngineContext context) {
          T result = original.execute(operation, category, call, context);
          if (operation.equals("syncDirectory")) {
            entered.countDown();
            try { await(release); } catch (InterruptedException failure) { throw new AssertionError(failure); }
          }
          return result;
        }
      });
      try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        var completion = executor.submit(() -> { fixture.recover("verify", true); return null; });
        try {
          assertTrue(entered.await(10, TimeUnit.SECONDS));
          fixture.client.removeWatchedPath(fixture.root, TestEngineContexts.FOREGROUND);
          fixture.unreadable(fixture.first); fixture.unreadable(fixture.second);
          fixture.client.addWatchedRoot("documents", fixture.root, TestEngineContexts.FOREGROUND);
          fixture.drainWalks();
          release.countDown(); completion.get(10, TimeUnit.SECONDS);
          fixture.assertWarning();
        } finally { release.countDown(); rpcField.set(ops, original); }
      }
      fixture.repair();
      fixture.recover("boot", true);
      assertNull(fixture.status().walkError());
    }
  }

  @Test void R5ReloadWhileUnreadableRetainsWarningAndBootAfterRepairClears() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.addIncomplete();
      fixture.assertReload(true);
      fixture.client.reindexPersistedRoots(TestEngineContexts.BACKGROUND);
      fixture.drainWalks();
      fixture.assertWarning();
      fixture.repair();
      fixture.client.reindexPersistedRoots(TestEngineContexts.BACKGROUND);
      fixture.drainWalks();
      assertNull(fixture.status().walkError());
      fixture.assertReload(false);
    }
  }

  @ParameterizedTest @ValueSource(strings = {"periodic", "verify", "recorded"})
  void R6EmptyRepairedRootCompletesWithoutTimestamp(String route) throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.addIncomplete(); fixture.repair();
      Files.delete(fixture.first); Files.delete(fixture.second); Files.delete(fixture.control);
      fixture.recover(route, false);
      assertNull(fixture.status().walkError());
      assertNull(fixture.status().lastIndexed());
      assertTrue(fixture.status().walkCompleted());
      fixture.assertReload(false);
    }
  }

  @ParameterizedTest @ValueSource(strings = {"ownership", "glob", "literal"})
  void R2LegitimatePolicyAndOwnershipExclusionsPermitWholeEligibleCoverage(String exclusion) throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.addIncomplete(); fixture.repair();
      Path child = Files.createDirectory(fixture.root.resolve("owned"));
      Path hidden = Files.writeString(child.resolve("hidden.txt"), "child collection");
      Files.setPosixFilePermissions(hidden, Set.of());
      assertFalse(Files.isReadable(hidden));
      try {
        var register = WatchedRootsState.class.getDeclaredMethod("register", Path.class, String.class, boolean.class);
        register.setAccessible(true); register.invoke(fixture.roots, child, "other", false);
        var persist = WatchedRootsState.class.getDeclaredMethod("persist"); persist.setAccessible(true); persist.invoke(fixture.roots);
        boolean excludeAll = !exclusion.equals("ownership");
        fixture.exclusions(exclusion.equals("literal")
            ? tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(List.of(
                io.justsearch.configuration.excludes.LiteralExclusion.create(fixture.root,
                    io.justsearch.configuration.excludes.LiteralExclusion.Scope.FOLDER)))
            : excludeAll ? "[\"*.txt\"]" : "[]");
        var scope = new RecordedRootPlan.Root(fixture.root, "documents", true, false,
            KnowledgeClient.captureRecordedExcludePatterns(), List.of(child));
        assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, fixture.recorded(scope));
        assertNull(fixture.status().walkError(), () -> "scope=" + scope + " bindings="
            + fixture.roots.snapshotBindings() + " policy=" + KnowledgeClient.captureRecordedExcludePatterns());
        if (excludeAll) assertNull(fixture.status().lastIndexed());
        else assertNotNull(fixture.status().lastIndexed());
        assertTrue(fixture.status().walkCompleted());
      } finally { Files.setPosixFilePermissions(hidden, fixture.permissions); }
    }
  }

  @Test void R8SuccessfulSyncLeavesOtherWalkErrorsAndUnrelatedRootsAlone() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.addIncomplete(); fixture.repair();
      var observe = WatchedRootsState.class.getDeclaredMethod("markWalkFailed", Path.class, String.class);
      observe.setAccessible(true); observe.invoke(fixture.roots, fixture.root, "OTHER_WALK_ERROR");
      fixture.recover("verify", true);
      assertEquals("OTHER_WALK_ERROR", fixture.status().walkError());
      observe.invoke(fixture.roots, fixture.root, "INCOMPLETE_COVERAGE");
      Path other = Files.createDirectory(directory.resolve("other"));
      fixture.client.addWatchedRoot("other", other, TestEngineContexts.FOREGROUND); fixture.drainWalks();
      fixture.client.syncDirectory(other.toString(), true, TestEngineContexts.FOREGROUND);
      assertEquals("INCOMPLETE_COVERAGE", fixture.status().walkError());
    }
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertTrue(latch.await(15, TimeUnit.SECONDS));
  }

  private static final class Fixture implements AutoCloseable {
    final ConfigStore previous = ConfigStore.globalOrNull();
    final Path root, data, first, second, control;
    final Set<PosixFilePermission> permissions;
    final WatchedRootsState roots;
    final SqliteJobQueue queue;
    final WorkerIngestService service;
    final WorkerAppServices services;
    final DefaultEngineExecutorRegistry registry = new DefaultEngineExecutorRegistry();
    final EngineKnowledgeClient client;
    final String generation;
    int sequence;

    Fixture(Path directory) throws Exception {
      root = Files.createDirectory(directory.resolve("documents"));
      first = Files.writeString(root.resolve("first.txt"), "marmoset first");
      second = Files.writeString(root.resolve("second.txt"), "capybara second");
      control = Files.writeString(root.resolve("control.txt"), "wombat control");
      permissions = Files.getPosixFilePermissions(first);
      data = Files.createDirectory(directory.resolve("data"));
      EngineTestHarness.publishConfig(data, directory.resolve("index"), Map.of());
      roots = WatchedRootsState.load(data);
      var layout = new IndexGenerationManager(directory.resolve("index")).initializeOrLoad();
      generation = layout.activeGenerationId();
      queue = new SqliteJobQueue(layout.basePath().resolve("jobs.db"), ignored -> JobQueue.RecordedClaimDecision.ALLOW_FORCE);
      queue.open();
      var runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
      when(runtime.isAcceptingWrites()).thenReturn(true);
      when(runtime.pruneOps()).thenReturn(null);
      when(runtime.documentFieldOps()).thenReturn(null);
      when(runtime.readPathOps().search(any(), anyInt(), anySet(), any(), nullable(String.class)))
          .thenReturn(new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0));
      service = spy(new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          layout.basePath(), layout.activeGenerationPath(), runtime, runtime, null, 0L));
      service.setDeclaredRootsDirectory(data);
      services = mock(WorkerAppServices.class);
      when(services.ingestService()).thenReturn(service);
      client = new EngineKnowledgeClient(registry, () -> services, new ForegroundLoadGate(new ForegroundLoad()),
          20_000, 100, IpcTelemetry.noop(), () -> {}, new EngineAdmissionController(8, 8, 1), roots);
      client.bindSourceOrigins(ignored -> {});
    }

    void exclusions(String patterns) throws Exception {
      EngineTestHarness.publishConfig(data, data.getParent().resolve("index"),
          Map.of("justsearch.ui.exclude_patterns", patterns));
      assertEquals(patterns, ConfigStore.global().get().ui().excludePatterns());
    }
    void addIncomplete() throws Exception {
      unreadable(first); unreadable(second);
      client.addWatchedRoot("documents", root, TestEngineContexts.FOREGROUND);
      drainWalks();
    }
    void unreadable(Path file) throws Exception {
      Files.setPosixFilePermissions(file, Set.of());
      assertFalse(Files.isReadable(file), "mode 000 must be unreadable; requires an unprivileged process");
    }
    void readable(Path file) throws Exception { Files.setPosixFilePermissions(file, permissions); }
    void repair() throws Exception { readable(first); readable(second); }
    void drainWalks() throws Exception {
      var field = KnowledgeClient.class.getDeclaredField("walkExecutor"); field.setAccessible(true);
      ((ExecutorService) field.get(client)).submit(() -> {}).get(15, TimeUnit.SECONDS);
    }
    IndexingService.WatchedRoot status() {
      return client.getWatchedRoots(TestEngineContexts.BACKGROUND).stream()
          .filter(entry -> entry.path().equals(root)).findFirst().orElseThrow();
    }
    void assertWarning() throws Exception {
      assertEquals("INCOMPLETE_COVERAGE", status().walkError());
      assertReload(true);
    }
    void assertReload(boolean warning) throws Exception {
      var loaded = WatchedRootsState.load(data);
      try (var reloadRegistry = new DefaultEngineExecutorRegistry();
          var reopened = new EngineKnowledgeClient(reloadRegistry, () -> services, new ForegroundLoadGate(new ForegroundLoad()),
          20_000, 100, IpcTelemetry.noop(), () -> {}, new EngineAdmissionController(8, 8, 1), loaded)) {
        var entry = reopened.getWatchedRoots(TestEngineContexts.BACKGROUND).getFirst();
        assertEquals(root, entry.path()); assertEquals("documents", entry.collection());
        assertEquals(warning ? "INCOMPLETE_COVERAGE" : null, entry.walkError());
        assertTrue(entry.walkCompleted());
        assertEquals(status().lastIndexed(), entry.lastIndexed());
      }
      assertEquals(warning, Files.readString(data.resolve("watched_roots.json")).contains("INCOMPLETE_COVERAGE"));
    }
    void recover(String route, boolean force) throws Exception {
      switch (route) {
        case "periodic" -> {
          var field = KnowledgeClient.class.getDeclaredField("syncOps"); field.setAccessible(true);
          var ops = field.get(client);
          var method = ops.getClass().getDeclaredMethod("syncWatchedDirectory", String.class, boolean.class,
              io.justsearch.core.context.EngineContext.class); method.setAccessible(true);
          var response = (SyncDirectoryResponse) method.invoke(ops, root.toString(), false, TestEngineContexts.BACKGROUND);
          assertNotNull(response); assertEquals("", response.getError());
        }
        case "verify" -> {
          var hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
              .digest(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
          assertTrue(client.reconcileRoot(hash, true, TestEngineContexts.FOREGROUND));
        }
        case "recorded" -> recorded(new RecordedRootPlan.Root(root, "documents", force, false, List.of(), List.of()));
        case "boot" -> { client.reindexPersistedRoots(TestEngineContexts.BACKGROUND); drainWalks(); }
        default -> throw new AssertionError(route);
      }
    }
    JobQueue.WalkEnumerationOutcome recorded(RecordedRootPlan.Root scope) throws Exception {
      String key = "coverage-" + ++sequence;
      long epoch = queue.beginRecordedWalk(key, "b".repeat(64), true).enumerationEpoch();
      var plan = new RecordedRootPlan(generation, List.of(scope));
      var completion = scope.path().equals(root) && !scope.singleFile()
          ? client.enumerateWatchedRecordedRoot(plan, key, epoch, TestEngineContexts.FOREGROUND, new CancelToken())
          : client.enumerateRecordedRoot(plan, key, epoch, TestEngineContexts.FOREGROUND, new CancelToken());
      return completion.toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
    @Override public void close() throws Exception {
      for (Path file : List.of(first, second)) if (Files.exists(file)) readable(file);
      try { client.close(); } finally { try { registry.close(); } finally { queue.close(); } }
      if (previous != null) ConfigStore.setGlobal(previous);
      else { var clear = ConfigStore.class.getDeclaredMethod("clearGlobal"); clear.setAccessible(true); clear.invoke(null); }
    }
  }
}
