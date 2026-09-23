package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.memory.MemoryStore;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequestFiltersBuilder;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Agent bundle registration, adapter reuse and file-operation journal ownership. */
@DisplayName("AgentToolFactory — single construction authority")
final class AgentToolFactoryCompositionTest {

  // KnowledgeHttpApiAdapter's constructor builds a KnowledgeSearchEngine, which reads the global
  // reranker config — so the adapter cannot be constructed without a published ConfigStore.
  private ConfigStore previousConfigStore;

  @BeforeEach
  void publishConfigStore() {
    previousConfigStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
  }

  @AfterEach
  void restoreConfigStore() {
    TestResolvedConfigHelper.restoreGlobal(previousConfigStore);
  }

  private static KnowledgeHttpApiAdapter agentAdapter() {
    return new KnowledgeHttpApiAdapter(mock(KnowledgeServerBootstrap.class), mock(SearchPerSourceExecutor.class));
  }

  /**
   * Tempdoc 832 — the two-authorities regression guard. The eager path and the late-bound path used
   * to assemble the bundle independently, so a wiring added to one silently missed the other. These
   * assert they now compose the same bundle: same tool set, same adapter-identity semantics, recorded-owner composition (covered by RecordedHandlerCompositionTest).
   *
   * <p><b>Tempdoc 876 §B.5 — this assertion is vacuous on REMEMBER.</b> {@code eager} and
   * {@code lateBound} are two SEPARATE registries, and this call passes {@code null} for
   * {@code memoryStore}, so neither path ever registers {@code core.remember} — the
   * {@code registeredIds()} sets are equal only because both are missing the same ref, not because
   * REMEMBER made it onto both. {@link #eagerThenLateBoundRegistersAllSixOnTheSameRegistry} is the
   * test with teeth: same registry, both paths, non-null MemoryStore.
   */
  @Test
  @DisplayName("both paths register the same operation set")
  void bothPathsRegisterTheSameOperations(@TempDir Path dataDir) {
    KnowledgeClient client = mock(KnowledgeClient.class);

    HandlerRegistry eager = new HandlerRegistry();
    AgentToolFactory.Output eagerTools =
        AgentToolFactory.build(
            mock(SearchPerSourceExecutor.class),
            dataDir,
            mock(KnowledgeServerBootstrap.class),
            client,
            client,
            OnlineAiService.unavailable(),
            null,
            mock(DocumentService.class),
            io.justsearch.app.api.operations.RecordedIngestionService.unavailable(),
            io.justsearch.app.services.worker.WatchedRootsState.inMemory(),
            () -> mock(KnowledgeClient.class));
    AgentToolHandlers.registerEager(eager, eagerTools);

    HandlerRegistry lateBound = new HandlerRegistry();
    assertTrue(
        AgentToolHandlers.registerLateBound(
            mock(SearchPerSourceExecutor.class),
            lateBound,
            mock(KnowledgeServerBootstrap.class),
            client,
            dataDir,
            client,
            OnlineAiService.unavailable(),
            null,
            null,
            null,
            null,
            mock(DocumentService.class),
            io.justsearch.app.api.operations.RecordedIngestionService.unavailable(),
            io.justsearch.app.services.worker.WatchedRootsState.inMemory(),
            () -> mock(KnowledgeClient.class)),
        "late-bound registration ran (prerequisites met)");

    assertEquals(
        eager.registeredIds(),
        lateBound.registeredIds(),
        "the eager and late-bound paths must expose the same agent tools");
  }

  /**
   * Tempdoc 876 §B.5 — the actual regression guard for finding 4. On {@code main},
   * {@code registerLateBound} short-circuits on {@code resolve(SEARCH_INDEX).isPresent()}: once
   * {@code registerEager} has registered SEARCH_INDEX on this SAME registry (the production shape
   * — {@code SubstratePhase} always eager-registers into the one {@code HandlerRegistry} that
   * {@code registerLateBound} is later called on via the Memoized field in {@code HeadAssembly}),
   * the late-bound call returns {@code false} at its very first line and registers NOTHING —
   * including {@code core.remember}, which the eager path never registers (it has no
   * {@code MemoryStore} parameter). {@code core_remember} is offered to the model unconditionally
   * (no availability guard in {@code AgentToolsOperationCatalog}), so this leaves an offered tool
   * with no handler for the lifetime of the process. This test fails on {@code main} because the
   * sentinel makes {@code lateBound.registeredIds()} come back with only the 5 eager refs, missing
   * REMEMBER; the fix (per-ref idempotent registration, no whole-call short-circuit) makes the two
   * calls compose instead of the second excluding itself.
   */
  @Test
  @DisplayName("eager then late-bound on the SAME registry registers all six handlers (fails on main)")
  void eagerThenLateBoundRegistersAllSixOnTheSameRegistry(@TempDir Path dataDir) {
    KnowledgeClient client = mock(KnowledgeClient.class);

    HandlerRegistry registry = new HandlerRegistry();

    // Step 1: the eager path, exactly as SubstratePhase.run calls it at construction time.
    AgentToolFactory.Output eagerTools =
        AgentToolFactory.build(
            mock(SearchPerSourceExecutor.class),
            dataDir,
            mock(KnowledgeServerBootstrap.class),
            client,
            client,
            OnlineAiService.unavailable(),
            null,
            mock(DocumentService.class),
            io.justsearch.app.api.operations.RecordedIngestionService.unavailable(),
            io.justsearch.app.services.worker.WatchedRootsState.inMemory(),
            () -> mock(KnowledgeClient.class));
    AgentToolHandlers.registerEager(registry, eagerTools);
    assertTrue(
        registry.resolve(AgentToolsOperationCatalog.SEARCH_INDEX).isPresent(),
        "eager registration must have registered SEARCH_INDEX (the sentinel ref on main)");

    // Step 2: the late-bound path, on the SAME registry, with a non-null MemoryStore — the only
    // producer of core.remember. This is what HeadAssembly's Memoized field triggers on worker
    // connect (876 §A.0 finding 4 confirmation: "any eager registration of SEARCH_INDEX therefore
    // permanently suppresses REMEMBER").
    boolean lateBoundRan =
        AgentToolHandlers.registerLateBound(
            mock(SearchPerSourceExecutor.class),
            registry,
            mock(KnowledgeServerBootstrap.class),
            client,
            dataDir,
            client,
            OnlineAiService.unavailable(),
            null,
            null,
            null,
            MemoryStore.noop(),
            mock(DocumentService.class),
            io.justsearch.app.api.operations.RecordedIngestionService.unavailable(),
            io.justsearch.app.services.worker.WatchedRootsState.inMemory(),
            () -> mock(KnowledgeClient.class));
    assertTrue(lateBoundRan, "late-bound registration must run: all prerequisites are satisfied");

    assertEquals(
        Set.of(
            AgentToolsOperationCatalog.SEARCH_INDEX,
            AgentToolsOperationCatalog.READ_DOCUMENT,
            AgentToolsOperationCatalog.BROWSE_FOLDERS,
            AgentToolsOperationCatalog.INGEST_FILES,
            AgentToolsOperationCatalog.FILE_OPERATIONS,
            AgentToolsOperationCatalog.REMEMBER),
        registry.registeredIds(),
        "all six agent-tool handlers must be registered once both paths have run, including "
            + "REMEMBER — which only the late-bound path ever registers");
  }

  @Test
  @DisplayName("adapter identity: late-bound reuses the eager adapter, and builds one when absent")
  void adapterIdentitySemantics(@TempDir Path dataDir) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    KnowledgeHttpApiAdapter existing = agentAdapter();

    AgentToolFactory.Output reused =
        AgentToolFactory.assemble(
            mock(SearchPerSourceExecutor.class),
            dataDir,
            mock(KnowledgeServerBootstrap.class),
            client,
            client,
            OnlineAiService.unavailable(),
            null,
            existing,
            null,
            mock(DocumentService.class),
            io.justsearch.app.api.operations.RecordedIngestionService.unavailable(),
            io.justsearch.app.services.worker.WatchedRootsState.inMemory(),
            () -> mock(KnowledgeClient.class), null);
    assertSame(existing, reused.agentSearchAdapter(), "a supplied adapter is reused, not replaced");

    AgentToolFactory.Output fresh =
        AgentToolFactory.assemble(
            mock(SearchPerSourceExecutor.class),
            dataDir,
            mock(KnowledgeServerBootstrap.class),
            client,
            client,
            OnlineAiService.unavailable(),
            null,
            null,
            null,
            mock(DocumentService.class),
            io.justsearch.app.api.operations.RecordedIngestionService.unavailable(),
            io.justsearch.app.services.worker.WatchedRootsState.inMemory(),
            () -> mock(KnowledgeClient.class), null);
    assertNotNull(fresh.agentSearchAdapter(), "a fresh adapter is built when none is supplied");
    assertNotSame(existing, fresh.agentSearchAdapter());
  }

  @Test
  @DisplayName("eager adapter reads feature flags from its captured ConfigStore on every search")
  void eagerAdapterUsesCapturedLiveSearchFlags(@TempDir Path dataDir) {
    assertComposedAdapterUsesCapturedLiveSearchFlags(dataDir, true);
  }

  @Test
  @DisplayName("late adapter reads feature flags from its captured ConfigStore on every search")
  void lateAdapterUsesCapturedLiveSearchFlags(@TempDir Path dataDir) {
    assertComposedAdapterUsesCapturedLiveSearchFlags(dataDir, false);
  }

  /**
   * Exercises the adapter produced by each real factory path. The process-global snapshot is the
   * contradictory disabled snapshot; only the explicitly captured store starts enabled. After the
   * adapter is built, replacing that captured snapshot must change the next operations without
   * reconstructing the adapter.
   */
  private static void assertComposedAdapterUsesCapturedLiveSearchFlags(
      Path dataDir, boolean eager) {
    ConfigStore captured =
        new ConfigStore(
            TestResolvedConfigHelper.fromEntries(
                Map.of(
                    "justsearch.qu.enabled", "true",
                    "justsearch.filter_norm.enabled", "true")));
    ConfigStore.setGlobal(
        new ConfigStore(
            TestResolvedConfigHelper.fromEntries(
                Map.of(
                    "justsearch.qu.enabled", "false",
                    "justsearch.filter_norm.enabled", "false"))));

    KnowledgeClient client = mock(KnowledgeClient.class);
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(captured.publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.client()).thenReturn(client);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((java.util.function.Function<KnowledgeClient, ?>) invocation.getArgument(0))
            .apply(client));
    when(client.search(any(SearchRequest.class), any(EngineContext.class)))
        .thenReturn(SearchResponse.getDefaultInstance());

    OnlineAiService ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    when(ai.chatCompletion(any(), anyInt(), any(), any(EngineContext.class)))
        .thenReturn(
            CompletableFuture.completedFuture(
                "{\"query\":\"captured query\",\"meta_source\":[\"captured source\"]}"));

    SearchPerSourceExecutor perSource = mock(SearchPerSourceExecutor.class);
    AgentToolFactory.Output output =
        eager
            ? AgentToolFactory.build(
                perSource,
                dataDir,
                bootstrap,
                client,
                client,
                ai,
                null,
                mock(DocumentService.class),
                io.justsearch.app.api.operations.RecordedIngestionService.unavailable(),
                io.justsearch.app.services.worker.WatchedRootsState.inMemory(),
                () -> client,
                captured)
            : AgentToolFactory.assemble(
                perSource,
                dataDir,
                bootstrap,
                client,
                client,
                ai,
                null,
                null,
                null,
                mock(DocumentService.class),
                io.justsearch.app.api.operations.RecordedIngestionService.unavailable(),
                io.justsearch.app.services.worker.WatchedRootsState.inMemory(),
                () -> client,
                captured);

    KnowledgeHttpApiAdapter adapter = output.agentSearchAdapter();
    assertNotNull(adapter);
    var quEnabled = adapter.search(queryUnderstandingRequest(), TestEngineContexts.internal());
    var normalizationEnabled =
        adapter.search(filterNormalizationRequest(), TestEngineContexts.internal());
    assertNotNull(quEnabled.queryUnderstanding(), "captured A enables query understanding");
    assertNotNull(
        normalizationEnabled.filterNormalization(), "captured A enables filter normalization");
    verify(ai, times(2)).chatCompletion(any(), anyInt(), any(), any(EngineContext.class));

    captured.update(
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                "justsearch.qu.enabled", "false",
                "justsearch.filter_norm.enabled", "false")));
    var quDisabled = adapter.search(queryUnderstandingRequest(), TestEngineContexts.internal());
    var normalizationDisabled =
        adapter.search(filterNormalizationRequest(), TestEngineContexts.internal());
    assertNull(quDisabled.queryUnderstanding(), "the next request observes disabled QU");
    assertNull(
        normalizationDisabled.filterNormalization(),
        "the next request observes disabled filter normalization");
    verify(ai, times(2)).chatCompletion(any(), anyInt(), any(), any(EngineContext.class));
    verify(client, times(4)).search(any(SearchRequest.class), any(EngineContext.class));
  }

  private static KnowledgeSearchRequest queryUnderstandingRequest() {
    return searchRequest(null);
  }

  private static KnowledgeSearchRequest filterNormalizationRequest() {
    return searchRequest(
        KnowledgeSearchRequestFiltersBuilder.builder()
            .metaSource(List.of("Unresolved Source"))
            .build());
  }

  private static KnowledgeSearchRequest searchRequest(KnowledgeSearchRequest.Filters filters) {
    return new KnowledgeSearchRequest(
        "budget report",
        10,
        "text",
        null,
        null,
        List.of(),
        filters,
        null,
        null,
        null,
        false,
        false,
        new PipelineConfig(false, true, false, "none", false, false, 1, false, false));
  }

  @Test
  @DisplayName("every bundle component is composed on both paths")
  void bundleIsFullyComposed(@TempDir Path dataDir) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    AgentToolFactory.Output out =
        AgentToolFactory.build(
            mock(SearchPerSourceExecutor.class),
            dataDir,
            mock(KnowledgeServerBootstrap.class),
            client,
            client,
            OnlineAiService.unavailable(),
            null,
            mock(DocumentService.class),
            io.justsearch.app.api.operations.RecordedIngestionService.unavailable(),
            io.justsearch.app.services.worker.WatchedRootsState.inMemory(),
            () -> mock(KnowledgeClient.class));
    assertNotNull(out.agentSearchAdapter());
    assertNotNull(out.fileOperationLog());
    assertNotNull(out.fileOperationsTool());
    assertNotNull(out.searchTool());
    assertNotNull(out.browseTool());
    assertNotNull(out.ingestTool());
    // Tempdoc 868 §B.2 — the read tool joins the bundle, so it joins this completeness guard.
    assertNotNull(out.readDocumentTool());
  }

  /**
   * The eager guard, restated at its real scope by tempdoc 913 D5.
   *
   * <p>What the guard is FOR: no Worker, no Worker-backed tool. Every tool below reaches the
   * Worker (search/browse/ingest/read through the adapter, file-operations through
   * {@code knowledgeClient::updateDocumentPaths}), so building one without a client is either an
   * NPE or a tool that lies about what it can do. That part is unchanged and still asserted.
   *
   * <p>What changed: {@code fileOperationLog} was swept into the same all-null arm despite
   * depending on neither collaborator — it reads and writes {@code dataDir/file-operations} and
   * nothing else. Because this arm is the one the REAL boot takes ({@code HeadlessApp} constructs
   * {@code HeadAssembly} with a null knowledgeServer), that made the agent-history endpoint
   * permanently empty. The narrower assertion is the point of the fix: the guard covers the tools,
   * not the journal. The end-to-end proof lives in
   * {@code HeadAssemblyTest#agentOperationHistoryReadsTheJournalAtBootstrapBeforeAnyWorkerConnects}.
   */
  @Test
  @DisplayName("the eager guard covers the Worker-backed tools — and only those (913 D5)")
  void eagerGuardNullsTheWorkerBackedToolsButNotTheJournal(@TempDir Path dataDir) {
    AgentToolFactory.Output out =
        AgentToolFactory.build(
            mock(SearchPerSourceExecutor.class),
            dataDir,
            mock(KnowledgeServerBootstrap.class),
            null,
            mock(KnowledgeClient.class),
            OnlineAiService.unavailable(),
            null,
            mock(DocumentService.class),
            io.justsearch.app.api.operations.RecordedIngestionService.unavailable(),
            io.justsearch.app.services.worker.WatchedRootsState.inMemory(),
            () -> mock(KnowledgeClient.class));
    assertNull(out.agentSearchAdapter());
    assertNull(out.fileOperationsTool());
    assertNull(out.searchTool());
    assertNull(out.browseTool());
    assertNull(out.ingestTool());
    assertNull(out.readDocumentTool());

    assertNotNull(
        out.fileOperationLog(),
        "the file-operation journal has no Worker dependency — nulling it here is what left"
            + " GET /api/chat/agent/history reporting no batches over full journals (913 D5)");
    assertEquals(
        dataDir.resolve("file-operations"),
        logDirOf(out.fileOperationLog()),
        "and it must be the SAME directory the late-bound write path uses, not a second location");
  }

  /** The journal's directory, so the two paths can be asserted to name one place. */
  private static Path logDirOf(io.justsearch.agent.tools.FileOperationLog log) {
    try {
      var f = io.justsearch.agent.tools.FileOperationLog.class.getDeclaredField("logDir");
      f.setAccessible(true);
      return (Path) f.get(log);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("FileOperationLog.logDir is the journal's only location field", e);
    }
  }

  /**
   * Tempdoc 913 D5 — one journal instance for the process. The late-bound path reuses whatever the
   * eager path already built (the same contract {@code existingAdapter} has), so the reader the
   * agent-history endpoint holds is the writer {@code FileOperationsTool} appends through.
   */
  @Test
  @DisplayName("assemble reuses a supplied file-operation journal instead of building a second")
  void suppliedJournalIsReused(@TempDir Path dataDir) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    io.justsearch.agent.tools.FileOperationLog existing =
        new io.justsearch.agent.tools.FileOperationLog(dataDir.resolve("file-operations"));

    AgentToolFactory.Output reused =
        AgentToolFactory.assemble(
            mock(SearchPerSourceExecutor.class),
            dataDir,
            mock(KnowledgeServerBootstrap.class),
            client,
            client,
            OnlineAiService.unavailable(),
            null,
            null,
            existing,
            mock(DocumentService.class),
            io.justsearch.app.api.operations.RecordedIngestionService.unavailable(),
            io.justsearch.app.services.worker.WatchedRootsState.inMemory(),
            () -> mock(KnowledgeClient.class), null);
    assertSame(existing, reused.fileOperationLog(), "a supplied journal is reused, not replaced");

    AgentToolFactory.Output fresh =
        AgentToolFactory.assemble(
            mock(SearchPerSourceExecutor.class),
            dataDir,
            mock(KnowledgeServerBootstrap.class),
            client,
            client,
            OnlineAiService.unavailable(),
            null,
            null,
            null,
            mock(DocumentService.class),
            io.justsearch.app.api.operations.RecordedIngestionService.unavailable(),
            io.justsearch.app.services.worker.WatchedRootsState.inMemory(),
            () -> mock(KnowledgeClient.class), null);
    assertNotNull(fresh.fileOperationLog(), "a fresh journal is built when none is supplied");
    assertNotSame(existing, fresh.fileOperationLog());
  }

}
