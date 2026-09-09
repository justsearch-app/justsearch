package io.justsearch.app.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.justsearch.agent.api.encryption.StoreCatalog;
import io.justsearch.agent.api.encryption.StoreDescriptor;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.app.api.SearchRequest;
import io.justsearch.app.api.SearchResponse;
import io.justsearch.app.config.ConfigManagerBootstrap;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.core.dto.Result;
import io.justsearch.core.search.SearchPort;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadAssemblyTest {
  @TempDir private Path tempDir;
  private String previousCapabilitiesProp;
  private String previousProdProp;
  private String previousHomeProp;
  private String previousDataDirProp;
  private ConfigStore previousStore;

  @BeforeEach
  void captureOverrides() {
    previousCapabilitiesProp = System.getProperty("app.api.fake_capabilities");
    previousProdProp = System.getProperty("justsearch.prod");
    previousHomeProp = System.getProperty("justsearch.home");
    previousDataDirProp = System.getProperty("justsearch.data.dir");
    previousStore = ConfigStore.globalOrNull();
    System.clearProperty("app.api.fake_capabilities");
    System.clearProperty("justsearch.prod");
    System.setProperty("justsearch.home", tempDir.toString());
    System.setProperty("justsearch.data.dir", tempDir.toString());
    TestResolvedConfigHelper.storeFromEnvironment();
  }

  @AfterEach
  void restoreOverrides() {
    if (previousCapabilitiesProp == null) {
      System.clearProperty("app.api.fake_capabilities");
    } else {
      System.setProperty("app.api.fake_capabilities", previousCapabilitiesProp);
    }
    if (previousProdProp == null) {
      System.clearProperty("justsearch.prod");
    } else {
      System.setProperty("justsearch.prod", previousProdProp);
    }
    TestResolvedConfigHelper.restoreGlobal(previousStore);
    if (previousHomeProp == null) System.clearProperty("justsearch.home");
    else System.setProperty("justsearch.home", previousHomeProp);
    if (previousDataDirProp == null) System.clearProperty("justsearch.data.dir");
    else System.setProperty("justsearch.data.dir", previousDataDirProp);
  }

  @Test
  void appFacadeExposesFacade() throws Exception {
    SearchPort searchPort =
        (intent, engineContext) -> new Result(List.of(), Map.of(), null, Map.of());
    Telemetry telemetry = new NoopTelemetry();

    try (HeadAssembly bootstrap = HeadAssembly.bootForSearchPortOnly(new io.justsearch.core.execution.TestEngineExecutors(), searchPort, telemetry)) {
      // Tempdoc 519 §5 / Step 4: bootstrap is itself the AppFacade (no separate accessor).
      assertNotNull(bootstrap);
    }
  }

  @Test
  void finalInferenceTransitionDrainsAfterManagerCloseEvenWhenAnotherHandleFails() throws Exception {
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var head = HeadAssembly.bootForSearchPortOnly(executors,
            (intent, context) -> new Result(List.of(), Map.of(), null, Map.of()), new NoopTelemetry())) {
      var reasons = new java.util.concurrent.CopyOnWriteArrayList<String>();
      var transitionLog = new io.justsearch.app.inference.AsyncInferenceTransitionLog(executors,
          (timestamp, from, to, reason, success, duration, wireCode, generation) -> reasons.add(reason));
      var logField = HeadAssembly.class.getDeclaredField("asyncTransitionLog");
      logField.setAccessible(true);
      logField.set(head, transitionLog);
      var handles = new io.justsearch.app.services.bootstrap.OrchestrationHandles(
          null, null, null, null, null, null,
          () -> transitionLog.record(1, "ONLINE", "OFFLINE", "SHUTDOWN", true, 0, null, 1),
          null, null, null, null, null, null,
          () -> { throw new IllegalStateException("unrelated handle failure"); });
      var handlesField = HeadAssembly.class.getDeclaredField("orchestration");
      handlesField.setAccessible(true);
      handlesField.set(head, handles);
      assertThrows(IllegalStateException.class, head::close);
      assertEquals(List.of("SHUTDOWN"), reasons,
          "the log must stay open through the manager's final transition and drain on failure too");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void failedUnlockScanCloseStillAttemptsEveryScanAndOrchestrationOwner() throws Exception {
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var head = HeadAssembly.bootForSearchPortOnly(executors,
            (intent, context) -> new Result(List.of(), Map.of(), null, Map.of()), new NoopTelemetry())) {
      var order = new java.util.ArrayList<String>();
      var first = org.mockito.Mockito.mock(io.justsearch.app.services.encryption.UnlockDeferredScan.class);
      var second = org.mockito.Mockito.mock(io.justsearch.app.services.encryption.UnlockDeferredScan.class);
      var scanFailure = new IllegalStateException("scan close failed");
      org.mockito.Mockito.doAnswer(invocation -> { order.add("first-scan"); throw scanFailure; })
          .when(first).close();
      org.mockito.Mockito.doAnswer(invocation -> { order.add("second-scan"); return null; })
          .when(second).close();
      var scansField = HeadAssembly.class.getDeclaredField("unlockScans");
      scansField.setAccessible(true);
      var scans = (List<io.justsearch.app.services.encryption.UnlockDeferredScan>) scansField.get(head);
      scans.add(first);
      scans.add(second);
      var handlesField = HeadAssembly.class.getDeclaredField("orchestration");
      handlesField.setAccessible(true);
      handlesField.set(head, new io.justsearch.app.services.bootstrap.OrchestrationHandles(
          null, null, null, null, null, null,
          () -> { order.add("inference"); throw new IllegalStateException("manager close failed"); },
          null, null, null, null, null, null, null));
      var failure = assertThrows(IllegalStateException.class, head::close);
      org.junit.jupiter.api.Assertions.assertSame(scanFailure, failure);
      assertEquals(List.of("first-scan", "second-scan", "inference"), order);
      assertEquals(1, failure.getSuppressed().length);
      assertEquals("manager close failed", failure.getSuppressed()[0].getSuppressed()[0].getMessage());
    }
  }

  /**
   * Tempdoc 879: the authored-store registration list must ask {@link StoreCatalog#isAuthored()}
   * rather than trust its callers, so enrolling a DERIVED store in the encrypted backup fails at
   * assembly instead of silently widening what the backup claims to protect.
   */
  @Test
  void registerAuthoredStoreRefusesADerivedCatalogEntry() throws Exception {
    SearchPort searchPort =
        (intent, engineContext) -> new Result(List.of(), Map.of(), null, Map.of());

    try (HeadAssembly bootstrap =
        HeadAssembly.bootForSearchPortOnly(new io.justsearch.core.execution.TestEngineExecutors(), searchPort, new NoopTelemetry())) {
      StoreDescriptor derived =
          new StoreDescriptor(StoreCatalog.INDEX, tempDir, List::of, entries -> 0);
      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class, () -> bootstrap.registerAuthoredStore(derived));
      assertTrue(thrown.getMessage().contains("INDEX"), thrown.getMessage());
      assertTrue(thrown.getMessage().contains("DERIVED"), thrown.getMessage());
      assertTrue(bootstrap.authoredStores().isEmpty());

      StoreDescriptor authored =
          new StoreDescriptor(StoreCatalog.MEMORIES, tempDir, List::of, entries -> 0);
      bootstrap.registerAuthoredStore(authored);
      assertEquals(1, bootstrap.authoredStores().size());
    }
  }

  @Test
  void defaultConstructorBootsSearchRuntime() throws Exception {
    Telemetry telemetry = new NoopTelemetry();

    try (HeadAssembly bootstrap = new HeadAssembly(new io.justsearch.core.execution.TestEngineExecutors(), telemetry, new ConfigManagerBootstrap(), null, new io.justsearch.app.services.settings.UiSettingsStore(io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY), null, io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
        new io.justsearch.app.services.lease.OperationLeaseServiceImpl(),
        org.mockito.Mockito.mock(io.justsearch.app.api.EngineAdmissionService.class))) {
      SearchRequest request = new SearchRequest(5, 0, true, null, List.of(), List.of(), null);
      SearchResponse response =
          bootstrap.workers().search().search(request, TestEngineContexts.internal());
      assertNotNull(response);
      assertNotNull(response.hits());
    }
  }

  @Test
  void shutdownDirectiveReachesHeldInferenceManager() throws Exception {
    try (HeadAssembly assembly =
        new HeadAssembly(new io.justsearch.core.execution.TestEngineExecutors(),
            new NoopTelemetry(),
            new ConfigManagerBootstrap(),
            null,
            new io.justsearch.app.services.settings.UiSettingsStore(
                io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY),
            null, io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
        new io.justsearch.app.services.lease.OperationLeaseServiceImpl(),
        org.mockito.Mockito.mock(io.justsearch.app.api.EngineAdmissionService.class))) {
      Field field = HeadAssembly.class.getDeclaredField("inferenceManager");
      field.setAccessible(true);
      io.justsearch.app.inference.InferenceLifecycleManager manager =
          (io.justsearch.app.inference.InferenceLifecycleManager) field.get(assembly);

      assembly.setStopGenerativeBackendOnClose(false);
      assertFalse(manager.stopsServerOnClose());
      assembly.setStopGenerativeBackendOnClose(true);
      assertTrue(manager.stopsServerOnClose());
    }
  }

  /**
   * Regression (543-fwd hotfix 299b2ba69 + ordering fix 0febc18fb): async-path
   * connectKnowledgeServer must trigger agent-tool registration AFTER (a) the
   * worker-capability bridge transitions the local capability to READY (else registerLateBound
   * skips → "No handler registered for binding core.search-index"), AND (b) this.services is
   * reassembled with the fresh worker services (else this.services.worker().indexing() is null
   * → NPE in registerLateBound on indexingService::getWatchedPaths → HeadlessApp boot crash).
   * Asserts: connect does NOT throw (the boot NPE) AND the Memoized registration resolved true.
   */
  @Test
  void connectKnowledgeServerRegistersAgentToolsWithoutBootNpe() throws Exception {
    Telemetry telemetry = new NoopTelemetry();
    // Tempdoc 627 Deliverable 10: share the capability the mocked KS reports, so the HeadAssembly's
    // localCap IS ks.workerCapability() (the production invariant) and no mirror is needed.
    var cap = new io.justsearch.app.services.lifecycle.WorkerCapability();
    try (HeadAssembly bootstrap =
        new HeadAssembly(new io.justsearch.core.execution.TestEngineExecutors(),
            telemetry,
            new ConfigManagerBootstrap(),
            null,
            new io.justsearch.app.services.settings.UiSettingsStore(
                io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY), cap, io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
        new io.justsearch.app.services.lease.OperationLeaseServiceImpl(),
        org.mockito.Mockito.mock(io.justsearch.app.api.EngineAdmissionService.class))) {
      var ks =
          org.mockito.Mockito.mock(
              io.justsearch.app.services.worker.KnowledgeServerBootstrap.class);
      var client =
          org.mockito.Mockito.mock(io.justsearch.app.services.worker.KnowledgeClient.class);
      cap.transition(io.justsearch.app.api.lifecycle.CapabilityHealth.READY, null);
      org.mockito.Mockito.when(ks.workerCapability()).thenReturn(cap);
      org.mockito.Mockito.when(ks.isReady()).thenReturn(true);
      org.mockito.Mockito.when(ks.client()).thenReturn(client);

      // Must NOT throw the boot NPE, and the agent-tool handlers must register.
      var gpuGauge = new io.justsearch.core.scheduling.GpuSchedulingGauge();
      gpuGauge.setMainGpuActive(true);
      org.mockito.Mockito.when(ks.gpuScheduling()).thenReturn(gpuGauge);
      bootstrap.connectKnowledgeServer(ks);
      assertFalse(gpuGauge.isMainGpuActive(), "connect seeds the current offline inference mode");
      assertTrue(
          bootstrap.agentToolsRegistration().get(),
          "agent-tool handlers must register on worker connect");
    }
  }

  /**
   * Tempdoc 672 regression: the VDU offline coordinator was value-capturing the Worker client at
   * bootstrap (always null — Head is built with {@code knowledgeServer=null}, the Worker connects
   * asynchronously), so {@code OfflineCoordinatorBuilder.build} bailed on its {@code client ==
   * null} guard and the offline-processing trigger stayed null for the process lifetime.
   *
   * <p>Drives the real bootstrap → connect ordering (not a direct {@code OfflineCoordinator}
   * construction, which bypasses the bug entirely — see {@code unreachable-seed-green}). Asserts
   * (a) the coordinator now builds at bootstrap despite the null client, and (b) after {@code
   * connectKnowledgeServer}, {@code startOfflineProcessing} resolves the live client and drives
   * real Worker calls — proving the supplier, not a frozen null, reaches the coordinator.
   */
  @Test
  void offlineCoordinatorBuildsAtBootstrapAndResolvesClientAfterConnect() throws Exception {
    Telemetry telemetry = new NoopTelemetry();
    var cap = new io.justsearch.app.services.lifecycle.WorkerCapability();
    try (HeadAssembly bootstrap =
        new HeadAssembly(new io.justsearch.core.execution.TestEngineExecutors(),
            telemetry,
            new ConfigManagerBootstrap(),
            null,
            new io.justsearch.app.services.settings.UiSettingsStore(
                io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY),
            cap, io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
        new io.justsearch.app.services.lease.OperationLeaseServiceImpl(),
        org.mockito.Mockito.mock(io.justsearch.app.api.EngineAdmissionService.class))) {

      // Core regression: the coordinator must be non-null at bootstrap, before any Worker
      // connects — it must not have value-captured the (null) client.
      var coordinatorAtBootstrap = bootstrap.headInfraRegistry().offlineCoordinator();
      assertNotNull(
          coordinatorAtBootstrap,
          "OfflineCoordinator must build at bootstrap despite the Worker not being connected yet"
              + " (client is threaded as a live supplier, not a captured value)");

      var ks =
          org.mockito.Mockito.mock(
              io.justsearch.app.services.worker.KnowledgeServerBootstrap.class);
      var client =
          org.mockito.Mockito.mock(io.justsearch.app.services.worker.KnowledgeClient.class);
      org.mockito.Mockito.when(client.recoverVduProcessing(org.mockito.ArgumentMatchers.any())).thenReturn(0);
      org.mockito.Mockito.when(client.countPendingVdu(org.mockito.ArgumentMatchers.any())).thenReturn(0);
      org.mockito.Mockito.when(client.countPendingEmbeddings(org.mockito.ArgumentMatchers.any())).thenReturn(0);
      cap.transition(io.justsearch.app.api.lifecycle.CapabilityHealth.READY, null);
      org.mockito.Mockito.when(ks.workerCapability()).thenReturn(cap);
      org.mockito.Mockito.when(ks.isReady()).thenReturn(true);
      org.mockito.Mockito.when(ks.client()).thenReturn(client);

      org.mockito.Mockito.when(ks.gpuScheduling())
          .thenReturn(new io.justsearch.core.scheduling.GpuSchedulingGauge());
      bootstrap.connectKnowledgeServer(ks);

      // Same coordinator instance both API entry points read (HeadInfraRegistry / ServicePhase
      // Output both derive from HeadAssembly.this.offlineCoordinator).
      var coordinatorAfterConnect = bootstrap.headInfraRegistry().offlineCoordinator();
      assertTrue(
          coordinatorAtBootstrap == coordinatorAfterConnect,
          "connectKnowledgeServer must not replace the coordinator instance (no rebuild needed;"
              + " the live supplier resolves the client itself)");

      // Drive synchronously (no virtual-thread indirection) — proves the supplier resolved the
      // POST-connect client, not a value frozen at bootstrap.
      coordinatorAfterConnect.startOfflineProcessing();
      org.mockito.Mockito.verify(client).recoverVduProcessing(org.mockito.ArgumentMatchers.any());
      org.mockito.Mockito.verify(client).countPendingVdu(org.mockito.ArgumentMatchers.any());
    }
  }

  /**
   * Tempdoc 913 D5 regression: {@code GET /api/chat/agent/history} answered {@code
   * {"batches":[]}} while v2 journals sat in {@code <dataDir>/file-operations/}, before and after a
   * restart. Same bug class as the 672 case above — a value captured at bootstrap while the Worker
   * is still connecting — but one layer further in: {@code AgentToolFactory.build} returned an
   * all-null bundle when {@code knowledgeClient}/{@code indexingService} were null, and the
   * file-operation journal rode that guard arm despite depending on neither. {@code
   * AgentLoopService} then captured the null into a final field with no rebind path, so {@code
   * AgentRunQueryService.operationHistory} returned {@code List.of()} for the process lifetime
   * while the write side got its own second instance at connect time.
   *
   * <p>Drives the real bootstrap ordering (the production {@code knowledgeServer=null} constructor
   * {@code HeadlessApp} uses) and reads back through {@code core().agent()} — the same instance the
   * controller's live supplier resolves. A direct {@code AgentRunQueryService} construction cannot
   * catch this: {@code AgentLoopServiceTest} already builds one with a real log by hand and is green
   * throughout the defect ({@code unreachable-seed-green}).
   *
   * <p>The assertion is deliberately PRE-connect. Post-connect would pass even with the defect
   * present, because the late-bound path builds its own journal — the failing case is precisely the
   * window in which the product actually lives.
   */
  @Test
  void agentOperationHistoryReadsTheJournalAtBootstrapBeforeAnyWorkerConnects() throws Exception {
    Path dataDir = tempDir.resolve("d5-data");
    Path fileOps = dataDir.resolve("file-operations");
    Files.createDirectories(fileOps);
    // A v2 journal batch, the shape the live validation found on disk (schemaVersion 2 +
    // destinationDigest, tempdoc 909 items 7/8).
    String batchJson =
        "{"
            + "\"batchId\":\"batch-913-d5\","
            + "\"schemaVersion\":2,"
            + "\"timestamp\":\"2026-09-02T10:00:00Z\","
            + "\"explanation\":\"D5 wiring regression fixture\","
            + "\"operations\":[{\"op\":\"COPY\",\"source\":\"/a.txt\",\"destination\":\"/b.txt\"}],"
            + "\"executed\":[{\"index\":0,\"status\":\"OK\",\"destinationDigest\":\"abc123\"}],"
            + "\"finalized\":\"2026-09-02T10:00:01Z\""
            + "}";
    Files.writeString(fileOps.resolve("batch-913-d5.json"), batchJson, StandardCharsets.UTF_8);

    String prevDataDir = System.getProperty("justsearch.data.dir");
    ConfigStore storeBefore = ConfigStore.globalOrNull();
    try {
      System.setProperty("justsearch.data.dir", dataDir.toAbsolutePath().toString());
      // Rebuild the global store so HeadAssembly's rc.paths().dataDir() resolves to the seeded dir
      // rather than the developer's real data directory.
      TestResolvedConfigHelper.storeFromEnvironment();

      var cap = new io.justsearch.app.services.lifecycle.WorkerCapability();
      try (HeadAssembly bootstrap =
          new HeadAssembly(new io.justsearch.core.execution.TestEngineExecutors(),
              new NoopTelemetry(),
              new ConfigManagerBootstrap(),
              null,
              new io.justsearch.app.services.settings.UiSettingsStore(
                  io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY),
              cap, io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
        new io.justsearch.app.services.lease.OperationLeaseServiceImpl(),
        org.mockito.Mockito.mock(io.justsearch.app.api.EngineAdmissionService.class))) {

        var agent = bootstrap.core().agent();
        assertNotNull(agent, "the agent service must exist at bootstrap");
        // Right-reason guard: AgentService.unavailable() also answers operationHistory with an
        // empty list (the AgentRunQueries default), so a green here would say nothing about the
        // journal wiring if the real loop service had not been constructed.
        assertNotNull(
            agent.getClass().getName(),
            "agent implementation must be identifiable for the right-reason check");
        assertTrue(
            agent.getClass().getName().contains("AgentLoopService"),
            "expected the real AgentLoopService at bootstrap, got "
                + agent.getClass().getName()
                + " — an unavailable() stub would return an empty history for the wrong reason");

        List<Map<String, Object>> history = agent.operationHistory(10);
        assertEquals(
            1,
            history.size(),
            "the journal batch on disk must be listed at bootstrap, before any Worker connects —"
                + " the file-operation log does not depend on the Worker");
        assertEquals("batch-913-d5", history.get(0).get("batchId"));
      }
    } finally {
      if (prevDataDir == null) {
        System.clearProperty("justsearch.data.dir");
      } else {
        System.setProperty("justsearch.data.dir", prevDataDir);
      }
      TestResolvedConfigHelper.restoreGlobal(storeBefore);
    }
  }

  @Test
  void fileBackedCapabilitiesHandlerServesPayload() throws Exception {
    Path payload = tempDir.resolve("caps.json");
    Files.writeString(payload, "{\"features\":[\"one\"]}", StandardCharsets.UTF_8);
    System.setProperty("app.api.fake_capabilities", payload.toString());

    try (HeadAssembly bootstrap = new HeadAssembly(new io.justsearch.core.execution.TestEngineExecutors(), new NoopTelemetry(), new ConfigManagerBootstrap(), null, new io.justsearch.app.services.settings.UiSettingsStore(io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY), null, io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
        new io.justsearch.app.services.lease.OperationLeaseServiceImpl(),
        org.mockito.Mockito.mock(io.justsearch.app.api.EngineAdmissionService.class))) {
      FakeHttpExchange exchange = fakeExchange("GET", URI.create("http://localhost/infra/capabilities"));
      bootstrap.capabilitiesHandler().handle(exchange);
      assertEquals(200, exchange.statusCode);
      assertEquals("application/json; charset=utf-8", exchange.getResponseHeaders().getFirst("Content-Type"));
      assertEquals("{\"features\":[\"one\"]}", exchange.body());
    }
  }

  @Test
  void productionModeIgnoresFileBackedCapabilitiesOverride() throws Exception {
    Path payload = tempDir.resolve("caps.json");
    Files.writeString(payload, "{\"features\":[\"fake-production-capability\"]}", StandardCharsets.UTF_8);
    System.setProperty("app.api.fake_capabilities", payload.toString());
    System.setProperty("justsearch.prod", "true");
    TestResolvedConfigHelper.storeFromEnvironment();

    try (HeadAssembly bootstrap = new HeadAssembly(new io.justsearch.core.execution.TestEngineExecutors(), new NoopTelemetry(), new ConfigManagerBootstrap(), null, new io.justsearch.app.services.settings.UiSettingsStore(io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY), null, io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
        new io.justsearch.app.services.lease.OperationLeaseServiceImpl(),
        org.mockito.Mockito.mock(io.justsearch.app.api.EngineAdmissionService.class))) {
      FakeHttpExchange exchange = fakeExchange("GET", URI.create("http://localhost/infra/capabilities"));
      bootstrap.capabilitiesHandler().handle(exchange);
      assertEquals(200, exchange.statusCode);
      assertFalse(exchange.body().contains("fake-production-capability"));
    }
  }

  @Test
  void fileBackedCapabilitiesHandlerHandlesMissingFile() throws Exception {
    Path missing = tempDir.resolve("missing.json");
    System.setProperty("app.api.fake_capabilities", missing.toString());

    try (HeadAssembly bootstrap = new HeadAssembly(new io.justsearch.core.execution.TestEngineExecutors(), new NoopTelemetry(), new ConfigManagerBootstrap(), null, new io.justsearch.app.services.settings.UiSettingsStore(io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY), null, io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
        new io.justsearch.app.services.lease.OperationLeaseServiceImpl(),
        org.mockito.Mockito.mock(io.justsearch.app.api.EngineAdmissionService.class))) {
      FakeHttpExchange exchange = fakeExchange("GET", URI.create("http://localhost/infra/capabilities"));
      bootstrap.capabilitiesHandler().handle(exchange);
      assertEquals(503, exchange.statusCode);
      assertTrue(exchange.body().contains("Capabilities fixture not found"));
    }
  }

  @Test
  void fileBackedCapabilitiesHandlerRejectsUnsupportedMethod() throws Exception {
    Path payload = tempDir.resolve("caps.json");
    Files.writeString(payload, "{\"features\":[]}", StandardCharsets.UTF_8);
    System.setProperty("app.api.fake_capabilities", payload.toString());

    try (HeadAssembly bootstrap = new HeadAssembly(new io.justsearch.core.execution.TestEngineExecutors(), new NoopTelemetry(), new ConfigManagerBootstrap(), null, new io.justsearch.app.services.settings.UiSettingsStore(io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY), null, io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
        new io.justsearch.app.services.lease.OperationLeaseServiceImpl(),
        org.mockito.Mockito.mock(io.justsearch.app.api.EngineAdmissionService.class))) {
      FakeHttpExchange exchange = fakeExchange("POST", URI.create("http://localhost/infra/capabilities"));
      bootstrap.capabilitiesHandler().handle(exchange);
      assertEquals(405, exchange.statusCode);
      assertEquals("text/plain; charset=utf-8", exchange.getResponseHeaders().getFirst("Content-Type"));
    }
  }

  /**
   * Tempdoc 374 alpha.26 hotfix regression guard — adapted for the slice 1.1.a (430 / 429
   * substrate) merge that replaced the reflective {@code knowledgeServerRef} mechanism with
   * direct method dispatch via {@code defaultFacade.lateBindWorkerServices(...)}.
   *
   * <p>Original round-15 evidence: alpha.25 U14-B introduced a fatal NPE because the
   * {@code knowledgeServerRef} field was {@code final}; constructing with
   * {@code knowledgeServer=null} left it null and the late-bind path crashed.
   * Alpha.26 made the field {@code volatile} non-final.
   *
   * <p>Post-merge: the 429 substrate retired the reflective field. The same contract is now
   * enforced by direct dispatch through {@code defaultFacade.lateBindWorkerServices(client,
   * client, documentService)} — compile-time checked, no reflection needed. The behavioral
   * pin retained here is: constructing with {@code knowledgeServer=null} and calling
   * {@code connectKnowledgeServer(null)} must not throw.
   */
  @Test
  void connectKnowledgeServerLateBindDoesNotThrowOnNullCtor() throws Exception {
    // Construct with knowledgeServer=null (the round-15 cold-start sequence).
    try (HeadAssembly bootstrap =
        new HeadAssembly(new io.justsearch.core.execution.TestEngineExecutors(), new NoopTelemetry(), new ConfigManagerBootstrap(), null, new io.justsearch.app.services.settings.UiSettingsStore(io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY), null, io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
        new io.justsearch.app.services.lease.OperationLeaseServiceImpl(),
        org.mockito.Mockito.mock(io.justsearch.app.api.EngineAdmissionService.class))) {

      // connectKnowledgeServer(null) is documented as a no-op (early return on ks == null).
      // Post-merge, this is the entire contract — the 429 substrate dispatches via
      // defaultFacade.lateBindWorkerServices when ks != null, no reflective field probe.
      bootstrap.connectKnowledgeServer(null);
    }
  }

  @Test
  void chooseFirstNonBlankPrefersFirstValue() throws Exception {
    Method method =
        HeadAssembly.class.getDeclaredMethod("chooseFirstNonBlank", String[].class);
    method.setAccessible(true);
    Object result = method.invoke(null, new Object[] {new String[] {"  ", "VALUE", "ignored"}});
    assertEquals("VALUE", result);
  }

  @Test
  void chooseFirstNonBlankReturnsNullWhenEmpty() throws Exception {
    Method method =
        HeadAssembly.class.getDeclaredMethod("chooseFirstNonBlank", String[].class);
    method.setAccessible(true);
    Object result = method.invoke(null, new Object[] {new String[] {null, "   ", ""}});
    assertNull(result);
  }

  @Test
  void chooseFirstNonBlankRemainsPrivateStaticVarargsShim() throws Exception {
    Method method =
        HeadAssembly.class.getDeclaredMethod("chooseFirstNonBlank", String[].class);
    assertTrue(Modifier.isPrivate(method.getModifiers()));
    assertTrue(Modifier.isStatic(method.getModifiers()));
    assertTrue(method.isVarArgs());
  }

  @Test
  void fileBackedCapabilitiesHandlerDirectInvocation() throws Exception {
    Path payload = tempDir.resolve("caps.json");
    String json = "{\"features\":[\"a\",\"b\"]}";
    Files.writeString(payload, json, StandardCharsets.UTF_8);
    // §31 Phase 1 followup: FileBackedCapabilitiesHandler was extracted to a top-level class in
    // bootstrap/phases/ during the structural cleanup. Reflection load by FQN.
    Class<?> handlerClass =
        Class.forName(
            "io.justsearch.app.services.bootstrap.phases.FileBackedCapabilitiesHandler");
    assertNotNull(handlerClass, "FileBackedCapabilitiesHandler not found");
    Constructor<?> ctor = handlerClass.getDeclaredConstructor(Path.class);
    ctor.setAccessible(true);
    Object handler = ctor.newInstance(payload);
    Method handle = handlerClass.getDeclaredMethod("handle", HttpExchange.class);
    handle.setAccessible(true);

    FakeHttpExchange exchange = fakeExchange("GET", URI.create("http://localhost/infra/capabilities"));
    handle.invoke(handler, exchange);
    assertEquals(200, exchange.statusCode);
    assertEquals(json, exchange.body());
  }

  private FakeHttpExchange fakeExchange(String method, URI uri) {
    return new FakeHttpExchange(method, uri);
  }

  private static final class NoopTelemetry implements Telemetry {
    @Override
    public void close() {}
  }

  private static final class FakeHttpExchange extends HttpExchange {
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final String method;
    private final URI uri;
    private final ByteArrayInputStream requestBody = new ByteArrayInputStream(new byte[0]);
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private int statusCode = -1;

    FakeHttpExchange(String method, URI uri) {
      this.method = method;
      this.uri = uri;
    }

    String body() {
      return responseBody.toString(StandardCharsets.UTF_8);
    }

    @Override
    public Headers getRequestHeaders() {
      return requestHeaders;
    }

    @Override
    public Headers getResponseHeaders() {
      return responseHeaders;
    }

    @Override
    public URI getRequestURI() {
      return uri;
    }

    @Override
    public String getRequestMethod() {
      return method;
    }

    @Override
    public HttpContext getHttpContext() {
      return null;
    }

    @Override
    public void close() {}

    @Override
    public InputStream getRequestBody() {
      return requestBody;
    }

    @Override
    public OutputStream getResponseBody() {
      return responseBody;
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) {
      this.statusCode = rCode;
    }

    @Override
    public int getResponseCode() {
      return statusCode;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
      return InetSocketAddress.createUnresolved("localhost", 0);
    }

    @Override
    public InetSocketAddress getLocalAddress() {
      return InetSocketAddress.createUnresolved("localhost", 0);
    }

    @Override
    public String getProtocol() {
      return "HTTP/1.1";
    }

    @Override
    public Object getAttribute(String name) {
      return null;
    }

    @Override
    public void setAttribute(String name, Object value) {}

    @Override
    public void setStreams(InputStream i, OutputStream o) {}

    @Override
    public HttpPrincipal getPrincipal() {
      return null;
    }
  }
}
