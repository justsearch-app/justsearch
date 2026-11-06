/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.DownloadedFiles;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.engine.DefaultEngineProcessResources;
import io.justsearch.app.engine.EngineRoot;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.ai.install.AiInstallService;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.settings.FixedSettingsComponentComposer;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerConfig;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.InstallPlanner;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.ui.api.LifecycleShutdownBridge;
import io.justsearch.ui.api.LocalApiServer;
import io.justsearch.ui.api.UpgradeShutdownBridge;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Design D4, found live: the Engine composes its services with no index half yet, so a usage probe
 * bound to that construction argument said "Search is still starting" for the life of the process.
 * Only the production composition shows this: the real {@code buildApi} with the real Engine root,
 * the real index start and the real handover, in a temporary data folder. A fake probe cannot.
 */
final class HeadlessAppRemovalUsageWiringTest {
  private static final String STARTING = "Search is still starting - try again in a moment";

  @TempDir Path tempDir;

  @Test
  @Timeout(120)
  void bestMatchFirstRemovalFollowsTheLiveIndexHalfThroughTheProductionComposition() throws Exception {
    assumeTrue(EnvRegistry.MODELS_DIR.get().isEmpty(),
        "the removal below must run in this test's own models folder");
    Path dataDir = Files.createDirectories(tempDir.resolve("data"));
    Path indexDir = Files.createDirectories(dataDir.resolve("index"));
    Map<String, String> properties = Map.of(
        "justsearch.data.dir", dataDir.toString(), "justsearch.home", dataDir.toString(),
        "justsearch.api.port", "0", "justsearch.prod", "false", "justsearch.ai.disabled", "true",
        "justsearch.index.base_path", indexDir.toString(),
        "justsearch.ssot.path", dataDir.resolve("SSOT").toString(),
        "user.home", dataDir.resolve("home").toString());
    ConfigStore previousGlobal = ConfigStore.globalOrNull();
    Field headlessStore = HeadlessApp.class.getDeclaredField("configStore");
    headlessStore.setAccessible(true);
    Object previousHeadlessStore = headlessStore.get(null);
    Map<String, String> previousProperties = setProperties(properties);
    // The policy owner publishes these as it starts; they are restored with the others.
    for (String key : List.of("policy.gpu_acceleration_enabled",
        "justsearch.policy.disallowExternalInferenceServers")) {
      previousProperties.put(key, System.getProperty(key));
    }
    HeadlessApp.ApiPhaseResult api = null;
    KnowledgeServerBootstrap indexHalf = null;
    EngineRoot root = null;
    RuntimeManifestPublisher manifest = null;
    SqliteOperationStore operations = null;
    DefaultEngineProcessResources resources = null;
    try {
      var settings = new UiSettingsStore(
          UiSettingsStore.PersistenceMode.READ_WRITE, dataDir.resolve("ui/settings.json"));
      UiSettings off = new UiSettings();
      off.setRerankerEnabled(false);
      settings.replacePrepared(settings.prepare(off, new SettingsWitness(0, null)));
      // Built as the settings owner rebuilds it, so saving the decline changes no process source.
      var resolved = ConfigStoreRebuilder.prepare(settings.inspect().settings());
      var store = new ConfigStore(resolved);
      ConfigStore.setGlobal(store);
      headlessStore.set(null, store);

      operations = new SqliteOperationStore(dataDir.resolve("operations.db"));
      resources = new DefaultEngineProcessResources(store.publicationLock());
      var composer = new FixedSettingsComponentComposer(resources.components());
      var settingsOwner = new SettingsCommitCoordinator(settings, store, () -> {},
          candidate -> OperationResult.success("Settings committed"),
          resources.admission()::isClosing, composer);
      var attempts = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
          Set.of(OperationKind.values()), settingsOwner, new RecordedIngestPlanResolver());
      root = EngineRoot.forProcess(operations, attempts, 30_000L, 100, code -> {},
          ManagedChildRegistry.noop(), () -> {}, OperationAuthority.load(dataDir), store, resources);
      manifest = new RuntimeManifestPublisher(dataDir);
      var config = new HeadlessApp.ConfigPhaseResult(settings, settings.load(), resolved, store, dataDir);
      Telemetry telemetry = () -> {};
      api = buildApi(new HeadlessApp.InfraPhaseResult(config, telemetry, null), settings, manifest,
          root, composer);
      // Production has the policy owner's values before it builds config; publish them the same way.
      store.update(ConfigStoreRebuilder.prepare(settings.inspect().settings()));
      AiInstallService install = api.bootstrap().serviceOut().aiInstallHelper();
      Path partial = writePartial(install, dataDir);

      assertEquals(STARTING, reranker(install).reason,
          "no index half yet: its query owners really cannot be asked");

      indexHalf = startIndexHalf(root, dataDir);
      connect(api, indexHalf);

      DownloadedFiles.Part ready = awaitRemovable(install);
      assertEquals(List.of(dataDir.resolve("models").relativize(partial).toString().replace('\\', '/')),
          ready.delete.stream().map(file -> file.path).toList(), "the staging file is what is offered");

      DownloadedFiles.Removal removal = install.removeFiles("reranker", ready.previewDigest)
          .completion().toCompletableFuture().get(30, TimeUnit.SECONDS);

      assertEquals("removed", removal.state, removal.message);
      assertFalse(Files.exists(partial), "the composed removal deleted the file");
      assertTrue(settings.load().getDeclinedAiPackages().contains("reranker"),
          "the composed removal recorded the decline");

      Files.write(partial, new byte[] {1});
      indexHalf.close();
      assertEquals(STARTING, reranker(install).reason,
          "a closed index half is asked at the call: the earlier free answer is not remembered");
      assertTrue(Files.exists(partial), "nothing was deleted while it cannot be asked");
    } finally {
      Throwable failure = null;
      if (api != null) {
        failure = close(failure, api.apiServer()::stop);
        failure = close(failure, api.bootstrap()::close);
      }
      if (indexHalf != null) failure = close(failure, indexHalf::close);
      if (manifest != null) failure = close(failure, manifest::close);
      if (root != null) failure = close(failure, root::close);
      if (operations != null) failure = close(failure, operations::close);
      if (resources != null) failure = close(failure, resources::close);
      headlessStore.set(null, previousHeadlessStore);
      TestResolvedConfigHelper.restoreGlobal(previousGlobal);
      restoreProperties(previousProperties);
      if (failure instanceof Exception thrown) throw thrown;
      if (failure instanceof Error thrown) throw thrown;
    }
  }

  /** A download's staging file: removable without the registry's real model bytes. */
  private static Path writePartial(AiInstallService install, Path dataDir) throws Exception {
    ModelPackage pkg = install.getManifest().packages().stream()
        .filter(candidate -> candidate.id().equals("reranker")).findFirst().orElseThrow();
    var variant = pkg.variants().get(0);
    Path partial = dataDir.resolve("models").resolve(InstallPlanner.effectiveTargetDir(pkg, variant))
        .resolve(variant.filename() + InstallPlanner.PARTIAL_SUFFIX);
    Files.createDirectories(partial.getParent());
    return Files.write(partial, new byte[] {1});
  }

  private static DownloadedFiles.Part reranker(AiInstallService install) {
    return install.downloadedFiles().parts.stream()
        .filter(part -> part.id.equals("reranker")).findFirst().orElseThrow();
  }

  /** The index half may still be composing its models (a busy answer); it never stays unknown. */
  private static DownloadedFiles.Part awaitRemovable(AiInstallService install) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    DownloadedFiles.Part part = reranker(install);
    while (!part.removable && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(50);
      part = reranker(install);
    }
    assertEquals(null, part.reason, "the connected index half's query owners hold no session");
    assertTrue(part.removable);
    return part;
  }

  private static KnowledgeServerBootstrap startIndexHalf(EngineRoot root, Path dataDir) throws Exception {
    var config = new KnowledgeServerConfig(
        false, dataDir, dataDir, dataDir, 30_000, 1_000, 1, 5_000, 1_000, 0, 100, 0, 0);
    Method start = HeadlessApp.class.getDeclaredMethod("tryStartKnowledgeServer",
        KnowledgeServerConfig.class, EngineRoot.class, boolean.class, Consumer.class);
    start.setAccessible(true);
    var constructed = new CompletableFuture<KnowledgeServerBootstrap>();
    Consumer<KnowledgeServerBootstrap> onConstructed = constructed::complete;
    invoke(start, config, root, false, onConstructed);
    KnowledgeServerBootstrap owner = constructed.getNow(null);
    assertNotNull(owner);
    assertTrue(owner.hasClient(), "the real index half started");
    return owner;
  }

  private static void connect(HeadlessApp.ApiPhaseResult api, KnowledgeServerBootstrap indexHalf)
      throws Exception {
    Method connect = HeadlessApp.class.getDeclaredMethod("connectAndBind",
        io.justsearch.app.services.HeadAssembly.class, LocalApiServer.class,
        KnowledgeServerBootstrap.class, String.class);
    connect.setAccessible(true);
    invoke(connect, api.bootstrap(), api.apiServer(), indexHalf, null);
  }

  private static HeadlessApp.ApiPhaseResult buildApi(HeadlessApp.InfraPhaseResult infra,
      UiSettingsStore settings, RuntimeManifestPublisher manifest, EngineRoot root,
      FixedSettingsComponentComposer composer) throws Exception {
    Method build = HeadlessApp.class.getDeclaredMethod("buildApi",
        HeadlessApp.InfraPhaseResult.class, UiSettingsStore.class, RuntimeManifestPublisher.class,
        ManagedChildRegistry.class, UpgradeShutdownBridge.class,
        LifecycleShutdownBridge.class, EngineRoot.class, FixedSettingsComponentComposer.class,
        CompletableFuture.class, io.justsearch.app.api.runtime.child.ChildControl.class);
    build.setAccessible(true);
    return (HeadlessApp.ApiPhaseResult) invoke(build, infra, settings, manifest,
        ManagedChildRegistry.noop(), new UpgradeShutdownBridge(),
        new LifecycleShutdownBridge(), root, composer, new CompletableFuture<Void>(), null);
  }

  private static Object invoke(Method method, Object... arguments) throws Exception {
    try {
      return method.invoke(null, arguments);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception cause) throw cause;
      if (failure.getCause() instanceof Error cause) throw cause;
      throw failure;
    }
  }

  private static Map<String, String> setProperties(Map<String, String> overrides) {
    Map<String, String> previous = new LinkedHashMap<>();
    overrides.forEach((key, value) -> {
      previous.put(key, System.getProperty(key));
      System.setProperty(key, value);
    });
    return previous;
  }

  private static void restoreProperties(Map<String, String> previous) {
    previous.forEach((key, value) -> {
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
