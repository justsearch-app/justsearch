/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite, filesystem traversal, producer and Lucene across query-only replanning. */
@Timeout(180)
final class ContextReplanIngestTest {
  @Test
  void approvedRootWalkIndexesAfterContextChangesFrom16kTo32k(@TempDir Path dir) throws Exception {
    var prior = ConfigStore.globalOrNull();
    var layout = WorkerBootFixture.layout(dir);
    var settings = new java.util.HashMap<>(Map.of(
        "justsearch.ai.embed.enabled", "false", "justsearch.splade.enabled", "false",
        "justsearch.ner.enabled", "false", "justsearch.rerank.enabled", "false",
        "justsearch.citation.scorer.enabled", "false", "justsearch.bgem3.enabled", "false",
        "justsearch.extraction.sandbox.mode", "in_process", "justsearch.context.size", "16384"));
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE", settings);
    var lifecycle = new RecordedIngestionLifecycle() {
      @Override public JobQueue.RecordedClaimDecision recordedClaimDecision(String key) {
        return JobQueue.RecordedClaimDecision.ALLOW;
      }
      @Override public Attachment attach(JobQueue queue, CheckedServingGeneration generation,
          java.util.function.BooleanSupplier online) { return () -> {}; }
    };
    try (var executors = new TestEngineExecutors();
        var components = io.justsearch.core.component.TestEngineComponents.fourComponents();
        var server = new KnowledgeServer(executors, WorkerBootFixture.workerConfig(layout.dataDir()),
            null, io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), lifecycle,
            null, components.handle("encoders"))) {
      server.start();
      server.deferredModelInit.join();
      settings.put("justsearch.context.size", "32768");
      settings.put("justsearch.embed.gpu_mem_mb", "2466");
      settings.put("justsearch.rerank.gpu_mem_mb", "2468");
      WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE", settings);
      var desired = ConfigStore.globalOrNull().get();
      var prepared = server.prepareQueryRoleSettings(new UiSettings(), desired,
          Set.of("justsearch.context.size"), new QueryRoleSelection(
              QueryRoleSelection.Role.disabled(), QueryRoleSelection.Role.disabled()));
      prepared.withOwnerLocks(() -> { prepared.validate(); prepared.install(); });
      prepared.notifyObservers();
      prepared.retire();
      Path root = Files.createDirectory(dir.resolve("approved-root"));
      Path document = Files.writeString(root.resolve("sentinel.txt"), "contextreplansentinel",
          StandardCharsets.UTF_8);
      var queue = server.infraCtx.jobQueue();
      String operation = "01994180-0000-7000-8000-000000000234";
      var walk = queue.beginRecordedWalk(operation, "a".repeat(64), true);
      var progress = new ArrayList<io.justsearch.ipc.ScanRootProgress>();
      try (var view = server.captureServingView()) {
        var ingest = view.services().ingestService();
        var request = io.justsearch.ipc.ScanRootRequest.newBuilder().setRootPath(root.toString());
        // This approved explicit ingest is a deliberate add. Older before-states have no marker.
        var rootAdded = request.getDescriptorForType().findFieldByName("root_added");
        if (rootAdded != null) request.setField(rootAdded, true);
        var scan = new WorkerIngestService.RecordedRootScan(
            request.build(),
            operation, walk.enumerationEpoch(), ingest.captureServingGeneration(CallContext.none()),
            false, java.util.List.of());
        assertDoesNotThrow(() -> ingest.scanRecordedRoot(scan, progress::add, CallContext.none()),
            "approved traversal must survive the memory-plan publication");
      }
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
      String content = null;
      String indexedPath = null;
      while (System.nanoTime() < deadline) {
        var runtime = (io.justsearch.adapters.lucene.runtime.RunningRuntime) server.infraCtx.ingestLifecycleSupplier().get();
        runtime.commitOps().maybeRefreshBlocking();
        var ids = runtime.documentFieldOps().queryNonChunkDocIdsByField(
            io.justsearch.indexing.SchemaFields.CONTENT, "contextreplansentinel", 1);
        if (!ids.isEmpty()) {
          content = runtime.documentFieldOps().getDocumentField(ids.getFirst(),
              io.justsearch.indexing.SchemaFields.CONTENT);
          indexedPath = runtime.documentFieldOps().getDocumentField(ids.getFirst(),
              io.justsearch.indexing.SchemaFields.PATH);
        }
        if (content != null && content.contains("contextreplansentinel")) break;
        Thread.sleep(50);
      }
      assertNotNull(content, "the walked document must appear in the real index");
      assertTrue(content.contains("contextreplansentinel"), content);
      assertNotNull(indexedPath);
      assertEquals(document, Path.of(indexedPath), "the indexed sentinel is the walked file");
    } finally {
      io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(prior);
    }
  }
}
