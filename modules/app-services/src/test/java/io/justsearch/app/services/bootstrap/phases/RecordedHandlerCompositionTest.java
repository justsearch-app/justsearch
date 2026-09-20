/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class RecordedHandlerCompositionTest {
  private ConfigStore previous;

  @BeforeEach void config() {
    previous = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
  }

  @AfterEach void restore() { TestResolvedConfigHelper.restoreGlobal(previous); }

  @ParameterizedTest
  @ValueSource(strings = {"eager", "late", "reindex"})
  void everyConstructionPathUsesCurrentClientAndTheSameRecordedOwner(String path, @TempDir Path directory) {
    var first = mock(KnowledgeClient.class);
    var second = mock(KnowledgeClient.class);
    var current = new AtomicReference<KnowledgeClient>(first);
    var roots = mock(WatchedRootsState.class);
    when(roots.snapshotBindings()).thenReturn(List.of(new RootBinding(directory, "documents")));
    var ingestion = mock(RecordedIngestionService.class);
    var context = TestEngineContexts.internal();
    when(first.captureServingGeneration(context)).thenReturn("generation-1");
    when(second.captureServingGeneration(context)).thenReturn("generation-2");
    var server = mock(KnowledgeServerBootstrap.class);
    var search = mock(SearchPerSourceExecutor.class);
    var registry = new HandlerRegistry();
    OperationHandler handler;
    if (path.equals("eager")) {
      var tools = AgentToolFactory.build(search, directory, server, first, first,
          OnlineAiService.unavailable(), null, mock(DocumentService.class), ingestion, roots, current::get);
      AgentToolHandlers.registerEager(registry, tools);
      handler = registry.resolve(AgentToolsOperationCatalog.INGEST_FILES).orElseThrow();
    } else if (path.equals("late")) {
      var capability = mock(WorkerCapability.class);
      when(capability.available()).thenReturn(true);
      assertTrue(AgentToolHandlers.registerLateBound(search, registry, server, first, capability,
          directory, first, OnlineAiService.unavailable(), null, null, null, null,
          mock(DocumentService.class), ingestion, roots, current::get));
      handler = registry.resolve(AgentToolsOperationCatalog.INGEST_FILES).orElseThrow();
    } else {
      OperationHandlerRegistrations.registerWorker(registry, () -> server, current::get,
          () -> null, () -> null, () -> null, () -> null, () -> null, () -> null, () -> null,
          () -> null, () -> null, () -> null, mock(OperationLeaseService.class), ingestion, roots);
      handler = registry.resolve(CoreOperationCatalog.REINDEX).orElseThrow();
    }
    var provenance = InvocationProvenance.fromEngineContext(context, ExecutorTag.CLI,
        Instant.parse("2026-09-14T12:00:00Z"), Optional.empty());
    String args = path.equals("reindex") ? "{\"force\":true}" :
        tools.jackson.databind.json.JsonMapper.builder().build()
            .writeValueAsString(Map.of("paths", List.of(directory.toString())));
    var prepared = handler.prepare(args, provenance, context);
    assertEquals("generation-1", RecordedRootPlan.fromReplayPayload(prepared.replayPayloadJson()).generation());
    verifyNoInteractions(ingestion);

    current.set(second);
    var next = handler.prepare(args, provenance, context);
    assertEquals("generation-2", RecordedRootPlan.fromReplayPayload(next.replayPayloadJson()).generation());
    verify(first).captureServingGeneration(context);
    verify(second).captureServingGeneration(context);
    verify(first, never()).getWatchedRoots(context);
    verify(second, never()).getWatchedRoots(context);

    var record = mock(OperationRecordHandle.class);
    var completion = new CompletableFuture<OperationResult>();
    var expected = new OperationExecution(OperationResult.success("accepted"), completion);
    when(ingestion.execute(record, context)).thenReturn(expected);
    handler.validatePreparation(prepared);
    handler.approvalPreview(prepared);
    assertSame(expected, handler.executePrepared(prepared, provenance, context, record));
    assertFalse(completion.isDone());
    verify(roots, org.mockito.Mockito.times(2)).snapshotBindings();
    verify(first).captureServingGeneration(context);
    verify(second).captureServingGeneration(context);
    verify(ingestion).execute(record, context);
    completion.complete(OperationResult.success("finished"));
  }
}
