/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.engine.EngineRoot;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.lifecycle.RegistryBackedCapability;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.component.ComponentState;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.ui.api.LifecycleShutdownBridge;
import io.justsearch.ui.api.UpgradeShutdownBridge;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HeadlessAppComponentRegistryCoverageTest {
  private static final Set<String> EXPECTED_COMPONENTS =
      Set.of("api", "index", "encoders", "generative");
  private static final Set<String> REGISTRATION_EXCEPTIONS = Set.of();

  @TempDir Path tempDir;

  @Test
  void productionApiPhaseRegistersAllFourComponentsInTheEngineRootRegistry() throws Exception {
    Path dataDir = Files.createDirectories(tempDir.resolve("data"));
    Path indexDir = Files.createDirectories(dataDir.resolve("index"));
    Map<String, String> propertyOverrides =
        Map.of(
            "justsearch.data.dir", dataDir.toString(),
            "justsearch.home", dataDir.toString(),
            "justsearch.api.port", "0",
            "justsearch.prod", "false",
            "justsearch.ai.disabled", "true");
    ConfigStore previousGlobal = ConfigStore.globalOrNull();
    Field headlessConfigStore = HeadlessApp.class.getDeclaredField("configStore");
    headlessConfigStore.setAccessible(true);
    Object previousHeadlessStore = headlessConfigStore.get(null);
    Map<String, String> previousProperties = captureAndSetProperties(propertyOverrides);

    HeadlessApp.ApiPhaseResult apiPhase = null;
    EngineRoot root = null;
    RuntimeManifestPublisher manifest = null;
    SqliteOperationStore operations = null;
    try {
      operations = new SqliteOperationStore(dataDir.resolve("operations.db"));
      var resolved =
          TestResolvedConfigHelper.fromEntries(
              Map.of(
                  EnvRegistry.DATA_DIR.configKey(), dataDir.toString(),
                  EnvRegistry.INDEX_BASE_PATH.configKey(), indexDir.toString(),
                  EnvRegistry.API_PORT.configKey(), "0",
                  EnvRegistry.AI_DISABLED.configKey(), "true",
                  EnvRegistry.PROD_MODE.configKey(), "false"));
      var store = new ConfigStore(resolved);
      ConfigStore.setGlobal(store);
      headlessConfigStore.set(null, store);

      var settings =
          new UiSettingsStore(
              UiSettingsStore.PersistenceMode.IN_MEMORY, dataDir.resolve("ui/settings.json"));
      var configPhase =
          new HeadlessApp.ConfigPhaseResult(
              settings, settings.load(), resolved, store, dataDir);
      Telemetry telemetry = () -> {};
      var infraPhase = new HeadlessApp.InfraPhaseResult(configPhase, telemetry, null);
      manifest = new RuntimeManifestPublisher(dataDir);
      root = new EngineRoot(operations, mock(OperationAttemptRunner.class), 30_000L, 100);

      apiPhase =
          buildApi(
              infraPhase,
              settings,
              manifest,
              io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
              new UpgradeShutdownBridge(),
              new LifecycleShutdownBridge(),
              root);

      var components = root.components().snapshot().components();
      Set<String> registeredWithDependencies =
          components.stream()
              .filter(component -> !component.spec().dependencyKeys().isEmpty())
              .map(component -> component.spec().name())
              .collect(Collectors.toUnmodifiableSet());
      Set<String> explicitlyAccountedFor = new java.util.HashSet<>(EXPECTED_COMPONENTS);
      explicitlyAccountedFor.addAll(REGISTRATION_EXCEPTIONS);

      assertTrue(
          components.size() >= 4,
          "the coverage check must not pass after the production component set becomes vacuous");
      assertEquals(
          explicitlyAccountedFor,
          registeredWithDependencies,
          "every root-wired component with configuration dependencies needs coverage or an"
              + " explicit exception");
      assertEquals(
          EXPECTED_COMPONENTS,
          components.stream().map(component -> component.spec().name()).collect(Collectors.toSet()));
      assertTrue(
          components.stream().allMatch(component -> !component.spec().dependencyKeys().isEmpty()));

      var api = component(components, "api");
      assertEquals(ComponentState.READY, api.state());
      assertNotNull(api.appliedVersion());
      assertEquals(api.appliedVersion(), api.desiredVersion());
      assertEquals("boundPort=" + apiPhase.port(), api.evidence());
      assertTrue(apiPhase.port() > 0);

      var generative = component(components, "generative");
      assertEquals(ComponentState.ABSENT, generative.state());
      assertNull(
          generative.reasonCode(),
          "disabled optional inference is absent intent, not an observed runtime failure");
      assertEquals(ComponentState.ABSENT, component(components, "index").state());
      assertEquals(ComponentState.ABSENT, component(components, "encoders").state());

      // Actual HeadAssembly/OrchestrationPhase wiring plus actual CoreApiAssembly attachment.
      // No manual registration, trigger subscription, thunk replacement or monitoring timer.
      var trigger = apiPhase.bootstrap().substrate().health().readinessReconciliationTrigger();
      var executorField = trigger.getClass().getDeclaredField("executor");
      executorField.setAccessible(true);
      var samplerExecutor = (java.util.concurrent.ExecutorService) executorField.get(trigger);
      drainSampler(samplerExecutor);
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      var nextSample = new java.util.concurrent.atomic.AtomicReference<>(
          new java.util.concurrent.CountDownLatch(1));
      var client = mock(io.justsearch.app.services.worker.KnowledgeClient.class);
      org.mockito.Mockito.when(client.getWorkerOperationalView(org.mockito.ArgumentMatchers.any()))
          .thenAnswer(invocation -> {
            calls.incrementAndGet();
            nextSample.get().countDown();
            return io.justsearch.app.api.status.WorkerOperationalView.fallback("READY");
          });
      var knowledgeServer = mock(io.justsearch.app.services.worker.KnowledgeServerBootstrap.class);
      var workerCapability = new RegistryBackedCapability(root.components(), "index", "worker");
      org.mockito.Mockito.when(knowledgeServer.client()).thenReturn(client);
      org.mockito.Mockito.when(knowledgeServer.hasClient()).thenReturn(true);
      org.mockito.Mockito.when(knowledgeServer.workerCapability()).thenReturn(workerCapability);
      org.mockito.Mockito.when(knowledgeServer.gpuScheduling())
          .thenReturn(new io.justsearch.core.scheduling.GpuSchedulingGauge());
      root.indexComponent().transition(ComponentState.READY, null, null);
      drainSampler(samplerExecutor); // A legacy capability event cannot satisfy the bind assertion.
      assertEquals(0, calls.get());
      var connect = HeadlessApp.class.getDeclaredMethod("connectAndBind",
          io.justsearch.app.services.HeadAssembly.class, io.justsearch.ui.api.LocalApiServer.class,
          io.justsearch.app.services.worker.KnowledgeServerBootstrap.class, String.class);
      connect.setAccessible(true);
      connect.invoke(null, apiPhase.bootstrap(), apiPhase.apiServer(), knowledgeServer, null);
      assertTrue(nextSample.get().await(5, java.util.concurrent.TimeUnit.SECONDS),
          "the real post-bind handover must request the first observation");
      drainSampler(samplerExecutor);
      int beforeStop = calls.get();
      assertEquals(1, beforeStop, "binding must schedule exactly one real Worker observation");
      nextSample.set(new java.util.concurrent.CountDownLatch(1));
      apiPhase.apiServer().stop();
      assertTrue(nextSample.get().await(5, java.util.concurrent.TimeUnit.SECONDS),
          "the real API component's ABSENT transition must drive the real sampler");
      drainSampler(samplerExecutor);
      assertEquals(beforeStop + 1, calls.get());
      assertEquals(ComponentState.ABSENT,
          component(root.components().snapshot().components(), "api").state());
    } finally {
      Throwable cleanupFailure = null;
      if (apiPhase != null) {
        cleanupFailure = close(cleanupFailure, apiPhase.apiServer()::stop);
        cleanupFailure = close(cleanupFailure, apiPhase.bootstrap()::close);
      }
      if (manifest != null) cleanupFailure = close(cleanupFailure, manifest::close);
      if (root != null) {
        cleanupFailure = close(cleanupFailure, root::close);
      }
      if (operations != null) cleanupFailure = close(cleanupFailure, operations::close);
      if (root != null) {
        cleanupFailure = close(cleanupFailure, root.components()::close);
        cleanupFailure = close(cleanupFailure, root.executors()::close);
      }
      headlessConfigStore.set(null, previousHeadlessStore);
      TestResolvedConfigHelper.restoreGlobal(previousGlobal);
      restoreProperties(previousProperties);
      if (cleanupFailure instanceof Exception failure) throw failure;
      if (cleanupFailure instanceof Error failure) throw failure;
    }
  }

  private static void drainSampler(java.util.concurrent.ExecutorService executor) throws Exception {
    executor.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
  }

  private static io.justsearch.core.component.EngineComponentSnapshot.Component component(
      java.util.List<io.justsearch.core.component.EngineComponentSnapshot.Component> components,
      String name) {
    return components.stream()
        .filter(component -> component.spec().name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static HeadlessApp.ApiPhaseResult buildApi(
      HeadlessApp.InfraPhaseResult infraPhase,
      UiSettingsStore settings,
      RuntimeManifestPublisher manifest,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      UpgradeShutdownBridge upgradeShutdown,
      LifecycleShutdownBridge lifecycleShutdown,
      EngineRoot root)
      throws Exception {
    Method method =
        HeadlessApp.class.getDeclaredMethod(
            "buildApi",
            HeadlessApp.InfraPhaseResult.class,
            UiSettingsStore.class,
            RuntimeManifestPublisher.class,
            io.justsearch.app.api.runtime.ManagedChildRegistry.class,
            UpgradeShutdownBridge.class,
            LifecycleShutdownBridge.class,
            EngineRoot.class);
    method.setAccessible(true);
    try {
      return (HeadlessApp.ApiPhaseResult)
          method.invoke(
              null,
              infraPhase,
              settings,
              manifest,
              childRegistry,
              upgradeShutdown,
              lifecycleShutdown,
              root);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception cause) throw cause;
      if (failure.getCause() instanceof Error cause) throw cause;
      throw failure;
    }
  }

  private static Map<String, String> captureAndSetProperties(Map<String, String> overrides) {
    Map<String, String> previous = new LinkedHashMap<>();
    overrides.forEach(
        (key, value) -> {
          previous.put(key, System.getProperty(key));
          System.setProperty(key, value);
        });
    return previous;
  }

  private static void restoreProperties(Map<String, String> previous) {
    previous.forEach(
        (key, value) -> {
          if (value == null) System.clearProperty(key);
          else System.setProperty(key, value);
        });
  }

  private static Throwable close(Throwable earlier, ThrowingClose close) {
    try {
      close.run();
      return earlier;
    } catch (Throwable failure) {
      if (earlier == null) return failure;
      earlier.addSuppressed(failure);
      return earlier;
    }
  }

  @FunctionalInterface
  private interface ThrowingClose {
    void run() throws Exception;
  }
}
