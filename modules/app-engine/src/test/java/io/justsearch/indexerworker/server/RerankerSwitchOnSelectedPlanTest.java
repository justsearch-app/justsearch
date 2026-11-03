/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.engine.EngineRoot;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.gpu.CudaDevice;
import io.justsearch.app.services.gpu.DeviceFacts;
import io.justsearch.app.services.gpu.DevicePlanState;
import io.justsearch.app.services.gpu.GpuProfileResolver;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ort.DeviceArenaPlans;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArenaPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Best match first switched back on with a retained custom CUDA reranker while discovery finds a
 * different installed model. The Engine's query owner, the Worker's preparation and the Engine's
 * real device planning together: the reranker warms under the plan of the files the preparation
 * selected (model, variant, size), and the commit publishes that plan, not the one of the model
 * discovery would pick. On f111a68d66 both plans budgeted the discovered model.
 */
final class RerankerSwitchOnSelectedPlanTest {
  private static final double MIB = 1024.0 * 1024.0;
  private final DevicePlanState state = DevicePlanState.isolated();

  @AfterEach
  void restore() {
    ConfigStoreRebuilder.rememberDevicePlanner(null);
    state.publish(GpuProfileResolver.Resolution.unresolved());
    DeviceArenaPlans.withdraw(0);
  }

  @Test
  void switchOnWarmsUnderAndPublishesThePlanOfTheRetainedCustomFiles(@TempDir Path dir)
      throws Exception {
    // Discovery: the installed reranker under the data folder, a 1 MiB CUDA (FP16) model.
    Path installed = Files.createDirectories(dir.resolve("models/onnx/reranker"));
    Files.write(installed.resolve("model_fp16.onnx"), new byte[1 << 20]);
    Files.writeString(installed.resolve("tokenizer.json"), "installed-tokenizer");
    // Retained while off: a 3 MiB custom CUDA (FP16) model elsewhere.
    Path custom = Files.createDirectories(dir.resolve("custom-reranker"));
    Path model = Files.write(custom.resolve("model_fp16.onnx"), new byte[3 << 20]);
    Path tokenizer = Files.writeString(custom.resolve("tokenizer.json"), "custom-tokenizer");
    var retained = QueryRoleSelection.Role.selected("model_fp16.onnx", witness(model),
        witness(tokenizer), ModelPrecision.FP16, ExecutionProvider.CUDA);

    ResolvedConfig off = config(dir, "false");
    ResolvedConfig desired = config(dir, "true");
    var planner = GpuProfileResolver.planner(() -> device(12_282),
        InferenceCompositionRoot::gpuRoleWeightsMiB);
    var store = new ConfigStore(off);
    installDevicePlanning(store, planner);
    state.publish(planner.plan(off));
    GpuArenaPlan offPlan = DeviceArenaPlans.current(0).orElseThrow();
    assertTrue(offPlan.planned(), "a 12 GB device runs a planned profile");
    assertTrue(offPlan.role(EncoderRole.RERANKER).isEmpty(), "off publishes no reranker budget");
    // The fixture's premise: discovery budgets the installed model, not the retained one.
    assertEquals(1.0, rerankerWeight(planner.plan(desired).plan().orElseThrow()), 1e-9,
        "discovery finds the installed 1 MiB model");

    var root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
        30_000L, 5_000);
    root.followDevicePlan(state);
    try (var worker = new OffWorker(dir, off)) {
      var field = EngineRoot.class.getDeclaredField("server");
      field.setAccessible(true);
      field.set(root, worker.server);
      var warmedUnder = new AtomicReference<GpuArenaPlan>();
      var composedWith = new AtomicReference<QueryRoleSelection>();
      try (var composition = Mockito.mockStatic(InferenceCompositionRoot.class,
          Mockito.CALLS_REAL_METHODS)) {
        composition.when(() -> InferenceCompositionRoot.estimateQueryFootprintBytes(any(), any(),
            any())).thenReturn(1024L);
        composition.when(() -> InferenceCompositionRoot.sourceQueryReleasableBytes(any()))
            .thenReturn(0L);
        composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(),
            any(), any())).thenAnswer(call -> {
              // The session is composed and warmed here, under the device's current plan.
              warmedUnder.set(DeviceArenaPlans.current(0).orElseThrow());
              composedWith.set(call.getArgument(1));
              return worker.surface(call.getArgument(0), call.getArgument(1));
            });
        UiSettings on = new UiSettings();
        on.setRerankerEnabled(true);
        on.setRerankerRestoreSelection(retained);
        var prepared = root.queryRoleSettingsOwner(() -> disabled())
            .prepare(on, desired, Set.of("rerankerEnabled"));
        try {
          assertEquals(retained, composedWith.get().reranker(),
              "the Worker selected the retained custom files");
          assertEquals(3.0, rerankerWeight(warmedUnder.get()), 1e-9,
              "the reranker warms under a budget for the 3 MiB file it loads");
          // The commit publishes the configuration; the Engine publishes its plan.
          store.notifyListeners(store.swap(desired));
          GpuArenaPlan published = DeviceArenaPlans.current(0).orElseThrow();
          assertEquals(3.0, rerankerWeight(published), 1e-9,
              "the commit publishes the plan of the selected files");
          assertEquals(warmedUnder.get(), published, "the published plan is the staged one");
          assertNotEquals(offPlan, published);
        } finally {
          prepared.abort();
        }
      }
    }
  }

  private static double rerankerWeight(GpuArenaPlan plan) {
    return plan.role(EncoderRole.RERANKER).orElseThrow(
        () -> new AssertionError("no reranker budget in " + plan.roles().keySet())).weightsMiB();
  }

  private void installDevicePlanning(ConfigStore store, ConfigStoreRebuilder.DevicePlanner planner)
      throws Exception {
    var install = EngineRoot.class.getDeclaredMethod("installDevicePlanning", ConfigStore.class,
        ConfigStoreRebuilder.DevicePlanner.class, DevicePlanState.class);
    install.setAccessible(true);
    install.invoke(null, store, planner, state);
  }

  private static ResolvedConfig config(Path dir, String enabled) {
    return new ResolvedConfigBuilder().putDefault("justsearch.data.dir", dir.toString())
        .putSettings("justsearch.rerank.enabled", enabled).build();
  }

  private static DeviceFacts device(double totalMiB) {
    CudaDevice device = new CudaDevice(0, Optional.of("luid_0x00000000_0x0000d7a2"),
        Optional.of("GPU-8b20006f-755d-8a37-9762-7b385907e813"), Optional.of("0000:01:00.0"),
        (long) (totalMiB * MIB));
    return new DeviceFacts(DeviceFacts.ProbeStatus.RAN_OK, 0, 12_080, List.of(device),
        Optional.of(device));
  }

  private static QueryRoleSelection disabled() {
    return new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.disabled());
  }

  private static ModelFile witness(Path path) throws Exception {
    byte[] bytes = Files.readAllBytes(path);
    return new ModelFile(path.toAbsolutePath().normalize(), HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length);
  }

  /** A physical query owner serving with Best match first off: no reranker, a disabled witness. */
  private static final class OffWorker implements AutoCloseable {
    final TestEngineExecutors executors = new TestEngineExecutors();
    final KnowledgeServer server;

    OffWorker(Path dir, ResolvedConfig configuration) throws Exception {
      var projection = EncoderConfigurationProjection.from(configuration);
      var generation = mock(GenerationModelSelection.class);
      var index = new EncoderSet(new InferenceSurface(Optional.empty(), Optional.empty(),
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
          new InferenceSurface.ComponentObservation(Optional.of(
              EncoderConfigurationProjection.from(configuration, generation).indexDigest()),
              Set.of(), Set.of())), new EncoderSet.ModelIdentity(
          IndexFingerprint.ModelFingerprint.present("test"),
          IndexFingerprint.ModelFingerprint.present("test"),
          IndexFingerprint.ModelFingerprint.present("test"), false, 768));
      var queryOff = new QueryRoleSet(new InferenceSurface(Optional.empty(), Optional.empty(),
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
          new InferenceSurface.ComponentObservation(Optional.of(projection.queryDigest()),
              Set.of(), Set.of(), Optional.of(disabled()))));
      var component = mock(ComponentHandle.class);
      var spec = new ComponentSpec("encoders", false, Set.of(),
          ComponentSpec.ComposeCapability.BESIDE, Duration.ZERO, 0);
      when(component.snapshot()).thenReturn(new EngineComponentSnapshot.Component(spec,
          ComponentState.READY, null, Instant.now(), 0L, projection.digest(), projection.digest(),
          null, 0, null));
      when(component.prepareReplacement(any())).thenReturn(
          mock(io.justsearch.core.component.EngineComponentRegistry.PreparedBatch.class));
      var worker = new io.justsearch.indexerworker.WorkerConfig(dir, 60_000L, "test",
          java.util.Map.of(), "test", 500L);
      server = new KnowledgeServer(executors, worker, null,
          io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
          RecordedIngestionLifecycle.denied(), null, component, configuration,
          () -> configuration, new java.util.concurrent.locks.ReentrantReadWriteLock(),
          () -> new io.justsearch.core.component.DeviceMemoryLine(4096L, 4096L));
      server.signalBus = mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class);
      var runtime = mock(RunningRuntime.class);
      server.infraCtx = new InfraContext(worker,
          mock(io.justsearch.indexerworker.queue.JobQueue.class), () -> runtime, () -> runtime,
          server.signalBus, null, null, dir, dir.resolve("active"), () -> null, 5_000L);
      var producer = mock(DefaultWorkerAppServices.class);
      when(producer.prepareQueryServingSuccessor(any(), any(), any())).thenAnswer(call -> {
        var fresh = mock(DefaultWorkerAppServices.class);
        when(fresh.ingestService()).thenReturn(
            mock(io.justsearch.indexerworker.services.WorkerIngestService.class));
        when(producer.prepareProducerTransferTo(fresh)).thenReturn(
            mock(DefaultWorkerAppServices.ProducerTransfer.class));
        return fresh;
      });
      Class<?> viewType = Class.forName(KnowledgeServer.class.getName() + "$ServingView");
      var constructor = viewType.getDeclaredConstructors()[0];
      constructor.setAccessible(true);
      Object view = constructor.newInstance(producer, runtime, runtime, dir.resolve("active"));
      attach(view, "attachEncoderSet", EncoderSet.class, index);
      attach(view, "attachQueryRoleSet", QueryRoleSet.class, queryOff);
      set("servingView", view);
      set("initialModelSelection", generation);
      set("initialEncoderSet", index);
      set("initialQueryRoleSet", queryOff);
      set("startupConfiguration", configuration);
      server.appServices = producer;
      server.startDeferredModelInitialization(() -> {});
      server.deferredModelInit.join();
    }

    /** The composed query roles: a reranker whose CUDA session realizes CUDA. */
    InferenceSurface surface(EncoderConfigurationProjection projection,
        QueryRoleSelection selection) throws Exception {
      var sessions = mock(io.justsearch.ort.SessionHandle.class);
      var tokenizer = mock(io.justsearch.reranker.RerankerTokenizer.class);
      when(tokenizer.encodePairs(anyString(), any(String[].class))).thenReturn(
          new io.justsearch.reranker.RerankerTokenizer.EncodedBatch(
              new long[][] {{1, 2}}, new long[][] {{1, 1}}, new long[][] {{0, 0}}, 1, 2, 0, 2));
      var nativeSession = mock(ai.onnxruntime.OrtSession.class);
      var nativeResult = mock(ai.onnxruntime.OrtSession.Result.class);
      var scores = mock(ai.onnxruntime.OnnxValue.class);
      when(scores.getValue()).thenReturn(new float[][] {{0.25f}});
      when(nativeResult.get(0)).thenReturn(scores);
      when(nativeSession.run(anyMap())).thenReturn(nativeResult);
      when(sessions.environment()).thenReturn(ai.onnxruntime.OrtEnvironment.getEnvironment());
      when(sessions.acquire(any())).thenReturn(new io.justsearch.ort.SessionHandle.Lease(
          nativeSession, null, () -> {}, false, io.justsearch.ort.OrtRunRecorder.NOOP));
      when(sessions.isGpuAvailable()).thenReturn(true);
      when(sessions.retirementStatus())
          .thenReturn(io.justsearch.ort.SessionHandle.RetirementStatus.RETIRED);
      var assembly = new io.justsearch.reranker.RerankerAssembly(sessions,
          new io.justsearch.reranker.RerankerShape(512, false), tokenizer);
      return new InferenceSurface(Optional.empty(), Optional.empty(), Optional.of(assembly),
          Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(sessions),
          new InferenceSurface.ComponentObservation(Optional.of(projection.queryDigest()),
              Set.of(EncoderRole.RERANKER), Set.of(), Optional.of(selection)));
    }

    private void set(String name, Object value) throws Exception {
      var field = KnowledgeServer.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(server, value);
    }

    private static void attach(Object view, String name, Class<?> type, Object value)
        throws Exception {
      var method = view.getClass().getDeclaredMethod(name, type);
      method.setAccessible(true);
      method.invoke(view, value);
    }

    @Override
    public void close() throws Exception {
      server.close();
      executors.close();
    }
  }
}
